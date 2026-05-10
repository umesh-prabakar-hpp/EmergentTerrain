package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-tick research cycle: author selection, computation, peer review, and
 * draft enqueue (or template-fallback publish when no narration callback is wired).
 *
 * Carved out of UniversityService in Phase 0 Task 0.3d. The cycle reaches into
 * many UniversityService responsibilities (paperId generation, recent paper
 * lookup, publish fallback, narration callback) so callers pass a {@link Context}
 * rather than the cycle service depending on UniversityService directly. This
 * avoids a Spring circular dependency and keeps the contract narrow.
 *
 * Owns the per-room counters (papersSinceTerm, totalRejected) and the global
 * draftQueueSize gate, plus the AnomalyTracker that supplies z-scores.
 */
@Service
public class ResearchCycleService {

    private static final Logger log = LoggerFactory.getLogger(ResearchCycleService.class);

    private static final int REVIEWERS_PER_PAPER = 2;
    private static final int TOURNAMENT_K        = 3;

    /**
     * Surface UniversityService exposes to the cycle. Lets the cycle call back into
     * the orchestrator without taking it as a constructor dependency.
     */
    public interface Context {
        String nextPaperId(UUID roomId);
        List<ResearchPaper> getPublishedPapers(UUID roomId, int limit);
        /** Template-fallback publish path used when no narration callback is wired. */
        void publishPaper(PaperDraft draft, NarrationResult narration);
        /** May be null — when null the cycle falls back to publishPaper directly. */
        Consumer<PaperDraft> narratingCallback();
        /** May be null — when null the cycle falls back to genome sampling. */
        Function<Object[], Object> formulationCallback();
    }

    private final AnomalyTracker anomalyTracker = new AnomalyTracker();
    private final ConcurrentHashMap<UUID, Integer> papersSinceTerm = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> totalRejected   = new ConcurrentHashMap<>();
    private final AtomicInteger draftQueueSize = new AtomicInteger(0);

    /** Phase 2 Iter 5 — optional planner. Null when no Spring bean is wired. */
    private volatile ExperimentPlannerService planner;

    /** Setter (rather than constructor arg) to keep the existing test setUp tidy. */
    public void setExperimentPlannerService(ExperimentPlannerService planner) {
        this.planner = planner;
    }

    // ---- State accessors used by UniversityService.maybeTick / getStats / publishPaper ----

    public int draftQueueSize()                  { return draftQueueSize.get(); }
    public void decrementDraftQueue()            { draftQueueSize.decrementAndGet(); }
    public int papersSinceTerm(UUID roomId)      { return papersSinceTerm.getOrDefault(roomId, 0); }
    public void resetPapersSinceTerm(UUID roomId) { papersSinceTerm.put(roomId, 0); }
    public int totalRejected(UUID roomId)        { return totalRejected.getOrDefault(roomId, 0); }

    // ---- Cycle ----

    public void runCycle(UUID roomId, List<Researcher> pool, Random rng, long gameTick,
                         PopulationEcologyService.PopulationParams popParams,
                         boolean peerReviewEnabled, Context ctx) {
        Researcher author = tournamentSelect(pool, rng);

        DynamicProblem directive = author.getState().getPendingDirective();
        if (directive != null) {
            author.getState().setPendingDirective(null);
            runDynamicCycle(roomId, author, pool, directive, rng, gameTick, peerReviewEnabled, ctx);
            return;
        }

        int topicIndex = author.selectTopic(rng);
        String domain  = author.getDomain();

        FollowUpParams pendingFollowUp = author.getState().getPendingFollowUp();
        boolean isFollowUp = (pendingFollowUp != null);
        int    param1;
        double param2;
        Double predictedScore   = null;
        String priorMethodology = null;
        Object formResult = null;
        Optional<ExperimentPlannerService.ExperimentPlan> plan = Optional.empty();
        if (planner != null && planner.isEnabled() && !isFollowUp) {
            try {
                UUID authorBotId = UUID.fromString(author.getBotId());
                plan = planner.planNext(authorBotId, domain,
                        author.getState().getGenome(), rng);
            } catch (IllegalArgumentException ignored) { /* author bot id malformed */ }
        }
        if (isFollowUp) {
            param1          = pendingFollowUp.param1();
            param2          = pendingFollowUp.param2();
            predictedScore  = pendingFollowUp.predictedScore();
            priorMethodology = pendingFollowUp.priorMethodology();
            author.getState().setPendingFollowUp(null);
            log.debug("university consuming follow-up botId={} param1={} param2={} predicted={}",
                    author.getBotId(), param1, param2, predictedScore);
        } else if (plan.isPresent()) {
            ExperimentPlannerService.ExperimentPlan p = plan.get();
            param1 = p.param1();
            param2 = p.param2();
            log.info("planner: bot={} domain={} strategy={} param1={} param2={} target={}",
                    author.getBotId(), domain, p.strategy(), param1, param2, p.targetQuestionId());
        } else {
            String topicNameForFormulation = MathPhysicsEngine.topicName(domain, topicIndex);
            List<ResearchPaper> recentForFormulation = ctx.getPublishedPapers(roomId, 6);
            Function<Object[], Object> formCb = ctx.formulationCallback();
            formResult = (formCb != null)
                    ? formCb.apply(new Object[]{
                            domain, topicNameForFormulation,
                            author.getState().getGenome(), recentForFormulation,
                            author.getState().getResearchHypothesis()})
                    : null;
            if (formResult instanceof com.emergentterrain.service.FormulationService.FormulationResult fr) {
                param1 = fr.param1();
                param2 = fr.param2();
                log.debug("university formulation used botId={} param1={} param2={} question={}",
                        author.getBotId(), param1, param2, fr.researchQuestion());
            } else {
                param1 = author.getState().getGenome().sampleParam1(rng);
                param2 = author.getState().getGenome().sampleParam2(rng);
            }
        }

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(
                author.getState().getGenome(), popParams.educationFactor());
        MathPhysicsEngine.ResearchResult result = MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);

        boolean authorError         = rng.nextDouble() < author.errorRate();
        double  reportedReviewValue = authorError
                ? result.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.3)
                : result.reviewValue();
        double  authorScore = MathPhysicsEngine.scoreResult(domain, topicIndex, reportedReviewValue, sp);

        ResultClassification classification = UniversityService.classifyResult(result, authorScore);
        if (classification == ResultClassification.DEGENERATE) {
            log.info("university DEGENERATE result rejected domain={} topic={} param1={} param2={} score={}",
                    domain, MathPhysicsEngine.topicName(domain, topicIndex), param1, param2, authorScore);
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
            return;
        }

        PreRegistration preReg = null;
        if (!isFollowUp && formResult instanceof com.emergentterrain.service.FormulationService.FormulationResult fr) {
            preReg = fr.preRegistration();
        }

        List<String> reviewerIds     = new ArrayList<>();
        Set<String>  usedReviewerIds = new HashSet<>();
        boolean reviewPassed         = result.theoryConsistent();
        double  totalReviewScore     = 0;
        int     reviewCount          = 0;

        if (peerReviewEnabled) {
            for (int i = 0; i < REVIEWERS_PER_PAPER && reviewPassed; i++) {
                Researcher reviewer      = pickReviewer(pool, author, usedReviewerIds, rng);
                usedReviewerIds.add(reviewer.getBotId());
                boolean    reviewerError = rng.nextDouble() < reviewer.errorRate();

                MathPhysicsEngine.ResearchResult reviewResult =
                        MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);
                double reviewValue = reviewerError
                        ? reviewResult.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.15)
                        : reviewResult.reviewValue();
                double reviewScore = MathPhysicsEngine.scoreResult(domain, topicIndex, reviewValue, sp);

                boolean agrees = Math.abs(authorScore - reviewScore)
                        < effectivePeerTolerance(reviewer);
                if (!agrees) reviewPassed = false;

                reviewerIds.add(reviewer.getBotId());
                totalReviewScore += reviewer.getReputation() * (agrees ? 1.0 : 0.3);
                reviewCount++;
            }
        }

        double peerScore = reviewCount > 0 ? totalReviewScore / reviewCount : authorScore;

        if (reviewPassed) {
            String paperId    = ctx.nextPaperId(roomId);
            String topicName  = MathPhysicsEngine.topicName(domain, topicIndex);
            String title      = MathPhysicsEngine.generateTitle(domain, topicIndex, rng);
            String templateHypothesis = (result.hypothesis() != null && !result.hypothesis().isBlank())
                    ? result.hypothesis()
                    : MathPhysicsEngine.generateHypothesis(domain, topicIndex, param1, param2);
            String templateConclusion = MathPhysicsEngine.generateConclusion(result, true);
            double zScore = anomalyTracker.observe(domain, topicName, peerScore);

            papersSinceTerm.merge(roomId, 1, Integer::sum);
            author.recordPublished(peerScore);
            author.getState().getGenome().recordSuccess(param1, param2, peerScore);

            PaperDraft draft = new PaperDraft(
                    roomId, author.getBotId(), domain, topicName, title,
                    result.findings(), templateHypothesis, templateConclusion,
                    author.getMethodology(), Collections.unmodifiableList(reviewerIds),
                    peerScore, authorScore, zScore, gameTick, Instant.now(),
                    author.getGeneration(), paperId, param1, (double) param2, isFollowUp,
                    predictedScore, priorMethodology,
                    ctx.getPublishedPapers(roomId, 5), author.getState().getResearchHypothesis(),
                    preReg, classification);

            Consumer<PaperDraft> narratingCallback = ctx.narratingCallback();
            if (narratingCallback != null) {
                draftQueueSize.incrementAndGet();
                narratingCallback.accept(draft);
            } else {
                ctx.publishPaper(draft, new NarrationResult(templateHypothesis, result.findings(),
                        templateConclusion, null, null, 0.5, List.of()));
            }
            log.debug("university draft queued paperId={} domain={} topic={} score={}",
                    paperId, domain, topicName, peerScore);
        } else {
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
        }

        for (String rid : reviewerIds) {
            pool.stream().filter(r -> r.getBotId().equals(rid)).findFirst()
                .ifPresent(r -> r.recordPublished(0.1));
        }
    }

    // ---- Dynamic cycle ----

    private void runDynamicCycle(UUID roomId, Researcher author, List<Researcher> pool,
                                 DynamicProblem directive, Random rng, long gameTick,
                                 boolean peerReviewEnabled, Context ctx) {
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(author.getState().getGenome());
        MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(directive);

        boolean authorError  = rng.nextDouble() < author.errorRate();
        double reportedValue = authorError
                ? result.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.3)
                : result.reviewValue();
        double authorScore = MathPhysicsEngine.scoreResult("DYNAMIC", 0, reportedValue, sp);

        List<String> reviewerIds = new ArrayList<>();
        Set<String>  usedIds     = new HashSet<>();
        boolean reviewPassed     = result.theoryConsistent();
        double  totalReviewScore = 0;
        int     reviewCount      = 0;

        if (peerReviewEnabled) {
            for (int i = 0; i < REVIEWERS_PER_PAPER && reviewPassed; i++) {
                Researcher reviewer = pickReviewer(pool, author, usedIds, rng);
                usedIds.add(reviewer.getBotId());
                MathPhysicsEngine.ResearchResult reviewResult = DynamicComputeEngine.compute(directive);
                boolean reviewerError = rng.nextDouble() < reviewer.errorRate();
                double reviewValue = reviewerError
                        ? reviewResult.reviewValue() * (1 + (rng.nextDouble() - 0.5) * 0.15)
                        : reviewResult.reviewValue();
                double reviewScore = MathPhysicsEngine.scoreResult("DYNAMIC", 0, reviewValue, sp);
                boolean agrees = Math.abs(authorScore - reviewScore)
                        < effectivePeerTolerance(reviewer);
                if (!agrees) reviewPassed = false;
                reviewerIds.add(reviewer.getBotId());
                totalReviewScore += reviewer.getReputation() * (agrees ? 1.0 : 0.3);
                reviewCount++;
            }
        }

        double peerScore = reviewCount > 0 ? totalReviewScore / reviewCount : authorScore;

        ResultClassification dynClassification = UniversityService.classifyResult(result, authorScore);
        if (dynClassification == ResultClassification.DEGENERATE) {
            log.info("university DEGENERATE dynamic result rejected description=\"{}\" score={}",
                    directive.description(), authorScore);
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
            return;
        }

        if (reviewPassed) {
            String paperId            = ctx.nextPaperId(roomId);
            String topicName          = dynamicTopicName(directive);
            String templateHypothesis = directive.hypothesis() != null
                    ? directive.hypothesis() : directive.description();
            String templateConclusion = MathPhysicsEngine.generateConclusion(result, true);

            // Dynamic problems are always number-theoretic — classify as MATHEMATICS.
            double zScore = anomalyTracker.observe("MATHEMATICS", topicName, peerScore);

            papersSinceTerm.merge(roomId, 1, Integer::sum);
            author.recordPublished(peerScore);

            PaperDraft draft = new PaperDraft(
                    roomId, author.getBotId(), "MATHEMATICS", topicName,
                    dynamicPaperTitle(directive), result.findings(),
                    templateHypothesis, templateConclusion,
                    author.getMethodology(), Collections.unmodifiableList(reviewerIds),
                    peerScore, authorScore, zScore, gameTick, Instant.now(),
                    author.getGeneration(), paperId, null, null, false,
                    null, null,
                    ctx.getPublishedPapers(roomId, 5), author.getState().getResearchHypothesis(),
                    null, dynClassification);

            Consumer<PaperDraft> narratingCallback = ctx.narratingCallback();
            if (narratingCallback != null) {
                draftQueueSize.incrementAndGet();
                narratingCallback.accept(draft);
            } else {
                ctx.publishPaper(draft, new NarrationResult(templateHypothesis, result.findings(),
                        templateConclusion, null, null, 0.5, List.of()));
            }
            log.debug("university dynamic draft queued botId={} description=\"{}\" score={}",
                    author.getBotId(), directive.description(), peerScore);
        } else {
            author.recordRejected();
            author.getState().getGenome().recordRejection(rng);
            totalRejected.merge(roomId, 1, Integer::sum);
        }

        for (String rid : reviewerIds) {
            pool.stream().filter(r -> r.getBotId().equals(rid)).findFirst()
                .ifPresent(r -> r.recordPublished(0.1));
        }
    }

    /**
     * Phase 1 Iter 2 — apply the reviewer's skepticismLevel as a tolerance
     * multiplier on top of the genome's peerTolerance. Higher skepticism →
     * tighter agreement window → easier for the reviewer to disagree.
     */
    private static double effectivePeerTolerance(Researcher reviewer) {
        double base = reviewer.getState().getGenome().getPeerTolerance();
        double s = Math.max(0.0, Math.min(1.0, reviewer.getState().getSkepticismLevel()));
        return base * (1.0 - s * 0.5);
    }

    // ---- Researcher selection helpers ----

    private Researcher tournamentSelect(List<Researcher> pool, Random rng) {
        Researcher best = null;
        for (int i = 0; i < TOURNAMENT_K; i++) {
            Researcher candidate = pool.get(rng.nextInt(pool.size()));
            if (best == null || candidate.fitness() > best.fitness()) best = candidate;
        }
        return best;
    }

    private Researcher pickReviewer(List<Researcher> pool, Researcher author,
                                    Set<String> alreadyPicked, Random rng) {
        List<Researcher> candidates = pool.stream()
                .filter(r -> !r.getBotId().equals(author.getBotId())
                          && !alreadyPicked.contains(r.getBotId()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = pool.stream()
                    .filter(r -> !r.getBotId().equals(author.getBotId()))
                    .collect(Collectors.toList());
        }
        if (candidates.isEmpty()) return pool.get(rng.nextInt(pool.size()));
        List<Researcher> sameDomain = candidates.stream()
                .filter(r -> r.getDomain().equals(author.getDomain()))
                .collect(Collectors.toList());
        List<Researcher> pick = sameDomain.isEmpty() ? candidates : sameDomain;
        return pick.get(rng.nextInt(pick.size()));
    }

    // ---- Dynamic paper naming helpers ----

    private static String dynamicPaperTitle(DynamicProblem d) {
        return "%s of %s Numbers in [%d, %d)".formatted(
                metricLabel(d.metric()), predicateLabel(d.predicate()),
                d.rangeStart(), d.rangeEnd());
    }

    private static String dynamicTopicName(DynamicProblem d) {
        String name = predicateLabel(d.predicate()) + " " + metricLabel(d.metric());
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    private static String predicateLabel(String predicate) {
        return switch (predicate) {
            case "isPrime"           -> "Prime";
            case "isHappy"           -> "Happy";
            case "collatzTerminates" -> "Collatz-terminating";
            case "isAbundant"        -> "Abundant";
            case "isPerfect"         -> "Perfect";
            default                  -> predicate;
        };
    }

    private static String metricLabel(String metric) {
        return switch (metric) {
            case "fraction_satisfying" -> "Density";
            case "mean_of:collatzSteps" -> "Mean Collatz Depth";
            case "max_of:collatzSteps"  -> "Max Collatz Depth";
            case "mean_of:sigmaSum"     -> "Mean Divisor Sum";
            default                     -> metric;
        };
    }
}
