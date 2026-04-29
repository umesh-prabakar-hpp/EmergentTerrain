package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
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
import java.util.Arrays;
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

        // Build a research narrative from recent papers
        StringBuilder history = new StringBuilder();
        for (ResearchPaper p : papers) {
            history.append("  %s — %s / %s — score %.2f%n"
                    .formatted(p.status(), p.domain(), p.topic(), p.peerScore()));
        }

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
                Recent work:
                %s
                As their advisor, reflect on the arc of their recent work. What does it say about \
                where their research instincts are taking them? Suggest one or two small genome \
                adjustments that would help them go further in the direction they are already moving — \
                not to fix weaknesses, but to sharpen what is already working. Also identify one trait \
                to protect.

                Reply in this exact format:
                REFLECTION: <2–3 sentences on emerging research identity>
                PROTECT: <gene name> — <why this trait is an asset>
                DEVELOP 1 — GENE: <w0|w1|w2|w3|w4|explorationRate> DELTA: <-0.15 to 0.15> REASON: <one sentence>
                DEVELOP 2 (optional) — GENE: <...> DELTA: <...> REASON: <...>
                """.formatted(
                        bot.getId().toString().substring(0, 8),
                        domain, method, gen,
                        w0, w1, w2, w3, w4, er,
                        pub, rej, history.toString().trim());
    }

    private void applyGenomeMentoringResult(RoomBot bot, String response) {
        if (response == null || response.isBlank()) return;
        List<double[]> edits = parseGenomeEdits(response);
        if (edits.isEmpty()) {
            log.debug("LLM genome mentor: no parseable edits for bot={}", bot.getId());
            return;
        }

        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return;
        try {
            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
            BotGenome genome = ls.getGenome();
            if (genome == null) return;

            String[] geneNames = {"w0", "w1", "w2", "w3", "w4", "explorationRate"};
            for (double[] edit : edits) {
                String gene  = geneNames[(int) edit[0]];
                double delta = edit[1];
                genome.applyDelta(gene, delta);
                log.info("LLM genome mentor applied: bot={} gene={} delta={}", bot.getId(), gene, delta);
            }

            bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
            botRepository.save(bot);
        } catch (Exception e) {
            log.warn("LLM genome mentor write-back failed for bot={}: {}", bot.getId(), e.getMessage());
        }
    }

    // --- Parsing helpers ---

    private List<double[]> parseGenomeEdits(String response) {
        // Each edit is a DEVELOP N — GENE: <name> DELTA: <number> line
        List<double[]> result = new ArrayList<>();
        String[] geneNames = {"w0", "w1", "w2", "w3", "w4", "explorationRate"};
        for (String line : response.lines().toList()) {
            if (!line.contains("GENE:") || !line.contains("DELTA:")) continue;
            try {
                String genePart  = line.substring(line.indexOf("GENE:") + 5).trim();
                String gene      = genePart.split("\\s")[0].trim();
                int geneIdx      = Arrays.asList(geneNames).indexOf(gene);
                if (geneIdx < 0) continue;

                String deltaPart = line.substring(line.indexOf("DELTA:") + 6).trim();
                double delta     = Double.parseDouble(deltaPart.split("\\s")[0].trim());
                delta            = Math.max(-0.15, Math.min(0.15, delta));
                result.add(new double[]{geneIdx, delta});
            } catch (Exception ignored) {}
        }
        return result;
    }

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
