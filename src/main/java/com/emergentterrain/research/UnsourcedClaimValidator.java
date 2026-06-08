package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 18 §18.2 (P8d) — flag Findings/Conclusion sentences that assert a
 * derivative / uncertainty / error-propagation / {@code ±N} claim with no
 * paired {@code [call_N]} reference in the same sentence.
 *
 * <p>The corpus pattern: the LLM produces honest tool-call math (exact
 * Snell value, exact Kepler deviations) and then pads the prose with
 * "physical interpretation" content that reads plausible but has no
 * tool-call provenance. Refresh #2 evidence:
 *
 * <ul>
 *   <li>P-a328-603 Snell. Call 3 returned exact
 *       {@code arcsin(1.49/1.50) = 83.3804°}. Findings prose invented
 *       "1.5° maximum observed error" and "331° per unit n₂ derivative"
 *       — neither has a supporting tool call.</li>
 *   <li>P-a328-605 Kepler. Calls 3 + 5 returned exact 4.12% / 4.08%
 *       deviations. Findings prose claimed "53 samples" and "20 samples"
 *       — only 5 calls ran. (Sample-size claims are
 *       {@link SinglePointSweepValidator}'s territory; this validator
 *       deliberately does NOT double-stamp them.)</li>
 * </ul>
 *
 * <p><strong>Matching:</strong> sentence-scoped. The prose is split on
 * sentence-end punctuation (period / exclamation / question followed by
 * whitespace+capital or EOL); each sentence is searched for a trigger
 * pattern (derivative / uncertainty / error / ±N) and, when one fires,
 * for a paired {@code [call_N]} marker. Sentence-scoped is stricter
 * than paragraph-scoped (higher precision, more FPs on long sentences
 * that legitimately cite at the end). Empirical from a 30-paper refresh
 * window will tell us which to keep.
 *
 * <p>Returns reasons tagged by claim type so the call_path stamp can
 * distinguish {@code unsourced_claim:derivative} from
 * {@code unsourced_claim:uncertainty}. Stateless and pure.
 */
public final class UnsourcedClaimValidator {

    private UnsourcedClaimValidator() {}

    /**
     * Trigger phrases for each claim type. Order matters: more specific
     * patterns first, so a sentence carrying both a derivative AND an
     * uncertainty claim only counts once (the more specific trigger
     * wins). Each entry's regex matches the trigger phrase; the validator
     * then checks the surrounding sentence for a {@code [call_N]} marker.
     */
    private enum Trigger {
        // 2026-06-08 review rec 2 — a claim that an INDEPENDENT / SEPARATE
        // party (re-)verified, validated, confirmed, reproduced or recomputed
        // a result. In this pipeline the only verification that happens is a
        // tool call (verify_conjecture, a predicate scan) that carries its own
        // [call_N]; so an "independent verification" phrasing with no paired
        // [call_N] in the sentence is a fabricated process claim, not a real
        // one. P-b72c-313 invented "the professor's independent verification
        // confirmed the same count of 11 happy primes" with no such call in
        // the trace. High precision by design: requires the independent/
        // separate cue ADJACENT to the verify-verb, so "independent variable"
        // / "separate analysis" / a "## Adversarial Check" header never fire.
        PHANTOM_VERIFICATION("phantom_verification", Pattern.compile(
                "(?i)\\b(?:independent(?:ly)?|separate(?:ly)?)\\s+"
                        + "(?:verif\\w+|validat\\w+|confirm\\w+|reproduc\\w+|re-?comput\\w+)"
                        + "|(?i)\\b(?:verif\\w+|confirm\\w+|validat\\w+|reproduc\\w+)"
                        + "\\s+(?:independently|separately)\\b")),
        // "the derivative of X", "dy/dx", "∂f/∂x", "per unit <var> derivative".
        DERIVATIVE("derivative", Pattern.compile(
                "(?i)\\bderivative\\b|\\bd[A-Za-z_][A-Za-z_0-9]?/d[A-Za-z_]\\b|∂[A-Za-z_]/∂[A-Za-z_]")),
        // "uncertainty of ±0.5", "uncertainty estimate", "with uncertainty …".
        UNCERTAINTY("uncertainty", Pattern.compile(
                "(?i)\\buncertaint(?:y|ies)\\b")),
        // "error propagation", "error magnitude", "maximum observed error".
        ERROR("error", Pattern.compile(
                "(?i)\\berror\\s+(?:propagation|magnitude|of\\b|in\\b)"
                        + "|\\b(?:maximum|max|observed|measured)\\s+(?:absolute\\s+)?error\\b")),
        // Bare ±N claims (e.g. "yielded ±1.5° at the crossover"). The trigger
        // here is the ±-prefixed number itself; the call_N check decides
        // whether the LLM tied the bound to a real tool result.
        PLUS_MINUS("plus_minus", Pattern.compile(
                "±\\s*[0-9]+(?:\\.[0-9]+)?"));

        final String label;
        final Pattern pattern;

        Trigger(String label, Pattern pattern) {
            this.label = label;
            this.pattern = pattern;
        }
    }

    /** Sentence boundary: period/!/? + whitespace + capital or EOL.
     *  Negative-lookbehind for common abbreviations would help; not done
     *  here because the corpus prose rarely uses "e.g." style without a
     *  trailing comma, and over-engineering the splitter risks merging
     *  sentences and hiding violations. */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[.!?])\\s+(?=[A-Z(\\[]|$)");

    private static final Pattern CALL_REF = Pattern.compile("\\[call_\\d+\\]");

    /**
     * 2026-06-06 review rec #2 — absence/meta exemption. A sentence that
     * asserts the ABSENCE of an analysis ("no uncertainty analysis is
     * therefore reported", "no standard error was produced") has, by its
     * nature, no tool call to cite — flagging it penalizes exactly the
     * honest hedging the pipeline wants more of (P-b72c-214's strike).
     *
     * <p>Exempt iff the sentence carries a negation cue AND no digit:
     * a digit means a concrete value is being claimed (the original
     * laundering target, e.g. "error no greater than 0.5"), so any
     * numeric sentence keeps the full check. PLUS_MINUS triggers are
     * structurally unaffected — their trigger is itself a number.
     */
    private static final Pattern NEGATION_CUE = Pattern.compile(
            "(?i)\\b(?:no|not|none|never|without|absen(?:t|ce)|lack(?:s|ing)?|"
                    + "unavailable|unreported|unquantified)\\b");
    private static final Pattern ANY_DIGIT = Pattern.compile("[0-9]");

    private static boolean isAbsenceMeta(String sentence) {
        return NEGATION_CUE.matcher(sentence).find()
                && !ANY_DIGIT.matcher(sentence).find();
    }

    /** First ~60 chars of the flagged sentence, whitespace-collapsed, for the reason text. */
    private static String snippet(String sentence) {
        String collapsed = sentence.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 60 ? collapsed : collapsed.substring(0, 60) + "…";
    }

    /**
     * Returns a list of {@code unsourced_claim:<type>} reasons, one per
     * trigger fired without a same-sentence {@code [call_N]} match. Empty
     * map / null inputs short-circuit to empty. Multiple offending claims
     * fold into one reason per trigger type so the call-path stamp stays
     * compact.
     */
    public static List<String> validate(Map<Integer, String> findingsBlocks,
                                        String conclusion) {
        List<String> reasons = new ArrayList<>();
        StringBuilder bag = new StringBuilder();
        if (findingsBlocks != null) {
            for (String block : new java.util.TreeMap<>(findingsBlocks).values()) {
                if (block == null || block.isBlank()) continue;
                bag.append(block).append("\n\n");
            }
        }
        if (conclusion != null && !conclusion.isBlank()) {
            bag.append(conclusion);
        }
        String prose = bag.toString();
        if (prose.isBlank()) return reasons;

        String[] sentences = SENTENCE_SPLIT.split(prose);
        java.util.EnumMap<Trigger, Integer> firstHitSentence =
                new java.util.EnumMap<>(Trigger.class);
        for (int i = 0; i < sentences.length; i++) {
            String s = sentences[i];
            if (s.isBlank()) continue;
            boolean hasCallRef = CALL_REF.matcher(s).find();
            boolean absenceMeta = isAbsenceMeta(s);   // rec #2 exemption
            for (Trigger t : Trigger.values()) {
                if (firstHitSentence.containsKey(t)) continue;  // already flagged
                Matcher tm = t.pattern.matcher(s);
                if (tm.find() && !hasCallRef && !absenceMeta) {
                    firstHitSentence.put(t, i);
                }
            }
        }
        for (Map.Entry<Trigger, Integer> e : firstHitSentence.entrySet()) {
            // 2026-06-06 review rec #2 — carry a short snippet of the flagged
            // sentence. A downgrade can rewrite Findings before publication
            // (P-b72c-219), leaving the strike unclassifiable from the
            // persisted artifact; the snippet makes every firing auditable
            // from the log alone. The call-path stamp builder only reads the
            // claim-type token, so the suffix is stamp-safe.
            reasons.add(String.format(
                    "unsourced_claim:%s — first occurrence in sentence %d "
                            + "of Findings/Conclusion has no paired [call_N] reference. "
                            + "Sentence: \"%s\"",
                    e.getKey().label, e.getValue() + 1,
                    snippet(sentences[e.getValue()])));
        }
        return reasons;
    }

    /** Single-string overload for callers that don't have the block map. */
    public static List<String> validate(String findings, String conclusion) {
        java.util.Map<Integer, String> single =
                findings == null ? java.util.Map.of() : java.util.Map.of(1, findings);
        return validate(single, conclusion);
    }
}
