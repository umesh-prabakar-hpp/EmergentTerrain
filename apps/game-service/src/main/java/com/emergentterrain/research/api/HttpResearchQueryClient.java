package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchPaperSummary;
import com.emergentterrain.researchcontract.ResearchPaperProvenanceSummary;
import com.emergentterrain.researchcontract.ResearchProtocolReuseRemediationAssessmentSummary;
import com.emergentterrain.researchcontract.ResearchQualityAssessmentSummary;
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

    static final String QUERY_KEY_HEADER = "X-Research-Query-Key";

    private final RestClient restClient;

    public HttpResearchQueryClient(RestClient.Builder builder,
                                   ResearchApiProperties properties) {
        if (properties.queryApiKey().isBlank()) {
            throw new IllegalStateException("RESEARCH_QUERY_API_KEY is required when app.research-api.mode=remote");
        }
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader(QUERY_KEY_HEADER, properties.queryApiKey())
                .build();
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
    public List<ResearchQualityAssessmentSummary> getQualityAssessmentHistory(UUID roomId, String paperId) {
        List<ResearchQualityAssessmentSummary> assessments = restClient.get()
                .uri("/api/v1/research/rooms/{roomId}/papers/{paperId}/quality-assessments", roomId, paperId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ResearchQualityAssessmentSummary>>() { });
        return assessments == null ? List.of() : assessments;
    }

    @Override
    public List<ResearchProtocolReuseRemediationAssessmentSummary> getProtocolReuseRemediationAssessmentHistory(
            UUID roomId, String paperId) {
        List<ResearchProtocolReuseRemediationAssessmentSummary> assessments = restClient.get()
                .uri("/api/v1/research/rooms/{roomId}/papers/{paperId}/remediation-assessments", roomId, paperId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ResearchProtocolReuseRemediationAssessmentSummary>>() { });
        return assessments == null ? List.of() : assessments;
    }

    @Override
    public ResearchPaperProvenanceSummary getPaperProvenance(UUID roomId, String paperId) {
        ResearchPaperProvenanceSummary summary = restClient.get()
                .uri("/api/v1/research/rooms/{roomId}/papers/{paperId}/provenance", roomId, paperId)
                .retrieve()
                .body(ResearchPaperProvenanceSummary.class);
        return summary == null ? ResearchPaperProvenanceSummary.pending(paperId) : summary;
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
