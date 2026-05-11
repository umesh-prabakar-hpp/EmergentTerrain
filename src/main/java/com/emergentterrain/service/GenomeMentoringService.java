package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.EpistemicMarketService;
import com.emergentterrain.research.PromptTemplateIds;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Genome mentoring loop. Carved out of LlmService in Phase 0 Task 0.2 so the
 * narration / formulation / mentoring concerns can evolve independently.
 *
 * Flow: UniversityService publishes a high-quality paper → mentoringCallback fires →
 * enqueueGenomeMentoring puts the bot on the queue → drainGenomeQueue picks it up,
 * acquires the genome slot, asks the LLM for genome deltas + an optional dynamic
 * directive, validates the response, and writes back to the bot's lifecycleJson.
 *
 * Concurrency is controlled by a dedicated semaphore (2 permits) so mentoring
 * cannot exhaust the narration pool.
 */
@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class GenomeMentoringService {

    private static final Logger log = LoggerFactory.getLogger(GenomeMentoringService.class);

    /**
     * Wall-clock cooldown between mentoring sessions per bot. Keyed off the research
     * tick interval (15s) so AI_EDIT_COOLDOWN_TICKS × 15s ≈ 50 minutes minimum gap.
     */
    private static final long AI_EDIT_COOLDOWN_TICKS = 200L;

    private static final Set<String> VALID_GENES = Set.of(
            "w0", "w1", "w2", "w3", "w4", "explorationRate",
            "paramCenter1", "paramCenter2");

    private static final Set<String> VALID_PREDICATES = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");

    private static final Set<String> VALID_METRICS = Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

    /**
     * Phase 1 Iter 2: per-session drift rate when nudging skepticismLevel toward
     * the bot's epistemic-market calibration score. ~50-min mentoring cooldown
     * × 0.1 = ~7 sessions to half-converge ≈ 6 hours of game time. "Slow."
     */
    private static final double SKEPTICISM_DRIFT_RATE = 0.1;
    /** Cap per-session change so a single outlier session can't whip the value. */
    private static final double SKEPTICISM_DRIFT_MAX  = 0.05;

    private final LlmRouter router;
    private final RoomUpdateBroadcaster broadcaster;
    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;
    private final EpistemicMarketService epistemicMarketService;
    private final PromptTemplateRegistry promptRegistry;

    private final Semaphore genomeSlot = new Semaphore(2);
    private final ConcurrentLinkedQueue<GenomeMentoringRequest> genomeQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, Long> lastMentoredAt = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public GenomeMentoringService(LlmRouter router,
                                  RoomUpdateBroadcaster broadcaster,
                                  RoomBotRepository botRepository,
                                  ObjectMapper objectMapper,
                                  EpistemicMarketService epistemicMarketService,
                                  PromptTemplateRegistry promptRegistry) {
        this.router                 = router;
        this.broadcaster            = broadcaster;
        this.botRepository          = botRepository;
        this.objectMapper           = objectMapper;
        this.epistemicMarketService = epistemicMarketService;
        this.promptRegistry         = promptRegistry;
    }

    /** Wire the mentoring callback into UniversityService once the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setMentoringCallback(this::enqueueGenomeMentoring);
            log.info("GenomeMentoringService: mentoring callback wired to UniversityService");
        }
    }

    public void enqueueGenomeMentoring(RoomBot bot, List<ResearchPaper> recentPapers) {
        UUID botId = bot.getId();
        UUID roomId = bot.getRoom().getId();

        Long lastTick = lastMentoredAt.get(botId);
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
            // Slot busy — put it back; the next drain call handles it.
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
            router.streamPrimary(prompt, token -> {
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

        // Substring-extract genome fields without round-tripping through ObjectMapper.
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
        int    pc1 = (int) extractNamedDouble(json, "paramCenter1", 50);
        double pc2 = extractNamedDouble(json, "paramCenter2", 5.0);
        int    pr1 = (int) extractNamedDouble(json, "paramRadius1", 35);
        double pr2 = extractNamedDouble(json, "paramRadius2", 3.5);

        String botIdStr = bot.getId().toString();

        StringBuilder ownWork         = new StringBuilder();
        StringBuilder peerWork        = new StringBuilder();
        StringBuilder crossDomainAnom = new StringBuilder();
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
            } else if (!botIdStr.equals(p.authorBotId()) && Math.abs(p.anomalyZScore()) >= 1.5) {
                String snippet = p.findings().length() > 110
                        ? p.findings().substring(0, 110) + "…" : p.findings();
                crossDomainAnom.append("  bot-%s: %s / %s — score %.2f [z=%+.1f]: %s%n"
                        .formatted(p.authorBotId().substring(0, 6), p.domain(), p.topic(),
                                   p.peerScore(), p.anomalyZScore(), snippet));
            }
        }
        String ownSection         = ownWork.isEmpty()         ? "  (none yet)\n" : ownWork.toString();
        String peerSection        = peerWork.isEmpty()        ? "  (none in domain)\n" : peerWork.toString();
        String crossDomainSection = crossDomainAnom.isEmpty() ? ""
                : "\nAnomalous findings in OTHER domains (cross-domain inspiration from peers):\n"
                  + crossDomainAnom.toString().trim() + "\n";

        // Numeric pre-formatting matches the pre-carve "%.2f"/"%d"/"%.1f"/"±%d"/"±%.1f"
        // patterns exactly so the rendered prompt is byte-for-byte identical to
        // what String.format would have produced. The PromptTemplateRegistryTest
        // equivalence test pins this.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("botId8",                  botIdStr.substring(0, 8));
        params.put("domain",                  domain);
        params.put("methodology",             method);
        params.put("generation",              String.valueOf(gen));
        params.put("w0",                      String.format("%.2f", w0));
        params.put("w1",                      String.format("%.2f", w1));
        params.put("w2",                      String.format("%.2f", w2));
        params.put("w3",                      String.format("%.2f", w3));
        params.put("w4",                      String.format("%.2f", w4));
        params.put("explorationRate",         String.format("%.2f", er));
        params.put("paramCenter1",            String.valueOf(pc1));
        params.put("paramCenter2",            String.format("%.1f", pc2));
        params.put("paramRadius1",            String.valueOf(pr1));
        params.put("paramRadius2",            String.format("%.1f", pr2));
        params.put("publishedCount",          String.valueOf(pub));
        params.put("rejectedCount",           String.valueOf(rej));
        params.put("ownWork",                 ownSection.trim());
        params.put("peerWork",                peerSection.trim());
        params.put("crossDomainAnomalies",    crossDomainSection);
        return promptRegistry.render(PromptTemplateIds.MENTORING_GENOME_ID, params);
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

        JsonNode root;
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
            log.warn("LLM genome mentor: no parseable edits or directive for bot={}; "
                    + "applying skepticism drift only", bot.getId());
        }

        String lifecycleJson = bot.getLifecycleJson();
        if (lifecycleJson == null || lifecycleJson.isBlank()) return;
        try {
            BotLifecycleState ls     = objectMapper.readValue(lifecycleJson, BotLifecycleState.class);
            BotGenome         genome = ls.getGenome();
            boolean           dirty  = false;
            if (genome != null && !edits.isEmpty()) {
                for (GenomeEdit edit : edits) {
                    genome.applyDelta(edit.gene(), edit.delta());
                    log.info("LLM genome mentor applied: bot={} gene={} delta={}",
                            bot.getId(), edit.gene(), edit.delta());
                }
                dirty = true;
            }
            if (directive != null) {
                ls.setPendingDirective(directive);
                log.info("LLM genome mentor directive queued: bot={} description=\"{}\" range=[{},{})",
                        bot.getId(), directive.description(), directive.rangeStart(), directive.rangeEnd());
                dirty = true;
            }
            if (driftSkepticismLevel(bot.getId(), ls)) dirty = true;
            if (dirty) {
                bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                botRepository.save(bot);
            }
        } catch (Exception e) {
            log.warn("LLM genome mentor write-back failed for bot={}: {}", bot.getId(), e.getMessage());
        }
    }

    /**
     * Phase 1 Iter 2 wiring: nudge {@link BotLifecycleState#getSkepticismLevel} toward
     * the bot's epistemic-market calibration score. A bot whose stake outcomes have been
     * accurate (calibration &gt; 0.5) earns a stricter peer-review tolerance over time;
     * an inaccurate bot drifts back toward the neutral 0.5 default. Returns true when
     * the value actually moved (so the caller knows to persist).
     */
    boolean driftSkepticismLevel(UUID botId, BotLifecycleState ls) {
        double current     = ls.getSkepticismLevel();
        double calibration = epistemicMarketService.calibrationScore(botId);
        double rawDelta    = (calibration - current) * SKEPTICISM_DRIFT_RATE;
        double delta       = Math.max(-SKEPTICISM_DRIFT_MAX, Math.min(SKEPTICISM_DRIFT_MAX, rawDelta));
        if (Math.abs(delta) < 1e-4) return false;
        double next = Math.max(0.0, Math.min(1.0, current + delta));
        ls.setSkepticismLevel(next);
        log.info("skepticism drift bot={} from={} target={} delta={} new={}",
                botId,
                "%.3f".formatted(current),
                "%.3f".formatted(calibration),
                "%+.4f".formatted(delta),
                "%.3f".formatted(next));
        return true;
    }

    private List<GenomeEdit> parseEditsFromRoot(JsonNode root, UUID botId) {
        JsonNode editsNode = root.get("edits");
        if (editsNode == null || !editsNode.isArray()) {
            log.warn("LLM genome mentor: JSON missing 'edits' array for bot={}", botId);
            return List.of();
        }
        List<GenomeEdit> result = new ArrayList<>();
        for (JsonNode e : editsNode) {
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

    private DynamicProblem parseDirectiveFromRoot(JsonNode root, UUID botId) {
        JsonNode d = root.get("directive");
        if (d == null || d.isNull() || d.isMissingNode()) return null;
        try {
            String description = d.path("description").asText(null);
            if (description != null) {
                description = description.replaceAll("\\[ANOMALOUS[^\\]]*\\]", "").trim();
            }
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

    /** Extract a value from a JSON array field (e.g. topicWeights[index]). */
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

    private record GenomeEdit(String gene, double delta) {}

    private record GenomeMentoringRequest(
            UUID roomId, RoomBot bot, List<ResearchPaper> recentPapers, long enqueuedAt) {}
}
