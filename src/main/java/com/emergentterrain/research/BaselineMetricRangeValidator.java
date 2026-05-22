package com.emergentterrain.research;

import java.util.Optional;

/**
 * 2026-05-21 review §1 rec #4 — catches the P-a328-871 failure shape where
 * the LLM pairs a probability-magnitude baseline (e.g. {@code 0.300}) with
 * a value-magnitude metric (e.g. {@code mean_of:sigmaSum}). σ(n) for the
 * integers in [45, 65) ranges 30–100; a 0.300 baseline against that metric
 * is ~250× off and almost certainly the narrator confusing
 * {@code fraction_satisfying} (a probability ∈ [0,1]) with
 * {@code mean_of:*} (an integer-scale magnitude).
 *
 * <p>The validator is invoked inline at the {@code run_predicate_scan} tool
 * call site (before the {@code DynamicProblem} is constructed), so the LLM
 * sees the rejection synchronously and can correct mid-loop — same shape
 * as the existing {@code Invalid metric '…'} / {@code Invalid predicate
 * token '…'} rejections in {@link com.emergentterrain.service.MathToolSuite}.
 *
 * <p>Conservative rules (only obvious shape mismatches flag):
 * <ul>
 *   <li>{@code fraction_satisfying}: baseline must be in {@code [0.0, 1.0]}
 *       — it's a probability.</li>
 *   <li>{@code mean_of:*}, {@code max_of:*}: baseline in {@code (0.0, 1.0)}
 *       is flagged. {@code baseline == 0.0} is the "no expectation" sentinel
 *       and intentionally allowed; integer-scale magnitudes ≥ 1 are
 *       allowed.</li>
 * </ul>
 *
 * <p>What this does NOT catch (deferred to a later iteration):
 * <ul>
 *   <li>Hypothesis prose stating one baseline (e.g. "≈ 0.300") while the
 *       tool call passes a different baseline (e.g. {@code 0}). The
 *       P-a328-871 prose-vs-args drift needs a separate prose-parser.</li>
 *   <li>Negative baselines against magnitude metrics (no observed
 *       failures yet — adding a check is premature).</li>
 * </ul>
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class BaselineMetricRangeValidator {

    private BaselineMetricRangeValidator() {}

    /**
     * Returns a populated Optional with a human-readable rejection reason
     * when the {@code (metric, baseline)} pairing is shape-mismatched;
     * empty Optional means the pairing is plausible (or the metric is
     * unrecognised, in which case the upstream {@code VALID_METRICS} check
     * has already rejected the call).
     */
    public static Optional<String> validate(String metric, double baseline) {
        if (metric == null) return Optional.empty();

        if ("fraction_satisfying".equals(metric)) {
            if (baseline < 0.0 || baseline > 1.0) {
                return Optional.of(String.format(
                        "Baseline %s implausible for metric 'fraction_satisfying' — "
                                + "fraction_satisfying returns a probability in [0,1]; "
                                + "pick a baseline in that range or pick a different metric.",
                        format(baseline)));
            }
            return Optional.empty();
        }

        if (metric.startsWith("mean_of:") || metric.startsWith("max_of:")) {
            if (baseline > 0.0 && baseline < 1.0) {
                return Optional.of(String.format(
                        "Baseline %s implausible for metric '%s' — '%s' returns an "
                                + "integer-magnitude value (mean / max of a counting "
                                + "function), so a baseline in (0,1) looks like a "
                                + "probability mistakenly paired with a magnitude metric. "
                                + "Use baseline=0 if you have no expectation, or a value "
                                + "≥ 1 matching the metric's natural scale.",
                        format(baseline), metric, metric));
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    private static String format(double v) {
        // Compact textual form: drop trailing .0 for integers, keep precision otherwise.
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
