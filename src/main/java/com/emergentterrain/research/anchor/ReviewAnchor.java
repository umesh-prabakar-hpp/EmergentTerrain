package com.emergentterrain.research.anchor;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 32 Wave 3 (32.4) — the absolute review head, pure parts: prompt
 * construction and verdict parsing. The AI-Scientist lesson adopted here is
 * the *methodology*, not the rubric: an absolute decision head is only
 * trustworthy once calibrated against labeled human verdicts
 * ({@code research/review-verdicts.csv}, harvested from every
 * {@code /paper-review} session) — so this head ships advisory-only and OFF,
 * and flips only when {@link ReviewAnchorCalibration} clears the bar
 * (binary accuracy ≥ 0.80, Reject-recall ≥ 0.90 on the held-out split).
 *
 * <p>The rubric IS the operator's own review rubric (the {@code /paper-review}
 * skill's verdict definitions), so the head predicts the operator's labels —
 * the only ground truth ET has.
 */
public final class ReviewAnchor {

    private ReviewAnchor() {}

    /** The four operator verdict classes, plus the binary roll-up. */
    public enum Verdict {
        ACCEPTABLE, MINOR_REVISION, MAJOR_REVISION, REJECT;

        /** Binary accept: the operator ships Acceptable/Minor as publishable. */
        public boolean accept() {
            return this == ACCEPTABLE || this == MINOR_REVISION;
        }
    }

    public record AnchorVerdict(Verdict verdict, String rationale) {}

    /**
     * The operator rubric, verbatim from the /paper-review skill. Kept as one
     * block so a rubric change is one diff reviewed against the skill text.
     */
    static final String RUBRIC = """
            Assign exactly one verdict:
            - ACCEPTABLE — math right, text complete, coherent.
            - MINOR_REVISION — math right, but truncated, weakly presented, or boilerplate.
            - MAJOR_REVISION — partial issues; needs structural fixes (wrong-target trace, \
            conclusion contradicting findings, degenerate premise).
            - REJECT — internally contradictory, unphysical/unmathematical, near-clone of an \
            earlier paper, broken computation, or pseudo-scientific premise.
            Judge the MATHEMATICAL CONTENT against the executed tool-call trace in ## Methods: \
            numbers in prose must trace to tool output; a SUPPORTED claim needs adversarial \
            coverage; honest NULL_RESULT papers with clean traces are ACCEPTABLE, not failures.""";

    public static String buildPrompt(String paperMarkdown) {
        return "You are the research-room operator reviewing one autonomously generated "
                + "mathematics paper against your own historical rubric.\n\n" + RUBRIC
                + "\n\nRespond with EXACTLY one line of JSON and nothing else:\n"
                + "{\"verdict\":\"ACCEPTABLE|MINOR_REVISION|MAJOR_REVISION|REJECT\","
                + "\"rationale\":\"<one sentence, <=140 chars>\"}\n\n"
                + "PAPER:\n" + paperMarkdown;
    }

    private static final Pattern VERDICT_JSON = Pattern.compile(
            "\"verdict\"\\s*:\\s*\"(ACCEPTABLE|MINOR_REVISION|MAJOR_REVISION|REJECT)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RATIONALE_JSON = Pattern.compile(
            "\"rationale\"\\s*:\\s*\"([^\"]{0,300})\"");

    /**
     * Lenient parse: JSON field first, bare-keyword fallback (models under
     * lenient recovery sometimes emit prose around the JSON). Empty when no
     * verdict keyword is recoverable — the caller treats that as no-anchor,
     * never as a default class.
     */
    public static Optional<AnchorVerdict> parse(String response) {
        if (response == null || response.isBlank()) return Optional.empty();
        Matcher m = VERDICT_JSON.matcher(response);
        String verdictToken = null;
        if (m.find()) {
            verdictToken = m.group(1).toUpperCase(Locale.ROOT);
        } else {
            // Bare-keyword fallback, most-severe-first so a rationale that
            // MENTIONS a lesser class while concluding REJECT parses as REJECT.
            for (String kw : new String[]{"REJECT", "MAJOR_REVISION", "MINOR_REVISION", "ACCEPTABLE"}) {
                if (response.toUpperCase(Locale.ROOT).contains(kw)) {
                    verdictToken = kw;
                    break;
                }
            }
        }
        if (verdictToken == null) return Optional.empty();
        Matcher r = RATIONALE_JSON.matcher(response);
        String rationale = r.find() ? r.group(1) : "";
        return Optional.of(new AnchorVerdict(Verdict.valueOf(verdictToken), rationale));
    }
}
