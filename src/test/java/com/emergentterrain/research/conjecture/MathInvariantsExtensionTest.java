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
