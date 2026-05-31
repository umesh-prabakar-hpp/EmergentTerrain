package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 frontier-escalation Phase 2 — the power-law cost extrapolator, in
 * isolation (the math). Pure.
 */
class StallPredictorTest {

    @Test
    void fitsLinearCostAndComputesFrontier() {
        // cost = 1e-3 * x  ->  10^7:1e4ms, 10^8:1e5ms, 10^9:1e6ms. budget 1e6 -> x_c=1e9.
        List<CostObservation> obs = List.of(
                new CostObservation(10_000_000L, 10_000L),
                new CostObservation(100_000_000L, 100_000L),
                new CostObservation(1_000_000_000L, 1_000_000L));
        StallPrediction p = StallPredictor.predict(obs, 1_000_000.0, 1.0e12);
        assertThat(p.valid()).isTrue();
        assertThat(p.exponent()).isCloseTo(1.0, within(0.01));
        assertThat(p.coefficient()).isCloseTo(1.0e-3, within(1.0e-4));
        assertThat(p.computeFrontierX()).isCloseTo(1.0e9, within(1.0e7));
        assertThat(p.stallsBeforeScience()).isTrue();          // x_c=1e9 < x_s=1e12
    }

    @Test
    void recoversSublinearExponent() {
        // cost = x^(2/3): 10^6:1e4, 10^9:1e6, 10^12:1e8
        List<CostObservation> obs = List.of(
                new CostObservation(1_000_000L, 10_000L),
                new CostObservation(1_000_000_000L, 1_000_000L),
                new CostObservation(1_000_000_000_000L, 100_000_000L));
        StallPrediction p = StallPredictor.predict(obs, 1.0e9, 0);
        assertThat(p.exponent()).isCloseTo(2.0 / 3.0, within(0.02));
    }

    @Test
    void needsTwoPositivePoints() {
        assertThat(StallPredictor.predict(List.of(), 1000, 0).valid()).isFalse();
        assertThat(StallPredictor.predict(List.of(new CostObservation(10L, 5L)), 1000, 0).valid()).isFalse();
        // zero-elapsed points are filtered out
        assertThat(StallPredictor.predict(List.of(
                new CostObservation(10L, 0L), new CostObservation(100L, 0L)), 1000, 0).valid()).isFalse();
    }

    @Test
    void rejectsNonIncreasingCost() {
        // flat cost -> exponent ~0 -> can't forecast a stall
        List<CostObservation> obs = List.of(
                new CostObservation(10L, 100L),
                new CostObservation(100L, 100L),
                new CostObservation(1000L, 100L));
        assertThat(StallPredictor.predict(obs, 1000, 0).valid()).isFalse();
    }

    @Test
    void scienceFrontierZeroLeavesStallFlagOff() {
        List<CostObservation> obs = List.of(
                new CostObservation(10_000_000L, 10_000L),
                new CostObservation(100_000_000L, 100_000L));
        StallPrediction p = StallPredictor.predict(obs, 1.0e6, 0);
        assertThat(p.valid()).isTrue();
        assertThat(p.stallsBeforeScience()).isFalse();         // x_s unknown
    }
}
