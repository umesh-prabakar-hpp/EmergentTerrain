package com.emergentterrain.research.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP client used after the research service is deployed independently. */
@Service
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "remote")
public class HttpResearchQueryClient implements ResearchQueryClient {

    private final RestClient restClient;

    public HttpResearchQueryClient(RestClient.Builder builder,
                                   ResearchApiProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public List<ResearchPaperSummary> getPublishedPapers(UUID roomId, int limit) {
        List<ResearchPaperSummary> papers = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/research/rooms/{roomId}/papers")
                        .queryParam("limit", limit)
                        .build(roomId))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ResearchPaperSummary>>() { });
        return papers == null ? List.of() : papers;
    }

    @Override
    public Map<String, Object> getStats(UUID roomId) {
        Map<String, Object> stats = restClient.get()
                .uri("/api/v1/research/rooms/{roomId}/stats", roomId)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        return stats == null ? Map.of() : stats;
    }
}
