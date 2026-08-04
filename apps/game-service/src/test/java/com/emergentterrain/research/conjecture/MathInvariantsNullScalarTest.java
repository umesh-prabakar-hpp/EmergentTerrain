package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MathInvariantsNullScalarTest {

    @Test
    void nullableBaseScalarUsesTheDeterministicZeroFallback() {
        Map<String, Double> raw = new HashMap<>();
        raw.put("mean_omega", null);
        raw.put("mean_Omega", Double.NaN);

        Map<String, Double> extracted = MathInvariants.extract(raw, 1_000L, 100L);

        assertThat(extracted).containsEntry("mean_omega", 0.0)
                .containsEntry("mean_Omega", 0.0);
    }
}
