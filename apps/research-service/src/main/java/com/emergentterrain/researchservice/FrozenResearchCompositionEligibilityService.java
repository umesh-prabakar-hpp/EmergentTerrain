package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationFailureParity;
import com.emergentterrain.researchcontract.ResearchComputationParity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Fail-closed research-owned precondition for a future composition extraction. It only evaluates
 * persisted audit evidence and is deliberately not wired to composition, publication, or delivery.
 */
@Service
public class FrozenResearchCompositionEligibilityService {
    private final ResearchComputationParityRepository numericParities;
    private final ResearchComputationFailureParityRepository failureParities;
    private final ObjectMapper objectMapper;

    public FrozenResearchCompositionEligibilityService(ResearchComputationParityRepository numericParities,
                                                       ResearchComputationFailureParityRepository failureParities,
                                                       ObjectMapper objectMapper) {
        this.numericParities = numericParities;
        this.failureParities = failureParities;
        this.objectMapper = objectMapper;
    }

    public Eligibility assess(UUID draftId, UUID requestId, String inputFingerprint) {
        ResearchComputationParity numeric = numericParities.findById(numericId(draftId))
                .map(record -> record.toParity(objectMapper))
                .orElse(null);
        if (numeric == null) return Eligibility.NO_NUMERIC_PARITY;
        if (!sameFrozenInput(numeric.draftId(), numeric.requestId(), numeric.inputFingerprint(),
                draftId, requestId, inputFingerprint)) return Eligibility.FROZEN_INPUT_MISMATCH;
        if (!ResearchComputationParity.MATCHED.equals(numeric.status())) return Eligibility.NUMERIC_MISMATCH;

        ResearchComputationFailureParity failure = failureParities.findById(failureId(draftId))
                .map(ResearchComputationFailureParityRecord::toParity).orElse(null);
        if (failure == null) return Eligibility.ELIGIBLE;
        if (!sameFrozenInput(failure.draftId(), failure.requestId(), failure.inputFingerprint(),
                draftId, requestId, inputFingerprint)) return Eligibility.FROZEN_INPUT_MISMATCH;
        return ResearchComputationFailureParity.MATCHED.equals(failure.status())
                ? Eligibility.ELIGIBLE : Eligibility.FAILURE_MISMATCH;
    }

    private static boolean sameFrozenInput(UUID observedDraftId, UUID observedRequestId, String observedFingerprint,
                                           UUID draftId, UUID requestId, String inputFingerprint) {
        return observedDraftId.equals(draftId) && observedRequestId.equals(requestId)
                && observedFingerprint.equals(inputFingerprint);
    }

    private static UUID numericId(UUID draftId) {
        return UUID.nameUUIDFromBytes(("research-computation-parity:" + draftId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID failureId(UUID draftId) {
        return UUID.nameUUIDFromBytes(("research-computation-failure-parity:" + draftId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public enum Eligibility {
        ELIGIBLE,
        NO_NUMERIC_PARITY,
        FROZEN_INPUT_MISMATCH,
        NUMERIC_MISMATCH,
        FAILURE_MISMATCH
    }
}
