package com.emergentterrain.research.recognise;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 26 §26.4 — verifies that every {@code cite-recognised:*}
 * handle appearing in a paper's prose resolves to a real
 * {@link RecognitionEvidence} entry in the paper's audit chain.
 *
 * <h2>The contract</h2>
 *
 * The narration layer embeds citation handles in prose, e.g.:
 * <pre>{@code
 *   The refined coefficient 1.20206... is recognised as ζ(3)
 *   [cite-recognised:pslq:zeta(3)].
 * }</pre>
 *
 * This validator scans the paper text for every match of
 * {@link #HANDLE_PATTERN} and checks each handle against the
 * citation-handle set of a paper's evidence list. Any handle that
 * doesn't resolve is a hallucinated citation — the §26.6 acceptance
 * bar requires <em>zero</em> of these across the first 30 published
 * papers.
 *
 * <h2>What this doesn't check</h2>
 *
 * The validator does not re-verify that the engine result is
 * <em>correct</em> — that's the engine's job and the §26.6 offline
 * audit. The validator only ensures the citation handle isn't
 * fabricated. The two checks together form the trust chain:
 *
 * <ol>
 *   <li>Engine produced the result → {@link RecognitionEvidence}</li>
 *   <li>Narration cited the result → matching handle in prose</li>
 *   <li>Validator: handle in prose ↔ evidence entry (this class)</li>
 *   <li>Offline auditor: evidence entry is mathematically correct
 *       (§26.6, separate workflow)</li>
 * </ol>
 *
 * <p>Stateless and thread-safe.
 */
public final class RecognitionCitationValidator {

    /**
     * Matches any citation handle the recognise layer can emit. Three
     * engine namespaces ({@code oeis}, {@code pslq}, {@code lll}) and
     * an arbitrary identifier tail.
     *
     * <p>Examples that match:
     * <ul>
     *   <li>{@code cite-recognised:oeis:A000045}</li>
     *   <li>{@code cite-recognised:pslq:zeta(3)}</li>
     *   <li>{@code cite-recognised:lll:degree4:x^4_-_10*x^2_+_1}</li>
     * </ul>
     *
     * <p>The handle's tail uses the same sanitised charset as
     * {@code ConstantRecognitionService.sanitiseHandle}: alphanumerics
     * + underscore + plus/minus + caret + parentheses + asterisk +
     * slash + dot + colon. Whitespace and other punctuation are
     * stripped before the handle is emitted, so the validator's
     * regex is correspondingly strict.
     */
    public static final Pattern HANDLE_PATTERN =
            Pattern.compile("cite-recognised:(oeis|pslq|lll):[A-Za-z0-9_+\\-^()*/.:]+");

    private RecognitionCitationValidator() {}

    /**
     * Validate that every {@code cite-recognised:*} handle in
     * {@code paperText} matches a citation handle in {@code evidence}.
     *
     * @param paperText the concatenated prose of the paper
     *                  (hypothesis + findings + conclusion + discussion)
     * @param evidence  the paper's recognition-evidence list
     * @return a {@link ValidationOutcome} listing any hallucinated
     *         handles (handles in prose with no matching evidence row)
     *         and any unused evidence (engine results that didn't make
     *         it into prose — informational, not an error)
     */
    public static ValidationOutcome validate(String paperText,
                                              List<RecognitionEvidence> evidence) {
        if (paperText == null) paperText = "";
        if (evidence == null) evidence = List.of();

        Set<String> evidenceHandles = new HashSet<>();
        for (RecognitionEvidence e : evidence) {
            if (e != null && e.citationHandle() != null) {
                evidenceHandles.add(e.citationHandle());
            }
        }

        // Walk every match in the prose; sets handle the "cited
        // multiple times in prose" case naturally (one entry per unique
        // handle).
        Set<String> citedInProse = new HashSet<>();
        Matcher m = HANDLE_PATTERN.matcher(paperText);
        while (m.find()) {
            citedInProse.add(m.group());
        }

        List<String> hallucinated = new ArrayList<>();
        for (String cited : citedInProse) {
            if (!evidenceHandles.contains(cited)) {
                hallucinated.add(cited);
            }
        }
        List<String> unused = new ArrayList<>();
        for (String handle : evidenceHandles) {
            if (!citedInProse.contains(handle)) {
                unused.add(handle);
            }
        }
        return new ValidationOutcome(
                hallucinated.isEmpty(),
                List.copyOf(hallucinated),
                List.copyOf(unused));
    }

    /**
     * @param ok           true when no hallucinated handles found.
     *                     <em>This is the §26.6 acceptance-bar signal.</em>
     * @param hallucinated handles cited in prose with no matching
     *                     evidence row. Any non-empty list here is a
     *                     P0 — the paper claims a recognition result
     *                     that didn't happen.
     * @param unused       handles in evidence that weren't cited in
     *                     prose. Informational only — the engine
     *                     produced a result but the narrator chose
     *                     not to surface it, which is fine.
     */
    public record ValidationOutcome(
            boolean ok,
            List<String> hallucinated,
            List<String> unused) {}
}
