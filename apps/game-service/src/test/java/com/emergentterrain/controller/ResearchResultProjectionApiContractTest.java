package com.emergentterrain.controller;

import com.emergentterrain.config.ResearchContextApiKeyFilter;
import com.emergentterrain.research.api.ResearchResultProjectionService;
import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ResearchResultProjectionApiContractTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsOnlyTheDisplaySafeResultContract() throws Exception {
        ResearchResultProjectionService projections = mock(ResearchResultProjectionService.class);
        ResearchResult result = result();

        var response = new ResearchResultProjectionController(projections).receive(result);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(projections).apply(result);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("\"resultId\"", "\"requestId\"", "\"workspaceId\"", "\"findings\"");
    }

    @Test
    void rejectsAProjectionThatViolatesTheGameOwnedDisplayPolicy() {
        ResearchResultProjectionService projections = mock(ResearchResultProjectionService.class);
        ResearchResult result = result();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("not approved"))
                .when(projections).apply(result);

        var response = new ResearchResultProjectionController(projections).receive(result);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authenticatesOnlyThePublishedResultEndpointAndHeader() throws Exception {
        ResearchContextApiKeyFilter filter = new ResearchContextApiKeyFilter("context-key", "result-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/game/research-results");
        request.addHeader("X-Research-Result-Key", "result-key");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("research-service");
    }

    @Test
    void openApiConstrainsDisplayOutcomesWithoutRestrictingTopicHistoryOutcomes() throws Exception {
        String spec = Files.readString(openApi());
        int topicHistoryStart = spec.indexOf("    ResearchTopicHistoryEntry:\n");
        int researchResultStart = spec.indexOf("    ResearchResult:\n");

        assertThat(topicHistoryStart).isGreaterThanOrEqualTo(0);
        assertThat(researchResultStart).isGreaterThan(topicHistoryStart);
        assertThat(spec.substring(topicHistoryStart, researchResultStart))
                .contains("outcome: { type: string, minLength: 1 }")
                .doesNotContain("enum: [DRAFT_CANDIDATE_PLANNED, DRAFT_BRANCH_PREPARED]");
        assertThat(spec.substring(researchResultStart))
                .contains("enum: [DRAFT_CANDIDATE_PLANNED, DRAFT_BRANCH_PREPARED]");
    }

    private static ResearchResult result() {
        return new ResearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DRAFT_CANDIDATE_PLANNED",
                "A display-safe result.", new ObjectMapper().createObjectNode().put("draftEligibility", "CANDIDATE"),
                Instant.parse("2026-08-06T12:00:00Z"));
    }

    private static Path openApi() {
        Path root = Path.of("libs", "research-contract", "openapi", "openapi.yaml");
        return Files.isRegularFile(root) ? root
                : Path.of("..", "..", "libs", "research-contract", "openapi", "openapi.yaml");
    }
}
