package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
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
    private final FrozenResearchDraftPlanner draftPlanner;

    public GenericResearchCycleExecutor(ObjectMapper objectMapper, FrozenResearchDraftPlanner draftPlanner) {
        this.objectMapper = objectMapper;
        this.draftPlanner = draftPlanner;
    }

    @Override public boolean supports(String workType) { return "RESEARCH_CYCLE".equals(workType); }

    @Override
    public ResearchResult execute(ResearchWorkRequest request) {
        ResearchDraftPlan draft = draftPlanner.plan(request);
        ObjectNode findings = objectMapper.createObjectNode();
        findings.put("sourceRevision", request.sourceRevision());
        findings.put("subjectCount", request.subjects().size());
        findings.put("contextPresent", !request.contextSnapshot().isEmpty());
        findings.put("method", "immutable-context-baseline");
        findings.put("draftId", draft.draftId().toString());
        findings.put("draftDomain", draft.domain());
        findings.put("draftTopic", draft.topic());
        findings.put("draftEligibility", draft.eligibility());
        UUID resultId = UUID.nameUUIDFromBytes(("research-result:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        String summary = "Planned a reproducible candidate draft for " + request.subjects().size()
                + " subjects at source revision " + request.sourceRevision() + ".";
        return new ResearchResult(resultId, request.requestId(), request.workspaceId(), "DRAFT_CANDIDATE_PLANNED",
                summary, findings, Instant.now());
    }
}
