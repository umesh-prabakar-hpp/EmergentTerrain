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

    /**
     * Phase 4b — when true, pickReviewer scores candidates by topic-weight
     * orthogonality to the author and picks the most adversarial one rather
     * than a random same-domain candidate. Wired by UniversityService from
     * {@code app.research.reviewer-routing.enabled}.
     */
    private volatile boolean reviewerOrthogonalityEnabled = false;

    public void setReviewerOrthogonalityEnabled(boolean enabled) {
        this.reviewerOrthogonalityEnabled = enabled;
    }

    /**
     * Phase 4b — when true, pickReviewer narrows the candidate pool to journal
     * editors (top-reputation same-domain researchers) before applying the
     * existing routing. Requires {@link #institutionService} to be wired.
     */
    private volatile boolean journalReviewEnabled = false;
    private volatile InstitutionService institutionService;

    /** Maximum journal editor pool size; reviewers are drawn from these. */
    private static final int JOURNAL_EDITOR_LIMIT = 5;

    public void setJournalReviewEnabled(boolean enabled) {
        this.journalReviewEnabled = enabled;
    }

    public void setInstitutionService(InstitutionService institutionService) {
        this.institutionService = institutionService;
    }

    /**
     * Phase 23.4 — optional conjecture-topic provider. Setter-injected to
     * match the existing setter-based optional-dependency pattern on this
     * service. When non-null and {@code app.research.conjectureMiner.paperShare}
     * is > 0, {@code runStandardCycle} can branch into the conjecture-driven
     * paper-writing path. The actual cycle-level call sits with 23.4b; this
     * setter ships now so the wiring point is clear and the bean is
     * available the moment the branch lands.
     */
    private volatile com.emergentterrain.research.conjecture.ConjectureTopicProvider conjectureTopicProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConjectureTopicProvider(
            com.emergentterrain.research.conjecture.ConjectureTopicProvider provider) {
        this.conjectureTopicProvider = provider;
    }

    /**
     * Phase 28 §28.5 — optional sidecar shadow-read wrapper. When Spring
     * resolves a {@link MathPhysicsEngineDelegate} bean (which it does
     * once the @Service is on the classpath — i.e. always in production),
     * the cycle routes compute calls through the delegate. The delegate
     * is dormant by default — {@code app.julia.physics.topic.enabled=false}
     * — so the in-process behaviour is unchanged. Flipping the flag
     * activates the shadow-read parity check. Tests that construct
     * ResearchCycleService directly leave this null; the call sites
     * gracefully fall back to {@link MathPhysicsEngine#compute} when so.
     */
    private volatile MathPhysicsEngineDelegate physicsEngineDelegate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhysicsEngineDelegate(MathPhysicsEngineDelegate delegate) {
        this.physicsEngineDelegate = delegate;
    }

    /**
     * Centralised dispatch for the two MathPhysicsEngine.compute call
     * sites in this class — uses the delegate when wired, else the
     * static path. Inline by hand at each call site would duplicate
     * the null check; one helper keeps the dispatch policy in one place.
     */
    private MathPhysicsEngine.ResearchResult dispatchCompute(String domain, int topicIndex,
                                                              int param1, double param2,
                                                              MathPhysicsEngine.SearchParams sp) {
        MathPhysicsEngineDelegate delegate = this.physicsEngineDelegate;
        if (delegate == null) {
            throw new IllegalStateException("physics-topic compute requires the sidecar-backed "
                    + "MathPhysicsEngineDelegate; none wired (in-process engine removed in §28.6).");
        }
        return delegate.compute(domain, topicIndex, param1, param2, sp);
    }

    /**
     * Phase 7 F3 — when true, the topic-selection step uses uniform random over
     * every registered topic for the bot's domain (current 5 per main domain
     * plus the F3 additions) rather than the bot's weighted genome over the
     * original 5. Wired by UniversityService from
     * {@code app.research.topics-v2.enabled}.
     */
    private volatile boolean topicsV2Enabled = false;

    public void setTopicsV2Enabled(boolean enabled) {
        this.topicsV2Enabled = enabled;
    }

    /**
     * Phase 16 P7 — when true, the topic-selection step applies a backoff
     * after the initial pick: count REJECTED papers on the (domain, topic)
     * pair in the room's recent history, and re-roll the selection with
     * probability {@code 1 − 0.5^rejections}. Default off; flip on after
     * one corpus refresh under P6 has soaked. See
     * {@link TopicCloneBackoff} for the math and the
     * {@code app.research.topic-clone-backoff.*} family of YAML knobs.
     */
    private volatile boolean topicCloneBackoffEnabled = false;
    private volatile int topicCloneBackoffWindow     = 5;
    private volatile int topicCloneBackoffMaxRetries = 3;

    public void setTopicCloneBackoffEnabled(boolean enabled) {
        this.topicCloneBackoffEnabled = enabled;
    }

    public void setTopicCloneBackoffWindow(int window) {
        this.topicCloneBackoffWindow = Math.max(1, window);
    }

    public void setTopicCloneBackoffMaxRetries(int retries) {
        this.topicCloneBackoffMaxRetries = Math.max(0, retries);
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

        String domain  = author.getDomain();

        // Phase 23.4b — conjecture-driven branch. When the topic provider
        // fires (paperShare > 0, queue non-empty), publish a paper
        // investigating the mined conjecture instead of running the
        // catalog cycle. The catalog flow continues below if the provider
        // returns empty. The bot's selectTopic / genome state and
        // anomalyTracker are intentionally untouched on this branch —
        // conjecture papers don't roll the catalog at all.
        if (conjectureTopicProvider != null) {
            java.util.Optional<com.emergentterrain.research.conjecture.Conjecture> conjecture =
                    conjectureTopicProvider.selectConjecture(domain, rng);
            if (conjecture.isPresent()) {
                publishConjectureInvestigation(roomId, author, conjecture.get(), gameTick, ctx);
                return;
            }
        }

        // Phase 7 F3 — when the flag is on, sample uniformly over every registered
        // topic for the bot's domain (current 5 + F3.2–F3.5 additions). When off,
        // keep the weighted-genome selection over the original 5 — bit-identical to
        // pre-F3 behaviour. selectTopicIndex collapses the genome's 0..4 raw choice
        // to a domain-clamped index in either case.
        int topicIndex = MathPhysicsEngine.selectTopicIndex(
                domain, author.selectTopic(rng), topicsV2Enabled, rng);

        // Phase 16 P7 — topic-clone backoff. When the chosen (domain, topic)
        // has accumulated REJECTED published papers in the room's recent
        // history, probabilistically resample. Default off; flip on per the
        // app.research.topic-clone-backoff.* family. Targets the divisor-sum-
        // average cluster (P-534/539/553) where the scheduler kept picking
        // the same topic despite three consecutive rejections.
        if (topicCloneBackoffEnabled) {
            final List<ResearchPaper> historyForBackoff =
                    ctx.getPublishedPapers(roomId, topicCloneBackoffWindow);
            final int initialTopic = topicIndex;
            String initialName = MathPhysicsEngine.topicName(domain, initialTopic);
            final String domainForResample = domain;
            TopicCloneBackoff.ResampleSupplier resample = TopicCloneBackoff.fromSuppliers(
                    () -> MathPhysicsEngine.selectTopicIndex(
                            domainForResample, author.selectTopic(rng), topicsV2Enabled, rng),
                    idx -> MathPhysicsEngine.topicName(domainForResample, idx));
            int finalIndex = TopicCloneBackoff.applyBackoff(
                    initialTopic, initialName, domain, historyForBackoff,
                    resample, topicCloneBackoffMaxRetries, rng);
            if (finalIndex != initialTopic) {
                log.info("topic-clone backoff: bot={} domain={} initial={} (rej={}) → final={}",
                        author.getBotId(), domain, initialName,
                        TopicCloneBackoff.countRecentRejections(historyForBackoff, domain, initialName),
                        MathPhysicsEngine.topicName(domain, finalIndex));
            }
            topicIndex = finalIndex;
        }

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
                            author.getState().getResearchHypothesis(),
                            author.getState()})
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
        MathPhysicsEngine.ResearchResult result = dispatchCompute(domain, topicIndex, param1, param2, sp);

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
                Researcher reviewer      = pickReviewer(roomId, pool, author, usedReviewerIds, rng);
                usedReviewerIds.add(reviewer.getBotId());
                boolean    reviewerError = rng.nextDouble() < reviewer.errorRate();

                MathPhysicsEngine.ResearchResult reviewResult =
                        dispatchCompute(domain, topicIndex, param1, param2, sp);
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

            UUID plannerQuestionId = plan.map(ExperimentPlannerService.ExperimentPlan::targetQuestionId)
                    .orElse(null);
            PaperDraft draft = new PaperDraft(
                    roomId, author.getBotId(), domain, topicName, title,
                    result.findings(), templateHypothesis, templateConclusion,
                    author.getMethodology(), Collections.unmodifiableList(reviewerIds),
                    peerScore, authorScore, zScore, gameTick, Instant.now(),
                    author.getGeneration(), paperId, param1, (double) param2, isFollowUp,
                    predictedScore, priorMethodology,
                    ctx.getPublishedPapers(roomId, 5), author.getState().getResearchHypothesis(),
                    preReg, classification, plannerQuestionId);

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

    // ---- Conjecture investigation cycle (Phase 23.4b) ----

    /**
     * Build and dispatch a {@link PaperDraft} that investigates a mined
     * conjecture. Bypasses {@code MathPhysicsEngine.compute} and peer
     * review — the conjecture has already been validated by surviving
     * the miner's seed/refute/tighten loop across multiple windows. The
     * paper's hypothesis is the inequality verbatim, findings are a
     * mining-history template, and the kind is tagged
     * {@link PaperKind#CONJECTURE_INVESTIGATION} so downstream consumers
     * can route it differently (different prompt template, different
     * reviewer panel, different scorecard).
     *
     * <p>Skips peer review because the conjecture's peer-review-equivalent
     * is its existing track record (confirmed runs across configs,
     * p99Slack, no counterexamples). A future slice can have reviewers
     * run {@code verify_conjecture} on their own held-out windows; for
     * v1 the empty reviewer list signals "miner-validated, no human
     * panel."
     */
    void publishConjectureInvestigation(
            UUID roomId,
            Researcher author,
            com.emergentterrain.research.conjecture.Conjecture conjecture,
            long gameTick,
            Context ctx) {
        String paperId = ctx.nextPaperId(roomId);
        String topicName = "conjecture/" + conjecture.lhs();
        String title = String.format(
                "Investigation of bound on %s (%s ≤ %+.4f · %s + %+.4f)",
                conjecture.lhs(), conjecture.lhs(),
                conjecture.k(), conjecture.rhs(), conjecture.c());
        String hypothesis = synthesizeConjectureHypothesis(conjecture);
        String findings = synthesizeConjectureFindings(conjecture);
        String conclusion = synthesizeConjectureConclusion(conjecture);

        // Map p99Slack into a peer-equivalent score: tighter (more
        // negative or near-zero slack with no violations) → higher score.
        // Saturate at [0.3, 1.0] so even loose-but-confirmed conjectures
        // get a defensible publication threshold.
        double slackBudget = 4.0 * com.emergentterrain.research.conjecture.ConjectureEngine.TOL;
        double normalized = 1.0
                - (conjecture.p99Slack() + com.emergentterrain.research.conjecture.ConjectureEngine.TOL) / slackBudget;
        double peerScore = Math.max(0.3, Math.min(1.0, normalized));

        PaperDraft draft = new PaperDraft(
                roomId, author.getBotId(),
                author.getDomain(),
                topicName,
                title,
                findings, hypothesis, conclusion,
                "EMPIRICAL",
                List.of(),
                peerScore, peerScore,
                0.0,
                gameTick, Instant.now(),
                author.getGeneration(), paperId,
                null, null,
                false,
                null, null,
                ctx.getPublishedPapers(roomId, 5),
                author.getState().getResearchHypothesis(),
                null,
                ResultClassification.SUPPORTED,
                null,
                PaperKind.CONJECTURE_INVESTIGATION);

        Consumer<PaperDraft> narratingCallback = ctx.narratingCallback();
        if (narratingCallback != null) {
            draftQueueSize.incrementAndGet();
            narratingCallback.accept(draft);
        } else {
            ctx.publishPaper(draft, new NarrationResult(
                    hypothesis, findings, conclusion, null, null, 0.5, List.of()));
        }

        papersSinceTerm.merge(roomId, 1, Integer::sum);
        author.recordPublished(peerScore);
        log.info("conjecture investigation queued: room={} bot={} paperId={} conjecture=\"{}\" runs={}",
                roomId, author.getBotId(), paperId,
                String.format("%s <= %+.4f * %s + %+.4f",
                        conjecture.lhs(), conjecture.k(), conjecture.rhs(), conjecture.c()),
                conjecture.confirmedRuns());
    }

    private static String synthesizeConjectureHypothesis(
            com.emergentterrain.research.conjecture.Conjecture c) {
        return String.format(
                "The math-domain conjecture miner surfaced the inequality `%s ≤ %+.4f · %s + %+.4f` " +
                        "with %d confirmed runs. This paper investigates whether the bound holds on " +
                        "held-out windows the miner did not sample.",
                c.lhs(), c.k(), c.rhs(), c.c(), c.confirmedRuns());
    }

    private static String synthesizeConjectureFindings(
            com.emergentterrain.research.conjecture.Conjecture c) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Mining trace for %s ≤ %+.4f · %s + %+.4f (domain=%s):%n",
                c.lhs(), c.k(), c.rhs(), c.c(), c.domain()));
        sb.append(String.format("- Confirmed across %d runs.%n", c.confirmedRuns()));
        sb.append(String.format("- Worst observed margin (p99 slack): %+.5f.%n", c.p99Slack()));
        if (c.oeisMatchJson() != null && !c.oeisMatchJson().isEmpty()) {
            sb.append(String.format("- OEIS annotation present: %s%n", c.oeisMatchJson()));
        }
        sb.append("The verify_conjecture tool may be invoked on held-out windows for further evidence.");
        return sb.toString();
    }

    private static String synthesizeConjectureConclusion(
            com.emergentterrain.research.conjecture.Conjecture c) {
        return String.format(
                "The inequality has not been refuted in the miner's observed regimes (p99 slack %+.5f " +
                        "across %d runs). Held-out verification via verify_conjecture is recommended " +
                        "before promoting this from CONJECTURE to EMPIRICAL_RESULT.",
                c.p99Slack(), c.confirmedRuns());
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
                Researcher reviewer = pickReviewer(roomId, pool, author, usedIds, rng);
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
                    null, dynClassification, null);

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

    private Researcher pickReviewer(UUID roomId, List<Researcher> pool, Researcher author,
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

        // Phase 4b — journal-mediated review narrows the candidate pool to the
        // journal's editor cohort (top-reputation same-domain researchers).
        // Empty editor list means no journal exists for this (room, domain),
        // so we fall through to the existing same-domain pick.
        if (journalReviewEnabled && institutionService != null) {
            List<Researcher> editors = institutionService.journalEditorsFor(
                    roomId, author.getDomain(), pick, JOURNAL_EDITOR_LIMIT);
            if (!editors.isEmpty()) {
                List<String> alreadyPickedSnapshot = List.copyOf(alreadyPicked);
                List<Researcher> editorCandidates = editors.stream()
                        .filter(r -> !r.getBotId().equals(author.getBotId())
                                  && !alreadyPickedSnapshot.contains(r.getBotId()))
                        .collect(Collectors.toList());
                if (!editorCandidates.isEmpty()) pick = editorCandidates;
            }
        }

        // Phase 4b — when reviewer-routing is on, pick the reviewer whose
        // research bias is most adversarial to the author. Maximises peer-
        // review pressure (the phase doc's "highest adversarial pressure").
        // Falls back to random pick on ties or when scores are inconclusive.
        if (reviewerOrthogonalityEnabled && pick.size() > 1) {
            Researcher mostOrthogonal = pickMostOrthogonal(author, pick);
            if (mostOrthogonal != null) return mostOrthogonal;
        }
        return pick.get(rng.nextInt(pick.size()));
    }

    /**
     * Phase 4b helper — score each candidate by 1 - cosine_similarity of the
     * author's and candidate's BotGenome.topicWeights vectors (the
     * research-bias proxy from Phase 1 Iter 2's ResearchIdentity). Returns
     * the candidate with the highest orthogonality, or null if no candidate
     * has a non-zero score (e.g. all topic weights identical or zero).
     */
    static Researcher pickMostOrthogonal(Researcher author, List<Researcher> candidates) {
        double[] authorWeights = topicWeightsOf(author);
        if (authorWeights == null) return null;
        Researcher best = null;
        double bestScore = -1.0;
        for (Researcher c : candidates) {
            double[] cw = topicWeightsOf(c);
            if (cw == null) continue;
            double sim = cosineSimilarity(authorWeights, cw);
            // NaN means at least one vector had zero norm — no signal.
            if (Double.isNaN(sim)) continue;
            double score = 1.0 - sim;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        // Treat "all candidates identically aligned with author" as no preference.
        return bestScore > 1e-6 ? best : null;
    }

    private static double[] topicWeightsOf(Researcher r) {
        if (r == null || r.getState() == null) return null;
        BotGenome g = r.getState().getGenome();
        return g != null ? g.getTopicWeights() : null;
    }

    /**
     * Cosine similarity in [0,1] for non-negative topic weights. Returns
     * {@code NaN} when either vector has zero norm — caller should treat that
     * as "no signal" rather than maximum orthogonality.
     */
    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return Double.NaN;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) return Double.NaN;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
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
