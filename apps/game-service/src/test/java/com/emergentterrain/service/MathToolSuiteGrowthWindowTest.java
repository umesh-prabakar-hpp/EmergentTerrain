package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MathToolSuiteGrowthWindowTest {

    @Test
    void nullableVerifierStartUsesTheRequestedWindowFallback() {
        assertThat(MathToolSuite.growthWindowStart(Map.of("mean_omega", 2.0), 1_000L,
                "scale-ladder rung=1000", 0)).isEqualTo(1_000L);
    }

    @Test
    void missingGrowthScalarReportsTheExactWindowContext() {
        assertThatThrownBy(() -> MathToolSuite.growthWindowValue(Map.of("_window_start", 1_000.0),
                "mean_omega", "scale-ladder rung=1000", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mean_omega")
                .hasMessageContaining("rung=1000")
                .hasMessageContaining("index=2");
    }
}
