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
                .startsWith("locked-constant-limit:v3;")
                .contains("known_limit=mean_divisor_excess:local-window:v1")
                .contains("target=1.1544313298030657");
        assertThat(ConstantLimitPreRegistration.exactTestDirective(allocation.registration()))
                .contains("target=1.1544313298030657");
    }
}
