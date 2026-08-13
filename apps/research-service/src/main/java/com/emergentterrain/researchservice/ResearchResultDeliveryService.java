package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

/** Delivers completed generic results at least once; successful acknowledgement is durably recorded. */
@Service
public class ResearchResultDeliveryService {
    private final ResearchWorkItemRepository workItems;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final ResultSender sender;

    public ResearchResultDeliveryService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper,
                                         RestClient.Builder builder,
                                         @Value("${app.research-service.result-delivery.base-url}") String baseUrl,
                                         @Value("${app.research-service.result-delivery.api-key:}") String apiKey) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.sender = result -> client.post().uri("/api/v1/game/research-results")
                .header("X-Research-Result-Key", apiKey).body(result).retrieve().toBodilessEntity();
    }

    ResearchResultDeliveryService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper,
                                  String apiKey, ResultSender sender) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
        this.client = null;
        this.apiKey = apiKey;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${app.research-service.result-delivery.interval-ms:1000}")
    public void deliverCompleted() {
        if (apiKey.isBlank()) return;
        for (ResearchWorkItem item : workItems
                .findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc("COMPLETED")) {
            try {
                ResearchResult result = objectMapper.readValue(item.getResultJson(), ResearchResult.class);
                sender.send(result);
                item.markResultPublished();
                workItems.save(item);
            } catch (RestClientResponseException rejected) {
                if (rejected.getStatusCode().value() == 400) {
                    item.markResultDeliveryRejected("HTTP_400");
                    workItems.save(item);
                }
            } catch (Exception ignored) {
                // Leave unpublished for the next bounded retry; the game projection is idempotent by result id.
            }
        }
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ResearchResult result);
    }
}
