package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

/** Delivers completed generic results at least once; successful acknowledgement is durably recorded. */
@Service
public class ResearchResultDeliveryService {
    private final ResearchWorkItemRepository workItems;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final ResultSender sender;
    private final Duration claimLease;

    public ResearchResultDeliveryService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper,
                                         RestClient.Builder builder,
                                         @Value("${app.research-service.result-delivery.base-url}") String baseUrl,
                                         @Value("${app.research-service.result-delivery.api-key:}") String apiKey,
                                         @Value("${app.research-service.result-delivery.claim-lease-ms:30000}") long claimLeaseMs) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.sender = result -> client.post().uri("/api/v1/game/research-results")
                .header("X-Research-Result-Key", apiKey).body(result).retrieve().toBodilessEntity();
        this.claimLease = Duration.ofMillis(Math.max(1_000L, claimLeaseMs));
    }

    ResearchResultDeliveryService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper,
                                  String apiKey, ResultSender sender) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
        this.client = null;
        this.apiKey = apiKey;
        this.sender = sender;
        this.claimLease = Duration.ofSeconds(30);
    }

    @Scheduled(fixedDelayString = "${app.research-service.result-delivery.interval-ms:1000}")
    public void deliverCompleted() {
        if (apiKey.isBlank()) return;
        Instant now = Instant.now();
        Instant staleBefore = now.minus(claimLease);
        for (ResearchWorkItem item : workItems.findResultDeliveryCandidates("COMPLETED", staleBefore,
                PageRequest.of(0, 20))) {
            Instant claimedAt = Instant.now();
            if (workItems.claimResultDelivery(item.getRequestId(), claimedAt, claimedAt.minus(claimLease)) != 1) continue;
            try {
                ResearchResult result = objectMapper.readValue(item.getResultJson(), ResearchResult.class);
                sender.send(result);
                workItems.acknowledgeResultDelivery(item.getRequestId(), claimedAt, Instant.now());
            } catch (RestClientResponseException rejected) {
                if (rejected.getStatusCode().value() == 400) {
                    workItems.rejectResultDelivery(item.getRequestId(), claimedAt, Instant.now(), "HTTP_400");
                } else {
                    workItems.releaseResultDeliveryClaim(item.getRequestId(), claimedAt);
                }
            } catch (Exception ignored) {
                workItems.releaseResultDeliveryClaim(item.getRequestId(), claimedAt);
            }
        }
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ResearchResult result);
    }
}
