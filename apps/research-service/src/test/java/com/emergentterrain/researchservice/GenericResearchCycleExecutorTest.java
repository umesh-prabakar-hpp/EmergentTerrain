package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenericResearchCycleExecutorTest {
    @Test
    void producesAReproducibleGameAgnosticBaseline() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(FrozenDraftSelectionFixtures.primaryInput(subjectId)));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 7,
                Instant.parse("2026-08-05T12:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);
        FrozenResearchDraftPlanner planner = new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper);
        ResearchResult result = new GenericResearchCycleExecutor(mapper, planner,
                new FrozenResearchBranchPreparer(mapper), executionPreparer(mapper)).execute(request);
        assertThat(result.outcome()).isEqualTo("DRAFT_CANDIDATE_PLANNED");
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.findings().path("subjectCount").asInt()).isEqualTo(1);
        assertThat(result.findings().path("sourceRevision").asLong()).isEqualTo(7L);
        assertThat(result.findings().path("draftEligibility").asText()).isEqualTo("CANDIDATE");
        assertThat(result.findings().path("draftSelectorVersion").asText()).isEqualTo("legacy-standard-catalog-v1");
        assertThat(result.findings().path("draftExecutionReadiness").asText())
                .isEqualTo("AWAITING_COMPUTATION_INPUTS");
    }

    @Test
    void preparesAValidFrozenFollowUpBranchWithoutClaimingComputation() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ObjectNode context = mapper.createObjectNode();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchParameterOverride override = new ResearchParameterOverride(ResearchParameterOverride.LEGACY_FOLLOW_UP_V1,
                42, 1.5, 0.6, "prior scan");
        context.set("draftSelection", mapper.valueToTree(new ResearchDraftSelectionInput(
                standard.selectorVersion(), standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent("DEFERRED_FOLLOW_UP_STUDY",
                        mapper.valueToTree(override)))));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 8,
                Instant.parse("2026-08-06T12:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchResult result = new GenericResearchCycleExecutor(mapper,
                new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper),
                new FrozenResearchBranchPreparer(mapper), executionPreparer(mapper)).execute(request);

        assertThat(result.outcome()).isEqualTo("DRAFT_BRANCH_PREPARED");
        assertThat(result.findings().path("draftExecutionKind").asText()).isEqualTo("DEFERRED_FOLLOW_UP_STUDY");
        assertThat(result.findings().path("draftExecutionStatus").asText()).isEqualTo("PREPARED");
        assertThat(result.findings().path("draftExecutionCanonicalPayload").path("parameterOne").asInt()).isEqualTo(42);
        assertThat(result.findings().path("draftExecutionReadiness").asText()).isEqualTo("PREPARED_NO_COMPUTATION");
    }

    @Test
    void preparesAValidFrozenParameterizedStudyWithoutStartingComputation() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ObjectNode context = mapper.createObjectNode();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchStudySpecification study = new ResearchStudySpecification(
                ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1, "study-7", "bounded predicate study",
                10, 50, "isPrime", "fraction_satisfying", 0.5, "The fraction is above the baseline",
                "ABOVE_BASELINE", null);
        context.set("draftSelection", mapper.valueToTree(new ResearchDraftSelectionInput(
                standard.selectorVersion(), standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent("DEFERRED_PARAMETERIZED_STUDY", mapper.valueToTree(study)))));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 9,
                Instant.parse("2026-08-06T12:01:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchResult result = new GenericResearchCycleExecutor(mapper,
                new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper),
                new FrozenResearchBranchPreparer(mapper), executionPreparer(mapper)).execute(request);

        assertThat(result.outcome()).isEqualTo("DRAFT_BRANCH_PREPARED");
        assertThat(result.findings().path("draftExecutionCanonicalPayload").path("studyId").asText())
                .isEqualTo("study-7");
        assertThat(result.findings().path("draftTopic").asText()).isEqualTo("study/study-7");
        assertThat(result.summary()).contains("without computation");
    }

    @Test
    void preparesAFrozenConjectureWithoutStartingVerificationOrPublication() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ObjectNode context = mapper.createObjectNode();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, UUID.randomUUID().toString(), "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.QUEUE_CANDIDATE);
        context.set("draftSelection", mapper.valueToTree(new ResearchDraftSelectionInput(
                standard.selectorVersion(), standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent("DEFERRED_CONJECTURE_STUDY", mapper.valueToTree(conjecture)))));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 10,
                Instant.parse("2026-08-06T12:02:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchResult result = new GenericResearchCycleExecutor(mapper,
                new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper),
                new FrozenResearchBranchPreparer(mapper), executionPreparer(mapper)).execute(request);

        assertThat(result.outcome()).isEqualTo("DRAFT_BRANCH_PREPARED");
        assertThat(result.findings().path("draftExecutionCanonicalPayload").path("leftExpression").asText())
                .isEqualTo("left_metric");
        assertThat(result.findings().path("draftTopic").asText())
                .isEqualTo("conjecture/" + conjecture.sourceId());
        assertThat(result.summary()).contains("without computation");
    }

    @Test
    void reportsCompactReplayProvenanceForAFrozenQueueObservation() {
        ObjectMapper mapper = new ObjectMapper();
        UUID subjectId = UUID.randomUUID();
        ResearchDraftSelectionInput standard = FrozenDraftSelectionFixtures.primaryInput(subjectId);
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "queue-audit-7", "math", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.PROMOTED_ANOMALY);
        ResearchConjectureQueueSnapshot snapshot = new ResearchConjectureQueueSnapshot(
                ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1, "math", 1.0, List.of(conjecture), List.of());
        ResearchDraftSelectionInput observed = new ResearchDraftSelectionInput(standard.selectorVersion(),
                standard.selectionSeed(), standard.topicSelectionMode(), standard.candidates(),
                new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                        mapper.valueToTree(conjecture)), standard.topicHistory(), standard.topicReselectionPolicy(),
                snapshot, 0);
        ObjectNode context = mapper.createObjectNode();
        context.set("draftSelection", mapper.valueToTree(observed));
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 11,
                Instant.parse("2026-08-06T12:03:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null)), context);

        ResearchResult result = new GenericResearchCycleExecutor(mapper,
                new FrozenResearchDraftPlanner(mock(ResearchDraftPlanRepository.class), mapper),
                new FrozenResearchBranchPreparer(mapper), executionPreparer(mapper)).execute(request);

        assertThat(result.findings().path("draftQueueObservation").path("replayed").asBoolean()).isTrue();
        assertThat(result.findings().path("draftQueueObservation").path("snapshotVersion").asText())
                .isEqualTo(ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1);
        assertThat(result.findings().path("draftQueueObservation").path("promotedCandidateCount").asInt()).isEqualTo(1);
        assertThat(result.findings().path("draftQueueObservation").path("ordinaryCandidateCount").asInt()).isZero();
    }

    private static FrozenResearchExecutionPreparer executionPreparer(ObjectMapper mapper) {
        return new FrozenResearchExecutionPreparer(mock(ResearchExecutionPreparationRepository.class), mapper);
    }
}
