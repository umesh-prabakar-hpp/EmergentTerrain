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

    /**
     * 2026-06-06 review rec #1 — hypothesis-LHS coverage. A conjecture paper
     * (topic {@code "conjecture/<lhs>"}) whose trace never invokes
     * {@code verify_conjecture} with that lhs never evaluated its own
     * hypothesis, whatever else it computed. Two same-batch signatures:
     * P-b72c-210 (every call used {@code lhs="prime_density"} under a
     * twin_vs_prime_sq topic, producing a tautology the paper then blamed on
     * the harness) and P-b72c-215 (the preregistered pair never invoked; the
     * paper published a self-described "Critical Gap"). The binding-regime
     * gate only catches the SUPPORTED-classified variant; this one is
     * classification-independent.
     *
     * <p>Empty/null traces return {@code false} — the missing-methods gate
     * owns that case. Non-conjecture topics return {@code false}.
     */
    public static boolean hypothesisLhsUncovered(String topic, List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/")) {
            return false;
        }
        String lhs = topic.substring("conjecture/".length()).trim();
        if (lhs.isEmpty() || trace == null || trace.isEmpty()) {
            return false;
        }
        String needle = "\"lhs\":\"" + lhs + "\"";
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())) {
                continue;
            }
            String args = call.argsJson();
            if (args != null && args.replace(" ", "").contains(needle)) {
                return false;   // hypothesis LHS was exercised at least once
            }
        }
        return true;   // conjecture topic, non-empty trace, LHS never tested
    }

    /**
     * The LHS quantities whose <i>leading order</i> is a textbook closed-form law
     * — so a paper that only bounds them confirms the known asymptotic, which is a
     * non-result (the novelty is the next term). This set is the ONLY mathematical
     * knowledge the engagement gate carries: it scopes WHICH topics must go past
     * the leading order. It deliberately does NOT map a topic to "the" second-order
     * scalar — the professor LLM picks that object (the directive's SECOND-ORDER
     * STEP gives it the menu). The gate enforces <i>engagement</i>; the model owns
     * <i>determination</i>. Keep this in sync with the directive's enumeration, not
     * with the scalar vocabulary.
     */
    public static final java.util.Set<String> LEADING_ORDER_LHS = java.util.Set.of(
            "mean_omega", "mean_Omega", "mean_divisor_count", "max_divisor_count",
            "abundant_density", "deficient_density", "happy_density", "prime_density");

    /**
     * The result-frame tools that engage second-order structure (a converged
     * constant, an oscillation envelope, a growth law) — as opposed to
     * {@code verify_conjecture}, which only tests a bound on the raw quantity.
     * Using ANY of these on a leading-order topic counts as engagement; the gate
     * does not inspect WHICH scalar the frame was pointed at (that is the model's
     * determination).
     */
    private static final java.util.Set<String> SECOND_ORDER_FRAMES = java.util.Set.of(
            "verify_constant", "verify_oscillation", "verify_growth");

    /**
     * True when a conjecture investigation on a TEXTBOOK-leading-order LHS
     * ({@link #LEADING_ORDER_LHS}) never engaged a single second-order frame
     * ({@link #SECOND_ORDER_FRAMES}) — i.e. it stopped at the bound frame and so
     * could only confirm the known asymptotic. The deterministic <i>engagement</i>
     * trigger for the second-order corrective re-run: the app detects "you stopped
     * at the leading order"; the re-run note then asks the model to choose and
     * measure the right second-order object. Mirrors {@link #hypothesisLhsUncovered}'s
     * contract: non-conjecture topics, topics outside the leading-order set, and
     * empty/null traces all return {@code false} (the missing-methods gate owns the
     * empty case; off-target coverage is a separate gate).
     */
    public static boolean secondOrderEngagementSkipped(String topic, List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/")) {
            return false;
        }
        String lhs = topic.substring("conjecture/".length()).trim();
        if (!LEADING_ORDER_LHS.contains(lhs)) {
            return false;   // no textbook leading order to go past — nothing to enforce
        }
        if (trace == null || trace.isEmpty()) {
            return false;   // missing-methods gate owns the empty-trace case
        }
        for (ToolInvocation call : trace) {
            if (call != null && SECOND_ORDER_FRAMES.contains(call.functionName())) {
                return false;   // a second-order frame was engaged — the model chose the object
            }
        }
        return true;   // leading-order topic, non-empty trace, no second-order frame used at all
    }

    private static final java.util.regex.Pattern LHS_ARG =
            java.util.regex.Pattern.compile("\"lhs\"\\s*:\\s*\"([^\"]+)\"");

    private static String extractLhsArg(String argsJson) {
        if (argsJson == null) {
            return null;
        }
        java.util.regex.Matcher m = LHS_ARG.matcher(argsJson);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The distinct second-order objects a trace actually measured — the {@code lhs}
     * of every {@code verify_constant}/{@code verify_oscillation}/{@code verify_growth}
     * call, in first-seen order. This is the scalar the MODEL chose (the gate enforces
     * engagement; this records the determination). Empty when no second-order frame was
     * used. Used to stamp {@code second-order:engaged:<scalar>} so coverage — and which
     * object each leading-order paper reached — is visible in the log without opening
     * the paper's Methods.
     */
    public static java.util.List<String> secondOrderScalarsEngaged(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (ToolInvocation call : trace) {
            if (call == null || !SECOND_ORDER_FRAMES.contains(call.functionName())) {
                continue;
            }
            String lhs = extractLhsArg(call.argsJson());
            if (lhs != null && !lhs.isEmpty()) {
                out.add(lhs);
            }
        }
        return new java.util.ArrayList<>(out);
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
