package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Per-room university simulation with full bot lifecycle management.
 *
 * Each RoomBot carries a BotLifecycleState (in lifecycleJson) encoding its
 * genome, age, research qualification, and social state. This service:
 *   - Ages all bots and transitions JUVENILE→ADULT→ELDER each tick
 *   - Runs competency tests every TERM_PAPERS publications to decide who researches
 *   - Runs one research cycle per interval (author + peer reviewers from QUALIFIED bots)
 *   - Processes elder merges (higher-fitness bot absorbs lower, retires it)
 *   - Facilitates dating between ADULT bots to spawn child bots
 *   - Queues high-score papers for LLM genome mentoring (via callback)
 */
@Service
@ConditionalOnProperty(name = "app.university.enabled", havingValue = "true", matchIfMissing = false)
public class UniversityService {

    private static final Logger log = LoggerFactory.getLogger(UniversityService.class);

    // Research cycle constants
    private static final int REVIEWERS_PER_PAPER    = 2;
    private static final int TERM_PAPERS            = 30;    // publications before next competency test
    private static final int MAX_PAPERS_PER_ROOM    = 500;
    private static final int TOURNAMENT_K           = 3;
    private static final double REVIEW_TOLERANCE    = 0.05;  // score-based, not value-based
    private static final double COMPETENCY_THRESHOLD = 0.60;
    // Trigger is authorScore (result quality), not peerScore (reviewer reputation).
    // 0.45 ≈ sigmoid(1.0) × 0.9 — fires for genuinely good work from the first generation.
    private static final double MENTOR_SCORE_THRESHOLD = 0.45;

    // Max pending drafts: if backlog reaches this, skip the research cycle this tick.
    // Keeps paper output naturally paced to LLM throughput with no build-up.
    private final int     maxDraftBacklog;
    private final boolean peerReviewEnabled;

    // Dating constants (static defaults; runtime uses dynamic values from computePopulationParams)
    private static final long   DATING_COOLDOWN_TICKS   = 200L;
    private static final double DATING_BASE_PROB        = 0.05;
    private static final int    POPULATION_K_DEFAULT    = 200;

    private record PopulationParams(int K, long adultToElder, long datingCooldown,
                                    double scoreVariance, double fillRatio, double educationFactor) {}

    // Per-room in-memory counters. Paper counters are lazily seeded from existing
    // on-disk research files so restarts keep IDs unique.
    private final ConcurrentHashMap<UUID, List<ResearchPaper>> published     = new ConcurrentHashMap<>();
    private final AnomalyTracker                               anomalyTracker = new AnomalyTracker();
    private final ConcurrentHashMap<UUID, Long>                lastTickAt    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>             papersSinceTerm = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>                paperCounter  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>             totalRejected = new ConcurrentHashMap<>();
    private final AtomicInteger                                draftQueueSize = new AtomicInteger(0);

    private record ReplicationRequest(UUID roomId, String paperId, String domain,
                                       int topicIndex, int param1, double param2,
                                       double originalScore) {}

    private static final Pattern CITED_PAPER_PATTERN = Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    // Score gap that triggers a DISPUTED status on failed replication
    private static final double REPLICATION_FAIL_THRESHOLD = 0.15;
    private static final double ANOMALY_REPLICATION_Z      = 3.0;
    private static final double BRIDGE_SCORE_THRESHOLD     = 0.38;

    private final ConcurrentLinkedQueue<ReplicationRequest> replicationQueue = new ConcurrentLinkedQueue<>();

    private static final DateTimeFormatter FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final Pattern PAPER_SUFFIX_PATTERN =
            Pattern.compile("P-[0-9a-fA-F]{4}-(\\d+)");

    private final long          researchIntervalMs;
    private final Path          researchOutputDir;
    private final RoomBotRepository botRepository;
    private final ObjectMapper  objectMapper;

    // Optional callbacks set by LlmService — avoids circular Spring bean dependency
    private volatile java.util.function.BiConsumer<RoomBot, List<ResearchPaper>> mentoringCallback;
    private volatile java.util.function.Consumer<PaperDraft>                     narratingCallback;
    // Formulation: (domain, topicName, genome, recentPapers) -> FormulationResult (nullable)
    @SuppressWarnings("unchecked")
    private volatile java.util.function.Function<Object[], Object>               formulationCallback;

    public UniversityService(
            @Value("${app.university.researchIntervalMs:15000}") long researchIntervalMs,
            @Value("${app.university.researchOutputDir:research}") String researchOutputDir,
            @Value("${app.university.maxDraftBacklog:3}") int maxDraftBacklog,
            @Value("${app.university.peerReviewEnabled:true}") boolean peerReviewEnabled,
            RoomBotRepository botRepository,
            ObjectMapper objectMapper) {
        this.researchIntervalMs  = researchIntervalMs;
        this.researchOutputDir   = Paths.get(researchOutputDir);
        this.maxDraftBacklog     = maxDraftBacklog;
        this.peerReviewEnabled   = peerReviewEnabled;
        this.botRepository      = botRepository;
        this.objectMapper       = objectMapper;
        try {
            Files.createDirectories(this.researchOutputDir);
        } catch (IOException e) {
            log.warn("Could not create research output directory {}: {}", researchOutputDir, e.getMessage());
        }
    }

    public void setMentoringCallback(
            java.util.function.BiConsumer<RoomBot, List<ResearchPaper>> callback) {
        this.mentoringCallback = callback;
    }

    public void setNarratingCallback(java.util.function.Consumer<PaperDraft> callback) {
        this.narratingCallback = callback;
    }

    public void setFormulationCallback(java.util.function.Function<Object[], Object> callback) {
        this.formulationCallback = callback;
    }

    /**
     * Called by LlmService (on its narration thread) once the LLM has written the paper
     * narrative. Falls back to template text for any null/blank field.
     * Also fires genome mentoring for high-quality papers.
     */
    public void publishPaper(PaperDraft draft, NarrationResult narration) {
        String hypothesis = (narration.hypothesis() != null && !narration.hypothesis().isBlank())
                ? narration.hypothesis() : draft.templateHypothesis();
        String findings   = (narration.findings()  != null && !narration.findings().isBlank())
                ? narration.findings()  : draft.rawFindings();
        String conclusion = (narration.conclusion() != null && !narration.conclusion().isBlank())
                ? narration.conclusion() : draft.templateConclusion();

        ResearchPaper paper = new ResearchPaper(
                draft.paperId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                draft.title(), hypothesis, draft.methodology(), findings, conclusion,
                draft.reviewerBotIds(), draft.peerScore(), "PUBLISHED",
                draft.gameTick(), draft.publishedAt(), draft.generation(), draft.anomalyZScore());

        addPaper(draft.roomId(), paper);
        draftQueueSize.decrementAndGet();
        log.info("university paper published paperId={} domain={} topic={} score={}",
                draft.paperId(), draft.domain(), draft.topicName(), draft.peerScore());

        FollowUpParams followUp = narration.followUp();
        if (followUp != null) {
            // Stamp this paper's methodology as the priorMethodology for the follow-up chain
            FollowUpParams withChain = new FollowUpParams(
                    followUp.param1(), followUp.param2(),
                    followUp.predictedScore(), draft.methodology());
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot -> {
                String json = bot.getLifecycleJson();
                if (json != null && !json.isBlank()) {
                    try {
                        BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                        ls.setPendingFollowUp(withChain);
                        bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                        botRepository.save(bot);
                        log.debug("university follow-up stored botId={} param1={} param2={} predicted={} priorMethod={}",
                                botId, withChain.param1(), withChain.param2(),
                                withChain.predictedScore(), withChain.priorMethodology());
                    } catch (Exception e) {
                        log.warn("university follow-up storage failed botId={}: {}", botId, e.getMessage());
                    }
                }
            });
        }

        // Citation tracking: parse cited paper IDs from narration text, boost cited authors
        processCitations(draft.roomId(), draft.paperId(), hypothesis, findings, conclusion);

        // Anomaly replication: queue high-z papers for independent verification
        if (Math.abs(draft.anomalyZScore()) >= ANOMALY_REPLICATION_Z && draft.param1() != null) {
            int topicIndex = resolveTopicIndex(draft.domain(), draft.topicName());
            if (topicIndex >= 0) {
                replicationQueue.offer(new ReplicationRequest(
                        draft.roomId(), draft.paperId(), draft.domain(), topicIndex,
                        draft.param1(), draft.param2(), draft.peerScore()));
                log.info("university replication queued paperId={} z={}", draft.paperId(), draft.anomalyZScore());
            }
        }

        // Cross-domain knowledge bridging: share productive params with specialists
        if ("INTERDISCIPLINARY".equals(draft.domain()) && draft.peerScore() >= BRIDGE_SCORE_THRESHOLD
                && draft.param1() != null) {
            bridgeKnowledge(draft);
        }

        if (draft.authorScore() >= MENTOR_SCORE_THRESHOLD && mentoringCallback != null) {
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot ->
                    mentoringCallback.accept(bot, getPublishedPapers(draft.roomId(), 20)));
        }
    }

    // ---- Citation tracking ----

    private void processCitations(UUID roomId, String thisPaperId,
                                   String hypothesis, String findings, String conclusion) {
        Set<String> cited = new LinkedHashSet<>();
        for (String text : new String[]{hypothesis, findings, conclusion}) {
            if (text == null) continue;
            Matcher m = CITED_PAPER_PATTERN.matcher(text);
            while (m.find()) {
                String id = m.group();
                if (!id.equals(thisPaperId)) cited.add(id);
            }
        }
        if (cited.isEmpty()) return;

        List<ResearchPaper> allPapers = published.getOrDefault(roomId, List.of());
        List<ResearchPaper> snapshot;
        synchronized (allPapers) { snapshot = new ArrayList<>(allPapers); }

        for (String citedId : cited) {
            snapshot.stream().filter(p -> citedId.equals(p.paperId())).findFirst()
                    .ifPresent(citedPaper -> {
                        try {
                            UUID authorId = UUID.fromString(citedPaper.authorBotId());
                            botRepository.findById(authorId).ifPresent(bot -> {
                                String json = bot.getLifecycleJson();
                                if (json == null || json.isBlank()) return;
                                try {
                                    BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                                    ls.setReputation(Math.min(1.0, ls.getReputation() + 0.03));
                                    bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                                    botRepository.save(bot);
                                    log.debug("citation boost: cited={} by={} author={}",
                                            citedId, thisPaperId, authorId);
                                } catch (Exception e) {
                                    log.warn("citation boost failed for cited={}: {}", citedId, e.getMessage());
                                }
                            });
                        } catch (IllegalArgumentException ignored) {}
                    });
        }
    }

    // ---- Cross-domain knowledge bridging ----

    /**
     * Component domains by interdisciplinary topic index.
     * Each topic bridges two domains; we share the productive params with bots in each.
     */
    private static final String[][] BRIDGE_DOMAINS = {
        {"MATHEMATICS", "PHYSICS"},    // topic 0: Collatz→RLC
        {"MATHEMATICS", "MATERIALS"},  // topic 1: PrimeGap→Thermal
        {"PHYSICS",     "MATERIALS"},  // topic 2: RLC→Fatigue
        {"MATHEMATICS", "MATERIALS"},  // topic 3: Happy→Packing
        {"PHYSICS",     "MATERIALS"},  // topic 4: VdW→Composite
    };

    private void bridgeKnowledge(PaperDraft draft) {
        int topicIndex = resolveTopicIndex(draft.domain(), draft.topicName());
        if (topicIndex < 0 || topicIndex >= BRIDGE_DOMAINS.length) return;
        String[] domains = BRIDGE_DOMAINS[topicIndex];

        List<RoomBot> roomBots = botRepository.findByRoom_IdAndStatus(
                draft.roomId(), com.emergentterrain.domain.RoomBot.BotStatus.ACTIVE);
        Random rng = new Random(draft.paperId().hashCode());

        for (String targetDomain : domains) {
            // Pick one eligible specialist bot in this domain without a pending follow-up
            roomBots.stream()
                    .filter(bot -> !bot.getId().toString().equals(draft.authorBotId()))
                    .filter(bot -> {
                        String json = bot.getLifecycleJson();
                        if (json == null || json.isBlank()) return false;
                        try {
                            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                            return targetDomain.equals(ls.getGenome() != null ? ls.getGenome().getDomain() : "")
                                    && ls.getPendingFollowUp() == null;
                        } catch (Exception e) { return false; }
                    })
                    .sorted(Comparator.comparingInt(b -> rng.nextInt())) // random selection
                    .findFirst()
                    .ifPresent(bot -> {
                        String json = bot.getLifecycleJson();
                        try {
                            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                            ls.setPendingFollowUp(new FollowUpParams(
                                    draft.param1(), draft.param2(), null, "INTERDISCIPLINARY"));
                            bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                            botRepository.save(bot);
                            log.info("university bridge: interdisciplinary={} → {} bot={}",
                                    draft.paperId(), targetDomain, bot.getId());
                        } catch (Exception e) {
                            log.warn("university bridge failed domain={}: {}", targetDomain, e.getMessage());
                        }
                    });
        }
    }

    // ---- Replication and retraction ----

    private void processReplication(UUID roomId, List<Researcher> pool, Random rng,
                                     long gameTick, PopulationParams popParams) {
        ReplicationRequest req = replicationQueue.peek();
        if (req == null || !req.roomId().equals(roomId)) return;
        replicationQueue.poll();

        // Pick a replicator who is not the original author
        Researcher replicator = pool.stream()
                .filter(r -> !r.getBotId().equals(
                        published.getOrDefault(roomId, List.of()).stream()
                                .filter(p -> req.paperId().equals(p.paperId()))
                                .map(ResearchPaper::authorBotId).findFirst().orElse("")))
                .findFirst().orElse(pool.get(rng.nextInt(pool.size())));

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(
                replicator.getState().getGenome(), popParams.educationFactor());
        MathPhysicsEngine.ResearchResult replicaResult = MathPhysicsEngine.compute(
                req.domain(), req.topicIndex(), req.param1(), req.param2(), sp);
        double replicaScore = MathPhysicsEngine.scoreResult(req.domain(), req.topicIndex(),
                replicaResult.reviewValue(), sp);

        double scoreDiff = Math.abs(req.originalScore() - replicaScore);
        boolean failed = scoreDiff > REPLICATION_FAIL_THRESHOLD;
        log.info("university replication paperId={} original={} replica={} diff={} failed={}",
                req.paperId(), req.originalScore(), replicaScore, scoreDiff, failed);

        if (failed) {
            markPaperDisputed(roomId, req.paperId(), replicator.getBotId(), replicaScore);
        }
    }

    private void markPaperDisputed(UUID roomId, String paperId,
                                    String replicatorBotId, double replicaScore) {
        List<ResearchPaper> papers = published.get(roomId);
        if (papers == null) return;
        synchronized (papers) {
            for (int i = 0; i < papers.size(); i++) {
                ResearchPaper p = papers.get(i);
                if (!paperId.equals(p.paperId())) continue;
                // Replace with DISPUTED status
                ResearchPaper disputed = new ResearchPaper(
                        p.paperId(), p.authorBotId(), p.domain(), p.topic(), p.title(),
                        p.hypothesis(), p.methodology(), p.findings(),
                        p.conclusion() + " [REPLICATION NOTE: Independent replication by bot-"
                                + replicatorBotId.substring(0, 6)
                                + " produced score " + String.format("%.3f", replicaScore)
                                + " vs original " + String.format("%.3f", p.peerScore())
                                + " — result is under dispute.]",
                        p.reviewerBotIds(), p.peerScore(), "DISPUTED",
                        p.tick(), p.publishedAt(), p.generation(), p.anomalyZScore());
                papers.set(i, disputed);
                overwritePaperFile(roomId, disputed);
                // Penalise original author's reputation
                try {
                    UUID authorId = UUID.fromString(p.authorBotId());
                    botRepository.findById(authorId).ifPresent(bot -> {
                        String json = bot.getLifecycleJson();
                        if (json == null || json.isBlank()) return;
                        try {
                            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                            ls.setReputation(Math.max(0.0, ls.getReputation() - 0.08));
                            bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                            botRepository.save(bot);
                        } catch (Exception e) {
                            log.warn("dispute reputation penalty failed: {}", e.getMessage());
                        }
                    });
                } catch (IllegalArgumentException ignored) {}
                log.info("university paper DISPUTED paperId={}", paperId);
                break;
            }
        }
    }

    // ---- Topic index resolution ----

    private static int resolveTopicIndex(String domain, String topicName) {
        String[] topics = switch (domain) {
            case "PHYSICS"           -> new String[]{"Projectile Drag Optimisation", "Pendulum Breakdown Angle",
                                                    "Van der Waals Crossover", "Diffraction Order Survey",
                                                    "RLC Damping Transition"};
            case "MATERIALS"         -> new String[]{"Composite Failure Mode", "Binary Sphere Packing",
                                                    "Stiffness Crossover Volume Fraction", "Arrhenius Rule Validity",
                                                    "Thermal Stress Safety Window"};
            case "INTERDISCIPLINARY" -> new String[]{"Collatz-RLC Depth Resonance", "Prime-Gap Defect Thermal Loading",
                                                    "Resonant Thermal Fatigue Window", "Happy-Number Packing Anomaly",
                                                    "Van-der-Waals Composite Crossover"};
            default                  -> new String[]{"Prime Constellation Search", "Collatz Stopping Time",
                                                    "Goldbach Partition Survey", "Aliquot Sequence Behaviour",
                                                    "Happy Number Distribution"};
        };
        for (int i = 0; i < topics.length; i++) {
            if (topics[i].equalsIgnoreCase(topicName)) return i;
        }
        return -1;
    }

    // ---- Public API ----

    public void maybeTick(UUID roomId, List<RoomBot> activeBots, long gameTick) {
        maybeTick(roomId, activeBots, gameTick, null);
    }

    public void maybeTick(UUID roomId, List<RoomBot> activeBots, long gameTick,
                          GovernmentState govState) {
        long now = System.currentTimeMillis();
        Long last = lastTickAt.get(roomId);
        if (last != null && now - last < researchIntervalMs) return;
        lastTickAt.put(roomId, now);

        // Only work with ACTIVE bots
        List<RoomBot> bots = activeBots.stream()
                .filter(b -> b.getStatus() == RoomBot.BotStatus.ACTIVE)
                .collect(Collectors.toList());
        if (bots.isEmpty()) return;

        // Load lifecycle states, initialising any bot that doesn't have one yet
        Map<UUID, BotLifecycleState> states = loadStates(bots, gameTick);

        // Compute dynamic population parameters from government state + current population
        PopulationParams params = computePopulationParams(bots, govState, roomId);

        // Age all bots using dynamic adult lifespan threshold
        List<RoomBot> dirty = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            ls.incrementAge(BotLifecycleState.JUVENILE_TO_ADULT, params.adultToElder());
            ls.decayReputation();
            dirty.add(bot);
        }

        // Process elder merges before research
        processElderMerges(bots, states, dirty, gameTick);

        // Check if it's time for a competency test (term boundary)
        int termPapers = papersSinceTerm.getOrDefault(roomId, 0);
        if (termPapers >= TERM_PAPERS) {
            papersSinceTerm.put(roomId, 0);
            runCompetencyTests(bots, states, gameTick, dirty);
        }

        // Build qualified researcher pool (ADULT or ELDER, QUALIFIED).
        // Skip research this tick if drafts are already queued — pace output to LLM throughput.
        List<Researcher> pool = buildResearcherPool(bots, states);
        if (!pool.isEmpty()) {
            if (draftQueueSize.get() < maxDraftBacklog) {
                Random rng = new Random(gameTick * 1337L + roomId.getLeastSignificantBits());
                runResearchCycle(roomId, pool, rng, gameTick, params);
            } else {
                log.debug("university skipping research cycle — draft backlog={}", draftQueueSize.get());
            }
        }

        // Replication pass — one pending request per tick, uses the qualified researcher pool
        List<Researcher> poolForReplication = buildResearcherPool(bots, states);
        if (!poolForReplication.isEmpty()) {
            Random rng2 = new Random(gameTick * 7919L + roomId.getMostSignificantBits());
            processReplication(roomId, poolForReplication, rng2, gameTick, params);
        }

        // Dating pass with dynamic K and cooldown
        processDating(bots, states, dirty, gameTick, params);

        // Persist lifecycle state into each bot's lifecycleJson before saving
        persistStates(bots, states);
        if (!dirty.isEmpty()) {
            botRepository.saveAll(dirty);
        }
    }

    public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
        List<ResearchPaper> all = published.get(roomId);
        if (all == null) return List.of();
        List<ResearchPaper> copy;
        synchronized (all) {
            if (all.isEmpty()) return List.of();
            copy = new ArrayList<>(all);
        }
        int fromIndex = Math.max(0, copy.size() - limit);
        List<ResearchPaper> slice = new ArrayList<>(copy.subList(fromIndex, copy.size()));
        Collections.reverse(slice);
        return slice;
    }

    public Map<String, Object> getStats(UUID roomId) {
        List<RoomBot> bots = botRepository.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE);
        Map<UUID, BotLifecycleState> states = loadStatesQuiet(bots);

        List<ResearchPaper> papers = published.getOrDefault(roomId, List.of());
        Map<String, Long> byDomain = papers.stream()
                .collect(Collectors.groupingBy(ResearchPaper::domain, Collectors.counting()));
        long disputed = papers.stream().filter(p -> "DISPUTED".equals(p.status())).count();

        OptionalDouble avgScore = papers.stream().mapToDouble(ResearchPaper::peerScore).average();
        int maxGen = states.values().stream()
                .mapToInt(s -> s.getGenome() != null ? s.getGenome().getGeneration() : 0)
                .max().orElse(0);
        double avgRep = states.values().stream().mapToDouble(BotLifecycleState::getReputation)
                .average().orElse(0);
        int totalRej = totalRejected.getOrDefault(roomId, 0);
        long qualified = states.values().stream()
                .filter(s -> s.getResearchStatus() == BotLifecycleState.ResearchStatus.QUALIFIED).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPublished",    papers.size());
        stats.put("totalRejected",     totalRej);
        stats.put("researcherCount",   bots.size());
        stats.put("qualifiedCount",    qualified);
        stats.put("maxGeneration",     maxGen);
        stats.put("avgReputation",     Math.round(avgRep * 1000.0) / 1000.0);
        stats.put("avgPeerScore",      avgScore.isPresent()
                ? Math.round(avgScore.getAsDouble() * 1000.0) / 1000.0 : 0.0);
        stats.put("byDomain",          byDomain);
        stats.put("disputed",          disputed);
        stats.put("replicationPending", replicationQueue.size());
        return stats;
    }

    // ---- Dynamic population ecology ----

    private PopulationParams computePopulationParams(List<RoomBot> bots,
                                                      GovernmentState govState,
                                                      UUID roomId) {
        double educationFactor = 0.3;
        double healthFactor    = 0.3;
        double trustFactor     = 0.5;
        double serviceFactor   = 0.5;

        if (govState != null && govState.getFederal() != null) {
            GovernmentState.BudgetState budget = govState.getFederal().getBudget();
            if (budget != null) {
                educationFactor = Math.min(1.0, budget.getEducation() / 100_000.0);
                healthFactor    = Math.min(1.0, budget.getHealth()    / 100_000.0);
            }
            trustFactor   = Math.min(1.0, Math.max(0.0, govState.getFederal().getPublicTrust()));
            serviceFactor = Math.min(1.0, Math.max(0.0, govState.getFederal().getServiceAccess()));
        }

        // K: 60 (bare minimum) up to 1200 (thriving society)
        int K = (int)(60 + educationFactor * 600 + trustFactor * 300 + serviceFactor * 240);

        // Adult lifespan: 800 base ticks + health/education investment (800-5800 ticks)
        long adultToElder = (long)(800 + healthFactor * 3000 + educationFactor * 2000);

        // Evolutionary pressure: high score variance → shorter generations (faster exploration)
        List<ResearchPaper> recent = getPublishedPapers(roomId, 20);
        double avg = recent.stream().mapToDouble(ResearchPaper::peerScore).average().orElse(0.2);
        double variance = recent.stream()
                .mapToDouble(p -> (p.peerScore() - avg) * (p.peerScore() - avg))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        // stdDev near 0.15 is neutral; higher → shorten generations, lower → extend
        adultToElder += (long)((0.15 - stdDev) * 1000);
        adultToElder = Math.max(800, Math.min(6000, adultToElder));

        // Dating cooldown: quadratic braking as population approaches K
        double fillRatio = K > 0 ? Math.min(1.0, (double) bots.size() / K) : 1.0;
        long datingCooldown = (long)(200 + fillRatio * fillRatio * 1800);

        return new PopulationParams(K, adultToElder, datingCooldown, stdDev, fillRatio, educationFactor);
    }

    // ---- Lifecycle: competency test ----

    private void runCompetencyTests(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                                    long gameTick, List<RoomBot> dirty) {
        // Derive test puzzle deterministically from gameTick so all bots face the same instance
        String[] domains = BotGenome.DOMAINS;
        String testDomain = domains[(int) (gameTick % domains.length)];
        int topicIndex    = (int) ((gameTick / 3) % 5);
        int param1        = 10 + (int) Math.abs(gameTick % 90);
        double param2     = 1.0 + Math.abs((gameTick >> 16) % 9);

        Random rng = new Random(gameTick);
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls.getLifecycle() == BotLifecycleState.Phase.JUVENILE) continue;

            double score = runCompetencyPuzzle(ls.getGenome(), testDomain, topicIndex, param1, param2, rng);
            ls.setLastCompetencyScore(score);
            ls.setLastCompetencyTick(gameTick);
            ls.setResearchStatus(score >= COMPETENCY_THRESHOLD
                    ? BotLifecycleState.ResearchStatus.QUALIFIED
                    : BotLifecycleState.ResearchStatus.UNQUALIFIED);

        }
        log.info("university competency test domain={} topic={} bots={}",
                testDomain, topicIndex, bots.size());
    }

    private double runCompetencyPuzzle(BotGenome genome, String domain, int topicIndex,
                                       int param1, double param2, Random rng) {
        if (genome == null) return 0.0;
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(genome);
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);
        double score = MathPhysicsEngine.scoreResult(domain, topicIndex, result.reviewValue(), sp);
        return Math.max(0.0, score + rng.nextGaussian() * 0.05);
    }

    // ---- Lifecycle: elder merge ----

    private void processElderMerges(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                                    List<RoomBot> dirty, long gameTick) {
        List<RoomBot> elders = bots.stream()
                .filter(b -> states.get(b.getId()).getLifecycle() == BotLifecycleState.Phase.ELDER)
                .collect(Collectors.toList());
        if (elders.size() < 2) return;

        // Sort by fitness ascending; pair up lowest-fitness bots
        elders.sort(Comparator.comparingDouble(b -> states.get(b.getId()).fitness()));

        // Process one merge per tick to avoid large batch changes
        RoomBot botB = elders.get(0);  // lowest fitness — will be retired
        RoomBot botA = elders.get(1);  // survives, absorbs B

        BotLifecycleState stateA = states.get(botA.getId());
        BotLifecycleState stateB = states.get(botB.getId());

        BotGenome merged = BotGenome.merge(stateA.getGenome(), stateB.getGenome());
        stateA.setGenome(merged);
        // Reset age so the survivor continues as ADULT, not immediately re-entering merge queue
        stateA.setAgeTicks(BotLifecycleState.JUVENILE_TO_ADULT);
        stateA.setLifecycle(BotLifecycleState.Phase.ADULT);

        botB.setStatus(RoomBot.BotStatus.RETIRED);
        log.info("university merge: botA={} absorbed botB={} at tick={}",
                botA.getId(), botB.getId(), gameTick);
    }

    // ---- Lifecycle: dating ----

    private void processDating(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                                List<RoomBot> dirty, long gameTick, PopulationParams params) {
        if (bots.size() >= params.K()) return;

        long cooldown = params.datingCooldown();
        List<RoomBot> datingCandidates = bots.stream()
                .filter(b -> {
                    BotLifecycleState ls = states.get(b.getId());
                    return ls.getLifecycle() == BotLifecycleState.Phase.ADULT
                            && (ls.getLastDatedAt() < 0 || gameTick - ls.getLastDatedAt() > cooldown);
                })
                .collect(Collectors.toList());
        if (datingCandidates.size() < 2) return;

        Random rng = new Random(gameTick ^ 0xDA7E5EEDL);
        Collections.shuffle(datingCandidates, rng);

        // Try one pair per tick
        RoomBot botA = datingCandidates.get(0);
        RoomBot botB = datingCandidates.get(1);
        BotLifecycleState sA = states.get(botA.getId());
        BotLifecycleState sB = states.get(botB.getId());

        double datingProb = sA.getGenome().getExplorationRate()
                          * sB.getGenome().getExplorationRate()
                          * DATING_BASE_PROB;
        if (rng.nextDouble() > datingProb) return;

        // Create child
        BotGenome childGenome = BotGenome.crossover(
                sA.getGenome(), sB.getGenome(), rng, sA.fitness(), sB.fitness());
        childGenome.mutate(rng);

        BotLifecycleState childState = new BotLifecycleState();
        childState.setGenome(childGenome);
        childState.setReputation(0.1);

        RoomBot child = new RoomBot();
        child.setRoom(botA.getRoom());
        child.setType("AGENT-C" + gameTick);
        child.setStatus(RoomBot.BotStatus.ACTIVE);
        // Position at midpoint of parents
        if (botA.getX() != null && botB.getX() != null) {
            child.setX((botA.getX() + botB.getX()) / 2);
            child.setY((botA.getY() + botB.getY()) / 2);
        }
        child.setLifecycleJson(toJson(childState));
        botRepository.save(child);

        sA.setLastDatedAt(gameTick);
        sB.setLastDatedAt(gameTick);
        log.info("university dating: parents={},{} child={} at tick={}",
                botA.getId(), botB.getId(), child.getId(), gameTick);
    }

    // ---- Research cycle ----

    private void runResearchCycle(UUID roomId, List<Researcher> pool, Random rng, long gameTick,
                                  PopulationParams popParams) {
        Researcher author = tournamentSelect(pool, rng);

        DynamicProblem directive = author.getState().getPendingDirective();
        if (directive != null) {
            author.getState().setPendingDirective(null);
            runDynamicResearchCycle(roomId, author, pool, directive, rng, gameTick);
            return;
        }

        int topicIndex = author.selectTopic(rng);
        String domain  = author.getDomain();

        FollowUpParams pendingFollowUp = author.getState().getPendingFollowUp();
        boolean isFollowUp = (pendingFollowUp != null);
        int    param1;
        double param2;
        Double predictedScore   = null;
        String priorMethodology = null;
        if (isFollowUp) {
            param1          = pendingFollowUp.param1();
            param2          = pendingFollowUp.param2();
            predictedScore  = pendingFollowUp.predictedScore();
            priorMethodology = pendingFollowUp.priorMethodology();
            author.getState().setPendingFollowUp(null);
            log.debug("university consuming follow-up botId={} param1={} param2={} predicted={}",
                    author.getBotId(), param1, param2, predictedScore);
        } else {
            // Ask LLM to choose specific params based on prior work; fall back to genome sampling.
            String topicNameForFormulation = MathPhysicsEngine.topicName(domain, topicIndex);
            List<ResearchPaper> recentForFormulation = getPublishedPapers(roomId, 6);
            Object formResult = (formulationCallback != null)
                    ? formulationCallback.apply(new Object[]{
                            domain, topicNameForFormulation,
                            author.getState().getGenome(), recentForFormulation})
                    : null;
            if (formResult instanceof com.emergentterrain.service.LlmService.FormulationResult fr) {
                param1 = fr.param1();
                param2 = fr.param2();
                log.debug("university formulation used botId={} param1={} param2={} question={}",
                        author.getBotId(), param1, param2, fr.researchQuestion());
            } else {
                param1 = author.getState().getGenome().sampleParam1(rng);
                param2 = author.getState().getGenome().sampleParam2(rng);
            }
        }

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(
                author.getState().getGenome(), popParams.educationFactor());
        MathPhysicsEngine.ResearchResult result = MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);

        boolean authorError         = rng.nextDouble() < author.errorRate();
        double  reportedReviewValue = authorError
                ? result.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.3)
                : result.reviewValue();
        double  authorScore = MathPhysicsEngine.scoreResult(domain, topicIndex, reportedReviewValue, sp);

        List<String> reviewerIds     = new ArrayList<>();
        Set<String>  usedReviewerIds = new HashSet<>();
        boolean reviewPassed         = result.theoryConsistent();
        double  totalReviewScore     = 0;
        int     reviewCount          = 0;

        if (peerReviewEnabled) {
            for (int i = 0; i < REVIEWERS_PER_PAPER && reviewPassed; i++) {
                Researcher reviewer      = pickReviewer(pool, author, usedReviewerIds, rng);
                usedReviewerIds.add(reviewer.getBotId());
                boolean    reviewerError = rng.nextDouble() < reviewer.errorRate();

                MathPhysicsEngine.ResearchResult reviewResult =
                        MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);
                double reviewValue = reviewerError
                        ? reviewResult.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.15)
                        : reviewResult.reviewValue();
                double reviewScore = MathPhysicsEngine.scoreResult(domain, topicIndex, reviewValue, sp);

                boolean agrees = Math.abs(authorScore - reviewScore)
                        < reviewer.getState().getGenome().getPeerTolerance();
                if (!agrees) reviewPassed = false;

                reviewerIds.add(reviewer.getBotId());
                totalReviewScore += reviewer.getReputation() * (agrees ? 1.0 : 0.3);
                reviewCount++;
            }
        }

        double peerScore = reviewCount > 0 ? totalReviewScore / reviewCount : authorScore;

        if (reviewPassed) {
            String paperId    = nextPaperId(roomId);
            String topicName  = MathPhysicsEngine.topicName(domain, topicIndex);
            String title      = MathPhysicsEngine.generateTitle(domain, topicIndex, rng);
            String templateHypothesis = (result.hypothesis() != null && !result.hypothesis().isBlank())
                    ? result.hypothesis()
                    : MathPhysicsEngine.generateHypothesis(domain, topicIndex, param1, param2);
            String templateConclusion = MathPhysicsEngine.generateConclusion(result, true);
            double zScore = anomalyTracker.observe(domain, topicName, peerScore);

            // Reputation updated now — evolutionary pressure must not wait for LLM latency.
            papersSinceTerm.merge(roomId, 1, Integer::sum);
            author.recordPublished(peerScore);
            author.getState().getGenome().recordSuccess(param1, param2, peerScore);

            PaperDraft draft = new PaperDraft(
                    roomId, author.getBotId(), domain, topicName, title,
                    result.findings(), templateHypothesis, templateConclusion,
                    author.getMethodology(), Collections.unmodifiableList(reviewerIds),
                    peerScore, authorScore, zScore, gameTick, Instant.now(),
                    author.getGeneration(), paperId, param1, (double) param2, isFollowUp,
                    predictedScore, priorMethodology,
                    getPublishedPapers(roomId, 5));

            if (narratingCallback != null) {
                draftQueueSize.incrementAndGet();
                narratingCallback.accept(draft);
            } else {
                publishPaper(draft, new NarrationResult(templateHypothesis, result.findings(), templateConclusion, null));
            }
            log.debug("university draft queued paperId={} domain={} topic={} score={}",
                    paperId, domain, topicName, peerScore);
        } else {
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
        }

        // Reviewers get a small reputation bump for service regardless of outcome.
        for (String rid : reviewerIds) {
            pool.stream().filter(r -> r.getBotId().equals(rid)).findFirst()
                .ifPresent(r -> r.recordPublished(0.1));
        }
    }

    // ---- Dynamic research cycle ----

    private void runDynamicResearchCycle(UUID roomId, Researcher author, List<Researcher> pool,
                                         DynamicProblem directive, Random rng, long gameTick) {
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(author.getState().getGenome());
        MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(directive);

        boolean authorError  = rng.nextDouble() < author.errorRate();
        double reportedValue = authorError
                ? result.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.3)
                : result.reviewValue();
        double authorScore = MathPhysicsEngine.scoreResult("DYNAMIC", 0, reportedValue, sp);

        List<String> reviewerIds = new ArrayList<>();
        Set<String>  usedIds     = new HashSet<>();
        boolean reviewPassed     = result.theoryConsistent();
        double  totalReviewScore = 0;
        int     reviewCount      = 0;

        if (peerReviewEnabled) {
            for (int i = 0; i < REVIEWERS_PER_PAPER && reviewPassed; i++) {
                Researcher reviewer = pickReviewer(pool, author, usedIds, rng);
                usedIds.add(reviewer.getBotId());
                MathPhysicsEngine.ResearchResult reviewResult = DynamicComputeEngine.compute(directive);
                boolean reviewerError = rng.nextDouble() < reviewer.errorRate();
                double reviewValue = reviewerError
                        ? reviewResult.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.15)
                        : reviewResult.reviewValue();
                double reviewScore = MathPhysicsEngine.scoreResult("DYNAMIC", 0, reviewValue, sp);
                boolean agrees = Math.abs(authorScore - reviewScore)
                        < reviewer.getState().getGenome().getPeerTolerance();
                if (!agrees) reviewPassed = false;
                reviewerIds.add(reviewer.getBotId());
                totalReviewScore += reviewer.getReputation() * (agrees ? 1.0 : 0.3);
                reviewCount++;
            }
        }

        double peerScore = reviewCount > 0 ? totalReviewScore / reviewCount : authorScore;

        if (reviewPassed) {
            String paperId            = nextPaperId(roomId);
            String topicName          = dynamicTopicName(directive);
            String templateHypothesis = directive.hypothesis() != null
                    ? directive.hypothesis() : directive.description();
            String templateConclusion = MathPhysicsEngine.generateConclusion(result, true);

            // Dynamic problems are always number-theoretic; always classify as MATHEMATICS.
            double zScore = anomalyTracker.observe("MATHEMATICS", topicName, peerScore);

            papersSinceTerm.merge(roomId, 1, Integer::sum);
            author.recordPublished(peerScore);

            PaperDraft draft = new PaperDraft(
                    roomId, author.getBotId(), "MATHEMATICS", topicName,
                    dynamicPaperTitle(directive), result.findings(),
                    templateHypothesis, templateConclusion,
                    author.getMethodology(), Collections.unmodifiableList(reviewerIds),
                    peerScore, authorScore, zScore, gameTick, Instant.now(),
                    author.getGeneration(), paperId, null, null, false,
                    null, null,
                    getPublishedPapers(roomId, 5));

            if (narratingCallback != null) {
                draftQueueSize.incrementAndGet();
                narratingCallback.accept(draft);
            } else {
                publishPaper(draft, new NarrationResult(templateHypothesis, result.findings(), templateConclusion, null));
            }
            log.debug("university dynamic draft queued botId={} description=\"{}\" score={}",
                    author.getBotId(), directive.description(), peerScore);
        } else {
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
        }

        for (String rid : reviewerIds) {
            pool.stream().filter(r -> r.getBotId().equals(rid)).findFirst()
                .ifPresent(r -> r.recordPublished(0.1));
        }
    }

    // ---- Researcher pool ----

    private List<Researcher> buildResearcherPool(List<RoomBot> bots,
                                                  Map<UUID, BotLifecycleState> states) {
        List<Researcher> pool = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls.getLifecycle() == BotLifecycleState.Phase.JUVENILE) continue;
            if (ls.getResearchStatus() != BotLifecycleState.ResearchStatus.QUALIFIED) continue;
            pool.add(new Researcher(bot.getId().toString(), ls));
        }
        // If no one is qualified yet (early game), fall back to all non-juvenile bots
        if (pool.isEmpty()) {
            for (RoomBot bot : bots) {
                BotLifecycleState ls = states.get(bot.getId());
                if (ls.getLifecycle() != BotLifecycleState.Phase.JUVENILE) {
                    pool.add(new Researcher(bot.getId().toString(), ls));
                }
            }
        }
        return pool;
    }

    private Researcher tournamentSelect(List<Researcher> pool, Random rng) {
        Researcher best = null;
        for (int i = 0; i < TOURNAMENT_K; i++) {
            Researcher candidate = pool.get(rng.nextInt(pool.size()));
            if (best == null || candidate.fitness() > best.fitness()) best = candidate;
        }
        return best;
    }

    private Researcher pickReviewer(List<Researcher> pool, Researcher author,
                                    Set<String> alreadyPicked, Random rng) {
        List<Researcher> candidates = pool.stream()
                .filter(r -> !r.getBotId().equals(author.getBotId())
                          && !alreadyPicked.contains(r.getBotId()))
                .collect(Collectors.toList());
        // Fall back to excluding only the author if there aren't enough unique reviewers.
        if (candidates.isEmpty()) {
            candidates = pool.stream()
                    .filter(r -> !r.getBotId().equals(author.getBotId()))
                    .collect(Collectors.toList());
        }
        if (candidates.isEmpty()) return pool.get(rng.nextInt(pool.size()));
        List<Researcher> sameDomain = candidates.stream()
                .filter(r -> r.getDomain().equals(author.getDomain()))
                .collect(Collectors.toList());
        List<Researcher> pick = sameDomain.isEmpty() ? candidates : sameDomain;
        return pick.get(rng.nextInt(pick.size()));
    }

    // ---- Lifecycle state I/O ----

    private Map<UUID, BotLifecycleState> loadStates(List<RoomBot> bots, long gameTick) {
        Map<UUID, BotLifecycleState> states = new LinkedHashMap<>();
        int domainIdx = 0;
        for (RoomBot bot : bots) {
            boolean isNew = bot.getLifecycleJson() == null || bot.getLifecycleJson().isBlank();
            BotLifecycleState ls = readLifecycleState(bot);
            if (ls.getGenome() == null) {
                ls.setGenome(BotGenome.initialise(bot.getId(), domainIdx));
            }
            if (isNew) {
                // Bots without lifecycle state pre-date the system — start as ADULT, not JUVENILE
                ls.setAgeTicks(BotLifecycleState.JUVENILE_TO_ADULT);
                ls.setLifecycle(BotLifecycleState.Phase.ADULT);
            }
            states.put(bot.getId(), ls);
            domainIdx++;
        }
        return states;
    }

    private Map<UUID, BotLifecycleState> loadStatesQuiet(List<RoomBot> bots) {
        Map<UUID, BotLifecycleState> states = new LinkedHashMap<>();
        int idx = 0;
        for (RoomBot bot : bots) {
            BotLifecycleState ls = readLifecycleState(bot);
            if (ls.getGenome() == null) ls.setGenome(BotGenome.initialise(bot.getId(), idx));
            states.put(bot.getId(), ls);
            idx++;
        }
        return states;
    }

    private BotLifecycleState readLifecycleState(RoomBot bot) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return new BotLifecycleState();
        try {
            return objectMapper.readValue(json, BotLifecycleState.class);
        } catch (Exception e) {
            log.warn("Failed to parse lifecycleJson for bot={}: {}", bot.getId(), e.getMessage());
            return new BotLifecycleState();
        }
    }

    private void persistStates(List<RoomBot> bots, Map<UUID, BotLifecycleState> states) {
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls != null) {
                bot.setLifecycleJson(toJson(ls));
            }
        }
    }

    // ---- Dynamic paper title / topic helpers ----

    private static String dynamicPaperTitle(DynamicProblem d) {
        return "%s of %s Numbers in [%d, %d)".formatted(
                metricLabel(d.metric()), predicateLabel(d.predicate()),
                d.rangeStart(), d.rangeEnd());
    }

    private static String dynamicTopicName(DynamicProblem d) {
        String name = predicateLabel(d.predicate()) + " " + metricLabel(d.metric());
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    private static String predicateLabel(String predicate) {
        return predicate
                .replace("isPrime",           "Prime")
                .replace("isHappy",           "Happy")
                .replace("isAbundant",        "Abundant")
                .replace("isPerfect",         "Perfect")
                .replace("collatzTerminates", "Collatz-Terminating")
                .replace(" AND ", "-")
                .replace(" OR ",  " or ");
    }

    private static String metricLabel(String metric) {
        return switch (metric) {
            case "fraction_satisfying"  -> "Density";
            case "mean_of:collatzSteps" -> "Mean Collatz Depth";
            case "max_of:collatzSteps"  -> "Maximum Collatz Depth";
            case "mean_of:sigmaSum"     -> "Mean Divisor Sum";
            default                     -> metric;
        };
    }

    // ---- Helpers ----

    private void addPaper(UUID roomId, ResearchPaper paper) {
        List<ResearchPaper> roomPapers = published.computeIfAbsent(roomId, k -> new ArrayList<>());
        synchronized (roomPapers) {
            roomPapers.add(paper);
            if (roomPapers.size() > MAX_PAPERS_PER_ROOM) roomPapers.remove(0);
        }
        writePaperToFile(roomId, paper);
    }

    String nextPaperId(UUID roomId) {
        String roomPrefix = roomId.toString().substring(0, 4);
        long next = paperCounter.compute(roomId, (id, current) -> {
            long base = current != null ? current : loadMaxPaperNumber(id);
            return base + 1;
        });
        return "P-" + roomPrefix + "-" + next;
    }

    private long loadMaxPaperNumber(UUID roomId) {
        String roomDirName = roomId.toString().substring(0, 8);
        Path roomDir = researchOutputDir.resolve(roomDirName);
        if (!Files.isDirectory(roomDir)) return 0L;

        String paperPrefix = "P-" + roomId.toString().substring(0, 4) + "-";
        long max = 0L;
        try (var stream = Files.list(roomDir)) {
            Iterator<Path> paths = stream.iterator();
            while (paths.hasNext()) {
                String filename = paths.next().getFileName().toString();
                if (!filename.contains(paperPrefix)) continue;
                Matcher matcher = PAPER_SUFFIX_PATTERN.matcher(filename);
                while (matcher.find()) {
                    max = Math.max(max, Long.parseLong(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan existing research papers for room {}: {}", roomId, e.getMessage());
        }
        return max;
    }

    /** Overwrite a paper's markdown file in-place — used when status changes to DISPUTED. */
    private void overwritePaperFile(UUID roomId, ResearchPaper paper) {
        try {
            Path roomDir = researchOutputDir.resolve(roomId.toString().substring(0, 8));
            String topicSlug = paper.topic().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            String dateStr = FILE_DATE_FMT.format(paper.publishedAt());
            String filename = "%s_%s_%s_%s.md"
                    .formatted(dateStr, paper.domain().toLowerCase(), topicSlug, paper.paperId());
            Path file = roomDir.resolve(filename);
            if (Files.exists(file)) {
                Files.writeString(file, formatPaperAsMarkdown(roomId, paper));
                log.debug("university paper file overwritten paperId={} status={}", paper.paperId(), paper.status());
            }
        } catch (IOException e) {
            log.warn("Failed to overwrite paper file {}: {}", paper.paperId(), e.getMessage());
        }
    }

    private void writePaperToFile(UUID roomId, ResearchPaper paper) {
        try {
            // Directory: research/<roomId-prefix>/
            Path roomDir = researchOutputDir.resolve(roomId.toString().substring(0, 8));
            Files.createDirectories(roomDir);

            // Filename: <date>_<domain>_<topic-slug>_<paperId>.md
            String dateStr    = FILE_DATE_FMT.format(paper.publishedAt());
            String topicSlug  = paper.topic().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            String filename   = "%s_%s_%s_%s.md"
                    .formatted(dateStr, paper.domain().toLowerCase(), topicSlug, paper.paperId());
            Path file = roomDir.resolve(filename);

            String content = formatPaperAsMarkdown(roomId, paper);
            Files.writeString(file, content);
        } catch (IOException e) {
            log.warn("Failed to write research paper {} to file: {}", paper.paperId(), e.getMessage());
        }
    }

    private String formatPaperAsMarkdown(UUID roomId, ResearchPaper paper) {
        boolean disputed = "DISPUTED".equals(paper.status());
        String disputedBanner = disputed
                ? "\n> ⚠ **UNDER DISPUTE** — Independent replication produced a divergent result. "
                  + "This paper's findings are under review.\n"
                : "";
        return """
                # %s

                **Paper ID**: %s
                **Published**: %s
                **Room**: %s
                **Domain**: %s  |  **Topic**: %s
                **Methodology**: %s
                **Peer Score**: %.3f%s
                **Status**: %s
                **Generation**: %d
                **Author**: %s
                **Reviewers**: %s
                %s
                ---

                ## Hypothesis

                %s

                ## Findings

                %s

                ## Conclusion

                %s
                """.formatted(
                paper.title(),
                paper.paperId(),
                paper.publishedAt(),
                roomId,
                paper.domain(), paper.topic(),
                paper.methodology(),
                paper.peerScore(),
                Math.abs(paper.anomalyZScore()) >= 1.5
                        ? "  ⚑ anomaly z=%.2f".formatted(paper.anomalyZScore()) : "",
                paper.status(),
                paper.generation(),
                paper.authorBotId(),
                String.join(", ", paper.reviewerBotIds()),
                disputedBanner,
                paper.hypothesis(),
                paper.findings(),
                paper.conclusion());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise lifecycle state", e);
        }
    }

}
