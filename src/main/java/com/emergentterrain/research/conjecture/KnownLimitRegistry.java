package com.emergentterrain.research.conjecture;

import java.util.Optional;

/**
 * Authoritative definitions for constant-limit scalars whose target is fixed by the instrument,
 * rather than selected by a narration model.
 *
 * <p>The distinction between a local window mean and a cumulative average is mathematically
 * material. In particular, {@code mean_divisor_excess} is a local-window observable:
 * {@code mean_window(d(n)) - ln(window_center)} tends to {@code 2 gamma}. The different
 * cumulative observable {@code D(x)/x - ln(x)} tends to {@code 2 gamma - 1}; it is not the
 * scalar currently exposed to {@code verify_constant}.
 */
public final class KnownLimitRegistry {
    /** Euler–Mascheroni constant, doubled at full double precision. */
    public static final double TWO_EULER_GAMMA = 1.1544313298030657;
    /** Accept the rounded registered target 1.15443, but not the cumulative target 2γ−1. */
    private static final double MEAN_DIVISOR_EXCESS_TARGET_TOLERANCE = 1.0e-5;
    /** Prime-power correction in the average difference Ω(n) − ω(n). */
    public static final double OMEGA_OMEGA_GAP_TARGET = 0.77316;
    private static final double OMEGA_OMEGA_GAP_TARGET_TOLERANCE = 1.0e-5;

    public record KnownLimit(String id, String scalar, String definition,
                             double target, double targetTolerance) {
        public boolean matches(Double suppliedTarget) {
            return suppliedTarget != null && Double.isFinite(suppliedTarget)
                    && Math.abs(suppliedTarget - target) <= targetTolerance;
        }
    }

    private static final KnownLimit MEAN_DIVISOR_EXCESS = new KnownLimit(
            "mean_divisor_excess:local-window:v1",
            "mean_divisor_excess",
            "mean_window(d(n)) - ln(window_center)",
            TWO_EULER_GAMMA,
            MEAN_DIVISOR_EXCESS_TARGET_TOLERANCE);

    private static final KnownLimit OMEGA_OMEGA_GAP = new KnownLimit(
            "omega_Omega_gap:prime-power-correction:v1",
            "omega_Omega_gap",
            "mean_window(Ω(n) − ω(n))",
            OMEGA_OMEGA_GAP_TARGET,
            OMEGA_OMEGA_GAP_TARGET_TOLERANCE);

    private KnownLimitRegistry() {}

    public static Optional<KnownLimit> find(String scalar) {
        if (MEAN_DIVISOR_EXCESS.scalar().equals(scalar)) return Optional.of(MEAN_DIVISOR_EXCESS);
        if (OMEGA_OMEGA_GAP.scalar().equals(scalar)) return Optional.of(OMEGA_OMEGA_GAP);
        return Optional.empty();
    }

    /** Null means the supplied target is allowed; an omitted target remains an exploratory estimate. */
    public static String targetValidationError(String scalar, Double suppliedTarget) {
        KnownLimit known = find(scalar).orElse(null);
        if (known == null || suppliedTarget == null) return null;
        if (known.matches(suppliedTarget)) return null;
        if (MEAN_DIVISOR_EXCESS.scalar().equals(known.scalar())) {
            return "scalar=" + known.scalar() + " is " + known.definition()
                    + " and has locked target=" + known.target()
                    + " (2*Euler-Mascheroni); target=" + suppliedTarget
                    + " is incompatible. The cumulative observable D(x)/x - ln(x), not this scalar, has limit 2*gamma-1.";
        }
        return "scalar=" + known.scalar() + " is " + known.definition()
                + " and has locked target=" + known.target()
                + "; target=" + suppliedTarget + " is incompatible.";
    }
}
