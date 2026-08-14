package com.emergentterrain.researchservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchWorkStatusServiceTest {

    @Test
    void exposesOnlyDurableLifecycleAndDeliveryFields() throws Exception {
        UUID requestId = UUID.randomUUID();
        ResearchWorkItem item = new ResearchWorkItem(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 7L,
                "{\"privateContext\":\"must-not-leak\"}", Instant.parse("2026-08-13T12:00:00Z"));
        item.markRunning(Instant.parse("2026-08-13T12:00:01Z"));
        item.markCompleted("{\"privateResult\":\"must-not-leak\"}");
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findById(requestId)).thenReturn(Optional.of(item));

        var status = new ResearchWorkStatusService(repository).status(requestId).orElseThrow();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(status);

        assertThat(status.state()).isEqualTo("COMPLETED");
        assertThat(status.attempts()).isEqualTo(1);
        assertThat(json).doesNotContain("privateContext", "privateResult", "requestJson", "resultJson");
    }

    @Test
    void exposesAnActiveDeliveryLeaseWithoutLeakingTheResult() throws Exception {
        UUID requestId = UUID.randomUUID();
        ResearchWorkItem item = new ResearchWorkItem(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 7L,
                "{\"privateContext\":\"must-not-leak\"}", Instant.parse("2026-08-13T12:00:00Z"));
        item.markCompleted("{\"privateResult\":\"must-not-leak\"}");
        Instant claimedAt = Instant.parse("2026-08-13T12:01:00Z");
        ReflectionTestUtils.setField(item, "resultDeliveryClaimedAt", claimedAt);
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findById(requestId)).thenReturn(Optional.of(item));

        var status = new ResearchWorkStatusService(repository).status(requestId).orElseThrow();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(status);

        assertThat(status.resultDeliveryClaimedAt()).isEqualTo(claimedAt);
        assertThat(json).doesNotContain("privateContext", "privateResult", "requestJson", "resultJson");
    }

    @Test
    void reportsNoStatusForAnUnknownRequest() {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        UUID requestId = UUID.randomUUID();
        when(repository.findById(requestId)).thenReturn(Optional.empty());

        assertThat(new ResearchWorkStatusService(repository).status(requestId)).isEmpty();
    }
}
