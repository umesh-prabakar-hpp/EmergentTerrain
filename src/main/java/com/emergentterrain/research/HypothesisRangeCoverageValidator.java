package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper-review 2026-05-22 fix 4 — flag papers whose Hypothesis text
 * declares a parameter range but whose tool-call trace covers only a
 * single point in (or outside) that range. Phase 5 B-series style;
 * ships in warn mode first.
 *
 * <p>Worked examples from the 2026-05-22 /paper-review on
 * research/a3289005:
 * <ul>
 *   <li>P-a328-900: Hypothesis says "the number of visible diffraction
 *       orders will decrease as the wavelength increases from 400nm to
 *       700nm" but only λ=654nm was tested in {@code run_symbolic}.</li>
 *   <li>P-a328-901: Hypothesis says "for a unit-area emitter swept from
 *       T=2800 K..." but only one σ value was checked. (No upper bound
 *       in the text — this validator does not fire on open-ended
 *       sweeps; that would need a separate single-point detector.)</li>
 * </ul>
 *
 * <p>Heuristic, in order:
 * <ol>
 *   <li>Find a {@code "from X[unit] to Y[unit]"} or {@code "between X
 *       and Y"} pattern in the hypothesis. Both bounds must be
 *       explicit; open-ended phrases ({@code "from T=2800 K"} with no
 *       upper) are deliberately skipped.</li>
 *   <li>Extract numeric values from the persisted trace's {@code
 *       argsJson} fields.</li>
 *   <li>Filter trace values to those in the stated range {@code
 *       [min(X,Y), max(X,Y)]}. Call them {@code points}.</li>
 *   <li>Flag if {@code points.size() < 2} OR
 *       {@code span(points) < COVERAGE_FRACTION * width(range)} —
 *       i.e. the sweep covers less than half the stated range.</li>
 * </ol>
 *
 * <p>Returns an empty list when no range pattern matches OR when the
 * trace is empty (legacy / fallback paths legitimately have no
 * trace). Stateless and pure; safe to call from any thread.
 */
public final class HypothesisRangeCoverageValidator {

    private HypothesisRangeCoverageValidator() {}

    /**
     * Coverage fraction required of {@code span(points) / width(range)}.
     * 0.5 = half the range. Drawn from the P-a328-900 case where a single
     * λ=654 nm out of a stated 400–700 nm sweep should fire; 1-point
     * coverage trivially fails this band.
     */
    private static final double COVERAGE_FRACTION = 0.5;

    /**
     * "from X to Y" with optional unit tokens between/after the numbers.
     * Matches "from 400nm to 700nm", "from 400 nm to 700 nm",
     * "from -10 to 10", "from 2.5 to 5.0 GHz".
     */
    private static final Pattern FROM_X_TO_Y = Pattern.compile(
            "(?i)\\bfrom\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*[A-Za-z%°]*\\s+"
                    + "to\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*[A-Za-z%°]*");

    /**
     * "between X and Y" — same shape, alternate phrasing.
     */
    private static final Pattern BETWEEN_X_AND_Y = Pattern.compile(
            "(?i)\\bbetween\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*[A-Za-z%°]*\\s+"
                    + "and\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*[A-Za-z%°]*");

    /**
     * "[X, Y]" / "[X,Y]" / "(X..Y)" range notation. Caps both bounds
     * within {@code \d{1,7}(?:\.\d+)?} to avoid matching coordinates,
     * fractions, or stray bracket pairs.
     */
    private static final Pattern BRACKET_RANGE = Pattern.compile(
            "[\\[(]\\s*([+-]?\\d{1,7}(?:\\.\\d+)?)\\s*[,.]{1,2}\\s*"
                    + "([+-]?\\d{1,7}(?:\\.\\d+)?)\\s*[\\])]");

    /**
     * Extract every numeric literal from a JSON-shaped string. Lenient
     * by design — the regex matches any {@code [+-]?\d+(?:\.\d+)?},
     * including those inside string fields. False positives are
     * acceptable because the downstream filter rejects values outside
     * the stated range anyway.
     */
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    /**
     * Run the range-coverage check.
     *
     * @param hypothesis the published Hypothesis prose
     * @param trace      persisted tool-call trace
     * @return non-empty issue list when the hypothesis declares a
     *         range but the trace fails to cover ≥
     *         {@link #COVERAGE_FRACTION} of it
     */
    public static List<String> validate(String hypothesis, List<ToolInvocation> trace) {
        if (hypothesis == null || hypothesis.isBlank()) return List.of();
        if (trace == null || trace.isEmpty()) return List.of();

        double[] range = findRange(hypothesis);
        if (range == null) return List.of();
        double lo = Math.min(range[0], range[1]);
        double hi = Math.max(range[0], range[1]);
        double width = hi - lo;
        if (width <= 0) return List.of();  // degenerate

        List<Double> points = collectInRangeValues(trace, lo, hi);
        int n = points.size();
        if (n == 0) {
            return List.of(String.format(
                    "Hypothesis declares range [%s, %s] but no tool-call argument "
                            + "falls inside it — the sweep was not exercised.",
                    formatBound(lo), formatBound(hi)));
        }
        if (n == 1) {
            return List.of(String.format(
                    "Hypothesis declares range [%s, %s] (width %s) but only one "
                            + "tool-call argument (%s) covers it — single-point "
                            + "evaluation of a range claim.",
                    formatBound(lo), formatBound(hi), formatBound(width),
                    formatBound(points.get(0))));
        }
        double min = points.stream().mapToDouble(Double::doubleValue).min().orElse(lo);
        double max = points.stream().mapToDouble(Double::doubleValue).max().orElse(hi);
        double span = max - min;
        if (span < COVERAGE_FRACTION * width) {
            return List.of(String.format(
                    "Hypothesis declares range [%s, %s] (width %s) but the %d "
                            + "in-range tool-call argument(s) span only [%s, %s] "
                            + "(%.0f%% of range, %.0f%% required).",
                    formatBound(lo), formatBound(hi), formatBound(width), n,
                    formatBound(min), formatBound(max),
                    100.0 * span / width, 100.0 * COVERAGE_FRACTION));
        }
        return List.of();
    }

    /**
     * Try each range pattern in turn; first match wins.
     * Returns {@code null} when no pattern matches.
     */
    private static double[] findRange(String hypothesis) {
        for (Pattern p : new Pattern[]{FROM_X_TO_Y, BETWEEN_X_AND_Y, BRACKET_RANGE}) {
            Matcher m = p.matcher(hypothesis);
            if (m.find()) {
                try {
                    double a = Double.parseDouble(m.group(1));
                    double b = Double.parseDouble(m.group(2));
                    return new double[]{a, b};
                } catch (NumberFormatException ignored) {
                    // Continue to next pattern; the regex is lenient
                    // enough that a non-parseable capture should be rare
                    // but defensive on principle.
                }
            }
        }
        return null;
    }

    /**
     * Walk every {@code argsJson} in the trace and collect numeric
     * literals that fall inside {@code [lo, hi]}. Lenient — picks up
     * integers and floats in scientific notation; downstream caller
     * filters anything outside the bounds.
     */
    private static List<Double> collectInRangeValues(
            List<ToolInvocation> trace, double lo, double hi) {
        List<Double> out = new ArrayList<>();
        for (ToolInvocation inv : trace) {
            String args = inv.argsJson();
            if (args == null) continue;
            Matcher m = NUMERIC_LITERAL.matcher(args);
            while (m.find()) {
                try {
                    double v = Double.parseDouble(m.group());
                    if (v >= lo && v <= hi) out.add(v);
                } catch (NumberFormatException ignored) {
                    // regex shape is parseable; defensive ignore.
                }
            }
        }
        return out;
    }

    /** Compact human-readable formatter — integers without trailing zeros. */
    private static String formatBound(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.format("%.3f", v);
    }
}
