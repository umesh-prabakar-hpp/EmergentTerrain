package com.emergentterrain.research;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 5 B4 — flag papers whose Hypothesis text mixes domain markers from
 * a domain other than the one the engine assigned to the paper, except where
 * a legitimate bridge has been documented.
 *
 * <p>5 unfixed paper-ID clusters in the May 2026 corpus had no physical
 * mechanism linking the math and materials/physics halves of their hypothesis:
 * <ul>
 *   <li>P-a328-1, 23, 44, 50, 61 — prime gaps → CTE / thermal stress</li>
 *   <li>P-a328-6, 41 — happy numbers → packing efficiency</li>
 *   <li>P-a328-7 — Van der Waals → composite stress</li>
 *   <li>P-a328-16 — pendulum-breakdown angle → Collatz depth</li>
 *   <li>P-a328-18 — divisor sums hypothesised as materials</li>
 * </ul>
 *
 * <p>The gate <b>intentionally skips</b> the {@code INTERDISCIPLINARY} domain.
 * Those topics are by design cross-domain ({@code Prime-Gap Defect Thermal
 * Loading} etc.); the engine generates them on purpose. The gate's job is to
 * catch the bot drifting out of its declared {@code MATHEMATICS} /
 * {@code MATERIALS} / {@code PHYSICS} lane, not to second-guess the engine.
 *
 * <p>Bridges allowlist starts empty — populate it only when a legitimate
 * cross-domain finding emerges from real experimentation.
 */
public final class CrossDomainHypothesisGate {

    private CrossDomainHypothesisGate() {}

    /** Markers that strongly indicate the hypothesis is talking about MATHEMATICS. */
    private static final List<Pattern> MATH_MARKERS = compile(
            "\\bprime\\s+gap\\b", "\\btwin\\s+prime\\b", "\\bprime\\s+constellation\\b",
            "\\bGoldbach\\b", "\\bCollatz\\b", "\\bhappy\\s+number\\b",
            "\\baliquot\\b", "\\bdivisor\\s+sum\\b", "\\bsigma\\s*\\(\\s*n\\s*\\)");

    /** Markers that strongly indicate MATERIALS or PHYSICS. */
    private static final List<Pattern> MAT_PHYS_MARKERS = compile(
            "\\bthermal\\s+stress\\b", "\\bfibre\\b", "\\bfiber\\b",
            "\\bcomposite\\b", "\\bCTE\\b",
            "\\bRLC\\b", "\\binductance\\b", "\\bcapacitance\\b",
            "\\bpendulum\\b", "\\bprojectile\\s+drag\\b",
            "\\bVan\\s+der\\s+Waals\\b", "\\bdiffraction\\b",
            "\\bArrhenius\\b", "\\bReuss\\b", "\\byield\\s+strength\\b");

    /**
     * Set of (declaredDomain, otherDomainMarker) bridges that are explicitly
     * permitted. Starts empty. Future commits can add documented bridges here
     * as their physical mechanisms are established.
     */
    static final Set<String> KNOWN_BRIDGES = Set.of();

    /**
     * Returns a textual mismatch reason if the hypothesis text contains markers
     * from a domain other than {@code declaredDomain}, except for the
     * INTERDISCIPLINARY domain (intentionally cross-domain) and the bridges
     * allowlist.
     */
    public static Optional<String> validate(String declaredDomain, String hypothesis) {
        if (declaredDomain == null || hypothesis == null) return Optional.empty();
        if ("INTERDISCIPLINARY".equalsIgnoreCase(declaredDomain)) return Optional.empty();
        // Phase 7 FX.6 — the post-FX domains (BIOLOGY, CHEMISTRY, ARCHITECTURE_*)
        // are orthogonal to the math/physics/materials marker sets this gate
        // watches for; their natural vocabulary doesn't intersect the marker
        // patterns. Skip the gate for these — false positives outnumber genuine
        // drift catches in those domains.
        String upper = declaredDomain.toUpperCase();
        if ("BIOLOGY".equals(upper) || "CHEMISTRY".equals(upper)
                || upper.startsWith("ARCHITECTURE_")) {
            return Optional.empty();
        }
        if ("MATHEMATICS".equalsIgnoreCase(declaredDomain)) {
            return firstMatchingMarker(hypothesis, MAT_PHYS_MARKERS).map(matched -> {
                if (KNOWN_BRIDGES.contains("MATHEMATICS::" + matched)) return null;
                return String.format(
                        "MATHEMATICS hypothesis contains materials/physics marker '%s' with no documented bridge.",
                        matched);
            }).filter(s -> s != null);
        }
        if ("MATERIALS".equalsIgnoreCase(declaredDomain) || "PHYSICS".equalsIgnoreCase(declaredDomain)) {
            return firstMatchingMarker(hypothesis, MATH_MARKERS).map(matched -> {
                String key = declaredDomain.toUpperCase() + "::" + matched;
                if (KNOWN_BRIDGES.contains(key)) return null;
                return String.format(
                        "%s hypothesis contains math marker '%s' with no documented bridge.",
                        declaredDomain.toUpperCase(), matched);
            }).filter(s -> s != null);
        }
        return Optional.empty();
    }

    private static Optional<String> firstMatchingMarker(String text, List<Pattern> markers) {
        for (Pattern p : markers) {
            var m = p.matcher(text);
            if (m.find()) return Optional.of(m.group());
        }
        return Optional.empty();
    }

    private static List<Pattern> compile(String... patterns) {
        return java.util.Arrays.stream(patterns)
                .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
