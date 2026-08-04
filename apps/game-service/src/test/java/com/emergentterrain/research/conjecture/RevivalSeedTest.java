package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Run-36 rec 1 — family-revival seeds: extinction backstop only. */
class RevivalSeedTest {

    @Test
    void revivalFamilies_injectedOnlyWhenExtinct_andOnlyWithFrames() {
        // Extinct + framed -> seeded.
        assertThat(ConjectureMinerService.seedTargets(
                List.of("max_prime_gap", "twin_vs_prime_sq"), Set.of()))
                .contains("max_prime_gap", "twin_vs_prime_sq");
        // Servable family -> NOT seeded (never competes with organic rows).
        assertThat(ConjectureMinerService.seedTargets(
                List.of("max_prime_gap"), Set.of("max_prime_gap")))
                .doesNotContain("max_prime_gap");
        // Frameless family -> NOT seeded (nothing to frame the investigation with).
        assertThat(ConjectureMinerService.seedTargets(
                List.of("no_such_scalar"), Set.of()))
                .doesNotContain("no_such_scalar");
        // Oscillating targets ride along unchanged; empty/null revival list is safe.
        assertThat(ConjectureMinerService.seedTargets(null, Set.of()))
                .containsAll(ScalarOpenness.OSCILLATING_TARGETS.stream()
                        .filter(l -> ScalarOpenness.measurementFrame(l) != null).toList());
    }
}
