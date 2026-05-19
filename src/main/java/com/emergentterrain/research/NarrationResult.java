package com.emergentterrain.research;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * LLM-written narrative sections that replace the template text in a published paper.
 * Null/blank fields fall back to the template values stored in the PaperDraft.
 * followUp, when non-null, is stored on the author bot and used as search params for
 * their next paper, creating a chain of inquiry from one result to the next.
 * aiModel identifies which LLM generated the narration; null when template fallback is used.
 * confidenceScore (0.0–1.0) is the LLM's self-assessed confidence in the findings quality.
 * citedPaperIds is the structured list of prior paper IDs cited in the narrative.
 * callPath is the LLM call audit trail for this paper.
 *
 * Phase 2b — typedCitations carries optional per-citation relationship info
 * (id + RelationshipType + confidence). When the LLM emits the richer
 * {@code cited_papers} JSON shape, the parser populates this list and the
 * publish path writes typed PaperCitation rows instead of the default
 * {@link RelationshipType#EXTENDS}. Empty list = caller should fall back to
 * the untyped {@link #citedPaperIds} (heuristic EXTENDS edges).
 *
 * Phase 4c Piece 2 gate 3 — {@code templateId} and {@code templateVersion}
 * identify which prompt template variant produced this narration. Stamped at
 * pipeline emission so {@link UniversityService#publishPaper} can copy the
 * provenance onto the persisted {@link ResearchPaper}. Both null when the
 * narration came from a path that has not yet been instrumented (e.g. fallback
 * suppression results); the publish path treats null as "unattributed."
 *
 * Phase 7 F2 — {@code findingsMap} is the canonical, per-computation form of the
 * findings prose, keyed by 1-based computation index. The legacy String-shaped
 * {@link #findings()} accessor remains (joins the map's values in key order, or
 * returns the single value when the map has one entry — bit-identical to the
 * pre-F2 String field). All legacy constructors that took a {@code String findings}
 * still compile: they wrap the prose as {@code Map.of(1, prose)} into the canonical
 * record. The Map shape is what F2's per-block truncation guard and per-block H3
 * markdown renderer consume.
 */
public record NarrationResult(String hypothesis, Map<Integer, String> findingsMap, String conclusion,
                               FollowUpParams followUp, String aiModel,
                               double confidenceScore, List<String> citedPaperIds,
                               String callPath, List<TypedCitation> typedCitations,
                               List<ExtractedFinding> formalFindings,
                               String templateId, String templateVersion,
                               /* Phase 6 D2 — Discussion and Related-Work sections.
                                  Both nullable; null fields render no section. */
                               String discussion, String relatedWork,
                               /* Phase 7 G8 — audit-4b dimension names that fired on this paper.
                                  Empty list = audit clean / didn't run. Null treated as empty. */
                               List<String> auditGapDimensions,
                               /* Phase 7 F5.3 — agentic tool-call trace from the professor stage.
                                  Tier 2 of F5's provenance scheme. Empty list = professor stage
                                  used the JSON-path (no tool calls) or didn't run. Null treated
                                  as empty. Stamped onto the result via
                                  {@link #withToolCallTrace(List)} at the call site that ran the
                                  tool loop. */
                               List<com.emergentterrain.llm.ToolInvocation> toolCallTrace,
                               /* Phase 7 F7.2 — multi-reviewer panel outcome. Null when the panel
                                  did not run (flag off, no reviewer pools resolved, or paper had
                                  no tool-call trace to grade). Carries the median grades,
                                  agreement score, consensus cited/contradiction lists, and the
                                  raw N reviews. Stamped via {@link #withPanelOutcome(PanelOutcome)}. */
                               PanelOutcome panelOutcome,
                               /* Phase 11 C2 — structured numeric claims emitted alongside the
                                  prose. Every [call_N] citation in the prose has a matching entry
                                  here with the value, units, tool_call_id, and source location.
                                  The citation validator checks these against the tool-call trace.
                                  Null or empty when the LLM didn't emit the array (pre-Phase-11
                                  papers, or papers that the prompt's new schema rule didn't yet
                                  reach). Stamped via {@link #withNumericClaims(List)}. */
                               List<NumericClaim> numericClaims,
                               /* Phase 20.4 — narration-call envelope. Timing and per-call token
                                  totals as first-class fields so ActivityReporter rows and
                                  UniversityService publish-time persistence stop relying on
                                  call-path string parsing. All five default to null/0/UNKNOWN
                                  on legacy constructors (additive, non-breaking).

                                  - startedAt: wall-clock at orchestration entry. Null = not measured.
                                  - completedAt: wall-clock at orchestration exit (success or thrown).
                                    Null = not measured.
                                  - inputTokens: cumulative prompt tokens across all LLM calls that
                                    contributed to this narration (tool-loop rounds + revision passes).
                                    0 = not measured.
                                  - outputTokens: cumulative completion tokens, same scope. 0 = not
                                    measured.
                                  - status: terminal outcome enum. Maps directly from the existing
                                    exception branches in ResearchNarrationService (BUDGET_EXHAUSTED,
                                    ALL_MODELS_BLOCKED, TOOL_LOOP_LIMIT, TEMPLATE_FALLBACK, ERROR).
                                    UNKNOWN = not stamped (legacy emission site). OK = full success. */
                               Instant startedAt, Instant completedAt,
                               long inputTokens, long outputTokens,
                               OutcomeStatus status) {

    /**
     * Phase 20.4 — terminal outcome of one narration orchestration. Stamped on
     * the result so the publish path and ActivityReporter can branch on the
     * enum instead of parsing the call_path string ("sandbox_reject=N",
     * "all_models_blocked", "BudgetExceededException", etc.).
     *
     * Mapping from existing exception branches in ResearchNarrationService:
     *  - {@code OK}                — full success: LLM returned synthesis, no
     *                                fallback was needed.
     *  - {@code PARTIAL}           — synthesis returned but truncated (tool
     *                                loop hit MAX_TOOL_ROUNDS while still
     *                                producing usable output, or a revision
     *                                pass salvaged a panel-rejected draft).
     *  - {@code BUDGET_EXHAUSTED}  — {@code BudgetGate.tryAcquireOrThrow}
     *                                threw mid-orchestration; the narration
     *                                bailed before all rounds completed.
     *  - {@code ALL_MODELS_BLOCKED} — every primary handle reported
     *                                {@code isBlocked()=true} (per-pool
     *                                tpd/tpm/rateLimit expiries); the LlmRouter
     *                                fallback chain found nothing to call.
     *  - {@code TOOL_LOOP_LIMIT}    — the tool loop hit MAX_TOOL_ROUNDS without
     *                                the LLM emitting a final synthesis, and
     *                                no salvage path produced one.
     *  - {@code TEMPLATE_FALLBACK} — no LLM emission at all; the publish path
     *                                used the PaperDraft's template text.
     *  - {@code ERROR}             — uncaught error path; the orchestrator
     *                                logged but couldn't classify.
     *  - {@code UNKNOWN}           — emission site has not yet been migrated
     *                                to stamp a status. Treat as "not measured."
     */
    public enum OutcomeStatus {
        OK, PARTIAL, BUDGET_EXHAUSTED, ALL_MODELS_BLOCKED,
        TOOL_LOOP_LIMIT, TEMPLATE_FALLBACK, ERROR, UNKNOWN
    }

    /** Phase 2b — one typed citation entry from the LLM's structured output. */
    public record TypedCitation(String paperId, RelationshipType type, double confidence) {}

    /**
     * Phase 11 C2 — one numeric claim from Findings or Conclusion prose.
     * {@code value} is the asserted number (parsed from the LLM's JSON, not the prose).
     * {@code units} is a free-text unit string ("K", "kJ/mol", "MPa", "" for dimensionless).
     * {@code toolCallId} is the {@code "call_N"} marker that the LLM cited; must match
     * an ID in the persisted {@link com.emergentterrain.llm.ToolInvocation} trace.
     * {@code context} is the LLM's free-text description of what this value represents.
     * {@code sourceLocation} is one of: {@code "hypothesis"}, {@code "findings:N"} (1-based
     * computation index), {@code "conclusion"}.
     */
    public record NumericClaim(
            double value,
            String units,
            String toolCallId,
            String context,
            String sourceLocation) {}

    /**
     * Phase 4 Iter 9 — a single structured finding the narration LLM extracted
     * from the paper's results. Persisted to {@link FormalFinding} when the
     * findings flag is on. Carried on NarrationResult rather than written
     * directly so the publish path keeps the persistence + provenance logic
     * (extractedByBotId, validation, error handling) in one place.
     */
    public record ExtractedFinding(
            RelationType relationType,
            String dependentVariable,
            List<String> independentVariables,
            String equationForm,
            MathPhysicsEngine.ClaimStatus claimStatus,
            double confidence,
            double validRegionMin,
            double validRegionMax) {}

    /**
     * Phase 7 F2 — joined-prose form of the findings map. Single-entry map
     * returns its sole value verbatim (legacy behaviour). Multi-entry map
     * returns values joined by a blank line, in ascending key order. Null
     * or empty map returns null so downstream null-checks behave identically
     * to the pre-F2 String field.
     */
    public String findings() {
        if (findingsMap == null || findingsMap.isEmpty()) return null;
        if (findingsMap.size() == 1) {
            return findingsMap.values().iterator().next();
        }
        return new TreeMap<>(findingsMap).values().stream()
                .filter(v -> v != null)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Phase 11 C2 — 17-arg backward-compat for callers that already use the
     * canonical {@code Map<Integer, String> findingsMap} shape (mostly tests).
     * Defaults {@code numericClaims} to null (pre-Phase-11 behaviour: validator
     * exits early in warn mode when the array is absent).
     */
    public NarrationResult(String hypothesis, Map<Integer, String> findingsMap, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork,
                           List<String> auditGapDimensions,
                           List<com.emergentterrain.llm.ToolInvocation> toolCallTrace,
                           PanelOutcome panelOutcome) {
        this(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations,
                formalFindings, templateId, templateVersion, discussion, relatedWork,
                auditGapDimensions, toolCallTrace, panelOutcome, null,
                null, null, 0L, 0L, OutcomeStatus.UNKNOWN);
    }

    /**
     * Phase 20.4 — 18-arg backward-compat for callers that already passed
     * {@code numericClaims} but predate the envelope fields. Defaults timing
     * to null, token totals to 0, status to UNKNOWN — treat as "not measured."
     */
    public NarrationResult(String hypothesis, Map<Integer, String> findingsMap, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork,
                           List<String> auditGapDimensions,
                           List<com.emergentterrain.llm.ToolInvocation> toolCallTrace,
                           PanelOutcome panelOutcome,
                           List<NumericClaim> numericClaims) {
        this(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations,
                formalFindings, templateId, templateVersion, discussion, relatedWork,
                auditGapDimensions, toolCallTrace, panelOutcome, numericClaims,
                null, null, 0L, 0L, OutcomeStatus.UNKNOWN);
    }

    /**
     * Phase 7 F2 — single-prose legacy String-shaped 15-arg constructor. Wraps
     * the prose as a single-entry map at key 1 so the canonical {@link #findingsMap()}
     * field stays consistent. Null prose stays null.
     */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork,
                           List<String> auditGapDimensions) {
        this(hypothesis, wrapSingle(findings), conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations,
                formalFindings, templateId, templateVersion, discussion, relatedWork,
                auditGapDimensions, null, null, null);
    }

    /** 14-arg backward-compatible — null auditGapDimensions. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion,
                           String discussion, String relatedWork) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, null);
    }

    /** 12-arg backward-compatible — null Discussion / Related-Work fields. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings,
                           String templateId, String templateVersion) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, null, null, null);
    }

    /** 10-arg backward-compatible — null template provenance + null Discussion/Related-Work. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations,
                           List<ExtractedFinding> formalFindings) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, formalFindings, null, null, null, null, null);
    }

    /** 9-arg backward-compatible constructor — empty formalFindings, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath, List<TypedCitation> typedCitations) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, typedCitations, List.of(), null, null, null, null, null);
    }

    /** 8-arg backward-compatible — empty typed citations, empty findings, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds,
                           String callPath) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, callPath, List.of(), List.of(), null, null, null, null, null);
    }

    /** 7-arg backward-compatible — null callPath, empty extras, null template provenance. */
    public NarrationResult(String hypothesis, String findings, String conclusion,
                           FollowUpParams followUp, String aiModel,
                           double confidenceScore, List<String> citedPaperIds) {
        this(hypothesis, findings, conclusion, followUp, aiModel, confidenceScore,
                citedPaperIds, null, List.of(), List.of(), null, null, null, null, null);
    }

    /** Return a copy of this result with template provenance stamped on. */
    public NarrationResult withTemplateProvenance(String templateId, String templateVersion) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /** Phase 6 D2 — return a copy with the Discussion + Related-Work fields stamped on. */
    public NarrationResult withDiscussionAndRelatedWork(String discussion, String relatedWork) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /** Phase 7 G8 — return a copy with audit-4b gap dimensions stamped on. */
    public NarrationResult withAuditGapDimensions(List<String> auditGapDimensions) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /** Phase 7 F5.3 — return a copy with the agentic tool-call trace stamped on. */
    public NarrationResult withToolCallTrace(List<com.emergentterrain.llm.ToolInvocation> trace) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                trace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /** Phase 7 F7.2 — return a copy with the panel outcome stamped on. */
    public NarrationResult withPanelOutcome(PanelOutcome outcome) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, outcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /** Phase 11 C2 — return a copy with the numeric-claims array stamped on. */
    public NarrationResult withNumericClaims(List<NumericClaim> claims) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, claims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /**
     * Phase 17 — return a copy with the call_path string replaced. Used by
     * {@link GateRetryQueue} to stamp {@code gate-retry:gave-up:attempts=N}
     * on the cached first-pass before the give-up publish so the persisted
     * call_path explains why the paper landed on disk despite its
     * {@code gate:resources:professor} stamp.
     */
    public NarrationResult withCallPath(String newCallPath) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, newCallPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /**
     * Phase 20.4 — stamp the orchestration envelope (timing + cumulative
     * token totals + terminal status) on this result. Use at the emission
     * site that knows the wall-clock bracket and the per-call usage rollup.
     * All five fields are nullable / zero-defaulted on legacy paths, so a
     * caller that only knows status can pass null/null/0/0/status without
     * losing the existing semantics ("not measured").
     */
    public NarrationResult withEnvelope(Instant startedAt, Instant completedAt,
                                         long inputTokens, long outputTokens,
                                         OutcomeStatus status) {
        return new NarrationResult(hypothesis, findingsMap, conclusion, followUp, aiModel,
                confidenceScore, citedPaperIds, callPath, typedCitations, formalFindings,
                templateId, templateVersion, discussion, relatedWork, auditGapDimensions,
                toolCallTrace, panelOutcome, numericClaims,
                startedAt, completedAt, inputTokens, outputTokens, status);
    }

    /**
     * Phase 20.4 — narrow helper: stamp only the terminal status, preserve
     * existing timing / token totals. For emission sites that can classify
     * the outcome but don't have token accounting wired yet.
     */
    public NarrationResult withStatus(OutcomeStatus newStatus) {
        return withEnvelope(startedAt, completedAt, inputTokens, outputTokens, newStatus);
    }

    /**
     * Phase 7 F2 — wrap a single prose string into the canonical {1: prose} single-entry
     * map. Null prose returns null (downstream null-checks behave identically to the
     * pre-F2 String field).
     */
    private static Map<Integer, String> wrapSingle(String findings) {
        if (findings == null) return null;
        return Map.of(1, findings);
    }

    /**
     * Phase 7 F2 — construct an ordered findings map from a {@code computation_N} JSON
     * shape parsed at the LLM-output boundary. Sorts keys ascending so downstream
     * consumers (truncation guard, markdown renderer, validators) see a stable order
     * regardless of how the LLM serialised the object. Returns null when the input
     * is null or empty (preserving the legacy null-findings contract).
     */
    public static Map<Integer, String> orderedFindingsMap(Map<Integer, String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        Map<Integer, String> ordered = new LinkedHashMap<>();
        new TreeMap<>(raw).forEach((k, v) -> {
            if (v != null && !v.isBlank()) ordered.put(k, v);
        });
        return ordered.isEmpty() ? null : ordered;
    }
}
