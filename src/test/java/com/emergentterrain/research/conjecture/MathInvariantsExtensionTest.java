package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 5 — the flag-OFF byte-identity invariant for the 30.5 vocabulary-expansion overloads:
 * with no admitted scalars, {@code vocabulary(...)} / {@code extract(...)} reproduce the base paths
 * exactly (so {@code ConjectureEngineParityTest} and the mined survivor set are unaffected).
 */
class MathInvariantsExtensionTest {

    private static Map<String, Double> raw() {
        Map<String, Double> m = new java.util.HashMap<>(Map.of(
                "prime_density", 0.12, "mean_omega", 2.1, "mean_Omega", 2.8,
                "mertens_delta", -3.0, "abundant_density", 0.24, "deficient_density", 0.74,
                "happy_density", 0.14, "mean_divisor_count", 6.0, "max_divisor_count", 12.0));
        m.put("max_prime_gap", 34.0);
        m.put("twin_prime_density", 0.019);
        return m;
    }

    // ── Phase 30 §5.2 (2026-06-05) — open-behavior scalar math ─────────────────

    @Test
    void cramerGapRatioNormalizesByLogSquaredWindowCenter() {
        // center = 1000 + 500/2 = 1250; ln(1250) ≈ 7.1309; gap/ln² ≈ 34/50.85.
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500);
        double lc = Math.log(1250.0);
        org.assertj.core.api.Assertions.assertThat(flat.get("cramer_gap_ratio"))
                .isCloseTo(34.0 / (lc * lc), org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    void twinVsPrimeSqIsTheHardyLittlewoodRatio() {
        // 0.019 / 0.12² ≈ 1.3194 — near 2C₂ ≈ 1.3203 by construction of the fixture.
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500);
        org.assertj.core.api.Assertions.assertThat(flat.get("twin_vs_prime_sq"))
                .isCloseTo(0.019 / (0.12 * 0.12), org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    void newScalarsDegradeToZeroNotNaNOnMissingInputs() {
        Map<String, Double> flat = MathInvariants.extract(Map.of(), 0, 0);
        org.assertj.core.api.Assertions.assertThat(flat.get("cramer_gap_ratio")).isZero();
        org.assertj.core.api.Assertions.assertThat(flat.get("twin_vs_prime_sq")).isZero();
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.vocabulary())
                .contains("max_prime_gap", "twin_prime_density",
                        "cramer_gap_ratio", "twin_vs_prime_sq");
    }

    // ── 2026-06-08 — growth anchors (so a bound can track a growing LHS) ───────

    @Test
    void growthAnchorsComputeAndJoinTheVocabulary() {
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500);
        org.assertj.core.api.Assertions.assertThat(flat.get("sqrt_window_start"))
                .isCloseTo(Math.sqrt(1000.0), org.assertj.core.api.Assertions.within(1e-12));
        double lc = Math.log(1250.0);   // center = window_start + window_size/2
        org.assertj.core.api.Assertions.assertThat(flat.get("log_sq_window_start"))
                .isCloseTo(lc * lc, org.assertj.core.api.Assertions.within(1e-12));
        org.assertj.core.api.Assertions.assertThat(MathInvariants.vocabulary())
                .contains("sqrt_window_start", "log_sq_window_start");
    }

    // ── 2026-06-13 — second-order: mean_divisor_count − ln(center) → ≈ 2γ ──────

    @Test
    void meanDivisorExcessSubtractsNaturalLogOfCenterAndJoinsVocabulary() {
        Map<String, Double> r = new java.util.HashMap<>(raw());
        r.put("mean_divisor_count", 8.0);
        Map<String, Double> flat = MathInvariants.extract(r, 1000, 500);   // center = 1250
        org.assertj.core.api.Assertions.assertThat(flat.get("mean_divisor_excess"))
                .isCloseTo(8.0 - Math.log(1250.0), org.assertj.core.api.Assertions.within(1e-12));
        org.assertj.core.api.Assertions.assertThat(MathInvariants.vocabulary())
                .contains("mean_divisor_excess");
        // degrades to 0 (not NaN) when the window is degenerate
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 0, 0).get("mean_divisor_excess")).isZero();
    }

    @Test
    void erdosKacAndDirichletAreBasePassThroughScalarsInVocabulary() {
        // omega_ek_ratio + dirichlet_error are computed sidecar-side (numbertheory.jl) and PASS THROUGH
        // MathInvariants.extract as BASE scalars — so Java and Julia agree by construction.
        Map<String, Double> r = new java.util.HashMap<>(raw());
        r.put("omega_ek_ratio", 0.97);
        r.put("dirichlet_error", -0.42);
        Map<String, Double> flat = MathInvariants.extract(r, 1000, 500);
        org.assertj.core.api.Assertions.assertThat(flat.get("omega_ek_ratio")).isEqualTo(0.97);
        org.assertj.core.api.Assertions.assertThat(flat.get("dirichlet_error")).isEqualTo(-0.42);
        org.assertj.core.api.Assertions.assertThat(MathInvariants.vocabulary())
                .contains("omega_ek_ratio", "dirichlet_error");
        // absent in raw → 0.0 (BASE getOrDefault), never NaN
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 1000, 500).get("omega_ek_ratio")).isZero();
    }

    @Test
    void growthAnchorsDegradeToZeroOnMissingInputs() {
        Map<String, Double> flat = MathInvariants.extract(Map.of(), 0, 0);
        org.assertj.core.api.Assertions.assertThat(flat.get("sqrt_window_start")).isZero();
        org.assertj.core.api.Assertions.assertThat(flat.get("log_sq_window_start")).isZero();
    }

    // ── 2026-06-14 — Wigert growth anchor for max_divisor_count (§1 rec #1) ─────

    @Test
    void wigertBoundIsExpLogOverLogLogCenterAndJoinsVocabulary() {
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500);   // center = 1250
        double lc = Math.log(1250.0);
        double expected = Math.exp(lc / Math.log(lc));
        org.assertj.core.api.Assertions.assertThat(flat.get("wigert_bound"))
                .isCloseTo(expected, org.assertj.core.api.Assertions.within(1e-9));
        org.assertj.core.api.Assertions.assertThat(MathInvariants.vocabulary())
                .contains("wigert_bound");
    }

    @Test
    void wigertBoundTracksMaxDivisorGrowthSoAFlatGapCannotRefuteIt() {
        // The anchor grows with x at the divisor maximal-order rate, so the ratio of a realistic
        // max_divisor_count to the anchor stays O(1) across decades — the property that lets a
        // k·wigert_bound + c bound HOLD where a flat-feature bound is refuted by construction.
        double small = MathInvariants.extract(raw(), 300_000, 5_000).get("wigert_bound");
        double large = MathInvariants.extract(raw(), 1_500_000, 5_000).get("wigert_bound");
        org.assertj.core.api.Assertions.assertThat(large).isGreaterThan(small);   // monotone in x
        // measured peaks (P-b72c-77627585 / -77627584): 192 @300k, 240 @1.5M — both within a small
        // multiple of the anchor (ratios ≈1.3 and ≈1.13), i.e. one k∈grid + tight c upper-bounds them.
        org.assertj.core.api.Assertions.assertThat(192.0 / small).isLessThan(2.0);
        org.assertj.core.api.Assertions.assertThat(240.0 / large).isLessThan(2.0);
    }

    @Test
    void wigertBoundDegradesToZeroNotOverflowOnDegenerateWindow() {
        // center ≤ e ⇒ ln ln(center) ≤ 0; must return 0.0, never NaN/Infinity.
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 0, 0).get("wigert_bound")).isZero();
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 1, 0).get("wigert_bound")).isZero();
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(raw(), 1000, 500).get("wigert_bound")).isFinite();
    }

    // ── 2026-06-14 — ln ln x growth anchor for mean_omega/mean_Omega (§1 rec #1 redux) ─

    @Test
    void logLogWindowStartIsLnLnCenterAndJoinsVocabulary() {
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500);   // center = 1250
        double expected = Math.log(Math.log(1250.0));
        org.assertj.core.api.Assertions.assertThat(flat.get("log_log_window_start"))
                .isCloseTo(expected, org.assertj.core.api.Assertions.within(1e-12));
        org.assertj.core.api.Assertions.assertThat(MathInvariants.vocabulary())
                .contains("log_log_window_start");
    }

    @Test
    void logLogWindowStartTracksMeanOmegaGrowthSoAFlatFeatureCannotRefuteIt() {
        // mean_omega ~ ln ln x + B₁ + 1/ln x, so the anchor and the LHS grow together — the property
        // a flat feature lacks. The key invariant: mean_omega / anchor stays O(1) (≈constant) across
        // x, so a k·anchor + c bound tracks and holds, where a flat-feature bound is refuted.
        double atLow = MathInvariants.extract(raw(), 10_000, 1_000).get("log_log_window_start");
        double atHigh = MathInvariants.extract(raw(), 320_000, 2_000).get("log_log_window_start");
        org.assertj.core.api.Assertions.assertThat(atHigh).isGreaterThan(atLow);   // grows with x
        // measured mean_omega (P-b72c-77627596/-598): ≈2.608 over [1e4,3e4); ≈2.84 @ 3.2e5.
        double ratioLow = 2.608 / atLow;
        double ratioHigh = 2.84 / atHigh;
        org.assertj.core.api.Assertions.assertThat(ratioLow).isBetween(1.0, 1.4);
        org.assertj.core.api.Assertions.assertThat(ratioHigh).isBetween(1.0, 1.4);
        // the ratio is nearly flat across 1.5 orders of magnitude in x — the anchor tracks the growth.
        org.assertj.core.api.Assertions.assertThat(Math.abs(ratioLow - ratioHigh)).isLessThan(0.15);
    }

    @Test
    void logLogWindowStartDegradesToZeroNotNonFiniteOnDegenerateWindow() {
        // center ≤ 1 ⇒ ln(center) ≤ 0 ⇒ ln ln undefined; must return 0.0, never NaN/-Infinity.
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 0, 0).get("log_log_window_start")).isZero();
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(Map.of(), 1, 0).get("log_log_window_start")).isZero();
        org.assertj.core.api.Assertions.assertThat(
                MathInvariants.extract(raw(), 1000, 500).get("log_log_window_start")).isFinite();
    }

    @Test
    void emptyAdmittedReproducesBaseVocabulary() {
        assertThat(MathInvariants.vocabulary(List.of())).isEqualTo(MathInvariants.vocabulary());
        assertThat(MathInvariants.vocabulary(null)).isEqualTo(MathInvariants.vocabulary());
    }

    @Test
    void emptyAdmittedReproducesBaseExtract() {
        assertThat(MathInvariants.extract(raw(), 1000, 500, List.of()))
                .isEqualTo(MathInvariants.extract(raw(), 1000, 500));
    }

    @Test
    void admittedScalarsAreAppendedAndComputed() {
        DerivedScalar logPd = new DerivedScalar("log(prime_density)",
                OperatorGrammar.Expr.unary(OperatorGrammar.Unary.LOG,
                        OperatorGrammar.Expr.leaf("prime_density")), 1.0);

        // vocabulary gains the admitted name (after the base names, which are unchanged).
        List<String> base = MathInvariants.vocabulary();
        List<String> extended = MathInvariants.vocabulary(List.of(logPd));
        assertThat(extended).startsWith(base.toArray(new String[0]));
        assertThat(extended).endsWith("log(prime_density)");

        // extract computes the admitted scalar from the flattened base map.
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500, List.of(logPd));
        assertThat(flat).containsKeys("prime_density", "log(prime_density)");
        assertThat(flat.get("log(prime_density)")).isCloseTo(Math.log(0.12), within(1e-9));
    }
}
