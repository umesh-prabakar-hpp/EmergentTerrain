package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchResultDeliveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsSuccessfulDisplayProjectionAcknowledgement() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc(
                "COMPLETED")).thenReturn(List.of(item));
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { });

        service.deliverCompleted();

        assertThat(item.getResultPublishedAt()).isNotNull();
        assertThat(item.getResultDeliveryRejectedAt()).isNull();
        verify(repository).save(item);
    }

    @Test
    void recordsAClientPolicyRejectionAndDoesNotRetryIt() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc(
                "COMPLETED")).thenReturn(List.of(item));
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { throw new HttpClientErrorException(HttpStatus.BAD_REQUEST); });

        service.deliverCompleted();

        assertThat(item.getResultPublishedAt()).isNull();
        assertThat(item.getResultDeliveryRejectedAt()).isNotNull();
        assertThat(item.getResultDeliveryRejection()).isEqualTo("HTTP_400");
        verify(repository).save(item);
    }

    @Test
    void leavesTransientFailureEligibleForTheNextRetry() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc(
                "COMPLETED")).thenReturn(List.of(item));
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { throw new IllegalStateException("connection lost"); });

        service.deliverCompleted();

        assertThat(item.getResultPublishedAt()).isNull();
        assertThat(item.getResultDeliveryRejectedAt()).isNull();
        verify(repository, never()).save(item);
    }

    @Test
    void leavesAnAuthenticationFailureEligibleForRetryAfterCredentialsAreCorrected() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc(
                "COMPLETED")).thenReturn(List.of(item));
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "stale-key",
                result -> { throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED); });

        service.deliverCompleted();

        assertThat(item.getResultPublishedAt()).isNull();
        assertThat(item.getResultDeliveryRejectedAt()).isNull();
        verify(repository, never()).save(item);
    }

    @Test
    void skipsDeliveryWithoutAConfiguredCredential() {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "",
                result -> { throw new AssertionError("must not deliver"); });

        service.deliverCompleted();

        verify(repository, never())
                .findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc("COMPLETED");
    }

    private ResearchWorkItem completedItem() throws Exception {
        UUID requestId = UUID.randomUUID();
        ResearchWorkItem item = new ResearchWorkItem(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 7L,
                "{}", Instant.parse("2026-08-10T12:00:00Z"));
        ResearchResult result = new ResearchResult(UUID.randomUUID(), requestId, item.getWorkspaceId(),
                "DRAFT_CANDIDATE_PLANNED", "A display-only planning result.", mapper.createObjectNode(),
                Instant.parse("2026-08-10T12:01:00Z"));
        item.markCompleted(mapper.writeValueAsString(result));
        return item;
    }
}
