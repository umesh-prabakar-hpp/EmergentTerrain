package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final int DIGEST_MAX_CASES = 3;
    private static final int DIGEST_MAX_BILLS = 3;
    private static final long AI_EDIT_COOLDOWN_TICKS = 200L;

    private final ChatClient chatClient;
    private final RoomUpdateBroadcaster broadcaster;
    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Long> lastAcceptedAt       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastDigestFingerprint = new ConcurrentHashMap<>();
    // Per-bot: last gameTick when a genome mentoring was actually dispatched
    private final ConcurrentHashMap<UUID, Long>   lastMentoredAt        = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    // Three independent semaphores — none can block another from checking its slot.
    // streamSlot/genomeSlot allow 2 concurrent requests each when OLLAMA_NUM_PARALLEL >= 3.
    // consultSlot stays at 1 because it blocks the caller synchronously.
    private final Semaphore consultSlot = new Semaphore(1);  // policy consultation (sync)
    private final Semaphore streamSlot  = new Semaphore(2);  // government digest (async stream)
    private final Semaphore genomeSlot  = new Semaphore(2);  // genome mentoring (async stream)

    // Genome mentoring requests are queued — they must not be shed (earned reward).
    private final ConcurrentLinkedQueue<GenomeMentoringRequest> genomeQueue = new ConcurrentLinkedQueue<>();

    private final long digestCooldownMs;

    public LlmService(ChatClient.Builder chatClientBuilder,
                      RoomUpdateBroadcaster broadcaster,
                      RoomBotRepository botRepository,
                      ObjectMapper objectMapper,
                      @Value("${app.ai.digestCooldownMinutes:10}") long digestCooldownMinutes) {
        this.chatClient       = chatClientBuilder.build();
        this.broadcaster      = broadcaster;
        this.botRepository    = botRepository;
        this.objectMapper     = objectMapper;
        this.digestCooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0L, digestCooldownMinutes));
    }

    /** Wire up the mentoring callback into UniversityService after the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setMentoringCallback(this::enqueueGenomeMentoring);
            log.info("LlmService: genome mentoring callback wired to UniversityService");
        }
    }

    // --- Public triggers ---

    public void generateGovernmentDigestIfAbsent(UUID roomId, GovernmentState state) {
        long now = System.currentTimeMillis();
        String kind = "government_digest";
        String cooldownKey = cooldownKey(roomId, kind);
        if (isOnCooldown(cooldownKey, now, digestCooldownMs)) return;

        List<GovernmentState.CourtCase> decidedCases = state.getCourtCases().stream()
                .filter(c -> "DECIDED".equalsIgnoreCase(c.getStatus()))
                .sorted(Comparator.comparing(this::courtCaseEventAt).reversed())
                .limit(DIGEST_MAX_CASES)
                .toList();
        List<GovernmentState.GovernmentBill> enactedBills = state.getBills().stream()
                .filter(b -> "ENACTED".equalsIgnoreCase(b.getLawStatus())
                          || "BLOCKED".equalsIgnoreCase(b.getLawStatus()))
                .sorted(Comparator.comparing(this::billEventAt).reversed())
                .limit(DIGEST_MAX_BILLS)
                .toList();
        GovernmentState.ElectionRecord recentElection = state.getElections().stream()
                .filter(e -> e.getHeldAt() != null)
                .max(Comparator.comparing(GovernmentState.ElectionRecord::getHeldAt))
                .orElse(null);

        if (decidedCases.isEmpty() && enactedBills.isEmpty() && recentElection == null) return;

        String fingerprint = digestFingerprint(decidedCases, enactedBills, recentElection);
        if (fingerprint.equals(lastDigestFingerprint.get(roomId))) return;

        String streamId = "digest-" + now;
        if (generateAndStream(roomId, streamId, kind,
                buildGovernmentDigestPrompt(decidedCases, enactedBills, recentElection))) {
            markAccepted(cooldownKey, now);
            lastDigestFingerprint.put(roomId, fingerprint);
            log.info("LLM digest accepted room={} cases={} bills={} election={}",
                    roomId, decidedCases.size(), enactedBills.size(), recentElection != null);
        }
    }

    // --- Synchronous consultation ---

    public String consultPolicySync(String prompt) {
        if (!consultSlot.tryAcquire()) {
            log.debug("LLM consult slot busy - skipping policy consultation");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            String response = chatClient.prompt().user(prompt).call().content();
            log.info("LLM policy consultation done ms={}", System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            log.warn("LLM policy consultation failed ms={}: {}", System.currentTimeMillis() - start, e.getMessage());
            return null;
        } finally {
            consultSlot.release();
        }
    }

    // --- Core streaming ---

    public boolean generateAndStream(UUID roomId, String streamId, String kind, String prompt) {
        if (!streamSlot.tryAcquire()) {
            log.info("LLM stream slot busy - shedding room={} kind={}", roomId, kind);
            broadcaster.broadcastLlmDone(roomId, streamId, kind);
            return false;
        }
        try {
            executor.execute(() -> {
                long start = System.currentTimeMillis();
                try {
                    chatClient.prompt()
                            .user(prompt)
                            .stream()
                            .content()
                            .toStream()
                            .forEach(token -> broadcaster.broadcastLlmToken(roomId, streamId, kind, token));
                    log.info("LLM done room={} kind={} ms={}", roomId, kind, System.currentTimeMillis() - start);
                    broadcaster.broadcastLlmDone(roomId, streamId, kind);
                } catch (Exception e) {
                    log.warn("LLM failed room={} kind={} ms={}: {}", roomId, kind,
                            System.currentTimeMillis() - start, e.getMessage());
                    broadcaster.broadcastLlmDone(roomId, streamId, kind);
                } finally {
                    streamSlot.release();
                }
            });
            return true;
        } catch (RuntimeException e) {
            streamSlot.release();
            throw e;
        }
    }

    // --- Genome mentoring ---

    /**
     * Enqueues a genome mentoring request. Called by UniversityService when a bot
     * earns a high-score publication. Requests are never shed — they queue and drain
     * via the independent genomeSlot.
     */
    public void enqueueGenomeMentoring(RoomBot bot, List<ResearchPaper> recentPapers) {
        UUID botId = bot.getId();
        UUID roomId = bot.getRoom().getId();

        // Cooldown: load last mentored tick from in-memory map
        Long lastTick = lastMentoredAt.get(botId);
        // We use wall-clock cooldown here since gameTick isn't directly available
        // AI_EDIT_COOLDOWN_TICKS × 15s interval ≈ 50 minutes minimum between sessions
        long cooldownMs = AI_EDIT_COOLDOWN_TICKS * 15_000L;
        if (lastTick != null && System.currentTimeMillis() - lastTick < cooldownMs) {
            log.debug("LLM genome mentoring on cooldown for bot={}", botId);
            return;
        }

        String lifecycleJson = bot.getLifecycleJson();
        if (lifecycleJson == null || lifecycleJson.isBlank()) return;

        genomeQueue.offer(new GenomeMentoringRequest(roomId, bot, recentPapers,
                System.currentTimeMillis()));
        executor.execute(this::drainGenomeQueue);
    }

    private void drainGenomeQueue() {
        GenomeMentoringRequest req = genomeQueue.poll();
        if (req == null) return;
        if (!genomeSlot.tryAcquire()) {
            // Slot busy — put it back and let the next drainGenomeQueue call handle it
            genomeQueue.offer(req);
            return;
        }
        try {
            runGenomeMentoring(req);
        } finally {
            genomeSlot.release();
            if (!genomeQueue.isEmpty()) executor.execute(this::drainGenomeQueue);
        }
    }

    private void runGenomeMentoring(GenomeMentoringRequest req) {
        UUID roomId = req.roomId();
        RoomBot bot = req.bot();
        UUID botId  = bot.getId();

        String prompt = buildGenomeMentoringPrompt(bot, req.recentPapers());
        if (prompt == null) return;

        String streamId = "mentor-" + botId + "-" + req.enqueuedAt();
        long start = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();
        try {
            chatClient.prompt()
                    .user(prompt)
                    .stream()
                    .content()
                    .toStream()
                    .forEach(token -> {
                        broadcaster.broadcastLlmToken(roomId, streamId, "genome_mentor", token);
                        fullResponse.append(token);
                    });
            broadcaster.broadcastLlmDone(roomId, streamId, "genome_mentor");
            log.info("LLM genome mentor done bot={} ms={}", botId, System.currentTimeMillis() - start);

            applyGenomeMentoringResult(bot, fullResponse.toString());
            lastMentoredAt.put(botId, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("LLM genome mentor failed bot={} ms={}: {}", botId,
                    System.currentTimeMillis() - start, e.getMessage());
            broadcaster.broadcastLlmDone(roomId, streamId, "genome_mentor");
        }
    }

    private String buildGenomeMentoringPrompt(RoomBot bot, List<ResearchPaper> papers) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return null;

        // Parse just enough to build the narrative — use raw string parsing to avoid
        // circular dependency on ObjectMapper here; delegate parsing to a simple helper.
        // We pass the lifecycle JSON as context and let the LLM read the plain-English summary.

        // Extract genome fields from the lifecycle JSON via substring search
        // (avoids needing ObjectMapper injection here)
        double w0 = extractDouble(json, "topicWeights", 0, 0.3);
        double w1 = extractDouble(json, "topicWeights", 1, 0.2);
        double w2 = extractDouble(json, "topicWeights", 2, 0.5);
        double w3 = extractDouble(json, "topicWeights", 3, 0.5);
        double w4 = extractDouble(json, "topicWeights", 4, 0.05);
        double er = extractNamedDouble(json, "explorationRate", 0.2);
        int gen   = (int) extractNamedDouble(json, "generation", 0);
        int pub   = (int) extractNamedDouble(json, "publishedCount", 0);
        int rej   = (int) extractNamedDouble(json, "rejectedCount", 0);
        String domain = extractString(json, "domain", "MATHEMATICS");
        String method = extractString(json, "methodology", "THEORETICAL");

        String botIdStr = bot.getId().toString();

        // Split papers into own work vs peers in the same domain; flag anomalies
        StringBuilder ownWork  = new StringBuilder();
        StringBuilder peerWork = new StringBuilder();
        for (ResearchPaper p : papers) {
            String anomalyTag = Math.abs(p.anomalyZScore()) >= 1.5
                    ? " [ANOMALOUS z=%+.1f]".formatted(p.anomalyZScore()) : "";
            if (botIdStr.equals(p.authorBotId())) {
                ownWork.append("  %s / %s — score %.2f%s%n"
                        .formatted(p.domain(), p.topic(), p.peerScore(), anomalyTag));
            } else if (domain.equalsIgnoreCase(p.domain())) {
                peerWork.append("  bot-%s: %s / %s — score %.2f%s%n"
                        .formatted(p.authorBotId().substring(0, 6), p.domain(), p.topic(),
                                   p.peerScore(), anomalyTag));
            }
        }
        String ownSection  = ownWork.isEmpty()  ? "  (none yet)\n" : ownWork.toString();
        String peerSection = peerWork.isEmpty() ? "  (none in domain)\n" : peerWork.toString();

        return """
                You are a research advisor in a simulation. A researcher bot has just published \
                notable work and is eligible for a mentoring session.

                Researcher: %s  |  Domain: %s  |  Methodology: %s
                Generation: %d  (evolution and mentoring cycles completed)

                Research personality (genome):
                  Exploration boldness:  %.2f  — how large their search moves are
                  Restart willingness:   %.2f  — how readily they abandon a direction and try another
                  Candidate breadth:     %.2f  — how many options they consider before committing
                  Depth vs breadth:      %.2f  — whether they pursue one path deeply or survey widely
                  Acceptance threshold:  %.2f  — how close to perfect a solution must be to satisfy them
                  Curiosity:             %.2f  — overall openness to new approaches

                Career so far: %d papers published, %d drafts not accepted
                Their own recent work:
                %s
                Recent work by peers in the same domain:
                %s
                As their advisor, consider what the peer findings reveal about this domain. \
                Pay particular attention to results marked [ANOMALOUS] — these deviate significantly \
                from the room's historical distribution for that topic and may indicate unexplored \
                territory or a structural pattern worth investigating. Suggest one or two small genome \
                adjustments and, if any anomalous result points to a specific unexplored range, include \
                a directive to follow it up.

                Reply with only this JSON and nothing else:
                {"identity":"one sentence on their emerging research identity","edits":[{"gene":"w0","delta":0.05}],"directive":{"description":"prime-happy overlap near 500","rangeStart":500,"rangeEnd":700,"predicate":"isPrime AND isHappy","metric":"fraction_satisfying","baseline":0.018,"hypothesis":"The overlap of primes and happy numbers near 500 should be ~1.8%% of the range."}}
                Gene rules: gene must be one of w0 w1 w2 w3 w4 explorationRate. delta between -0.15 and 0.15. Include 1 or 2 edits.
                Directive rules: predicate uses isPrime, isHappy, collatzTerminates, isAbundant, isPerfect joined with AND or OR. \
                metric is one of: fraction_satisfying, mean_of:collatzSteps, max_of:collatzSteps, mean_of:sigmaSum. \
                rangeEnd - rangeStart must not exceed 500. baseline is your predicted numeric value. \
                Only include directive if the peer papers reveal a specific unexplored range worth targeting. Omit directive entirely otherwise.
                """.formatted(
                        botIdStr.substring(0, 8),
                        domain, method, gen,
                        w0, w1, w2, w3, w4, er,
                        pub, rej, ownSection.trim(), peerSection.trim());
    }

    private void applyGenomeMentoringResult(RoomBot bot, String response) {
        if (response == null || response.isBlank()) return;
        log.info("LLM genome mentor raw response bot={}:\n{}", bot.getId(), response);

        int jsonStart = response.indexOf('{');
        int jsonEnd   = response.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            log.warn("LLM genome mentor: no JSON block found for bot={}", bot.getId());
            return;
        }

        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(response.substring(jsonStart, jsonEnd + 1));
        } catch (Exception e) {
            log.warn("LLM genome mentor: JSON parse failed for bot={}: {}", bot.getId(), e.getMessage());
            return;
        }

        String identity = root.path("identity").asText(null);
        if (identity != null) log.info("LLM genome mentor identity bot={}: {}", bot.getId(), identity);

        List<GenomeEdit> edits     = parseEditsFromRoot(root, bot.getId());
        DynamicProblem   directive = parseDirectiveFromRoot(root, bot.getId());

        if (edits.isEmpty() && directive == null) {
            log.warn("LLM genome mentor: no parseable edits or directive for bot={}", bot.getId());
            return;
        }

        String lifecycleJson = bot.getLifecycleJson();
        if (lifecycleJson == null || lifecycleJson.isBlank()) return;
        try {
            BotLifecycleState ls     = objectMapper.readValue(lifecycleJson, BotLifecycleState.class);
            BotGenome         genome = ls.getGenome();
            if (genome != null) {
                for (GenomeEdit edit : edits) {
                    genome.applyDelta(edit.gene(), edit.delta());
                    log.info("LLM genome mentor applied: bot={} gene={} delta={}", bot.getId(), edit.gene(), edit.delta());
                }
            }
            if (directive != null) {
                ls.setPendingDirective(directive);
                log.info("LLM genome mentor directive queued: bot={} description=\"{}\" range=[{},{})",
                        bot.getId(), directive.description(), directive.rangeStart(), directive.rangeEnd());
            }
            bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
            botRepository.save(bot);
        } catch (Exception e) {
            log.warn("LLM genome mentor write-back failed for bot={}: {}", bot.getId(), e.getMessage());
        }
    }

    // --- Parsing helpers ---

    private static final java.util.Set<String> VALID_GENES = java.util.Set.of(
            "w0", "w1", "w2", "w3", "w4", "explorationRate");

    private static final java.util.Set<String> VALID_PREDICATES = java.util.Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");

    private static final java.util.Set<String> VALID_METRICS = java.util.Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

    private List<GenomeEdit> parseEditsFromRoot(com.fasterxml.jackson.databind.JsonNode root, UUID botId) {
        com.fasterxml.jackson.databind.JsonNode editsNode = root.get("edits");
        if (editsNode == null || !editsNode.isArray()) {
            log.warn("LLM genome mentor: JSON missing 'edits' array for bot={}", botId);
            return List.of();
        }
        List<GenomeEdit> result = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode e : editsNode) {
            String gene  = e.path("gene").asText(null);
            double delta = e.path("delta").asDouble(Double.NaN);
            if (gene == null || !VALID_GENES.contains(gene) || Double.isNaN(delta)) {
                log.warn("LLM genome mentor: invalid edit entry for bot={}: {}", botId, e);
                continue;
            }
            result.add(new GenomeEdit(gene, Math.max(-0.15, Math.min(0.15, delta))));
        }
        return result;
    }

    private DynamicProblem parseDirectiveFromRoot(com.fasterxml.jackson.databind.JsonNode root, UUID botId) {
        com.fasterxml.jackson.databind.JsonNode d = root.get("directive");
        if (d == null || d.isNull() || d.isMissingNode()) return null;
        try {
            String description = d.path("description").asText(null);
            int    rangeStart  = d.path("rangeStart").asInt(-1);
            int    rangeEnd    = d.path("rangeEnd").asInt(-1);
            String predicate   = d.path("predicate").asText(null);
            String metric      = d.path("metric").asText("fraction_satisfying");
            double baseline    = d.path("baseline").asDouble(0.0);
            String hypothesis  = d.path("hypothesis").asText(null);

            if (description == null || rangeStart < 1 || rangeEnd <= rangeStart
                    || rangeEnd - rangeStart > 500) {
                log.warn("LLM genome mentor: invalid directive range for bot={}: {}", botId, d);
                return null;
            }
            if (predicate == null || predicate.isBlank()) {
                log.warn("LLM genome mentor: directive missing predicate for bot={}", botId);
                return null;
            }
            // Validate each predicate token
            for (String token : predicate.replace(" AND ", "|").replace(" OR ", "|").split("\\|")) {
                if (!VALID_PREDICATES.contains(token.trim())) {
                    log.warn("LLM genome mentor: unknown predicate '{}' for bot={}", token.trim(), botId);
                    return null;
                }
            }
            if (!VALID_METRICS.contains(metric)) {
                log.warn("LLM genome mentor: unknown metric '{}' for bot={}", metric, botId);
                return null;
            }

            String id = "dyn-" + botId.toString().substring(0, 6) + "-" + System.currentTimeMillis();
            return new DynamicProblem(id, description, rangeStart, rangeEnd,
                    predicate, metric, baseline, hypothesis);
        } catch (Exception e) {
            log.warn("LLM genome mentor: directive parse failed for bot={}: {}", botId, e.getMessage());
            return null;
        }
    }

    private record GenomeEdit(String gene, double delta) {}

    /** Extract a value from a JSON array field (topicWeights[index]). */
    private double extractDouble(String json, String arrayField, int index, double fallback) {
        try {
            int start = json.indexOf("\"" + arrayField + "\"");
            if (start < 0) return fallback;
            int arrStart = json.indexOf('[', start);
            int arrEnd   = json.indexOf(']', arrStart);
            if (arrStart < 0 || arrEnd < 0) return fallback;
            String[] parts = json.substring(arrStart + 1, arrEnd).split(",");
            if (index >= parts.length) return fallback;
            return Double.parseDouble(parts[index].trim());
        } catch (Exception e) { return fallback; }
    }

    /** Extract a named scalar from JSON. */
    private double extractNamedDouble(String json, String field, double fallback) {
        try {
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return fallback;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return fallback;
            int end = json.indexOf(',', colon);
            if (end < 0) end = json.indexOf('}', colon);
            return Double.parseDouble(json.substring(colon + 1, end).trim());
        } catch (Exception e) { return fallback; }
    }

    private String extractString(String json, String field, String fallback) {
        try {
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return fallback;
            int colon  = json.indexOf(':', idx);
            int q1     = json.indexOf('"', colon + 1);
            int q2     = json.indexOf('"', q1 + 1);
            return json.substring(q1 + 1, q2);
        } catch (Exception e) { return fallback; }
    }

    // --- Government digest helpers (unchanged) ---

    private String cooldownKey(UUID roomId, String kind) { return roomId + ":" + kind; }

    private boolean isOnCooldown(String cooldownKey, long now, long cooldownMs) {
        if (cooldownMs <= 0) return false;
        Long last = lastAcceptedAt.get(cooldownKey);
        return last != null && now - last < cooldownMs;
    }

    private void markAccepted(String cooldownKey, long now) { lastAcceptedAt.put(cooldownKey, now); }

    private String digestFingerprint(List<GovernmentState.CourtCase> cases,
                                     List<GovernmentState.GovernmentBill> bills,
                                     GovernmentState.ElectionRecord election) {
        List<String> parts = new ArrayList<>();
        for (GovernmentState.CourtCase c : cases)
            parts.add("case:" + nvl(c.getCaseId()) + ":" + courtCaseEventAt(c));
        for (GovernmentState.GovernmentBill b : bills)
            parts.add("bill:" + nvl(b.getBillId()) + ":" + nvl(b.getLawStatus()) + ":" + billEventAt(b));
        if (election != null)
            parts.add("election:" + nvl(election.getElectionId()) + ":" + election.getHeldAt());
        return String.join("|", parts);
    }

    private java.time.Instant courtCaseEventAt(GovernmentState.CourtCase c) {
        return c.getDecidedAt() == null ? java.time.Instant.EPOCH : c.getDecidedAt();
    }

    private java.time.Instant billEventAt(GovernmentState.GovernmentBill b) {
        if (b.getUpdatedAt() != null) return b.getUpdatedAt();
        return b.getCreatedAt() == null ? java.time.Instant.EPOCH : b.getCreatedAt();
    }

    private String buildGovernmentDigestPrompt(List<GovernmentState.CourtCase> cases,
                                               List<GovernmentState.GovernmentBill> bills,
                                               GovernmentState.ElectionRecord election) {
        StringBuilder events = new StringBuilder();
        for (GovernmentState.CourtCase c : cases) {
            String ruling = c.isBlockedAction() ? "BLOCKED" : "UPHELD";
            events.append("- Court (%s): %s — %s [%s]\n"
                    .formatted(nvl(c.getCourtLevel()), nvl(c.getCaseId()),
                               nvl(c.getIssueSummary()), ruling));
        }
        for (GovernmentState.GovernmentBill b : bills) {
            events.append("- Bill %s: \"%s\" [%s, domain: %s]\n"
                    .formatted(nvl(b.getLawStatus()), nvl(b.getTitle()),
                               nvl(b.getLawStatus()), nvl(b.getPolicyDomain())));
        }
        if (election != null) {
            events.append("- Election %s: winner=%s, turnout=%.0f%%\n"
                    .formatted(nvl(election.getElectionId()),
                               nvl(election.getWinningPartyId()),
                               election.getTurnout() * 100));
        }
        return """
                You are a civic news anchor in a simulation game.
                Write a brief government bulletin (3-5 sentences, plain prose, under 100 words).
                Cover only the events listed. Be factual and neutral.

                Events:
                %s
                End with one sentence on what citizens should watch next."""
                .formatted(events.toString().trim());
    }

    private static String nvl(String v) { return v != null ? v : ""; }

    // --- Inner record ---

    private record GenomeMentoringRequest(
            UUID roomId, RoomBot bot, List<ResearchPaper> recentPapers, long enqueuedAt) {}
}
