package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
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
            case ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY -> prepareStudy(draft);
            case ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY -> prepareFollowUp(draft);
            case ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY -> prepareConjecture(draft);
            default -> throw new IllegalArgumentException("Unsupported frozen execution kind: " + draft.executionKind());
        };
    }

    private PreparedBranch prepareStudy(ResearchDraftPlan draft) {
        ResearchStudySpecification study = read(draft.executionPayload(), ResearchStudySpecification.class,
                "parameterized study");
        requireVersion(study.specificationVersion(), ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1,
                "parameterized study");
        requireTopic(draft, "study/" + study.studyId());
        return new PreparedBranch("PREPARED", write(study));
    }

    private PreparedBranch prepareConjecture(ResearchDraftPlan draft) {
        ResearchConjectureSpecification conjecture = read(draft.executionPayload(), ResearchConjectureSpecification.class,
                "conjecture specification");
        requireVersion(conjecture.specificationVersion(), ResearchConjectureSpecification.LEGACY_CONJECTURE_V1,
                "conjecture specification");
        requireTopic(draft, "conjecture/" + conjecture.sourceId());
        return new PreparedBranch("PREPARED", write(conjecture));
    }

    private PreparedBranch prepareFollowUp(ResearchDraftPlan draft) {
        ResearchParameterOverride override = read(draft.executionPayload(), ResearchParameterOverride.class,
                "parameter override");
        requireVersion(override.overrideVersion(), ResearchParameterOverride.LEGACY_FOLLOW_UP_V1,
                "parameter override");
        return new PreparedBranch("PREPARED", write(override));
    }

    private static void requireTopic(ResearchDraftPlan draft, String expectedTopic) {
        if (!expectedTopic.equals(draft.topic())) {
            throw new IllegalArgumentException("Frozen execution payload does not match draft topic");
        }
    }

    private static void requireVersion(String actual, String supported, String description) {
        if (!supported.equals(actual)) {
            throw new IllegalArgumentException("Unsupported frozen " + description + " version: " + actual);
        }
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
