package com.emergentterrain.service;

import com.emergentterrain.research.FollowUpParams;
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

    /** Set during wireUniversityCallback — calls university.publishPaper on completion. */
    private volatile java.util.function.BiConsumer<PaperDraft, NarrationResult> narrationPublisher;

    public ResearchNarrationService(LlmRouter router, ObjectMapper objectMapper,
                                    PromptTemplateRegistry promptRegistry) {
        this.router         = router;
        this.toolSuite      = new MathToolSuite(router, objectMapper);
        this.objectMapper   = objectMapper;
        this.promptRegistry = promptRegistry;
        this.narrationSlot  = new Semaphore(router.primaryPoolSize());
        log.info("ResearchNarrationService: narrationSlots={}", narrationSlot.availablePermits());
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

        // null means professor blank or all-TPD — pass null so queue counter is decremented.
        // Phase 4c Piece 2 gate 3: every emitted result is stamped with the primary
        // narration template id so the persisted ResearchPaper carries provenance.
        // Refinement / critique / revision steps are tracked separately via callPath.
        NarrationResult result = finalResult == null ? null : new NarrationResult(
                finalResult.hypothesis(), finalResult.findings(), finalResult.conclusion(),
                finalResult.followUp(), router.primaryModel(),
                finalResult.confidenceScore(), finalResult.citedPaperIds(), pathStr,
                finalResult.typedCitations(), finalResult.formalFindings(),
                com.emergentterrain.research.PromptTemplateIds.NARRATION_PRIMARY_ID,
                com.emergentterrain.research.PromptTemplateIds.NARRATION_PRIMARY_VERSION);
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
            String systemPrompt = buildProfessorSystemPrompt(promptRegistry, auditSummary);
            String userPrompt   = buildProfessorUserPrompt(promptRegistry, draft, firstPass);

            // Phase 5 C3 — collect per-paper sandbox-reject counts and surface them on
            // the persisted LLM Call Path. Reviewers can then tell whether a Findings
            // claim was actually computed or hand-waved after the sandbox denied the
            // narrator's code attempts.
            MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
            String professorSynthesis = router.callPrimaryWithTools(
                    systemPrompt, userPrompt, toolsJson,
                    toolSuite.buildExecutor(draft, metrics));
            if (metrics.sandboxRejectCount() > 0) {
                callPath.add("sandbox_reject=" + metrics.sandboxRejectCount());
            }

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
            router.streamPrimary(buildRevisionPromptFromSynthesis(promptRegistry, draft, firstPass, professorSynthesis),
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
        params.put("findings",            narration.findings());
        params.put("conclusion",          narration.conclusion());
        params.put("experimentSpec",      experimentSpec);
        return registry.render(PromptTemplateIds.NARRATION_CRITIQUE_ID, params);
    }

    // ── Tool-calling professor prompts ────────────────────────────────────────

    // ── Audit gap record ─────────────────────────────────────────────────────
    private record AuditGap(String dimension, String severity, String description) {}

    // ── Quality audit (fast Ollama) ──────────────────────────────────────────

    private String runQualityAudit(PaperDraft draft, NarrationResult firstPass,
                                    List<String> callPath) {
        try {
            long t = System.currentTimeMillis();
            String raw = router.callFast(buildAuditPrompt(promptRegistry, draft, firstPass));
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

    static String buildAuditPrompt(PromptTemplateRegistry registry,
                                    PaperDraft draft, NarrationResult narration) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",         draft.domain());
        params.put("topicName",      draft.topicName());
        params.put("classification", draft.resultClassification() != null
                ? draft.resultClassification() : "UNKNOWN");
        params.put("hypothesis",     narration.hypothesis());
        params.put("findings",       narration.findings());
        params.put("conclusion",     narration.conclusion());
        return registry.render(PromptTemplateIds.NARRATION_AUDIT_ID, params);
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

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry) {
        return buildProfessorSystemPrompt(registry, "");
    }

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry, String auditSummary) {
        String auditSection = (auditSummary != null && !auditSummary.isBlank())
                ? "\nA quality audit identified these specific gaps. Address them with your tools:\n"
                  + auditSummary + "\n"
                : "";
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                Map.of("auditSection", auditSection));
    }

    static String buildProfessorUserPrompt(PromptTemplateRegistry registry,
                                            PaperDraft draft, NarrationResult narration) {
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
        params.put("findings",              narration.findings());
        params.put("conclusion",            narration.conclusion());
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID, params);
    }

    static String buildRevisionPromptFromSynthesis(PromptTemplateRegistry registry,
                                                    PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis) {
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        String paramsLine = draft.param1() != null
                ? "Primary parameters: param1=%d, param2=%.1f\n".formatted(draft.param1(), draft.param2())
                : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramsLine",         paramsLine);
        params.put("domain",             draft.domain());
        params.put("topicName",          draft.topicName());
        params.put("professorSynthesis", professorSynthesis);
        params.put("hypothesis",         firstPass.hypothesis());
        params.put("findings",           firstPass.findings());
        params.put("conclusion",         firstPass.conclusion());
        params.put("conclusionSpec",     conclusionSpec);
        return registry.render(PromptTemplateIds.NARRATION_REVISION_ID, "v1-from-synthesis", params);
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
        params.put("findings",           firstPass.findings());
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
        return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID, params);
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

            return new NarrationResult(
                    root.path("hypothesis").asText(null),
                    root.path("findings").asText(null),
                    root.path("conclusion").asText(null),
                    followUp, null, confidenceScore, List.copyOf(citedPaperIds),
                    null, List.copyOf(typedCitations), List.copyOf(formalFindings));
        } catch (Exception e) {
            log.warn("LLM narration: JSON parse failed: {}", e.getMessage());
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        }
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
