package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 13 E6 — flag papers whose Findings prose reports a unit-anchored
 * numeric without any uncertainty disclosure.
 *
 * <p>REVIEW_REPORT refresh #1's panel-recommendation pattern audit found
 * ~38 reviewer comments asking for some form of uncertainty / error bound
 * / sensitivity quantification across ~18 of 23 published papers (~80%
 * of the corpus). It's the single most-repeated panel ask. Bots almost
 * never put ± uncertainties on reported values even when the engine
 * computes σ over a sweep — the narrator drops the variance information.
 *
 * <p>The validator enforces a minimum uncertainty discipline: when the
 * Findings prose reports a unit-anchored numeric, the prose must also
 * carry at least one uncertainty indicator. Accepted forms:
 *
 * <ul>
 *   <li>{@code ± N} — explicit numeric uncertainty.</li>
 *   <li>{@code exact}, {@code exactly}, {@code closed form},
 *       {@code [exact closed form]} — qualitative "no uncertainty"
 *       acknowledgement. Appropriate for closed-form recoveries.</li>
 *   <li>{@code to within X%} — alternate bound syntax.</li>
 * </ul>
 *
 * <p>The validator deliberately rejects {@code ≈} / "approximately" /
 * "roughly" alone — those are hedges, not bounds. A paper claiming
 * "max σ_y ≈ 2377 MPa" without any quantification still flags.
 *
 * <p>Stateless and pure; safe to call from any thread.
 */
public final class UncertaintyDisclosureValidator {

    private UncertaintyDisclosureValidator() {}

    /**
     * Unit-anchored numeric — either an integer or a decimal followed by
     * a physical unit. Reuses the unit set from Phase 12 D2d's
     * {@link NumericalConsistencyValidator} {@code SALIENT_INTEGER_WITH_UNIT}
     * patterns so the two validators share their notion of "salient".
     *
     * <p>Excludes pure scientific notation without a unit (e.g. {@code 1.2e-3}
     * alone) because those typically appear in computation results and the
     * caller handles them elsewhere. Includes scientific notation with a
     * unit (e.g. {@code 7.80e-22 J}).
     */
    private static final Pattern UNIT_ANCHORED_NUMERIC = Pattern.compile(
            "(?<![\\d.])(-?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"
                    + "\\s*"
                    + "(MPa·µm\\^½|MPa·µm\\^1/2|MPa|GPa|Pa|kJ/mol|kJ|J/mol|J|K|°C|°|"
                    + "nm|µm|um|mm|cm|km|"
                    + "Hz|kHz|MHz|GHz|eV|keV|MeV|"
                    + "m/s|kg|kg/m\\^3|s|rpm|%)"
                    + "(?![A-Za-z])");

    /**
     * Uncertainty-disclosure indicators. Any one of these in the Findings
     * prose is enough to satisfy the discipline:
     *
     * <ul>
     *   <li>{@code ±} followed by a numeric (the canonical quantitative form).</li>
     *   <li>The literal phrase {@code [exact closed form]}.</li>
     *   <li>The standalone words {@code exact}, {@code exactly}, or the
     *       phrase {@code closed form} / {@code closed-form}.</li>
     *   <li>{@code to within} followed by a percentage or numeric value.</li>
     * </ul>
     *
     * <p>The check is case-insensitive. Word-boundary anchors prevent
     * matching {@code exactly} inside e.g. {@code inexactly} (impossible in
     * practice but cheap to guard).
     */
    private static final Pattern UNCERTAINTY_INDICATOR = Pattern.compile(
            "(?i)("
                    + "±\\s*[0-9]+(?:\\.[0-9]+)?"      // ± numeric
                    + "|\\[\\s*exact\\s+closed\\s+form\\s*\\]"
                    + "|\\bexact(?:ly)?\\b"
                    + "|\\bclosed[\\s-]form\\b"
                    + "|\\bto\\s+within\\s+[0-9]"
                    + ")");

    /**
     * Returns a single-entry list when the findings prose contains at
     * least one unit-anchored numeric AND zero uncertainty indicators;
     * empty list otherwise. Null / blank inputs return empty (callers
     * handle null elsewhere via the template-fallback path).
     *
     * <p>Per-numeric flagging would be too aggressive — many papers
     * carry multiple values from the same sweep and only one needs the
     * ± clause to satisfy the discipline (since the rest are samples of
     * the same uncertainty). The validator flags the paper-as-a-whole
     * for missing disclosure, not each individual numeric.
     */
    public static List<String> validate(String findings) {
        List<String> reasons = new ArrayList<>();
        if (findings == null || findings.isBlank()) return reasons;
        if (!UNIT_ANCHORED_NUMERIC.matcher(findings).find()) return reasons;
        if (UNCERTAINTY_INDICATOR.matcher(findings).find()) return reasons;
        reasons.add(
                "Findings cites unit-anchored numerics with no uncertainty disclosure "
                        + "(Phase 13 E6). Accepted forms: '± N' for quantitative bounds, "
                        + "'exact' / '[exact closed form]' for closed-form recoveries, "
                        + "or 'to within X%' for alternate bound syntax.");
        return reasons;
    }

    /**
     * Structured-findings overload. Operates on the concatenation of every
     * block's prose — the single-paper uncertainty discipline is satisfied
     * by ANY indicator appearing in any block.
     */
    public static List<String> validate(java.util.Map<Integer, String> findingsMap) {
        if (findingsMap == null || findingsMap.isEmpty()) return List.of();
        return validate(MagnitudeRangeValidator.joinBlocks(findingsMap));
    }
}
