package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ScalarOpenness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConjectureInvestigationProtocolTest {

    @Test
    void validAffineRelationRetainsLockedVerifyConjectureRoute() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "max_prime_gap",
                "log_sq_window_start", 0.42, 0.0);

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture, null);

        assertThat(allocation.route()).isEqualTo(ConjectureInvestigationProtocol.Route.AFFINE_BOUND);
        assertThat(ConjecturePreRegistration.isLocked(allocation.registration())).isTrue();
        assertThat(ConjecturePreRegistration.exactTestDirective(allocation.registration()))
                .contains("verify_conjecture").contains("rhs=\"log_sq_window_start\"");
    }

    @Test
    void cramerPlaceholderRoutesToLockedFiniteEnvelopeInsteadOfInvalidConstantCall() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "cramer_gap_ratio",
                "_constant_", 0.0, 0.36);
        ScalarOpenness.MeasurementSpec spec = ScalarOpenness.measurementFrame("cramer_gap_ratio");

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture, spec);

        assertThat(allocation.route()).isEqualTo(ConjectureInvestigationProtocol.Route.REGISTERED_MEASUREMENT);
        assertThat(CramerGapEnvelopePreRegistration.isLocked(allocation.registration())).isTrue();
        assertThat(CramerGapEnvelopePreRegistration.exactTestDirective(allocation.registration()))
                .contains("verify_constant").contains("scale_ladder=false")
                .doesNotContain("_constant_");
    }

    @Test
    void mertensWindowIncrementRoutesToLockedEnvelopeInsteadOfAffineFrontierBound() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "mertens_delta",
                "omega_excess_density", 2.0, 17.6208);

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture,
                        ScalarOpenness.measurementFrame("mertens_delta"));

        assertThat(allocation.route()).isEqualTo(ConjectureInvestigationProtocol.Route.REGISTERED_MEASUREMENT);
        assertThat(allocation.reason()).isEqualTo("registered_mertens_window_envelope_measurement");
        assertThat(MertensEnvelopePreRegistration.isLocked(allocation.registration())).isTrue();
        assertThat(MertensEnvelopePreRegistration.exactTestDirective(allocation.registration()))
                .contains("verify_oscillation").contains("scale_rhs=\"sqrt_window_size\"")
                .contains("scale_ladder=false").doesNotContain("omega_excess_density");
    }

    @Test
    void unknownAffineScalarIsRejectedBeforeDraftAllocation() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "max_prime_gap",
                "_constant_", 0.0, 0.36);

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture, null);

        assertThat(allocation.allocatable()).isFalse();
        assertThat(allocation.reason()).isEqualTo("non_executable_affine_relation");
    }

    @Test
    void divisorWindowLimitRegistrationUsesCanonicalLocalTarget() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "mean_divisor_excess",
                "_constant_", 0.0, 0.0);
        ScalarOpenness.MeasurementSpec deliberatelyWrongSpec = new ScalarOpenness.MeasurementSpec(
                ScalarOpenness.MeasurementFrame.CONSTANT, null, 0.1544313298030657,
                ScalarOpenness.Convergence.FAST);

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture, deliberatelyWrongSpec);

        assertThat(allocation.route()).isEqualTo(ConjectureInvestigationProtocol.Route.CONSTANT_LIMIT);
        assertThat(allocation.registration().mappingNotes())
                .startsWith("locked-constant-limit:v5;")
                .contains("known_limit=mean_divisor_excess:local-window:v1")
                .contains("target=1.1544313298030657")
                .contains("primary_ladder_start=100000")
                .contains("location_holdout_start=1500000000")
                .contains("width_holdout_start=1500100000")
                .contains("deadline_aware=true");
        assertThat(ConstantLimitPreRegistration.exactTestDirective(allocation.registration()))
                .contains("target=1.1544313298030657")
                .contains("ladder_start=100000")
                .contains("deadline_aware=true")
                .contains("window_start=1500000000")
                .contains("window_start=1500100000");
    }

    @Test
    void constantLimitEvidenceRequiresDeadlineAwareLadderAndIndependentLocationAndWidthHoldouts() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "omega_Omega_gap",
                "_constant_", 0.0, 0.0);
        var registration = ConstantLimitPreRegistration.from(conjecture,
                ScalarOpenness.measurementFrame("omega_Omega_gap"));
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("verify_constant",
                        "{\"lhs\":\"omega_Omega_gap\",\"target\":0.77316,\"scale_ladder\":true,"
                                + "\"window_size\":5000,\"num_windows\":5,\"ladder_start\":100000,\"deadline_aware\":true}",
                        "verify_constant scale-ladder: omega_Omega_gap ladder_start=100000 measured across 5 scales.", 0),
                new ToolInvocation("verify_constant",
                        "{\"lhs\":\"omega_Omega_gap\",\"target\":0.77316,\"window_start\":1500000000,"
                                + "\"window_size\":5000,\"num_windows\":5}",
                        "verify_constant: omega_Omega_gap ~ 0.77316 over 5 window(s) of size 5000 starting at 1500000000.", 0),
                new ToolInvocation("verify_constant",
                        "{\"lhs\":\"omega_Omega_gap\",\"target\":0.77316,\"window_start\":1500100000,"
                                + "\"window_size\":10000,\"num_windows\":5}",
                        "verify_constant: omega_Omega_gap ~ 0.77316 over 5 window(s) of size 10000 starting at 1500100000.", 0));

        assertThat(ConstantLimitPreRegistration.hasExactEvidence(registration, trace)).isTrue();

        List<ToolInvocation> collapsed = List.of(trace.get(0), trace.get(1), new ToolInvocation("verify_constant",
                "{\"lhs\":\"omega_Omega_gap\",\"target\":0.77316,\"window_start\":1500000000,"
                        + "\"window_size\":10000,\"num_windows\":5}",
                "verify_constant: omega_Omega_gap ~ 0.77316 over 5 window(s) of size 10000 starting at 1500000000.", 0));
        assertThat(ConstantLimitPreRegistration.hasExactEvidence(registration, collapsed)).isFalse();
    }

    @Test
    void namedConjecturalConstantRequiresLockedLiteratureGrounding() {
        var registration = ConstantLimitPreRegistration.from(
                new Conjecture("MATHEMATICS", "twin_vs_prime_sq", "_constant_", 0.0, 0.0),
                ScalarOpenness.measurementFrame("twin_vs_prime_sq"));

        assertThat(ConstantLimitPreRegistration.requiresSourceGrounding(registration)).isTrue();
        assertThat(ConstantLimitPreRegistration.isEstablishedCalibration(registration)).isFalse();
        assertThat(ConstantLimitPreRegistration.literatureSubject(registration))
                .isEqualTo("Hardy-Littlewood twin prime constant");
    }
}
