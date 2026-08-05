package com.emergentterrain.research.api;

import com.emergentterrain.domain.ResearchWorkOutboxEntry;
import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Publishes pending outbox records to the research-owned work API. */
@Service
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "outbox")
public class ResearchWorkOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorkOutboxRelay.class);

    private final ResearchWorkOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;

    public ResearchWorkOutboxRelay(ResearchWorkOutboxRepository outbox, ObjectMapper objectMapper,
                                   RestClient.Builder builder, ResearchApiProperties properties,
                                   @Value("${app.research-api.work-api-key:}") String apiKey) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(properties.baseUrl()).build();
        this.apiKey = apiKey;
    }

    @Scheduled(fixedDelayString = "${app.research-api.outbox-relay-interval-ms:1000}")
    public void relayPending() {
        for (ResearchWorkOutboxEntry entry : outbox.findTop50ByStateOrderByCreatedAtAsc("PENDING")) {
            deliver(entry);
        }
    }

    @Transactional
    void deliver(ResearchWorkOutboxEntry entry) {
        try {
            ResearchWorkRequest request = objectMapper.readValue(entry.getPayloadJson(), ResearchWorkRequest.class);
            ResearchWorkReceipt receipt = client.post()
                    .uri("/api/v1/research/work")
                    .header("X-Research-Work-Key", apiKey)
                    .body(request)
                    .retrieve()
                    .body(ResearchWorkReceipt.class);
            if (receipt == null || !entry.getRequestId().equals(receipt.requestId())) {
                throw new IllegalStateException("Research work receipt did not acknowledge request");
            }
            entry.markDelivered();
            outbox.save(entry);
        } catch (Exception deliveryFailure) {
            entry.recordDeliveryFailure();
            outbox.save(entry);
            log.warn("Research work delivery deferred requestId={} attempts={}", entry.getRequestId(),
                    entry.getDeliveryAttempts(), deliveryFailure);
        }
    }
}
