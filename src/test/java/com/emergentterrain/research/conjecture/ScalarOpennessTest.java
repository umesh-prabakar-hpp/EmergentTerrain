package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 30 G1 — openness-prior demotion predicate (pure). */
class ScalarOpennessTest {

    private static Inequality ineq(String lhs, String rhs, double k) {
        return new Inequality(lhs, rhs, k, 0.0);
    }

    @Test
    void dropsSlopedAffineBoundOnSettledLhsWithFlatFeatureRhs() {
        // the documented trivial-refutation class: mean_omega ≤ k·<flat feature>.
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_omega", "dirichlet_error", 2.0))).isTrue();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_omega", "omega_ek_ratio", 2.0))).isTrue();         // P-598's shape
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_divisor_count", "dirichlet_error", 0.0001))).isTrue();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("prime_density", "happy_density", 1.0))).isTrue();
    }

    @Test
    void keepsGrowthAnchorBoundsOnSettledLhs() {
        // mean_omega ≤ k·log_log_window_start (the ln ln x anchor) is THE result, not a drop.
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_omega", "log_log_window_start", 1.0))).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_divisor_count", "log_window_start", 1.0))).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("mean_Omega", "log_log_window_start", 1.0))).isFalse();
    }

    @Test
    void keepsConstantDensityBoundsAndOpenLhs() {
        // k≈0 constant bound on a density is the real density-limit claim (kept).
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("abundant_density", "deficient_density", 0.0))).isFalse();
        // an OPEN / growing / second-order LHS is never demoted by G1 (it is the target).
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("dirichlet_error", "prime_density", 2.0))).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("max_prime_gap", "prime_density", 2.0))).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("max_divisor_count", "mean_divisor_excess", 2.0))).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(
                ineq("omega_Omega_gap", "prime_density", 2.0))).isFalse();
    }

    @Test
    void classifierAndNullSafety() {
        assertThat(ScalarOpenness.isSettledLeadingOrder("mean_omega")).isTrue();
        assertThat(ScalarOpenness.isSettledLeadingOrder("dirichlet_error")).isFalse();
        assertThat(ScalarOpenness.isSettledLeadingOrder(null)).isFalse();
        assertThat(ScalarOpenness.isTrivialAffineOnSettled(null)).isFalse();
    }
}
