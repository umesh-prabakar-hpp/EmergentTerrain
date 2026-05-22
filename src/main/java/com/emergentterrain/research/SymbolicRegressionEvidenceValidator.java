package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 24 §24.4 — flag papers whose prose claims a symbolic-regression
 * result (a recovered closed-form, a Pareto-front entry, a "PySR found
 * X" mention) without a matching {@code symbolic_regress} tool-call in
 * the audit trail.
 *
 * <p>Companion to {@link NumericalConsistencyValidator} for the SR
 * surface: the underlying concern is "the LLM wrote a numeric result
 * the tool never produced." For PySR specifically, the failure mode is
 * citing a polynomial or transcendental fit ("we recovered F = G·m1·m2/r²
 * via symbolic regression") with no corresponding tool call.
 *
 * <p>Scope (v1):
 * <ul>
 *   <li>Detect symbolic-regression claims by keyword: "symbolic
 *       regression", "PySR", "Pareto front", "recovered the law",
 *       "recovered closed-form".</li>
 *   <li>For each detected claim, the {@code toolCallTrace} must carry
 *       at least one {@code functionName == "symbolic_regress"} entry
 *       with a result that begins with the success prefix
 *       {@code "symbolic_regress:"} (matches the marker
 *       {@link com.emergentterrain.service.MathToolSuite#SUCCESS_PREFIXES}).
 *   </li>
 *   <li>Flag once per missing-evidence shape — even if the prose
 *       repeats the claim, one failure entry is enough.</li>
 * </ul>
 *
 * <p>What this does NOT catch (deferred to v1.5):
 * <ul>
 *   <li>Verbatim expression / mse matching. Prose might say "x*y with
 *       mse 1.2e-9" and the tool actually returned "y*x" or
 *       "mse=1.8e-9". A future pass can parse the Markdown Pareto
 *       table out of the result and check claim-by-claim.</li>
 *   <li>Backwards direction: the trace has a symbolic_regress call but
 *       the prose ignores it. That's a missed-opportunity signal,
 *       not a falsifiability one — different rubric.</li>
 *   <li>Tool-call results with the {@code busy} retry-friendly hint —
 *       those are not success, but they don't count as a fabricated
 *       claim either. They're just missing evidence.</li>
 * </ul>
 *
 * <p>Stateless and pure; tool trace + prose passed by argument so the
 * validator can be unit-tested without the Spring context. Mirrors
 * {@link CitationCorpusValidator} / {@link ScalingSweepDepthValidator}
 * in shape.
 */
public final class SymbolicRegressionEvidenceValidator {

    /**
     * Case-insensitive keyword set that signals the prose is claiming
     * a symbolic-regression result. Each phrase must be specific enough
     * to avoid false positives on generic math prose. "Regression"
     * alone is too common (linear regression, regression toward the
     * mean); "symbolic regression" pins it.
     *
     * <p>Stored as lowercase strings; {@link #containsAny} downcases
     * the prose before searching.
     */
    private static final List<String> CLAIM_KEYWORDS = List.of(
            "symbolic regression",
            "symbolic-regression",
            "pysr",
            "pareto front",
            "pareto frontier");

    /**
     * Patterns for "recovered ... law/closed-form" — looser than plain
     * substring so we catch articles ("recovered a closed-form",
     * "recovered the law"). Each pattern is compiled case-insensitive
     * and matched against the lowercased prose. The captured token is
     * surfaced in the failure message verbatim so operators see what
     * shape of claim tripped the validator.
     */
    private static final List<Pattern> CLAIM_PATTERNS = List.of(
            Pattern.compile("recovered\\s+(?:the\\s+|a\\s+)?(?:law|closed[-\\s]?form|expression)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:fit|found)\\s+(?:the\\s+|a\\s+)?(?:closed[-\\s]?form|symbolic\\s+law)",
                    Pattern.CASE_INSENSITIVE));

    /** Success prefix MathToolSuite stamps on a real symbolic_regress result. */
    private static final String SUCCESS_PREFIX = "symbolic_regress:";

    /** Matches the Markdown Pareto-table header MathToolSuite emits. */
    private static final Pattern PARETO_HEADER = Pattern.compile(
            "\\|\\s*Complexity\\s*\\|\\s*MSE\\s*\\|\\s*Expression\\s*\\|");

    private SymbolicRegressionEvidenceValidator() {}

    /**
     * Validate that any SR-flavoured claim in the prose is backed by a
     * matching tool-call. Returns the list of human-readable failure
     * messages; empty when prose has no SR claims, or every claim has
     * at least one supporting tool call.
     *
     * @param paperProse Concatenated paper text — findings + conclusion
     *                   + discussion. Null/blank → no failures.
     * @param toolCallTrace Audit trail of tool calls. Null/empty +
     *                      SR claim in prose → flagged.
     */
    public static List<String> validate(String paperProse,
                                         List<ToolInvocation> toolCallTrace) {
        if (paperProse == null || paperProse.isBlank()) {
            return List.of();
        }
        String lower = paperProse.toLowerCase(java.util.Locale.ROOT);
        List<String> claims = matchedKeywords(lower);
        if (claims.isEmpty()) {
            return List.of();
        }
        // The prose mentions SR. The trace must show at least one
        // successful symbolic_regress call.
        if (hasSuccessfulSymbolicRegressCall(toolCallTrace)) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        failures.add(
                "Prose mentions symbolic-regression result(s) [" + String.join(", ", claims) + "] "
                        + "but the tool-call trace contains no successful symbolic_regress call. "
                        + "Either remove the SR claim or add a matching tool call before re-publishing.");
        return failures;
    }

    /**
     * Stricter v1.5 entry point: returns failures both for missing
     * tool calls AND for SR-flavoured success calls whose Pareto front
     * is absent from the result. Not yet wired into the publish path;
     * available for unit tests + future opt-in tightening.
     */
    public static List<String> validateStrict(String paperProse,
                                                List<ToolInvocation> toolCallTrace) {
        List<String> failures = new ArrayList<>(validate(paperProse, toolCallTrace));
        if (toolCallTrace == null) return failures;
        for (ToolInvocation inv : toolCallTrace) {
            if (!"symbolic_regress".equals(inv.functionName())) continue;
            String result = inv.resultJson() == null ? "" : inv.resultJson();
            if (!result.startsWith(SUCCESS_PREFIX)) continue;
            Matcher m = PARETO_HEADER.matcher(result);
            if (!m.find()) {
                failures.add("symbolic_regress call (round=" + inv.round()
                        + ") returned the success prefix but no Pareto-table header. "
                        + "Result shape may have changed; re-check MathToolSuite format.");
            }
        }
        return failures;
    }

    private static List<String> matchedKeywords(String lowerProse) {
        List<String> hits = new ArrayList<>();
        for (String kw : CLAIM_KEYWORDS) {
            if (lowerProse.contains(kw)) hits.add(kw);
        }
        for (Pattern p : CLAIM_PATTERNS) {
            Matcher m = p.matcher(lowerProse);
            if (m.find()) {
                hits.add(m.group(0).trim());
            }
        }
        return hits;
    }

    private static boolean hasSuccessfulSymbolicRegressCall(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return false;
        for (ToolInvocation inv : trace) {
            if (!"symbolic_regress".equals(inv.functionName())) continue;
            String result = inv.resultJson() == null ? "" : inv.resultJson();
            if (result.startsWith(SUCCESS_PREFIX)) return true;
        }
        return false;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
