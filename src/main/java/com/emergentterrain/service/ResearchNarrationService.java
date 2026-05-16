package com.emergentterrain.service;

import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.LiteratureReference;
import com.emergentterrain.research.LiteratureSearchService;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.PromptTemplateIds;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.emergentterrain.research.ResultClassification;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Paper narration pipeline: refinement → statistical framing → first-pass narration →
 * audit/critique → professor-directed experiments → student revision → publish.
 *
 * Carved out of LlmService in Phase 0 Task 0.2c so the narration concerns can evolve
 * independently of digest, consultation, and streaming. Owns its own narration and
 * refinement semaphores plus a virtual-thread executor; nothing here can starve the
 * digest or streaming paths.
 */
@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class ResearchNarrationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchNarrationService.class);

    private final LlmRouter router;
    private final MathToolSuite toolSuite;
    private final ObjectMapper objectMapper;
    private final PromptTemplateRegistry promptRegistry;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore narrationSlot;
    private final Semaphore refinementSlot = new Semaphore(2);
    /** Phase 6 D1.3 — optional. Null when the literature service bean isn't on the context. */
    private final LiteratureSearchService literatureSearch;
    private final boolean literatureSearchEnabled;
    /** Phase 6 D2 — when on, the narrator emits two extra JSON keys via the v2 template. */
    private final boolean discussionSectionEnabled;

    /** Phase 7 E3 — when on, the router falls back to Ollama critique+revise (no tools) when both
     *  the primary tools path and the primary JSON path are unavailable. */
    private final boolean ollamaFallbackEnabled;

    /** Phase 7 F2 — when on, the narrator emits one H3 prose block per computation
     *  in the findings section. Off → behaviour bit-identical to F1-baseline. */
    private final boolean structuredFindingsEnabled;

    /** Phase 7 F2 — number of independent computation prose blocks the narrator emits.
     *  Force-clamped to 1 when structuredFindingsEnabled is false. */
    private final int maxComputations;

    /**
     * Phase 12 D4 — when on, a panel that returns {@code got ∈ {2, 3}} of a
     * configured 4 stamps a {@code panel:n=N:degraded:demoted_to_needs_revision}
     * marker. {@link com.emergentterrain.research.UniversityService#publishPaper}
     * reads the marker and routes status PUBLISHED → NEEDS_REVISION. The n=1
     * suppression branch above this is unchanged (single-reviewer agreement is
     * non-informative, suppress outright).
     *
     * <p>Default off; the operator flips it on after one REVIEW_REPORT refresh
     * exercises the D-series suppressions so the demotion signal lands in a
     * known baseline.
     */
    private final boolean panelDegradationDemoteEnabled;

    /**
     * Phase 11 C4 — citations-as-schema validator. Runs after the student-revision
     * stage produces a NarrationResult with {@code numericClaims} and a tool-call
     * trace with stable {@code call_N} IDs. Operates in three modes via
     * {@code app.research.citations-as-schema.mode}:
     * <ul>
     *   <li>{@code warn} (default) — stamp call_path, never suppress</li>
     *   <li>{@code downgrade} — route papers with mismatched/out-of-tolerance claims
     *       to NULL_RESULT (C5a, future)</li>
     *   <li>{@code suppress} — drop those papers entirely (C5b, future)</li>
     * </ul>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.CitationValidator citationValidator;

    /** Phase 11 C4 — see {@link #citationValidator}. */
    @org.springframework.beans.factory.annotation.Value("${app.research.citations-as-schema.mode:warn}")
    private String citationsAsSchemaMode;

    /**
     * SimulationGate consult before each Groq-spending stage (first-pass, professor
     * tools, student revision, refinement-loop iteration). Optional: null in tests
     * that don't wire it. When closed, the affected stage short-circuits to a
     * cheaper fallback (first-pass narration or null-suppress) rather than throwing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.llm.SimulationGate simulationGate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.llm.SystemResourcesGate systemResourcesGate;

    /**
     * Phase 7 F7.2 — multi-reviewer panel. Runs after the professor stage's
     * synthesis and stamps a {@link com.emergentterrain.research.PanelOutcome}
     * onto the returned {@link NarrationResult}. Optional dependency so tests
     * that don't wire it get a no-op pipeline.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReviewPanelService reviewPanelService;

    /** Set during wireUniversityCallback — calls university.publishPaper on completion. */
    private volatile java.util.function.BiConsumer<PaperDraft, NarrationResult> narrationPublisher;

    public ResearchNarrationService(
            LlmRouter router,
            ObjectMapper objectMapper,
            PromptTemplateRegistry promptRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LiteratureSearchService literatureSearch,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.gpu.GpuEngineAdapter gpuEngineAdapter,
            @org.springframework.beans.factory.annotation.Value("${app.research.literature-search.enabled:false}")
            boolean literatureSearchEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.discussion-section.enabled:false}")
            boolean discussionSectionEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.ollama-fallback.enabled:true}")
            boolean ollamaFallbackEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.structured-findings.enabled:false}")
            boolean structuredFindingsEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.max-computations:2}")
            int maxComputationsCfg,
            @org.springframework.beans.factory.annotation.Value("${app.research.panel-degradation-demote.enabled:false}")
            boolean panelDegradationDemoteEnabled) {
        this.router                    = router;
        // Phase 8 Tier 4 — GpuEngineAdapter is @Autowired(required=false) so
        // tests and configurations without the GPU engine wired still construct
        // cleanly; run_gpu_eval surfaces a "not configured" error in that case.
        this.toolSuite                 = new MathToolSuite(router, objectMapper, gpuEngineAdapter);
        this.objectMapper              = objectMapper;
        this.promptRegistry            = promptRegistry;
        this.literatureSearch          = literatureSearch;
        this.literatureSearchEnabled   = literatureSearchEnabled;
        this.discussionSectionEnabled  = discussionSectionEnabled;
        this.ollamaFallbackEnabled     = ollamaFallbackEnabled;
        this.structuredFindingsEnabled = structuredFindingsEnabled;
        // Phase 7 F2 — flag-off path force-clamps maxComputations to 1 so the
        // entire pipeline (parser, prompt, truncation guard, validators, markdown)
        // stays bit-identical to the F1-baseline single-block behaviour.
        this.maxComputations           = structuredFindingsEnabled
                ? Math.max(1, maxComputationsCfg)
                : 1;
        this.panelDegradationDemoteEnabled = panelDegradationDemoteEnabled;
        this.narrationSlot             = new Semaphore(router.primaryPoolSize());
        log.info("ResearchNarrationService: narrationSlots={} literatureSearchEnabled={} "
                + "discussionSectionEnabled={} ollamaFallbackEnabled={} "
                + "structuredFindingsEnabled={} maxComputations={} (cfg={}) "
                + "panelDegradationDemoteEnabled={}",
                narrationSlot.availablePermits(), literatureSearchEnabled, discussionSectionEnabled,
                ollamaFallbackEnabled, structuredFindingsEnabled, maxComputations, maxComputationsCfg,
                panelDegradationDemoteEnabled);
        if (literatureSearchEnabled && literatureSearch == null) {
            log.warn("ResearchNarrationService: app.research.literature-search.enabled=true "
                    + "but no LiteratureSearchService bean on the context; degrading to off");
        }
    }

    /**
     * Returns true when the simulation gate is wired and currently closed, meaning
     * the next Groq-spending stage for this paper should short-circuit. Stamps the
     * callPath with a stage-specific marker so the persisted Call Path explains why
     * the paper skipped that stage.
     */
    private boolean gateClosed(String stage, PaperDraft draft, List<String> callPath) {
        if (systemResourcesGate != null && !systemResourcesGate.isOpen()) {
            callPath.add("gate:resources:" + stage);
            log.warn("LLM stage skipped: resources gate CRITICAL paperId={} stage={} reason={}",
                    draft.paperId(), stage, systemResourcesGate.reason());
            return true;
        }
        if (simulationGate == null || simulationGate.isOpen()) return false;
        String reason = simulationGate.reason();
        callPath.add("gate:closed:" + stage);
        log.warn("LLM stage skipped: gate closed paperId={} stage={} reason={}",
                draft.paperId(), stage, reason);
        return true;
    }

    /** Wire the narration callback into UniversityService once the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setNarratingCallback(this::enqueueNarration);
            this.narrationPublisher = university::publishPaper;
            log.info("ResearchNarrationService: narration callback wired to UniversityService");
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

        // Pre-emptive: SimulationGate budget tripped. Suppress before spending any
        // primary tokens on first-pass narration. Same publisher contract as
        // primaryFullyExhausted (null result → queue counter decrements).
        if (gateClosed("first-pass", draft, callPath)) {
            log.info("LLM call path paperId={}: {}", draft.paperId(), String.join(" → ", callPath));
            if (narrationPublisher != null) narrationPublisher.accept(draft, null);
            return;
        }

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
        List<String> auditGapDimensions = new java.util.ArrayList<>();
        NarrationResult finalResult = maybeCritiqueAndRevise(draft, firstPass, callPath, auditGapDimensions);

        String pathStr = String.join(" → ", callPath);
        log.info("LLM call path paperId={}: {}", draft.paperId(), pathStr);

        // null means professor blank or all-TPD — pass null so queue counter is decremented.
        // Phase 4c Piece 2 gate 3: every emitted result is stamped with the primary
        // narration template id so the persisted ResearchPaper carries provenance.
        // Refinement / critique / revision steps are tracked separately via callPath.
        // Phase 7 F2 — stamp the ACTUAL variant version that ran (v1 / v2-with-discussion /
        // v3-structured-findings*) so per-variant rollback statistics see the variant
        // that produced the paper rather than the constant "v1".
        NarrationResult result = finalResult == null ? null : new NarrationResult(
                finalResult.hypothesis(), finalResult.findingsMap(), finalResult.conclusion(),
                finalResult.followUp(), router.primaryModel(),
                finalResult.confidenceScore(), finalResult.citedPaperIds(), pathStr,
                finalResult.typedCitations(), finalResult.formalFindings(),
                com.emergentterrain.research.PromptTemplateIds.NARRATION_PRIMARY_ID,
                activeNarrationPrimaryVersion(),
                finalResult.discussion(), finalResult.relatedWork(),
                List.copyOf(auditGapDimensions),
                finalResult.toolCallTrace(),
                finalResult.panelOutcome(),
                finalResult.numericClaims());
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
                                                    List<String> callPath, List<String> auditGapDimensions) {
        if (firstPass.hypothesis() == null || firstPass.findings() == null || firstPass.conclusion() == null) {
            // G1 — fill null h/f/c fields from the draft's template fallbacks
            // (designed as the "LLM-failure" fallback per PaperDraft javadoc)
            // and proceed to critique rather than skipping. Falls through to
            // the original skip if even the templates can't close the gaps.
            NarrationResult merged = fillFromTemplates(draft, firstPass);
            if (merged == null) {
                callPath.add("critique:skip:incomplete_firstpass");
                return firstPass;
            }
            int filled = (firstPass.hypothesis() == null ? 1 : 0)
                       + (firstPass.findings()   == null ? 1 : 0)
                       + (firstPass.conclusion() == null ? 1 : 0);
            callPath.add("critique:partial:" + filled + "_filled");
            log.info("LLM partial-revision merge: filled {} null field(s) from PaperDraft templates paperId={}",
                    filled, draft.paperId());
            firstPass = merged;
        }
        // Phase 7 E3 — three-way routing. Tools first (any tool-capable primary
        // unblocked), then JSON path (primary chain has any unblocked pool — the
        // revision streams from a primary model), then Ollama (no tools; both
        // critique and revise on the fast pool). When all three fail, fall back
        // to firstPass with a skip log so telemetry sees a hard miss.
        if (router.supportsProfessorTools()) {
            return maybeCritiqueAndReviseWithTools(draft, firstPass, callPath, auditGapDimensions);
        }
        if (router.hasJsonPathPrimary()) {
            return maybeCritiqueAndReviseJsonPath(draft, firstPass, callPath);
        }
        if (ollamaFallbackEnabled && router.hasFastPool()) {
            return maybeCritiqueAndReviseOllama(draft, firstPass, callPath);
        }
        callPath.add("critique:skip:no_path_available");
        log.warn("LLM no critique path available paperId={} ollamaFallbackEnabled={}",
                draft.paperId(), ollamaFallbackEnabled);
        return firstPass;
    }

    /**
     * Phase 7 G1 — fill null hypothesis/findings/conclusion fields from the
     * PaperDraft's template fallbacks. Returns a new NarrationResult with the
     * gaps closed, or {@code null} when even the templates are null on the
     * matching draft fields. Package-private for unit testing.
     */
    static NarrationResult fillFromTemplates(PaperDraft draft, NarrationResult firstPass) {
        String hypothesis = firstPass.hypothesis();
        String findings   = firstPass.findings();
        String conclusion = firstPass.conclusion();
        if (hypothesis == null) hypothesis = draft.templateHypothesis();
        if (findings   == null) findings   = draft.rawFindings();
        if (conclusion == null) conclusion = draft.templateConclusion();
        if (hypothesis == null || findings == null || conclusion == null) {
            return null;
        }
        return new NarrationResult(
                hypothesis, findings, conclusion,
                firstPass.followUp(), firstPass.aiModel(),
                firstPass.confidenceScore(), firstPass.citedPaperIds(),
                firstPass.callPath(), firstPass.typedCitations(),
                firstPass.formalFindings(),
                firstPass.templateId(), firstPass.templateVersion(),
                firstPass.discussion(), firstPass.relatedWork());
    }

    /** Tool-calling professor path: audit → professor (tool-calling) → student revision. */
    private NarrationResult maybeCritiqueAndReviseWithTools(PaperDraft draft, NarrationResult firstPass,
                                                             List<String> callPath,
                                                             List<String> auditGapDimensions) {
        // Phase 7 F5.3 — accumulates tool-call trace from the professor stage so it
        // can be stamped onto whichever NarrationResult this function ultimately
        // returns. Stays empty if the professor stage never ran or failed early.
        java.util.List<com.emergentterrain.llm.ToolInvocation> profTrace = java.util.List.of();
        try {
            // Phase 1 — Quality audit (fast Ollama): identify gaps before professor is invoked
            String auditSummary = runQualityAudit(draft, firstPass, callPath, auditGapDimensions);

            // Pre-emptive gate check before the heaviest call in the pipeline:
            // professor tools can spend 5+ rounds × toolMaxTokens. Fall back to
            // first-pass narration if the budget tripped during audit.
            if (gateClosed("professor", draft, callPath)) {
                return firstPass;
            }

            // Phase 2 — Professor (Groq tool-calling), directed by audit gaps
            long start = System.currentTimeMillis();
            boolean includeDynamic = "MATHEMATICS".equalsIgnoreCase(draft.domain())
                    || "INTERDISCIPLINARY".equalsIgnoreCase(draft.domain());
            String toolsJson    = toolSuite.toolsJson(includeDynamic);
            // Phase 6 D1.3 — fetch curated literature for this (domain, topic).
            // Empty / null context means the prompts render their v1 variants
            // verbatim; non-empty context switches to v2-with-literature.
            String literatureContext = renderLiteratureContext(draft);
            String systemPrompt = buildProfessorSystemPrompt(promptRegistry, auditSummary, literatureContext);
            String userPrompt   = buildProfessorUserPrompt(promptRegistry, draft, firstPass, literatureContext);

            // Phase 5 C3 — collect per-paper sandbox-reject counts and surface them on
            // the persisted LLM Call Path. Reviewers can then tell whether a Findings
            // claim was actually computed or hand-waved after the sandbox denied the
            // narrator's code attempts.
            MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
            com.emergentterrain.llm.ToolCallResponse profResponse = router.callPrimaryWithTools(
                    systemPrompt, userPrompt, toolsJson,
                    toolSuite.buildExecutor(draft, metrics));
            String professorSynthesis = profResponse.synthesis();
            // Phase 7 F5.3 — capture trace immediately so subsequent return paths
            // (gate closed, parse failed, exception) can still stamp the trace
            // onto the returned NarrationResult.
            profTrace = profResponse.trace();
            // Phase 12 D7 — surface budget / TPD / tool-call-failure model
            // rejections that drove fallback. The router records each handle it
            // tried and rejected before reaching the one whose synthesis is in
            // hand. Stamp them BEFORE the prof:ok marker so reviewers reading
            // the persisted callPath see budget → success in left-to-right order
            // (matching the actual sequence in time).
            for (String skipped : profResponse.skippedModels()) {
                callPath.add("prof:budget_fallback:from=" + abbrev(skipped));
            }
            if (metrics.sandboxRejectCount() > 0) {
                callPath.add("sandbox_reject=" + metrics.sandboxRejectCount());
            }

            if (professorSynthesis == null || professorSynthesis.isBlank()) {
                callPath.add("prof:" + abbrev(router.primaryModel()) + ":blank→suppressed");
                log.warn("LLM professor tools: blank synthesis paperId={} ms={} — draft suppressed",
                        draft.paperId(), System.currentTimeMillis() - start);
                return null;
            }
            // Phase 12 D3 — widen the suppression gate. Phase 10's `>= 3 rejects
            // AND 0 successes` threshold only caught safety-rejected calls; it
            // missed the May 2026 refresh-#3 papers (P-a328-395, 402, 405, 411,
            // 422) where every tool call threw a *runtime* error ("index out of
            // bounds", "Cannot use array as scalar") rather than failing the
            // safety review. ToolMetrics increments `sandboxRejectCount` only on
            // safety rejections, so runtime-error papers accumulated zero rejects
            // AND zero successes — slipping past `totalRejects >= 3`.
            //
            // The new threshold is just `totalSuccesses == 0`. A paper that
            // produced any valid tool output still publishes; the only papers
            // suppressed are those with literally no successful computation —
            // which is exactly the fabrication signal refresh #3 named.
            if (metrics.totalSuccesses() == 0) {
                callPath.add("prof:" + abbrev(router.primaryModel())
                        + ":zero_successes→suppressed");
                log.warn("LLM professor tools: 0 successes (rejects={}) paperId={} — draft suppressed "
                                + "(Phase 12 D3; fabrication pattern from REVIEW_REPORT.md refresh #3 "
                                + "P-395/402/405/411/422)",
                        metrics.totalRejects(), draft.paperId());
                return null;
            }
            callPath.add("prof:" + abbrev(router.primaryModel()) + ":ok");
            log.info("LLM professor tools: synthesis_len={} paperId={} ms={}",
                    professorSynthesis.length(), draft.paperId(), System.currentTimeMillis() - start);

            // Gate may have tripped during the professor tool loop. The professor's
            // synthesis is already complete and the tokens already spent; suppress
            // only the revision stream, keep firstPass as the published narrative.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass.withToolCallTrace(profTrace);
            }

            // Phase 3 — Student revision incorporating professor's findings
            long revStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            router.streamPrimary(buildRevisionPromptFromSynthesis(promptRegistry, draft, firstPass,
                            professorSynthesis, literatureContext),
                    revised::append);
            // Phase 13 E2 — pre-parse repair of common malformed-JSON cases
            // (code-fence wrappers, prose preamble, trailing commas, unclosed
            // strings, unbalanced braces). REVIEW_REPORT refresh #1 §1 rec #3
            // reported `rev:parse_failed→fp` on 9 of 23 published papers
            // (39%); the lenient pre-parser repairs the majority of those
            // before Jackson sees the input. When the repair fires AND parse
            // succeeds, a `rev:parse_recovered:from=lenient` call-path stamp
            // marks the recovered subset for operator correlation.
            com.emergentterrain.research.LenientJsonParser.RepairResult repair =
                    com.emergentterrain.research.LenientJsonParser.repair(revised.toString());
            NarrationResult revParsed = parseNarrationResult(repair.text());
            if (repair.repaired() && revParsed.hypothesis() != null) {
                callPath.add("rev:parse_recovered:from=lenient");
            }
            // Merge revision with firstPass field-by-field rather than requiring all-or-nothing.
            // The student-revision LLM under the v2 Discussion+Related-Work template sometimes
            // emits only the changed sections (discussion, related_work) and omits hypothesis /
            // findings / conclusion when they're unchanged. Under the prior all-or-nothing check
            // this dropped the entire revision and the paper fell back to firstPass with a
            // `rev:parse_failed→fp` marker — 2/2 of the run's prof:ok papers hit it. The fix:
            // for each field, take the revision's value if present, otherwise the firstPass's.
            // A revision with NO recoverable content (everything null) is still treated as
            // parse_failed and falls back to firstPass.
            boolean revHasContent = revParsed.hypothesis()  != null
                                 || revParsed.findings()    != null
                                 || revParsed.conclusion()  != null
                                 || revParsed.discussion()  != null
                                 || revParsed.relatedWork() != null;
            if (revHasContent) {
                String hypothesis  = revParsed.hypothesis()  != null ? revParsed.hypothesis()  : firstPass.hypothesis();
                String findings    = revParsed.findings()    != null ? revParsed.findings()    : firstPass.findings();
                String conclusion  = revParsed.conclusion()  != null ? revParsed.conclusion()  : firstPass.conclusion();
                String discussion  = revParsed.discussion()  != null ? revParsed.discussion()  : firstPass.discussion();
                String relatedWork = revParsed.relatedWork() != null ? revParsed.relatedWork() : firstPass.relatedWork();
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                boolean revFullySupplied = revParsed.hypothesis() != null
                        && revParsed.findings() != null && revParsed.conclusion() != null;
                callPath.add(revFullySupplied
                        ? "rev:" + abbrev(router.primaryModel()) + ":ok"
                        : "rev:" + abbrev(router.primaryModel()) + ":partial→merged");
                log.info("LLM student revision done paperId={} ms={} mode={}", draft.paperId(),
                        System.currentTimeMillis() - revStart, revFullySupplied ? "full" : "partial-merge");
                NarrationResult revisedResult = new NarrationResult(
                        hypothesis, findings, conclusion,
                        followUp, router.primaryModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds(),
                        null, List.of(), List.of(), null, null, discussion, relatedWork)
                        .withToolCallTrace(profTrace)
                        .withNumericClaims(revParsed.numericClaims());
                // Phase 11 C4-C5 — citation validator gate. Warn mode (default)
                // returns the input unchanged but stamps call_path. Downgrade
                // mode rewrites findings+conclusion to a NULL_RESULT preamble
                // when claims don't trace. Suppress mode returns null which
                // drops the paper via the same return-null pattern as the
                // Phase 10 all_tools_failed→suppressed gate.
                NarrationResult gated = applyCitationsGate(
                        revisedResult, profTrace, callPath, draft.paperId());
                if (gated == null) {
                    log.warn("LLM citation gate: paperId={} suppressed by citations-as-schema",
                            draft.paperId());
                    return null;
                }
                // Phase 7 F7.2 — run the multi-reviewer panel against the revised
                // narration + the trace, stamp the aggregated outcome on the result
                // so UniversityService.publishPaper can persist it. The panel
                // short-circuits to empty when disabled / no trace / no providers
                // resolved — the publish path is bit-identical to pre-F7.2 in
                // those cases.
                return runPanelAndStamp(draft, gated, callPath, auditGapDimensions);
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
        // Phase 7 F5.3 — stamp whatever trace accumulated before the failure so
        // the partial professor work survives in the audit record.
        NarrationResult fallback = firstPass.withToolCallTrace(profTrace);
        // Phase 7 F7.2 — even on the fallback path, run the panel if a trace
        // exists. A paper that hit an exception after the professor produced
        // useful tool results still benefits from honesty/completeness grading.
        return runPanelAndStamp(draft, fallback, callPath, auditGapDimensions);
    }

    /**
     * Phase 7 F7.2 — run the multi-reviewer panel and stamp the outcome on the
     * narration. No-op when the panel service isn't wired, when the narration
     * carries no tool-call trace, or when the panel returns an empty outcome.
     * Failures are swallowed and logged so a panel-side bug never blocks a
     * paper from publishing.
     *
     * <p>Phase 7 F7.3 — when the panel's aggregated outcome shows ≥2 reviewers
     * agreed the LLM omitted a relevant tool result, the
     * {@code cherry_picked_evidence} audit-gap dimension is appended to
     * {@code auditGapDimensions}. UniversityService.publishPaper consumes that
     * dimension under the {@code audit-cherry-picked-rejects} flag.
     */
    /**
     * Phase 11 C5 — number of {@code uncited} numbers above which {@code suppress}
     * mode drops the paper even without any trace-mismatch. Three is the threshold
     * because below that the LLM is plausibly missing markers for derived /
     * meta-precision values that the validator's exclusion patterns didn't catch;
     * three or more is strong signal that the inline-citation rule isn't being
     * honoured at all.
     */
    private static final int CITATIONS_UNCITED_SUPPRESS_THRESHOLD = 3;

    /**
     * Phase 11 C4 — run the citation validator and apply the configured rollout
     * mode. Returns the (possibly downgraded) narration, or null when {@code
     * suppress} mode drops the paper.
     *
     * <p>Modes:
     * <ul>
     *   <li>{@code warn} (default) — stamp call_path, return narration unchanged
     *       regardless of failures.</li>
     *   <li>{@code downgrade} — when {@code unmatched > 0} OR {@code out_of_tolerance > 0},
     *       rewrite findings + conclusion to an honest NULL_RESULT preamble naming
     *       the unverifiable values. When only {@code uncited > 0}, stamp a
     *       {@code citations:manual_review} marker and keep publishing.</li>
     *   <li>{@code suppress} — same trigger condition as downgrade, but the paper
     *       is dropped (return null). Additionally drops papers with {@code uncited
     *       >= CITATIONS_UNCITED_SUPPRESS_THRESHOLD}.</li>
     * </ul>
     *
     * <p>No-op (return narration unchanged) when the validator bean isn't on
     * the context, when the trace is empty, or when every trace entry has a
     * null callId (pre-Phase-11 papers).
     */
    private NarrationResult applyCitationsGate(NarrationResult narration,
                                                java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
                                                List<String> callPath,
                                                String paperId) {
        if (citationValidator == null) return narration;
        if (trace == null || trace.isEmpty()) return narration;
        com.emergentterrain.research.CitationValidator.CitationValidationResult cv;
        try {
            cv = citationValidator.validate(narration, trace);
        } catch (RuntimeException ex) {
            // Defensive — the validator must never break the pipeline. If a
            // regex or numeric parse blows up, stamp it and continue.
            callPath.add("citations:error");
            log.warn("CitationValidator: paperId={} failed with {}; continuing",
                    paperId, ex.getMessage());
            return narration;
        }

        if (!cv.hasFailures()) {
            callPath.add("citations:ok");
            log.debug("CitationValidator: paperId={} clean", paperId);
            return narration;
        }

        String summary = "citations:uncited=" + cv.uncitedCount()
                + ":unmatched=" + cv.unmatchedClaimCount()
                + ":out_of_tolerance=" + cv.outOfToleranceCount();
        boolean traceMismatch = cv.unmatchedClaimCount() > 0 || cv.outOfToleranceCount() > 0;
        boolean uncitedHeavy  = cv.uncitedCount() >= CITATIONS_UNCITED_SUPPRESS_THRESHOLD;
        String mode = citationsAsSchemaMode == null ? "warn" : citationsAsSchemaMode.toLowerCase();

        switch (mode) {
            case "suppress" -> {
                callPath.add(summary);
                if (traceMismatch || uncitedHeavy) {
                    callPath.add("prof:" + abbrev(router.primaryModel())
                            + ":citations_failed→suppressed");
                    log.warn("CitationValidator: paperId={} mode=suppress {} — paper "
                                    + "dropped issues={}", paperId, summary, cv.issues());
                    return null;
                }
                log.warn("CitationValidator: paperId={} mode=suppress {} below threshold — "
                                + "publishing", paperId, summary);
                return narration;
            }
            case "downgrade" -> {
                callPath.add(summary);
                if (traceMismatch) {
                    callPath.add("citations:downgrade→null_result");
                    log.warn("CitationValidator: paperId={} mode=downgrade {} — routing to "
                                    + "NULL_RESULT issues={}", paperId, summary, cv.issues());
                    return downgradeNarrationToNull(narration, cv);
                }
                callPath.add("citations:manual_review");
                log.warn("CitationValidator: paperId={} mode=downgrade {} uncited-only — "
                                + "MANUAL_REVIEW stamped, publishing", paperId, summary);
                return narration;
            }
            default -> {  // "warn" or anything else
                callPath.add(summary);
                log.warn("CitationValidator: paperId={} mode={} {} issues={}",
                        paperId, mode, summary, cv.issues());
                return narration;
            }
        }
    }

    /**
     * Phase 11 C5a — rewrite a paper's findings + conclusion to an honest
     * NULL_RESULT preamble that names the citation-validator failures.
     * Hypothesis is preserved (it's the question the paper set out to answer),
     * but findings and conclusion are replaced so the published prose
     * explicitly reports that direct computational evidence is missing.
     *
     * <p>Up to three example issues are included in the preamble; more would
     * bloat the prose without adding signal.
     */
    static NarrationResult downgradeNarrationToNull(
            NarrationResult narration,
            com.emergentterrain.research.CitationValidator.CitationValidationResult cv) {
        StringBuilder preamble = new StringBuilder();
        preamble.append("NULL_RESULT — citation validator flagged ")
                .append(cv.totalFailureCount())
                .append(cv.totalFailureCount() == 1 ? " numeric value" : " numeric values")
                .append(" in this draft that could not be traced to direct tool-call output. ")
                .append("The hypothesis remains untested in this submission.");
        if (!cv.issues().isEmpty()) {
            preamble.append(" Specifically: ");
            preamble.append(cv.issues().stream()
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining("; ")));
            preamble.append(".");
        }
        String conclusion = "The pre-registered prediction was not verified against direct "
                + "computational evidence; this paper is recorded as a null result.";
        return new NarrationResult(
                narration.hypothesis(),
                Map.of(1, preamble.toString()),
                conclusion,
                narration.followUp(),
                narration.aiModel(),
                narration.confidenceScore(),
                narration.citedPaperIds(),
                narration.callPath(),
                narration.typedCitations(),
                narration.formalFindings(),
                narration.templateId(),
                narration.templateVersion(),
                narration.discussion(),
                narration.relatedWork(),
                narration.auditGapDimensions(),
                narration.toolCallTrace(),
                narration.panelOutcome(),
                narration.numericClaims());
    }

    private NarrationResult runPanelAndStamp(PaperDraft draft, NarrationResult narration,
                                              List<String> callPath,
                                              List<String> auditGapDimensions) {
        if (reviewPanelService == null || narration == null) return narration;
        if (!reviewPanelService.isActive()) return narration;
        if (narration.toolCallTrace() == null || narration.toolCallTrace().isEmpty()) {
            return narration;
        }
        try {
            int configuredSize = reviewPanelService.panelSize();
            com.emergentterrain.research.PanelOutcome outcome =
                    reviewPanelService.runPanel(draft, narration);
            int got = outcome.reviewerCount();
            if (got == 0) {
                // Phase 7 F7.4 — no reviewers responded (all quota-blocked, all dropped
                // on parse fail, or all RPC-errored). Stamp `panel:skip:unavailable`
                // and continue — the paper still publishes, just without the panel
                // signal. No new failure mode.
                callPath.add("panel:skip:unavailable");
                return narration;
            }
            callPath.add(buildPanelCallPathStamp(got, configuredSize, outcome.agreementScore()));
            // Phase 7 F7.3 — cherry-picked-evidence audit gap when consensus exists.
            if (auditGapDimensions != null
                    && !outcome.consensusOmittedRelevant().isEmpty()
                    && !auditGapDimensions.contains("cherry_picked_evidence")) {
                auditGapDimensions.add("cherry_picked_evidence");
                log.info("F7.3 cherry_picked_evidence: paperId={} omitted_calls={}",
                        draft != null ? draft.paperId() : "?",
                        outcome.consensusOmittedRelevant());
            }
            // Phase 10 — suppress papers that limp through with a single
            // reviewer when the panel was configured for ≥2. P-a328-360 went
            // out with `panel:n=1:degraded:from=4` after `rev:parse_failed→fp`
            // and contained five blocks of fabricated Reuss-ratio output that
            // the single reviewer happily co-signed. One reviewer cannot
            // produce useful inter-rater agreement — `agreementScore` defaults
            // to 1.00 trivially — so the panel signal is non-informative.
            // Treat this as MANUAL_REVIEW: suppress the paper, log loudly,
            // let the upstream draft queue see the rejection.
            if (got == 1 && configuredSize >= 2) {
                callPath.add("panel:n=1:degraded:MANUAL_REVIEW→suppressed");
                log.warn("ReviewPanelService: paperId={} panel n=1 of configured {} — "
                                + "single-reviewer agreement is non-informative; draft "
                                + "suppressed for MANUAL_REVIEW per REVIEW_REPORT.md "
                                + "refresh #3 (P-360 fabrication pattern).",
                        draft != null ? draft.paperId() : "?", configuredSize);
                return null;
            }
            // Phase 12 D4 — n ∈ {2, 3} of a configured 4 carries lower confidence
            // than a full panel but is not non-informative the way n=1 is.
            // REVIEW_REPORT refresh #3 §1 rec #6 named four Reject papers
            // (P-417, P-419, P-428, P-431) that carried PUBLISHED despite
            // degraded panels; D4 demotes them to NEEDS_REVISION so the lower
            // confidence is surfaced. The paper still publishes — peer score,
            // call path, and panel outcome are preserved — but the status
            // reflects what actually happened in review.
            if (panelDegradationDemoteEnabled
                    && got >= 2 && got < configuredSize) {
                callPath.add("panel:n=" + got + ":degraded:demoted_to_needs_revision");
                log.info("ReviewPanelService: paperId={} panel n={} of configured {} — "
                                + "demoting PUBLISHED → NEEDS_REVISION (Phase 12 D4).",
                        draft != null ? draft.paperId() : "?", got, configuredSize);
            }
            return narration.withPanelOutcome(outcome);
        } catch (Exception e) {
            log.warn("LLM panel review path failed paperId={}: {}",
                    draft != null ? draft.paperId() : "?", e.getMessage());
            callPath.add("panel:error");
            return narration;
        }
    }

    /**
     * Phase 7 F7.4 — build the panel call-path stamp from observed vs configured
     * reviewer counts. Visibility is package-private so tests can verify the
     * exact string format without standing up a full {@link ResearchNarrationService}.
     */
    static String buildPanelCallPathStamp(int got, int configuredSize, double agreementScore) {
        String agreement = String.format("%.2f", agreementScore);
        if (got < configuredSize) {
            return "panel:n=" + got + ":degraded:from=" + configuredSize
                    + ":agreement=" + agreement;
        }
        return "panel:n=" + got + ":agreement=" + agreement;
    }

    /** Legacy JSON-based path — fast Ollama critique + prescribed experiment + Groq revision. */
    private NarrationResult maybeCritiqueAndReviseJsonPath(PaperDraft draft, NarrationResult firstPass,
                                                            List<String> callPath) {
        try {
            long critiqueStart = System.currentTimeMillis();
            String critiqueResponse = router.callFast(buildCritiquePrompt(promptRegistry, draft, firstPass));
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

            // Pre-emptive gate check before the primary-stream revision call —
            // the only remaining Groq-spending stage on this path.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass;
            }

            long revisionStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            router.streamPrimary(
                    buildRevisionPrompt(promptRegistry, draft, firstPass, critique.weaknesses(), professorExperiment),
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
     * Phase 7 E3 — Ollama-only critique+revise loop. Mirrors the JSON path but routes
     * the revision stream through the fast pool too, since the primary chain is
     * exhausted or unavailable in this branch. No tool calls (Ollama's tool-calling
     * is unreliable). Stamps {@code rev:ollama:ok} so the publish path treats the
     * paper as fully revised (not PUBLISHED_NO_PROFESSOR).
     */
    private NarrationResult maybeCritiqueAndReviseOllama(PaperDraft draft, NarrationResult firstPass,
                                                          List<String> callPath) {
        try {
            long critiqueStart = System.currentTimeMillis();
            String critiqueResponse = router.callFast(buildCritiquePrompt(promptRegistry, draft, firstPass));
            CritiqueResult critique = parseCritiqueResult(critiqueResponse);

            if (!critique.hasActionableFeedback()) {
                callPath.add("ollama_critique:4b:clean→fp");
                log.info("LLM professor review (ollama): clean draft paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - critiqueStart);
                return firstPass;
            }
            callPath.add("ollama_critique:4b:" + critique.weaknesses().size() + "gaps");
            log.info("LLM professor review (ollama): {} weakness(es) experiment={} paperId={} ms={}",
                    critique.weaknesses().size(), critique.experimentalGap() != null,
                    draft.paperId(), System.currentTimeMillis() - critiqueStart);

            String professorExperiment = null;
            if (critique.experimentalGap() != null) {
                professorExperiment = runProfessorDirectedExperiment(draft, critique.experimentalGap());
            }

            // Gate check is still meaningful — keeps any other Groq-spending stage
            // (e.g. statistical framing inside runProfessorDirectedExperiment) within
            // the simulation's budget posture.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass;
            }

            long revisionStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            router.streamFast(
                    buildRevisionPrompt(promptRegistry, draft, firstPass, critique.weaknesses(), professorExperiment),
                    revised::append);
            NarrationResult revParsed = parseNarrationResult(revised.toString());
            if (revParsed.hypothesis() != null && revParsed.findings() != null && revParsed.conclusion() != null) {
                callPath.add("rev:ollama:ok");
                log.info("LLM student revision (ollama) done paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - revisionStart);
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                return new NarrationResult(
                        revParsed.hypothesis(), revParsed.findings(), revParsed.conclusion(),
                        followUp, router.fastModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds());
            }
            callPath.add("rev:ollama:parse_failed→fp");
        } catch (Exception e) {
            callPath.add("ollama_critique:error→fp");
            log.warn("LLM professor+revision (ollama) failed paperId={}: {} — using first-pass narration",
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
                        buildStatisticalFramingPrompt(promptRegistry, findings, draft.domain(), draft.topicName())));
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

    static String buildCritiquePrompt(PromptTemplateRegistry registry,
                                       PaperDraft draft, NarrationResult narration) {
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

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",              draft.domain());
        params.put("topicName",           draft.topicName());
        params.put("preRegAndClassLines", preRegLine + classLine);
        params.put("hypothesis",          narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the critique LLM can address each block separately.
        params.put("findings",            formatFindingsForPrompt(narration));
        params.put("conclusion",          narration.conclusion());
        params.put("experimentSpec",      experimentSpec);
        return registry.render(PromptTemplateIds.NARRATION_CRITIQUE_ID, params);
    }

    // ── Tool-calling professor prompts ────────────────────────────────────────

    // ── Audit gap record ─────────────────────────────────────────────────────
    private record AuditGap(String dimension, String severity, String description) {}

    // ── Quality audit (Cerebras when available, else fast Ollama) ────────────

    private String runQualityAudit(PaperDraft draft, NarrationResult firstPass,
                                    List<String> callPath, List<String> auditGapDimensions) {
        try {
            long t = System.currentTimeMillis();
            // Phase 7 E1.6 — direct audit at the "fast-audit" role pool (Cerebras WSE-3
            // delivers ~14 ms inference vs Ollama's ~100-500 ms). Falls back to the
            // configured fast pool when Cerebras is disabled, RPM-blocked, or throws.
            String raw = router.callForRole("fast-audit",
                    buildAuditPrompt(promptRegistry, draft, firstPass));
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
            // Phase 7 G8 — accumulate unique dimension names for the publish-path validator.
            for (AuditGap g : gaps) {
                if (!auditGapDimensions.contains(g.dimension())) auditGapDimensions.add(g.dimension());
            }
            return gaps.stream()
                    .map(g -> "- [" + g.severity().toUpperCase() + "] " + g.dimension() + ": " + g.description())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            callPath.add("audit:4b:failed");
            log.warn("LLM quality audit failed paperId={}: {}", draft.paperId(), e.getMessage());
            return "";
        }
    }

    static String buildAuditPrompt(PromptTemplateRegistry registry,
                                    PaperDraft draft, NarrationResult narration) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",         draft.domain());
        params.put("topicName",      draft.topicName());
        params.put("classification", draft.resultClassification() != null
                ? draft.resultClassification() : "UNKNOWN");
        params.put("hypothesis",     narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the audit LLM can flag gaps PER block. Single-block layout
        // is bit-identical to the legacy single-string findings.
        params.put("findings",       formatFindingsForPrompt(narration));
        params.put("conclusion",     narration.conclusion());
        return registry.render(PromptTemplateIds.NARRATION_AUDIT_ID, params);
    }

    /**
     * Phase 7 F2 — render the findings field for a downstream prompt slot. Single-
     * block (flag-off) maps return the prose verbatim — the legacy joined-string
     * shape every audit / professor / revision template already expects. Multi-
     * block maps emit a {@code [Computation N]\n<prose>} labelled layout in
     * ascending key order so the LLM can address each block independently.
     */
    static String formatFindingsForPrompt(NarrationResult narration) {
        if (narration == null) return null;
        java.util.Map<Integer, String> map = narration.findingsMap();
        if (map == null || map.isEmpty()) return narration.findings();
        if (map.size() == 1) return narration.findings();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<Integer, String> e :
                new java.util.TreeMap<>(map).entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (!first) sb.append("\n\n");
            first = false;
            sb.append("[Computation ").append(e.getKey()).append("]\n")
                    .append(e.getValue());
        }
        return sb.toString();
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

    /**
     * Phase 6 D1.3 — fetches the curated references for this draft's (domain, topic)
     * and renders them as a slot-ready block. Returns the empty string when the
     * literature flag is off, when no service is wired, or when the corpus has
     * no entries for the key. Empty input → empty slot → v1 template renders
     * verbatim (memo §3.3 — no "no literature found" annotation).
     */
    String renderLiteratureContext(PaperDraft draft) {
        if (!literatureSearchEnabled || literatureSearch == null) return "";
        java.util.List<LiteratureReference> refs =
                literatureSearch.findFor(draft.domain(), draft.topicName());
        if (refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nPRIOR LITERATURE FOR THIS REGION:\n");
        for (LiteratureReference r : refs) {
            sb.append("- ").append(r.citation()).append(": ").append(r.closedForm());
            if (r.validRegion() != null && !r.validRegion().isBlank()) {
                sb.append(" Region: ").append(r.validRegion()).append('.');
            }
            if (r.magnitudeEstimate() != null && !r.magnitudeEstimate().isBlank()) {
                sb.append(" Magnitude: ").append(r.magnitudeEstimate()).append('.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry) {
        return buildProfessorSystemPrompt(registry, "", null);
    }

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry, String auditSummary) {
        return buildProfessorSystemPrompt(registry, auditSummary, null);
    }

    /**
     * Phase 6 D1.3 — {@code literatureContext} is a rendered block of curated
     * references (or null/empty when the flag is off or the lookup found
     * nothing). Non-null/non-empty value switches the render to the
     * {@code v2-with-literature} template variant; otherwise the original v1
     * template renders verbatim.
     */
    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry,
                                              String auditSummary, String literatureContext) {
        String auditSection = (auditSummary != null && !auditSummary.isBlank())
                ? "\nA quality audit identified these specific gaps. Address them with your tools:\n"
                  + auditSummary + "\n"
                : "";
        if (literatureContext != null && !literatureContext.isEmpty()) {
            return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                    "v2-with-literature",
                    Map.of("auditSection", auditSection,
                           "literatureContext", literatureContext));
        }
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION,
                Map.of("auditSection", auditSection));
    }

    static String buildProfessorUserPrompt(PromptTemplateRegistry registry,
                                            PaperDraft draft, NarrationResult narration) {
        return buildProfessorUserPrompt(registry, draft, narration, null);
    }

    /** Phase 6 D1.3 — see {@link #buildProfessorSystemPrompt(PromptTemplateRegistry, String, String)}. */
    static String buildProfessorUserPrompt(PromptTemplateRegistry registry,
                                            PaperDraft draft, NarrationResult narration,
                                            String literatureContext) {
        String preRegLine = draft.preRegistration() != null
                ? "Pre-registered prediction: " + draft.preRegistration().prediction() + "\n" : "";
        String classLine  = draft.resultClassification() != null
                ? "Result classification: " + draft.resultClassification() + "\n" : "";
        // preRegLine + classLine concatenated into a single composite slot; both
        // may be empty independently. Pre-carve used positional %s%s for the same.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",                draft.domain());
        params.put("topicName",             draft.topicName());
        params.put("preRegAndClassLines",   preRegLine + classLine);
        params.put("hypothesis",            narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the professor can address each block when prescribing experiments.
        params.put("findings",              formatFindingsForPrompt(narration));
        params.put("conclusion",            narration.conclusion());
        if (literatureContext != null && !literatureContext.isEmpty()) {
            params.put("literatureContext", literatureContext);
            return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                    "v2-with-literature", params);
        }
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_USER_VERSION, params);
    }

    /**
     * Phase 11 C2 — addendum appended to the student-revision prompt after the
     * professor's tool calls have produced a trace with stable {@code call_N}
     * IDs. Two things this asks the LLM to do:
     * <ol>
     *   <li>Place an inline {@code [call_N]} marker immediately after every
     *       numeric value cited in findings or conclusion.</li>
     *   <li>Emit a parallel {@code numeric_claims} array with one entry per
     *       cited value, carrying the same {@code call_N} ID for cross-check.</li>
     * </ol>
     * The CitationValidator (C3) checks both shapes against the persisted
     * {@link com.emergentterrain.llm.ToolInvocation} trace. In C4 warn-only
     * mode the validator only stamps the call_path; in later modes it routes
     * unattributable papers to NULL_RESULT or suppresses them.
     *
     * <p>Skipped on the first-pass and critique-loop prompts because no tool
     * calls have run yet at those points — there are no {@code call_N} IDs to
     * cite.
     */
    static final String CITATION_RULE_ADDENDUM = """


            CITATION RULE (Phase 11): every numeric value in `findings` or `conclusion` \
            (any number with or without units, except citation indices like [1], equation \
            numbers like (eq. 3), and year mentions like 1884) must be IMMEDIATELY followed \
            by a tool-call marker `[call_N]` referring to the tool call that produced or \
            directly uses that value. Tool results in the professor's synthesis above are \
            already prefixed with their `[call_N]` IDs — reuse those exact markers.

            ALSO emit a parallel `numeric_claims` JSON array. One entry per cited value, \
            shape: {"value": 7.553, "units": "", "tool_call_id": "call_2", \
            "context": "ln(Kc/Kc0) at T=792 K", "source_location": "findings:1"}. \
            `source_location` is one of "hypothesis", "findings:N" (1-based block index), or \
            "conclusion". A number cited in prose without a matching `numeric_claims` entry \
            (or with a `tool_call_id` whose value does not appear in that call's result) \
            will reject the paper. Omit the array entirely if there are zero numeric values \
            in your prose; do NOT emit fabricated entries to fill it.
            """;

    static String buildRevisionPromptFromSynthesis(PromptTemplateRegistry registry,
                                                    PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis) {
        return buildRevisionPromptFromSynthesis(registry, draft, firstPass, professorSynthesis, null);
    }

    /** Phase 6 D1.3 — when {@code literatureContext} is non-null/non-empty the
     *  {@code v2-from-synthesis-with-literature} variant carries it through to the
     *  student-revision step so the revised text can reference the cited closed-form. */
    static String buildRevisionPromptFromSynthesis(PromptTemplateRegistry registry,
                                                    PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis,
                                                    String literatureContext) {
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        // Phase 10 — same accumulator-format treatment as the refinement loop:
        // drop the `param1=N, param2=M.M` literal that the LLM was parroting
        // into the published Hypothesis (P-344, P-355, P-356, P-359, P-362)
        // and Findings (P-344, P-359). Use the existing natural-language form
        // already in use at line 1379-1381 so the two prompts stay aligned.
        String paramsLine = draft.param1() != null
                ? "Computation parameters: starting position %d, scale %.1f\n"
                        .formatted(draft.param1(), draft.param2())
                : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramsLine",         paramsLine);
        params.put("domain",             draft.domain());
        params.put("topicName",          draft.topicName());
        params.put("professorSynthesis", professorSynthesis);
        params.put("hypothesis",         firstPass.hypothesis());
        // Phase 7 F2 — labeled per-computation layout so the student-revision LLM
        // knows which block to revise when only one block needs touching.
        params.put("findings",           formatFindingsForPrompt(firstPass));
        params.put("conclusion",         firstPass.conclusion());
        params.put("conclusionSpec",     conclusionSpec);
        if (literatureContext != null && !literatureContext.isEmpty()) {
            params.put("literatureContext", literatureContext);
            return registry.render(PromptTemplateIds.NARRATION_REVISION_ID,
                    "v2-from-synthesis-with-literature", params) + CITATION_RULE_ADDENDUM;
        }
        return registry.render(PromptTemplateIds.NARRATION_REVISION_ID, "v1-from-synthesis", params)
                + CITATION_RULE_ADDENDUM;
    }

    // ─────────────────────────────────────────────────────────────────────────

    static String buildRevisionPrompt(PromptTemplateRegistry registry,
                                      PaperDraft draft, NarrationResult firstPass,
                                      List<String> weaknesses) {
        return buildRevisionPrompt(registry, draft, firstPass, weaknesses, null);
    }

    static String buildRevisionPrompt(PromptTemplateRegistry registry,
                                      PaperDraft draft, NarrationResult firstPass,
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",             draft.domain());
        params.put("topicName",          draft.topicName());
        params.put("hypothesis",         firstPass.hypothesis());
        // Phase 7 F2 — labeled per-computation layout in the revision prompt's
        // current-findings slot.
        params.put("findings",           formatFindingsForPrompt(firstPass));
        params.put("conclusion",         firstPass.conclusion());
        params.put("weaknessList",       weaknessList);
        params.put("experimentSection",  experimentSection);
        params.put("conclusionSpec",     conclusionSpec);
        return registry.render(PromptTemplateIds.NARRATION_REVISION_ID, "v1-from-critique", params);
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
            // Break the refinement loop when the gate is closed — refinement is
            // optional (it improves findings, not paper validity), so spending
            // any further fast-pool tokens here is wasted under budget pressure.
            if (simulationGate != null && !simulationGate.isOpen()) {
                log.info("LLM refinement {}/{} skipped: gate closed paperId={} reason={}",
                        iteration, MAX_REFINEMENTS, draft.paperId(), simulationGate.reason());
                break;
            }
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

                // Phase 10 — drop the `(param1=N, param2=M.M)` annotation that
                // was leaking verbatim into the published prose of P-344,
                // P-347, P-355, P-356, P-359, P-362 (6 of 21 papers per
                // REVIEW_REPORT.md refresh #3). The rationale and refined
                // findings already carry the substantive information the
                // narrator LLM needs to differentiate computations; the
                // harness-internal token names contribute nothing scientifically
                // and only invite parrot-back into the user-visible Hypothesis
                // and Findings.
                accumulatedFindings += "\nComputation %d — %s: %s"
                        .formatted(iteration + 1, decision.rationale(), refined.findings());
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
                draft.preRegistration(), draft.resultClassification(),
                draft.plannerQuestionId());
    }

    private String buildRefinementPrompt(PaperDraft draft, String accumulatedFindings,
                                          int iteration, int maxIterations) {
        String compact = compactRawFindings(accumulatedFindings);
        String iterContext = iteration == 1
                ? "initial result"
                : "results so far (%d computations completed)".formatted(iteration);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("iterContext",          iterContext);
        params.put("domain",               draft.domain());
        params.put("topicName",            draft.topicName());
        params.put("methodology",          draft.methodology());
        params.put("param1",               String.valueOf(draft.param1()));
        params.put("param2",               String.format("%.1f", draft.param2()));
        params.put("accumulatedFindings",  compact);
        params.put("remainingIterations",  String.valueOf(maxIterations - iteration + 1));
        params.put("maxIterations",        String.valueOf(maxIterations));
        return promptRegistry.render(PromptTemplateIds.NARRATION_REFINEMENT_ID, params);
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
            String response = router.callFast(buildStatisticalFramingPrompt(promptRegistry, raw, draft.domain(), draft.topicName()));
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
                        draft.preRegistration(), draft.resultClassification(),
                        draft.plannerQuestionId());
            }
        } catch (Exception e) {
            log.warn("LLM statistical framing failed paperId={}: {} — using raw findings",
                    draft.paperId(), e.getMessage());
        }
        return draft;
    }

    static String buildStatisticalFramingPrompt(PromptTemplateRegistry registry,
                                                 String rawFindings, String domain, String topicName) {
        String trimmed = rawFindings.length() > 500 ? rawFindings.substring(0, 500) + "…" : rawFindings;
        return registry.render(PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_ID, Map.of(
                "domain",      domain,
                "topicName",   topicName,
                "rawFindings", trimmed));
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

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",         draft.domain());
        params.put("topicName",      draft.topicName());
        params.put("methodology",    draft.methodology());
        params.put("title",          draft.title());
        params.put("generation",     String.valueOf(draft.generation()));
        params.put("paramsLine",     paramsLine);
        params.put("followUpNote",   followUpNote);
        params.put("anomalyNote",    anomalyNote);
        params.put("rawFindings",    compactRawFindings(draft.rawFindings()));
        params.put("scaffoldHyp",    scaffoldHyp);
        params.put("scaffoldCon",    scaffoldCon);
        params.put("priorPapers",    priorSection.trim());
        params.put("preRegSection",  preRegSection);
        params.put("conclusionSpec", conclusionSpec);
        // Phase 7 F2 — when the structured-findings flag is on AND maxComputations > 1,
        // render a v3 variant that asks for one prose block per computation. When the
        // flag is off (or maxComputations==1), keep the v1 / v2-with-discussion path
        // exactly as before — flag-off bit-identical to F1-baseline.
        if (structuredFindingsEnabled && maxComputations > 1) {
            params.put("maxComputations",      String.valueOf(maxComputations));
            params.put("findingsSpec",         renderStructuredFindingsSpec(maxComputations));
            params.put("findingsInstructions", renderStructuredFindingsInstructions(maxComputations));
            String variant = discussionSectionEnabled
                    ? "v3-structured-findings-with-discussion"
                    : "v3-structured-findings";
            return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID, variant, params);
        }
        // Phase 6 D2 — pick v2-with-discussion when the flag is on; otherwise v1.
        // Both variants take identical slots.
        if (discussionSectionEnabled) {
            return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID,
                    "v2-with-discussion", params);
        }
        return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID,
                PromptTemplateIds.NARRATION_PRIMARY_VERSION, params);
    }

    /**
     * Phase 7 F2 — returns the active NARRATION_PRIMARY template version that
     * buildNarrationPrompt selects, given the current flag state. Stamped onto
     * the persisted ResearchPaper so the per-variant rollback statistics see
     * the actual variant used rather than the constant "v1".
     */
    String activeNarrationPrimaryVersion() {
        if (structuredFindingsEnabled && maxComputations > 1) {
            return discussionSectionEnabled
                    ? "v3-structured-findings-with-discussion"
                    : "v3-structured-findings";
        }
        if (discussionSectionEnabled) return "v2-with-discussion";
        return PromptTemplateIds.NARRATION_PRIMARY_VERSION;
    }

    /**
     * Phase 7 F2 — render the {@code findingsSpec} slot for the v3 templates as a
     * comma-separated list of {@code "computation_N":"<paragraph N>"} pairs. The
     * narrator must emit exactly maxComputations keyed paragraphs; the parser
     * silently drops blanks so a missing final block degrades to NEEDS_REVISION
     * (via the per-block truncation guard) rather than crashing.
     */
    static String renderStructuredFindingsSpec(int maxComputations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= maxComputations; i++) {
            if (i > 1) sb.append(',');
            sb.append('"').append("computation_").append(i).append("\":");
            sb.append('"').append("<paragraph ").append(i).append('>').append('"');
        }
        return sb.toString();
    }

    /**
     * Phase 7 F2 — instructions paragraph telling the narrator how to write each
     * computation block. Lives in its own slot so v3 variants stay readable
     * regardless of how maxComputations grows.
     */
    static String renderStructuredFindingsInstructions(int maxComputations) {
        return ("Findings rules: emit exactly %d non-empty paragraphs, keyed computation_1 "
                + "through computation_%d. Each paragraph is 2-3 plain-prose sentences "
                + "interpreting the corresponding raw-output block above — its pattern, "
                + "trend, or notable numeric. Do NOT reproduce tables. Reference the "
                + "closed-form equation governing this topic when relevant. Cite prior "
                + "papers by ID where they bear on the block's interpretation.")
                .formatted(maxComputations, maxComputations);
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

            // Phase 2b — typed citations override the flat cited_paper_ids when present.
            // The LLM may emit either shape; we accept both and prefer the richer one.
            List<NarrationResult.TypedCitation> typedCitations = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode typedNode = root.get("cited_papers");
            if (typedNode != null && typedNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode e : typedNode) {
                    String id = e.path("id").asText(null);
                    if (id == null || !PAPER_ID_PATTERN.matcher(id).matches()) continue;
                    String typeStr = e.path("type").asText("EXTENDS").toUpperCase();
                    com.emergentterrain.research.RelationshipType type;
                    try {
                        type = com.emergentterrain.research.RelationshipType.valueOf(typeStr);
                    } catch (IllegalArgumentException iae) {
                        type = com.emergentterrain.research.RelationshipType.EXTENDS;
                    }
                    double conf = Math.max(0.0, Math.min(1.0, e.path("confidence").asDouble(0.7)));
                    typedCitations.add(new NarrationResult.TypedCitation(id, type, conf));
                }
            }

            List<String> citedPaperIds = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode citedNode = root.get("cited_paper_ids");
            if (citedNode != null && citedNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode idNode : citedNode) {
                    String id = idNode.asText(null);
                    if (id != null && PAPER_ID_PATTERN.matcher(id).matches()) citedPaperIds.add(id);
                }
            }
            // When typed citations are present, the flat ID list mirrors them so legacy
            // consumers (regex fallback, audit logs) still see every cited paper.
            if (!typedCitations.isEmpty() && citedPaperIds.isEmpty()) {
                for (NarrationResult.TypedCitation t : typedCitations) citedPaperIds.add(t.paperId());
            }

            // Phase 4 Iter 9 — optional formal_findings array. Each entry maps to a
            // FormalFinding row when the findings flag is on; malformed entries
            // are silently skipped (don't fail the whole paper for one bad finding).
            List<NarrationResult.ExtractedFinding> formalFindings = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode findingsNode = root.get("formal_findings");
            if (findingsNode != null && findingsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode f : findingsNode) {
                    NarrationResult.ExtractedFinding ef = parseFormalFinding(f);
                    if (ef != null) formalFindings.add(ef);
                }
            }

            // Phase 6 D2 — optional Discussion + Related-Work fields. Null when the
            // narrator did not emit the keys (D2 flag off, or v1 template); the
            // markdown writer omits the sections in that case.
            String discussion = optionalText(root, "discussion");
            String relatedWork = optionalText(root, "related_work");
            // Phase 7 F2 — findings may be either a flat string (legacy) or a map
            // keyed by computation_N. Either shape funnels into the canonical
            // Map<Integer, String> on NarrationResult.
            java.util.Map<Integer, String> findingsMap = parseFindingsShape(root.get("findings"));
            // Phase 11 C2 — parse numeric_claims if present; null when absent (pre-Phase-11
            // papers or runs where the prompt's new schema rule did not yet reach the LLM).
            java.util.List<NarrationResult.NumericClaim> numericClaims =
                    parseNumericClaims(root.get("numeric_claims"));
            return new NarrationResult(
                    root.path("hypothesis").asText(null),
                    findingsMap,
                    root.path("conclusion").asText(null),
                    followUp, null, confidenceScore, List.copyOf(citedPaperIds),
                    null, List.copyOf(typedCitations), List.copyOf(formalFindings),
                    null, null, discussion, relatedWork, null, null, null, numericClaims);
        } catch (Exception e) {
            log.warn("LLM narration: JSON parse failed: {}", e.getMessage());
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        }
    }

    /**
     * Phase 7 F2 — parse the {@code findings} JSON node into the canonical
     * Map&lt;Integer, String&gt; shape. Accepts three node shapes:
     * <ul>
     *   <li>Text — wrapped as {1: prose}, the F1-baseline shape.</li>
     *   <li>Object with {@code computation_N} or {@code N} keys — parsed to a
     *       sorted map keyed by the parsed integer; blank / null values dropped.</li>
     *   <li>Anything else (array, number, null, missing) — null.</li>
     * </ul>
     */
    static java.util.Map<Integer, String> parseFindingsShape(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) {
            String s = node.asText();
            return (s == null || s.isBlank()) ? null : java.util.Map.of(1, s);
        }
        if (node.isObject()) {
            java.util.Map<Integer, String> raw = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Integer idx = parseComputationKey(entry.getKey());
                if (idx == null) return;
                com.fasterxml.jackson.databind.JsonNode v = entry.getValue();
                if (v == null || !v.isTextual()) return;
                String prose = v.asText();
                if (prose != null && !prose.isBlank()) raw.put(idx, prose);
            });
            return NarrationResult.orderedFindingsMap(raw);
        }
        return null;
    }

    /**
     * Phase 7 F2 — parse a findings-map key into the 1-based computation index.
     * Accepts {@code "computation_1"}, {@code "computation 1"}, {@code "1"}, or
     * {@code "comp1"} forms. Returns null when the key cannot be mapped to a
     * positive integer (silently skipped by the caller).
     */
    static Integer parseComputationKey(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        // Strip a leading "computation" / "comp" / "block" prefix plus any separator.
        String body = trimmed
                .replaceFirst("(?i)^(computation|comp|block)[\\s_:#]*", "");
        if (body.isEmpty()) return null;
        try {
            int n = Integer.parseInt(body);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Phase 11 C2 — parse the {@code numeric_claims} array on the LLM output.
     * Returns null when the field is absent (pre-Phase-11 papers, or runs where
     * the prompt's new schema rule hasn't reached the LLM yet). Returns an
     * empty list when the array is present but empty. Skips malformed entries
     * silently — the validator treats a too-short array as separate coverage
     * failures, but a single bad entry must not poison the whole list.
     *
     * <p>Expected entry shape:
     * <pre>{"value": 7.553, "units": "", "tool_call_id": "call_2",
     *      "context": "ln(Kc/Kc0) at T=792 K", "source_location": "findings:1"}</pre>
     */
    static java.util.List<NarrationResult.NumericClaim> parseNumericClaims(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isArray()) {
            log.warn("LLM narration: numeric_claims is not an array (got {}); ignoring.",
                    node.getNodeType());
            return java.util.List.of();
        }
        java.util.List<NarrationResult.NumericClaim> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode entry : node) {
            if (entry == null || !entry.isObject()) continue;
            com.fasterxml.jackson.databind.JsonNode valueNode = entry.get("value");
            if (valueNode == null || !valueNode.isNumber()) continue;
            String toolCallId = optionalText(entry, "tool_call_id");
            if (toolCallId == null) continue;
            String units = optionalText(entry, "units");
            String context = optionalText(entry, "context");
            String sourceLocation = optionalText(entry, "source_location");
            out.add(new NarrationResult.NumericClaim(
                    valueNode.asDouble(),
                    units == null ? "" : units,
                    toolCallId,
                    context == null ? "" : context,
                    sourceLocation == null ? "" : sourceLocation));
        }
        return java.util.List.copyOf(out);
    }

    /** Phase 6 D2 — returns the JSON string at {@code key}, or null when absent / blank / non-string. */
    private static String optionalText(com.fasterxml.jackson.databind.JsonNode root, String key) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(key);
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * Phase 4 Iter 9 — parse one {@code formal_findings[]} entry. Returns null on
     * malformed input rather than throwing, so a single bad finding doesn't
     * disqualify the rest of the narration. Confidence is clamped to [0,1];
     * unknown enum values silently fall back to NULL / OBSERVATIONAL.
     */
    static NarrationResult.ExtractedFinding parseFormalFinding(
            com.fasterxml.jackson.databind.JsonNode f) {
        if (f == null || !f.isObject()) return null;
        String relStr = f.path("relation_type").asText("NULL").toUpperCase();
        com.emergentterrain.research.RelationType relation;
        try {
            relation = com.emergentterrain.research.RelationType.valueOf(relStr);
        } catch (IllegalArgumentException e) {
            relation = com.emergentterrain.research.RelationType.NULL;
        }
        String depVar = f.path("dependent_variable").asText(null);
        if (depVar == null || depVar.isBlank()) return null;

        java.util.List<String> indepVars = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode indepNode = f.get("independent_variables");
        if (indepNode != null && indepNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode v : indepNode) {
                String name = v.asText(null);
                if (name != null && !name.isBlank()) indepVars.add(name);
            }
        }

        String equationForm = f.path("equation_form").asText(null);

        String claimStr = f.path("claim_status").asText("OBSERVATIONAL").toUpperCase();
        com.emergentterrain.research.MathPhysicsEngine.ClaimStatus claim;
        try {
            claim = com.emergentterrain.research.MathPhysicsEngine.ClaimStatus.valueOf(claimStr);
        } catch (IllegalArgumentException e) {
            claim = com.emergentterrain.research.MathPhysicsEngine.ClaimStatus.OBSERVATIONAL;
        }

        double confidence = Math.max(0.0, Math.min(1.0, f.path("confidence").asDouble(0.5)));
        double validMin = f.path("valid_region_min").asDouble(Double.NEGATIVE_INFINITY);
        double validMax = f.path("valid_region_max").asDouble(Double.POSITIVE_INFINITY);

        return new NarrationResult.ExtractedFinding(
                relation, depVar, indepVars, equationForm,
                claim, confidence, validMin, validMax);
    }

    // Genome mentoring moved to GenomeMentoringService in Phase 0 Task 0.2.

    // --- Parsing helpers ---

    private static final java.util.Set<String> VALID_PREDICATES = java.util.Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");

    private static final java.util.Set<String> VALID_METRICS = java.util.Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

}
