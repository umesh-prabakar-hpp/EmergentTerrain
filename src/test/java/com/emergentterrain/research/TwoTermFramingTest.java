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

    // rec #2 — a write-back-captured bound steers the investigation to its capture (failure) region.

    @Test
    void captureWindowSteersInvestigationToFailureRegion() {
        Conjecture c = new Conjecture("math", "max_divisor_count", "wigert_bound", 2.0, -164.1926);
        c.setCaptureWindowStart(1_200_000L);
        String hyp = ResearchCycleService.synthesizeConjectureHypothesis(c);
        assertThat(hyp).contains("verified at the failure region x≈1200000")
                .contains("test held-out windows AT OR ABOVE that region");
    }

    @Test
    void noCaptureWindow_noFailureRegionNote() {
        Conjecture c = new Conjecture("math", "max_prime_gap", "log_sq_window_start", 0.649, -17.77);
        assertThat(ResearchCycleService.synthesizeConjectureHypothesis(c))
                .doesNotContain("failure region");
    }
}
