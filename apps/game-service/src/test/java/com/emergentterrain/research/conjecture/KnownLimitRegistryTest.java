package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnownLimitRegistryTest {

    @Test
    void localMeanDivisorExcessLocksTwoEulerGammaRatherThanCumulativeTarget() {
        assertThat(KnownLimitRegistry.targetValidationError("mean_divisor_excess", 1.15443)).isNull();

        String error = KnownLimitRegistry.targetValidationError("mean_divisor_excess", 2.0 * 0.5772156649015329 - 1.0);
        assertThat(error).contains("locked target=")
                .contains("cumulative observable")
                .contains("2*gamma-1");
    }

    @Test
    void omittedTargetRemainsAnExploratoryEstimate() {
        assertThat(KnownLimitRegistry.targetValidationError("mean_divisor_excess", null)).isNull();
    }
}
