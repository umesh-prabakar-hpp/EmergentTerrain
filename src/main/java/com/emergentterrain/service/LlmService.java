package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
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

    // Four independent semaphores — none can block another from checking its slot.
    // streamSlot/genomeSlot allow 2 concurrent requests each when OLLAMA_NUM_PARALLEL >= 3.
    // consultSlot and narrationSlot stay at 1 (sync call and sequential paper pipeline).
    private final Semaphore consultSlot      = new Semaphore(1);  // policy consultation (sync)
    private final Semaphore streamSlot       = new Semaphore(2);  // government digest (async stream)
    private final Semaphore genomeSlot       = new Semaphore(2);  // genome mentoring (async stream)
    private final Semaphore narrationSlot    = new Semaphore(1);  // paper narration — one at a time
    private final Semaphore formulationSlot  = new Semaphore(1);  // pre-computation formulation (sync)

    // Genome mentoring requests are queued — they must not be shed (earned reward).
    private final ConcurrentLinkedQueue<GenomeMentoringRequest> genomeQueue     = new ConcurrentLinkedQueue<>();
    // Paper narration queue — every passed paper goes through LLM before being published.
    private final ConcurrentLinkedQueue<PaperDraft>             narrationQueue  = new ConcurrentLinkedQueue<>();

    // Set during wireUniversityCallback — calls university.publishPaper on narration completion.
    private volatile java.util.function.BiConsumer<PaperDraft, NarrationResult> narrationPublisher;

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
            university.setNarratingCallback(this::enqueueNarration);
            university.setFormulationCallback(args -> {
                String domain         = (String) args[0];
                String topicName      = (String) args[1];
                BotGenome genome      = (BotGenome) args[2];
                @SuppressWarnings("unchecked")
                List<ResearchPaper> papers = (List<ResearchPaper>) args[3];
                return formulateResearch(domain, topicName, genome, papers);
            });
            this.narrationPublisher = university::publishPaper;
            log.info("LlmService: genome mentoring + narration + formulation callbacks wired to UniversityService");
        }
    }

    public record FormulationResult(int param1, double param2, String researchQuestion) {}

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

    // --- Research formulation (pre-computation LLM step) ---

    /**
     * Synchronous pre-computation step: LLM chooses specific param1/param2 values and a
     * research question based on the bot's learned search region and prior papers in this
     * domain+topic. Returns null immediately if the formulation slot is busy — caller
     * falls back to genome sampling in that case.
     */
    public FormulationResult formulateResearch(String domain, String topicName,
                                               BotGenome genome, List<ResearchPaper> priorPapers) {
        if (!formulationSlot.tryAcquire()) {
            log.debug("LLM formulation slot busy — falling back to genome sampling");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            String prompt = buildFormulationPrompt(domain, topicName, genome, priorPapers);
            String response = chatClient.prompt().user(prompt).call().content();
            log.info("LLM formulation done domain={} topic={} ms={}", domain, topicName,
                    System.currentTimeMillis() - start);
            return parseFormulationResult(response);
        } catch (Exception e) {
            log.warn("LLM formulation failed domain={} topic={} ms={}: {}", domain, topicName,
                    System.currentTimeMillis() - start, e.getMessage());
            return null;
        } finally {
            formulationSlot.release();
        }
    }

    private String buildFormulationPrompt(String domain, String topicName,
                                          BotGenome genome, List<ResearchPaper> priorPapers) {
        int center1 = genome.getParamCenter1();
        double center2 = genome.getParamCenter2();
        int radius1 = genome.getParamRadius1();
        double radius2 = genome.getParamRadius2();

        StringBuilder priorWork = new StringBuilder();
        priorPapers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain)
                          && p.topic().equalsIgnoreCase(topicName))
                .limit(4)
                .forEach(p -> priorWork.append("  %s [score %.2f]: %s\n"
                        .formatted(p.paperId(), p.peerScore(),
                                p.findings().length() > 120
                                        ? p.findings().substring(0, 120) + "…"
                                        : p.findings())));
        String priorSection = priorWork.isEmpty()
                ? "  (no prior papers in this topic yet — choose a fresh starting region)\n"
                : priorWork.toString();

        return """
                You are a research advisor choosing where a computational researcher should focus next.

                Domain: %s | Topic: %s
                Researcher's learned search region: param1 center=%d (±%d), param2 center=%.1f (±%.1f)
                param1 is an integer 10–99 (controls the starting position of the search space).
                param2 is a decimal 1.0–9.0 (controls depth/scale of the search).

                Prior papers in this topic:
                %s
                Based on the prior work, choose a specific unexplored or promising parameter region
                within or near the researcher's learned search region. Explain the research question
                this choice targets in one sentence.

                Reply with ONLY this JSON, no other text:
                {"param1":N,"param2":X.X,"question":"one sentence stating the specific research question"}

                param1 must be an integer 10–99. param2 must be a decimal 1.0–9.0.
                Choose values that differ from or extend what prior papers have already explored.
                """.formatted(domain, topicName, center1, radius1, center2, radius2, priorSection.trim());
    }

    private FormulationResult parseFormulationResult(String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    objectMapper.readTree(response.substring(start, end + 1));
            int    p1 = root.path("param1").asInt(-1);
            double p2 = root.path("param2").asDouble(-1.0);
            String q  = root.path("question").asText(null);
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) {
                log.debug("LLM formulation: params out of range (param1={}, param2={}) — discarded", p1, p2);
                return null;
            }
            return new FormulationResult(p1, p2, q);
        } catch (Exception e) {
            log.warn("LLM formulation: JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    // --- Paper narration ---

    /**
     * Enqueues a passed paper for LLM narrative synthesis. Every paper goes through
     * this path before being published; the narrationSlot serialises calls so a slow
     * model response cannot pile up concurrent LLM requests.
     */
    public void enqueueNarration(PaperDraft draft) {
        narrationQueue.offer(draft);
        executor.execute(this::drainNarrationQueue);
    }

    private void drainNarrationQueue() {
        PaperDraft draft = narrationQueue.poll();
        if (draft == null) return;
        if (!narrationSlot.tryAcquire()) {
            narrationQueue.offer(draft);
            return;
        }
        try {
            runNarration(draft);
        } finally {
            narrationSlot.release();
            if (!narrationQueue.isEmpty()) executor.execute(this::drainNarrationQueue);
        }
    }

    private void runNarration(PaperDraft draft) {
        String prompt = buildNarrationPrompt(draft);
        long start = System.currentTimeMillis();
        StringBuilder response = new StringBuilder();
        try {
            chatClient.prompt().user(prompt).stream().content().toStream()
                    .forEach(response::append);
            log.info("LLM narration done paperId={} ms={}", draft.paperId(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("LLM narration failed paperId={} ms={}: {} — publishing with template text",
                    draft.paperId(), System.currentTimeMillis() - start, e.getMessage());
        }
        NarrationResult result = parseNarrationResult(response.toString());
        if (narrationPublisher != null) {
            narrationPublisher.accept(draft, result);
        }
    }

    private String buildNarrationPrompt(PaperDraft draft) {
        StringBuilder priorWork = new StringBuilder();
        draft.recentPapers().stream()
                .filter(p -> p.domain().equalsIgnoreCase(draft.domain())
                          && p.topic().equalsIgnoreCase(draft.topicName()))
                .limit(3)
                .forEach(p -> {
                    String snippet = p.findings().length() > 160
                            ? p.findings().substring(0, 160) + "…" : p.findings();
                    priorWork.append("  %s [score %.2f]: %s%n"
                            .formatted(p.paperId(), p.peerScore(), snippet));
                });
        String priorSection = priorWork.isEmpty()
                ? "  (no prior papers in this domain/topic yet)\n"
                : priorWork.toString();

        String paramsLine = (draft.param1() != null)
                ? "Computation parameters: starting position %d, scale %.1f\n"
                        .formatted(draft.param1(), draft.param2())
                : "";

        String followUpNote;
        if (draft.isFollowUp()) {
            String predNote = (draft.predictedScore() != null)
                    ? " The prior paper predicted a quality score of ~%.2f for this region; actual score is %.2f. Explicitly compare prediction to outcome."
                            .formatted(draft.predictedScore(), draft.peerScore())
                    : "";
            String methodChain = (draft.priorMethodology() != null)
                    ? " Prior paper used %s methodology; this paper uses %s — acknowledge the methodological progression if relevant."
                            .formatted(draft.priorMethodology(), draft.methodology())
                    : "";
            followUpNote = "IMPORTANT: This paper is a deliberate follow-up investigation — the author's "
                    + "prior conclusion suggested this exact parameter region. Acknowledge the chain: "
                    + "reference a prior paper by ID, state what was predicted, and report whether "
                    + "this result confirms, refines, or surprises that prediction."
                    + predNote + methodChain + "\n";
        } else {
            followUpNote = "";
        }

        String anomalyNote;
        double absZ = Math.abs(draft.anomalyZScore());
        if (absZ >= 2.5) {
            anomalyNote = (draft.anomalyZScore() > 0
                    ? "DISCOVERY CONTEXT: This result is anomalously strong (z=+%.1f σ above the room's historical distribution for this topic). Frame the conclusion as a notable discovery, not a routine survey. Explain what makes this region special."
                    : "NOTE: This result is anomalously weak (z=%.1f σ, unusually below the room average for this topic). The conclusion should acknowledge the unexpected underperformance and suggest why this region may be structurally less productive.")
                    .formatted(draft.anomalyZScore()) + "\n";
        } else if (absZ >= 1.5) {
            anomalyNote = "NOTE: This result sits at z=%.1f σ from the room baseline — moderately noteworthy. Mention whether the result is stronger or weaker than typical for this topic.\n"
                    .formatted(draft.anomalyZScore());
        } else {
            anomalyNote = "";
        }

        String scaffoldHyp = (draft.templateHypothesis() != null && !draft.templateHypothesis().isBlank())
                ? draft.templateHypothesis() : "(none)";
        String scaffoldCon = (draft.templateConclusion() != null && !draft.templateConclusion().isBlank())
                ? draft.templateConclusion() : "(none)";

        return """
                You are writing the narrative for a computational research paper in a simulation.

                Domain: %s | Topic: %s | Methodology: %s
                Title: "%s"
                Bot generation: %d
                %s%s%s
                Raw computation output — interpret the PATTERN or TREND, do NOT reproduce \
                tables or copy individual values:
                %s

                Preliminary hypothesis (rewrite in your own words, grounding it in the \
                computation parameters above):
                %s

                Preliminary conclusion (rewrite — explain what the result implies and what \
                open question it raises for the next investigation):
                %s

                Prior papers in this domain and topic (cite by Paper ID if directly relevant):
                %s
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)","findings":"2-3 sentences \
                interpreting the pattern or trend — its significance and what it suggests; \
                cite a prior paper by ID if relevant","conclusion":"1-2 sentences: what this \
                result implies and one concrete open question it raises",\
                "followUp":{"param1":N,"param2":X.X,"predictedScore":0.30}}

                followUp rules: ONLY include followUp if the prior papers reveal a specific \
                unexplored parameter region worth targeting next. param1 is an integer 10–99 \
                (shifts the search starting position). param2 is a decimal 1.0–9.0 (scales depth). \
                predictedScore is your numeric prediction (0.0–1.0) for what quality the follow-up \
                result will achieve. If no clear follow-up region emerges, OMIT the followUp field entirely.
                All text values under 120 words each. Plain prose inside JSON strings only — \
                no bullet points, no sub-headers, no markdown formatting.
                """.formatted(
                draft.domain(), draft.topicName(), draft.methodology(),
                draft.title(), draft.generation(),
                paramsLine, followUpNote, anomalyNote,
                compactRawFindings(draft.rawFindings()),
                scaffoldHyp,
                scaffoldCon,
                priorSection.trim());
    }

    private static String compactRawFindings(String raw) {
        if (raw == null || raw.isBlank()) return "(none)";
        if (raw.length() <= 350) return raw;
        // Count Unicode arrow data points (e.g. Vf=10%→1513MPa)
        int arrowCount = 0;
        for (int i = raw.indexOf('→'); i >= 0; i = raw.indexOf('→', i + 1)) arrowCount++;
        if (arrowCount >= 5) {
            // Preamble: text before the first key→value token
            int firstArrow = raw.indexOf('→');
            int blockStart = raw.lastIndexOf(' ', firstArrow - 2) + 1;
            String preamble = blockStart > 0 ? raw.substring(0, blockStart).trim() : "";
            // Sample: keep first two key→value tokens to show the pattern and scale
            StringBuilder sample = new StringBuilder();
            int pos = blockStart, kv = 0;
            while (pos < raw.length() && kv < 2) {
                int next = raw.indexOf(' ', pos + 1);
                if (next < 0) next = raw.length();
                String token = raw.substring(pos, next).trim();
                if (token.contains("→")) { sample.append(token).append(" "); kv++; }
                pos = next + 1;
            }
            // Extract all numeric values after arrows for range summary
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            int n = 0;
            pos = 0;
            while ((pos = raw.indexOf('→', pos)) >= 0) {
                int end = pos + 1;
                while (end < raw.length()
                        && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) end++;
                if (end > pos + 1) {
                    try {
                        double v = Double.parseDouble(raw.substring(pos + 1, end));
                        min = Math.min(min, v); max = Math.max(max, v); n++;
                    } catch (NumberFormatException ignored) {}
                }
                pos = end;
            }
            String stats = n > 0
                    ? "… [%d total; min=%.0f, max=%.0f]".formatted(n, min, max)
                    : "… [%d data points]".formatted(arrowCount);
            // Tail: summary sentence(s) after the last data point
            int lastArrow = raw.lastIndexOf('→');
            int tailStart = raw.indexOf('.', lastArrow + 5);
            String tail   = (tailStart > 0 && tailStart + 1 < raw.length())
                    ? raw.substring(tailStart + 1).trim() : "";
            return (preamble.isEmpty() ? "" : preamble + "\n")
                    + sample.toString().trim() + " " + stats
                    + (tail.isEmpty() ? "" : "\n" + tail);
        }
        return raw.substring(0, 350) + "…";
    }

    private NarrationResult parseNarrationResult(String response) {
        if (response == null || response.isBlank()) return new NarrationResult(null, null, null, null);
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new NarrationResult(null, null, null, null);
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    objectMapper.readTree(response.substring(start, end + 1));
            FollowUpParams followUp = null;
            com.fasterxml.jackson.databind.JsonNode fuNode = root.get("followUp");
            if (fuNode != null && !fuNode.isNull() && !fuNode.isMissingNode()) {
                int    p1 = fuNode.path("param1").asInt(-1);
                double p2 = fuNode.path("param2").asDouble(-1.0);
                if (p1 >= 10 && p1 <= 99 && p2 >= 1.0 && p2 <= 9.0) {
                    double ps = fuNode.path("predictedScore").asDouble(-1.0);
                    Double predictedScore = (ps >= 0.0 && ps <= 1.0) ? ps : null;
                    followUp = new FollowUpParams(p1, p2, predictedScore, null);
                } else {
                    log.debug("LLM narration: followUp out of range (param1={}, param2={}) — discarded", p1, p2);
                }
            }
            return new NarrationResult(
                    root.path("hypothesis").asText(null),
                    root.path("findings").asText(null),
                    root.path("conclusion").asText(null),
                    followUp);
        } catch (Exception e) {
            log.warn("LLM narration: JSON parse failed: {}", e.getMessage());
            return new NarrationResult(null, null, null, null);
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
        int    pc1 = (int) extractNamedDouble(json, "paramCenter1", 50);
        double pc2 = extractNamedDouble(json, "paramCenter2", 5.0);
        int    pr1 = (int) extractNamedDouble(json, "paramRadius1", 35);
        double pr2 = extractNamedDouble(json, "paramRadius2", 3.5);

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
                  Step size:             %.2f  — spacing between candidate regions (0.05=narrow, 1.0=wide)
                  Restart willingness:   %.2f  — reserved, not yet active
                  Candidate breadth:     %.2f  — regions explored per paper (20–80; key quality driver)
                  Peer tolerance:        %.2f  — reviewer strictness (low=strict 0.02, high=lenient 0.12)
                  Acceptance threshold:  %.2f  — reserved for future gating
                  Curiosity:             %.2f  — overall openness to new directions

                Parameter search region (learned from publication history):
                  Center: [param1=%d, param2=%.1f]  — where they focus searches
                  Radius: [±%d, ±%.1f]              — how broadly they explore around that center
                  (narrowing radius = deepening specialisation; wider = more exploratory)

                Career so far: %d papers published, %d drafts not accepted
                Their own recent work:
                %s
                Recent work by peers in the same domain:
                %s
                As their advisor, consider what the peer findings reveal about this domain. \
                Pay particular attention to results marked [ANOMALOUS] — these deviate significantly \
                from the room's historical distribution for that topic and may indicate unexplored \
                territory or a structural pattern worth investigating. Suggest one or two small genome \
                adjustments. If the peer papers reveal a specific unexplored parameter region, you may \
                also redirect the bot's search center (paramCenter1, paramCenter2) toward it, OR \
                include a directive to run a focused number-theoretic investigation.

                Reply with only this JSON and nothing else:
                {"identity":"one sentence on their emerging research identity","edits":[{"gene":"w0","delta":0.05}],"directive":{"description":"your description","rangeStart":N,"rangeEnd":M,"predicate":"your predicate","metric":"your metric","baseline":0.0,"hypothesis":"your hypothesis"}}
                Gene rules: gene must be one of w0 w1 w2 w3 w4 explorationRate paramCenter1 paramCenter2. \
                delta between -0.15 and 0.15. Include 1 or 2 edits. \
                For paramCenter1: delta ±0.15 shifts the center by ±6 units (range 10–99). \
                For paramCenter2: delta ±0.15 shifts by ±0.75 (range 1.0–9.0).
                Directive rules: predicate uses isPrime, isHappy, collatzTerminates, isAbundant, isPerfect joined with AND or OR. \
                metric is one of: fraction_satisfying, mean_of:collatzSteps, max_of:collatzSteps, mean_of:sigmaSum. \
                rangeEnd - rangeStart must not exceed 500. baseline is your predicted numeric value for the metric. \
                Base the directive on what the ACTUAL peer papers above reveal — target a specific unexplored range \
                or combination that their results point toward. \
                Only include directive if the peer papers reveal a specific unexplored range worth targeting. Omit directive entirely otherwise.
                Example directives (DO NOT copy these — generate your own based on the actual papers shown above):
                  {"description":"abundant numbers in range 200-400","rangeStart":200,"rangeEnd":400,"predicate":"isAbundant","metric":"fraction_satisfying","baseline":0.25,"hypothesis":"About 25%% of integers in 200-400 are abundant, consistent with the known asymptotic density."}
                  {"description":"mean Collatz depth for happy primes near 800","rangeStart":780,"rangeEnd":950,"predicate":"isPrime AND isHappy","metric":"mean_of:collatzSteps","baseline":75.0,"hypothesis":"Happy primes near 800 require about 75 Collatz steps to terminate, deeper than the general average."}
                  {"description":"perfect or abundant numbers below 300","rangeStart":2,"rangeEnd":302,"predicate":"isAbundant OR isPerfect","metric":"fraction_satisfying","baseline":0.24,"hypothesis":"Roughly 24%% of integers below 302 are abundant or perfect."}
                """.formatted(
                        botIdStr.substring(0, 8),
                        domain, method, gen,
                        w0, w1, w2, w3, w4, er,
                        pc1, pc2, pr1, pr2,
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
            "w0", "w1", "w2", "w3", "w4", "explorationRate",
            "paramCenter1", "paramCenter2");

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
