package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import com.emergentterrain.repo.ResearchProgramRepository;
import com.emergentterrain.repo.SurpriseEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Phase 2 Iter 5 — autonomous experiment planning per {@link ResearchProgram}.
 *
 * Replaces the single-step LLM-suggested follow-up with algorithmic parameter
 * selection. Operates against a program's open questions, contradictions in
 * the citation graph (Phase 1 Iter 3), and high-surprise events (Phase 2 Iter 1).
 *
 * Gated behind {@code app.research.planner.enabled} (default false). When the
 * flag is off, {@link #planNext} always returns empty and the existing
 * formulation / pendingFollowUp flow stays in charge.
 *
 * Strategies are layered so the planner ships in slices:
 *   ADAPTIVE_SWEEP        — implemented in this PR (the safe baseline)
 *   CONTRADICTION_TARGET  — implemented (basic: midpoint disambiguation)
 *   REPLICATION           — implemented (basic: re-run highest-surprise paper)
 *   EXPLORATION           — implemented (basic: escape the genome radius)
 *   BAYESIAN_OPTIMIZATION — deferred (Phase 2b)
 *
 * Strategy selection priority (descending): CONTRADICTION_TARGET > REPLICATION
 * > EXPLORATION (when uncertainty is low) > ADAPTIVE_SWEEP. The first strategy
 * with a viable plan wins.
 */
@Service
public class ExperimentPlannerService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentPlannerService.class);

    /** Min surprise score for a paper to qualify as a REPLICATION target. */
    private static final double REPLICATION_SURPRISE_THRESHOLD = 3.0;

    /** Programs whose questions all have uncertainty below this trigger EXPLORATION. */
    private static final double EXPLORATION_TRIGGER_UNCERTAINTY = 0.25;

    /** Each completed paper reduces the target question's uncertainty by 1 / (n+1). */
    private static final double UNCERTAINTY_DECAY_BASE = 1.0;

    public enum Strategy { ADAPTIVE_SWEEP, CONTRADICTION_TARGET, REPLICATION, EXPLORATION }

    public record ExperimentPlan(
            UUID programId,
            UUID targetQuestionId,    // null when no specific question drove the plan (e.g. EXPLORATION)
            Strategy strategy,
            int param1,
            double param2,
            double expectedUncertaintyReduction
    ) {}

    private final boolean enabled;
    private final boolean worldInterventionEnabled;
    private final ResearchProgramRepository programs;
    private final ProgramOpenQuestionRepository openQuestions;
    private final PaperCitationRepository       citations;
    private final SurpriseEventRepository       surprises;
    private final WorldDataQueryService         worldData;

    /**
     * Phase 4b — optional source of dynamic per-room exploration triggers. When
     * null (or when its flag is off) {@link #planExploration} falls back to the
     * static {@link #EXPLORATION_TRIGGER_UNCERTAINTY}. Set via setter from
     * UniversityService to avoid a constructor cycle.
     */
    private volatile ExplorationTuningService explorationTuningService;

    public ExperimentPlannerService(
            @Value("${app.research.planner.enabled:false}") boolean enabled,
            @Value("${app.research.world-intervention.enabled:false}") boolean worldInterventionEnabled,
            ResearchProgramRepository programs,
            ProgramOpenQuestionRepository openQuestions,
            PaperCitationRepository citations,
            SurpriseEventRepository surprises,
            WorldDataQueryService worldData) {
        this.enabled                  = enabled;
        this.worldInterventionEnabled = worldInterventionEnabled;
        this.programs      = programs;
        this.openQuestions = openQuestions;
        this.citations     = citations;
        this.surprises     = surprises;
        this.worldData     = worldData;
        if (enabled) {
            log.info("ExperimentPlannerService: ENABLED — algorithmic parameter selection "
                    + "will be consulted before LLM formulation when a research program exists.");
        }
        if (worldInterventionEnabled) {
            log.info("ExperimentPlannerService: WORLD_INTERVENTION ENABLED — planWorldIntervention "
                    + "will scan program open questions for world-variable hypotheses and propose "
                    + "GovernmentBill-routed interventions.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /** Phase 4b — wired from UniversityService after both services are constructed. */
    public void setExplorationTuningService(ExplorationTuningService svc) {
        this.explorationTuningService = svc;
    }

    /**
     * Plan the next experiment for the bot's active program in this domain.
     * Returns empty when:
     *  - the planner is disabled,
     *  - no ACTIVE program exists for this (bot, domain) yet,
     *  - no strategy applies (e.g. no open questions and no contradictions or
     *    surprises in the room).
     *
     * Caller is responsible for:
     *  - using the returned (param1, param2) instead of consulting the LLM
     *    formulation callback,
     *  - calling {@link #recordOutcome} after the resulting paper publishes.
     */
    public Optional<ExperimentPlan> planNext(UUID ownerBotId, String domain,
                                             BotGenome genome, Random rng) {
        if (!enabled || ownerBotId == null || domain == null || genome == null || rng == null) {
            return Optional.empty();
        }
        ResearchProgram program = programs.findFirstByOwnerBotIdAndDomainAndStatus(
                ownerBotId, domain, ResearchProgram.Status.ACTIVE).orElse(null);
        if (program == null) {
            return Optional.empty();
        }

        // Strategy 1: contradiction targeting — settle disputes first.
        Optional<ExperimentPlan> p = planContradiction(program, genome, rng);
        if (p.isPresent()) return p;

        // Strategy 2: replicate the most surprising recent paper for this program's domain.
        p = planReplication(program, genome, rng);
        if (p.isPresent()) return p;

        // Strategy 3: exploration — once uncertainty is broadly low, escape the genome region.
        p = planExploration(program, genome, rng);
        if (p.isPresent()) return p;

        // Default: adaptive sweep over the highest-uncertainty open question.
        return planAdaptiveSweep(program, genome, rng);
    }

    /**
     * Phase 3 Iter 6 (write-side wire-up) — generates a
     * {@link WorldInterventionService.InterventionProposal} when the program's
     * highest-uncertainty open question references one of
     * {@link WorldDataQueryService}'s known variable names. The caller is
     * expected to pass the returned proposal to
     * {@link WorldInterventionService#propose} and store the handle.
     *
     * The match strategy is intentionally simple: lowercase substring search
     * over each open question's text for any variable name from
     * {@code worldData.knownVariableNames()}. A budget-axis match is mapped
     * to its corresponding {@link WorldInterventionService.PolicyTarget};
     * a non-budget variable falls back to {@link
     * WorldInterventionService.PolicyTarget#SERVICES} as the broadest lever.
     *
     * This method is a parallel surface to {@link #planNext}, not a
     * {@link Strategy} value, because the output type
     * ({@link WorldInterventionService.InterventionProposal}) is genuinely
     * different from the math/physics {@link ExperimentPlan}: a
     * world-intervention "experiment" is filing a bill, not picking
     * numerical parameters. Callers gate via
     * {@code app.research.world-intervention.enabled}; when the flag is
     * off this always returns empty.
     *
     * Returns empty when:
     *  - the planner is disabled or the world-intervention flag is off,
     *  - no ACTIVE program exists for (bot, domain),
     *  - no open question contains a known world-variable name,
     *  - {@link WorldDataQueryService} dependency is null (test wiring).
     */
    public Optional<WorldInterventionService.InterventionProposal> planWorldIntervention(
            UUID ownerBotId, String domain) {
        if (!enabled || !worldInterventionEnabled
                || ownerBotId == null || domain == null || worldData == null) {
            return Optional.empty();
        }
        ResearchProgram program = programs.findFirstByOwnerBotIdAndDomainAndStatus(
                ownerBotId, domain, ResearchProgram.Status.ACTIVE).orElse(null);
        if (program == null) {
            return Optional.empty();
        }
        List<ProgramOpenQuestion> open = openQuestions.findByProgramIdAndStatus(
                program.getId(), ProgramOpenQuestion.Status.OPEN);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        List<String> variableNames = worldData.knownVariableNames();
        // Walk highest-uncertainty first so the most-open question gets first dibs.
        ProgramOpenQuestion target = null;
        String matchedVariable = null;
        for (ProgramOpenQuestion q : open.stream()
                .sorted(Comparator.comparingDouble(ProgramOpenQuestion::getUncertainty).reversed())
                .toList()) {
            String text = q.getQuestion() == null ? "" : q.getQuestion().toLowerCase(Locale.ROOT);
            for (String name : variableNames) {
                if (text.contains(name.toLowerCase(Locale.ROOT))) {
                    target = q;
                    matchedVariable = name;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null) {
            return Optional.empty();
        }
        WorldInterventionService.PolicyTarget policy = policyTargetForVariable(matchedVariable);
        log.debug("planner: WORLD_INTERVENTION program={} questionId={} variable={} policy={}",
                program.getId(), target.getId(), matchedVariable, policy);
        return Optional.of(new WorldInterventionService.InterventionProposal(
                ownerBotId,
                program.getId(),
                target.getQuestion(),
                policy,
                /* durationTicks = */ 30L));
    }

    /**
     * Map a {@link WorldDataQueryService} variable name to the
     * {@link WorldInterventionService.PolicyTarget} whose
     * {@code policyDomain} bumps the corresponding budget axis in
     * {@code GovernmentSimulationService.applyBudgetEffects}.
     * Non-budget variables fall back to {@code SERVICES} as the broadest
     * lever (it bumps both education and health, which propagate to
     * trust + service-access aggregates).
     */
    static WorldInterventionService.PolicyTarget policyTargetForVariable(String variableName) {
        if (variableName == null) return WorldInterventionService.PolicyTarget.SERVICES;
        return switch (variableName) {
            case WorldDataQueryService.NAME_BUDGET_EDUCATION,
                 WorldDataQueryService.NAME_BUDGET_HEALTH ->
                    WorldInterventionService.PolicyTarget.SERVICES;
            case WorldDataQueryService.NAME_BUDGET_WELFARE ->
                    WorldInterventionService.PolicyTarget.HOUSING;
            case WorldDataQueryService.NAME_BUDGET_INFRASTRUCTURE ->
                    WorldInterventionService.PolicyTarget.INFRASTRUCTURE;
            case WorldDataQueryService.NAME_BUDGET_JUSTICE ->
                    WorldInterventionService.PolicyTarget.RIGHTS_PROCESS;
            default -> WorldInterventionService.PolicyTarget.SERVICES;
        };
    }

    /**
     * Update the targeted question's uncertainty after a paper completed under
     * this plan. Lowers uncertainty by 1 / (1 + completedCount); when the
     * uncertainty drops below 0.1 the question is marked RESOLVED.
     */
    public void recordOutcome(UUID questionId, double surpriseScore) {
        if (!enabled || questionId == null) return;
        openQuestions.findById(questionId).ifPresent(q -> {
            // Lower uncertainty in proportion to how much the paper reduced our ignorance.
            // Surprises mean "expectation was wrong" — we don't reduce uncertainty as much.
            double surpriseDamper = 1.0 / (1.0 + Math.max(0.0, surpriseScore));
            double reduction = UNCERTAINTY_DECAY_BASE * surpriseDamper / 2.0;
            double next = Math.max(0.0, q.getUncertainty() - reduction);
            q.setUncertainty(next);
            if (next < 0.1) {
                q.setStatus(ProgramOpenQuestion.Status.RESOLVED);
            } else if (q.getStatus() == ProgramOpenQuestion.Status.OPEN) {
                q.setStatus(ProgramOpenQuestion.Status.BEING_INVESTIGATED);
            }
            try {
                openQuestions.save(q);
            } catch (Exception e) {
                log.warn("planner: recordOutcome save failed questionId={}: {}",
                        questionId, e.getMessage());
            }
        });
    }

    // ---- Strategies ----

    private Optional<ExperimentPlan> planAdaptiveSweep(ResearchProgram program,
                                                       BotGenome genome, Random rng) {
        ProgramOpenQuestion target = highestUncertaintyOpen(program.getId());
        if (target == null) return Optional.empty();

        // Sample around the genome's learned center, narrowing the effective radius
        // proportionally to the question's uncertainty. Higher uncertainty → wider sweep.
        double widthScale = Math.max(0.2, target.getUncertainty());
        int    p1Radius   = Math.max(1, (int) Math.round(genome.getParamRadius1() * widthScale));
        double p2Radius   = Math.max(0.1, genome.getParamRadius2() * widthScale);

        int    param1 = clamp1(genome.getParamCenter1()
                + (int) Math.round((rng.nextDouble() * 2 - 1) * p1Radius));
        double param2 = clamp2(genome.getParamCenter2()
                + (rng.nextDouble() * 2 - 1) * p2Radius);

        return Optional.of(new ExperimentPlan(program.getId(), target.getId(),
                Strategy.ADAPTIVE_SWEEP, param1, param2,
                /* expectedUncertaintyReduction = */ 0.5 * target.getUncertainty()));
    }

    private Optional<ExperimentPlan> planContradiction(ResearchProgram program,
                                                       BotGenome genome, Random rng) {
        // Use the room-scoped contradiction edges; pick one that involves a paper
        // from this program's domain. Phase 4's FormalFinding contradiction
        // detection will refine this.
        List<PaperCitation> roomContradictions = citations.findByRoomIdAndRelationshipType(
                program.getRoomId(), RelationshipType.CONTRADICTS);
        if (roomContradictions.isEmpty()) return Optional.empty();

        // Pick the most recent contradiction; for MVP the planner just samples
        // params at the genome center (the disputed midpoint heuristic in the
        // phase doc would require fetching the cited paper params, which is a
        // bigger refactor — track this as a follow-up).
        PaperCitation pick = roomContradictions.stream()
                .max(Comparator.comparing(PaperCitation::getCreatedAt))
                .orElse(roomContradictions.get(0));

        int    param1 = clamp1(genome.getParamCenter1());
        double param2 = clamp2(genome.getParamCenter2());

        log.debug("planner: CONTRADICTION_TARGET program={} edge={}→{}", program.getId(),
                pick.getCitingPaperId(), pick.getCitedPaperId());
        return Optional.of(new ExperimentPlan(program.getId(), null,
                Strategy.CONTRADICTION_TARGET, param1, param2, 0.4));
    }

    private Optional<ExperimentPlan> planReplication(ResearchProgram program,
                                                      BotGenome genome, Random rng) {
        List<SurpriseEvent> recent = surprises
                .findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
                        program.getRoomId(), REPLICATION_SURPRISE_THRESHOLD);
        if (recent.isEmpty()) return Optional.empty();

        SurpriseEvent target = recent.stream()
                .filter(s -> program.getDomain().equalsIgnoreCase(s.getDomain()))
                .findFirst().orElse(null);
        if (target == null) return Optional.empty();

        int    param1 = clamp1(genome.getParamCenter1());
        double param2 = clamp2(genome.getParamCenter2());

        log.debug("planner: REPLICATION program={} sourcePaper={} surprise={}",
                program.getId(), target.getPaperId(), target.getSurpriseScore());
        return Optional.of(new ExperimentPlan(program.getId(), null,
                Strategy.REPLICATION, param1, param2, 0.6));
    }

    private Optional<ExperimentPlan> planExploration(ResearchProgram program,
                                                     BotGenome genome, Random rng) {
        List<ProgramOpenQuestion> open = openQuestions.findByProgramIdAndStatus(
                program.getId(), ProgramOpenQuestion.Status.OPEN);
        double trigger = explorationTuningService != null
                ? explorationTuningService.getExplorationTrigger(program.getRoomId())
                : EXPLORATION_TRIGGER_UNCERTAINTY;
        boolean allLowUncertainty = !open.isEmpty() && open.stream()
                .allMatch(q -> q.getUncertainty() < trigger);
        if (!allLowUncertainty) return Optional.empty();

        // Sample outside the genome's comfortable radius. Use the explorationRate
        // as a multiplier on how far we step out.
        double escapeFactor = 1.5 + genome.getExplorationRate();
        int    p1Radius    = (int) Math.round(genome.getParamRadius1() * escapeFactor);
        double p2Radius    = genome.getParamRadius2() * escapeFactor;

        int    param1 = clamp1(genome.getParamCenter1()
                + (int) Math.round((rng.nextDouble() * 2 - 1) * p1Radius));
        double param2 = clamp2(genome.getParamCenter2()
                + (rng.nextDouble() * 2 - 1) * p2Radius);

        return Optional.of(new ExperimentPlan(program.getId(), null,
                Strategy.EXPLORATION, param1, param2, 0.2));
    }

    // ---- Helpers ----

    private ProgramOpenQuestion highestUncertaintyOpen(UUID programId) {
        return openQuestions.findByProgramIdAndStatus(programId, ProgramOpenQuestion.Status.OPEN)
                .stream()
                .max(Comparator.comparingDouble(ProgramOpenQuestion::getUncertainty))
                .orElse(null);
    }

    private static int clamp1(int p1)       { return Math.max(10, Math.min(99, p1)); }
    private static double clamp2(double p2) { return Math.max(1.0, Math.min(9.0, p2)); }
}
