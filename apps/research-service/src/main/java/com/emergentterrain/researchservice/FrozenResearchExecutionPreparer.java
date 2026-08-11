package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Persists a canonical frozen execution readiness record without starting computation.  A
 * standard catalog draft is deliberately blocked until its computation parameters are frozen;
 * non-standard branches retain their already-canonical payload but remain non-computing.
 */
@Service
public class FrozenResearchExecutionPreparer {
    private final ResearchExecutionPreparationRepository preparations;
    private final ObjectMapper objectMapper;

    public FrozenResearchExecutionPreparer(ResearchExecutionPreparationRepository preparations,
                                           ObjectMapper objectMapper) {
        this.preparations = preparations;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResearchExecutionPreparation prepare(ResearchDraftPlan draft,
                                                FrozenResearchBranchPreparer.PreparedBranch branch) {
        ObjectNode canonicalInput = objectMapper.createObjectNode();
        canonicalInput.put("draftId", draft.draftId().toString());
        canonicalInput.put("requestId", draft.requestId().toString());
        canonicalInput.put("domain", draft.domain());
        canonicalInput.put("topic", draft.topic());
        canonicalInput.put("executionKind", draft.executionKind());
        canonicalInput.put("draftInputFingerprint", draft.inputFingerprint());
        JsonNode payload = branch.canonicalPayload() == null ? draft.executionPayload() : branch.canonicalPayload();
        canonicalInput.set("executionPayload", payload);
        if (draft.fallbackParameterSample() != null) {
            canonicalInput.set("fallbackParameterSample", objectMapper.valueToTree(draft.fallbackParameterSample()));
        }
        if (draft.formulationSpecification() != null) {
            canonicalInput.set("frozenFormulation", objectMapper.valueToTree(draft.formulationSpecification()));
        }
        String readiness = ResearchDraftExecutionIntent.STANDARD_CATALOG.equals(draft.executionKind())
                ? draft.formulationSpecification() == null ? ResearchExecutionPreparation.AWAITING_COMPUTATION_INPUTS
                        : ResearchExecutionPreparation.FORMULATION_FROZEN_AWAITING_COMPUTATION
                : ResearchExecutionPreparation.PREPARED_NO_COMPUTATION;
        String inputJson = write(canonicalInput);
        ResearchExecutionPreparation calculated = new ResearchExecutionPreparation(
                UUID.nameUUIDFromBytes(("research-execution-preparation:" + draft.draftId())
                        .getBytes(StandardCharsets.UTF_8)),
                draft.draftId(), draft.requestId(), ResearchExecutionPreparation.FROZEN_EXECUTION_PREPARATION_V1,
                draft.executionKind(), readiness, fingerprint(inputJson), canonicalInput, Instant.now());
        return preparations.findById(calculated.preparationId())
                .map(existing -> reuse(existing, calculated))
                .orElseGet(() -> save(calculated, inputJson));
    }

    private ResearchExecutionPreparation save(ResearchExecutionPreparation preparation, String inputJson) {
        preparations.save(new ResearchExecutionPreparationRecord(preparation, inputJson));
        return preparation;
    }

    private ResearchExecutionPreparation reuse(ResearchExecutionPreparationRecord existing,
                                               ResearchExecutionPreparation calculated) {
        ResearchExecutionPreparation persisted = existing.toPreparation(objectMapper);
        if (!persisted.draftId().equals(calculated.draftId())
                || !persisted.requestId().equals(calculated.requestId())
                || !persisted.preparationVersion().equals(calculated.preparationVersion())
                || !persisted.executionKind().equals(calculated.executionKind())
                || !persisted.readiness().equals(calculated.readiness())
                || !persisted.inputFingerprint().equals(calculated.inputFingerprint())
                || !persisted.canonicalInput().equals(calculated.canonicalInput())) {
            throw new IllegalStateException("Frozen execution preparation is already bound to different input");
        }
        return persisted;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Unable to serialize frozen execution preparation", serializationFailure);
        }
    }

    private static String fingerprint(String canonicalInputJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalInputJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailableDigest) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailableDigest);
        }
    }
}
