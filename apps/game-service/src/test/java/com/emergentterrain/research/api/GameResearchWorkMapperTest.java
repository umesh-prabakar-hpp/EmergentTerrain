package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.TopicCloneBackoff;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.StandardResearchConjectureQueueSelector;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchTopicHistoryEntry;
import com.emergentterrain.researchcontract.ResearchTopicHistorySnapshot;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameResearchWorkMapperTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void freezesTheLegacyWeightedStandardSelectionWithoutGameTypesInTheContract() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID botId = UUID.fromString("00000000-0000-0000-0000-000000000222");
        long revision = 17L;
        BotLifecycleState state = qualifiedAdult("MATHEMATICS", new double[] {0.1, 0.9, 0.1, 0.1, 0.1});
        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, false)
                .fromTick(roomId, List.of(bot(botId, state)), revision, null);

        ResearchDraftSelectionInput input = mapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);
        ResearchDraftSelection actual = StandardResearchDraftSelector.select(input);
        Random legacy = new Random(revision * 1337L + roomId.getLeastSignificantBits());
        for (int draw = 0; draw < 3; draw++) legacy.nextInt(1);
        int expectedIndex = MathPhysicsEngine.selectTopicIndex(state.getGenome().getDomain(),
                state.getGenome().selectTopic(legacy), false, legacy);

        assertThat(actual.subjectId()).isEqualTo(botId);
        assertThat(actual.eligibility()).isEqualTo("CANDIDATE");
        assertThat(actual.topic()).isEqualTo(MathPhysicsEngine.topicName("MATHEMATICS", expectedIndex));
        assertThat(request.contextSnapshot().path("draftSelection").path("selectorVersion").asText())
                .isEqualTo("legacy-standard-catalog-v1");
    }

    @Test
    void freezesTheLegacyUniformTopicModeAndMatureFallbackTier() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000333");
        UUID botId = UUID.fromString("00000000-0000-0000-0000-000000000444");
        long revision = 29L;
        BotLifecycleState state = qualifiedAdult("PHYSICS", new double[] {1, 1, 1, 1, 1});
        state.setResearchStatus(BotLifecycleState.ResearchStatus.UNQUALIFIED);
        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, true)
                .fromTick(roomId, List.of(bot(botId, state)), revision, null);

        ResearchDraftSelectionInput input = mapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);
        ResearchDraftSelection actual = StandardResearchDraftSelector.select(input);
        Random legacy = new Random(revision * 1337L + roomId.getLeastSignificantBits());
        for (int draw = 0; draw < 3; draw++) legacy.nextInt(1);
        int expectedIndex = MathPhysicsEngine.selectTopicIndex(state.getGenome().getDomain(),
                state.getGenome().selectTopic(legacy), true, legacy);

        assertThat(actual.eligibility()).isEqualTo("FALLBACK_CANDIDATE");
        assertThat(actual.topic()).isEqualTo(MathPhysicsEngine.topicName("PHYSICS", expectedIndex));
    }

    @Test
    void freezesAParameterizedBranchAsAGameFreeStudySpecification() throws Exception {
        BotLifecycleState state = qualifiedAdult("MATHEMATICS", new double[] {1, 1, 1, 1, 1});
        state.setPendingDirective(new DynamicProblem("directive-1", "frozen parameterized study", 10, 40,
                "isPrime", "fraction_satisfying", 0.5, "The fraction exceeds the baseline"));
        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, false).fromTick(UUID.randomUUID(),
                List.of(bot(UUID.randomUUID(), state)), 31L, null);

        ResearchDraftSelectionInput input = mapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);

        assertThat(input.executionIntent().kind()).isEqualTo("DEFERRED_PARAMETERIZED_STUDY");
        ResearchStudySpecification study = mapper.treeToValue(input.executionIntent().payload(),
                ResearchStudySpecification.class);
        assertThat(study.specificationVersion()).isEqualTo(ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1);
        assertThat(study.studyId()).isEqualTo("directive-1");
        assertThat(study.rangeStart()).isEqualTo(10);
        assertThat(study.rangeEnd()).isEqualTo(40);
        assertThat(study.predicateExpression()).isEqualTo("isPrime");
        assertThat(study.expectedRelation()).isEqualTo("ABOVE_BASELINE");
    }

    @Test
    void freezesAFollowUpBranchAsAGameFreeParameterOverride() throws Exception {
        BotLifecycleState state = qualifiedAdult("PHYSICS", new double[] {1, 1, 1, 1, 1});
        state.setPendingFollowUp(new FollowUpParams(37, 2.5, 0.75, "prior bounded scan"));
        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, false).fromTick(UUID.randomUUID(),
                List.of(bot(UUID.randomUUID(), state)), 32L, null);

        ResearchDraftSelectionInput input = mapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);

        assertThat(input.executionIntent().kind()).isEqualTo("DEFERRED_FOLLOW_UP_STUDY");
        ResearchParameterOverride override = mapper.treeToValue(input.executionIntent().payload(),
                ResearchParameterOverride.class);
        assertThat(override.overrideVersion()).isEqualTo(ResearchParameterOverride.LEGACY_FOLLOW_UP_V1);
        assertThat(override.parameterOne()).isEqualTo(37);
        assertThat(override.parameterTwo()).isEqualTo(2.5);
        assertThat(override.predictedScore()).isEqualTo(0.75);
        assertThat(override.priorMethodology()).isEqualTo("prior bounded scan");
    }

    @Test
    void mapsAPersistedConjectureToAGameFreeFrozenSpecification() throws Exception {
        UUID conjectureId = UUID.randomUUID();
        Conjecture conjecture = mock(Conjecture.class);
        when(conjecture.id()).thenReturn(conjectureId);
        when(conjecture.domain()).thenReturn("math");
        when(conjecture.kind()).thenReturn("BOUND");
        when(conjecture.lhs()).thenReturn("left_metric");
        when(conjecture.rhs()).thenReturn("right_metric");
        when(conjecture.rhs2()).thenReturn("secondary_metric");
        when(conjecture.k()).thenReturn(1.25);
        when(conjecture.k2()).thenReturn(0.5);
        when(conjecture.c()).thenReturn(-0.25);
        when(conjecture.confirmedRuns()).thenReturn(7);
        when(conjecture.p99Slack()).thenReturn(0.08);
        when(conjecture.captureWindowStart()).thenReturn(1000L);

        ResearchDraftExecutionIntent intent = new GameResearchWorkMapper(mapper, false)
                .frozenConjectureIntent(conjecture, ResearchConjectureSpecification.PROMOTED_ANOMALY);

        assertThat(intent.kind()).isEqualTo("DEFERRED_CONJECTURE_STUDY");
        ResearchConjectureSpecification specification = mapper.treeToValue(intent.payload(),
                ResearchConjectureSpecification.class);
        assertThat(specification.sourceId()).isEqualTo(conjectureId.toString());
        assertThat(specification.leftExpression()).isEqualTo("left_metric");
        assertThat(specification.secondaryCoefficient()).isEqualTo(0.5);
        assertThat(specification.selectionReason()).isEqualTo("PROMOTED_ANOMALY");
    }

    @Test
    void freezesOrderedConjectureQueueCandidatesForReadOnlyPriorityParity() {
        Conjecture promoted = conjecture(UUID.randomUUID(), "promoted_lhs");
        Conjecture ordinary = conjecture(UUID.randomUUID(), "ordinary_lhs");
        ResearchConjectureQueueSnapshot snapshot = new GameResearchWorkMapper(mapper, false)
                .frozenConjectureQueueSnapshot("math", 1.0, List.of(promoted), List.of(ordinary));

        var selected = StandardResearchConjectureQueueSelector.select(snapshot, new Random(7L));

        assertThat(selected.specification().leftExpression()).isEqualTo("promoted_lhs");
        assertThat(selected.preTopicRandomDoubleDraws()).isZero();
        assertThat(snapshot.ordinaryCandidates()).hasSize(1);
    }

    @Test
    void mapsTopicHistoryToGenericFrozenEntriesWithoutEnablingReselection() {
        ResearchPaper paper = mock(ResearchPaper.class);
        when(paper.domain()).thenReturn("MATHEMATICS");
        when(paper.topic()).thenReturn("prime density");
        when(paper.status()).thenReturn("REJECTED");
        when(paper.peerScore()).thenReturn(0.25);
        ResearchDraftSelectionInput input = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 1L,
                "WEIGHTED", List.of());

        ResearchTopicReselectionPolicy policy = new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1",
                true, false, 3, 0.6, 0.7);
        ResearchDraftSelectionInput frozen = new GameResearchWorkMapper(mapper, false)
                .withTopicHistory(input, List.of(paper), policy);

        assertThat(frozen.topicHistory().snapshotVersion()).isEqualTo("legacy-topic-history-v1");
        assertThat(frozen.topicHistory().entries()).containsExactly(
                new ResearchTopicHistoryEntry("MATHEMATICS", "prime density", "REJECTED", 0.25));
        assertThat(frozen.topicReselectionPolicy()).isEqualTo(policy);
    }

    @Test
    void genericFrozenHistoryReplayMatchesTheLegacyRejectionOnlyBackoffDraws() {
        UUID subjectId = UUID.randomUUID();
        List<ResearchTopicOption> topics = List.of(new ResearchTopicOption("cooled", 1.0),
                new ResearchTopicOption("fresh", 1.0));
        ResearchDraftSelectionInput input = new ResearchDraftSelectionInput("legacy-standard-catalog-v1", 43L,
                "WEIGHTED", List.of(new com.emergentterrain.researchcontract.ResearchDraftCandidate(subjectId,
                "PRIMARY", 1.0, "MATHEMATICS", topics)), ResearchDraftExecutionIntent.standardCatalog(),
                new ResearchTopicHistorySnapshot("legacy-topic-history-v1", List.of(
                        new ResearchTopicHistoryEntry("MATHEMATICS", "cooled", "REJECTED", 0.2))),
                new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1", true, false, 2, 0.0, 1.0));

        ResearchDraftSelection actual = StandardResearchDraftSelector.select(input);
        Random legacyRandom = new Random(input.selectionSeed());
        var winner = StandardResearchDraftSelector.selectCandidate(input, legacyRandom);
        var initial = StandardResearchDraftSelector.selectTopic(input, winner, legacyRandom);
        ResearchPaper rejected = mock(ResearchPaper.class);
        when(rejected.domain()).thenReturn("MATHEMATICS");
        when(rejected.topic()).thenReturn("cooled");
        when(rejected.status()).thenReturn("REJECTED");
        int expectedIndex = TopicCloneBackoff.applyBackoff(winner.topicOptions().indexOf(initial), initial.topic(),
                winner.domain(), List.of(rejected), () -> {
                    ResearchTopicOption next = StandardResearchDraftSelector.selectTopic(input, winner, legacyRandom);
                    return new TopicCloneBackoff.TopicCandidate(winner.topicOptions().indexOf(next), next.topic());
                }, 2, legacyRandom);

        assertThat(actual.topic()).isEqualTo(winner.topicOptions().get(expectedIndex).topic());
    }

    @Test
    void freezesHistoryAndPolicyIntoTheWorkRequestOnlyWhenExplicitlyRequested() throws Exception {
        BotLifecycleState state = qualifiedAdult("MATHEMATICS", new double[] {1, 1, 1, 1, 1});
        ResearchPaper paper = mock(ResearchPaper.class);
        when(paper.domain()).thenReturn("MATHEMATICS");
        when(paper.topic()).thenReturn("Prime Density");
        when(paper.status()).thenReturn("REJECTED");
        when(paper.peerScore()).thenReturn(0.25);
        ResearchTopicReselectionPolicy policy = new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1",
                true, false, 2, 0.6, 0.7);

        ResearchWorkRequest request = new GameResearchWorkMapper(mapper, false).fromTick(UUID.randomUUID(),
                List.of(bot(UUID.randomUUID(), state)), 33L, null, List.of(paper), policy);
        ResearchDraftSelectionInput input = mapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);

        assertThat(input.topicHistory().entries()).hasSize(1);
        assertThat(input.topicReselectionPolicy()).isEqualTo(policy);
    }

    private static BotLifecycleState qualifiedAdult(String domain, double[] weights) {
        BotLifecycleState state = new BotLifecycleState();
        state.setGenome(new BotGenome(domain, "COMPUTATIONAL", weights, 0.2, 1));
        state.setLifecycle(BotLifecycleState.Phase.ADULT);
        state.setResearchStatus(BotLifecycleState.ResearchStatus.QUALIFIED);
        state.setReputation(0.8);
        return state;
    }

    private RoomBot bot(UUID id, BotLifecycleState state) throws Exception {
        RoomBot bot = mock(RoomBot.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getType()).thenReturn("AGENT");
        when(bot.getLifecycleJson()).thenReturn(mapper.writeValueAsString(state));
        return bot;
    }

    private static Conjecture conjecture(UUID id, String lhs) {
        Conjecture conjecture = mock(Conjecture.class);
        when(conjecture.id()).thenReturn(id);
        when(conjecture.domain()).thenReturn("math");
        when(conjecture.kind()).thenReturn("BOUND");
        when(conjecture.lhs()).thenReturn(lhs);
        when(conjecture.rhs()).thenReturn("rhs");
        when(conjecture.k()).thenReturn(1.0);
        when(conjecture.k2()).thenReturn(0.0);
        when(conjecture.c()).thenReturn(0.0);
        when(conjecture.confirmedRuns()).thenReturn(1);
        when(conjecture.p99Slack()).thenReturn(0.1);
        return conjecture;
    }
}
