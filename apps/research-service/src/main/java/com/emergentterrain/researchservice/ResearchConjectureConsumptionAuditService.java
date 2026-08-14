package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchConjectureConsumptionEvent;
import com.emergentterrain.researchcontract.ResearchConjectureConsumptionWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Records evidence from the game-owned post-consumption window without replaying, selecting, or
 * mutating a queue. A changed epoch or skipped sequence is retained as inconclusive evidence.
 */
@Service
public class ResearchConjectureConsumptionAuditService {

    private final ResearchConjectureConsumptionAuditCursorRepository cursors;
    private final ResearchConjectureConsumptionAuditRecordRepository records;
    private final boolean enabled;

    public ResearchConjectureConsumptionAuditService(ResearchConjectureConsumptionAuditCursorRepository cursors,
                                                      ResearchConjectureConsumptionAuditRecordRepository records,
                                                      @Value("${app.research-service.conjecture-consumption-audit.enabled:false}")
                                                      boolean enabled) {
        this.cursors = cursors;
        this.records = records;
        this.enabled = enabled;
    }

    @Transactional
    public AuditSnapshot observe(UUID roomId, ResearchConjectureConsumptionWindow window) {
        if (!enabled) {
            return AuditSnapshot.disabled();
        }
        if (window == null) {
            return AuditSnapshot.unavailable();
        }
        ResearchConjectureConsumptionAuditCursor cursor = cursors.findById(roomId).orElse(null);
        if (cursor == null) {
            if (window.firstSequence() > 1L) {
                return retainIncident(roomId, null, window, Outcome.SEQUENCE_GAP);
            }
            return observeFromBaseline(roomId, window);
        }
        if (!cursor.getEpoch().equals(window.epoch())) {
            return retainIncident(roomId, cursor, window, Outcome.EPOCH_CHANGED);
        }
        if (cursor.getLatestSequence() < window.firstSequence() - 1L) {
            return retainIncident(roomId, cursor, window, Outcome.SEQUENCE_GAP);
        }
        if (cursor.getLatestSequence() > window.latestSequence()) {
            return AuditSnapshot.stale();
        }
        return recordNewEvents(roomId, cursor, window, Outcome.OBSERVED);
    }

    private AuditSnapshot observeFromBaseline(UUID roomId, ResearchConjectureConsumptionWindow window) {
        ResearchConjectureConsumptionAuditCursor cursor = new ResearchConjectureConsumptionAuditCursor(roomId,
                window.epoch(), 0L, Outcome.BASELINED.name());
        if (window.events().isEmpty()) {
            cursors.save(cursor);
            retainIncidentRecord(roomId, window, Outcome.BASELINED);
            return new AuditSnapshot(true, Outcome.BASELINED, 0);
        }
        return recordNewEvents(roomId, cursor, window, Outcome.OBSERVED);
    }

    private AuditSnapshot recordNewEvents(UUID roomId, ResearchConjectureConsumptionAuditCursor cursor,
                                          ResearchConjectureConsumptionWindow window, Outcome outcome) {
        int observed = 0;
        for (ResearchConjectureConsumptionEvent event : window.events()) {
            if (event.sequence() > cursor.getLatestSequence()) {
                if (!records.existsById(event.eventId())) {
                    records.save(ResearchConjectureConsumptionAuditRecord.observed(window.epoch(), event));
                    observed++;
                }
            }
        }
        cursor.observe(window.epoch(), window.latestSequence(), outcome.name());
        cursors.save(cursor);
        return new AuditSnapshot(true, outcome, observed);
    }

    private AuditSnapshot retainIncident(UUID roomId, ResearchConjectureConsumptionAuditCursor cursor,
                                         ResearchConjectureConsumptionWindow window, Outcome outcome) {
        ResearchConjectureConsumptionAuditCursor updated = cursor == null
                ? new ResearchConjectureConsumptionAuditCursor(roomId, window.epoch(), window.latestSequence(),
                outcome.name())
                : cursor;
        updated.observe(window.epoch(), window.latestSequence(), outcome.name());
        cursors.save(updated);
        retainIncidentRecord(roomId, window, outcome);
        return new AuditSnapshot(true, outcome, 0);
    }

    private void retainIncidentRecord(UUID roomId, ResearchConjectureConsumptionWindow window, Outcome outcome) {
        UUID incidentId = UUID.nameUUIDFromBytes(("research-conjecture-consumption-audit:" + roomId + ":"
                + window.epoch() + ":" + window.firstSequence() + ":" + window.latestSequence() + ":"
                + outcome).getBytes(StandardCharsets.UTF_8));
        if (!records.existsById(incidentId)) {
            records.save(ResearchConjectureConsumptionAuditRecord.incident(incidentId, roomId, window.epoch(),
                    window.firstSequence(), window.latestSequence(), outcome));
        }
    }

    public enum Outcome {
        DISABLED,
        UNAVAILABLE,
        BASELINED,
        OBSERVED,
        EPOCH_CHANGED,
        SEQUENCE_GAP,
        STALE_WINDOW
    }

    public record AuditSnapshot(boolean enabled, Outcome outcome, int observedEvents) {
        static AuditSnapshot disabled() { return new AuditSnapshot(false, Outcome.DISABLED, 0); }
        static AuditSnapshot unavailable() { return new AuditSnapshot(true, Outcome.UNAVAILABLE, 0); }
        static AuditSnapshot stale() { return new AuditSnapshot(true, Outcome.STALE_WINDOW, 0); }
    }
}
