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
    void authenticatesOnlyThePublishedResultEndpointAndHeader() throws Exception {
        ResearchContextApiKeyFilter filter = new ResearchContextApiKeyFilter("context-key", "result-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/game/research-results");
        request.addHeader("X-Research-Result-Key", "result-key");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("research-service");
    }

    private static ResearchResult result() {
        return new ResearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DRAFT_CANDIDATE_PLANNED",
                "A display-safe result.", new ObjectMapper().createObjectNode().put("draftEligibility", "CANDIDATE"),
                Instant.parse("2026-08-06T12:00:00Z"));
    }
}
