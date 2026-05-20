package com.emergentterrain.research;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 21 §21.4 — preflight sanity check on a pre-registered hypothesis
 * BEFORE the agentic professor stage runs. Targets the failure mode
 * P-a328-693 demonstrated: a hypothesis with a physically impossible
 * premise (TIR with n₂&gt;n₁) that reached a tool call, returned a
 * symbolic-solver echo, and then had the narration LLM fabricate
 * "61.04°" + "1.49" to fill the gap.
 *
 * <p>The four downstream validators (numerical-consistency, cherry-pick,
 * panel-grade-floor, citation-validator) all run AFTER the tool call.
 * None can stop the wasted call or the resulting fabrication. This
 * validator runs at hypothesis-publish time so a malformed premise is
 * abandoned (or retried with the error fed back into the prompt) before
 * any compute is burned.
 *
 * <p>Detection is conservative — the validator is allowed to miss
 * subtle physics errors that would need a domain model far beyond a
 * regex pass. The named acceptance is just "P-a328-693's hypothesis
 * would never have reached a tool call." Topics with no rule entry
 * always return {@link Result.Ok}.
 *
 * <p>Stateless / package-static; safe to call from any thread.
 */
public final class PremiseValidator {

    private PremiseValidator() {}

    /** Outcome of one validation pass. */
    public sealed interface Result permits Result.Ok, Result.Reject {
        record Ok() implements Result {}

        /**
         * Hypothesis rejected with a short machine-readable reason
         * (suitable for inclusion in a {@code premise:rejected:&lt;reason&gt;}
         * call-path stamp) and a human-readable explanation that can be
         * fed back into a retry prompt.
         */
        record Reject(String reasonCode, String explanation) implements Result {}
    }

    /** Singleton OK to avoid garbage when most papers pass. */
    public static final Result OK = new Result.Ok();

    /**
     * Validate a pre-registered hypothesis against topic-keyed rules.
     * Returns {@link Result.Ok} when the rule set has no entry for the
     * topic or every rule passes; {@link Result.Reject} when at least
     * one rule fires.
     *
     * <p>Topic matching is lowercase + hyphen-normalised, so "Snell
     * Critical Angle Dispersion", "snell-critical-angle", and
     * "snell_critical_angle" all hit the same rule.
     */
    public static Result validate(String topic, String hypothesisText) {
        if (hypothesisText == null || hypothesisText.isBlank()) return OK;
        String t = normaliseTopic(topic);

        if (isSnellOrTirTopic(t)) {
            Result snell = validateSnell(hypothesisText);
            if (snell instanceof Result.Reject) return snell;
        }
        if (isTwinPrimeTopic(t)) {
            Result twin = validateTwinPrime(hypothesisText);
            if (twin instanceof Result.Reject) return twin;
        }
        return OK;
    }

    // ───────────────────────── topic classifiers ─────────────────────────

    static String normaliseTopic(String topic) {
        if (topic == null) return "";
        return topic.toLowerCase()
                .replace('_', '-')
                .replaceAll("\\s+", "-");
    }

    static boolean isSnellOrTirTopic(String normalisedTopic) {
        return normalisedTopic.contains("snell")
                || normalisedTopic.contains("tir")
                || normalisedTopic.contains("critical-angle")
                || normalisedTopic.contains("total-internal-reflection");
    }

    static boolean isTwinPrimeTopic(String normalisedTopic) {
        return normalisedTopic.contains("twin-prime")
                || normalisedTopic.contains("hardy-littlewood")
                || normalisedTopic.contains("prime-constellation");
    }

    // ──────────────────────────── snell / TIR ────────────────────────────

    // Matches "n₁=1.50", "n1 = 1.50", "n_1=1.5", "n_{1}=1.5" — same for n2.
    // Subscript variants (₁/₂) handled by the alternation; whitespace
    // around `=` is optional.
    private static final Pattern N1_PAT = Pattern.compile(
            "n(?:[_]?\\{?1\\}?|₁)\\s*[=≈~]\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern N2_PAT = Pattern.compile(
            "n(?:[_]?\\{?2\\}?|₂)\\s*[=≈~]\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    static Result validateSnell(String hypothesis) {
        Matcher m1 = N1_PAT.matcher(hypothesis);
        Matcher m2 = N2_PAT.matcher(hypothesis);
        if (!m1.find() || !m2.find()) return OK;  // no n1/n2 pair to check
        double n1;
        double n2;
        try {
            n1 = Double.parseDouble(m1.group(1));
            n2 = Double.parseDouble(m2.group(1));
        } catch (NumberFormatException nf) {
            return OK;  // can't parse, can't reject
        }
        // Total Internal Reflection only occurs when light goes from the
        // denser to the rarer medium (n₁ > n₂). With n₂ ≥ n₁, n₂/n₁ ≥ 1,
        // arcsin(n₂/n₁) is undefined (no real critical angle). This is
        // the P-a328-693 signature.
        if (n2 >= n1) {
            return new Result.Reject(
                    "tir_indices_inverted",
                    "TIR requires n₁ > n₂ (light goes from denser to rarer medium); "
                            + "the hypothesis names n₁=" + format(n1) + ", n₂=" + format(n2)
                            + ". arcsin(n₂/n₁)=arcsin(" + format(n2 / n1) + ") has no real "
                            + "solution. Either swap the indices, or change the geometry so "
                            + "light enters the denser medium first.");
        }
        return OK;
    }

    // ────────────────────────── twin-prime ──────────────────────────────

    // Matches "twin density 0.0278" / "twin prime density of 0.13" / etc.
    private static final Pattern TWIN_DENSITY_PAT = Pattern.compile(
            "twin[\\s-]*(?:prime[\\s-]*)?density", Pattern.CASE_INSENSITIVE);
    // Matches "overall density 0.13" / "prime density of 0.18" / "total density".
    // The negative lookbehind blocks the "prime density" alternative from
    // matching inside "twin-prime density" / "cousin-prime density" / etc.,
    // which would otherwise spuriously satisfy the "has overall slot" check
    // and let conflated prose slip through.
    private static final Pattern OVERALL_DENSITY_PAT = Pattern.compile(
            "(?<![\\w-])(?:overall|total|all[\\s-]+prime|prime)\\s+density",
            Pattern.CASE_INSENSITIVE);
    // Matches the bare word "density".
    private static final Pattern BARE_DENSITY_PAT = Pattern.compile(
            "density", Pattern.CASE_INSENSITIVE);

    static Result validateTwinPrime(String hypothesis) {
        boolean mentionsDensity = BARE_DENSITY_PAT.matcher(hypothesis).find();
        if (!mentionsDensity) return OK;
        boolean hasTwinSlot    = TWIN_DENSITY_PAT.matcher(hypothesis).find();
        boolean hasOverallSlot = OVERALL_DENSITY_PAT.matcher(hypothesis).find();
        if (hasTwinSlot && hasOverallSlot) return OK;
        // P-a328-685 signature: the hypothesis conflates twin-prime density
        // (e.g. 0.0278) with overall prime density (≈0.13) because the
        // prose uses "density" without qualifying which one is meant.
        if (hasTwinSlot && !hasOverallSlot) {
            return new Result.Reject(
                    "twin_density_unqualified",
                    "The hypothesis mentions twin-prime density but no separate "
                            + "overall-prime density. State both explicitly so the prose "
                            + "cannot conflate the two (twin density ~1/log²x is much "
                            + "smaller than overall density ~1/log x).");
        }
        if (!hasTwinSlot && hasOverallSlot) {
            return new Result.Reject(
                    "overall_density_only",
                    "The hypothesis mentions overall prime density but no twin-prime "
                            + "density. For a twin-prime / Hardy-Littlewood topic, the "
                            + "twin density is the quantity to report; name it explicitly.");
        }
        return new Result.Reject(
                "density_ambiguous",
                "The hypothesis says 'density' without qualifying whether it means "
                        + "twin-prime density or overall-prime density. Distinguish "
                        + "the two so the findings cannot conflate them.");
    }

    private static String format(double v) {
        // Trim trailing zeros so "1.50" reads as "1.5" but "1.577" stays "1.577".
        return Double.toString(v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
