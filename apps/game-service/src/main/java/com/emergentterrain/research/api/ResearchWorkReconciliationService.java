package com.emergentterrain.research.api;

import com.emergentterrain.domain.ResearchWorkOutboxEntry;
import com.emergentterrain.domain.ResearchWorkReconciliationRecord;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.repo.ResearchWorkReconciliationRecordRepository;
import com.emergentterrain.researchcontract.ResearchWorkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Default-off game-owned observer for delivered outbox entries. It reads generic status only and
 * writes audit records; it never changes outbox delivery state, replays work, or controls research.
 */
@Service
@ConditionalOnProperty(name = "app.research-api.reconciliation.enabled", havingValue = "true")
public class ResearchWorkReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ResearchWorkReconciliationService.class);

    private final ResearchWorkOutboxRepository outbox;
    private final ResearchWorkReconciliationRecordRepository records;
    private final StatusReader reader;

    public ResearchWorkReconciliationService(ResearchWorkOutboxRepository outbox,
                                             ResearchWorkReconciliationRecordRepository records,
                                             RestClient.Builder builder, ResearchApiProperties properties,
                                             @Value("${app.research-api.work-api-key:}") String apiKey) {
        this(outbox, records, statusReader(builder.baseUrl(properties.baseUrl()).build(), apiKey));
    }

    private static StatusReader statusReader(RestClient client, String apiKey) {
        return requestId -> Optional.ofNullable(client.get()
                .uri("/api/v1/research/work/{requestId}", requestId)
                .header("X-Research-Work-Key", apiKey)
                .retrieve()
                .body(ResearchWorkStatus.class));
    }

    ResearchWorkReconciliationService(ResearchWorkOutboxRepository outbox,
                                      ResearchWorkReconciliationRecordRepository records, StatusReader reader) {
        this.outbox = outbox;
        this.records = records;
        this.reader = reader;
    }

    @Scheduled(fixedDelayString = "${app.research-api.reconciliation.interval-ms:5000}")
    public void reconcileDelivered() {
        for (ResearchWorkOutboxEntry entry : outbox.findTop50ByStateOrderByCreatedAtAsc("DELIVERED")) {
            reconcile(entry);
        }
    }

    void reconcile(ResearchWorkOutboxEntry entry) {
        try {
            Optional<ResearchWorkStatus> status = reader.status(entry.getRequestId());
            if (status.isEmpty() || !entry.getRequestId().equals(status.get().requestId())) {
                log.warn("Research reconciliation unavailable or mismatched requestId={}", entry.getRequestId());
                return;
            }
            ResearchWorkReconciliationRecord record = ResearchWorkReconciliationRecord.from(status.get());
            if (!records.existsById(record.getId())) {
                records.save(record);
            }
        } catch (Exception unavailable) {
            log.warn("Research reconciliation deferred requestId={}", entry.getRequestId(), unavailable);
        }
    }

    @FunctionalInterface
    interface StatusReader {
        Optional<ResearchWorkStatus> status(UUID requestId);
    }
}
