package com.emergentterrain.research.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiProtocolReuseRemediationAdjudicatorTest {
    @Test
    void acceptsOnlyKnownStructuredRemediationOutcomes() {
        assertThat(AiProtocolReuseRemediationAdjudicator.parse(new ObjectMapper(), """
                {"outcome":"PARTIALLY_ADDRESSED","rationale":"One request has trace support; another remains open."}
                """, Set.of("paper:P-2", "paper:P-1")))
                .hasValueSatisfying(decision -> assertThat(decision.outcome())
                        .isEqualTo(ProtocolReuseRemediationOutcome.PARTIALLY_ADDRESSED));
        assertThat(AiProtocolReuseRemediationAdjudicator.parse(new ObjectMapper(),
                "{\"outcome\":\"HUMAN_QUALITY_PAPER\",\"rationale\":\"not an outcome\"}",
                Set.of("paper:P-2"))).isEmpty();
    }
}
