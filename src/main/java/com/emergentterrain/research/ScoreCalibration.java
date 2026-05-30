package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.List;

/**
 * Score-calibration helpers (room b72c4e10 fix, Rec 2). Pure and stateless so
 * they unit-test without Spring, the sidecar, or Mockito.
 *
 * <p><b>Why this exists.</b> The published headline score is
 * {@code peerScore × (0.92 + 0.16·confidence)} ({@code UniversityService}). In
 * room b72c4e10 the three mertens papers had near-equal base {@code peerScore}
 * (~0.75), so the entire published spread (0.702→0.764) came from the confidence
 * multiplier — and confidence was <i>highest</i> for the paper (P-b72c-15) whose
 * all-HELD trace simply never looked for a counterexample, while it was
 * <i>lowest</i> for the two papers that found genuine violations. The headline
 * thus rewarded the absence of evidence and inverted the panel's own quality
 * ordering (P-15 had the lowest cascade-quality grade of the three).
 *
 * <p>Two independent, flag-gated levers address this:
 * <ol>
 *   <li>{@link #hasAdversarialCoverage} — gate the confidence boost: a SUPPORTED
 *       conjecture paper whose trace contains no adversarial probe (a
 *       {@code verify_conjecture} that was REFUTED, or one reaching the
 *       binding/small-start regime) earns no confidence boost. "Held because we
 *       never stressed it" must not buy confidence.</li>
 *   <li>{@link #cascadeQuality} — the panel's own quality signal (mean of the
 *       four median axes plus a tiny agreement tiebreak), which the cascade
 *       computes for winner-selection and then discards. Surfacing it as the
 *       headline rank decouples the headline from confidence entirely.</li>
 * </ol>
 */
public final class ScoreCalibration {

    private ScoreCalibration() {}

    /** Window start below which a {@code verify_conjecture} call counts as probing the binding regime. */
    public static final long BINDING_REGIME_MAX_START = 10_000L;

    /**
     * Cascade quality: mean of the four median panel axes
     * (honesty, completeness, rigor, novelty) plus a small agreement tiebreak,
     * matching {@code ResearchNarrationService.scoreCandidate}'s panel branch.
     * This is the panel's own ranking signal, independent of the narrator's
     * self-assessed confidence.
     */
    public static double cascadeQuality(double honesty, double completeness,
                                        double rigor, double novelty,
                                        double agreementScore) {
        double composite = (honesty + completeness + rigor + novelty) / 4.0;
        return composite + 0.001 * agreementScore;
    }

    /**
     * Does the trace contain genuine adversarial evidence for a conjecture
     * bound? True iff some {@code verify_conjecture} call either (a) was
     * REFUTED, or (b) probed the binding regime (a window starting at or below
     * {@link #BINDING_REGIME_MAX_START}). A trace whose {@code verify_conjecture}
     * calls all HELD and all sat in the comfortable large-start regime is
     * confirmatory-only and returns false.
     *
     * <p>Null/empty traces and traces with no {@code verify_conjecture} call
     * return false — there is nothing adversarial to credit.
     */
    /**
     * Does this trace use the conjecture machinery at all? True iff it contains
     * at least one {@code verify_conjecture} call. The confidence gate keys on
     * this so it bites only conjecture papers — a physics or number-theory paper
     * that legitimately never calls {@code verify_conjecture} is not penalised
     * for lacking adversarial conjecture coverage it was never expected to have.
     */
    public static boolean usesConjectureVerification(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (ToolInvocation call : trace) {
            if (call != null && "verify_conjecture".equals(call.functionName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A confirmatory-only conjecture trace: it used {@code verify_conjecture}
     * but none of those calls were adversarial (none REFUTED, none reached the
     * binding regime). This is the exact P-b72c-15 signature — the case whose
     * confidence boost should be withheld.
     */
    public static boolean isConfirmatoryOnlyConjecture(List<ToolInvocation> trace) {
        return usesConjectureVerification(trace) && !hasAdversarialCoverage(trace);
    }

    public static boolean hasAdversarialCoverage(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())) {
                continue;
            }
            String result = call.resultJson();
            // (a) An actual refutation is the strongest adversarial signal.
            if (result != null && result.contains("REFUTED")) {
                return true;
            }
            // (b) A HELD result still counts if it reached the binding regime.
            if (probedBindingRegime(call.argsJson())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff this {@code verify_conjecture} call's {@code window_start} is at
     * or below {@link #BINDING_REGIME_MAX_START}. Parses the small-integer value
     * out of the raw args JSON without a full JSON bind (the field is a plain
     * numeric literal: {@code "window_start":2003}). Returns false on any parse
     * difficulty — a probe we can't confirm is not credited.
     */
    static boolean probedBindingRegime(String argsJson) {
        if (argsJson == null) {
            return false;
        }
        int idx = argsJson.indexOf("\"window_start\"");
        if (idx < 0) {
            return false;
        }
        // Skip to the value: past the key, the colon, and any whitespace.
        int i = idx + "\"window_start\"".length();
        while (i < argsJson.length() && (argsJson.charAt(i) == ':' || argsJson.charAt(i) == ' ')) {
            i++;
        }
        int start = i;
        while (i < argsJson.length()
                && (Character.isDigit(argsJson.charAt(i)) || argsJson.charAt(i) == '-')) {
            i++;
        }
        if (i == start) {
            return false;
        }
        try {
            long windowStart = Long.parseLong(argsJson.substring(start, i));
            return windowStart >= 0 && windowStart <= BINDING_REGIME_MAX_START;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
