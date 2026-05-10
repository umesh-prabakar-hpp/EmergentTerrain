package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import com.emergentterrain.repo.ResearchProgramRepository;
import com.emergentterrain.repo.SurpriseEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Phase 2 Iter 5 ExperimentPlannerService — covers strategy
 * selection priority and the ADAPTIVE_SWEEP fallback. The state stores are
 * mocked so we control exactly which questions / contradictions / surprises
 * the planner sees.
 */
class ExperimentPlannerServiceTest {

    private ResearchProgramRepository       programs;
    private ProgramOpenQuestionRepository   openQuestions;
    private PaperCitationRepository         citations;
    private SurpriseEventRepository         surprises;
    private BotGenome                       genome;
    private Random                          rng;

    @BeforeEach
    void setUp() {
        programs      = mock(ResearchProgramRepository.class);
        openQuestions = mock(ProgramOpenQuestionRepository.class);
        citations     = mock(PaperCitationRepository.class);
        surprises     = mock(SurpriseEventRepository.class);
        genome        = BotGenome.initialise(UUID.randomUUID(), 0);
        rng           = new Random(42);
    }

    @Test
    void disabledPlannerReturnsEmpty() {
        ExperimentPlannerService planner = new ExperimentPlannerService(
                false, programs, openQuestions, citations, surprises);
        assertThat(planner.planNext(UUID.randomUUID(), "MATHEMATICS", genome, rng))
                .isEmpty();
    }

    @Test
    void planNextReturnsEmptyWhenNoProgramExists() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(eq(author), eq("MATHEMATICS"),
                eq(ResearchProgram.Status.ACTIVE))).thenReturn(Optional.empty());

        assertThat(planner.planNext(author, "MATHEMATICS", genome, rng)).isEmpty();
    }

    @Test
    void adaptiveSweepReturnsPlanWhenOpenQuestionExists() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram prog = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(prog));
        when(citations.findByRoomIdAndRelationshipType(any(), eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
                any(), any(Double.class))).thenReturn(List.of());
        ProgramOpenQuestion q = openQuestion(prog.getId(), 0.9);
        when(openQuestions.findByProgramIdAndStatus(eq(prog.getId()),
                eq(ProgramOpenQuestion.Status.OPEN))).thenReturn(List.of(q));

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy()).isEqualTo(ExperimentPlannerService.Strategy.ADAPTIVE_SWEEP);
        assertThat(plan.get().targetQuestionId()).isEqualTo(q.getId());
        assertThat(plan.get().param1()).isBetween(10, 99);
        assertThat(plan.get().param2()).isBetween(1.0, 9.0);
    }

    @Test
    void contradictionTargetWinsOverAdaptiveSweep() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram prog = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(prog));
        when(citations.findByRoomIdAndRelationshipType(eq(prog.getRoomId()),
                eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of(new PaperCitation("P-a", "P-b", prog.getRoomId(),
                        Instant.now(), RelationshipType.CONTRADICTS, null, 1.0)));

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy())
                .isEqualTo(ExperimentPlannerService.Strategy.CONTRADICTION_TARGET);
    }

    @Test
    void replicationWinsOverAdaptiveSweepWhenSurpriseRecorded() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram prog = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(prog));
        when(citations.findByRoomIdAndRelationshipType(any(), eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
                eq(prog.getRoomId()), any(Double.class)))
                .thenReturn(List.of(new SurpriseEvent("P-surprise", UUID.randomUUID(),
                        prog.getRoomId(), "MATHEMATICS",
                        0.5, 4.5, SurpriseEvent.Source.PRIOR, Instant.now())));

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy()).isEqualTo(ExperimentPlannerService.Strategy.REPLICATION);
    }

    @Test
    void explorationFiresWhenAllQuestionsHaveLowUncertainty() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram prog = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(prog));
        when(citations.findByRoomIdAndRelationshipType(any(), eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
                any(), any(Double.class))).thenReturn(List.of());
        when(openQuestions.findByProgramIdAndStatus(eq(prog.getId()),
                eq(ProgramOpenQuestion.Status.OPEN)))
                .thenReturn(List.of(openQuestion(prog.getId(), 0.10),
                                    openQuestion(prog.getId(), 0.05)));

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy()).isEqualTo(ExperimentPlannerService.Strategy.EXPLORATION);
    }

    @Test
    void recordOutcomeMarksQuestionResolvedWhenUncertaintyDropsBelowFloor() {
        ExperimentPlannerService planner = enabled();
        ProgramOpenQuestion q = openQuestion(UUID.randomUUID(), 0.05);
        AtomicReference<ProgramOpenQuestion> saved = new AtomicReference<>();
        when(openQuestions.findById(q.getId())).thenReturn(Optional.of(q));
        when(openQuestions.save(any(ProgramOpenQuestion.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        // surprise=0 → full reduction; uncertainty 0.05 → drops below 0.1 → RESOLVED.
        planner.recordOutcome(q.getId(), 0.0);

        assertThat(saved.get().getStatus()).isEqualTo(ProgramOpenQuestion.Status.RESOLVED);
        assertThat(saved.get().getUncertainty()).isLessThan(0.1);
    }

    @Test
    void recordOutcomeMovesQuestionToBeingInvestigated() {
        ExperimentPlannerService planner = enabled();
        ProgramOpenQuestion q = openQuestion(UUID.randomUUID(), 0.9);
        AtomicReference<ProgramOpenQuestion> saved = new AtomicReference<>();
        when(openQuestions.findById(q.getId())).thenReturn(Optional.of(q));
        when(openQuestions.save(any(ProgramOpenQuestion.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        planner.recordOutcome(q.getId(), 1.0);  // moderate surprise

        assertThat(saved.get().getStatus())
                .isEqualTo(ProgramOpenQuestion.Status.BEING_INVESTIGATED);
        assertThat(saved.get().getUncertainty()).isLessThan(0.9);
    }

    // ---- helpers ----

    private ExperimentPlannerService enabled() {
        return new ExperimentPlannerService(true, programs, openQuestions, citations, surprises);
    }

    private static ResearchProgram activeProgram(UUID owner, String domain) {
        ResearchProgram p = new ResearchProgram(
                owner, UUID.randomUUID(), domain,
                "Test Program", "objective", Instant.now());
        try {
            java.lang.reflect.Field f = ResearchProgram.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return p;
    }

    private static ProgramOpenQuestion openQuestion(UUID programId, double uncertainty) {
        ProgramOpenQuestion q = new ProgramOpenQuestion(programId, "test question?", Instant.now());
        q.setUncertainty(uncertainty);
        try {
            java.lang.reflect.Field f = ProgramOpenQuestion.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(q, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return q;
    }
}
