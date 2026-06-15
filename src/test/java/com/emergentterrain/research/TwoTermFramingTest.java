package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** G2 Stage 4 — the topic→paper bound rendering handles one- and two-term conjectures. */
class TwoTermFramingTest {

    @Test
    void oneTermBoundBody_unchanged() {
        Conjecture c = new Conjecture("math", "max_prime_gap", "log_sq_window_start", 0.649, -17.77);
        assertThat(ResearchCycleService.formatBoundBody(c))
                .isEqualTo("+0.6490 · log_sq_window_start + -17.7700");
    }

    @Test
    void twoTermBoundBody_rendersBothTerms() {
        Conjecture c = new Conjecture("math", "max_divisor_count", "mean_divisor_excess", 2.0, 5.0);
        c.setRhs2("dirichlet_error");
        c.setK2(1.5);
        assertThat(ResearchCycleService.formatBoundBody(c))
                .isEqualTo("+2.0000 · mean_divisor_excess + +1.5000 · dirichlet_error + +5.0000");
    }
}
