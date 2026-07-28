package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDefinitionAvailabilityValidatorTest {

    @Test
    void recordsContradictionWhenProseDeniesALockedWindowDefinition() {
        PreRegistration registration = new PreRegistration("h", "p", "f",
                "locked-conjecture-test:v1;lhs=x;rhs=y;k=1.0;rhs2=;k2=0.0;c=0.0;");

        assertThat(TraceDefinitionAvailabilityValidator.validate(
                "The window construction is not available, so the result cannot be reproduced.",
                registration, List.of())).isNotEmpty();
    }

    @Test
    void ignoresAnAvailabilityClaimWhenNoInstrumentWasRecorded() {
        assertThat(TraceDefinitionAvailabilityValidator.validate(
                "The formula is unavailable.", null, List.of())).isEmpty();
    }

    @Test
    void seesWindowArgumentsAsRecordedInstrumentEvidence() {
        ToolInvocation trace = new ToolInvocation("verify_conjecture",
                "{\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}",
                "HELD", 0);

        assertThat(TraceDefinitionAvailabilityValidator.validate(
                "No window allocation is available.", null, List.of(trace))).isNotEmpty();
    }
}
