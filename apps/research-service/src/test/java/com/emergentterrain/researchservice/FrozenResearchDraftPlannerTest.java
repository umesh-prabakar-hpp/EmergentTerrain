package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.FrozenResearchCyclePlanner;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchFormulationSpecification;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchTopicHistoryEntry;
import com.emergentterrain.researchcontract.ResearchTopicHistorySnapshot;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrozenResearchDraftPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void matchesTheFrozenGenericParityFixtures() throws Exception {
        try (InputStream fixtures = getClass().getResourceAsStream("/frozen-draft-selection-fixtures.json")) {
            assertThat(fixtures).isNotNull();
            for (JsonNode fixture : mapper.readTree(fixtures)) {
                ResearchDraftSelectionInput input = mapper.treeToValue(fixture.path("input"), ResearchDraftSelectionInput.class);
                ResearchDraftSelection actual = StandardResearchDraftSelector.select(input);
                JsonNode expected = fixture.path("expected");
                assertThat(actual.subjectId().toString()).isEqualTo(expected.path("subjectId").asText());
                assertThat(actual.eligibility()).isEqualTo(expected.path("eligibility").asText());
                assertThat(actual.domain()).isEqualTo(expected.path("domain").asText());
                assertThat(actual.topic()).isEqualTo(expected.path("topic").asText());
            }
        }
    }

    @Test
    void preservesHistoryAwareTopicSelectionAcrossTheWorkRequestJsonBoundary() throws Exception {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 43L,
                "WEIGHTED", List.of(new ResearchDraftCandidate(subjectId, "PRIMARY", 1.0, "MATHEMATICS",
                List.of(new ResearchTopicOption("cooled", 1.0), new ResearchTopicOption("fresh", 1.0)))),
                ResearchDraftExecutionIntent.standardCatalog(), new ResearchTopicHistorySnapshot("legacy-topic-history-v1",
                List.of(new ResearchTopicHistoryEntry("MATHEMATICS", "cooled", "REJECTED", 0.2))),
                new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1", true, false, 2, 0.0, 1.0));
        var context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchDraftPlan plan = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper)
                .plan(mapper.readValue(mapper.writeValueAsString(request), ResearchWorkRequest.class));

        assertThat(plan.topic()).isEqualTo(StandardResearchDraftSelector.select(input).topic());
        assertThat(plan.inputFingerprint()).isNotBlank();
    }

    @Test
    void persistsOnlyAFormulationThatMatchesTheFrozenStandardDraftTarget() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchDraftSelection selected = StandardResearchDraftSelector.select(input);
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        context.set("frozenFormulation", mapper.valueToTree(new ResearchFormulationSpecification(
                ResearchFormulationSpecification.LEGACY_FORMULATION_V1, subjectId, selected.domain(), selected.topic(),
                42, 1.5, "Does the scan support the relation?", "The relation holds.", "A stable margin appears.",
                "A counterexample falsifies it.", "")));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-10T00:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchDraftPlan plan = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(request);

        assertThat(plan.formulationSpecification()).isNotNull();
        assertThat(plan.formulationSpecification().parameterOne()).isEqualTo(42);
    }

    @Test
    void rejectsAFormulationWhoseTargetDoesNotMatchTheFrozenDraft() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        context.set("frozenFormulation", mapper.valueToTree(new ResearchFormulationSpecification(
                ResearchFormulationSpecification.LEGACY_FORMULATION_V1, subjectId, "MATHEMATICS", "other-topic",
                42, 1.5, "Does the scan support the relation?", "The relation holds.", "A stable margin appears.",
                "A counterexample falsifies it.", "")));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-10T00:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        assertThatThrownBy(() -> new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void givesEquivalentFrozenInputsTheSameFingerprintRegardlessOfJsonFieldOrder() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 43L,
                "WEIGHTED", List.of(new ResearchDraftCandidate(subjectId, "PRIMARY", 1.0, "MATHEMATICS",
                List.of(new ResearchTopicOption("cooled", 1.0), new ResearchTopicOption("fresh", 1.0)))));
        ObjectNode canonical = mapper.valueToTree(input);
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", canonical);
        ObjectNode reorderedContext = mapper.createObjectNode();
        reorderedContext.set("draftSelection", reverseObjectFields(canonical));
        UUID requestId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-06T12:03:00Z");
        ResearchWorkRequest canonicalRequest = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 17,
                requestedAt, List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
        ResearchWorkRequest reorderedRequest = new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", 17,
                requestedAt, List.of(new ResearchSubject(subjectId, "AGENT", null)), reorderedContext);
        FrozenResearchDraftPlanner planner = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper);

        ResearchDraftPlan canonicalPlan = planner.plan(canonicalRequest);
        ResearchDraftPlan reorderedPlan = planner.plan(reorderedRequest);

        assertThat(reorderedPlan.inputFingerprint()).isEqualTo(canonicalPlan.inputFingerprint());
    }

    @Test
    void rejectsASelectedCandidateThatWasNotProvidedAsAGenericSubject() {
        UUID selectedSubjectId = UUID.randomUUID();
        ResearchDraftSelectionInput input = FrozenDraftSelectionFixtures.primaryInput(selectedSubjectId);
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(UUID.randomUUID(), "AGENT", null)), context);

        assertThatThrownBy(() -> new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selected subject is not present");
    }

    @Test
    void rejectsAnUnsupportedFrozenSelectorVersion() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchDraftSelectionInput unsupported = new ResearchDraftSelectionInput("future-selector-v2",
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(), standard.executionIntent(),
                standard.topicHistory(), standard.topicReselectionPolicy(), standard.preTopicRandomDoubleDraws());
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(unsupported));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        assertThatThrownBy(() -> new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen draft selector version");
    }

    @Test
    void rejectsUnsupportedFrozenTopicHistoryAndPolicyVersions() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchDraftSelectionInput unknownHistory = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(), standard.executionIntent(),
                new ResearchTopicHistorySnapshot("future-history-v2", List.of()), standard.topicReselectionPolicy(),
                standard.preTopicRandomDoubleDraws());
        ResearchDraftSelectionInput unknownPolicy = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(), standard.executionIntent(),
                standard.topicHistory(), new ResearchTopicReselectionPolicy("future-policy-v2", false, false, 0, 0.0, 1.0),
                standard.preTopicRandomDoubleDraws());

        assertThatThrownBy(() -> plan(unknownHistory, subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen topic-history version");
        assertThatThrownBy(() -> plan(unknownPolicy, subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen topic-reselection policy version");
    }

    @Test
    void rejectsAnUnsupportedFrozenQueueObservationVersion() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureQueueSnapshot unsupportedSnapshot = new ResearchConjectureQueueSnapshot(
                "future-conjecture-queue-v2", "math", 0.0, List.of(), List.of());
        ResearchDraftSelectionInput unsupported = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                standard.executionIntent(), standard.topicHistory(), standard.topicReselectionPolicy(),
                unsupportedSnapshot, 0);

        assertThatThrownBy(() -> plan(unsupported, subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen conjecture queue snapshot version");
    }

    @Test
    void preservesTerminalBranchAndQueueMissFollowUpSelectionAcrossTheWorkRequestJsonBoundary() throws Exception {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 71L,
                "WEIGHTED", List.of(new ResearchDraftCandidate(subjectId, "PRIMARY", 1.0, "MATHEMATICS",
                List.of(new ResearchTopicOption("first", 1.0), new ResearchTopicOption("second", 1.0)))));
        ResearchStudySpecification study = new ResearchStudySpecification(
                ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1, "study-7", "bounded study",
                10, 50, "isPrime", "fraction_satisfying", 0.5, "above baseline", "ABOVE_BASELINE", null);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "conjecture-7", "MATHEMATICS", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.QUEUE_CANDIDATE);
        ResearchParameterOverride followUp = new ResearchParameterOverride(
                ResearchParameterOverride.LEGACY_FOLLOW_UP_V1, 42, 1.5, 0.6, "prior scan");

        ResearchDraftPlan parameterizedPlan = planAfterJsonRoundTrip(withIntent(standard,
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY,
                        mapper.valueToTree(study)), 1), subjectId);
        ResearchDraftPlan conjecturePlan = planAfterJsonRoundTrip(withIntent(standard,
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                        mapper.valueToTree(conjecture)), 1), subjectId);
        ResearchDraftSelectionInput queueMissFollowUp = withIntent(standard,
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY,
                        mapper.valueToTree(followUp)), 1);
        ResearchDraftPlan followUpPlan = planAfterJsonRoundTrip(queueMissFollowUp, subjectId);
        ResearchDraftSelection expectedFollowUp = FrozenResearchCyclePlanner.plan(standard, null,
                new ResearchConjectureQueueSelection(null, 1), queueMissFollowUp.executionIntent()).catalogSelection();

        assertThat(parameterizedPlan.subjectId()).isEqualTo(subjectId);
        assertThat(parameterizedPlan.topic()).isEqualTo("study/study-7");
        assertThat(parameterizedPlan.executionKind())
                .isEqualTo(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY);
        assertThat(conjecturePlan.subjectId()).isEqualTo(subjectId);
        assertThat(conjecturePlan.topic()).isEqualTo("conjecture/conjecture-7");
        assertThat(conjecturePlan.executionKind())
                .isEqualTo(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY);
        assertThat(followUpPlan.subjectId()).isEqualTo(expectedFollowUp.subjectId());
        assertThat(followUpPlan.topic()).isEqualTo(expectedFollowUp.topic());
        assertThat(followUpPlan.executionKind()).isEqualTo(ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY);
    }

    @Test
    void independentlyReplaysTheFrozenQueueObservationAcrossTheWorkRequestJsonBoundary() throws Exception {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "conjecture-queue-7", "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.PROMOTED_ANOMALY);
        ResearchConjectureQueueSnapshot snapshot = new ResearchConjectureQueueSnapshot(
                ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1, "math", 1.0, List.of(conjecture), List.of());
        ResearchDraftSelectionInput observed = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                        mapper.valueToTree(conjecture)), standard.topicHistory(), standard.topicReselectionPolicy(),
                snapshot, 0);

        ResearchDraftPlan plan = planAfterJsonRoundTrip(observed, subjectId);

        assertThat(plan.topic()).isEqualTo("conjecture/conjecture-queue-7");
        assertThat(plan.executionKind()).isEqualTo(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY);
    }

    @Test
    void rejectsATamperedSelectionThatDoesNotMatchItsFrozenQueueObservation() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureSpecification selected = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "queue-selected", "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.PROMOTED_ANOMALY);
        ResearchConjectureSpecification tampered = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "queue-tampered", "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.PROMOTED_ANOMALY);
        ResearchConjectureQueueSnapshot snapshot = new ResearchConjectureQueueSnapshot(
                ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1, "math", 1.0, List.of(selected), List.of());
        ResearchDraftSelectionInput observed = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                        mapper.valueToTree(tampered)), standard.topicHistory(), standard.topicReselectionPolicy(),
                snapshot, 0);

        assertThatThrownBy(() -> plan(observed, subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match its recorded selection");
    }

    @Test
    void rejectsAProbabilityGatedQueueMissThatDropsItsRequiredRandomDraw() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureSpecification ordinary = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "queue-ordinary", "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.QUEUE_CANDIDATE);
        ResearchConjectureQueueSnapshot snapshot = new ResearchConjectureQueueSnapshot(
                ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1, "math", Double.MIN_VALUE,
                List.of(), List.of(ordinary));
        ResearchDraftSelectionInput tampered = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                standard.executionIntent(), standard.topicHistory(), standard.topicReselectionPolicy(), snapshot, 0);

        assertThatThrownBy(() -> plan(tampered, subjectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match its recorded selection");
    }

    @Test
    void exactReplayReturnsTheDurablyFrozenPlanInsteadOfReplanningIt() {
        UUID subjectId = UUID.randomUUID();
        ResearchWorkRequest request = request(FrozenDraftSelectionFixtures.primaryInput(subjectId), subjectId,
                UUID.randomUUID());
        ResearchDraftPlan expected = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper)
                .plan(request);
        ResearchDraftPlanRecord stored = recordFor(expected);
        ResearchDraftPlanRepository repository = mock(ResearchDraftPlanRepository.class);
        when(repository.findById(expected.draftId())).thenReturn(Optional.of(stored));

        ResearchDraftPlan replay = new FrozenResearchDraftPlanner(repository, mapper).plan(request);

        assertThat(replay).isEqualTo(expected);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsReplayWhenTheDeterministicDraftIdWouldBindDifferentFrozenInput() {
        UUID subjectId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ResearchDraftSelectionInput original = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchWorkRequest initialRequest = request(original, subjectId, requestId);
        ResearchDraftPlan expected = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper)
                .plan(initialRequest);
        ResearchDraftPlanRepository repository = mock(ResearchDraftPlanRepository.class);
        when(repository.findById(expected.draftId())).thenReturn(Optional.of(recordFor(expected)));
        ResearchDraftSelectionInput conflicting = new ResearchDraftSelectionInput(original.selectorVersion(),
                original.selectionSeed(), original.topicSelectionMode(), List.of(new ResearchDraftCandidate(subjectId,
                "PRIMARY", 1.0, "MATHEMATICS", List.of(new ResearchTopicOption("different-topic", 1.0)))),
                original.executionIntent(), original.topicHistory(), original.topicReselectionPolicy(),
                original.preTopicRandomDoubleDraws());

        assertThatThrownBy(() -> new FrozenResearchDraftPlanner(repository, mapper)
                .plan(request(conflicting, subjectId, requestId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound to a different plan");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ResearchDraftPlan planAfterJsonRoundTrip(ResearchDraftSelectionInput input, UUID subjectId) throws Exception {
        var context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
        ResearchWorkRequest reloaded = mapper.readValue(mapper.writeValueAsString(request), ResearchWorkRequest.class);
        return new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(reloaded);
    }

    private ResearchWorkRequest request(ResearchDraftSelectionInput input, UUID subjectId, UUID requestId) {
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        return new ResearchWorkRequest(requestId, UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
    }

    private ResearchDraftPlanRecord recordFor(ResearchDraftPlan plan) {
        return new ResearchDraftPlanRecord(plan.draftId(), plan.requestId(), plan.workspaceId(), plan.subjectId(),
                plan.domain(), plan.topic(), plan.eligibility(), plan.selectorVersion(), plan.inputFingerprint(),
                plan.executionKind(), mapper.valueToTree(plan.executionPayload()).toString(), plan.plannedAt());
    }

    private ResearchDraftPlan plan(ResearchDraftSelectionInput input, UUID subjectId) {
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(input));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 17,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
        return new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper).plan(request);
    }

    private static ResearchDraftSelectionInput withIntent(ResearchDraftSelectionInput standard,
                                                           ResearchDraftExecutionIntent intent,
                                                           int preTopicRandomDoubleDraws) {
        return new ResearchDraftSelectionInput(standard.selectorVersion(), standard.selectionSeed(),
                standard.topicSelectionMode(), standard.candidates(), intent, standard.topicHistory(),
                standard.topicReselectionPolicy(), preTopicRandomDoubleDraws);
    }

    private JsonNode reverseObjectFields(JsonNode input) {
        if (input.isObject()) {
            ObjectNode reordered = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            input.fieldNames().forEachRemaining(fields::add);
            Collections.reverse(fields);
            for (String field : fields) reordered.set(field, reverseObjectFields(input.get(field)));
            return reordered;
        }
        if (input.isArray()) {
            ArrayNode reordered = mapper.createArrayNode();
            input.forEach(value -> reordered.add(reverseObjectFields(value)));
            return reordered;
        }
        return input;
    }
}
