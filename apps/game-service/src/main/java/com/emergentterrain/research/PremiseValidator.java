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
        // Topic-agnostic checks first — a truncated hypothesis fails the
        // pre-registration contract regardless of which physics it claims.
        Result completeness = validateCompleteness(hypothesisText);
        if (completeness instanceof Result.Reject) return completeness;

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

    // ─────────────────────── hypothesis completeness ─────────────────────

    /**
     * Reject when the hypothesis ends with a clause-continuation marker —
     * the LLM started a subordinate clause and the prose was truncated
     * before it finished. Added 2026-05-20 after P-a328-755 shipped with a
     * hypothesis ending at {@code "...the conjectured asymptotic 14.3%
     * [call_1] given"} and P-a328-727 shipped with {@code "...less than
     * 20°, based on"}. Both passed the upstream {@code audit:4b:0gaps}
     * check (which inspects semantic gaps, not syntactic completeness)
     * and produced wasted tool calls because the professor stage had no
     * complete hypothesis to test.
     *
     * <p>High-precision tail match: a curated set of subordinator phrases
     * (given / based on / due to / etc.) at the END of the hypothesis,
     * optionally followed by punctuation that doesn't close the sentence.
     * Hypotheses that end with a complete clause — even without a final
     * period — are accepted; the rule does not police missing terminal
     * punctuation alone, which would false-positive on stylistic
     * variants like "...n=40 samples" or "...within 2°".
     */
    private static final Pattern TRUNCATION_TAIL = Pattern.compile(
            "\\b(?:given|based\\s+on|due\\s+to|because|assuming|such\\s+that|"
                    + "whereas|including|consistent\\s+with|in\\s+the\\s+limit(?:\\s+of)?|"
                    + "under\\s+the\\s+assumption(?:\\s+that)?|as\\s+a\\s+function\\s+of|"
                    + "where|which(?:\\s+is)?|that(?:\\s+is)?)"
                    + "[\\s,;:]*$",
            Pattern.CASE_INSENSITIVE);

    static Result validateCompleteness(String hypothesis) {
        Matcher m = TRUNCATION_TAIL.matcher(hypothesis);
        if (m.find()) {
            String tail = m.group(0).trim();
            return new Result.Reject(
                    "hypothesis_truncated",
                    "Hypothesis appears to be truncated mid-clause — the final phrase '"
                            + tail + "' introduces a subordinate clause that is missing. "
                            + "Complete the sentence (state what follows '" + tail
                            + "') and re-emit the hypothesis.");
        }
        return OK;
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

    // Prose-form fallback for hypotheses that name indices via natural language
    // rather than n₁/n₂ tokens. P-a328-736 signature:
    //   "the incident medium has refractive index 1.50 and the adjoining
    //    medium has a refractive index near 1.565"
    // `[^.]{0,80}` constrains the match to a single sentence and ~80 chars
    // between the medium phrase and the "refractive index" token — tight
    // enough to skip spurious matches across paragraphs, loose enough to
    // accept articles like "a" / "the" / "an" between them.
    private static final Pattern N1_PROSE_PAT = Pattern.compile(
            "(?:first|incident|denser)\\s+medium[^.]{0,80}refractive\\s+index"
                    + "(?:\\s+(?:of|near|approximately|about|≈|~))?\\s+(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern N2_PROSE_PAT = Pattern.compile(
            "(?:second|adjoining|other|rarer)\\s+medium[^.]{0,80}refractive\\s+index"
                    + "(?:\\s+(?:of|near|approximately|about|≈|~))?\\s+(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    // Swept-n₂ fallback (added 2026-05-26 after P-a328-953). That hypothesis
    // wrote "n₂ varying from approximately 1.534 down to 1.490 across 56
    // samples" — no "n₂=" token and no "…medium…refractive index" prose, so
    // both single-value patterns above missed it and the inverted upper end
    // (1.534 > n₁=1.50, no real critical angle) shipped. N2_SWEEP_CLAUSE grabs
    // the sentence following an n₂ token; SWEEP_WORD confirms it is a range;
    // INDEX_LIKE harvests refractive-index-shaped values (1.x / 2.x only, so
    // the "scale 4.8" / "position 52" / "56 samples" knobs in the same clause
    // are excluded). TIR fails if ANY sample has n₂ ≥ n₁, so we test the max.
    // The window stops at a true sentence boundary (period-then-whitespace) but
    // must NOT stop at the decimal point inside an index like "1.534" — hence
    // the (?!\.\s) lookahead rather than a plain [^.] class, which truncated the
    // clause at "approximately 1" and hid the swept values.
    private static final Pattern N2_SWEEP_CLAUSE = Pattern.compile(
            "n(?:[_]?\\{?2\\}?|₂)(?:(?!\\.\\s).){0,160}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SWEEP_WORD = Pattern.compile(
            "\\b(?:from|to|down\\s+to|varying|sweep|swept|across|between|range)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_LIKE = Pattern.compile("\\b([12]\\.\\d+)\\b");

    private static Double firstDouble(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException nf) {
            return null;
        }
    }

    /** Largest plausible refractive index in a swept-n₂ clause, or null. */
    private static Double maxSweptN2(String hypothesis) {
        Matcher clause = N2_SWEEP_CLAUSE.matcher(hypothesis);
        if (!clause.find()) return null;
        String c = clause.group();
        if (!SWEEP_WORD.matcher(c).find()) return null;
        Matcher v = INDEX_LIKE.matcher(c);
        double max = Double.NEGATIVE_INFINITY;
        while (v.find()) {
            double d = Double.parseDouble(v.group(1));
            if (d > max) max = d;
        }
        return max == Double.NEGATIVE_INFINITY ? null : max;
    }

    static Result validateSnell(String hypothesis) {
        // Extract n₁ (incident/denser medium) from subscript or prose form.
        Double n1 = firstDouble(N1_PAT, hypothesis);
        if (n1 == null) n1 = firstDouble(N1_PROSE_PAT, hypothesis);
        if (n1 == null) return OK;  // no extractable n₁, can't reject

        // Extract n₂: prefer a single named value, then take the larger of that
        // and any swept-range maximum. The swept fallback can only raise n₂
        // (never lower it), so it never flips a prior reject into an OK — it can
        // only catch the inverted upper end of a sweep the old code waved through.
        Double n2 = firstDouble(N2_PAT, hypothesis);
        if (n2 == null) n2 = firstDouble(N2_PROSE_PAT, hypothesis);
        String n2Basis = "n₂";
        Double sweptMax = maxSweptN2(hypothesis);
        if (sweptMax != null && (n2 == null || sweptMax > n2)) {
            n2 = sweptMax;
            n2Basis = "max swept n₂";
        }
        if (n2 == null) return OK;  // no extractable n₂

        // Total Internal Reflection only occurs when light goes from the denser
        // to the rarer medium (n₁ > n₂). With n₂ ≥ n₁, n₂/n₁ ≥ 1 and
        // arcsin(n₂/n₁) is undefined (no real critical angle). This is the
        // P-a328-693 (subscript), P-a328-736 (prose), and P-a328-953 (swept)
        // signature.
        if (n2 >= n1) {
            return new Result.Reject(
                    "tir_indices_inverted",
                    "TIR requires n₁ > n₂ (light goes from denser to rarer medium); "
                            + "the hypothesis names n₁=" + format(n1) + ", " + n2Basis + "="
                            + format(n2) + ". arcsin(n₂/n₁)=arcsin(" + format(n2 / n1)
                            + ") has no real solution. Either swap the indices, keep the "
                            + "n₂ sweep below n₁, or change the geometry so light enters the "
                            + "denser medium first.");
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
