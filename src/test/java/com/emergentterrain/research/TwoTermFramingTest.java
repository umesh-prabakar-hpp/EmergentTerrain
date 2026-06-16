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
        String hyp = ResearchCycleService.synthesizeConjectureHypothesis(c, false);
        assertThat(hyp).contains("verified at the failure region x≈1200000")
                .contains("test held-out windows AT OR ABOVE that region");
    }

    @Test
    void noCaptureWindow_noFailureRegionNote() {
        Conjecture c = new Conjecture("math", "max_prime_gap", "log_sq_window_start", 0.649, -17.77);
        assertThat(ResearchCycleService.synthesizeConjectureHypothesis(c, false))
                .doesNotContain("failure region");
    }

    // measurement-frame-default.md Stage 1 — the claim-type lever framing.

    @Test
    void measurementFrame_reframesRegistryObjectAsMeasurement_whenOn() {
        // omega_Omega_gap has a CONSTANT frame (→ 0.77316). With the flag ON it is framed as a
        // measurement (verify_constant), NOT the default "investigates whether the bound holds".
        Conjecture c = new Conjecture("math", "omega_Omega_gap", "log_window_start", 2.0, -5.70);
        String hyp = ResearchCycleService.synthesizeConjectureHypothesis(c, true);
        assertThat(hyp).contains("CONVERGES to a constant").contains("verify_constant")
                .contains("0.77316").doesNotContain("whether the bound holds");
    }

    @Test
    void measurementFrame_oscillationForDirichlet_whenOn() {
        Conjecture c = new Conjecture("math", "dirichlet_error", "mertens_per_sqrt_W", 1.5, 1.7832);
        assertThat(ResearchCycleService.synthesizeConjectureHypothesis(c, true))
                .contains("OSCILLATES").contains("verify_oscillation").contains("NO scale_rhs");
    }

    @Test
    void measurementFrame_off_keepsBoundFraming() {
        Conjecture c = new Conjecture("math", "omega_Omega_gap", "log_window_start", 2.0, -5.70);
        assertThat(ResearchCycleService.synthesizeConjectureHypothesis(c, false))
                .contains("whether the bound holds").doesNotContain("verify_constant");
    }

    @Test
    void measurementFrame_nonRegistryObject_keepsBoundFramingEvenWhenOn() {
        // A non-registry object (e.g. abundant_density) is unaffected by the flag.
        Conjecture c = new Conjecture("math", "abundant_density", "prime_density", 2.0, 0.09);
        assertThat(ResearchCycleService.synthesizeConjectureHypothesis(c, true))
                .contains("whether the bound holds");
    }
}
