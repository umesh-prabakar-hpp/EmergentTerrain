package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.MathInvariants;
import com.emergentterrain.research.conjecture.ScalarOpenness;

/** Deterministic allocation-time choice of an executable conjecture-study instrument. */
public final class ConjectureInvestigationProtocol {
    public enum Route { AFFINE_BOUND, CONSTANT_LIMIT, REGISTERED_MEASUREMENT, REJECT }

    public record Allocation(Route route, PreRegistration registration, String reason) {
        public boolean allocatable() { return route != Route.REJECT; }
    }

    private ConjectureInvestigationProtocol() {}

    public static Allocation allocate(Conjecture conjecture, ScalarOpenness.MeasurementSpec measurementSpec) {
        if (conjecture == null || !known(conjecture.lhs())) {
            return new Allocation(Route.REJECT, null, "unknown_lhs_scalar");
        }
        if (measurementSpec != null) {
            if (measurementSpec.frame() == ScalarOpenness.MeasurementFrame.CONSTANT) {
                PreRegistration registration = ConstantLimitPreRegistration.from(conjecture, measurementSpec);
                return new Allocation(Route.CONSTANT_LIMIT, registration, "registered_constant_limit");
            }
            if (measurementSpec.frame() == ScalarOpenness.MeasurementFrame.ENVELOPE) {
                return new Allocation(Route.REGISTERED_MEASUREMENT,
                        CramerGapEnvelopePreRegistration.from(conjecture), "registered_finite_envelope_measurement");
            }
            if ("mertens_delta".equals(conjecture.lhs())) {
                return new Allocation(Route.REGISTERED_MEASUREMENT,
                        MertensEnvelopePreRegistration.from(conjecture), "registered_mertens_window_envelope_measurement");
            }
            if (measurementSpec.frame() == ScalarOpenness.MeasurementFrame.OSCILLATION) {
                return new Allocation(Route.REGISTERED_MEASUREMENT,
                        OscillationEnvelopePreRegistration.from(conjecture),
                        "registered_oscillation_envelope_measurement");
            }
            if (measurementSpec.frame() == ScalarOpenness.MeasurementFrame.GROWTH) {
                PreRegistration registration = growthRegistration(conjecture);
                if (registration == null) {
                    // A generic growth label is not a computational contract.  In particular,
                    // mean_omega and mean_Omega have a textbook log-log leading order but no
                    // locked fit/holdout protocol yet.  Letting them through would create an
                    // artifact with a literal null pre-registration that can never be promoted.
                    return new Allocation(Route.REJECT, null, "unregistered_growth_measurement");
                }
                return new Allocation(Route.REGISTERED_MEASUREMENT, registration,
                        "registered_growth_measurement");
            }
            return new Allocation(Route.REJECT, null,
                    "unregistered_" + measurementSpec.frame().name().toLowerCase() + "_measurement");
        }
        if (!known(conjecture.rhs()) || (conjecture.rhs2() != null && !known(conjecture.rhs2()))
                || !Double.isFinite(conjecture.k()) || !Double.isFinite(conjecture.c())
                || (conjecture.rhs2() != null && !Double.isFinite(conjecture.k2()))) {
            return new Allocation(Route.REJECT, null, "non_executable_affine_relation");
        }
        return new Allocation(Route.AFFINE_BOUND, ConjecturePreRegistration.from(conjecture),
                "executable_affine_relation");
    }

    private static boolean known(String scalar) {
        return scalar != null && MathInvariants.vocabulary().contains(scalar);
    }

    private static PreRegistration growthRegistration(Conjecture conjecture) {
        PreRegistration registration = SecondOrderPivotPreRegistration.from(conjecture);
        if (registration == null) registration = MaxDivisorCountMeasurementPreRegistration.from(conjecture);
        if (registration == null) registration = MaxPrimeGapMultiScalePreRegistration.from(conjecture);
        return registration;
    }
}
