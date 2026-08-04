package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 2 — a stall forecast from {@link StallPredictor}.
 *
 * @param valid               whether a fit was possible (≥2 points, increasing cost)
 * @param exponent            fitted {@code p} in {@code cost ≈ a·x^p}
 * @param coefficient         fitted {@code a}
 * @param computeFrontierX    {@code x_c} — scale where cost crosses the budget
 * @param stallsBeforeScience {@code x_c < x_s} (compute runs out before the answer);
 *                            the Phase-3 pivot trigger
 * @param basis               human-readable summary (logged)
 */
public record StallPrediction(
        boolean valid,
        double exponent,
        double coefficient,
        double computeFrontierX,
        boolean stallsBeforeScience,
        String basis) {

    public static StallPrediction invalid(String why) {
        return new StallPrediction(false, 0, 0, 0, false, why);
    }
}
