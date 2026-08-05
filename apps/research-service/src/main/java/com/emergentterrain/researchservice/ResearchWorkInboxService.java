package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Receives generic work idempotently before a research executor observes it. */
@Service
public class ResearchWorkInboxService {

    private final ResearchWorkItemRepository workItems;
    private final ObjectMapper objectMapper;

    public ResearchWorkInboxService(ResearchWorkItemRepository workItems, ObjectMapper objectMapper) {
        this.workItems = workItems;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResearchWorkReceipt accept(ResearchWorkRequest request) {
        ResearchWorkItem existing = workItems.findById(request.requestId()).orElse(null);
        if (existing != null) {
            return new ResearchWorkReceipt(existing.getRequestId(), existing.getState(), existing.getAcceptedAt());
        }
        Instant acceptedAt = Instant.now();
        try {
            int inserted = workItems.insertIfAbsent(request.requestId(), request.workspaceId(), request.workType(),
                    request.sourceRevision(), objectMapper.writeValueAsString(request), acceptedAt);
            if (inserted == 1) {
                return new ResearchWorkReceipt(request.requestId(), "QUEUED", acceptedAt);
            }
            ResearchWorkItem replay = workItems.findById(request.requestId())
                    .orElseThrow(() -> new IllegalStateException("Duplicate work request was not readable"));
            return new ResearchWorkReceipt(replay.getRequestId(), replay.getState(), replay.getAcceptedAt());
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to persist research work request", serializationFailure);
        }
    }
}
