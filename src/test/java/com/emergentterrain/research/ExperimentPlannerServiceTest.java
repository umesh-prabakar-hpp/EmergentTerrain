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
import static org.mockito.ArgumentMatchers.anyDouble;
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
    private com.emergentterrain.repo.ResearchPaperRepository papers;
    private BotGenome                       genome;
    private Random                          rng;

    @BeforeEach
    void setUp() {
        programs      = mock(ResearchProgramRepository.class);
        openQuestions = mock(ProgramOpenQuestionRepository.class);
        citations     = mock(PaperCitationRepository.class);
        surprises     = mock(SurpriseEventRepository.class);
        papers        = mock(com.emergentterrain.repo.ResearchPaperRepository.class);
        genome        = BotGenome.initialise(UUID.randomUUID(), 0);
        rng           = new Random(42);
    }

    @Test
    void disabledPlannerReturnsEmpty() {
        ExperimentPlannerService planner = new ExperimentPlannerService(
                false, false, programs, openQuestions, citations, surprises, papers, new WorldDataQueryService());
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

    // ---- planWorldIntervention ----

    @Test
    void planWorldInterventionReturnsEmptyWhenFlagOff() {
        ExperimentPlannerService planner = enabled();   // planner on, world flag off
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "GOVERNANCE");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "GOVERNANCE",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));

        assertThat(planner.planWorldIntervention(author, "GOVERNANCE")).isEmpty();
    }

    @Test
    void planWorldInterventionReturnsEmptyWhenPlannerDisabled() {
        // Even with world flag on, the planner-off short-circuit takes precedence.
        ExperimentPlannerService planner = new ExperimentPlannerService(
                false, true, programs, openQuestions, citations, surprises, papers, new WorldDataQueryService());
        UUID author = UUID.randomUUID();
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(activeProgram(author, "GOVERNANCE")));

        assertThat(planner.planWorldIntervention(author, "GOVERNANCE")).isEmpty();
    }

    @Test
    void planWorldInterventionReturnsEmptyWhenNoProgramExists() {
        ExperimentPlannerService planner = worldInterventionEnabled();
        UUID author = UUID.randomUUID();
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(planner.planWorldIntervention(author, "GOVERNANCE")).isEmpty();
    }

    @Test
    void planWorldInterventionReturnsEmptyWhenNoOpenQuestionMentionsWorldVariable() {
        ExperimentPlannerService planner = worldInterventionEnabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "GOVERNANCE");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "GOVERNANCE",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestionWithText(program.getId(), 0.9, "Does X cause Y?")));

        assertThat(planner.planWorldIntervention(author, "GOVERNANCE")).isEmpty();
    }

    @Test
    void planWorldInterventionMapsEducationVariableToServicesPolicy() {
        ExperimentPlannerService planner = worldInterventionEnabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "GOVERNANCE");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "GOVERNANCE",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestionWithText(program.getId(), 0.9,
                                "Does increasing federal.budget.education raise public trust?")));

        Optional<WorldInterventionService.InterventionProposal> proposal =
                planner.planWorldIntervention(author, "GOVERNANCE");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.SERVICES);
        assertThat(proposal.get().proposingBotId()).isEqualTo(author);
        assertThat(proposal.get().researchProgramId()).isEqualTo(program.getId());
        assertThat(proposal.get().hypothesis())
                .contains("federal.budget.education");
    }

    @Test
    void planWorldInterventionMapsInfrastructureBudgetToInfrastructurePolicy() {
        Optional<WorldInterventionService.InterventionProposal> proposal =
                proposalForQuestion("Will higher federal.budget.infrastructure lift service access?");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.INFRASTRUCTURE);
    }

    @Test
    void planWorldInterventionMapsWelfareBudgetToHousingPolicy() {
        Optional<WorldInterventionService.InterventionProposal> proposal =
                proposalForQuestion("Does federal.budget.welfare affect mean state trust?");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.HOUSING);
    }

    @Test
    void planWorldInterventionMapsJusticeBudgetToRightsProcessPolicy() {
        Optional<WorldInterventionService.InterventionProposal> proposal =
                proposalForQuestion("Does federal.budget.justice raise institutional trust?");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.RIGHTS_PROCESS);
    }

    @Test
    void planWorldInterventionFallsBackToServicesForNonBudgetWorldVariables() {
        // federal.public_trust is a known variable but not a budget axis →
        // SERVICES is the default lever (it bumps health+education which feed
        // public_trust aggregates).
        Optional<WorldInterventionService.InterventionProposal> proposal =
                proposalForQuestion("Can we shift federal.public_trust by policy alone?");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.SERVICES);
    }

    @Test
    void planWorldInterventionPicksHighestUncertaintyQuestionFirst() {
        ExperimentPlannerService planner = worldInterventionEnabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "GOVERNANCE");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "GOVERNANCE",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));

        ProgramOpenQuestion low = openQuestionWithText(program.getId(), 0.20,
                "Does federal.budget.infrastructure raise civic outcomes?");
        ProgramOpenQuestion high = openQuestionWithText(program.getId(), 0.95,
                "Does federal.budget.welfare lift mean state trust?");
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(low, high));

        Optional<WorldInterventionService.InterventionProposal> proposal =
                planner.planWorldIntervention(author, "GOVERNANCE");

        assertThat(proposal).isPresent();
        // High-uncertainty question wins → maps to HOUSING (welfare bump).
        assertThat(proposal.get().policyTarget())
                .isEqualTo(WorldInterventionService.PolicyTarget.HOUSING);
        assertThat(proposal.get().hypothesis()).contains("welfare");
    }

    // ---- planBayesianOptimization (Phase 2b MVP) ----

    @Test
    void bayesianOptimizationFiresWhenBotHasStrongDomainHistory() {
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "MATHEMATICS");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "MATHEMATICS",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        // One open question so planAdaptiveSweep also has a target — we test
        // that BO wins by checking the strategy on the returned plan.
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestion(program.getId(), 0.7)));
        // No contradictions, no surprises, no all-low-uncertainty exploration.
        when(citations.findByRoomIdAndRelationshipType(any(), any())).thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(any(), anyDouble()))
                .thenReturn(List.of());
        // Bot has 4 recent papers in MATHEMATICS with mean peerScore well
        // above the 0.65 exploit threshold. Build the paper list OUTSIDE the
        // when(...).thenReturn(...) call so Mockito doesn't see nested stubbing.
        List<ResearchPaper> strongHistory = List.of(
                paperWithScore(author, "MATHEMATICS", 0.80),
                paperWithScore(author, "MATHEMATICS", 0.72),
                paperWithScore(author, "MATHEMATICS", 0.85),
                paperWithScore(author, "MATHEMATICS", 0.78));
        when(papers.findByAuthorBotId(author.toString())).thenReturn(strongHistory);

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy())
                .isEqualTo(ExperimentPlannerService.Strategy.BAYESIAN_OPTIMIZATION);
    }

    @Test
    void bayesianOptimizationFallsThroughWhenMeanScoreBelowThreshold() {
        // Same setup as above but with weak peer scores → BO declines,
        // ADAPTIVE_SWEEP takes over (the default fallback).
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "MATHEMATICS");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "MATHEMATICS",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestion(program.getId(), 0.7)));
        when(citations.findByRoomIdAndRelationshipType(any(), any())).thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(any(), anyDouble()))
                .thenReturn(List.of());
        List<ResearchPaper> weakHistory = List.of(
                paperWithScore(author, "MATHEMATICS", 0.40),
                paperWithScore(author, "MATHEMATICS", 0.55),
                paperWithScore(author, "MATHEMATICS", 0.50));
        when(papers.findByAuthorBotId(author.toString())).thenReturn(weakHistory);

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan).isPresent();
        assertThat(plan.get().strategy())
                .isEqualTo(ExperimentPlannerService.Strategy.ADAPTIVE_SWEEP);
    }

    @Test
    void bayesianOptimizationFallsThroughBelowMinPaperCount() {
        // Bot has only 2 strong papers — below the 3-paper minimum.
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "MATHEMATICS");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "MATHEMATICS",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestion(program.getId(), 0.7)));
        when(citations.findByRoomIdAndRelationshipType(any(), any())).thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(any(), anyDouble()))
                .thenReturn(List.of());
        List<ResearchPaper> tooFewPapers = List.of(
                paperWithScore(author, "MATHEMATICS", 0.85),
                paperWithScore(author, "MATHEMATICS", 0.90));
        when(papers.findByAuthorBotId(author.toString())).thenReturn(tooFewPapers);

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan.get().strategy())
                .isEqualTo(ExperimentPlannerService.Strategy.ADAPTIVE_SWEEP);
    }

    @Test
    void bayesianOptimizationSkipsPapersFromOtherDomains() {
        // 4 strong papers but they're all in PHYSICS, not the MATHEMATICS
        // program — domain filter should reject them, BO declines.
        ExperimentPlannerService planner = enabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "MATHEMATICS");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "MATHEMATICS",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestion(program.getId(), 0.7)));
        when(citations.findByRoomIdAndRelationshipType(any(), any())).thenReturn(List.of());
        when(surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(any(), anyDouble()))
                .thenReturn(List.of());
        List<ResearchPaper> wrongDomainHistory = List.of(
                paperWithScore(author, "PHYSICS", 0.85),
                paperWithScore(author, "PHYSICS", 0.90),
                paperWithScore(author, "PHYSICS", 0.80));
        when(papers.findByAuthorBotId(author.toString())).thenReturn(wrongDomainHistory);

        Optional<ExperimentPlannerService.ExperimentPlan> plan =
                planner.planNext(author, "MATHEMATICS", genome, rng);

        assertThat(plan.get().strategy())
                .isEqualTo(ExperimentPlannerService.Strategy.ADAPTIVE_SWEEP);
    }

    // ---- helpers ----

    private ExperimentPlannerService enabled() {
        return new ExperimentPlannerService(true, false, programs, openQuestions, citations, surprises, papers, new WorldDataQueryService());
    }

    private ExperimentPlannerService worldInterventionEnabled() {
        return new ExperimentPlannerService(true, true, programs, openQuestions, citations, surprises, papers, new WorldDataQueryService());
    }

    private Optional<WorldInterventionService.InterventionProposal> proposalForQuestion(String questionText) {
        ExperimentPlannerService planner = worldInterventionEnabled();
        UUID author = UUID.randomUUID();
        ResearchProgram program = activeProgram(author, "GOVERNANCE");
        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(author, "GOVERNANCE",
                ResearchProgram.Status.ACTIVE)).thenReturn(Optional.of(program));
        when(openQuestions.findByProgramIdAndStatus(program.getId(),
                ProgramOpenQuestion.Status.OPEN)).thenReturn(List.of(
                        openQuestionWithText(program.getId(), 0.9, questionText)));
        return planner.planWorldIntervention(author, "GOVERNANCE");
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
        return openQuestionWithText(programId, uncertainty, "test question?");
    }

    private static ResearchPaper paperWithScore(UUID authorBotId, String domain, double peerScore) {
        ResearchPaper p = mock(ResearchPaper.class);
        when(p.authorBotId()).thenReturn(authorBotId.toString());
        when(p.domain()).thenReturn(domain);
        when(p.peerScore()).thenReturn(peerScore);
        when(p.publishedAt()).thenReturn(Instant.now());
        return p;
    }

    private static ProgramOpenQuestion openQuestionWithText(UUID programId, double uncertainty, String text) {
        ProgramOpenQuestion q = new ProgramOpenQuestion(programId, text, Instant.now());
        q.setUncertainty(uncertainty);
        try {
            java.lang.reflect.Field f = ProgramOpenQuestion.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(q, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return q;
    }
}
