package com.emergentterrain.research.api;

import com.emergentterrain.domain.ResearchResultProjection;
import com.emergentterrain.repo.ResearchResultProjectionRepository;
import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies display-only results; no research result can mutate gameplay through this path. */
@Service
public class ResearchResultProjectionService {
    private final ResearchResultProjectionRepository projections;
    private final ObjectMapper objectMapper;
    public ResearchResultProjectionService(ResearchResultProjectionRepository projections, ObjectMapper objectMapper) {
        this.projections = projections; this.objectMapper = objectMapper;
    }
    @Transactional
    public void apply(ResearchResult result) {
        if (projections.existsById(result.resultId())) return;
        try {
            projections.save(new ResearchResultProjection(result.resultId(), result.requestId(), result.workspaceId(),
                    result.outcome(), result.summary(), objectMapper.writeValueAsString(result.findings())));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot project research result", e); }
    }
}
