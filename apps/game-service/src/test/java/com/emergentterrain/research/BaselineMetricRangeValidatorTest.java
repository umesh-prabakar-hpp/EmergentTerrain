package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-21 review §1 rec #4 — unit tests for {@link
 * BaselineMetricRangeValidator}. The canonical corpus signature is
 * P-a328-871: baseline=0.300 paired with metric=mean_of:sigmaSum.
 */
class BaselineMetricRangeValidatorTest {

    @Test
    void p871SignatureFlags() {
        // mean_of:sigmaSum returns ~30–100 magnitudes on [45,65); a 0.300
        // baseline is probability-shaped. → flag.
        Optional<String> err = BaselineMetricRangeValidator.validate(
                "mean_of:sigmaSum", 0.300);
        assertThat(err).isPresent();
        assertThat(err.get())
                .contains("implausible")
                .contains("mean_of:sigmaSum")
                .contains("magnitude");
    }

    @Test
    void maxOfMetricWithProbabilityShapedBaselineFlags() {
        Optional<String> err = BaselineMetricRangeValidator.validate(
                "max_of:collatzSteps", 0.500);
        assertThat(err).isPresent();
        assertThat(err.get()).contains("max_of:collatzSteps");
    }

    @Test
    void magnitudeMetricWithMagnitudeBaselinePasses() {
        // 79 is the real mean σ over [45,65) per the P-871 tool result.
        // Plausible baseline, no flag.
        assertThat(BaselineMetricRangeValidator.validate("mean_of:sigmaSum", 79.0))
                .isEmpty();
    }

    @Test
    void magnitudeMetricWithZeroBaselineIsAllowed() {
        // baseline=0 is the "no expectation" sentinel; intentionally allowed
        // against magnitude metrics so callers can opt out of pre-registration.
        assertThat(BaselineMetricRangeValidator.validate("mean_of:sigmaSum", 0.0))
                .isEmpty();
        assertThat(BaselineMetricRangeValidator.validate("max_of:collatzSteps", 0.0))
                .isEmpty();
    }

    @Test
    void magnitudeMetricWithUnitBaselineIsAllowed() {
        // Exactly 1.0 is the boundary; the (0,1) open interval is suspicious
        // but [1, ∞) is the natural magnitude range. Allow.
        assertThat(BaselineMetricRangeValidator.validate("mean_of:sigmaSum", 1.0))
                .isEmpty();
    }

    @Test
    void fractionSatisfyingWithProbabilityBaselinePasses() {
        assertThat(BaselineMetricRangeValidator.validate("fraction_satisfying", 0.300))
                .isEmpty();
        assertThat(BaselineMetricRangeValidator.validate("fraction_satisfying", 0.0))
                .isEmpty();
        assertThat(BaselineMetricRangeValidator.validate("fraction_satisfying", 1.0))
                .isEmpty();
    }

    @Test
    void fractionSatisfyingWithMagnitudeBaselineFlags() {
        // A 79 baseline against fraction_satisfying is implausible — frac
        // returns [0,1]. → flag (the inverse of P-871's confusion).
        Optional<String> err = BaselineMetricRangeValidator.validate(
                "fraction_satisfying", 79.0);
        assertThat(err).isPresent();
        assertThat(err.get())
                .contains("fraction_satisfying")
                .contains("probability");
    }

    @Test
    void fractionSatisfyingWithNegativeBaselineFlags() {
        assertThat(BaselineMetricRangeValidator.validate("fraction_satisfying", -0.1))
                .isPresent();
    }

    @Test
    void nullMetricReturnsEmpty() {
        // Defensive: upstream VALID_METRICS check has already rejected this.
        assertThat(BaselineMetricRangeValidator.validate(null, 0.5)).isEmpty();
    }

    @Test
    void unrecognisedMetricReturnsEmpty() {
        // Validator only opines on the metric prefixes it knows; an unknown
        // metric is the upstream VALID_METRICS check's responsibility.
        assertThat(BaselineMetricRangeValidator.validate("mode_of:something", 0.5))
                .isEmpty();
    }
}
