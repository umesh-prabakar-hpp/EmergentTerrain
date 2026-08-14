package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** Research-owned, read-only lifecycle lookup for reconciliation after outbox or worker recovery. */
@Service
public class ResearchWorkStatusService {
    private final ResearchWorkItemRepository workItems;

    public ResearchWorkStatusService(ResearchWorkItemRepository workItems) {
        this.workItems = workItems;
    }

    public Optional<ResearchWorkStatus> status(UUID requestId) {
        return workItems.findById(requestId).map(item -> new ResearchWorkStatus(item.getRequestId(), item.getState(),
                item.getAttempts(), item.getAcceptedAt(), item.getCompletedAt(), item.getResultPublishedAt(),
                item.getResultDeliveryClaimedAt(), item.getResultDeliveryRejectedAt(),
                item.getResultDeliveryRejection()));
    }
}
