package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ResearchResultDeliveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsSuccessfulDisplayProjectionAcknowledgement() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        candidates(repository, item);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { });

        service.deliverCompleted();

        verify(repository).claimResultDelivery(eq(item.getRequestId()), any(), any());
        verify(repository).acknowledgeResultDelivery(eq(item.getRequestId()), any(), any());
        verify(repository, never()).save(item);
    }

    @Test
    void recordsAClientPolicyRejectionAndDoesNotRetryIt() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        candidates(repository, item);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { throw new HttpClientErrorException(HttpStatus.BAD_REQUEST); });

        service.deliverCompleted();

        verify(repository).rejectResultDelivery(eq(item.getRequestId()), any(), any(), eq("HTTP_400"));
        verify(repository, never()).save(item);
    }

    @Test
    void leavesTransientFailureEligibleForTheNextRetry() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        candidates(repository, item);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { throw new IllegalStateException("connection lost"); });

        service.deliverCompleted();

        verify(repository).releaseResultDeliveryClaim(eq(item.getRequestId()), any());
        verify(repository, never()).save(item);
    }

    @Test
    void leavesAnAuthenticationFailureEligibleForRetryAfterCredentialsAreCorrected() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        candidates(repository, item);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "stale-key",
                result -> { throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED); });

        service.deliverCompleted();

        verify(repository).releaseResultDeliveryClaim(eq(item.getRequestId()), any());
        verify(repository, never()).save(item);
    }

    @Test
    void skipsDeliveryWithoutAConfiguredCredential() {
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "",
                result -> { throw new AssertionError("must not deliver"); });

        service.deliverCompleted();

        verify(repository, never()).findResultDeliveryCandidates(eq("COMPLETED"), any(), any());
    }

    @Test
    void skipsAResultDeliveryAlreadyClaimedByAnotherWorker() throws Exception {
        ResearchWorkItem item = completedItem();
        ResearchWorkItemRepository repository = mock(ResearchWorkItemRepository.class);
        when(repository.findResultDeliveryCandidates(eq("COMPLETED"), any(), any())).thenReturn(List.of(item));
        when(repository.claimResultDelivery(eq(item.getRequestId()), any(), any())).thenReturn(0);
        ResearchResultDeliveryService service = new ResearchResultDeliveryService(repository, mapper, "result-key",
                result -> { throw new AssertionError("the lost claimant must not send"); });

        service.deliverCompleted();

        verify(repository, never()).acknowledgeResultDelivery(eq(item.getRequestId()), any(), any());
        verify(repository, never()).releaseResultDeliveryClaim(eq(item.getRequestId()), any());
    }

    private static void candidates(ResearchWorkItemRepository repository, ResearchWorkItem item) {
        when(repository.findResultDeliveryCandidates(eq("COMPLETED"), any(), any())).thenReturn(List.of(item));
        when(repository.claimResultDelivery(eq(item.getRequestId()), any(), any())).thenReturn(1);
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
