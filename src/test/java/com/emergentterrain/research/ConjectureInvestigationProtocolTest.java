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
    void cramerPlaceholderRoutesToLockedConstantScaleLadderInsteadOfInvalidAffineCall() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "cramer_gap_ratio",
                "_constant_", 0.0, 0.36);
        ScalarOpenness.MeasurementSpec spec = ScalarOpenness.measurementFrame("cramer_gap_ratio");

        ConjectureInvestigationProtocol.Allocation allocation =
                ConjectureInvestigationProtocol.allocate(conjecture, spec);

        assertThat(allocation.route()).isEqualTo(ConjectureInvestigationProtocol.Route.CONSTANT_LIMIT);
        assertThat(ConstantLimitPreRegistration.isLocked(allocation.registration())).isTrue();
        assertThat(ConstantLimitPreRegistration.exactTestDirective(allocation.registration()))
                .contains("verify_constant").contains("lhs=\"cramer_gap_ratio\"")
                .contains("scale_ladder=true").contains("window_size=5000").contains("window_size=10000")
                .doesNotContain("_constant_");
        assertThat(ConstantLimitPreRegistration.hasExactEvidence(allocation.registration(), List.of(
                new ToolInvocation("verify_constant", "{\"lhs\":\"cramer_gap_ratio\",\"scale_ladder\":true,"
                        + "\"window_size\":5000,\"num_windows\":5}",
                        "verify_constant scale-ladder: cramer_gap_ratio measured across 5 scales.", 0))))
                .isFalse();
        assertThat(ConstantLimitPreRegistration.hasExactEvidence(allocation.registration(), List.of(
                new ToolInvocation("verify_constant", "{\"lhs\":\"cramer_gap_ratio\",\"scale_ladder\":true,"
                        + "\"window_size\":5000,\"num_windows\":5}",
                        "verify_constant scale-ladder: cramer_gap_ratio measured across 5 scales.", 0),
                new ToolInvocation("verify_constant", "{\"lhs\":\"cramer_gap_ratio\",\"scale_ladder\":true,"
                        + "\"window_size\":10000,\"num_windows\":5}",
                        "verify_constant scale-ladder: cramer_gap_ratio measured across 5 scales.", 0))))
                .isTrue();
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
}
