package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Score-calibration helpers (room b72c4e10 fix, Rec 2). Pure — no Spring.
 */
class ScoreCalibrationTest {

    private static ToolInvocation verify(String argsJson, String resultJson) {
        return new ToolInvocation("verify_conjecture", argsJson, resultJson, 0, "call_1");
    }

    private static ToolInvocation other(String name) {
        return new ToolInvocation(name, "{}", "ok", 0, "call_1");
    }

    // ---- cascadeQuality ----

    @Test
    void cascadeQualityIsMeanOfAxesPlusAgreementTiebreak() {
        // Matches ResearchNarrationService.scoreCandidate's panel branch.
        double q = ScoreCalibration.cascadeQuality(0.90, 0.85, 0.73, 0.40, 0.94);
        assertEquals((0.90 + 0.85 + 0.73 + 0.40) / 4.0 + 0.001 * 0.94, q, 1e-12);
    }

    @Test
    void cascadeQualityRanksP15LastAmongTheMertensPapers() {
        // The real medians from REVIEW_REPORT.md: P-15 must rank below P-13/P-14,
        // inverting the confidence-weighted headline that put it first.
        double p13 = ScoreCalibration.cascadeQuality(0.90, 0.85, 0.73, 0.40, 0.94);
        double p14 = ScoreCalibration.cascadeQuality(0.90, 0.78, 0.69, 0.23, 0.96);
        double p15 = ScoreCalibration.cascadeQuality(0.79, 0.63, 0.41, 0.22, 0.93);
        assertTrue(p15 < p14, "P-15 should rank below P-14");
        assertTrue(p14 < p13, "P-14 should rank below P-13");
    }

    // ---- usesConjectureVerification ----

    @Test
    void usesConjectureVerificationDetectsTheCall() {
        assertTrue(ScoreCalibration.usesConjectureVerification(
                List.of(verify("{\"window_start\":200003}", "verify_conjecture: ... HELD"))));
    }

    @Test
    void usesConjectureVerificationFalseForNonConjectureOrEmptyTrace() {
        assertFalse(ScoreCalibration.usesConjectureVerification(List.of(other("run_param_sweep"))));
        assertFalse(ScoreCalibration.usesConjectureVerification(List.of()));
        assertFalse(ScoreCalibration.usesConjectureVerification(null));
    }

    // ---- hasAdversarialCoverage ----

    @Test
    void refutationCountsAsAdversarial() {
        assertTrue(ScoreCalibration.hasAdversarialCoverage(
                List.of(verify("{\"window_start\":2003}", "verify_conjecture: ... REFUTED ..."))));
    }

    @Test
    void bindingRegimeProbeCountsAsAdversarialEvenWhenHeld() {
        // A small-start window that HELD still demonstrates the author looked.
        assertTrue(ScoreCalibration.hasAdversarialCoverage(
                List.of(verify("{\"lhs\":\"x\",\"window_start\":2003,\"window_size\":500}",
                        "verify_conjecture: ... HELD ..."))));
    }

    @Test
    void confirmatoryOnlyLargeStartIsNotAdversarial() {
        // The P-b72c-15 signature: every verify_conjecture HELD at a large start.
        List<ToolInvocation> trace = List.of(
                verify("{\"window_start\":200003,\"window_size\":500}", "verify_conjecture: ... HELD ..."),
                verify("{\"window_start\":750000,\"window_size\":2000}", "verify_conjecture: ... HELD ..."));
        assertFalse(ScoreCalibration.hasAdversarialCoverage(trace));
        assertTrue(ScoreCalibration.isConfirmatoryOnlyConjecture(trace),
                "all-HELD large-start conjecture trace is confirmatory-only");
    }

    @Test
    void nonConjecturePaperIsNotConfirmatoryOnly() {
        // No verify_conjecture call at all → not a conjecture paper → gate must
        // not bite it.
        List<ToolInvocation> trace = List.of(other("run_param_sweep"), other("run_symbolic"));
        assertFalse(ScoreCalibration.isConfirmatoryOnlyConjecture(trace));
    }

    // ---- probedBindingRegime (arg parsing) ----

    @Test
    void probedBindingRegimeParsesWindowStart() {
        assertTrue(ScoreCalibration.probedBindingRegime("{\"window_start\":2003,\"k\":2}"));
        assertTrue(ScoreCalibration.probedBindingRegime("{\"window_start\": 10000}"));
        assertFalse(ScoreCalibration.probedBindingRegime("{\"window_start\":200003}"));
        assertFalse(ScoreCalibration.probedBindingRegime("{\"window_start\":10001}"));
        assertFalse(ScoreCalibration.probedBindingRegime("{\"k\":2,\"c\":-7.7}"));
        assertFalse(ScoreCalibration.probedBindingRegime(null));
    }

    // ---- hypothesisLhsUncovered (2026-06-06 review rec #1) ----

    @Test
    void p210_lhsSwappedForAnotherScalar_uncovered() {
        // P-b72c-210: topic conjecture/twin_vs_prime_sq, every call lhs=prime_density.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"prime_density\",\"rhs\":\"prime_density\",\"k\":2,\"c\":1.14}",
                        "HELD"),
                verify("{\"lhs\":\"prime_density\",\"rhs\":\"prime_density\",\"k\":2,\"c\":1.14}",
                        "HELD"));
        assertTrue(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/twin_vs_prime_sq", trace));
    }

    @Test
    void p215_pairNeverInvoked_uncovered() {
        // P-b72c-215: topic conjecture/cramer_gap_ratio; only off-target verify
        // calls and a scalar_window_stats call — the preregistered LHS never ran.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"omega_Omega_gap\",\"rhs\":\"omega_Omega_gap\",\"k\":1,\"c\":0}",
                        "HELD"),
                other("scalar_window_stats"),
                verify("{\"lhs\":\"omega_Omega_gap\",\"rhs\":\"mean_omega\",\"k\":2,\"c\":-1.0898}",
                        "HELD"));
        assertTrue(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/cramer_gap_ratio", trace));
    }

    @Test
    void p214_lhsExercised_covered() {
        // P-b72c-214 shape: the hypothesis LHS appears in at least one call.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"cramer_gap_ratio\",\"rhs\":\"mertens_per_sqrt_W\",\"k\":0,\"c\":0.4522}",
                        "HELD"));
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/cramer_gap_ratio", trace));
    }

    @Test
    void lhsCoverageToleratesArgWhitespace() {
        List<ToolInvocation> trace = List.of(
                verify("{ \"lhs\" : \"cramer_gap_ratio\", \"k\":0 }", "HELD"));
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/cramer_gap_ratio", trace));
    }

    @Test
    void nonConjectureTopicsAndEmptyTraces_notGated() {
        // Non-conjecture topic: never gated, whatever the trace.
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(
                "isAbundant AND isHappy Density",
                List.of(other("run_predicate_scan"))));
        // Empty/null trace: missing-methods gate owns that case.
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/cramer_gap_ratio", List.of()));
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(
                "conjecture/cramer_gap_ratio", null));
        assertFalse(ScoreCalibration.hypothesisLhsUncovered(null, List.of(other("x"))));
    }
}
