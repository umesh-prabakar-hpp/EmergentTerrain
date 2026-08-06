package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Validates and canonically reads frozen non-standard planning inputs without game-model access. */
@Service
public class FrozenResearchBranchPreparer {
    private final ObjectMapper objectMapper;

    public FrozenResearchBranchPreparer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PreparedBranch prepare(ResearchDraftPlan draft) {
        return switch (draft.executionKind()) {
            case ResearchDraftExecutionIntent.STANDARD_CATALOG -> PreparedBranch.notApplicable();
            case ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY -> new PreparedBranch(
                    "PREPARED", write(read(draft.executionPayload(), ResearchStudySpecification.class,
                            "parameterized study")));
            case ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY -> new PreparedBranch(
                    "PREPARED", write(read(draft.executionPayload(), ResearchParameterOverride.class,
                            "parameter override")));
            default -> throw new IllegalArgumentException("Unsupported frozen execution kind: " + draft.executionKind());
        };
    }

    private <T> T read(JsonNode payload, Class<T> type, String description) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (Exception invalidPayload) {
            throw new IllegalArgumentException("Invalid frozen " + description + " payload", invalidPayload);
        }
    }

    private JsonNode write(Object value) {
        return objectMapper.valueToTree(value);
    }

    public record PreparedBranch(String status, JsonNode canonicalPayload) {
        static PreparedBranch notApplicable() {
            return new PreparedBranch("NOT_APPLICABLE", null);
        }
    }
}
