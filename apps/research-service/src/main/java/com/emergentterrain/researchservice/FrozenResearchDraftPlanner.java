package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.FrozenResearchCyclePlanner;
import com.emergentterrain.researchcontract.FrozenResearchParameterSampler;
import com.emergentterrain.researchcontract.ResearchParameterSample;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Deterministic planner that consumes only a frozen generic selection input. */
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
        requireSupportedSelectorVersion(input);
        requireSupportedTopicSelectionVersions(input);
        requireSupportedQueueObservationVersion(input);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozenCycle = FrozenResearchCyclePlanner.plan(input,
                parameterizedStudy(input), conjectureSelection(input), followUp(input));
        PlanningSelection selection = selectionFor(input, frozenCycle);
        requireSelectedSubject(request, selection.subjectId());
        UUID draftId = UUID.nameUUIDFromBytes(("draft-plan:" + request.requestId()).getBytes(StandardCharsets.UTF_8));
        ResearchDraftPlan plan = new ResearchDraftPlan(draftId, request.requestId(), request.workspaceId(),
                selection.subjectId(), selection.domain(), selection.topic(), selection.eligibility(),
                input.selectorVersion(), fingerprint(frozenInput), input.executionIntent().kind(),
                input.executionIntent().payload(), fallbackParameterSample(input, selection), Instant.now());
        return plans.findById(draftId)
                .map(existing -> reuseExistingPlan(existing, plan))
                .orElseGet(() -> saveNewPlan(plan));
    }

    private ResearchDraftPlan saveNewPlan(ResearchDraftPlan plan) {
        plans.save(new ResearchDraftPlanRecord(plan.draftId(), plan.requestId(), plan.workspaceId(),
                plan.subjectId(), plan.domain(), plan.topic(), plan.eligibility(), plan.selectorVersion(),
                plan.inputFingerprint(), plan.executionKind(), objectMapper.valueToTree(plan.executionPayload()).toString(),
                plan.fallbackParameterSample() == null ? null
                        : objectMapper.valueToTree(plan.fallbackParameterSample()).toString(), plan.plannedAt()));
        return plan;
    }

    private ResearchDraftPlan reuseExistingPlan(ResearchDraftPlanRecord existing, ResearchDraftPlan calculated) {
        ResearchDraftPlan persisted = existing.toPlan(objectMapper);
        if (!existing.matches(calculated, objectMapper)) {
            throw new IllegalStateException("Frozen draft id is already bound to a different plan");
        }
        return persisted;
    }

    private static ResearchDraftExecutionIntent parameterizedStudy(ResearchDraftSelectionInput input) {
        return ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY.equals(input.executionIntent().kind())
                ? input.executionIntent() : null;
    }

    private static ResearchDraftExecutionIntent followUp(ResearchDraftSelectionInput input) {
        return ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY.equals(input.executionIntent().kind())
                ? input.executionIntent() : null;
    }

    private ResearchConjectureQueueSelection conjectureSelection(ResearchDraftSelectionInput input) {
        ResearchConjectureQueueSelection recorded = ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY
                .equals(input.executionIntent().kind())
                ? new ResearchConjectureQueueSelection(read(input.executionIntent().payload(),
                    ResearchConjectureSpecification.class, "conjecture specification"),
                    input.preTopicRandomDoubleDraws())
                : input.preTopicRandomDoubleDraws() == 0 ? null
                : new ResearchConjectureQueueSelection(null, input.preTopicRandomDoubleDraws());
        if (input.conjectureQueueSnapshot() != null) {
            FrozenResearchCyclePlanner.verifyConjectureQueueObservation(input,
                    recorded == null ? new ResearchConjectureQueueSelection(null, 0) : recorded);
        }
        return recorded;
    }

    private PlanningSelection selectionFor(ResearchDraftSelectionInput input,
                                           FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozenCycle) {
        ResearchDraftSelection catalogSelection = frozenCycle.catalogSelection();
        if (catalogSelection != null) {
            return new PlanningSelection(catalogSelection.subjectId(), catalogSelection.domain(),
                    catalogSelection.topic(), catalogSelection.eligibility());
        }
        ResearchDraftCandidate author = frozenCycle.electedAuthor();
        String topic = switch (input.executionIntent().kind()) {
            case ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY -> "study/" + read(
                    input.executionIntent().payload(), ResearchStudySpecification.class, "parameterized study").studyId();
            case ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY -> "conjecture/" + read(
                    input.executionIntent().payload(), ResearchConjectureSpecification.class,
                    "conjecture specification").sourceId();
            default -> throw new IllegalStateException("Frozen branch omitted its required catalog selection");
        };
        String eligibility = "PRIMARY".equals(author.tier()) ? "CANDIDATE" : "FALLBACK_CANDIDATE";
        return new PlanningSelection(author.subjectId(), author.domain(), topic, eligibility);
    }

    private static ResearchParameterSample fallbackParameterSample(ResearchDraftSelectionInput input,
                                                                    PlanningSelection selection) {
        return FrozenResearchParameterSampler.sampleFallback(input)
                .filter(sample -> sample.subjectId().equals(selection.subjectId())
                        && sample.domain().equals(selection.domain()) && sample.topic().equals(selection.topic()))
                .orElse(null);
    }

    private <T> T read(JsonNode payload, Class<T> type, String description) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (Exception invalidPayload) {
            throw new IllegalArgumentException("Invalid frozen " + description + " payload", invalidPayload);
        }
    }

    private record PlanningSelection(UUID subjectId, String domain, String topic, String eligibility) { }

    private static void requireSelectedSubject(ResearchWorkRequest request, UUID selectedSubjectId) {
        if (request.subjects().stream().noneMatch(subject -> selectedSubjectId.equals(subject.subjectId()))) {
            throw new IllegalArgumentException("Frozen selected subject is not present in the work request");
        }
    }

    private static void requireSupportedSelectorVersion(ResearchDraftSelectionInput input) {
        if (!ResearchDraftSelectionInput.LEGACY_STANDARD_CATALOG_V1.equals(input.selectorVersion())) {
            throw new IllegalArgumentException("Unsupported frozen draft selector version: " + input.selectorVersion());
        }
    }

    private static void requireSupportedTopicSelectionVersions(ResearchDraftSelectionInput input) {
        if (!com.emergentterrain.researchcontract.ResearchTopicHistorySnapshot.LEGACY_TOPIC_HISTORY_V1
                .equals(input.topicHistory().snapshotVersion())) {
            throw new IllegalArgumentException("Unsupported frozen topic-history version: "
                    + input.topicHistory().snapshotVersion());
        }
        if (!com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy.LEGACY_TOPIC_BACKOFF_V1
                .equals(input.topicReselectionPolicy().policyVersion())) {
            throw new IllegalArgumentException("Unsupported frozen topic-reselection policy version: "
                    + input.topicReselectionPolicy().policyVersion());
        }
    }

    private static void requireSupportedQueueObservationVersion(ResearchDraftSelectionInput input) {
        if (input.conjectureQueueSnapshot() != null
                && !com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1
                .equals(input.conjectureQueueSnapshot().snapshotVersion())) {
            throw new IllegalArgumentException("Unsupported frozen conjecture queue snapshot version: "
                    + input.conjectureQueueSnapshot().snapshotVersion());
        }
    }

    private String fingerprint(JsonNode input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsBytes(canonicalize(input)));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private JsonNode canonicalize(JsonNode input) {
        if (input.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            input.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            for (String field : fields) canonical.set(field, canonicalize(input.get(field)));
            return canonical;
        }
        if (input.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            input.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return input;
    }
}
