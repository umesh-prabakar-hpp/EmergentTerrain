package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** First executable, game-agnostic research slice: records a reproducible subject/context baseline. */
@Service
public class GenericResearchCycleExecutor implements ResearchWorkExecutor {
    private final ObjectMapper objectMapper;

    public GenericResearchCycleExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override public boolean supports(String workType) { return "RESEARCH_CYCLE".equals(workType); }

    @Override
    public ResearchResult execute(ResearchWorkRequest request) {
        ObjectNode findings = objectMapper.createObjectNode();
        findings.put("sourceRevision", request.sourceRevision());
        findings.put("subjectCount", request.subjects().size());
        findings.put("contextPresent", !request.contextSnapshot().isEmpty());
        findings.put("method", "immutable-context-baseline");
        UUID resultId = UUID.nameUUIDFromBytes(("research-result:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        String summary = "Recorded a reproducible research baseline for " + request.subjects().size()
                + " subjects at source revision " + request.sourceRevision() + ".";
        return new ResearchResult(resultId, request.requestId(), request.workspaceId(), "BASELINE_RECORDED",
                summary, findings, Instant.now());
    }
}
