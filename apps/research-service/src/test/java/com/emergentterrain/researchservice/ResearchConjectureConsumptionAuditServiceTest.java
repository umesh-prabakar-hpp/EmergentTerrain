package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchConjectureConsumptionEvent;
import com.emergentterrain.researchcontract.ResearchConjectureConsumptionWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchConjectureConsumptionAuditServiceTest {

    @Test
    void baselinesACompleteWindowAndPersistsOnlyCompletedEvents() {
        ResearchConjectureConsumptionAuditCursorRepository cursors = mock(ResearchConjectureConsumptionAuditCursorRepository.class);
        ResearchConjectureConsumptionAuditRecordRepository records = mock(ResearchConjectureConsumptionAuditRecordRepository.class);
        UUID roomId = UUID.randomUUID();
        when(cursors.findById(roomId)).thenReturn(Optional.empty());
        when(records.existsById(any())).thenReturn(false);

        var result = service(cursors, records, true).observe(roomId, window(roomId, "epoch-a", 1L, 2L));

        assertThat(result.outcome()).isEqualTo(ResearchConjectureConsumptionAuditService.Outcome.OBSERVED);
        assertThat(result.observedEvents()).isEqualTo(2);
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("OBSERVED") && record.getLatestSequence() == 1L));
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("OBSERVED") && record.getLatestSequence() == 2L));
        verify(cursors).save(org.mockito.ArgumentMatchers.argThat(cursor ->
                cursor.getEpoch().equals("epoch-a") && cursor.getLatestSequence() == 2L));
    }

    @Test
    void retainsAnEpochChangeAsInconclusiveWithoutReplayingEvents() {
        ResearchConjectureConsumptionAuditCursorRepository cursors = mock(ResearchConjectureConsumptionAuditCursorRepository.class);
        ResearchConjectureConsumptionAuditRecordRepository records = mock(ResearchConjectureConsumptionAuditRecordRepository.class);
        UUID roomId = UUID.randomUUID();
        ResearchConjectureConsumptionAuditCursor cursor = new ResearchConjectureConsumptionAuditCursor(roomId,
                "epoch-a", 4L, "OBSERVED");
        when(cursors.findById(roomId)).thenReturn(Optional.of(cursor));
        when(records.existsById(any())).thenReturn(false);

        var result = service(cursors, records, true).observe(roomId, window(roomId, "epoch-b", 1L, 2L));

        assertThat(result.outcome()).isEqualTo(ResearchConjectureConsumptionAuditService.Outcome.EPOCH_CHANGED);
        assertThat(result.observedEvents()).isZero();
        assertThat(cursor.getEpoch()).isEqualTo("epoch-b");
        assertThat(cursor.getLatestSequence()).isEqualTo(2L);
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("EPOCH_CHANGED")));
        verify(records, never()).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("OBSERVED")));
    }

    @Test
    void retainsASequenceGapAsInconclusiveWithoutInventingQueueEvents() {
        ResearchConjectureConsumptionAuditCursorRepository cursors = mock(ResearchConjectureConsumptionAuditCursorRepository.class);
        ResearchConjectureConsumptionAuditRecordRepository records = mock(ResearchConjectureConsumptionAuditRecordRepository.class);
        UUID roomId = UUID.randomUUID();
        ResearchConjectureConsumptionAuditCursor cursor = new ResearchConjectureConsumptionAuditCursor(roomId,
                "epoch-a", 2L, "OBSERVED");
        when(cursors.findById(roomId)).thenReturn(Optional.of(cursor));
        when(records.existsById(any())).thenReturn(false);

        var result = service(cursors, records, true).observe(roomId, window(roomId, "epoch-a", 4L, 5L));

        assertThat(result.outcome()).isEqualTo(ResearchConjectureConsumptionAuditService.Outcome.SEQUENCE_GAP);
        assertThat(result.observedEvents()).isZero();
        assertThat(cursor.getLatestSequence()).isEqualTo(5L);
        verify(records).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("SEQUENCE_GAP")));
        verify(records, never()).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getOutcome().equals("OBSERVED")));
    }

    @Test
    void staysInertWhenTheAuditIsDisabled() {
        ResearchConjectureConsumptionAuditCursorRepository cursors = mock(ResearchConjectureConsumptionAuditCursorRepository.class);
        ResearchConjectureConsumptionAuditRecordRepository records = mock(ResearchConjectureConsumptionAuditRecordRepository.class);

        var result = service(cursors, records, false).observe(UUID.randomUUID(), null);

        assertThat(result.enabled()).isFalse();
        assertThat(result.outcome()).isEqualTo(ResearchConjectureConsumptionAuditService.Outcome.DISABLED);
        verify(cursors, never()).findById(any());
        verify(records, never()).save(any());
    }

    private static ResearchConjectureConsumptionAuditService service(
            ResearchConjectureConsumptionAuditCursorRepository cursors,
            ResearchConjectureConsumptionAuditRecordRepository records, boolean enabled) {
        return new ResearchConjectureConsumptionAuditService(cursors, records, enabled);
    }

    private static ResearchConjectureConsumptionWindow window(UUID roomId, String epoch, long first, long latest) {
        return new ResearchConjectureConsumptionWindow(epoch, first, latest,
                java.util.stream.LongStream.rangeClosed(first, latest).mapToObj(sequence ->
                        new ResearchConjectureConsumptionEvent(UUID.nameUUIDFromBytes((epoch + sequence).getBytes()),
                                roomId, sequence, sequence, "author", "MATHEMATICS", "source-" + sequence,
                                "ORDINARY_QUEUE", Instant.EPOCH.plusSeconds(sequence)))
                        .toList());
    }
}
