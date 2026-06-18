package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The verdict legend appended to EVERY verify_conjecture result (held or refuted). It contains the
     * word "REFUTED" ("…only margin > 0.04 is REFUTED…"), so a detector keying on bare contains("REFUTED")
     * matches it on every call. Fixtures include it so the tests are realistic — the bug that shipped was
     * invisible precisely because the old fixtures omitted it.
     */
    private static final String LEGEND =
            " verdict legend: margin = lhs - bound; a window HOLDS iff margin <= 0.04 (2*TOL) — a small "
            + "positive margin still holds, only margin > 0.04 is REFUTED. The per-window holds/REFUTED "
            + "label is authoritative; do not re-derive or hedge the sign convention.";

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
                List.of(verify("{\"window_start\":2003}",
                        "verify_conjecture: x <= ... REFUTED across 20 window(s)." + LEGEND))));
    }

    @Test
    void bindingRegimeProbeCountsAsAdversarialEvenWhenHeld() {
        // A small-start window that HELD still demonstrates the author looked. The held result carries the
        // legend (with its "is REFUTED") — coverage here must come from the binding-regime probe, not the
        // legend masquerading as a refutation.
        assertTrue(ScoreCalibration.hasAdversarialCoverage(
                List.of(verify("{\"lhs\":\"x\",\"window_start\":2003,\"window_size\":500}",
                        "verify_conjecture: x <= ... HELD across 20 window(s)." + LEGEND))));
    }

    @Test
    void confirmatoryOnlyLargeStartIsNotAdversarial() {
        // The P-b72c-15 / P-b72c-77627715 signature: every verify_conjecture HELD at a large start. The
        // results carry the legend; with the old bare contains("REFUTED") this trace read as adversarial
        // (legend match) and the gate never fired — this is the regression the headline-keyed fix restores.
        List<ToolInvocation> trace = List.of(
                verify("{\"window_start\":200003,\"window_size\":500}",
                        "verify_conjecture: x <= ... HELD across 20 window(s)." + LEGEND),
                verify("{\"window_start\":750000,\"window_size\":2000}",
                        "verify_conjecture: x <= ... HELD across 20 window(s)." + LEGEND));
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

    // ── 2026-06-13 — second-order ENGAGEMENT gate ─────────────────────────────

    @Test
    void leadingOrderTopicWithOnlyBoundFrame_isSkipped() {
        // mean_omega has a textbook leading order (~ ln ln x); a trace that only
        // bounds it never reaches the second-order structure → engagement skipped.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"mean_omega\",\"rhs\":\"log_log_center\",\"k\":1}", "REFUTED"),
                verify("{\"lhs\":\"mean_omega\",\"rhs\":\"log_log_center\",\"k\":2}", "HELD"));
        assertTrue(ScoreCalibration.secondOrderEngagementSkipped("conjecture/mean_omega", trace));
    }

    @Test
    void leadingOrderTopicThatEngagesASecondOrderFrame_notSkipped() {
        // Any second-order frame (here verify_constant) counts as engagement — the
        // gate does NOT inspect which scalar it was pointed at (the model's choice).
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"mean_omega\",\"rhs\":\"log_log_center\",\"k\":1}", "REFUTED"),
                other("verify_constant"));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped("conjecture/mean_omega", trace));
        // verify_oscillation and verify_growth likewise engage.
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(
                "conjecture/mean_divisor_count", List.of(other("verify_oscillation"))));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(
                "conjecture/max_divisor_count", List.of(other("verify_growth"))));
    }

    @Test
    void nonLeadingOrderTopic_neverSkipped() {
        // cramer_gap_ratio is itself a second-order ratio — there is no textbook
        // leading order to go past, so the gate never fires even with a bound-only trace.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"cramer_gap_ratio\",\"k\":0,\"c\":0.45}", "HELD"));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(
                "conjecture/cramer_gap_ratio", trace));
    }

    @Test
    void secondOrderScalarsEngaged_extractsDistinctFrameLhsInOrder() {
        // The model's chosen objects: the lhs of each second-order-frame call.
        List<ToolInvocation> trace = List.of(
                verify("{\"lhs\":\"mean_omega\",\"rhs\":\"log_log_center\",\"k\":1}", "REFUTED"),
                new ToolInvocation("verify_constant",
                        "{\"lhs\":\"omega_ek_ratio\",\"target\":1.0}", "CONVERGED", 0, "c1"),
                new ToolInvocation("verify_constant",
                        "{ \"lhs\" : \"omega_ek_ratio\" , \"target\":1.0}", "CONVERGED", 0, "c2"),
                new ToolInvocation("verify_growth",
                        "{\"lhs\":\"mean_divisor_excess\"}", "approaching", 0, "c3"));
        // distinct, first-seen order; bound frame (verify_conjecture) ignored; whitespace tolerant.
        assertEquals(List.of("omega_ek_ratio", "mean_divisor_excess"),
                ScoreCalibration.secondOrderScalarsEngaged(trace));
    }

    @Test
    void secondOrderScalarsEngaged_emptyWhenNoFrameOrNoTrace() {
        assertEquals(List.of(), ScoreCalibration.secondOrderScalarsEngaged(
                List.of(verify("{\"lhs\":\"mean_omega\"}", "HELD"))));
        assertEquals(List.of(), ScoreCalibration.secondOrderScalarsEngaged(List.of()));
        assertEquals(List.of(), ScoreCalibration.secondOrderScalarsEngaged(null));
    }

    // ── 2026-06-14 — tight-k re-run gate (looseGrowthAnchorUntightened) ───────

    private static final String LOOSE_HELD =
            "verify_conjecture: max_prime_gap <= +1.0000 * log_sq_window_start + +0.0000 HELD across 20 "
            + "window(s). worst margin: -100.91487. relative slack: 0.5125. LOOSE: the tightest k that "
            + "holds these windows is ~0.4875 — re-verify at k=0.4880 (keep c=+0.0000) for a TIGHT bound.";
    private static final String TIGHT_HELD =
            "verify_conjecture: max_prime_gap <= +0.4880 * log_sq_window_start + +0.0000 HELD across 20 "
            + "window(s). worst margin: -2.10000. relative slack: 0.0110.";   // no re-verify suggestion

    @Test
    void looseGrowthAnchor_firesWhenHeldLooseAndNotTightened() {
        var s = ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_prime_gap", List.of(
                verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":1,\"c\":0}",
                        LOOSE_HELD)));
        assertNotNull(s);
        assertEquals("0.4880", s.suggestedK());
        assertEquals("log_sq_window_start", s.rhs());
        assertEquals("0", s.c());
    }

    @Test
    void looseGrowthAnchor_capturesFailureRegionWindowsFromRefutedFlatBound() {
        // The flat bound REFUTED at 300000 (the failure region); the growth anchor held loose at 1.2M.
        // The tighten must target the failure-region windows, not the benign ones.
        var s = ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_prime_gap", List.of(
                verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_window_size\",\"k\":2,\"c\":53.6,"
                        + "\"window_start\":300000,\"window_size\":1000,\"num_windows\":50}",
                        "verify_conjecture: ... REFUTED across 50 window(s) starting at 300000"),
                verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":1,\"c\":0,"
                        + "\"window_start\":1200000,\"window_size\":5000,\"num_windows\":20}", LOOSE_HELD)));
        assertNotNull(s);
        assertEquals("0.4880", s.suggestedK());
        assertEquals("300000", s.failStart());   // the REFUTED (failure) region, not 1.2M
        assertEquals("1000", s.failSize());
        assertEquals("50", s.failNum());
    }

    @Test
    void looseGrowthAnchor_nullWhenSubsequentTightHold() {
        // The professor DID re-verify tight (a HELD with no re-verify suggestion) → nothing to enforce.
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_prime_gap", List.of(
                verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":1,\"c\":0}",
                        LOOSE_HELD),
                verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":0.488,\"c\":0}",
                        TIGHT_HELD))));
    }

    @Test
    void looseGrowthAnchor_nullForRefutedNonConjectureAndEmpty() {
        // A REFUTED trace has no HELD summary → null.
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_prime_gap", List.of(
                verify("{\"lhs\":\"max_prime_gap\"}", "verify_conjecture: ... REFUTED across 50 window(s)"))));
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened("isAbundant Density", List.of(
                verify("{}", LOOSE_HELD))));
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_prime_gap", List.of()));
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened(null, null));
    }

    @Test
    void secondOrderGate_nonConjectureAndEmptyTraces_notGated() {
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(
                "isAbundant AND isHappy Density", List.of(other("run_predicate_scan"))));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(
                "conjecture/mean_omega", List.of()));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped("conjecture/mean_omega", null));
        assertFalse(ScoreCalibration.secondOrderEngagementSkipped(null, List.of(other("x"))));
    }

    // ---- isConstantOrOscillationFramed (review-2026-06-15e Rec 1) ----

    @Test
    void constantOrOscillationFramedDetectsTheMeasurementFrames() {
        assertTrue(ScoreCalibration.isConstantOrOscillationFramed(List.of(other("verify_constant"))));
        assertTrue(ScoreCalibration.isConstantOrOscillationFramed(List.of(other("verify_oscillation"))));
        assertTrue(ScoreCalibration.isConstantOrOscillationFramed(
                List.of(verify("{}", "REFUTED"), other("verify_constant"))));   // mixed → still framed
        // a pure bound paper is NOT exempt
        assertFalse(ScoreCalibration.isConstantOrOscillationFramed(List.of(verify("{}", "HELD"))));
        assertFalse(ScoreCalibration.isConstantOrOscillationFramed(List.of(other("verify_growth"))));
        assertFalse(ScoreCalibration.isConstantOrOscillationFramed(null));
    }

    // ── Phase 30 G2 — two-term intercept-tighten gate (looseTwoTermUntightened) ──

    private static final String TWO_TERM_LOOSE_HELD =
            "verify_conjecture: max_divisor_count <= +2.0000 * wigert_bound + +2.0000 * omega_Omega_gap + "
            + "-32.4768 HELD across 15 window(s). worst margin: -24.50000. relative slack: 0.5000. "
            + "LOOSE (two-term): holds with this much slack — the tightest intercept keeping k and k2 is "
            + "c ≈ -56.9768. Re-verify at c=-56.9768 (keep k=+2.0000, rhs2=omega_Omega_gap, k2=+2.0000) "
            + "AND confirm a slightly smaller c REFUTES, to demonstrate the binding regime.";
    private static final String TWO_TERM_ARGS =
            "{\"lhs\":\"max_divisor_count\",\"rhs\":\"wigert_bound\",\"k\":2,"
            + "\"rhs2\":\"omega_Omega_gap\",\"k2\":2,\"c\":-32.4768}";
    private static final String TWO_TERM_TIGHT_HELD =
            "verify_conjecture: max_divisor_count <= +2.0000 * wigert_bound + +2.0000 * omega_Omega_gap + "
            + "-56.9768 HELD across 15 window(s). worst margin: -0.40000. relative slack: 0.0080.";

    @Test
    void looseTwoTerm_firesWhenHeldLooseAndNotTightened() {
        var s = ScoreCalibration.looseTwoTermUntightened("conjecture/max_divisor_count",
                List.of(verify(TWO_TERM_ARGS, TWO_TERM_LOOSE_HELD)));
        assertNotNull(s);
        assertEquals("-56.9768", s.suggestedC());
        assertEquals("wigert_bound", s.rhs());
        assertEquals("omega_Omega_gap", s.rhs2());
        assertEquals("2", s.k());
        assertEquals("2", s.k2());
    }

    @Test
    void looseTwoTerm_nullWhenSubsequentTightTwoTermHold() {
        // The professor DID re-verify at the tight c (a two-term HELD with no LOOSE suggestion).
        assertNull(ScoreCalibration.looseTwoTermUntightened("conjecture/max_divisor_count", List.of(
                verify(TWO_TERM_ARGS, TWO_TERM_LOOSE_HELD),
                verify(TWO_TERM_ARGS, TWO_TERM_TIGHT_HELD))));
    }

    @Test
    void looseTwoTerm_capturesFailureRegionFromRefutedCall() {
        var s = ScoreCalibration.looseTwoTermUntightened("conjecture/max_divisor_count", List.of(
                verify("{\"lhs\":\"max_divisor_count\",\"rhs\":\"wigert_bound\",\"k\":2,"
                        + "\"rhs2\":\"omega_Omega_gap\",\"k2\":2,\"c\":-60,"
                        + "\"window_start\":300000,\"window_size\":1000,\"num_windows\":15}",
                        "verify_conjecture: ... REFUTED across 15 window(s) starting at 300000"),
                verify(TWO_TERM_ARGS, TWO_TERM_LOOSE_HELD)));
        assertNotNull(s);
        assertEquals("-56.9768", s.suggestedC());
        assertEquals("300000", s.failStart());
        assertEquals("1000", s.failSize());
        assertEquals("15", s.failNum());
    }

    @Test
    void looseTwoTerm_ignoresOneTermLooseHeld_noCrossTrigger() {
        // A one-term loose hold (SUGGEST_K, no rhs2) must NOT trip the two-term gate…
        assertNull(ScoreCalibration.looseTwoTermUntightened("conjecture/max_prime_gap",
                List.of(verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":1,\"c\":0}",
                        LOOSE_HELD))));
        // …and the two-term loose hold (SUGGEST_C) must NOT trip the one-term gate.
        assertNull(ScoreCalibration.looseGrowthAnchorUntightened("conjecture/max_divisor_count",
                List.of(verify(TWO_TERM_ARGS, TWO_TERM_LOOSE_HELD))));
    }

    @Test
    void looseTwoTerm_nullOnNonConjectureOrEmpty() {
        assertNull(ScoreCalibration.looseTwoTermUntightened("physics/foo",
                List.of(verify(TWO_TERM_ARGS, TWO_TERM_LOOSE_HELD))));
        assertNull(ScoreCalibration.looseTwoTermUntightened("conjecture/max_divisor_count", List.of()));
        assertNull(ScoreCalibration.looseTwoTermUntightened("conjecture/max_divisor_count", null));
    }

    // ── 2026-06-18 — sharpness-probe gate (heldTwoTermNoBindingRegion) ──────────

    // P-b72c-77627715 shape: a two-term composite that holds TIGHTLY (small slack, no LOOSE suggestion)
    // but never refutes anywhere — the binding region is never shown.
    private static final String RDD_ARGS =
            "{\"lhs\":\"richly_divisible_density\",\"rhs\":\"mean_divisor_excess\",\"k\":2,"
            + "\"rhs2\":\"omega_Omega_gap\",\"k2\":2,\"c\":-3.3452,"
            + "\"window_start\":150000,\"window_size\":5000,\"num_windows\":20}";
    private static final String RDD_TIGHT_HELD =
            "verify_conjecture: richly_divisible_density <= +2.0000 * mean_divisor_excess + +2.0000 * "
            + "omega_Omega_gap + -3.3452 HELD across 20 window(s) of size 5000 starting at 150000. "
            + "worst margin: -0.00893 at window starting 185000. relative slack: 0.0178." + LEGEND;

    @Test
    void sharpnessProbe_firesOnTightTwoTermHoldWithNoRefute() {
        var s = ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/richly_divisible_density",
                List.of(verify(RDD_ARGS, RDD_TIGHT_HELD)));
        assertNotNull(s);
        // tightest c = held c (-3.3452) + worst margin (-0.00893) = -3.35413 → -3.3541
        assertEquals("-3.3541", s.suggestedC());
        assertEquals("mean_divisor_excess", s.rhs());
        assertEquals("omega_Omega_gap", s.rhs2());
        assertEquals("2", s.k());
        assertEquals("2", s.k2());
        assertEquals("150000", s.heldStart());
    }

    @Test
    void sharpnessProbe_nullWhenARefuteAlreadyShowsTheBindingRegion() {
        // A real refutation (headline "REFUTED across") means the binding region IS shown → no probe.
        assertNull(ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/richly_divisible_density",
                List.of(verify(RDD_ARGS, "verify_conjecture: richly_divisible_density <= ... REFUTED across "
                                + "20 window(s) of size 5000 starting at 150000. worst margin: +0.05." + LEGEND),
                        verify(RDD_ARGS, RDD_TIGHT_HELD))));
    }

    @Test
    void sharpnessProbe_legendAloneIsNotARefute() {
        // Regression twin: the held result's legend contains "is REFUTED" but NOT "REFUTED across", so the
        // probe must still fire (the legend must not be read as a binding region).
        assertNotNull(ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/richly_divisible_density",
                List.of(verify(RDD_ARGS, RDD_TIGHT_HELD))));
    }

    @Test
    void sharpnessProbe_nullForOneTermHold() {
        // A one-term hold (no rhs2) never trips the two-term sharpness probe.
        assertNull(ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/max_prime_gap",
                List.of(verify("{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\",\"k\":1,\"c\":0}",
                        TIGHT_HELD))));
    }

    @Test
    void sharpnessProbe_nullOnNonConjectureOrEmpty() {
        assertNull(ScoreCalibration.heldTwoTermNoBindingRegion("physics/foo",
                List.of(verify(RDD_ARGS, RDD_TIGHT_HELD))));
        assertNull(ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/richly_divisible_density", List.of()));
        assertNull(ScoreCalibration.heldTwoTermNoBindingRegion("conjecture/richly_divisible_density", null));
    }
}
