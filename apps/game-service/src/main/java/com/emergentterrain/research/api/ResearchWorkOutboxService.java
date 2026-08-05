package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchWorkOutboxRepository;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Persists a work request in the same game transaction before any network delivery is attempted. */
@Service
public class ResearchWorkOutboxService {

    private final ResearchWorkOutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public ResearchWorkOutboxService(ResearchWorkOutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(ResearchWorkRequest request) {
        try {
            outbox.insertIfAbsent(request.requestId(), request.workType(), objectMapper.writeValueAsString(request),
                    Instant.now());
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to serialize research work request", serializationFailure);
        }
    }
}
