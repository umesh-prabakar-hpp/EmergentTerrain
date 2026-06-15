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

    // ── G4 selection prior ────────────────────────────────────────────────────

    @Test
    void selectionRankPromotesPriorityTargetsOverTheRest() {
        // rank 0 = drawn first: the actively-progressing open / second-order / growth-capture targets.
        for (String t : new String[]{"dirichlet_error", "max_prime_gap", "max_divisor_count",
                "omega_Omega_gap", "omega_ek_ratio", "mean_divisor_excess", "cramer_gap_ratio",
                "twin_vs_prime_sq", "twin_prime_density"}) {
            assertThat(ScalarOpenness.selectionRank(t)).as(t).isZero();
        }
        // rank 1 = default: settled leading orders (flat bounds G1-pruned) and the over-measured
        // Mertens envelope — deliberately NOT promoted.
        assertThat(ScalarOpenness.selectionRank("mertens_delta")).isEqualTo(1);
        assertThat(ScalarOpenness.selectionRank("mertens_per_sqrt_W")).isEqualTo(1);
        assertThat(ScalarOpenness.selectionRank("mean_omega")).isEqualTo(1);
        assertThat(ScalarOpenness.selectionRank("prime_density")).isEqualTo(1);
        assertThat(ScalarOpenness.selectionRank(null)).isEqualTo(1);
    }

    @Test
    void oscillatingTargetIsDirichletErrorOnly() {
        assertThat(ScalarOpenness.isOscillatingTarget("dirichlet_error")).isTrue();
        assertThat(ScalarOpenness.isOscillatingTarget("mertens_delta")).isFalse();   // open but not a priority/FIM-exempt target
        assertThat(ScalarOpenness.isOscillatingTarget("max_prime_gap")).isFalse();
        assertThat(ScalarOpenness.isOscillatingTarget(null)).isFalse();
    }
}
