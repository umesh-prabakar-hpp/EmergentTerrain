package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/** Deterministic standard-draft planner that consumes only a frozen generic selection input. */
@Service
public class FrozenResearchDraftPlanner {
    private final ResearchDraftPlanRepository plans;
    private final ObjectMapper objectMapper;
    public FrozenResearchDraftPlanner(ResearchDraftPlanRepository plans, ObjectMapper objectMapper) {
        this.plans = plans;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResearchDraftPlan plan(ResearchWorkRequest request) {
        JsonNode frozenInput = request.contextSnapshot().path("draftSelection");
        if (frozenInput.isMissingNode() || frozenInput.isNull()) {
            throw new IllegalArgumentException("A standard draft plan requires contextSnapshot.draftSelection");
        }
        ResearchDraftSelectionInput input;
        try {
            input = objectMapper.treeToValue(frozenInput, ResearchDraftSelectionInput.class);
        } catch (Exception invalidInput) {
            throw new IllegalArgumentException("Invalid frozen draft-selection input", invalidInput);
        }
        ResearchDraftSelection selection = StandardResearchDraftSelector.select(input);
        UUID draftId = UUID.nameUUIDFromBytes(("draft-plan:" + request.requestId()).getBytes(StandardCharsets.UTF_8));
        ResearchDraftPlan plan = new ResearchDraftPlan(draftId, request.requestId(), request.workspaceId(),
                selection.subjectId(), selection.domain(), selection.topic(), selection.eligibility(),
                input.selectorVersion(), fingerprint(frozenInput), input.executionIntent().kind(),
                input.executionIntent().payload(), Instant.now());
        if (!plans.existsById(draftId)) {
            plans.save(new ResearchDraftPlanRecord(plan.draftId(), plan.requestId(), plan.workspaceId(),
                    plan.subjectId(), plan.domain(), plan.topic(), plan.eligibility(), plan.selectorVersion(),
                    plan.inputFingerprint(), plan.executionKind(), objectMapper.valueToTree(plan.executionPayload()).toString(),
                    plan.plannedAt()));
        }
        return plan;
    }

    private static String fingerprint(JsonNode input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
