package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5 B2 — flag papers whose Findings contain magnitudes outside the
 * physically-reasonable range for their (domain, topic).
 *
 * <p>The May 2026 corpus had 7 papers in the "composite failure mode" cluster
 * all reporting longitudinal stresses of ~12 GPa — values an order of magnitude
 * above the bond limit of real polymer-matrix composites and well above the
 * fibre yield strength of 200 GPa·ε_y ≈ 2 GPa. P-a328-7 reported 7.8 GPa for
 * a VdW-driven composite stress. These are not "borderline interesting" —
 * they are physical impossibilities.
 *
 * <p>v1 hard-codes the bounds as a tiny table; the design doc anticipates
 * promotion to a {@code domain-magnitudes.yml} resource (and later a JPA
 * entity) once the table grows past ~30 entries. Today it has 4.
 *
 * <p>Each bound is a {@link Bound} record with a regex pattern that picks
 * the candidate magnitude out of the {@code findings} string, the unit
 * suffix that pins the regex to the right column, and the upper limit.
 */
public final class MagnitudeRangeValidator {

    private MagnitudeRangeValidator() {}

    /**
     * One physically-reasonable upper bound for a (domain, topic) magnitude.
     * If any matching value in the findings string exceeds {@link #upperLimit},
     * the paper is flagged.
     */
    private record Bound(String domain, String topic, Pattern pattern,
                          double upperLimit, String unit, String reason) {}

    /**
     * Hand-curated bound table. Each entry is anchored to a paper-ID cluster the
     * report names; the regex matches that cluster's emitted findings text.
     *
     * <p>Adding entries is safe — they only add new fail-fast checks. Removing
     * entries is also safe (turns the corresponding check into a no-op). The
     * regex must capture exactly one numeric group at index 1.
     */
    private static final List<Bound> BOUNDS = List.of(
            new Bound("MATERIALS", "Composite Failure Mode",
                    Pattern.compile("→([0-9]+(?:\\.[0-9]+)?)MPa"),
                    3000.0, "MPa",
                    "composite longitudinal stress > 3 GPa is unphysical for any "
                            + "real polymer-matrix composite"),
            new Bound("INTERDISCIPLINARY", "Van-der-Waals Composite Crossover",
                    Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*MPa"),
                    1500.0, "MPa",
                    "VdW-induced composite stress > 1.5 GPa exceeds any realistic "
                            + "real-gas loading regime"),
            new Bound("INTERDISCIPLINARY", "Prime-Gap Defect Thermal Loading",
                    Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*MPa"),
                    2000.0, "MPa",
                    "thermal-stress-from-prime-gap > 2 GPa is unphysical for any "
                            + "real defect periodicity"),
            // Arrhenius high-Ea low-T sanity: ratios > 12× indicate the simulator
            // returned a wildly non-physical Van't Hoff multiplier. (P-a328-19's
            // 1.18× is the opposite failure — too low; we don't bound below since
            // legitimate near-1× results exist.)
            new Bound("MATERIALS", "Arrhenius Rule Validity",
                    Pattern.compile("Ea=[0-9]+kJ→([0-9]+(?:\\.[0-9]+)?)×"),
                    12.0, "×",
                    "Van't Hoff +10K multiplier > 12× would require a non-physical "
                            + "Ea/T² regime; check the simulator before publishing")
    );

    /**
     * Returns a list of human-readable violation reasons for downstream
     * telemetry. Empty list = no violation.
     */
    public static List<String> validate(String domain, String topic, String findings) {
        List<String> reasons = new ArrayList<>();
        if (domain == null || topic == null || findings == null) return reasons;
        for (Bound b : BOUNDS) {
            if (!b.domain.equalsIgnoreCase(domain) || !b.topic.equalsIgnoreCase(topic)) continue;
            Matcher m = b.pattern.matcher(findings);
            while (m.find()) {
                double value;
                try {
                    value = Double.parseDouble(m.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value > b.upperLimit) {
                    reasons.add(String.format(
                            "%s (observed %.0f %s, bound %.0f %s): %s.",
                            topic, value, b.unit, b.upperLimit, b.unit, b.reason));
                    break;  // one violation per bound is enough
                }
            }
        }
        return reasons;
    }
}
