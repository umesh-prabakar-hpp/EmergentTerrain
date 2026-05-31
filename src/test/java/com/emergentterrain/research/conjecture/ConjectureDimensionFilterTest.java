package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 1 — unit tests for {@link ConjectureDimensionFilter} (the
 * math realization of the dimensional-consistency gate). Pure; no Spring/DB.
 */
class ConjectureDimensionFilterTest {

    private static Inequality ineq(String lhs, String rhs, double k, double c) {
        return new Inequality(lhs, rhs, k, c);
    }

    @Test
    void densityBoundedByWindowMagnitudeIsFlagged() {
        // real 212-pool entries: abundant_density <= 2*sqrt_window_size + c, etc.
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("abundant_density", "sqrt_window_size", 2.0, -269.89)))
                .contains("density_vs_window_magnitude");
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("prime_density", "log_window_start", 2.0, 0.1)))
                .contains("density_vs_window_magnitude");
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("happy_density", "log_window_size", 2.0, -7.6))).isPresent();
    }

    @Test
    void densityVsDensityIsNotFlagged() {
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("abundant_density", "prime_density", 2.0, 0.09))).isEmpty();
    }

    @Test
    void mertensBoundIsNotADensityMismatch() {
        // mertens_per_sqrt_W is not a density — the flagship bound stays out.
        assertThat(ConjectureDimensionFilter.isDimensionalMismatch(
                ineq("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621))).isFalse();
    }

    @Test
    void reverseDirectionIsNotFlagged() {
        // window_magnitude <= k*density is eventually FALSE -> refutation path, not vacuous.
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("log_window_start", "prime_density", 2.0, 0.0))).isEmpty();
    }

    @Test
    void zeroOrNegativeKIsNotFlagged() {
        // k=0 constant bound is deduped by the triviality filter, not a dimensional mismatch.
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("abundant_density", "log_window_size", 0.0, 0.25))).isEmpty();
        // k<0 with a growing RHS does not make a density bound eventually vacuous.
        assertThat(ConjectureDimensionFilter.dropReason(
                ineq("abundant_density", "log_window_size", -2.0, 0.25))).isEmpty();
    }

    @Test
    void nullIsSafe() {
        assertThat(ConjectureDimensionFilter.dropReason(null)).isEmpty();
        assertThat(ConjectureDimensionFilter.isDimensionalMismatch(null)).isFalse();
    }
}
