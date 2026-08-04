package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 12 D6 — flag papers whose Conclusion uses the canned "no prior
 * theoretical bound" sentence on a topic with a known textbook bound.
 *
 * <p>REVIEW_REPORT refresh #3 §1 rec #5 named five Reject papers where the
 * closing-prose template defaulted to *"…no prior theoretical bound
 * constrains the expected value in this region"* — P-a328-416 (Mertens),
 * P-422 (Beer-Lambert), P-423 (Stefan-Boltzmann), P-427 (Doppler), P-432
 * (Hall). All five topics have textbook closed-form solutions. The sentence
 * is harmless boilerplate on genuinely-open problems (Twin Prime, Goldbach,
 * Collatz) and a quality-killer on textbook problems.
 *
 * <p>This validator pairs the boilerplate-detection regex with the
 * {@link TextbookBoundRegistry} so the check only fires on topics whose
 * bounds are well-known. Returns a list of textual reasons mirroring the
 * other B-series validators ({@link NumericalConsistencyValidator},
 * {@link MagnitudeRangeValidator}, {@link TopicSpecificFindingsValidator}).
 * Empty list means no inconsistency.
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class ClosingProseValidator {

    private ClosingProseValidator() {}

    /**
     * The canned sentence the closing-prose template emits when it cannot
     * identify a textbook reference. Matched case-insensitive, whitespace-
     * flexible. The trailing "in this region" is optional because four of
     * the five named papers carry the longer form and one (P-a328-427)
     * truncated to the shorter form mid-sanitisation.
     */
    private static final Pattern NO_PRIOR_BOUND_BOILERPLATE = Pattern.compile(
            "(?i)no\\s+prior\\s+theoretical\\s+bound\\s+constrains\\s+the\\s+"
                    + "expected\\s+value(?:\\s+in\\s+this\\s+region)?");

    /**
     * Returns a single-entry list when the conclusion fires the boilerplate
     * AND the {@code (domain, topic)} is in {@link TextbookBoundRegistry};
     * empty list otherwise. Null / blank inputs return empty (callers handle
     * null elsewhere via the template-fallback path).
     */
    public static List<String> validate(String domain, String topic, String conclusion) {
        List<String> reasons = new ArrayList<>();
        if (conclusion == null || conclusion.isBlank()) return reasons;
        if (!NO_PRIOR_BOUND_BOILERPLATE.matcher(conclusion).find()) return reasons;
        if (!TextbookBoundRegistry.hasKnownBound(domain, topic)) return reasons;
        reasons.add(String.format(
                "Conclusion claims 'no prior theoretical bound constrains the expected value' "
                        + "on a (domain=%s, topic=%s) pair with a known textbook bound "
                        + "(Phase 12 D6 — boilerplate fires on a topic with a closed-form solution).",
                domain, topic));
        return reasons;
    }
}
