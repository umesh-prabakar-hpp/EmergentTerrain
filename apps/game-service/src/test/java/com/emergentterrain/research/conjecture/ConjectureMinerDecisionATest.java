package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-10 — decision A (discovery→pool write-back, step 2). Unit-tests the pure decision boundary:
 * an investigation-verified bound is asymptotic, so a counterexample BELOW the failure-region floor
 * must not retire it, while a genuine counterexample AT/above the floor still does. Pairs with the
 * runtime sparing wired into {@link ConjectureMinerService#mine()} (covered by the integration tests).
 */
class ConjectureMinerDecisionATest {

    private static Inequality withWitnessAt(long tick) {
        Inequality ineq = new Inequality("cramer_gap_ratio", "deficient_density", 2.0, -1.0478);
        ineq.refutedBy.add(new Witness("W500_low", tick, 0.42));
        return ineq;
    }

    @Test
    void verifiedRow_subRegionCounterexample_isSpared() {
        // the exact mechanism observed 2026-06-09: revived bound refuted at a low-x window.
        assertThat(ConjectureMinerService.spareFromCounterexample(true, 5_000)).isTrue();
    }

    @Test
    void verifiedRow_atFailureRegion_isRetired() {
        // a genuine refutation where the professor actually claimed the bound still retires it.
        assertThat(ConjectureMinerService.spareFromCounterexample(true,
                ConjectureMinerService.FAILURE_REGION_FLOOR)).isFalse();
        assertThat(ConjectureMinerService.spareFromCounterexample(true, 1_800_000)).isFalse();
    }

    @Test
    void unverifiedRow_isNeverSpared_evenSubRegion() {
        assertThat(ConjectureMinerService.spareFromCounterexample(false, 100)).isFalse();
    }

    @Test
    void worstWitnessTick_readsTheLatestWitness() {
        Inequality ineq = withWitnessAt(5_000);
        ineq.refutedBy.add(new Witness("W50k_high", 1_000_000, 0.9));
        assertThat(ConjectureMinerService.worstWitnessTick(ineq)).isEqualTo(1_000_000);
    }

    @Test
    void worstWitnessTick_noWitness_returnsMaxValue_soNeverSpared() {
        Inequality clean = new Inequality("a", "b", 1.0, 0.0);
        assertThat(ConjectureMinerService.worstWitnessTick(clean)).isEqualTo(Long.MAX_VALUE);
        assertThat(ConjectureMinerService.spareFromCounterexample(true,
                ConjectureMinerService.worstWitnessTick(clean))).isFalse();
    }
}
