package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 1 — unit tests for {@link ConjectureUnitConsistencyFilter}.
 */
class ConjectureUnitConsistencyFilterTest {

    private static Inequality ineq(String lhs, String rhs, double k, double c) {
        return new Inequality(lhs, rhs, k, c);
    }

    @Test
    void densityBoundedByWindowMagnitudeIsFlagged() {
        // subsumes the dimension filter's case
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("prime_density", "log_window_size", 2.0, -1.0)))
                .contains("bounded_nt_vs_window_magnitude");
    }

    @Test
    void boundedDerivedScalarVsWindowMagnitudeIsFlagged() {
        // the generalisation beyond densities: omega_Omega_gap and mertens_per_sqrt_W are O(1)
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("omega_Omega_gap", "sqrt_window_size", 1.5, 0.0))).isPresent();
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("mertens_per_sqrt_W", "log_window_start", 0.5, 0.0))).isPresent();
    }

    @Test
    void reverseDirectionNotFlagged() {
        // window_magnitude ≤ k·bounded is eventually false — refutation handles it, not this gate.
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("log_window_size", "prime_density", 2.0, 0.0))).isEmpty();
    }

    @Test
    void negativeOrZeroKNotFlagged() {
        // only the eventually-vacuous k>0 forward direction is in scope.
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("prime_density", "log_window_size", 0.0, 0.2))).isEmpty();
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("prime_density", "log_window_size", -1.0, 0.2))).isEmpty();
    }

    @Test
    void twoNumberTheoreticScalarsNotFlagged() {
        // both sides number-theoretic → no unit mismatch (this gate only catches NT-vs-window).
        assertThat(ConjectureUnitConsistencyFilter.dropReason(
                ineq("mean_omega", "mean_Omega", 1.0, 0.0))).isEmpty();
    }

    @Test
    void nullIsSafe() {
        assertThat(ConjectureUnitConsistencyFilter.dropReason(null)).isEmpty();
    }
}
