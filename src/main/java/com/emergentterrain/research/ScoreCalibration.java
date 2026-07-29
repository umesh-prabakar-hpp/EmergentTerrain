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
     * 2026-06-15 (review-2026-06-15e Rec 1) — a paper whose load-bearing frame is a MEASUREMENT
     * (verify_constant / verify_oscillation), not a one-sided bound. Its result is a converged limit
     * (e.g. omega_Omega_gap → 0.77316, mean_divisor_excess → 2γ) or a bounded envelope — which
     * legitimately has NO binding region and NO refutation, so {@code binding_regime_uncovered} must
     * not fire on it (that gate assumes a bound). True iff the trace contains any verify_constant or
     * verify_oscillation call. Null-safe.
     */
    public static boolean isConstantOrOscillationFramed(List<ToolInvocation> trace) {
        if (trace == null) {
            return false;
        }
        for (ToolInvocation call : trace) {
            if (call != null && ("verify_constant".equals(call.functionName())
                    || "verify_oscillation".equals(call.functionName()))) {
                return true;
            }
        }
        return false;
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
            // 2026-07-05 (P-b72c-77627782) — a MEASUREMENT-framed paper's hypothesis IS "the LHS
            // converges / oscillates within an envelope / follows a growth law", and the
            // measurement seed explicitly steers the professor AWAY from verify_conjecture; the
            // matching verify_* call with the same LHS exercises that hypothesis. Requiring
            // verify_conjecture specifically punished the instructed behavior (the paper used
            // verify_oscillation on dirichlet_error exactly as told, and was demoted here).
            if (call == null || !VERIFY_TOOLS.contains(call.functionName())) {
                continue;
            }
            String args = call.argsJson();
            if (args != null && args.replace(" ", "").contains(needle)) {
                return false;   // hypothesis LHS was exercised at least once
            }
        }
        return true;   // conjecture topic, non-empty trace, LHS never tested
    }

    /** The verification tools whose {@code lhs} argument exercises a conjecture-topic hypothesis. */
    private static final java.util.Set<String> VERIFY_TOOLS = java.util.Set.of(
            "verify_conjecture", "verify_constant", "verify_oscillation", "verify_growth");

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
     * 2026-06-18 (REVIEW_REPORT b72c4e10 §1 rec 2) — LHS quantities whose real content is a CONVERGED
     * CONSTANT, mapped to that constant. A one-sided {@code lhs <= k*rhs + c} bound on one of these is
     * typically non-informative (it bounds a settling quantity by a possibly-growing RHS; P-b72c-77627725
     * bounded omega_Omega_gap → 0.77316 by a log-growing RHS), and the right frame is verify_constant.
     * Used ONLY to ATTACH an informational caveat to a verify_conjecture result — it does NOT suppress the
     * call or the seed (the miner's breadth is intentional). Keep in sync with the directive's
     * CONSTANT-CONVERGENCE / SECOND-ORDER steps; oscillation-framed quantities (mertens_delta,
     * dirichlet_error) are deliberately NOT here — they belong to verify_oscillation, not verify_constant.
     */
    public static final java.util.Map<String, String> CONSTANT_FRAMED_LHS = java.util.Map.of(
            "omega_Omega_gap", "Σ_p 1/(p(p−1)) ≈ 0.77316",
            "mean_divisor_excess", "2γ ≈ 1.1544",
            "omega_ek_ratio", "1 (Erdős–Kac)",
            "squarefree_density", "6/π² ≈ 0.60793",
            "abundant_density", "≈ 0.2476",
            "twin_vs_prime_sq", "2C₂ ≈ 1.32032");

    /**
     * True when a constant-framed conjecture was tested as an affine bound but
     * never measured with its direct, two-sided {@code verify_constant} frame.
     * A one-sided ceiling on a settling quantity is not sufficient evidence for
     * publication: it can hold with a nearly invariant covariate while saying
     * nothing about the quantity's limiting behavior.
     *
     * <p>The check is deliberately topic- and LHS-specific. An unrelated
     * {@code verify_constant} call cannot satisfy it, and a failed/unavailable
     * direct measurement does not count as executed evidence. A measurement may
     * report either convergence or non-convergence; both are valid direct
     * observations and belong in a revisable paper.
     */
    public static boolean constantFrameDirectMeasurementMissing(String topic,
                                                                  List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/") || trace == null || trace.isEmpty()) {
            return false;
        }
        String lhs = topic.substring("conjecture/".length()).trim();
        if (!CONSTANT_FRAMED_LHS.containsKey(lhs)) {
            return false;
        }
        boolean affineBoundTested = false;
        for (ToolInvocation call : trace) {
            if (call == null || !lhs.equals(extractLhsArg(call.argsJson()))) {
                continue;
            }
            if ("verify_constant".equals(call.functionName()) && usableMeasurement(call.resultJson())) {
                return false;
            }
            if ("verify_conjecture".equals(call.functionName())) {
                affineBoundTested = true;
            }
        }
        return affineBoundTested;
    }

    private static boolean usableMeasurement(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String normalized = result.toLowerCase(java.util.Locale.ROOT);
        return !normalized.contains("unavailable")
                && !normalized.startsWith("invalid ")
                && !normalized.contains(" evaluation error")
                && !normalized.contains(" transport failure")
                && !normalized.contains(" failed:");
    }

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
    private static final java.util.regex.Pattern SUGGEST_K =
            java.util.regex.Pattern.compile("re-verify at k=([0-9.]+)");

    // Phase 30 G2 — the two-term intercept-tighten suggestion the verify tool emits for a loosely-HELD
    // composite ("LOOSE (two-term): … re-verify at c=X …"). Signed because c can be negative; the lever
    // is the intercept (k/k2 are kept). Distinct from SUGGEST_K so the two paths never cross-trigger.
    private static final java.util.regex.Pattern SUGGEST_C =
            java.util.regex.Pattern.compile("re-verify at c=([+-]?[0-9.]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String strArg(String argsJson, String key) {
        if (argsJson == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(argsJson);
        return m.find() ? m.group(1) : null;
    }

    private static String numArg(String argsJson, String key) {
        if (argsJson == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(argsJson);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The verify tool's tight-k suggestion on a loose-but-HELD growth-anchor bound. {@code rhs}/{@code c}
     * and the suggested tighter {@code suggestedK} come from the HELD call; {@code failStart}/
     * {@code failSize}/{@code failNum} are the window params of the FLAT bound's REFUTED call — the
     * failure region where a real bound must hold. The suggested k is only tight for the windows the tool
     * measured (often benign); the capturable bound is the tightest k that holds the FAILURE region, which
     * can be larger. Failure params are null when the trace has no REFUTED call to point at.
     */
    public record TightenSuggestion(String rhs, String c, String suggestedK,
            String failStart, String failSize, String failNum) {}

    /**
     * Non-null when a conjecture trace HELD a growth-anchor bound only LOOSELY — the verify tool emitted
     * its "re-verify at k=…" tight-k suggestion (it does so iff the hold leaves slack past the tightness
     * gate) — AND the professor never followed up with a tight hold (a HELD result carrying NO such
     * suggestion). The deterministic trigger for the tight-k corrective re-run: the tool already
     * DETERMINED a tight constant; the app enforces that the professor captures it AT THE FAILURE REGION
     * (P-b72c-77627560 held max_prime_gap ≤ 1·(ln x)² loose in a BENIGN region, was told k≈0.488, didn't
     * re-verify — and 0.488 refutes at the 338k failure region, which needs k≈0.531). Also captures the
     * first REFUTED call's window params so the re-run can target the binding region. Non-conjecture /
     * empty traces → null.
     */
    public static TightenSuggestion looseGrowthAnchorUntightened(String topic, List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/") || trace == null || trace.isEmpty()) {
            return null;
        }
        String looseRhs = null, looseC = null, looseK = null;
        String failStart = null, failSize = null, failNum = null;
        boolean tightHeldExists = false;
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())) {
                continue;
            }
            String result = call.resultJson();
            if (result == null) {
                continue;
            }
            if (result.contains("REFUTED across") && failStart == null) {  // headline verdict; bare "REFUTED" matches the always-present legend
                // The flat bound's failure region — the windows where a real bound must hold.
                failStart = numArg(call.argsJson(), "window_start");
                failSize = numArg(call.argsJson(), "window_size");
                failNum = numArg(call.argsJson(), "num_windows");
                continue;   // REFUTED summary carries no "HELD"
            }
            if (!result.contains("HELD across")) {   // headline verdict ("HELD" alone could match an auto-probe body)
                continue;
            }
            java.util.regex.Matcher km = SUGGEST_K.matcher(result);
            if (km.find()) {
                if (looseK == null) {
                    looseRhs = strArg(call.argsJson(), "rhs");
                    looseC = numArg(call.argsJson(), "c");
                    looseK = km.group(1);
                }
            } else {
                tightHeldExists = true;   // a HELD with no LOOSE suggestion is already tight
            }
        }
        return (looseK != null && !tightHeldExists)
                ? new TightenSuggestion(looseRhs, looseC, looseK, failStart, failSize, failNum)
                : null;
    }

    /**
     * Phase 30 G2 — the two-term twin of {@link TightenSuggestion}. {@code rhs}/{@code rhs2}/{@code k}/
     * {@code k2} are the composite's structure (kept); {@code suggestedC} is the tightest intercept the
     * verify tool named (the smallest c that still holds every window). {@code fail*} are the first
     * REFUTED call's window params when the trace has one (else null — for a confirmatory-only hold there
     * is none, and the re-run note tightens c on the tested windows instead).
     */
    public record TwoTermTightenSuggestion(String rhs, String rhs2, String k, String k2,
            String suggestedC, String failStart, String failSize, String failNum) {}

    /**
     * Non-null when a conjecture trace HELD a TWO-TERM composite (verify_conjecture called WITH rhs2)
     * only LOOSELY — the verify tool emitted its "LOOSE (two-term): … re-verify at c=…" intercept
     * suggestion — AND the professor never followed up with a tight two-term hold (a two-term HELD result
     * carrying NO such suggestion). Mirrors {@link #looseGrowthAnchorUntightened} but the lever is the
     * intercept c (k/k2 are the residual structure). The trigger for the two-term tighten corrective
     * re-run: drive a held-loosely composite to its binding region (clears binding_regime_uncovered) or
     * tighten a refuted-by-a-hair fit. Considers ONLY two-term calls, so a one-term trace never trips it
     * (and vice versa — SUGGEST_C ≠ SUGGEST_K). Non-conjecture / empty traces → null.
     */
    public static TwoTermTightenSuggestion looseTwoTermUntightened(String topic, List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/") || trace == null || trace.isEmpty()) {
            return null;
        }
        String looseRhs = null, looseRhs2 = null, looseK = null, looseK2 = null, suggestedC = null;
        String failStart = null, failSize = null, failNum = null;
        boolean tightHeldExists = false;
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())) {
                continue;
            }
            String result = call.resultJson();
            if (result == null) {
                continue;
            }
            String args = call.argsJson();
            boolean isTwoTerm = args != null && args.contains("\"rhs2\"");
            if (result.contains("REFUTED across") && failStart == null) {  // headline verdict; bare "REFUTED" matches the always-present legend
                // A REFUTED call's window params — the region a tightened composite must still hold.
                failStart = numArg(args, "window_start");
                failSize = numArg(args, "window_size");
                failNum = numArg(args, "num_windows");
                continue;   // REFUTED summary carries no "HELD"
            }
            if (!isTwoTerm || !result.contains("HELD across")) {
                continue;   // only an overall-HELD two-term call can carry the two-term intercept suggestion
            }
            java.util.regex.Matcher cm = SUGGEST_C.matcher(result);
            if (cm.find()) {
                if (suggestedC == null) {
                    looseRhs = strArg(args, "rhs");
                    looseRhs2 = strArg(args, "rhs2");
                    looseK = numArg(args, "k");
                    looseK2 = numArg(args, "k2");
                    suggestedC = cm.group(1);
                }
            } else {
                tightHeldExists = true;   // a two-term HELD with no LOOSE suggestion is already tight
            }
        }
        return (suggestedC != null && !tightHeldExists)
                ? new TwoTermTightenSuggestion(looseRhs, looseRhs2, looseK, looseK2,
                        suggestedC, failStart, failSize, failNum)
                : null;
    }

    // Phase 30 G2 follow-on (2026-06-18, REVIEW_REPORT b72c4e10 §1: P-b72c-77627715 held a two-term
    // composite across 60 windows with NO refuting call and published with the binding region never
    // shown). The headline discriminators are " HELD across" / " REFUTED across": the per-call result
    // also carries a verdict LEGEND that contains the word "REFUTED" on EVERY call (held or not), so a
    // bare contains("REFUTED") matches the legend, not the verdict — this detector keys on the headline.
    private static final java.util.regex.Pattern WORST_MARGIN =
            java.util.regex.Pattern.compile("worst margin:\\s*([+-]?[0-9.]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Phase 30 G2 follow-on — the TIGHT-HOLD twin of {@link TwoTermTightenSuggestion}. {@code rhs}/
     * {@code rhs2}/{@code k}/{@code k2} are the composite's structure (kept); {@code suggestedC} is the
     * tightest intercept that still holds every tested window (the held call's c plus its worst/binding
     * margin — the same {@code c + binding-margin} the verify tool's two-term LOOSE branch computes).
     * {@code held*} are the held call's own window params — the region the probe steps c down on, since a
     * confirmatory-only trace has no REFUTED region to point at.
     */
    public record SharpnessProbeSuggestion(String rhs, String rhs2, String k, String k2,
            String suggestedC, String heldStart, String heldSize, String heldNum) {}

    /**
     * Non-null when a conjecture trace HELD a TWO-TERM composite (verify_conjecture called WITH rhs2) but
     * NEVER produced a REFUTED call anywhere — so the binding region was never shown and the composite is
     * {@code binding-regime-uncovered} (Step 10 of the conjecture directive), no matter how tight the hold
     * or how many windows held. This is the case the loose-tighten gate ({@link #looseTwoTermUntightened})
     * does NOT catch: a TIGHT hold emits no "re-verify at c=…" suggestion, so the loose gate stays silent
     * and the professor stops at a comfortable hold (P-b72c-77627715: slack 0.018–0.042, 60/60 held, no
     * refute). The deterministic trigger for the sharpness-probe corrective re-run: drive the professor to
     * step the intercept down to where it binds. Determination is a deterministic computation over the
     * tool's own per-window data ({@code c + worst-margin}); the app enforces the skipped follow-up. Fires
     * ONLY when no REFUTED call exists (a trace that already shows a binding region needs no probe).
     * Non-conjecture / empty traces → null.
     */
    public static SharpnessProbeSuggestion heldTwoTermNoBindingRegion(String topic, List<ToolInvocation> trace) {
        if (topic == null || !topic.startsWith("conjecture/") || trace == null || trace.isEmpty()) {
            return null;
        }
        // 2026-06-18 fix — a constant-convergence / oscillation paper (verify_constant /
        // verify_oscillation, e.g. omega_Omega_gap → 0.77316, mean_divisor_excess → 2γ)
        // legitimately has NO binding region; demanding a refuting c is unsatisfiable and churns the
        // re-run to its cap (P-b72c-77627725 burned both sharpness re-runs on omega_Omega_gap). Exempt
        // that frame, mirroring the binding-regime gate's constant-frame exemption (UniversityService).
        if (isConstantOrOscillationFramed(trace)) {
            return null;
        }
        String rhs = null, rhs2 = null, k = null, k2 = null, heldStart = null, heldSize = null, heldNum = null;
        Double worstMargin = null, heldC = null;
        boolean anyRefuted = false;
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())) {
                continue;
            }
            String result = call.resultJson();
            if (result == null) {
                continue;
            }
            if (result.contains("REFUTED across")) {
                anyRefuted = true;   // the binding region IS shown (incl. an auto-probe refute) — no probe needed
                continue;
            }
            String args = call.argsJson();
            boolean isTwoTerm = args != null && args.contains("\"rhs2\"");
            if (!isTwoTerm || !result.contains("HELD across")) {
                continue;   // only an overall-HELD two-term call carries a binding region to probe
            }
            if (worstMargin == null) {   // first held two-term call defines the structure + tight c
                java.util.regex.Matcher wm = WORST_MARGIN.matcher(result);
                String cArg = numArg(args, "c");
                if (wm.find() && cArg != null) {
                    try {
                        worstMargin = Double.parseDouble(wm.group(1));
                        heldC = Double.parseDouble(cArg);
                    } catch (NumberFormatException e) {
                        worstMargin = null;
                        heldC = null;
                        continue;
                    }
                    rhs = strArg(args, "rhs");
                    rhs2 = strArg(args, "rhs2");
                    k = numArg(args, "k");
                    k2 = numArg(args, "k2");
                    heldStart = numArg(args, "window_start");
                    heldSize = numArg(args, "window_size");
                    heldNum = numArg(args, "num_windows");
                }
            }
        }
        if (worstMargin == null || heldC == null || anyRefuted) {
            return null;
        }
        // Tightest intercept that still holds every tested window = c + worst (max) margin; a slightly
        // smaller c then REFUTES, which is the binding region the gate requires. Same formula the tool's
        // own two-term LOOSE branch uses (c + binding margin), here over the held call's per-window data.
        String suggestedC = String.format(java.util.Locale.ROOT, "%+.4f", heldC + worstMargin);
        return new SharpnessProbeSuggestion(rhs, rhs2, k, k2, suggestedC, heldStart, heldSize, heldNum);
    }

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
            // (a) An actual refutation is the strongest adversarial signal. Key on the " REFUTED across"
            // headline, NOT bare "REFUTED": every result carries a verdict legend ("…only margin > X is
            // REFUTED…"), so contains("REFUTED") matched on EVERY held call and silently defeated this
            // gate (confirmatory-only papers always read as adversarial → isConfirmatoryOnlyConjecture
            // never fired). P-b72c-77627715 is the signature this restores.
            if (result != null && result.contains("REFUTED across")) {
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
