package com.emergentterrain.researchservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchQueryApiKeyFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAValidDedicatedKeyForResearchReads() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/research/rooms/abc/papers");
        request.addHeader(ResearchQueryApiKeyFilter.HEADER, "query-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_RESEARCH_QUERY");
    }

    @Test
    void rejectsMissingOrIncorrectKeysBeforeTheController() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/research/rooms/abc/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void protectsQualityAssessmentHistoryWithTheDedicatedReadKey() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/research/rooms/abc/papers/P-1/quality-assessments");
        request.addHeader(ResearchQueryApiKeyFilter.HEADER, "query-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_RESEARCH_QUERY");
    }

    @Test
    void protectsRemediationAssessmentHistoryWithTheDedicatedReadKey() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/research/rooms/abc/papers/P-1/remediation-assessments");
        request.addHeader(ResearchQueryApiKeyFilter.HEADER, "query-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_RESEARCH_QUERY");
    }

    @Test
    void protectsPaperProvenanceWithTheDedicatedReadKey() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/research/rooms/abc/papers/P-1/provenance");
        request.addHeader(ResearchQueryApiKeyFilter.HEADER, "query-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void leavesNonQueryRequestsForTheirOwnCredentialPolicy() throws Exception {
        ResearchQueryApiKeyFilter filter = new ResearchQueryApiKeyFilter("query-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/research/work");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
