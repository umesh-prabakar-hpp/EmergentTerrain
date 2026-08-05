package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GenericResearchCycleExecutorTest {
    @Test
    void producesAReproducibleGameAgnosticBaseline() {
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 7,
                Instant.parse("2026-08-05T12:00:00Z"), List.of(new ResearchSubject(UUID.randomUUID(), "AGENT", null)), null);
        ResearchResult result = new GenericResearchCycleExecutor(new ObjectMapper()).execute(request);
        assertThat(result.outcome()).isEqualTo("BASELINE_RECORDED");
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.findings().path("subjectCount").asInt()).isEqualTo(1);
        assertThat(result.findings().path("sourceRevision").asLong()).isEqualTo(7L);
    }
}
