package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConjecturePreRegistrationTest {

    @Test
    void exactEvidenceRequiresTheRegisteredTwoTermIntercept() {
        Conjecture conjecture = new Conjecture("MATHEMATICS", "twin_prime_density",
                "mean_divisor_excess", 1.997, -362.2958);
        conjecture.setRhs2("max_divisor_count");
        conjecture.setK2(2.0);
        PreRegistration registration = ConjecturePreRegistration.from(conjecture);

        ToolInvocation substitutedIntercept = new ToolInvocation("verify_conjecture", """
                {"lhs":"twin_prime_density","rhs":"mean_divisor_excess","k":1.997,
                 "rhs2":"max_divisor_count","k2":2,"c":-386.2461}
                """, "REFUTED across 20 windows", 0);
        ToolInvocation registeredRelation = new ToolInvocation("verify_conjecture", """
                {"lhs":"twin_prime_density","rhs":"mean_divisor_excess","k":1.997,
                 "rhs2":"max_divisor_count","k2":2,"c":-362.2958}
                """, "HELD across 20 windows", 0);

        assertThat(ConjecturePreRegistration.isLocked(registration)).isTrue();
        assertThat(ConjecturePreRegistration.hasExactEvidence(registration,
                List.of(substitutedIntercept))).isFalse();
        assertThat(ConjecturePreRegistration.hasExactEvidence(registration,
                List.of(substitutedIntercept, registeredRelation))).isTrue();
        assertThat(ConjecturePreRegistration.exactTestDirective(registration))
                .contains("c=-362.2958").contains("rhs2=\"max_divisor_count\"");
    }
}
