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
    /**
     * In-flight drafts, keyed by paperId → enqueue timestamp (ms). Replaces a raw
     * counter so release is <em>idempotent</em>: {@code remove} of an absent paperId
     * is a no-op, letting the same draft be released by {@code publishPaper}, the
     * narration task's {@code finally}, and the stuck-draft reaper without ever
     * double-counting. {@link #draftQueueSize()} = {@code size()} is the backlog the
     * {@code maybeTick} gate reads. A leaked draft (narration threw or hung before
     * {@code publishPaper}) no longer pins its slot forever — the {@code finally}
     * releases it on task end and {@link #reapStuckDrafts()} reclaims true hangs.
     */
    private final ConcurrentHashMap<String, Long> inFlightDrafts = new ConcurrentHashMap<>();

    /** Age (ms) after which an in-flight draft is presumed hung and its slot reclaimed. */
    @org.springframework.beans.factory.annotation.Value("${app.research.stuckDraftTtlMs:600000}")
    private long stuckDraftTtlMs;

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
     * Phase 30 fork 2b Path B — when ON, a conjecture-investigation paper whose
     * scalars map to an existing beyond-sieve discovery domain (the cumulative
     * Mertens function M(x) or the Dirichlet divisor sum D(x)) is framed as an
     * <em>asymptotic / frontier</em> question instead of an in-range verification:
     * the hypothesis asks whether the bound's behaviour persists past the sieve
     * ceiling (NT_MAX_N), and the seed text steers the narrator toward the
     * beyond-sieve tools ({@code discover_mertens_value} / {@code
     * recall_frontier_knowledge}) rather than only the in-range {@code
     * verify_conjecture}. Conjectures with no discovery oracle (e.g. ω-class /
     * density bounds) are unaffected — they keep the in-range framing, because
     * posing a beyond-sieve claim no tool can validate would be vacuous (extending
     * Path B to them needs a new oracle — Path A). Default OFF; flag-gated rollout
     * matching the rest of this service. See
     * phases-for-human-like-performance/phase-30-conjecture/frontier-escalation-loop.md.
     */
    private volatile boolean conjectureFrontierFramingEnabled = false;

    public void setConjectureFrontierFramingEnabled(boolean enabled) {
        this.conjectureFrontierFramingEnabled = enabled;
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
     * 2026-06-05 — graceful budget drain. Optional (absent in unit tests / when
     * the budget feature is off). When the tracker is DRAINING, {@code runCycle}
     * stops launching new paper pipelines and {@code reapStuckDrafts} reports
     * quiescence (in-flight queue empty) back to the tracker for the clean stop.
     */
    private volatile com.emergentterrain.llm.RunBudgetTracker runBudgetTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunBudgetTracker(com.emergentterrain.llm.RunBudgetTracker tracker) {
        this.runBudgetTracker = tracker;
    }

    /** Logged once per drain so the skip isn't a silent behaviour change. */
    private final java.util.concurrent.atomic.AtomicBoolean drainSkipLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
     * 2026-05-29 — when non-empty, restrict author selection to this engine
     * domain (e.g. "MATHEMATICS"); empty = no restriction (default). Lets the
     * room run a single domain for a phase — e.g. math-only while verifying the
     * conjecture path. Setter-injected from {@code app.research.restrictDomain}
     * (Spring calls it in production; tests using {@code new} keep the default).
     * Falls back to the full pool if no bot of the domain exists, so the room
     * never stalls. NOTE: this only narrows the AUTHOR; reviewers/replication
     * still draw from the full pool (pickReviewer already prefers same-domain).
     */
    private volatile String restrictDomain = "";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.Environment env;

    /**
     * 2026-05-29 — resolve restrictDomain from the Spring Environment at init
     * (robust against setter-injection not firing) and log the resolved value
     * plus whether the conjecture provider is wired, for verification. Tests
     * that {@code new} this service skip @PostConstruct, keeping the default.
     */
    @jakarta.annotation.PostConstruct
    void initRestrictDomainFromEnv() {
        if (env != null) {
            String v = env.getProperty("app.research.restrictDomain", "");
            this.restrictDomain = (v == null) ? "" : v.trim();
        }
        log.info("ResearchCycleService init: restrictDomain='{}' conjectureProvider={}",
                restrictDomain, conjectureTopicProvider != null);
    }

    public void setRestrictDomain(String domain) {
        this.restrictDomain = domain == null ? "" : domain.trim();
    }

    public String restrictDomain() {
        return restrictDomain;
    }

    /**
     * Narrow the author-selection pool to {@link #restrictDomain} when set;
     * returns the full pool unchanged when unset or when the domain has no bots.
     */
    private List<Researcher> restrictAuthorPool(List<Researcher> pool) {
        if (restrictDomain.isEmpty()) return pool;
        List<Researcher> filtered = pool.stream()
                .filter(r -> restrictDomain.equalsIgnoreCase(r.getDomain()))
                .collect(Collectors.toList());
        return filtered.isEmpty() ? pool : filtered;
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

    // 2026-06-08 — yield-aware (low-yield-null) backoff: density-family
    // down-weight. When on, the topic-clone backoff ALSO cools a topic whose
    // recent history is dominated by low-peer NULL_RESULTs (the density floor).
    private volatile boolean lowYieldBackoffEnabled = false;
    private volatile double lowYieldPeerCeiling = 0.60;
    private volatile double lowYieldNullDecay   = 0.70;

    public void setLowYieldBackoffEnabled(boolean enabled) {
        this.lowYieldBackoffEnabled = enabled;
    }

    public void setLowYieldPeerCeiling(double ceiling) {
        this.lowYieldPeerCeiling = ceiling;
    }

    public void setLowYieldNullDecay(double decay) {
        this.lowYieldNullDecay = decay;
    }

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

    public int draftQueueSize()                  { return inFlightDrafts.size(); }

    /** Record a draft as in-flight (occupying a backlog slot) at enqueue time. */
    void markDraftInFlight(String paperId) {
        if (paperId != null) inFlightDrafts.put(paperId, System.currentTimeMillis());
    }

    /**
     * Release a draft's backlog slot. Idempotent — removing an absent paperId is a
     * no-op — so this is safe to call from every settle path (publishPaper success
     * / suppression / reject, the narration task's finally, and the reaper) without
     * risk of double-counting the backlog negative.
     */
    public void decrementDraftQueue(String paperId) {
        if (paperId != null) inFlightDrafts.remove(paperId);
    }

    /**
     * Stuck-draft watchdog. A narration task that hangs (an LLM call that never
     * returns) never runs its finally, so without this its slot would be pinned
     * forever and — once maxDraftBacklog of them accumulate — silently dead-lock
     * all future research (P-a328 room, 2026-05-25: 3 drafts narrated then hung
     * post-audit, draftQueue stuck at 3, zero new cycles for 25+ min). Reap any
     * draft in-flight longer than the TTL so the backlog self-heals.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.research.stuckDraftScanMs:60000}")
    public void reapStuckDrafts() {
        reapStuckDrafts(stuckDraftTtlMs);
        // 2026-06-05 — graceful drain completion: while DRAINING, an empty
        // in-flight queue means every paper either published or was reaped
        // (the TTL sweep above guarantees a hung narration can't pin the
        // drain open forever) — report quiescence for the clean stop.
        com.emergentterrain.llm.RunBudgetTracker tracker = runBudgetTracker;
        if (tracker != null && tracker.isDraining() && inFlightDrafts.isEmpty()) {
            tracker.notifyQuiescent("in-flight drafts complete (draftQueue=0)");
        }
    }

    /** Reap drafts in-flight longer than {@code ttlMs}; returns the count reclaimed. */
    int reapStuckDrafts(long ttlMs) {
        long now = System.currentTimeMillis();
        int reaped = 0;
        for (var e : inFlightDrafts.entrySet()) {
            long ageMs = now - e.getValue();
            if (ageMs > ttlMs && inFlightDrafts.remove(e.getKey(), e.getValue())) {
                reaped++;
                log.warn("research: reaped stuck draft paperId={} ageMs={} (narration hung/never settled) — "
                        + "released backlog slot, draftQueue={}", e.getKey(), ageMs, inFlightDrafts.size());
            }
        }
        return reaped;
    }
    public int papersSinceTerm(UUID roomId)      { return papersSinceTerm.getOrDefault(roomId, 0); }
    public void resetPapersSinceTerm(UUID roomId) { papersSinceTerm.put(roomId, 0); }
    public int totalRejected(UUID roomId)        { return totalRejected.getOrDefault(roomId, 0); }

    // ---- Cycle ----

    public void runCycle(UUID roomId, List<Researcher> pool, Random rng, long gameTick,
                         PopulationEcologyService.PopulationParams popParams,
                         boolean peerReviewEnabled, Context ctx) {
        // 2026-06-05 — graceful drain: once the run budget enters DRAINING, no new
        // paper pipeline may start; in-flight drafts finish on the remaining budget.
        com.emergentterrain.llm.RunBudgetTracker tracker = runBudgetTracker;
        if (tracker != null && !tracker.allowNewResearch()) {
            if (drainSkipLogged.compareAndSet(false, true)) {
                log.info("research: run budget draining — skipping new research cycles; "
                        + "{} draft(s) in flight will complete", inFlightDrafts.size());
            }
            return;
        }
        Researcher author = tournamentSelect(restrictAuthorPool(pool), rng);

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
            // Phase 30 Wave 7 — 30.6: a promoted anomaly outranks ordinary conjecture
            // selection (rare by construction: requires surviving the adversarial
            // replication gate; self-limiting: one paper per promotion stamp).
            java.util.Optional<com.emergentterrain.research.conjecture.Conjecture> promoted =
                    conjectureTopicProvider.selectPromotedAnomaly(domain);
            if (promoted.isPresent()) {
                conjectureTopicProvider.markInvestigated(promoted.get());
                publishAnomalyPromotion(roomId, author, promoted.get(), gameTick, ctx);
                return;
            }
            java.util.Optional<com.emergentterrain.research.conjecture.Conjecture> conjecture =
                    conjectureTopicProvider.selectConjecture(domain, rng);
            if (conjecture.isPresent()) {
                // Phase 30 Wave 0 — record consumption so the queue rotates away
                // from this conjecture next cycle (no-op unless rotation flag on).
                conjectureTopicProvider.markInvestigated(conjecture.get());
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
            // 2026-06-08 — yield-aware variant cools the density floor too (a
            // topic dominated by low-peer NULLs); rejection-only otherwise.
            int finalIndex = lowYieldBackoffEnabled
                    ? TopicCloneBackoff.applyYieldAwareBackoff(
                            initialTopic, initialName, domain, historyForBackoff,
                            resample, topicCloneBackoffMaxRetries,
                            lowYieldPeerCeiling, lowYieldNullDecay, rng)
                    : TopicCloneBackoff.applyBackoff(
                            initialTopic, initialName, domain, historyForBackoff,
                            resample, topicCloneBackoffMaxRetries, rng);
            if (finalIndex != initialTopic) {
                log.info("topic-clone backoff: bot={} domain={} initial={} "
                                + "(rej={} lowYieldNull={}) → final={}",
                        author.getBotId(), domain, initialName,
                        TopicCloneBackoff.countRecentRejections(historyForBackoff, domain, initialName),
                        lowYieldBackoffEnabled
                                ? TopicCloneBackoff.countRecentLowYieldNulls(
                                        historyForBackoff, domain, initialName, lowYieldPeerCeiling)
                                : 0,
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
                markDraftInFlight(draft.paperId());
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
        // Path B — frontier-frame the investigation when enabled AND the conjecture's
        // scalars map to a discovery domain that can compute the quantity past the sieve.
        // Otherwise (and for all ω/density bounds) keep the in-range framing.
        FrontierDomain frontier = conjectureFrontierFramingEnabled
                ? frontierDomainFor(conjecture.lhs(), conjecture.rhs()) : null;
        String hypothesis = frontier == null
                ? synthesizeConjectureHypothesis(conjecture)
                : synthesizeFrontierHypothesis(conjecture, frontier);
        String findings = frontier == null
                ? synthesizeConjectureFindings(conjecture)
                : synthesizeFrontierFindings(conjecture, frontier);
        String conclusion = frontier == null
                ? synthesizeConjectureConclusion(conjecture)
                : synthesizeFrontierConclusion(conjecture, frontier);

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
                null, conjecture.c(),   // param2 carries the fitted constant for §26.4-style recognition
                false,
                null, null,
                ctx.getPublishedPapers(roomId, 5),
                author.getState().getResearchHypothesis(),
                null,
                ResultClassification.SUPPORTED,
                null,
                PaperKind.CONJECTURE_INVESTIGATION,
                frontier);   // null for in-range framing; drives the professor frontierHint

        Consumer<PaperDraft> narratingCallback = ctx.narratingCallback();
        if (narratingCallback != null) {
            markDraftInFlight(draft.paperId());
            narratingCallback.accept(draft);
        } else {
            ctx.publishPaper(draft, new NarrationResult(
                    hypothesis, findings, conclusion, null, null, 0.5, List.of()));
        }

        papersSinceTerm.merge(roomId, 1, Integer::sum);
        author.recordPublished(peerScore);
        log.info("conjecture investigation queued: room={} bot={} paperId={} framing={} conjecture=\"{}\" runs={}",
                roomId, author.getBotId(), paperId,
                frontier == null ? "in-range" : "frontier:" + frontier.boundKey,
                String.format("%s <= %+.4f * %s + %+.4f",
                        conjecture.lhs(), conjecture.k(), conjecture.rhs(), conjecture.c()),
                conjecture.confirmedRuns());
    }

    /**
     * Phase 30 Wave 7 — 30.6: publish a promoted anomaly as an UNEXPLAINED_OBSERVATION
     * investigation. The seed framing states exactly what is established (held N runs in
     * its regime, broke hard elsewhere, the break REPLICATED across adversarial
     * refutation attempts, no OEIS match) and instructs the professor to attack it again
     * with its tools, label honestly (UNEXPLAINED_OBSERVATION, never SUPPORTED), and
     * close with the open question. Kind {@link PaperKind#ANOMALY_PROMOTION} drives the
     * publish-time {@code ProgramOpenQuestion} seeding in {@code UniversityService}.
     */
    void publishAnomalyPromotion(
            UUID roomId,
            Researcher author,
            com.emergentterrain.research.conjecture.Conjecture conjecture,
            long gameTick,
            Context ctx) {
        String paperId = ctx.nextPaperId(roomId);
        String topicName = "anomaly/" + conjecture.lhs();
        String title = String.format(
                "Unexplained regime-dependence of %s (held bound %s ≤ %+.4f · %s + %+.4f broke and replicated)",
                conjecture.lhs(), conjecture.lhs(),
                conjecture.k(), conjecture.rhs(), conjecture.c());
        FrontierDomain frontier = conjectureFrontierFramingEnabled
                ? frontierDomainFor(conjecture.lhs(), conjecture.rhs()) : null;

        String hypothesis = String.format(
                "ANOMALY (promoted open problem): the bound `%s ≤ %+.4f · %s + %+.4f` was a real "
                        + "pattern first — confirmed across %d run(s) in its mined regime — then broke "
                        + "surprisingly hard elsewhere, and the break REPLICATED under %s independent "
                        + "adversarial re-verification attempts (fresh windows, both regimes). No OEIS "
                        + "match is known. The open question: WHAT distinguishes the regimes? This paper "
                        + "characterizes the regime-dependence; it does not claim the bound.",
                conjecture.lhs(), conjecture.k(), conjecture.rhs(), conjecture.c(),
                conjecture.confirmedRuns(), "multiple");
        String findings = String.format(
                "Promotion record for %s ≤ %+.4f · %s + %+.4f: confirmed %d run(s), then "
                        + "counterexample-refuted; the violation reproduced under fresh adversarial "
                        + "probes in the break regime while the hold regime stayed clean (promotion "
                        + "gate). Investigate with your tools: verify_conjecture in BOTH regimes; "
                        + "scalar_window_stats to characterize where the transition happens and what "
                        + "the scalars do across it; search_literature for prior work. Label this "
                        + "UNEXPLAINED_OBSERVATION — the honest outcome here is a well-characterized "
                        + "open problem, NOT a supported claim. If your probes refute the anomaly "
                        + "(violations in the hold regime, or the break vanishing), say so plainly: "
                        + "a refuted anomaly is a valid null result.",
                conjecture.lhs(), conjecture.k(), conjecture.rhs(), conjecture.c(),
                conjecture.confirmedRuns());
        String conclusion = "State what is established (the replicated regime-dependence), what is "
                + "open (its cause), and the sharpest form of the open question your evidence "
                + "supports. No proof claims; no asymptotic claims beyond the probed ranges.";

        double peerScore = 0.5;   // neutral seed; the panel re-scores on content
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
                null, conjecture.c(),   // fitted constant → conjecture-constant recognition
                false,
                null, null,
                ctx.getPublishedPapers(roomId, 5),
                author.getState().getResearchHypothesis(),
                null,
                ResultClassification.SUPPORTED,
                null,
                PaperKind.ANOMALY_PROMOTION,
                frontier);

        Consumer<PaperDraft> narratingCallback = ctx.narratingCallback();
        if (narratingCallback != null) {
            markDraftInFlight(draft.paperId());
            narratingCallback.accept(draft);
        } else {
            ctx.publishPaper(draft, new NarrationResult(
                    hypothesis, findings, conclusion, null, null, 0.5, List.of()));
        }
        papersSinceTerm.merge(roomId, 1, Integer::sum);
        author.recordPublished(peerScore);
        log.info("ANOMALY PROMOTION queued: room={} bot={} paperId={} conjecture=\"{}\" confirmedRuns={}",
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

    // ---- Path B: frontier-framed conjecture investigation ----

    /**
     * A beyond-sieve discovery domain a conjecture's scalars map to. Carries the
     * human label for the cumulative quantity, the frontier-knowledge corpus key
     * ({@code recall_frontier_knowledge} reads it), and the per-paper discovery
     * tool name if one exists ({@code null} = recall-only, no per-paper compute).
     */
    // FrontierDomain promoted to a top-level enum (same package, 2026-06-05) so it can be
    // threaded through PaperDraft to the professor stage's frontierHint.

    /**
     * Map a conjecture's scalars to a beyond-sieve discovery domain, or {@code null}
     * if none — mirroring {@code MathToolSuite.defaultRecallBound}: {@code mertens_delta}
     * is Mertens-class; a {@code divisor}-named scalar is divisor-class; a {@code prime}-named
     * scalar (e.g. {@code prime_density}) is prime-class (π(x)). Checks both sides so a bound
     * is frontier-framed whether the discovery quantity is the bounded or the bounding term.
     * Bounds with no discovery oracle (other ω / density scalars) return {@code null}.
     */
    static FrontierDomain frontierDomainFor(String lhs, String rhs) {
        String l = lhs == null ? "" : lhs;
        String r = rhs == null ? "" : rhs;
        if (l.contains("mertens") || r.contains("mertens")) {
            return FrontierDomain.MERTENS;
        }
        if (l.contains("divisor") || r.contains("divisor")) {
            return FrontierDomain.DIVISOR;
        }
        if (l.contains("prime") || r.contains("prime")) {
            return FrontierDomain.PRIME;
        }
        return null;
    }

    /** Names the beyond-sieve tool(s) the narrator should reach for in this domain. */
    private static String frontierToolGuidance(FrontierDomain fd) {
        String recall = "`recall_frontier_knowledge` (bound `" + fd.boundKey
                + "`) for the accumulated beyond-sieve frontier record";
        if (fd.discoverTool != null) {
            return "`" + fd.discoverTool + "` to compute " + fd.quantity.split(" ")[0]
                    + " at a specific x beyond the 2,000,000 sieve ceiling, and " + recall;
        }
        return recall + " (no per-paper compute tool in this domain — the autonomous frontier job populates it)";
    }

    private static String synthesizeFrontierHypothesis(
            com.emergentterrain.research.conjecture.Conjecture c, FrontierDomain fd) {
        return String.format(
                "The miner surfaced `%s ≤ %+.4f · %s + %+.4f` from windows inside the trusted sieve "
                        + "range (x ≤ 2,000,000), where it held across %d runs. This bound concerns %s, a "
                        + "quantity that can be carried PAST the sieve. The real question is asymptotic: does "
                        + "the bound's behaviour persist beyond the sieve ceiling, or does it eventually fail? "
                        + "This paper investigates the bound at the frontier, x > 2,000,000, where no direct "
                        + "oracle exists.",
                c.lhs(), c.k(), c.rhs(), c.c(), c.confirmedRuns(), fd.quantity);
    }

    private static String synthesizeFrontierFindings(
            com.emergentterrain.research.conjecture.Conjecture c, FrontierDomain fd) {
        return String.format(
                "In-range trace for %s ≤ %+.4f · %s + %+.4f: confirmed across %d runs, worst margin "
                        + "(p99 slack) %+.5f.%n"
                        + "Beyond the sieve, gather evidence with %s. Any value returned past x=2,000,000 is a "
                        + "COMPUTED, moat-validated result, NOT an established fact — report it as such and make "
                        + "no claim of proof. Cross-check the in-range regime with `verify_conjecture` (capped at "
                        + "the sieve range) so the in-range and beyond-sieve evidence are clearly separated.",
                c.lhs(), c.k(), c.rhs(), c.c(), c.confirmedRuns(), c.p99Slack(), frontierToolGuidance(fd));
    }

    private static String synthesizeFrontierConclusion(
            com.emergentterrain.research.conjecture.Conjecture c, FrontierDomain fd) {
        return String.format(
                "In-range the bound is unrefuted (p99 slack %+.5f over %d runs). Whether it holds asymptotically "
                        + "is open: a beyond-sieve counterexample from the frontier record would refute it, while "
                        + "continued holding is computed evidence — never a proof. Frame any frontier value as "
                        + "\"computed via a moat-validated method\", with no claim about the underlying conjecture.",
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
                markDraftInFlight(draft.paperId());
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
