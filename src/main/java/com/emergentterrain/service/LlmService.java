package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private final LlmRouter router;
    private final MathToolSuite toolSuite;
    private final RoomUpdateBroadcaster broadcaster;
    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Long> lastAcceptedAt       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastDigestFingerprint = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    // Independent semaphores — none can block another from checking its slot.
    // streamSlot allows 2 concurrent requests when OLLAMA_NUM_PARALLEL >= 3.
    // narrationSlot permits = primaryPoolSize (one concurrent narration per active Groq model).
    // refinementSlot limits concurrent GPU refinement loops independent of narration.
    // (genomeSlot moved to GenomeMentoringService in Phase 0 Task 0.2.)
    private final Semaphore consultSlot      = new Semaphore(1);  // policy consultation (sync)
    private final Semaphore streamSlot       = new Semaphore(2);  // government digest (async stream)
    private final Semaphore refinementSlot   = new Semaphore(2);  // pre-narration computation refinement
    private final Semaphore narrationSlot;                         // paper narration — set from primaryPoolSize
    private final Semaphore formulationSlot  = new Semaphore(1);  // pre-computation formulation (sync)

    // Set during wireUniversityCallback — calls university.publishPaper on narration completion.
    private volatile java.util.function.BiConsumer<PaperDraft, NarrationResult> narrationPublisher;

    private final long digestCooldownMs;

    public LlmService(LlmRouter router,
                      RoomUpdateBroadcaster broadcaster,
                      RoomBotRepository botRepository,
                      ObjectMapper objectMapper,
                      @Value("${app.ai.digestCooldownMinutes:10}") long digestCooldownMinutes) {
        this.router           = router;
        this.toolSuite        = new MathToolSuite(router, objectMapper);
        this.broadcaster      = broadcaster;
        this.botRepository    = botRepository;
        this.objectMapper     = objectMapper;
        this.digestCooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0L, digestCooldownMinutes));
        this.narrationSlot    = new Semaphore(router.primaryPoolSize());
        log.info("LlmService: primary={} fast={} narrationSlots={}",
                router.primaryModel(), router.fastModel(), narrationSlot.availablePermits());
    }

    /** Wire up the mentoring callback into UniversityService after the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setNarratingCallback(this::enqueueNarration);
            university.setFormulationCallback(args -> {
                String domain         = (String) args[0];
                String topicName      = (String) args[1];
                BotGenome genome      = (BotGenome) args[2];
                @SuppressWarnings("unchecked")
                List<ResearchPaper> papers = (List<ResearchPaper>) args[3];
                String hypothesis     = (args.length > 4) ? (String) args[4] : null;
                return formulateResearch(domain, topicName, genome, papers, hypothesis);
            });
            this.narrationPublisher = university::publishPaper;
            log.info("LlmService: narration + formulation callbacks wired to UniversityService");
        }
    }

    public record FormulationResult(int param1, double param2, String researchQuestion,
                                    PreRegistration preRegistration) {}

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
            String response = router.callFast(prompt);
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
                    router.streamFast(prompt,
                            token -> broadcaster.broadcastLlmToken(roomId, streamId, kind, token));
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
                                               BotGenome genome, List<ResearchPaper> priorPapers,
                                               String researchHypothesis) {
        if (!formulationSlot.tryAcquire()) {
            log.debug("LLM formulation slot busy — falling back to genome sampling");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            NoveltyAssessment novelty = checkNovelty(domain, topicName, priorPapers);
            String prompt = buildFormulationPrompt(domain, topicName, genome, priorPapers, researchHypothesis, novelty);
            String response = router.callFast(prompt);
            log.info("LLM formulation done domain={} topic={} novelty={} ms={}", domain, topicName,
                    String.format("%.2f", novelty.score()), System.currentTimeMillis() - start);
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
                                          BotGenome genome, List<ResearchPaper> priorPapers,
                                          String researchHypothesis, NoveltyAssessment novelty) {
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

        String hypothesisSection = (researchHypothesis != null && !researchHypothesis.isBlank())
                ? "Researcher's current research direction: " + researchHypothesis + "\n"
                : "";

        // Anomalous discoveries from adjacent topics — cross-topic context for parameter choice
        StringBuilder domainAnomalies = new StringBuilder();
        priorPapers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain)
                          && !p.topic().equalsIgnoreCase(topicName)
                          && Math.abs(p.anomalyZScore()) >= 1.5)
                .sorted((a, b) -> Double.compare(Math.abs(b.anomalyZScore()), Math.abs(a.anomalyZScore())))
                .limit(3)
                .forEach(p -> {
                    String snippet = p.findings().length() > 100
                            ? p.findings().substring(0, 100) + "…" : p.findings();
                    domainAnomalies.append("  %s / %s [z=%+.1f score=%.2f]: %s\n"
                            .formatted(p.paperId(), p.topic(),
                                    p.anomalyZScore(), p.peerScore(), snippet));
                });
        String anomalyContext = domainAnomalies.isEmpty() ? ""
                : "Anomalous findings in adjacent topics (same domain) — consider whether similar "
                  + "parameter regions might be interesting in this topic too:\n"
                  + domainAnomalies + "\n";

        String noveltyContext = "";
        if (novelty.isSaturated()) {
            noveltyContext = "NOVELTY WARNING (score=%.2f/1.0): This topic is becoming saturated. "
                    .formatted(novelty.score())
                    + (novelty.coverageNote().isBlank() ? "" : "Covered so far: " + novelty.coverageNote() + ". ")
                    + (novelty.unexploredHint().isBlank()
                        ? "Choose parameters that differ meaningfully from all prior work above.\n"
                        : "Unexplored angle: " + novelty.unexploredHint() + " Prioritise this gap.\n");
        } else if (novelty.score() < 0.60 && !novelty.unexploredHint().isBlank()) {
            noveltyContext = "NOVELTY NOTE (score=%.2f/1.0): Suggested unexplored angle: %s\n"
                    .formatted(novelty.score(), novelty.unexploredHint());
        }

        return """
                You are a research advisor choosing where a computational researcher should focus next.

                Domain: %s | Topic: %s
                Researcher's learned search region: param1 center=%d (±%d), param2 center=%.1f (±%.1f)
                param1 is an integer 10–99 (controls the starting position of the search space).
                param2 is a decimal 1.0–9.0 (controls depth/scale of the search).
                %s%s
                Prior papers in this topic:
                %s%s
                Based on the prior work, choose a specific unexplored or promising parameter region
                within or near the researcher's learned search region. Explain the research question
                this choice targets in one sentence.

                Before any computation runs, pre-register your hypothesis. This is locked and \
                cannot be revised once results are seen.

                Reply with ONLY this JSON, no other text:
                {"param1":N,"param2":X.X,"question":"one sentence research question",\
                "pre_registration":{"hypothesis":"one testable claim this parameter choice will investigate",\
                "prediction":"expected direction or magnitude (e.g. monotone increase, value near 0.3)",\
                "falsification_condition":"what outcome would contradict this (e.g. no correlation, 0 exceedance)",\
                "mapping_notes":"if crossing domains: state any constants or analogies and their justification; empty string otherwise"}}

                param1 must be an integer 10–99. param2 must be a decimal 1.0–9.0.
                Choose values that differ from or extend what prior papers have already explored.
                """.formatted(domain, topicName, center1, radius1, center2, radius2,
                        hypothesisSection, noveltyContext, priorSection.trim(), anomalyContext);
    }

    private FormulationResult parseFormulationResult(String response) {
        return parseFormulationResult(objectMapper, response);
    }

    static FormulationResult parseFormulationResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            int    p1 = root.path("param1").asInt(-1);
            double p2 = root.path("param2").asDouble(-1.0);
            String q  = root.path("question").asText(null);
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) {
                return null;
            }
            PreRegistration preReg = null;
            com.fasterxml.jackson.databind.JsonNode prNode = root.get("pre_registration");
            if (prNode != null && !prNode.isNull()) {
                String hyp   = prNode.path("hypothesis").asText(null);
                String pred  = prNode.path("prediction").asText(null);
                String falsc = prNode.path("falsification_condition").asText(null);
                String mapN  = prNode.path("mapping_notes").asText(null);
                if (hyp != null && !hyp.isBlank() && pred != null && !pred.isBlank()) {
                    preReg = new PreRegistration(hyp, pred, falsc, mapN);
                }
            }
            return new FormulationResult(p1, p2, q, preReg);
        } catch (Exception e) {
            log.warn("LLM formulation: JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private NoveltyAssessment checkNovelty(String domain, String topicName, List<ResearchPaper> papers) {
        long count = papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .count();
        if (count < 3) return new NoveltyAssessment(1.0, "", "");
        try {
            long start = System.currentTimeMillis();
            String response = router.callFast(buildNoveltyPrompt(domain, topicName, papers, count));
            NoveltyAssessment result = parseNoveltyAssessment(response);
            log.info("LLM novelty check done domain={} topic={} score={} saturated={} ms={}",
                    domain, topicName, String.format("%.2f", result.score()),
                    result.isSaturated(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("LLM novelty check failed domain={} topic={}: {}", domain, topicName, e.getMessage());
            return new NoveltyAssessment(1.0, "", "");
        }
    }

    private static String buildNoveltyPrompt(String domain, String topicName,
                                              List<ResearchPaper> papers, long totalCount) {
        StringBuilder recent = new StringBuilder();
        papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .limit(8)
                .forEach(p -> {
                    String snippet = p.findings().length() > 100
                            ? p.findings().substring(0, 100) + "…" : p.findings();
                    recent.append("  %s [score %.2f]: %s\n".formatted(p.paperId(), p.peerScore(), snippet));
                });
        return """
                You are assessing how much computational novelty remains in a research topic.

                Domain: %s | Topic: %s
                Total papers published in this topic so far: %d

                Most recent papers (findings summaries):
                %s
                Rate how much unexplored territory remains: 1.0 = completely fresh, 0.0 = fully saturated.
                Note which parameter regions or angles have NOT been tried yet.

                Reply with ONLY this JSON — no other text:
                {"novelty_score":0.7,"coverage_note":"brief note on what has been covered","unexplored_hint":"specific suggestion for an unexplored region or angle; empty string if no clear gap"}
                """.formatted(domain, topicName, totalCount, recent.toString().trim());
    }

    private NoveltyAssessment parseNoveltyAssessment(String response) {
        return parseNoveltyAssessment(objectMapper, response);
    }

    static NoveltyAssessment parseNoveltyAssessment(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return new NoveltyAssessment(1.0, "", "");
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new NoveltyAssessment(1.0, "", "");
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            double score = Math.max(0.0, Math.min(1.0, root.path("novelty_score").asDouble(1.0)));
            String coverageNote   = root.path("coverage_note").asText("");
            String unexploredHint = root.path("unexplored_hint").asText("");
            return new NoveltyAssessment(score, coverageNote, unexploredHint);
        } catch (Exception e) {
            return new NoveltyAssessment(1.0, "", "");
        }
    }

    // --- Paper narration ---

    /**
     * Submits a passed paper for LLM narrative synthesis on a virtual thread.
     * Refinement and narration run on separate slots so a slow GPU refinement loop
     * does not block other papers from starting their Groq narration call.
     */
    public void enqueueNarration(PaperDraft draft) {
        executor.execute(() -> refineAndNarrate(draft));
    }

    private void refineAndNarrate(PaperDraft draft) {
        refinementSlot.acquireUninterruptibly();
        PaperDraft enrichedDraft;
        try {
            enrichedDraft = maybeRefineComputation(draft);
        } finally {
            refinementSlot.release();
        }
        // Statistical framing — fast call between refinement and narration; best-effort
        PaperDraft framedDraft = applyStatisticalFraming(enrichedDraft);
        narrationSlot.acquireUninterruptibly();
        try {
            runNarration(framedDraft);
        } finally {
            narrationSlot.release();
        }
    }

    record RefinementDecision(int param1, double param2, String rationale) {}

    /** Novelty assessment for a domain+topic before formulation. score=1.0 means fully fresh. */
    record NoveltyAssessment(double score, String coverageNote, String unexploredHint) {
        boolean isSaturated() { return score < 0.30; }
    }

    /**
     * An experiment prescribed by the professor (critique LLM) to address a specific weakness.
     * type="param"    → run MathPhysicsEngine.compute at param1/param2
     * type="dynamic"  → run DynamicComputeEngine with predicate/range (MATHEMATICS / INTERDISCIPLINARY only)
     */
    record ExperimentalGap(
            String type, String description, String rationale,
            Integer param1, Double param2,               // param path
            String predicate, Integer rangeStart, Integer rangeEnd,  // dynamic path
            String metric, double baseline, String hypothesis) {}

    /** Full structured result from the professor critique call. */
    record CritiqueResult(List<String> weaknesses, ExperimentalGap experimentalGap) {
        boolean hasActionableFeedback() {
            return !weaknesses.isEmpty() || experimentalGap != null;
        }
    }

    private void runNarration(PaperDraft draft) {
        // End-game: all Groq models TPD-exhausted — suppress and log recovery time
        if (router.primaryFullyExhausted()) {
            java.time.Instant recovery = router.earliestPrimaryRecovery();
            log.error("LLM RESEARCH PAUSED — all Groq models TPD-exhausted. " +
                    "Earliest recovery: {}. paperId={} suppressed.", recovery, draft.paperId());
            if (narrationPublisher != null) narrationPublisher.accept(draft, null);
            return;
        }

        List<String> callPath = new java.util.ArrayList<>();

        // First-pass narration (Groq/primary stream)
        String prompt = buildNarrationPrompt(draft);
        long start = System.currentTimeMillis();
        StringBuilder response = new StringBuilder();
        try {
            router.streamPrimary(prompt, response::append);
            callPath.add("fp:" + abbrev(router.primaryModel()));
            log.info("LLM narration first-pass done paperId={} ms={}", draft.paperId(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            callPath.add("fp:FAILED");
            log.warn("LLM narration failed paperId={} ms={}: {} — publishing with template text",
                    draft.paperId(), System.currentTimeMillis() - start, e.getMessage());
        }

        NarrationResult firstPass = parseNarrationResult(response.toString());
        NarrationResult finalResult = maybeCritiqueAndRevise(draft, firstPass, callPath);

        String pathStr = String.join(" → ", callPath);
        log.info("LLM call path paperId={}: {}", draft.paperId(), pathStr);

        // null means professor blank or all-TPD — pass null so queue counter is decremented
        NarrationResult result = finalResult == null ? null : new NarrationResult(
                finalResult.hypothesis(), finalResult.findings(), finalResult.conclusion(),
                finalResult.followUp(), router.primaryModel(),
                finalResult.confidenceScore(), finalResult.citedPaperIds(), pathStr);
        if (narrationPublisher != null) {
            narrationPublisher.accept(draft, result);
        }
    }

    // --- Self-critique + revision ---

    /**
     * Professor–student collaboration loop.
     *
     * Tool-calling path (Groq available): the professor is a Groq model with four
     * mathematical tools it can call freely — param sweep, predicate scan, symbolic
     * evaluation, and sandboxed code (reviewed by 4b before execution). After calling
     * any tools it deems necessary, it writes a synthesis. The student (another Groq
     * call) then revises the paper incorporating the professor's findings.
     *
     * Fallback path (Ollama only): uses the existing JSON-based ExperimentalGap approach
     * (fast Ollama critique → prescribed experiment → Groq revision).
     *
     * Falls back to firstPass on any failure.
     */
    private NarrationResult maybeCritiqueAndRevise(PaperDraft draft, NarrationResult firstPass,
                                                    List<String> callPath) {
        if (firstPass.hypothesis() == null || firstPass.findings() == null || firstPass.conclusion() == null) {
            callPath.add("critique:skip:incomplete_firstpass");
            return firstPass;
        }
        return router.supportsProfessorTools()
                ? maybeCritiqueAndReviseWithTools(draft, firstPass, callPath)
                : maybeCritiqueAndReviseJsonPath(draft, firstPass, callPath);
    }

    /** Tool-calling professor path: audit → professor (tool-calling) → student revision. */
    private NarrationResult maybeCritiqueAndReviseWithTools(PaperDraft draft, NarrationResult firstPass,
                                                             List<String> callPath) {
        try {
            // Phase 1 — Quality audit (fast Ollama): identify gaps before professor is invoked
            String auditSummary = runQualityAudit(draft, firstPass, callPath);

            // Phase 2 — Professor (Groq tool-calling), directed by audit gaps
            long start = System.currentTimeMillis();
            boolean includeDynamic = "MATHEMATICS".equalsIgnoreCase(draft.domain())
                    || "INTERDISCIPLINARY".equalsIgnoreCase(draft.domain());
            String toolsJson    = toolSuite.toolsJson(includeDynamic);
            String systemPrompt = buildProfessorSystemPrompt(auditSummary);
            String userPrompt   = buildProfessorUserPrompt(draft, firstPass);

            String professorSynthesis = router.callPrimaryWithTools(
                    systemPrompt, userPrompt, toolsJson,
                    toolSuite.buildExecutor(draft));

            if (professorSynthesis == null || professorSynthesis.isBlank()) {
                callPath.add("prof:" + abbrev(router.primaryModel()) + ":blank→suppressed");
                log.warn("LLM professor tools: blank synthesis paperId={} ms={} — draft suppressed",
                        draft.paperId(), System.currentTimeMillis() - start);
                return null;
            }
            callPath.add("prof:" + abbrev(router.primaryModel()) + ":ok");
            log.info("LLM professor tools: synthesis_len={} paperId={} ms={}",
                    professorSynthesis.length(), draft.paperId(), System.currentTimeMillis() - start);

            // Phase 3 — Student revision incorporating professor's findings
            long revStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            router.streamPrimary(buildRevisionPromptFromSynthesis(draft, firstPass, professorSynthesis),
                    revised::append);
            NarrationResult revParsed = parseNarrationResult(revised.toString());
            if (revParsed.hypothesis() != null && revParsed.findings() != null && revParsed.conclusion() != null) {
                callPath.add("rev:" + abbrev(router.primaryModel()) + ":ok");
                log.info("LLM student revision done paperId={} ms={}", draft.paperId(),
                        System.currentTimeMillis() - revStart);
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                return new NarrationResult(
                        revParsed.hypothesis(), revParsed.findings(), revParsed.conclusion(),
                        followUp, router.primaryModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds());
            }
            callPath.add("rev:parse_failed→fp");
        } catch (Exception e) {
            if (router.primaryFullyExhausted()) {
                java.time.Instant recovery = router.earliestPrimaryRecovery();
                callPath.add("prof:ALL_TPD→suppressed:recovery=" + recovery);
                log.error("LLM ALL GROQ MODELS TPD-EXHAUSTED — research paused. " +
                        "Earliest recovery: {}. paperId={} suppressed.", recovery, draft.paperId());
                return null;
            }
            callPath.add("prof:error→fp");
            log.warn("LLM professor tools path failed paperId={}: {} — using first-pass narration",
                    draft.paperId(), e.getMessage());
        }
        return firstPass;
    }

    /** Legacy JSON-based path — fast Ollama critique + prescribed experiment + Groq revision. */
    private NarrationResult maybeCritiqueAndReviseJsonPath(PaperDraft draft, NarrationResult firstPass,
                                                            List<String> callPath) {
        try {
            long critiqueStart = System.currentTimeMillis();
            String critiqueResponse = router.callFast(buildCritiquePrompt(draft, firstPass));
            CritiqueResult critique = parseCritiqueResult(critiqueResponse);

            if (!critique.hasActionableFeedback()) {
                callPath.add("json_critique:4b:clean→fp");
                log.info("LLM professor review (json): clean draft paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - critiqueStart);
                return firstPass;
            }
            callPath.add("json_critique:4b:" + critique.weaknesses().size() + "gaps");
            log.info("LLM professor review (json): {} weakness(es) experiment={} paperId={} ms={}",
                    critique.weaknesses().size(), critique.experimentalGap() != null,
                    draft.paperId(), System.currentTimeMillis() - critiqueStart);

            String professorExperiment = null;
            if (critique.experimentalGap() != null) {
                professorExperiment = runProfessorDirectedExperiment(draft, critique.experimentalGap());
            }

            long revisionStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            router.streamPrimary(
                    buildRevisionPrompt(draft, firstPass, critique.weaknesses(), professorExperiment),
                    revised::append);
            NarrationResult revParsed = parseNarrationResult(revised.toString());
            if (revParsed.hypothesis() != null && revParsed.findings() != null && revParsed.conclusion() != null) {
                callPath.add("json_rev:" + abbrev(router.primaryModel()) + ":ok");
                log.info("LLM student revision (json) done paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - revisionStart);
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                return new NarrationResult(
                        revParsed.hypothesis(), revParsed.findings(), revParsed.conclusion(),
                        followUp, router.primaryModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds());
            }
            callPath.add("json_rev:parse_failed→fp");
        } catch (Exception e) {
            callPath.add("json_critique:error→fp");
            log.warn("LLM professor+revision (json) failed paperId={}: {} — using first-pass narration",
                    draft.paperId(), e.getMessage());
        }
        return firstPass;
    }

    /**
     * Executes the professor-prescribed experiment synchronously.
     * Returns a formatted summary string for the revision prompt, or null on failure.
     */
    private String runProfessorDirectedExperiment(PaperDraft draft, ExperimentalGap gap) {
        try {
            long start = System.currentTimeMillis();
            MathPhysicsEngine.ResearchResult result;

            if ("dynamic".equals(gap.type()) && gap.predicate() != null && gap.rangeStart() != null) {
                String id = "prof-" + draft.paperId() + "-" + System.currentTimeMillis();
                com.emergentterrain.research.DynamicProblem problem =
                        new com.emergentterrain.research.DynamicProblem(
                                id, gap.description(), gap.rangeStart(), gap.rangeEnd(),
                                gap.predicate(), gap.metric(), gap.baseline(), gap.hypothesis());
                result = com.emergentterrain.research.DynamicComputeEngine.compute(problem);
            } else if ("param".equals(gap.type()) && gap.param1() != null && gap.param2() != null) {
                int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
                if (topicIdx < 0) return null;
                MathPhysicsEngine.SearchParams focusSp =
                        new MathPhysicsEngine.SearchParams(0.1, 0.0, 10, 0.5, 0.0);
                result = MathPhysicsEngine.compute(
                        draft.domain(), topicIdx, gap.param1(), gap.param2(), focusSp);
            } else {
                return null;
            }

            // Best-effort statistical framing of the new result
            String findings = result.findings();
            try {
                String framed = parseStatisticalFraming(router.callFast(
                        buildStatisticalFramingPrompt(findings, draft.domain(), draft.topicName())));
                if (framed != null) findings = framed;
            } catch (Exception ignored) {}

            log.info("LLM professor experiment done paperId={} type={} ms=\"{}\" findings=\"{}\"",
                    draft.paperId(), gap.type(), System.currentTimeMillis() - start,
                    findings.length() > 100 ? findings.substring(0, 100) + "…" : findings);

            return "Professor-directed experiment — \"%s\":\n%s\n(Rationale: %s)"
                    .formatted(gap.description(), findings, gap.rationale());
        } catch (Exception e) {
            log.warn("LLM professor experiment failed paperId={}: {}", draft.paperId(), e.getMessage());
            return null;
        }
    }

    static String buildCritiquePrompt(PaperDraft draft, NarrationResult narration) {
        String preRegLine = draft.preRegistration() != null
                ? "Pre-registered prediction: " + draft.preRegistration().prediction() + "\n" : "";
        String classLine  = draft.resultClassification() != null
                ? "Result classification: " + draft.resultClassification() + "\n" : "";

        boolean canParam   = draft.param1() != null
                && MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName()) >= 0;
        boolean canDynamic = canParam
                && ("MATHEMATICS".equalsIgnoreCase(draft.domain())
                    || "INTERDISCIPLINARY".equalsIgnoreCase(draft.domain()));

        String experimentSpec;
        if (canDynamic) {
            experimentSpec = """
                    You may prescribe ONE of:
                    • Param-based (any topic): \
                    {"type":"param","param1":75,"param2":5.0,"description":"...","rationale":"..."}
                    • Number-theoretic (MATHEMATICS/INTERDISCIPLINARY): \
                    {"type":"dynamic","predicate":"isPrime AND isHappy","range_start":200,"range_end":500,\
                    "metric":"fraction_satisfying","hypothesis":"...","baseline":0.04,\
                    "description":"...","rationale":"..."}
                    Valid predicates: isPrime isHappy collatzTerminates isAbundant isPerfect (AND/OR)
                    Valid metrics: fraction_satisfying mean_of:collatzSteps max_of:collatzSteps mean_of:sigmaSum
                    param1 int 10–99, param2 decimal 1.0–9.0. range_end − range_start ≤ 500.
                    """;
        } else if (canParam) {
            experimentSpec = """
                    You may prescribe ONE param-based computation: \
                    {"type":"param","param1":75,"param2":5.0,"description":"...","rationale":"..."}
                    param1 int 10–99, param2 decimal 1.0–9.0.
                    """;
        } else {
            experimentSpec = "No additional computation is possible for this paper type.\n";
        }

        return """
                You are a professor reviewing a student's computational research paper.

                Domain: %s | Topic: %s
                %s%s
                Student's draft:
                Hypothesis: %s
                Findings: %s
                Conclusion: %s

                Provide two types of feedback:
                1. Up to 2 textual weaknesses (overclaiming, missing qualifications, logic gaps,
                   conclusions that contradict the pre-registered prediction).
                2. ONE prescribed experiment — a specific computation that would directly address
                   a real weakness in the paper. Only prescribe if it would materially change
                   what can be claimed. Omit if the paper is already well-supported.

                %s
                Reply with ONLY this JSON — no other text:
                {"weaknesses":["w1","w2"],"experimental_gap":{...}}
                Omit experimental_gap entirely if no computation would help.
                Return weaknesses as an empty array if the draft is sound.
                """.formatted(
                draft.domain(), draft.topicName(),
                preRegLine, classLine,
                narration.hypothesis(), narration.findings(), narration.conclusion(),
                experimentSpec);
    }

    // ── Tool-calling professor prompts ────────────────────────────────────────

    // ── Audit gap record ─────────────────────────────────────────────────────
    private record AuditGap(String dimension, String severity, String description) {}

    // ── Quality audit (fast Ollama) ──────────────────────────────────────────

    private String runQualityAudit(PaperDraft draft, NarrationResult firstPass,
                                    List<String> callPath) {
        try {
            long t = System.currentTimeMillis();
            String raw = router.callFast(buildAuditPrompt(draft, firstPass));
            List<AuditGap> gaps = parseAuditGaps(objectMapper, raw);
            if (gaps.isEmpty()) {
                callPath.add("audit:4b:0gaps");
                log.info("LLM quality audit paperId={} ms={} 0 gaps", draft.paperId(),
                        System.currentTimeMillis() - t);
                return "";
            }
            String pathEntry = gaps.stream()
                    .map(g -> g.dimension() + "(" + g.severity().charAt(0) + ")")
                    .collect(java.util.stream.Collectors.joining(","));
            callPath.add("audit:4b:" + gaps.size() + "gaps=[" + pathEntry + "]");
            log.info("LLM quality audit paperId={} ms={} gaps={}", draft.paperId(),
                    System.currentTimeMillis() - t, gaps.size());
            return gaps.stream()
                    .map(g -> "- [" + g.severity().toUpperCase() + "] " + g.dimension() + ": " + g.description())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            callPath.add("audit:4b:failed");
            log.warn("LLM quality audit failed paperId={}: {}", draft.paperId(), e.getMessage());
            return "";
        }
    }

    static String buildAuditPrompt(PaperDraft draft, NarrationResult narration) {
        return """
                Assess this computational research paper for quality gaps.
                Only flag material issues. Return an empty "gaps" array if the paper is fundamentally sound.

                Domain: %s | Topic: %s | Classification: %s
                Hypothesis: %s
                Findings: %s
                Conclusion: %s

                Check only these dimensions (skip if not problematic):
                - physical_mechanism: cross-domain mapping is purely definitional with no causal justification
                - degenerate_result: reported best result is a trivial edge case (e.g. zero/null/empty)
                - duplicate_computation: two or more computations tested identical parameter values
                - uncertainty: quantitative claims lack uncertainty language or error bounds
                - overclaiming: conclusion asserts more than the data demonstrates

                Reply with ONLY valid JSON:
                {"gaps":[{"dimension":"...","severity":"critical|moderate","description":"one sentence"}]}
                """.formatted(draft.domain(), draft.topicName(),
                draft.resultClassification() != null ? draft.resultClassification() : "UNKNOWN",
                narration.hypothesis(), narration.findings(), narration.conclusion());
    }

    static List<AuditGap> parseAuditGaps(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return List.of();
        int s = response.indexOf('{'), e = response.lastIndexOf('}');
        if (s < 0 || e <= s) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.substring(s, e + 1));
            List<AuditGap> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode g : root.path("gaps")) {
                String dim  = g.path("dimension").asText("").trim();
                String sev  = g.path("severity").asText("moderate").trim();
                String desc = g.path("description").asText("").trim();
                if (!dim.isBlank() && !desc.isBlank()) out.add(new AuditGap(dim, sev, desc));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String abbrev(String model) {
        if (model == null) return "?";
        if (model.length() <= 18) return model;
        // Keep the first 18 chars (e.g. "llama-3.3-70b-vers" → "llama-3.3-70b-ver")
        return model.substring(0, 18);
    }

    static String buildProfessorSystemPrompt() {
        return buildProfessorSystemPrompt("");
    }

    static String buildProfessorSystemPrompt(String auditSummary) {
        String auditSection = (auditSummary != null && !auditSummary.isBlank())
                ? "\nA quality audit identified these specific gaps. Address them with your tools:\n"
                  + auditSummary + "\n"
                : "";
        return """
                You are a professor reviewing a student's computational research paper.
                You have access to mathematical tools to investigate gaps you identify.
                Use tools when they would provide new evidence that materially changes what can be claimed.
                You do not need to use tools if the paper is already well-supported.
                %s
                After any tool calls, write a concise synthesis:
                  1. List specific textual weaknesses (overclaiming, missing error bounds,
                     inconsistency with pre-registration, unsupported leaps). Omit if none.
                  2. Summarise what your experiments revealed and how they bear on the claims.
                Be direct and specific. If the paper is fundamentally sound, say so briefly.
                If run_sandboxed_code is REJECTED, revise the code to address the rejection reason
                and submit again — you have additional tool rounds available.
                """.formatted(auditSection);
    }

    static String buildProfessorUserPrompt(PaperDraft draft, NarrationResult narration) {
        String preRegLine = draft.preRegistration() != null
                ? "Pre-registered prediction: " + draft.preRegistration().prediction() + "\n" : "";
        String classLine  = draft.resultClassification() != null
                ? "Result classification: " + draft.resultClassification() + "\n" : "";
        return """
                Paper for review:
                Domain: %s | Topic: %s
                %s%s
                Hypothesis: %s
                Findings: %s
                Conclusion: %s
                """.formatted(draft.domain(), draft.topicName(),
                preRegLine, classLine,
                narration.hypothesis(), narration.findings(), narration.conclusion());
    }

    static String buildRevisionPromptFromSynthesis(PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis) {
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        String paramsLine = draft.param1() != null
                ? "Primary parameters: param1=%d, param2=%.1f\n".formatted(draft.param1(), draft.param2())
                : "";
        return """
                You are a student revising your computational research paper based on your professor's review.
                Incorporate the professor's experimental findings naturally into the revised text.
                Address the identified weaknesses. Do not introduce new claims beyond what the data supports.

                %sDomain: %s | Topic: %s

                PROFESSOR'S REVIEW:
                %s

                CURRENT DRAFT:
                Hypothesis: %s
                Findings: %s
                Conclusion: %s

                Rewrite the paper as:
                {"hypothesis":"...",
                 "findings":"2-3 sentences with appropriate precision; incorporate new experimental evidence",
                 %s,
                 "confidenceScore":0.0-1.0}
                Reply with ONLY the JSON.
                """.formatted(paramsLine, draft.domain(), draft.topicName(),
                professorSynthesis,
                firstPass.hypothesis(), firstPass.findings(), firstPass.conclusion(),
                conclusionSpec);
    }

    // ─────────────────────────────────────────────────────────────────────────

    static String buildRevisionPrompt(PaperDraft draft, NarrationResult firstPass,
                                      List<String> weaknesses) {
        return buildRevisionPrompt(draft, firstPass, weaknesses, null);
    }

    static String buildRevisionPrompt(PaperDraft draft, NarrationResult firstPass,
                                      List<String> weaknesses, String professorExperiment) {
        String weaknessList = weaknesses.isEmpty() ? "  (no textual weaknesses — address the new experimental evidence only)"
                : weaknesses.stream()
                        .map(w -> "  - " + w)
                        .collect(java.util.stream.Collectors.joining("\n"));
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        String experimentSection = professorExperiment == null ? ""
                : "\nPROFESSOR-PRESCRIBED EXPERIMENT (run after the first draft):\n"
                  + professorExperiment
                  + "\nIntegrate this new evidence naturally. In your findings, reference it as a "
                  + "follow-up test. In your conclusion, state whether it resolves the weakness "
                  + "or raises a new question.\n";
        return """
                You are a student revising your computational research paper based on your \
                professor's feedback.

                Domain: %s | Topic: %s

                YOUR ORIGINAL DRAFT:
                Hypothesis: %s
                Findings: %s
                Conclusion: %s

                PROFESSOR'S FEEDBACK:
                %s%s
                Revise your paper. Keep correct content unchanged. Qualify over-claims. \
                If new experimental evidence is shown above, incorporate it naturally — \
                do not fabricate results beyond what is stated. Do not change physical quantities.

                Reply with ONLY this JSON — no markdown, no preamble:
                {"hypothesis":"revised hypothesis","findings":"revised 2-3 sentence findings",%s,\
                "confidenceScore":0.75,"cited_paper_ids":[]}

                All text values plain prose, under 120 words each.
                """.formatted(
                draft.domain(), draft.topicName(),
                firstPass.hypothesis(), firstPass.findings(), firstPass.conclusion(),
                weaknessList, experimentSection, conclusionSpec);
    }

    private List<String> parseCritiqueWeaknesses(String response) {
        return parseCritiqueWeaknesses(objectMapper, response);
    }

    static List<String> parseCritiqueWeaknesses(ObjectMapper mapper, String response) {
        return parseCritiqueResult(mapper, response).weaknesses();
    }

    private CritiqueResult parseCritiqueResult(String response) {
        return parseCritiqueResult(objectMapper, response);
    }

    static CritiqueResult parseCritiqueResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return new CritiqueResult(List.of(), null);
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new CritiqueResult(List.of(), null);
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));

            // Parse weaknesses
            List<String> weaknesses = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode arr = root.get("weaknesses");
            if (arr != null && arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    String w = node.asText(null);
                    if (w != null && !w.isBlank()) weaknesses.add(w);
                }
            }

            // Parse experimental_gap
            ExperimentalGap gap = null;
            com.fasterxml.jackson.databind.JsonNode gapNode = root.get("experimental_gap");
            if (gapNode != null && !gapNode.isNull() && !gapNode.isMissingNode()) {
                gap = parseExperimentalGap(gapNode);
            }
            return new CritiqueResult(List.copyOf(weaknesses), gap);
        } catch (Exception e) {
            return new CritiqueResult(List.of(), null);
        }
    }

    private static ExperimentalGap parseExperimentalGap(
            com.fasterxml.jackson.databind.JsonNode node) {
        String type        = node.path("type").asText(null);
        String description = node.path("description").asText(null);
        String rationale   = node.path("rationale").asText("");
        if (description == null || description.isBlank() || type == null) return null;

        if ("param".equals(type)) {
            int    p1 = node.path("param1").asInt(-1);
            double p2 = node.path("param2").asDouble(-1.0);
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) return null;
            return new ExperimentalGap(type, description, rationale,
                    p1, p2, null, null, null, null, 0.0, null);
        }
        if ("dynamic".equals(type)) {
            String predicate  = node.path("predicate").asText(null);
            int    rangeStart = node.path("range_start").asInt(-1);
            int    rangeEnd   = node.path("range_end").asInt(-1);
            String metric     = node.path("metric").asText("fraction_satisfying");
            double baseline   = node.path("baseline").asDouble(0.0);
            String hypothesis = node.path("hypothesis").asText(null);
            if (predicate == null || rangeStart < 1 || rangeEnd <= rangeStart
                    || rangeEnd - rangeStart > 500) return null;
            for (String token : predicate.replace(" AND ", "|").replace(" OR ", "|").split("\\|")) {
                if (!VALID_PREDICATES.contains(token.trim())) return null;
            }
            if (!VALID_METRICS.contains(metric)) return null;
            return new ExperimentalGap(type, description, rationale,
                    null, null, predicate, rangeStart, rangeEnd, metric, baseline, hypothesis);
        }
        return null;
    }

    private static final int MAX_REFINEMENTS = 2;

    /**
     * Multi-step computation loop: runs up to MAX_REFINEMENTS additional focused
     * computations before narration. Each iteration shows the LLM all findings so far;
     * it decides "refine" or "done". Runs inside refinementSlot (permits=2), decoupled
     * from narrationSlot so concurrent narrations are not blocked by GPU compute.
     * Dynamic papers (param1==null) are always skipped.
     */
    private PaperDraft maybeRefineComputation(PaperDraft draft) {
        if (draft.param1() == null) return draft;
        int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
        if (topicIdx < 0) return draft;

        MathPhysicsEngine.SearchParams focusSp =
                new MathPhysicsEngine.SearchParams(0.2, 0.0, 20, 0.5, 0.0);

        String accumulatedFindings = draft.rawFindings();
        int completedRefinements = 0;

        // Track visited (param1, param2) pairs to reject duplicate refinement proposals
        Set<String> visitedParams = new HashSet<>();
        if (draft.param1() != null) {
            visitedParams.add(draft.param1() + "_" + String.format("%.1f", draft.param2()));
        }

        for (int iteration = 1; iteration <= MAX_REFINEMENTS; iteration++) {
            long start = System.currentTimeMillis();
            try {
                String prompt = buildRefinementPrompt(
                        draft, accumulatedFindings, iteration, MAX_REFINEMENTS);
                String response = router.callFast(prompt);
                RefinementDecision decision = parseRefinementDecision(response);
                if (decision == null) {
                    log.debug("LLM refinement {}/{} done (model says stop) paperId={}",
                            iteration, MAX_REFINEMENTS, draft.paperId());
                    break;
                }

                // Reject duplicate parameter proposals — these add no new information
                String paramKey = decision.param1() + "_" + String.format("%.1f", decision.param2());
                if (visitedParams.contains(paramKey)) {
                    log.debug("LLM refinement {}/{} proposed duplicate params (param1={}, param2={}) — stopping paperId={}",
                            iteration, MAX_REFINEMENTS, decision.param1(), decision.param2(), draft.paperId());
                    break;
                }
                visitedParams.add(paramKey);

                MathPhysicsEngine.ResearchResult refined = MathPhysicsEngine.compute(
                        draft.domain(), topicIdx, decision.param1(), decision.param2(), focusSp);

                accumulatedFindings += "\nComputation %d — %s (param1=%d, param2=%.1f): %s"
                        .formatted(iteration + 1, decision.rationale(),
                                decision.param1(), decision.param2(), refined.findings());
                completedRefinements++;

                log.info("LLM refinement {}/{} done paperId={} ms={} rationale=\"{}\"",
                        iteration, MAX_REFINEMENTS, draft.paperId(),
                        System.currentTimeMillis() - start, decision.rationale());

                // Min-1 guarantee satisfied — stop early only if other papers are waiting for narration
                if (narrationSlot.getQueueLength() > 0) {
                    log.debug("LLM refinement stopping after {}/{} — narration backlog depth={}",
                            iteration, MAX_REFINEMENTS, narrationSlot.getQueueLength());
                    break;
                }

            } catch (Exception e) {
                log.warn("LLM refinement {}/{} failed paperId={} ms={}: {}",
                        iteration, MAX_REFINEMENTS, draft.paperId(),
                        System.currentTimeMillis() - start, e.getMessage());
                break;
            }
        }

        if (completedRefinements == 0) return draft;

        return new PaperDraft(
                draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                draft.title(), accumulatedFindings,
                draft.templateHypothesis(), draft.templateConclusion(),
                draft.methodology(), draft.reviewerBotIds(),
                draft.peerScore(), draft.authorScore(), draft.anomalyZScore(),
                draft.gameTick(), draft.publishedAt(), draft.generation(),
                draft.paperId(), draft.param1(), draft.param2(),
                draft.isFollowUp(), draft.predictedScore(), draft.priorMethodology(),
                draft.recentPapers(), draft.researchHypothesis(),
                draft.preRegistration(), draft.resultClassification());
    }

    private String buildRefinementPrompt(PaperDraft draft, String accumulatedFindings,
                                          int iteration, int maxIterations) {
        String compact = compactRawFindings(accumulatedFindings);
        String iterContext = iteration == 1
                ? "initial result"
                : "results so far (%d computations completed)".formatted(iteration);
        return """
                You are guiding a multi-step computational investigation. \
                The researcher has %s — decide whether one more focused \
                computation would add a meaningfully distinct data point.

                Domain: %s | Topic: %s | Methodology: %s
                Primary parameters: param1=%d, param2=%.1f
                Computations so far:
                %s

                A good refinement target narrows to a sub-region where something \
                interesting occurred, tests a boundary condition, or checks an \
                adjacent parameter scale. It must produce quantitatively different \
                numbers — not a repeat of what is already shown.

                Remaining iterations allowed: %d of %d.

                Reply with ONLY one of these JSON forms — no other text:
                {"action":"refine","param1":N,"param2":X.X,"rationale":"one sentence — what this probes and why"}
                {"action":"done"}

                param1 int 10–99. param2 decimal 1.0–9.0. \
                Choose "done" if the finding is already well-characterised or \
                no clear target remains.
                """.formatted(
                iterContext,
                draft.domain(), draft.topicName(), draft.methodology(),
                draft.param1(), draft.param2(),
                compact,
                maxIterations - iteration + 1, maxIterations);
    }

    private RefinementDecision parseRefinementDecision(String response) {
        return parseRefinementDecision(objectMapper, response);
    }

    static RefinementDecision parseRefinementDecision(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            if ("done".equals(root.path("action").asText(null))) return null;
            int    p1 = root.path("param1").asInt(-1);
            double p2 = root.path("param2").asDouble(-1.0);
            String rationale = root.path("rationale").asText("");
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) return null;
            return new RefinementDecision(p1, p2, rationale);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Statistical framing (Gaps 4 & 5) ---

    /**
     * Rewrites rawFindings with appropriate significant figures and uncertainty qualifiers.
     * Fast Ollama call; falls back to the unmodified draft on any failure.
     * Skipped for dynamic papers (param1 == null).
     */
    private PaperDraft applyStatisticalFraming(PaperDraft draft) {
        if (draft.param1() == null) return draft;
        String raw = draft.rawFindings();
        if (raw == null || raw.isBlank()) return draft;
        try {
            long start = System.currentTimeMillis();
            String response = router.callFast(buildStatisticalFramingPrompt(raw, draft.domain(), draft.topicName()));
            String framed = parseStatisticalFraming(response);
            if (framed != null) {
                log.info("LLM statistical framing done paperId={} ms={}", draft.paperId(),
                        System.currentTimeMillis() - start);
                return new PaperDraft(
                        draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                        draft.title(), framed,
                        draft.templateHypothesis(), draft.templateConclusion(),
                        draft.methodology(), draft.reviewerBotIds(),
                        draft.peerScore(), draft.authorScore(), draft.anomalyZScore(),
                        draft.gameTick(), draft.publishedAt(), draft.generation(),
                        draft.paperId(), draft.param1(), draft.param2(),
                        draft.isFollowUp(), draft.predictedScore(), draft.priorMethodology(),
                        draft.recentPapers(), draft.researchHypothesis(),
                        draft.preRegistration(), draft.resultClassification());
            }
        } catch (Exception e) {
            log.warn("LLM statistical framing failed paperId={}: {} — using raw findings",
                    draft.paperId(), e.getMessage());
        }
        return draft;
    }

    static String buildStatisticalFramingPrompt(String rawFindings, String domain, String topicName) {
        String trimmed = rawFindings.length() > 500 ? rawFindings.substring(0, 500) + "…" : rawFindings;
        return """
                You are a scientific editor improving precision reporting in a computational paper.

                Domain: %s | Topic: %s

                Raw computational output:
                %s

                Rewrite this for the findings section of a scientific paper. Rules:
                1. Replace exact decimals beyond 3 significant figures with rounded values.
                2. Add appropriate uncertainty qualifiers: "approximately", "roughly", "within ~X%%", "±" estimates.
                3. For very small (<0.001) or very large (>10000) values use scientific notation.
                4. Do NOT change any facts, trends, relationships, or physical quantities — only the precision framing.
                5. Keep the same length and structure. Plain prose only, no bullet points, no headers.

                Reply with ONLY the rewritten findings text — no preamble, no JSON, no explanation.
                """.formatted(domain, topicName, trimmed);
    }

    static String parseStatisticalFraming(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.strip();
        // Reject JSON (model confused about output format), empty, or suspiciously short
        if (cleaned.startsWith("{") || cleaned.startsWith("[") || cleaned.length() < 20) return null;
        // Strip common preambles like "Here is the rewritten..." if the model ignores the instruction
        for (String preamble : new String[]{"Here is", "Here's", "Rewritten:", "Revised:", "Output:"}) {
            if (cleaned.startsWith(preamble)) {
                int nl = cleaned.indexOf('\n');
                if (nl > 0 && nl < 80) {
                    cleaned = cleaned.substring(nl + 1).strip();
                } else if (preamble.endsWith(":")) {
                    // Single-line colon-terminated preamble — strip just the prefix
                    cleaned = cleaned.substring(preamble.length()).stripLeading();
                }
                break;
            }
        }
        return cleaned.isBlank() ? null : cleaned;
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

        // Pre-registration section — present only when formulation captured a pre-reg
        String preRegSection = "";
        if (draft.preRegistration() != null) {
            PreRegistration pr = draft.preRegistration();
            String mapLine = (pr.mappingNotes() != null && !pr.mappingNotes().isBlank()
                    && !pr.mappingNotes().equals("null") && !pr.mappingNotes().isEmpty())
                    ? "Cross-domain mapping used: " + pr.mappingNotes() + "\n"
                    : "";
            String classLine = (draft.resultClassification() != null)
                    ? "Deterministic result classification: " + draft.resultClassification() + "\n"
                    : "";
            String nullWarn = (draft.resultClassification() == ResultClassification.NULL_RESULT)
                    ? "⚠ NULL RESULT: The computation does not support the pre-registered prediction. "
                      + "Your conclusion MUST explicitly state this. Do NOT reframe as partial support, "
                      + "\"selective\" confirmation, or attribute failure to material/domain properties. "
                      + "A null result is a valid finding — report it honestly.\n"
                    : "";
            preRegSection = """

                    Pre-registered hypothesis (locked before computation ran): %s
                    Pre-registered prediction: %s
                    Falsification condition: %s
                    %s%s%s""".formatted(
                            pr.hypothesis(), pr.prediction(),
                            pr.falsificationCondition() != null ? pr.falsificationCondition() : "(not specified)",
                            mapLine, classLine, nullWarn);
        }

        // Conclusion instruction varies by classification to prevent null-result reframing
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"1-2 sentences: explicitly state the pre-registered prediction "
                  + "was NOT confirmed; what was expected, what was found, what this rules out\""
                : "\"conclusion\":\"1-2 sentences: assess whether the pre-registered prediction was "
                  + "met (if applicable); what this result implies; one concrete open question it raises\"";

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
                %s%s
                Reply with ONLY this JSON — no markdown, no preamble, no explanation outside the JSON:
                {"hypothesis":"one specific testable sentence grounding the claim in the actual \
                computation parameters (starting position, scale, or range)","findings":"2-3 sentences \
                interpreting the pattern or trend — its significance and what it suggests; \
                cite a prior paper by ID if relevant",%s,\
                "followUp":{"param1":N,"param2":X.X,"predictedScore":0.30},\
                "confidenceScore":0.75,"cited_paper_ids":["P-xxxx-1"]}

                followUp rules: ONLY include followUp if the prior papers reveal a specific \
                unexplored parameter region worth targeting next. param1 is an integer 10–99 \
                (shifts the search starting position). param2 is a decimal 1.0–9.0 (scales depth). \
                predictedScore is your numeric prediction (0.0–1.0) for what quality the follow-up \
                result will achieve. If no clear follow-up region emerges, OMIT the followUp field entirely.
                confidenceScore rules: 0.0–1.0; your assessment of how well-supported and robust \
                these findings are (0.5 = neutral, 1.0 = highly confident, 0.0 = very uncertain). \
                For a null result, confidenceScore should reflect confidence that the result is \
                genuinely null, not confidence in a positive claim.
                cited_paper_ids rules: array of Paper IDs from the prior papers list above that \
                you directly referenced in findings. Use exact IDs (format P-xxxx-N). Empty array if none.
                All text values under 120 words each. Plain prose inside JSON strings only — \
                no bullet points, no sub-headers, no markdown formatting.
                """.formatted(
                draft.domain(), draft.topicName(), draft.methodology(),
                draft.title(), draft.generation(),
                paramsLine, followUpNote, anomalyNote,
                compactRawFindings(draft.rawFindings()),
                scaffoldHyp,
                scaffoldCon,
                priorSection.trim(),
                preRegSection,
                conclusionSpec);
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

    private static final java.util.regex.Pattern PAPER_ID_PATTERN =
            java.util.regex.Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    private NarrationResult parseNarrationResult(String response) {
        return parseNarrationResult(objectMapper, response);
    }

    static NarrationResult parseNarrationResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank())
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start)
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            FollowUpParams followUp = null;
            com.fasterxml.jackson.databind.JsonNode fuNode = root.get("followUp");
            if (fuNode != null && !fuNode.isNull() && !fuNode.isMissingNode()) {
                int    p1 = fuNode.path("param1").asInt(-1);
                double p2 = fuNode.path("param2").asDouble(-1.0);
                if (p1 >= 10 && p1 <= 99 && p2 >= 1.0 && p2 <= 9.0) {
                    double ps = fuNode.path("predictedScore").asDouble(-1.0);
                    Double predictedScore = (ps >= 0.0 && ps <= 1.0) ? ps : null;
                    followUp = new FollowUpParams(p1, p2, predictedScore, null);
                }
            }
            double confidenceScore = Math.max(0.0, Math.min(1.0,
                    root.path("confidenceScore").asDouble(0.5)));
            List<String> citedPaperIds = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode citedNode = root.get("cited_paper_ids");
            if (citedNode != null && citedNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode idNode : citedNode) {
                    String id = idNode.asText(null);
                    if (id != null && PAPER_ID_PATTERN.matcher(id).matches()) citedPaperIds.add(id);
                }
            }
            return new NarrationResult(
                    root.path("hypothesis").asText(null),
                    root.path("findings").asText(null),
                    root.path("conclusion").asText(null),
                    followUp, null, confidenceScore, List.copyOf(citedPaperIds));
        } catch (Exception e) {
            log.warn("LLM narration: JSON parse failed: {}", e.getMessage());
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        }
    }

    // Genome mentoring moved to GenomeMentoringService in Phase 0 Task 0.2.

    // --- Parsing helpers ---

    private static final java.util.Set<String> VALID_PREDICATES = java.util.Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");

    private static final java.util.Set<String> VALID_METRICS = java.util.Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

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

}
