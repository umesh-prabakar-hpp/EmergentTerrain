package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecondOrderPivotPreRegistrationTest {

    @Test
    void meanDivisorCountGetsALockedDirichletErrorProtocol() {
        PreRegistration registration = SecondOrderPivotPreRegistration.from(
                new Conjecture("math", "mean_divisor_count", "prime_density", 0.0, 0.0));

        assertThat(SecondOrderPivotPreRegistration.isLocked(registration)).isTrue();
        assertThat(registration.computationalStudyPlan().hasSelectionSeparation()).isTrue();
        assertThat(SecondOrderPivotPreRegistration.exactTestDirective(registration))
                .contains("dirichlet_error").contains("dirichlet\",\"divisor")
                .contains("scale_rhs");
    }

    @Test
    void protocolRequiresLadderAndBothIndependentHoldouts() {
        PreRegistration registration = SecondOrderPivotPreRegistration.from(
                new Conjecture("math", "mean_divisor_count", "prime_density", 0.0, 0.0));
        List<ToolInvocation> complete = List.of(
                call("{\"lhs\":\"dirichlet_error\",\"scale_ladder\":true}",
                        "verify_oscillation scale-ladder: dirichlet_error measured across 5 scales."),
                call("{\"lhs\":\"dirichlet_error\",\"window_start\":900000000,\"window_size\":5000,\"num_windows\":20}",
                        "verify_oscillation: dirichlet_error signs=3"),
                call("{\"lhs\":\"dirichlet_error\",\"window_start\":1500000000,\"window_size\":10000,\"num_windows\":10}",
                        "verify_oscillation: dirichlet_error signs=4"));

        assertThat(SecondOrderPivotPreRegistration.hasExactEvidence(registration, complete)).isTrue();
        assertThat(SecondOrderPivotPreRegistration.hasExactEvidence(registration, complete.subList(0, 2))).isFalse();
        assertThat(SecondOrderPivotPreRegistration.publicationTitle(registration, complete,
                "Measurement of mean_divisor_count (growth law across scales)"))
                .isEqualTo("Measurement of dirichlet_error (finite-range envelope across scales)");
        assertThat(SecondOrderPivotPreRegistration.publicationTitle(registration, complete.subList(0, 2),
                "Measurement of mean_divisor_count (growth law across scales)"))
                .isEqualTo("Measurement of mean_divisor_count (growth law across scales)");
    }

    private static ToolInvocation call(String args, String result) {
        return new ToolInvocation("verify_oscillation", args, result, 0);
    }
}
