package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.MathPhysicsEngineDelegate;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PopulationEcologyService;
import com.emergentterrain.research.ResearchCycleService;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.Researcher;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ConjectureQueue;
import com.emergentterrain.research.conjecture.ConjectureTopicProvider;
import com.emergentterrain.researchcontract.FrozenResearchCyclePlanner;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchSelectionSeed;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.StandardResearchDraftSelector;
import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pins legacy conjecture branches against the frozen generic planner. */
class FrozenResearchCycleLegacyParityTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void frozenPlannerElectsTheSameAuthorBeforeTheLegacyOrdinaryConjectureBranch() throws Exception {
        assertAuthorParity(false);
    }

    @Test
    void frozenPlannerElectsTheSameAuthorBeforeTheLegacyPromotedConjectureBranch() throws Exception {
        assertAuthorParity(true);
    }

    @Test
    void ordinaryQueueMissPreservesTheLegacyProbabilityDrawBeforeFrozenCatalogSelection() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000711");
        long gameTick = 45L;
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000712");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000713");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000714");
        BotLifecycleState first = qualifiedAdult(0.20);
        BotLifecycleState second = qualifiedAdult(0.85);
        BotLifecycleState third = qualifiedAdult(0.45);
        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(firstId, first), bot(secondId, second), bot(thirdId, third)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);

        Random legacyRandom = new Random(ResearchSelectionSeed.from(roomId, gameTick));
        ResearchDraftCandidate legacyAuthor = StandardResearchDraftSelector.selectCandidate(input, legacyRandom);
        assertThat(missProvider().selectConjecture("MATHEMATICS", legacyRandom)).isEmpty();
        ResearchDraftSelection legacyCatalog = StandardResearchDraftSelector.selectForCandidate(
                input, legacyAuthor, legacyRandom);

        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(input, null,
                new ResearchConjectureQueueSelection(null, 1), null);

        assertThat(frozen.electedAuthor().subjectId()).isEqualTo(legacyAuthor.subjectId());
        assertThat(frozen.catalogSelection()).isEqualTo(legacyCatalog);
        assertThat(frozen.branch().preTopicRandomDoubleDraws()).isEqualTo(1);
    }

    @Test
    void directLegacyStandardCycleProducesTheFrozenAuthorAndCatalogTopic() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000721");
        long gameTick = 46L;
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000722");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000723");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000724");
        BotLifecycleState first = qualifiedAdult(0.20);
        BotLifecycleState second = qualifiedAdult(0.85);
        BotLifecycleState third = qualifiedAdult(0.45);
        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(firstId, first), bot(secondId, second), bot(thirdId, third)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(input,
                null, null, null);
        AtomicReference<PaperDraft> legacyDraft = new AtomicReference<>();
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.setPhysicsEngineDelegate(new StandardCycleStubDelegate());

        cycle.runCycle(roomId, List.of(new Researcher(firstId.toString(), first),
                        new Researcher(secondId.toString(), second), new Researcher(thirdId.toString(), third)),
                new Random(ResearchSelectionSeed.from(roomId, gameTick)), gameTick,
                new PopulationEcologyService.PopulationParams(10, 100L, 100L, 0.1, 0.5, 1.0),
                false, capture(legacyDraft));

        assertThat(legacyDraft.get()).isNotNull();
        assertThat(legacyDraft.get().authorBotId()).isEqualTo(frozen.electedAuthor().subjectId().toString());
        assertThat(legacyDraft.get().domain()).isEqualTo(frozen.catalogSelection().domain());
        assertThat(legacyDraft.get().topicName()).isEqualTo(frozen.catalogSelection().topic());
    }

    @Test
    void directLegacyColdStartFallbackProducesTheFrozenAuthorAndCatalogTopic() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000731");
        long gameTick = 47L;
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000732");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000733");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000734");
        BotLifecycleState first = matureUnqualified(0.20);
        BotLifecycleState second = matureUnqualified(0.85);
        BotLifecycleState third = matureUnqualified(0.45);
        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(firstId, first), bot(secondId, second), bot(thirdId, third)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(input,
                null, null, null);
        AtomicReference<PaperDraft> legacyDraft = new AtomicReference<>();
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.setPhysicsEngineDelegate(new StandardCycleStubDelegate());

        cycle.runCycle(roomId, List.of(new Researcher(firstId.toString(), first),
                        new Researcher(secondId.toString(), second), new Researcher(thirdId.toString(), third)),
                new Random(ResearchSelectionSeed.from(roomId, gameTick)), gameTick,
                new PopulationEcologyService.PopulationParams(10, 100L, 100L, 0.1, 0.5, 1.0),
                false, capture(legacyDraft));

        assertThat(frozen.catalogSelection().eligibility()).isEqualTo("FALLBACK_CANDIDATE");
        assertThat(legacyDraft.get()).isNotNull();
        assertThat(legacyDraft.get().authorBotId()).isEqualTo(frozen.electedAuthor().subjectId().toString());
        assertThat(legacyDraft.get().domain()).isEqualTo(frozen.catalogSelection().domain());
        assertThat(legacyDraft.get().topicName()).isEqualTo(frozen.catalogSelection().topic());
    }

    @Test
    void directLegacyFollowUpRetainsTheFrozenCatalogSelectionAndOverridesOnlyParameters() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000741");
        long gameTick = 48L;
        UUID authorId = UUID.fromString("00000000-0000-0000-0000-000000000742");
        BotLifecycleState author = qualifiedAdult(0.85);
        author.setPendingFollowUp(new FollowUpParams(42, 1.5, 0.6, "prior bounded scan"));
        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(authorId, author)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(input,
                null, null, input.executionIntent());
        AtomicReference<PaperDraft> legacyDraft = new AtomicReference<>();
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.setPhysicsEngineDelegate(new StandardCycleStubDelegate());

        cycle.runCycle(roomId, List.of(new Researcher(authorId.toString(), author)),
                new Random(ResearchSelectionSeed.from(roomId, gameTick)), gameTick,
                new PopulationEcologyService.PopulationParams(10, 100L, 100L, 0.1, 0.5, 1.0),
                false, capture(legacyDraft));

        assertThat(frozen.catalogSelection()).isNotNull();
        assertThat(legacyDraft.get()).isNotNull();
        assertThat(legacyDraft.get().authorBotId()).isEqualTo(frozen.electedAuthor().subjectId().toString());
        assertThat(legacyDraft.get().topicName()).isEqualTo(frozen.catalogSelection().topic());
        assertThat(legacyDraft.get().isFollowUp()).isTrue();
        assertThat(legacyDraft.get().param1()).isEqualTo(42);
        assertThat(legacyDraft.get().param2()).isEqualTo(1.5);
    }

    @Test
    void directLegacyParameterizedStudyAndFrozenPlannerElectTheSameTerminalAuthor() throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000751");
        long gameTick = 49L;
        UUID authorId = UUID.fromString("00000000-0000-0000-0000-000000000752");
        BotLifecycleState author = qualifiedAdult(0.85);
        author.setPendingDirective(new DynamicProblem("study-8", "bounded prime study", 10, 30,
                "isPrime", "fraction_satisfying", 0.5, "Prime density is above the baseline",
                DynamicProblem.ExpectedRelation.ABOVE_BASELINE, null));
        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(authorId, author)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(input,
                input.executionIntent(), null, null);
        ResearchCycleService cycle = new ResearchCycleService();

        cycle.runCycle(roomId, List.of(new Researcher(authorId.toString(), author)),
                new Random(ResearchSelectionSeed.from(roomId, gameTick)), gameTick,
                new PopulationEcologyService.PopulationParams(10, 100L, 100L, 0.1, 0.5, 1.0),
                false, capture(new AtomicReference<>()));

        assertThat(frozen.electedAuthor().subjectId()).isEqualTo(authorId);
        assertThat(frozen.catalogSelection()).isNull();
        assertThat(frozen.branch().executionIntent().kind())
                .isEqualTo("DEFERRED_PARAMETERIZED_STUDY");
        assertThat(author.getPendingDirective()).isNull();
    }

    private void assertAuthorParity(boolean promoted) throws Exception {
        UUID roomId = UUID.fromString("00000000-0000-0000-0000-000000000701");
        long gameTick = 44L;
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000702");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000703");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000704");
        BotLifecycleState first = qualifiedAdult(0.20);
        BotLifecycleState second = qualifiedAdult(0.85);
        BotLifecycleState third = qualifiedAdult(0.45);

        ResearchWorkRequest request = new GameResearchWorkMapper(objectMapper, false).fromTick(roomId,
                List.of(bot(firstId, first), bot(secondId, second), bot(thirdId, third)), gameTick, null);
        ResearchDraftSelectionInput input = objectMapper.treeToValue(
                request.contextSnapshot().path("draftSelection"), ResearchDraftSelectionInput.class);
        ResearchConjectureQueueSelection frozenConjecture = new ResearchConjectureQueueSelection(
                specification("legacy-conjecture", promoted), promoted ? 0 : 1);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(
                input, null, frozenConjecture, null);

        Conjecture legacyConjecture = new Conjecture("math", "happy_density", "prime_density", 0.0, 0.156);
        legacyConjecture.setConfirmedRuns(1);
        legacyConjecture.setP99Slack(0.1);
        AtomicInteger investigated = new AtomicInteger();
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.setConjectureTopicProvider(provider(legacyConjecture, investigated, promoted));
        AtomicReference<PaperDraft> legacyDraft = new AtomicReference<>();
        cycle.runCycle(roomId, List.of(
                        new Researcher(firstId.toString(), first),
                        new Researcher(secondId.toString(), second),
                        new Researcher(thirdId.toString(), third)),
                new Random(ResearchSelectionSeed.from(roomId, gameTick)), gameTick,
                new PopulationEcologyService.PopulationParams(10, 100L, 100L, 0.1, 0.5, 1.0),
                true, capture(legacyDraft));

        assertThat(legacyDraft.get()).isNotNull();
        assertThat(UUID.fromString(legacyDraft.get().authorBotId())).isEqualTo(frozen.electedAuthor().subjectId());
        assertThat(frozen.catalogSelection()).isNull();
        assertThat(frozen.branch().conjectureSelection()).isEqualTo(frozenConjecture);
        assertThat(investigated).hasValue(1);
    }

    private ResearchCycleService.Context capture(AtomicReference<PaperDraft> draft) {
        return new ResearchCycleService.Context() {
            @Override public String nextPaperId(UUID roomId) { return "P-test-" + roomId; }
            @Override public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) { return List.of(); }
            @Override public void publishPaper(PaperDraft paper, NarrationResult narration) { draft.set(paper); }
            @Override public Consumer<PaperDraft> narratingCallback() { return null; }
            @Override public Function<Object[], Object> formulationCallback() { return null; }
        };
    }

    private ConjectureTopicProvider provider(Conjecture conjecture, AtomicInteger investigated, boolean promoted) {
        ConjectureQueue queue = new ConjectureQueue() {
            @Override public Optional<Conjecture> next(String domain) {
                return promoted ? Optional.empty() : Optional.of(conjecture);
            }
            @Override public List<Conjecture> nextN(String domain, int count) { return List.of(conjecture); }
            @Override public Optional<Conjecture> nextPromotedAnomaly(String domain) {
                return promoted ? Optional.of(conjecture) : Optional.empty();
            }
            @Override public void markInvestigated(Conjecture selected) { investigated.incrementAndGet(); }
        };
        return new ConjectureTopicProvider(Optional.of(queue), 1.0, "math");
    }

    private ConjectureTopicProvider missProvider() {
        ConjectureQueue queue = new ConjectureQueue() {
            @Override public Optional<Conjecture> next(String domain) { return Optional.empty(); }
            @Override public List<Conjecture> nextN(String domain, int count) { return List.of(); }
        };
        return new ConjectureTopicProvider(Optional.of(queue), 1.0, "math");
    }

    private BotLifecycleState qualifiedAdult(double reputation) {
        BotLifecycleState state = new BotLifecycleState();
        state.setGenome(new BotGenome("MATHEMATICS", "COMPUTATIONAL", new double[] {1, 1, 1, 1, 1}, 0.2, 1));
        state.setLifecycle(BotLifecycleState.Phase.ADULT);
        state.setResearchStatus(BotLifecycleState.ResearchStatus.QUALIFIED);
        state.setReputation(reputation);
        return state;
    }

    private BotLifecycleState matureUnqualified(double reputation) {
        BotLifecycleState state = qualifiedAdult(reputation);
        state.setResearchStatus(BotLifecycleState.ResearchStatus.UNQUALIFIED);
        return state;
    }

    private RoomBot bot(UUID id, BotLifecycleState state) throws Exception {
        RoomBot bot = mock(RoomBot.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getType()).thenReturn("AGENT");
        when(bot.getLifecycleJson()).thenReturn(objectMapper.writeValueAsString(state));
        return bot;
    }

    private static ResearchConjectureSpecification specification(String sourceId, boolean promoted) {
        return new ResearchConjectureSpecification("legacy-conjecture-v1", sourceId, "math", "BOUND",
                "happy_density", "prime_density", null, 0.0, 0.0, 0.156, 1, 0.1, null,
                promoted ? "PROMOTED_ANOMALY" : "QUEUE_CANDIDATE");
    }

    private static final class StandardCycleStubDelegate extends MathPhysicsEngineDelegate {
        StandardCycleStubDelegate() {
            super((JuliaMathClient) null);
        }

        @Override
        public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                                        int param1, double param2,
                                                        MathPhysicsEngine.SearchParams searchParams) {
            return new MathPhysicsEngine.ResearchResult("Deterministic parity result.",
                    100.0, 1.0, 1.0, 100.0, true, "Theory-consistent test result.",
                    MathPhysicsEngine.ClaimStatus.SUPPORTED, "Supported by the deterministic stub.",
                    "The test result is supported.", "test-equation");
        }
    }
}
