package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
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
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchSelectionSeed;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
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

/** Pins the legacy ordinary-conjecture branch against the frozen generic planner. */
class FrozenResearchCycleLegacyParityTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void frozenPlannerElectsTheSameAuthorBeforeTheLegacyOrdinaryConjectureBranch() throws Exception {
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
                specification("legacy-conjecture"), 1);
        FrozenResearchCyclePlanner.FrozenResearchCyclePlan frozen = FrozenResearchCyclePlanner.plan(
                input, null, frozenConjecture, null);

        Conjecture legacyConjecture = new Conjecture("math", "happy_density", "prime_density", 0.0, 0.156);
        legacyConjecture.setConfirmedRuns(1);
        legacyConjecture.setP99Slack(0.1);
        AtomicInteger investigated = new AtomicInteger();
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.setConjectureTopicProvider(ordinaryProvider(legacyConjecture, investigated));
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

    private ConjectureTopicProvider ordinaryProvider(Conjecture conjecture, AtomicInteger investigated) {
        ConjectureQueue queue = new ConjectureQueue() {
            @Override public Optional<Conjecture> next(String domain) { return Optional.of(conjecture); }
            @Override public List<Conjecture> nextN(String domain, int count) { return List.of(conjecture); }
            @Override public void markInvestigated(Conjecture selected) { investigated.incrementAndGet(); }
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

    private RoomBot bot(UUID id, BotLifecycleState state) throws Exception {
        RoomBot bot = mock(RoomBot.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getType()).thenReturn("AGENT");
        when(bot.getLifecycleJson()).thenReturn(objectMapper.writeValueAsString(state));
        return bot;
    }

    private static ResearchConjectureSpecification specification(String sourceId) {
        return new ResearchConjectureSpecification("legacy-conjecture-v1", sourceId, "math", "BOUND",
                "happy_density", "prime_density", null, 0.0, 0.0, 0.156, 1, 0.1, null,
                "QUEUE_CANDIDATE");
    }
}
