package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-05-21 review §1 rec #7 — when a paper claims a scaling / power-law
 * / linearity relationship, the trace needs at least
 * {@link #MIN_DISTINCT_SAMPLES} independent sample points to make the
 * claim structurally testable. The existing
 * {@link SinglePointSweepValidator} catches narrator overclaims
 * (Findings says "n=40 windows" but trace has 1 call); this validator
 * catches honest underclaims (Findings says "σ scales as d^(-1/2)" with
 * trace = 2 calls).
 *
 * <p>P-a328-884 (Hall-Petch) is the canonical signature: 2 grain sizes
 * (0.05 µm and 1 µm) with σ values exactly matching σ₀ + k·d^(−1/2);
 * narrator honestly flagged the 0.05 µm point as unphysical for steel
 * but still claimed the scaling law. 2 points can fit any linear function
 * — you need 3+ to detect curvature or validate a power-law exponent.
 *
 * <p>Sweep-style tools ({@code run_param_sweep}, {@code run_predicate_scan},
 * {@code run_gpu_eval}, {@code evaluate_symbolic}) internally iterate over
 * many values, so one such call already counts as a valid sweep — the
 * gate looks at the trace's tool mix, not just its size. A paper using
 * any sweep tool passes the depth requirement regardless of trace size.
 *
 * <p>Stateless and pure; trace + prose passed by argument.
 */
public final class ScalingSweepDepthValidator {

    static final int MIN_DISTINCT_SAMPLES = 3;

    /**
     * Tool names whose single invocation internally iterates over many
     * sample points — calling one of these is equivalent to a sweep.
     */
    static final Set<String> SWEEP_STYLE_TOOLS = Set.of(
            "run_param_sweep",
            "run_predicate_scan",
            "run_gpu_eval",
            "evaluate_symbolic");

    /**
     * Prose patterns that imply a sweep is necessary for the claim to
     * be testable. Restricted to specific named relations and standard
     * phrasings so a paper that legitimately reports a single closed-form
     * evaluation isn't flagged for the word "scales".
     */
    private static final Pattern SCALING_CLAIM = Pattern.compile(
            "(?iu)(?:"
                    + "\\bscales?\\s+(?:as|with)\\b.{0,40}?(?:\\^|\\*\\*|sqrt|log|exp)"
                    + "|\\bscaling\\s+(?:relation|law|behaviou?r|regime)\\b"
                    + "|\\blinearity\\s+in\\b"
                    + "|\\blinear\\s+(?:trend|fit|relationship|regression)\\b"
                    + "|\\binverse(?:ly)?\\s+proportional\\b"
                    + "|\\bpower[-\\s]?law\\b"
                    + "|\\bHall[-\\u2010\\u2011]Petch\\s+(?:law|relation|trend|scaling)\\b"
                    + "|\\bArrhenius\\s+(?:law|relation|trend|scaling|behaviour)\\b"
                    + "|\\bHardy[-\\u2010\\u2011]Littlewood\\s+(?:density|prediction|conjecture)\\b"
                    + "|\\bStefan[-\\u2010\\u2011]Boltzmann\\s+(?:law|relation)\\b"
                    + "|\\btrend\\s+(?:in|across|with|of)\\s+(?:the\\s+)?(?:grain|temperature|window|range)\\b"
                    + ")",
            Pattern.DOTALL);

    private ScalingSweepDepthValidator() {}

    /**
     * Returns a single-entry reason list when the prose carries a
     * scaling-style claim but the trace lacks the depth to support it;
     * empty otherwise. Logic order:
     *
     * <ol>
     *   <li>Trace empty → empty (other validators handle this).</li>
     *   <li>Trace contains any sweep-style tool → empty (one sweep call is
     *       sufficient evidence depth).</li>
     *   <li>Trace size ≥ {@link #MIN_DISTINCT_SAMPLES} → empty.</li>
     *   <li>Prose contains no scaling-style claim → empty.</li>
     *   <li>Otherwise → flag.</li>
     * </ol>
     */
    public static List<String> validate(String hypothesis,
                                         Map<Integer, String> findingsMap,
                                         String conclusion,
                                         List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return List.of();
        for (ToolInvocation t : trace) {
            if (t != null && SWEEP_STYLE_TOOLS.contains(t.functionName())) {
                return List.of();
            }
        }
        if (trace.size() >= MIN_DISTINCT_SAMPLES) return List.of();

        String joined = joinProse(hypothesis, findingsMap, conclusion);
        if (joined.isEmpty()) return List.of();

        Matcher m = SCALING_CLAIM.matcher(joined);
        if (!m.find()) return List.of();

        return List.of(String.format(
                "Paper carries a scaling-style claim ('%s') but the tool-call trace "
                        + "has only %d non-sweep call(s); a scaling / linearity / "
                        + "power-law claim needs at least %d distinct sample points to "
                        + "be structurally testable. P-a328-884 signature.",
                m.group().trim(), trace.size(), MIN_DISTINCT_SAMPLES));
    }

    private static String joinProse(String hypothesis,
                                     Map<Integer, String> findingsMap,
                                     String conclusion) {
        StringBuilder sb = new StringBuilder();
        if (hypothesis != null && !hypothesis.isBlank()) sb.append(hypothesis).append(' ');
        if (findingsMap != null) {
            for (String block : findingsMap.values()) {
                if (block != null && !block.isBlank()) sb.append(block).append(' ');
            }
        }
        if (conclusion != null && !conclusion.isBlank()) sb.append(conclusion);
        return sb.toString();
    }
}
