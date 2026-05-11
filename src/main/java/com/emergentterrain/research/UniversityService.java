package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
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
    private static final int TERM_PAPERS            = 30;    // publications before next competency test
    private static final int MAX_PAPERS_PER_ROOM    = 500;
    private static final double MENTOR_SCORE_THRESHOLD = 0.30;

    // Max pending drafts: if backlog reaches this, skip the research cycle this tick.
    // Keeps paper output naturally paced to LLM throughput with no build-up.
    private final int     maxDraftBacklog;
    private final boolean peerReviewEnabled;

    // Per-room in-memory counters. Paper counters are lazily seeded from existing
    // on-disk research files so restarts keep IDs unique.
    private final ConcurrentHashMap<UUID, List<ResearchPaper>> published     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>                lastTickAt    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>                paperCounter  = new ConcurrentHashMap<>();
    private static final Pattern CITED_PAPER_PATTERN = Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    // (Replication threshold + queue moved to ReplicationService in Phase 0 Task 0.3b.)
    private static final double BRIDGE_SCORE_THRESHOLD = 0.38;

    private static final DateTimeFormatter FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final Pattern PAPER_SUFFIX_PATTERN =
            Pattern.compile("P-[0-9a-fA-F]{4}-(\\d+)");

    private final long          researchIntervalMs;
    private final Path          researchOutputDir;
    private final RoomBotRepository botRepository;
    private final ResearchPaperRepository papersRepository;
    private final PaperCitationRepository citationsRepository;
    private final PopulationEcologyService populationEcologyService;
    private final ReplicationService replicationService;
    private final BotLifecycleService botLifecycleService;
    private final ResearchCycleService researchCycleService;
    private final ResearchProgramService researchProgramService;
    private final ExpectationModelService expectationModelService;
    private final ExperimentPlannerService experimentPlannerService;
    private final InstitutionService institutionService;
    private final EpistemicMarketService epistemicMarketService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FundingAllocationService fundingAllocationService;
    private final ObjectMapper  objectMapper;

    /** Adapter that lets the cycle call back into this orchestrator. */
    private final ResearchCycleService.Context cycleContext = new ResearchCycleService.Context() {
        @Override public String nextPaperId(UUID roomId) { return UniversityService.this.nextPaperId(roomId); }
        @Override public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
            return UniversityService.this.getPublishedPapers(roomId, limit);
        }
        @Override public void publishPaper(PaperDraft draft, NarrationResult narration) {
            UniversityService.this.publishPaper(draft, narration);
        }
        @Override public java.util.function.Consumer<PaperDraft> narratingCallback() {
            return narratingCallback;
        }
        @Override public java.util.function.Function<Object[], Object> formulationCallback() {
            return formulationCallback;
        }
    };

    /** Tracks rooms whose in-memory cache has been seeded from the DB. */
    private final java.util.Set<UUID> hydratedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            ResearchPaperRepository papersRepository,
            PaperCitationRepository citationsRepository,
            PopulationEcologyService populationEcologyService,
            ReplicationService replicationService,
            BotLifecycleService botLifecycleService,
            ResearchCycleService researchCycleService,
            ResearchProgramService researchProgramService,
            ExpectationModelService expectationModelService,
            ExperimentPlannerService experimentPlannerService,
            InstitutionService institutionService,
            EpistemicMarketService epistemicMarketService,
            KnowledgeBaseService knowledgeBaseService,
            FundingAllocationService fundingAllocationService,
            @Value("${app.research.reviewer-routing.enabled:false}") boolean reviewerRoutingEnabled,
            @Value("${app.research.journal-review.enabled:false}") boolean journalReviewEnabled,
            ObjectMapper objectMapper) {
        this.researchIntervalMs       = researchIntervalMs;
        this.researchOutputDir        = Paths.get(researchOutputDir);
        this.maxDraftBacklog          = maxDraftBacklog;
        this.peerReviewEnabled        = peerReviewEnabled;
        this.botRepository            = botRepository;
        this.papersRepository         = papersRepository;
        this.citationsRepository      = citationsRepository;
        this.populationEcologyService = populationEcologyService;
        this.replicationService       = replicationService;
        this.botLifecycleService      = botLifecycleService;
        this.researchCycleService     = researchCycleService;
        this.researchProgramService   = researchProgramService;
        this.expectationModelService  = expectationModelService;
        this.experimentPlannerService = experimentPlannerService;
        this.institutionService       = institutionService;
        this.epistemicMarketService   = epistemicMarketService;
        this.knowledgeBaseService     = knowledgeBaseService;
        this.fundingAllocationService = fundingAllocationService;
        this.objectMapper             = objectMapper;
        // Phase 2 Iter 5 — hand the planner to the cycle so it can be consulted
        // before the formulation callback when the planner flag is on.
        this.researchCycleService.setExperimentPlannerService(experimentPlannerService);
        // Phase 4b — enable adversarial-reviewer routing when the flag is set.
        this.researchCycleService.setReviewerOrthogonalityEnabled(reviewerRoutingEnabled);
        if (reviewerRoutingEnabled) {
            log.info("UniversityService: reviewer-routing ENABLED — pickReviewer scores "
                    + "by topic-weight orthogonality instead of random within-domain pick.");
        }
        // Phase 4b — enable journal-mediated review when the flag is set. Hands
        // the cycle a reference to InstitutionService so it can look up the
        // journal's editor cohort during pickReviewer.
        this.researchCycleService.setInstitutionService(institutionService);
        this.researchCycleService.setJournalReviewEnabled(journalReviewEnabled);
        if (journalReviewEnabled) {
            log.info("UniversityService: journal-review ENABLED — pickReviewer narrows "
                    + "candidates to the top-{} journal editors first.", 5);
        }
        try {
            Files.createDirectories(this.researchOutputDir);
        } catch (IOException e) {
            log.warn("Could not create research output directory {}: {}", researchOutputDir, e.getMessage());
        }

        // Hand the replication service a cache-aware paper lookup so its mutations
        // are visible in the in-memory `published` cache without a re-read, and a
        // dispute listener so the markdown file stays in sync.
        this.replicationService.setPaperLookup(this::findCachedPaper);
        this.replicationService.setDisputeListener(p -> overwritePaperFile(p.roomId(), p));
    }

    /** Defensively parse an authorBotId string; returns null on malformed input. */
    private static UUID parseBotIdSafely(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** Cache-first paper lookup — falls through to the DB on a miss. */
    private Optional<ResearchPaper> findCachedPaper(String paperId) {
        for (List<ResearchPaper> bucket : published.values()) {
            synchronized (bucket) {
                for (ResearchPaper p : bucket) {
                    if (paperId.equals(p.paperId())) return Optional.of(p);
                }
            }
        }
        return papersRepository.findByPaperId(paperId);
    }

    /**
     * Lazily populate the in-memory cache for a room from the DB. Called from any read
     * path that needs the published list. Idempotent and thread-safe — first caller for
     * a room loads, subsequent calls are no-ops.
     */
    private List<ResearchPaper> roomPapers(UUID roomId) {
        if (hydratedRooms.add(roomId)) {
            List<ResearchPaper> fromDb = papersRepository.findByRoomIdOrderByTickAsc(roomId);
            if (!fromDb.isEmpty()) {
                List<ResearchPaper> bucket = published.computeIfAbsent(roomId, k -> new ArrayList<>());
                synchronized (bucket) {
                    bucket.clear();
                    bucket.addAll(fromDb);
                    if (bucket.size() > MAX_PAPERS_PER_ROOM) {
                        bucket.subList(0, bucket.size() - MAX_PAPERS_PER_ROOM).clear();
                    }
                }
                log.info("university hydrated cache from DB roomId={} papers={}", roomId, bucket.size());
            }
        }
        return published.computeIfAbsent(roomId, k -> new ArrayList<>());
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
        if (narration == null) {
            // Professor synthesis was blank — discard draft without publishing.
            researchCycleService.decrementDraftQueue();
            log.warn("university draft suppressed (blank professor synthesis) paperId={} domain={} topic={}",
                    draft.paperId(), draft.domain(), draft.topicName());
            return;
        }
        String hypothesis = (narration.hypothesis() != null && !narration.hypothesis().isBlank())
                ? narration.hypothesis() : draft.templateHypothesis();
        String findings   = (narration.findings()  != null && !narration.findings().isBlank())
                ? narration.findings()  : draft.rawFindings();
        String conclusion = (narration.conclusion() != null && !narration.conclusion().isBlank())
                ? narration.conclusion() : draft.templateConclusion();

        // Null results get no confidence boost (fixed ×0.92); others range ×0.92–×1.08
        boolean isNullResult = draft.resultClassification() == ResultClassification.NULL_RESULT;
        double confidenceFactor = isNullResult
                ? 0.92
                : (0.92 + 0.16 * Math.max(0.0, Math.min(1.0, narration.confidenceScore())));
        double adjustedScore = draft.peerScore() * confidenceFactor;

        String paperStatus = isNullResult ? "NULL_RESULT" : "PUBLISHED";

        ResearchPaper paper = new ResearchPaper(
                draft.paperId(), draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                draft.title(), hypothesis, draft.methodology(), findings, conclusion,
                draft.reviewerBotIds(), adjustedScore, paperStatus,
                draft.gameTick(), draft.publishedAt(), draft.generation(), draft.anomalyZScore(),
                narration.aiModel(), narration.callPath());

        // Phase 3 Iter 8: bootstrap institutions and stamp FKs onto the paper before
        // it lands in the cache + DB. No-op when the institutions flag is off.
        InstitutionService.BootstrapResult institutions =
                institutionService.bootstrapForPaper(draft.roomId(), draft.domain());
        if (institutions != null) {
            if (institutions.university() != null) {
                paper.setUniversityId(institutions.university().getId());
            }
            if (institutions.journal() != null) {
                paper.setJournalId(institutions.journal().getId());
                institutionService.recordJournalAcceptance(institutions.journal().getId());
            }
        }

        addPaper(draft.roomId(), paper);
        researchCycleService.decrementDraftQueue();
        log.info("university paper published paperId={} domain={} topic={} score={} confidence={} classification={} path=[{}]",
                draft.paperId(), draft.domain(), draft.topicName(),
                String.format("%.3f", adjustedScore), String.format("%.2f", narration.confidenceScore()),
                draft.resultClassification(),
                narration.callPath() != null ? narration.callPath() : "none");

        // Phase 1 Iter 4: attach the paper to a research program (no-op when the
        // app.research.programs.enabled flag is off).
        researchProgramService.onPaperPublished(paper, draft.resultClassification(), hypothesis);
        if (narration.followUp() != null) {
            UUID authorId = UUID.fromString(draft.authorBotId());
            researchProgramService.recordOpenQuestion(authorId, draft.domain(),
                    "Follow-up at param1=" + narration.followUp().param1()
                    + " param2=" + narration.followUp().param2());
        }

        // Phase 2 Iter 1: score the paper against the bot's running prior, then
        // fold the new sample in. High-surprise events get logged as a new open
        // question on the research program (no-op when programs flag is off).
        double observedSurpriseScore = 0.0;
        UUID authorBotIdForSurprise = parseBotIdSafely(draft.authorBotId());
        if (authorBotIdForSurprise != null) {
            SurpriseEvent surprise = expectationModelService.scoreExperiment(
                    authorBotIdForSurprise, paper, draft.preRegistration());
            if (surprise != null) {
                observedSurpriseScore = surprise.getSurpriseScore();
                researchProgramService.recordOpenQuestion(authorBotIdForSurprise, draft.domain(),
                        "Investigate surprise at paperId=" + paper.paperId()
                        + " (residual=" + String.format("%.3f", surprise.getResidual())
                        + ", score=" + String.format("%.2f", surprise.getSurpriseScore()) + ")");
            }
            expectationModelService.updatePrior(authorBotIdForSurprise, paper);
        }

        // Phase 2b: close the planner loop. When this draft was generated by the
        // experiment planner, decay the targeted question's uncertainty by an
        // amount that depends on how surprised we were by the outcome.
        if (draft.plannerQuestionId() != null) {
            experimentPlannerService.recordOutcome(
                    draft.plannerQuestionId(), observedSurpriseScore);
        }

        // Phase 3 Iter 7: resolve any open markets in this (room, domain, topic)
        // whose closing window has passed. Then open a new market on this paper
        // if it carries a falsifiable prediction. No-op when the markets flag
        // is off.
        epistemicMarketService.maybeResolveMarketsFor(paper, observedSurpriseScore);
        epistemicMarketService.openMarketFor(paper, draft.preRegistration());

        // Phase 4 Iter 9: persist structured findings extracted from the LLM
        // narration. No-op when the findings flag is off or the LLM didn't
        // produce a formal_findings block.
        knowledgeBaseService.persistFindings(
                paper, narration.formalFindings(), authorBotIdForSurprise);

        // Update bot state in one read+write: follow-up params and research hypothesis
        FollowUpParams followUp = narration.followUp();
        String newHypothesis = (conclusion != null && !conclusion.isBlank()) ? conclusion : null;
        if (followUp != null || newHypothesis != null) {
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot -> {
                String json = bot.getLifecycleJson();
                if (json != null && !json.isBlank()) {
                    try {
                        BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                        if (followUp != null) {
                            FollowUpParams withChain = new FollowUpParams(
                                    followUp.param1(), followUp.param2(),
                                    followUp.predictedScore(), draft.methodology());
                            ls.setPendingFollowUp(withChain);
                            log.debug("university follow-up stored botId={} param1={} param2={} predicted={} priorMethod={}",
                                    botId, withChain.param1(), withChain.param2(),
                                    withChain.predictedScore(), withChain.priorMethodology());
                        }
                        if (newHypothesis != null) {
                            ls.setResearchHypothesis(newHypothesis);
                        }
                        bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                        botRepository.save(bot);
                    } catch (Exception e) {
                        log.warn("university bot state update failed botId={}: {}", botId, e.getMessage());
                    }
                }
            });
        }

        // Citation tracking: prefer typed citations from the LLM, then flat IDs,
        // then regex extraction from the narrative text.
        processCitations(draft.roomId(), draft.paperId(),
                narration.typedCitations(), narration.citedPaperIds(),
                parseBotIdSafely(draft.authorBotId()),
                hypothesis, findings, conclusion);

        // Anomaly replication: only queue positive results — null results have no claim to verify.
        // The council can also force replication for any paper asserting a CONTRADICTS citation,
        // regardless of z-score, when app.research.replication-council.enabled is set.
        boolean anomalyTrigger = Math.abs(draft.anomalyZScore()) >= ReplicationService.ANOMALY_REPLICATION_Z;
        boolean councilTrigger = institutionService.councilWantsReplication(narration);
        if (!isNullResult && (anomalyTrigger || councilTrigger) && draft.param1() != null) {
            int topicIndex = resolveTopicIndex(draft.domain(), draft.topicName());
            if (topicIndex >= 0) {
                replicationService.enqueue(new ReplicationService.ReplicationRequest(
                        draft.roomId(), draft.paperId(), draft.domain(), topicIndex,
                        draft.param1(), draft.param2(), draft.peerScore()));
                institutionService.recordReplicationEnqueued(draft.roomId());
                log.info("university replication queued paperId={} z={} trigger={}",
                        draft.paperId(), draft.anomalyZScore(),
                        councilTrigger && !anomalyTrigger ? "council" : "anomaly");
            }
        }

        // Cross-domain knowledge bridging: only for positive, high-scoring results
        if (!isNullResult && "INTERDISCIPLINARY".equals(draft.domain())
                && draft.peerScore() >= BRIDGE_SCORE_THRESHOLD && draft.param1() != null) {
            bridgeKnowledge(draft);
        }

        if (draft.authorScore() >= MENTOR_SCORE_THRESHOLD && mentoringCallback != null) {
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot ->
                    mentoringCallback.accept(bot, getPublishedPapers(draft.roomId(), 40)));
        }
    }

    // ---- Citation tracking ----

    private void processCitations(UUID roomId, String thisPaperId,
                                   List<NarrationResult.TypedCitation> typedCitations,
                                   List<String> structuredCitedIds,
                                   UUID classifierBotId,
                                   String hypothesis, String findings, String conclusion) {
        Set<String> cited = new LinkedHashSet<>();
        // Phase 2b — typed citations win, then flat IDs, then regex scan as final fallback.
        Map<String, NarrationResult.TypedCitation> typedById = new LinkedHashMap<>();
        if (typedCitations != null) {
            for (NarrationResult.TypedCitation t : typedCitations) {
                if (t.paperId() == null || t.paperId().equals(thisPaperId)) continue;
                typedById.putIfAbsent(t.paperId(), t);
                cited.add(t.paperId());
            }
        }
        if (cited.isEmpty() && structuredCitedIds != null && !structuredCitedIds.isEmpty()) {
            for (String id : structuredCitedIds) {
                if (id != null && !id.equals(thisPaperId)) cited.add(id);
            }
        }
        if (cited.isEmpty()) {
            for (String text : new String[]{hypothesis, findings, conclusion}) {
                if (text == null) continue;
                Matcher m = CITED_PAPER_PATTERN.matcher(text);
                while (m.find()) {
                    String id = m.group();
                    if (!id.equals(thisPaperId)) cited.add(id);
                }
            }
        }
        if (cited.isEmpty()) return;

        // Persist edges with whatever typing we have. Untyped (heuristic) edges
        // default to EXTENDS with confidence 1.0 and a null classifier id.
        Instant now = Instant.now();
        for (String citedId : cited) {
            NarrationResult.TypedCitation t = typedById.get(citedId);
            try {
                if (t != null) {
                    citationsRepository.save(new PaperCitation(thisPaperId, citedId, roomId, now,
                            t.type(), classifierBotId, t.confidence()));
                } else {
                    citationsRepository.save(new PaperCitation(thisPaperId, citedId, roomId, now));
                }
            } catch (Exception e) {
                log.warn("citation edge persist failed citing={} cited={}: {}",
                        thisPaperId, citedId, e.getMessage());
            }
        }

        List<ResearchPaper> allPapers = roomPapers(roomId);
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

    // (Replication + dispute marking moved to ReplicationService in Phase 0 Task 0.3b.)

    // ---- Result classification ----

    /**
     * Deterministic classification of a computation result. Called after authorScore is known,
     * before peer review, before any LLM call. DEGENERATE results are rejected without a draft.
     * Classification is stored on PaperDraft and drives narration prompt framing and paper status.
     */
    static ResultClassification classifyResult(MathPhysicsEngine.ResearchResult result,
                                                double authorScore) {
        // Non-finite values or all-zero primary+secondary+review with no theory support = sentinel/fallback.
        // Note: theoryConsistent is respected — if the engine explicitly marks the result as theory-consistent,
        // zero values are a valid null result (e.g., "zero failures"), not a computation bug.
        if (!Double.isFinite(result.reviewValue())
                || (result.primaryValue() == 0.0 && result.secondaryValue() == 0.0
                    && result.reviewValue() == 0.0 && !result.theoryConsistent())) {
            return ResultClassification.DEGENERATE;
        }
        if (authorScore <= 0.0) return ResultClassification.DEGENERATE;

        // Very low score with no theory support = effectively null
        if (authorScore < 0.05) return ResultClassification.NULL_RESULT;

        return switch (result.claimStatus()) {
            case REFUTED      -> ResultClassification.NULL_RESULT;
            case SUPPORTED    -> ResultClassification.SUPPORTED;
            case PARTIAL      -> ResultClassification.PARTIALLY_SUPPORTED;
            case OBSERVATIONAL -> authorScore < 0.12
                    ? ResultClassification.NULL_RESULT
                    : ResultClassification.PARTIALLY_SUPPORTED;
        };
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
        Map<UUID, BotLifecycleState> states = botLifecycleService.loadStates(bots, gameTick);

        // Compute dynamic population parameters from government state + current population
        List<ResearchPaper> recentForEcology = getPublishedPapers(roomId, 20);
        PopulationEcologyService.PopulationParams params = populationEcologyService.computePopulationParams(bots, govState, recentForEcology);

        // Age all bots using dynamic adult lifespan threshold. Capture phase before
        // incrementAge so the reputation-history sampler can detect transitions.
        Map<UUID, BotLifecycleState.Phase> phaseBefore = new HashMap<>();
        List<RoomBot> dirty = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            phaseBefore.put(bot.getId(), ls.getLifecycle());
            ls.incrementAge(BotLifecycleState.JUVENILE_TO_ADULT, params.adultToElder());
            ls.decayReputation();
            dirty.add(bot);
        }

        // Phase 1 Iter 2 — sample reputation trajectory (cadence-throttled, per-bot).
        botLifecycleService.sampleReputationHistory(roomId, bots, states, gameTick, phaseBefore);

        // Process elder merges before research
        botLifecycleService.processElderMerges(bots, states, dirty, gameTick);

        // Check if it's time for a competency test (term boundary)
        int termPapers = researchCycleService.papersSinceTerm(roomId);
        if (termPapers >= TERM_PAPERS) {
            researchCycleService.resetPapersSinceTerm(roomId);
            botLifecycleService.runCompetencyTests(bots, states, gameTick, dirty);
        }

        // Build qualified researcher pool (ADULT or ELDER, QUALIFIED).
        // Skip research this tick if drafts are already queued — pace output to LLM throughput.
        List<Researcher> pool = botLifecycleService.buildResearcherPool(bots, states);
        if (!pool.isEmpty()) {
            if (researchCycleService.draftQueueSize() < maxDraftBacklog) {
                Random rng = new Random(gameTick * 1337L + roomId.getLeastSignificantBits());
                researchCycleService.runCycle(roomId, pool, rng, gameTick, params,
                        peerReviewEnabled, cycleContext);
            } else {
                log.debug("university skipping research cycle — draft backlog={}", researchCycleService.draftQueueSize());
            }
        }

        // Replication pass — one pending request per tick, uses the qualified researcher pool
        List<Researcher> poolForReplication = botLifecycleService.buildResearcherPool(bots, states);
        if (!poolForReplication.isEmpty()) {
            Random rng2 = new Random(gameTick * 7919L + roomId.getMostSignificantBits());
            if (replicationService.processOne(roomId, poolForReplication, rng2,
                    params.educationFactor())) {
                institutionService.recordReplicationCompleted(roomId);
            }
            // Phase 4b: auto-staking — sample same-domain bots to stake on open
            // markets. No-op when the auto-staking flag is off.
            epistemicMarketService.maybeAutoStake(roomId, poolForReplication, rng2);
        }

        // Dating pass with dynamic K and cooldown
        botLifecycleService.processDating(bots, states, dirty, gameTick, params);

        // Phase 4b — funding allocation. Self-paced (every 30 ticks per room),
        // no-op when the flag is off. Runs after publication so this tick's
        // BotMetaStats reflect the just-published papers.
        fundingAllocationService.maybeAllocate(roomId, govState, gameTick);

        // Persist lifecycle state into each bot's lifecycleJson before saving
        botLifecycleService.persistStates(bots, states);
        if (!dirty.isEmpty()) {
            botRepository.saveAll(dirty);
        }
    }

    public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
        List<ResearchPaper> all = roomPapers(roomId);
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
        Map<UUID, BotLifecycleState> states = botLifecycleService.loadStatesQuiet(bots);

        List<ResearchPaper> papers = roomPapers(roomId);
        Map<String, Long> byDomain = papers.stream()
                .collect(Collectors.groupingBy(ResearchPaper::domain, Collectors.counting()));
        long disputed    = papers.stream().filter(p -> "DISPUTED".equals(p.status())).count();
        long nullResults = papers.stream().filter(p -> "NULL_RESULT".equals(p.status())).count();
        long positive    = papers.size() - nullResults - disputed;

        // Average score over positive results only — null/disputed papers skew the baseline down
        OptionalDouble avgScore = papers.stream()
                .filter(p -> "PUBLISHED".equals(p.status()))
                .mapToDouble(ResearchPaper::peerScore).average();
        int maxGen = states.values().stream()
                .mapToInt(s -> s.getGenome() != null ? s.getGenome().getGeneration() : 0)
                .max().orElse(0);
        double avgRep = states.values().stream().mapToDouble(BotLifecycleState::getReputation)
                .average().orElse(0);
        int totalRej = researchCycleService.totalRejected(roomId);
        long qualified = states.values().stream()
                .filter(s -> s.getResearchStatus() == BotLifecycleState.ResearchStatus.QUALIFIED).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPublished",    papers.size());
        stats.put("positiveResults",   positive);
        stats.put("nullResults",       nullResults);
        stats.put("disputed",          disputed);
        stats.put("totalRejected",     totalRej);
        stats.put("researcherCount",   bots.size());
        stats.put("qualifiedCount",    qualified);
        stats.put("maxGeneration",     maxGen);
        stats.put("avgReputation",     Math.round(avgRep * 1000.0) / 1000.0);
        stats.put("avgPeerScore",      avgScore.isPresent()
                ? Math.round(avgScore.getAsDouble() * 1000.0) / 1000.0 : 0.0);
        stats.put("byDomain",          byDomain);
        stats.put("replicationPending", replicationService.pendingCount());
        return stats;
    }



    // ---- Helpers ----

    private void addPaper(UUID roomId, ResearchPaper paper) {
        // DB is source of truth. Persist first, then mirror to in-memory cache and disk.
        try {
            papersRepository.save(paper);
        } catch (Exception e) {
            log.error("university failed to persist paper paperId={}: {}", paper.paperId(), e.getMessage(), e);
            // Continue — cache+disk fallback keeps the simulation running even if DB is unavailable.
        }
        List<ResearchPaper> bucket = roomPapers(roomId);
        synchronized (bucket) {
            bucket.add(paper);
            if (bucket.size() > MAX_PAPERS_PER_ROOM) bucket.remove(0);
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

    /**
     * Finds the highest paperId numeric suffix for a room. DB is queried first;
     * markdown directory is consulted as a fallback only when the DB has no rows
     * (e.g. a freshly migrated environment whose papers exist only on disk).
     */
    private long loadMaxPaperNumber(UUID roomId) {
        try {
            long fromDb = papersRepository.maxPaperNumberForRoom(roomId);
            if (fromDb > 0L) return fromDb;
        } catch (Exception e) {
            log.warn("DB max-paper lookup failed for room {}: {}", roomId, e.getMessage());
        }

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
        boolean nullResult = "NULL_RESULT".equals(paper.status());
        String disputedBanner = disputed
                ? "\n> ⚠ **UNDER DISPUTE** — Independent replication produced a divergent result. "
                  + "This paper's findings are under review.\n"
                : nullResult
                ? "\n> ∅ **NULL RESULT** — Computation ran but the pre-registered hypothesis was not supported. "
                  + "Reported for transparency; no positive claim is made.\n"
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
                **AI Narrator**: %s
                **Reviewers**: %s
                **LLM Call Path**: %s
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
                paper.aiModel() != null ? paper.aiModel() : "template",
                String.join(", ", paper.reviewerBotIds()),
                paper.llmCallPath() != null ? paper.llmCallPath() : "none",
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
