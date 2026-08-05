package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Delivers completed generic results at least once; successful acknowledgement is durably recorded. */
@Service
public class ResearchResultDeliveryService {
    private final ResearchWorkItemRepository workItems;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;

    public ResearchResultDeliveryService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper,
                                         RestClient.Builder builder,
                                         @Value("${app.research-service.result-delivery.base-url}") String baseUrl,
                                         @Value("${app.research-service.result-delivery.api-key:}") String apiKey) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Scheduled(fixedDelayString = "${app.research-service.result-delivery.interval-ms:1000}")
    public void deliverCompleted() {
        if (apiKey.isBlank()) return;
        for (ResearchWorkItem item : workItems.findTop20ByStateAndResultPublishedAtIsNullOrderByCompletedAtAsc("COMPLETED")) {
            try {
                ResearchResult result = objectMapper.readValue(item.getResultJson(), ResearchResult.class);
                client.post().uri("/api/v1/game/research-results").header("X-Research-Result-Key", apiKey)
                        .body(result).retrieve().toBodilessEntity();
                item.markResultPublished();
                workItems.save(item);
            } catch (Exception ignored) {
                // Leave unpublished for the next bounded retry; the game projection is idempotent by result id.
            }
        }
    }
}
