package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
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
    private final FrozenResearchBranchPreparer branchPreparer;
    private final FrozenResearchExecutionPreparer executionPreparer;

    public GenericResearchCycleExecutor(ObjectMapper objectMapper, FrozenResearchDraftPlanner draftPlanner,
                                        FrozenResearchBranchPreparer branchPreparer,
                                        FrozenResearchExecutionPreparer executionPreparer) {
        this.objectMapper = objectMapper;
        this.draftPlanner = draftPlanner;
        this.branchPreparer = branchPreparer;
        this.executionPreparer = executionPreparer;
    }

    @Override public boolean supports(String workType) { return "RESEARCH_CYCLE".equals(workType); }

    @Override
    public ResearchResult execute(ResearchWorkRequest request) {
        ResearchDraftPlan draft = draftPlanner.plan(request);
        FrozenResearchBranchPreparer.PreparedBranch branch = branchPreparer.prepare(draft);
        ResearchExecutionPreparation executionPreparation = executionPreparer.prepare(draft, branch);
        ObjectNode findings = objectMapper.createObjectNode();
        findings.put("sourceRevision", request.sourceRevision());
        findings.put("subjectCount", request.subjects().size());
        findings.put("contextPresent", !request.contextSnapshot().isEmpty());
        findings.put("method", "immutable-context-baseline");
        findings.put("draftId", draft.draftId().toString());
        findings.put("draftDomain", draft.domain());
        findings.put("draftTopic", draft.topic());
        findings.put("draftEligibility", draft.eligibility());
        findings.put("draftSelectorVersion", draft.selectorVersion());
        findings.put("draftInputFingerprint", draft.inputFingerprint());
        findings.put("draftExecutionKind", draft.executionKind());
        findings.set("draftExecutionPayload", draft.executionPayload());
        findings.put("draftExecutionStatus", branch.status());
        if (branch.canonicalPayload() != null) findings.set("draftExecutionCanonicalPayload", branch.canonicalPayload());
        findings.put("draftExecutionPreparationVersion", executionPreparation.preparationVersion());
        findings.put("draftExecutionReadiness", executionPreparation.readiness());
        findings.put("draftExecutionInputFingerprint", executionPreparation.inputFingerprint());
        findings.set("draftExecutionCanonicalInput", executionPreparation.canonicalInput());
        if (draft.fallbackParameterSample() != null) {
            findings.set("draftFallbackParameterSample", objectMapper.valueToTree(draft.fallbackParameterSample()));
        }
        appendQueueObservationProvenance(request, findings);
        UUID resultId = UUID.nameUUIDFromBytes(("research-result:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        boolean standardCatalog = "STANDARD_CATALOG".equals(draft.executionKind());
        String summary = standardCatalog
                ? "Planned a reproducible candidate draft for " + request.subjects().size()
                    + " subjects at source revision " + request.sourceRevision() + "."
                : "Prepared a frozen non-standard draft branch without computation at source revision "
                    + request.sourceRevision() + ".";
        return new ResearchResult(resultId, request.requestId(), request.workspaceId(),
                standardCatalog ? "DRAFT_CANDIDATE_PLANNED" : "DRAFT_BRANCH_PREPARED",
                summary, findings, Instant.now());
    }

    /** Exposes compact immutable selection provenance; it never reads or consumes a live queue. */
    private static void appendQueueObservationProvenance(ResearchWorkRequest request, ObjectNode findings) {
        var selection = request.contextSnapshot().path("draftSelection");
        var snapshot = selection.path("conjectureQueueSnapshot");
        if (!snapshot.isObject()) return;
        ObjectNode provenance = findings.putObject("draftQueueObservation");
        provenance.put("replayed", true);
        provenance.put("snapshotVersion", snapshot.path("snapshotVersion").asText());
        provenance.put("domain", snapshot.path("domain").asText());
        provenance.put("promotedCandidateCount", snapshot.path("promotedCandidates").size());
        provenance.put("ordinaryCandidateCount", snapshot.path("ordinaryCandidates").size());
        provenance.put("preTopicRandomDoubleDraws", selection.path("preTopicRandomDoubleDraws").asInt());
    }
}
