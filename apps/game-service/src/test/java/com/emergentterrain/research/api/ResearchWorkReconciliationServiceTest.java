package com.emergentterrain.research.api;

import com.emergentterrain.domain.ResearchWorkOutboxEntry;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkReconciliationRecordRepository;
import com.emergentterrain.researchcontract.ResearchWorkStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchWorkReconciliationServiceTest {

    @Test
    void persistsOneGenericStatusObservationWithoutChangingTheOutboxEntry() {
        ResearchWorkOutboxRepository outbox = mock(ResearchWorkOutboxRepository.class);
        ResearchWorkReconciliationRecordRepository records = mock(ResearchWorkReconciliationRecordRepository.class);
        UUID requestId = UUID.randomUUID();
        ResearchWorkOutboxEntry entry = new ResearchWorkOutboxEntry(requestId, "RESEARCH_CYCLE", "{\"private\":true}");
        ResearchWorkStatus status = new ResearchWorkStatus(requestId, "COMPLETED", 1,
                Instant.parse("2026-08-13T12:00:00Z"), Instant.parse("2026-08-13T12:01:00Z"), null, null, null, "");
        when(records.existsById(any())).thenReturn(false);

        new ResearchWorkReconciliationService(outbox, records, ignored -> Optional.of(status)).reconcile(entry);

        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getRequestId().equals(requestId) && record.getState().equals("COMPLETED")));
        verify(outbox, never()).save(any());
    }

    @Test
    void ignoresAnUnavailableOrMismatchedStatusWithoutChangingAnything() {
        ResearchWorkOutboxRepository outbox = mock(ResearchWorkOutboxRepository.class);
        ResearchWorkReconciliationRecordRepository records = mock(ResearchWorkReconciliationRecordRepository.class);
        ResearchWorkOutboxEntry entry = new ResearchWorkOutboxEntry(UUID.randomUUID(), "RESEARCH_CYCLE", "{}");

        new ResearchWorkReconciliationService(outbox, records, ignored -> Optional.empty()).reconcile(entry);

        verify(records, never()).save(any());
        verify(outbox, never()).save(any());
    }
}
