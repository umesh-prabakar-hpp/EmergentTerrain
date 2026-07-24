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
     * Phase 30 (measurement-frame-default.md, tag claim-type-shift) — when on, an object with a
     * registered intrinsic asymptotic ({@link com.emergentterrain.research.conjecture.ScalarOpenness#MEASUREMENT_FRAMES})
     * is framed as a MEASUREMENT (verify_constant/oscillation/growth — the claim that scored &gt;0.4 this
     * session) rather than the default affine-bound proxy-wrapper (~0.22). OFF in code / ON in yaml; the
     * affine-bound framing is bit-identical when off.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.measurement-frame-default.enabled:false}")
    private boolean measurementFrameDefault;

    /**
     * Phase 33 lane T2 (2026-07-05) — serve the ledger's open questions back onto their family's
     * topics. v1 is RIDE-ALONG: when rotation serves family X, X's top unanswered self-harvested
     * question is appended to the hypothesis as the preferred load-bearing claim, and marked
     * answered-by the serving paper (served-once semantics; a coverage-checked "actually
     * answered" refinement is T2b). An explicit question-driven selection source stays deferred
     * until ride-along proves too slow. Repos are always-present beans; the flags gate behavior.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.question-serve.enabled:false}")
    private boolean questionServeEnabled;
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.results-ledger.enabled:false}")
    private boolean resultsLedgerServeEnabled;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.repo.PaperOpenQuestionRepository openQuestionRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.repo.VerifiedResultRepository verifiedResultRepo;

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

    /**
     * Phase 32 (brief Change 1) — optional well-posedness gate. When present and enabled, an ill-posed
     * directive (representation-dependent, no-limiting-value, tooling-artifact…) gets a caveat prepended
     * to its hypothesis so the writer opens by stating it. Caveat-first, best-effort; null = no-op.
     */
    private volatile WellPosednessGate wellPosednessGate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWellPosednessGate(WellPosednessGate gate) {
        this.wellPosednessGate = gate;
    }

    /**
     * 2026-06-15 (review-2026-06-15a Rec 1) — when ON, a directive the well-posedness gate flags
     * {@code representation_dependent} (the recurring base-10 happy-prime density topic — no
     * representation-independent density) is PRUNED: skipped + logged as a boundary event, not
     * caveated-and-published. A deliberate, flag-gated exception to caveat-first for that one
     * non-research-grade class; OFF preserves caveat-first exactly. Other ill-posed classes still
     * get the caveat.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.wellposedness-gate.prune-representation-dependent:false}")
    private boolean prunePosednessRepresentationDependent;

    /** Run-35 rec 1 — redirect known-constant density topics to second-order framing. */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.wellposedness-gate.known-density-redirect:false}")
    private boolean knownDensityRedirectEnabled;

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
            directive = applyWellPosednessCaveat(directive);   // brief Change 1 — annotate ill-posed topics
            if (directive == null) {   // pruned (representation-dependent base-10 topic) — drop this cycle
                return;                // directive already consumed; the author re-draws next tick
            }
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
        // measurement-frame-default.md Stage 1f — a registered measurement object (CONSTANT/OSCILLATION/
        // GROWTH) is framed as a MEASUREMENT, not a bound. run-13: dirichlet_error was titled "bound on
        // ..." and frontier-framed, so the professor tested the seed bound instead of laddering the
        // envelope and the OSCILLATION auto-ladder never fired. (a) Drop the affine bound from the title
        // so the professor isn't anchored on bound-testing; (b) let the measurement reframe win over the
        // frontier frame below (the cross-scale ladder to MAX_N IS the frontier investigation).
        com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementSpec measurementSpec =
                measurementFrameDefault
                        ? com.emergentterrain.research.conjecture.ScalarOpenness.measurementFrame(conjecture.lhs())
                        : null;
        String title = measurementSpec != null
                ? String.format("Measurement of %s (%s across scales)",
                        conjecture.lhs(), measurementFrameWord(measurementSpec.frame()))
                : String.format(
                        "Investigation of bound on %s (%s ≤ %s)",
                        conjecture.lhs(), conjecture.lhs(), formatBoundBody(conjecture));
        // Path B — frontier-frame the investigation when enabled AND the conjecture's
        // scalars map to a discovery domain that can compute the quantity past the sieve.
        // Otherwise (and for all ω/density bounds) keep the in-range framing. G2: a two-term bound
        // (rhs2 set) is a sieve-range residual relation — keep it in-range (no frontier mapping).
        // Stage 1f: a registered measurement object (measurementSpec != null) takes the measurement
        // reframe, NOT the frontier bound frame, so synthesizeConjectureHypothesis applies the auto-ladder.
        FrontierDomain frontier = (conjectureFrontierFramingEnabled && conjecture.rhs2() == null
                        && measurementSpec == null)
                ? frontierDomainFor(conjecture.lhs(), conjecture.rhs()) : null;
        String hypothesis = frontier == null
                ? synthesizeConjectureHypothesis(conjecture, measurementFrameDefault)
                : synthesizeFrontierHypothesis(conjecture, frontier);
        // Phase 33 lanes T2 + C1 (2026-07-05) — cross-run context rides on the served topic:
        // the family's most recent VERIFIED results (data for cross-scale/rate claims) and its
        // top unanswered self-harvested open question (the preferred load-bearing claim).
        hypothesis = appendCrossRunContext(hypothesis, conjecture.lhs(), paperId);
        // 2026-07-05 (P-b72c-77627773) — under the measurement frame, the findings/conclusion
        // seeds must not carry the row's affine bound either: the seeded "dirichlet_error ≤
        // 0.0016" leaked through the FINDINGS seed and the professor spent a call "refuting" an
        // intercept that was a row artifact ~1000× off the quantity's actual scale, then the
        // trivial refutation polluted the paper's framing. Measurement seeds get measurement text.
        String findings = frontier == null
                ? (measurementSpec != null
                        ? synthesizeMeasurementFindings(conjecture, measurementSpec)
                        : synthesizeConjectureFindings(conjecture))
                : synthesizeFrontierFindings(conjecture, frontier);
        String conclusion = frontier == null
                ? (measurementSpec != null
                        ? synthesizeMeasurementConclusion(conjecture)
                        : synthesizeConjectureConclusion(conjecture))
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
                // A measurement investigation has a different registered object; do not
                // mislabel the miner's scaffold bound as its confirmatory test.
                measurementSpec == null ? ConjecturePreRegistration.from(conjecture) : null,
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
                conjecture.lhs() + " <= " + formatBoundBody(conjecture),
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

    /**
     * G2 Stage 4 — render a mined bound's RHS body: one-term {@code "+k · rhs + +c"} or two-term
     * {@code "+k · rhs + +k2 · rhs2 + +c"} (rhs2 set). Used wherever the conjecture hypothesis/title is
     * framed, so a residual-discovered two-term relation reaches the professor as a two-term inequality.
     */
    static String formatBoundBody(com.emergentterrain.research.conjecture.Conjecture c) {
        if (c.rhs2() == null) {
            return String.format("%+.4f · %s + %+.4f", c.k(), c.rhs(), c.c());
        }
        return String.format("%+.4f · %s + %+.4f · %s + %+.4f",
                c.k(), c.rhs(), c.k2(), c.rhs2(), c.c());
    }

    /** measurement-frame-default.md Stage 1f — the noun for a measurement-object title by frame. */
    private static String measurementFrameWord(
            com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementFrame frame) {
        return switch (frame) {
            case CONSTANT -> "limit";
            case OSCILLATION -> "envelope";
            case GROWTH -> "growth law";
        };
    }

    /**
     * Phase 33 lanes T2 + C1 (2026-07-05) — query both cross-run ledgers for the served family
     * and append what they hold; mark a served question answered-by this paper (served-once,
     * see the field javadoc). Failure-soft: any repo error returns the hypothesis unchanged.
     */
    private final java.util.concurrent.atomic.AtomicBoolean reservationSweepDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Run-start sweep: reservations whose draft never persisted return to servable. */
    private void sweepStaleReservations() {
        if (!reservationSweepDone.compareAndSet(false, true)) {
            return;
        }
        try {
            var stale = openQuestionRepo.findByAnsweredByPaperIdIsNotNullAndAnsweredAtIsNull();
            for (var q : stale) {
                log.info("question-serve: sweeping stale reservation (question from {} reserved "
                        + "by dead draft {}) — servable again", q.paperId(), q.answeredByPaperId());
                q.clearReservation();
            }
            if (!stale.isEmpty()) {
                openQuestionRepo.saveAll(stale);
            }
        } catch (RuntimeException e) {
            log.warn("stale-reservation sweep failed (serving continues): {}", e.getMessage());
        }
    }

    private String appendCrossRunContext(String hypothesis, String family, String paperId) {
        try {
            java.util.List<com.emergentterrain.research.question.VerifiedResult> results =
                    (resultsLedgerServeEnabled && verifiedResultRepo != null)
                            ? verifiedResultRepo.findTop6ByFamilyOrderByCreatedAtDesc(family)
                            : java.util.List.of();
            com.emergentterrain.research.question.PaperOpenQuestion question = null;
            if (questionServeEnabled && openQuestionRepo != null) {
                sweepStaleReservations();
                // Two-phase answering (2026-07-06): servable = never reserved. A reservation
                // becomes answered only when the paper actually persists (publish-side confirm);
                // stale reservations from dead drafts are swept back at run start.
                var open = openQuestionRepo.findByFamilyAndAnsweredByPaperIdIsNull(family);
                question = open.stream()
                        .max(java.util.Comparator.comparing(
                                com.emergentterrain.research.question.PaperOpenQuestion::createdAt))
                        .orElse(null);
            }
            String rendered = renderCrossRunBlocks(hypothesis, results, question);
            if (question != null && !rendered.equals(hypothesis)) {
                question.reserve(paperId);
                openQuestionRepo.save(question);
                log.info("question-serve: family={} question from {} served to {} (kind={})",
                        family, question.paperId(), paperId, question.kind());
            }
            return rendered;
        } catch (RuntimeException e) {
            log.warn("cross-run context append failed family={} (topic unaffected): {}",
                    family, e.getMessage());
            return hypothesis;
        }
    }

    /** Pure formatting for {@link #appendCrossRunContext}; static for tests. */
    static String renderCrossRunBlocks(
            String hypothesis,
            java.util.List<com.emergentterrain.research.question.VerifiedResult> results,
            com.emergentterrain.research.question.PaperOpenQuestion question) {
        StringBuilder sb = new StringBuilder(hypothesis == null ? "" : hypothesis);
        if (results != null && !results.isEmpty()) {
            sb.append("\n\nPRIOR VERIFIED RESULTS for this family (cross-run ledger, newest "
                    + "first). Treat these as DATA: cross-scale trends across them — how a tight "
                    + "constant or a measured value moves with window_start — are legitimate "
                    + "load-bearing claims for THIS paper, and fitting that trend is often the "
                    + "strongest available result. When the paper restates one of these numbers, "
                    + "attribute it to its bracketed paperId in the same sentence.");
            for (var r : results) {
                sb.append("\n- [").append(r.paperId()).append(", ").append(r.kind()).append("] ")
                        .append(r.statement());
            }
        }
        if (question != null) {
            sb.append("\n\nOPEN QUESTION FROM PRIOR WORK (").append(question.kind())
                    .append(", posed by ").append(question.paperId()).append("): \"")
                    .append(question.question())
                    .append("\" — if consistent with the frame above, ANSWERING or NARROWING this "
                            + "is the preferred load-bearing claim of this paper.");
        }
        return sb.toString();
    }

    static String synthesizeConjectureHypothesis(
            com.emergentterrain.research.conjecture.Conjecture c, boolean measurementFrameDefault) {
        // measurement-frame-default.md Stage 1 — for an object with a registered intrinsic asymptotic,
        // frame the investigation as the MEASUREMENT (the >0.4-novelty claim), not the default affine
        // bound (a ~0.22 proxy-wrapper). Frame-only: the seeded bound is unchanged, but the professor is
        // told the load-bearing claim is the limit/envelope/growth law, measured via the right tool.
        if (measurementFrameDefault) {
            com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementSpec spec =
                    com.emergentterrain.research.conjecture.ScalarOpenness.measurementFrame(c.lhs());
            if (spec != null) {
                return measurementHypothesis(c.lhs(), spec);
            }
        }
        String twoTermNote = c.rhs2() == null ? "" :
                " This is a TWO-TERM bound (a residual-discovered composite of two scalars); verify it "
                + "with verify_conjecture passing the second term via rhs2 and k2.";
        // rec #2 — a write-back-captured bound was verified at a specific failure region (high x). Its
        // intercept is asymptotic and is EXPECTED to fail at low x, so steer the investigation to the
        // capture region rather than the default low window (P-b72c-77627660 wasted a run testing at 300K
        // a bound captured tight at 1.2M).
        String captureNote = c.captureWindowStart() == null ? "" : String.format(
                " NOTE: this bound was previously verified at the failure region x≈%d; test held-out "
                + "windows AT OR ABOVE that region (its intercept is asymptotic and is expected to fail at "
                + "much lower x — do not judge it on a low-x window alone).",
                c.captureWindowStart());
        return String.format(
                "The math-domain conjecture miner surfaced the inequality `%s ≤ %s` " +
                        "with %d confirmed runs. This paper investigates whether the bound holds on " +
                        "held-out windows the miner did not sample.%s%s",
                c.lhs(), formatBoundBody(c), c.confirmedRuns(), twoTermNote, captureNote);
    }

    /**
     * measurement-frame-default.md Stage 1 — the measurement hypothesis for a registry object. The
     * load-bearing claim is the object's INTRINSIC asymptotic (a limit, an O(1) envelope, or a growth
     * law), measured with the right tool — NOT an affine bound relating it to other scalars (which the
     * panel rates as a representation-dependent proxy-wrapper). For an OPEN object that measurement is a
     * genuine sieve-capped result against an open problem.
     */
    static String measurementHypothesis(
            String lhs, com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementSpec spec) {
        String target = spec.target() == null ? "" : String.format(" (conjectured limit ≈ %s)", spec.target());
        // Range pattern for measurements (measurement-frame-default.md). A GEOMETRIC scale ladder, not a
        // single failure-region window — the trajectory across orders of magnitude is the strong claim AND
        // what exercises the extended reach (~2e9; run 7 confirmed the professors otherwise top out ~1.7M).
        // run-7 follow-on: CONVERGENCE-AWARE — tell the professor whether the object is a slow vs fast
        // converger so IT sizes the ladder smartly (slow → widest span + trend; fast → decade ladder + limit).
        boolean slow = spec.convergence() == com.emergentterrain.research.conjecture.ScalarOpenness.Convergence.SLOW;
        String ladderNote = " RANGE — this is a MEASUREMENT, not a bound: sample a GEOMETRIC scale ladder of "
                + "window_start (each ~10× the last) as SEPARATE calls and report how the result moves across "
                + "scales — the trajectory and its rate of approach, not a single window. "
                + (slow
                    ? String.format("`%s` is a SLOW converger — its approach to the limit is glacial "
                        + "(number-theoretic, ~1/ln x or 1/ln ln x), so it WILL look unconverged at any single "
                        + "window. Span the WIDEST ladder (≈1e5 up to near the ceiling ~1e9) and weight heavily "
                        + "toward the top where it is tightest; the load-bearing result is the TREND and whether "
                        + "the gap to the limit is shrinking (and how fast) — do NOT expect a clean 'converged' "
                        + "verdict, and for a normal-order quantity recover and report the additive constant.", lhs)
                    : String.format("`%s` converges at a moderate (≈power-law) rate, so a geometric decade "
                        + "ladder (≈1e5…1e9) should show CLEAR convergence — report the limit, the per-scale "
                        + "values, and confirm the rate.", lhs));
        String base = switch (spec.frame()) {
            case CONSTANT -> String.format(
                    "This paper measures the intrinsic limit of `%s`: whether it CONVERGES to a constant%s "
                    + "as x grows. Call verify_constant with scale_ladder:true — the tool sweeps a GEOMETRIC "
                    + "ladder of scales out to the trusted ceiling FOR you (do NOT hand-pick window_start; "
                    + "the cross-scale trajectory and its rate of approach are the load-bearing claim, and a "
                    + "slow converger only reveals its limit at high x). This is NOT an affine bound relating "
                    + "%s to other scalars (a representation-dependent proxy). Report the trajectory, the gap "
                    + "to the limit at each scale, and whether it is shrinking — not a single-window verdict.",
                    lhs, target, lhs);
            case OSCILLATION -> String.format(
                    "This paper measures whether `%s` oscillates within a BOUNDED O(1) envelope as x grows, "
                    + "or whether its amplitude GROWS slowly (e.g. log log x) — the open boundedness question. "
                    + "It is already normalised, so call verify_oscillation with scale_ladder:true and NO "
                    + "scale_rhs — the tool sweeps a GEOMETRIC ladder of scales out to the trusted ceiling FOR "
                    + "you (do NOT hand-pick window_start) and reports the amplitude/envelope at each scale and "
                    + "whether it is FLAT (bounded) or rising. The cross-scale envelope TREND is the "
                    + "load-bearing claim — NOT an affine bound, and NOT a single low-x window (which cannot "
                    + "distinguish a bounded amplitude from a slowly growing one). Report the amplitude at each "
                    + "scale and the trend across decades.",
                    lhs);
            case GROWTH -> String.format(
                    "This paper measures the GROWTH LAW of `%s`: whether it tracks the %s scale (its natural "
                    + "growth anchor), using verify_growth or an affine bound anchored on %s. The growth "
                    + "constant/exponent is the load-bearing claim — a flat-feature bound is refuted by "
                    + "construction. For the growth-CONSTANT measurement call verify_growth with "
                    + "scale_ladder:true — the tool sweeps a GEOMETRIC ladder of scales out to the trusted "
                    + "ceiling FOR you and never hits the WORK cap (manual high-x windows do). Testing and "
                    + "tightening the anchored bound itself stays on verify_conjecture at the windows you "
                    + "choose. Report the measured growth constant, its cross-scale trajectory, and how close "
                    + "it is to its asymptotic value at reachable x.",
                    lhs, spec.growthScale(), spec.growthScale());
        };
        // All three frames now have the auto scale-ladder built into their verify_* tool
        // (scale_ladder:true). GROWTH still keeps the manual-ladder note below: its ladderNote wording
        // doubles as the SLOW-converger trend-interpretation guidance, and its BOUND work (tighten pass)
        // legitimately hand-picks failure-region windows via verify_conjecture.
        var frame = spec.frame();
        boolean autoLaddered =
                frame == com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementFrame.CONSTANT
                || frame == com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementFrame.OSCILLATION;
        // Stage 1f — for an AUTO-LADDERED frame (CONSTANT/OSCILLATION) the seeded affine bound is a
        // discarded proxy scaffold: tell the professor to ignore it and run the scale-ladder measurement
        // (run-13 P-713 got the OSCILLATION framing yet tested the seed bound, scale_ladder 0×). GROWTH is
        // EXCLUDED — its bound (e.g. max_prime_gap ≤ k·log_sq) IS the measurement; the growth-anchor tighten
        // pass legitimately tests/tightens it via verify_conjecture, so it keeps the manual-ladder note and
        // NO ignore-seed instruction (which would break that working pass).
        // 2026-07-05 (run-23 rec 2) — a registered second-order directive supersedes the default
        // "measure the limit" claim: the constant is already verified, so the deficit/rate question
        // is the load-bearing one. Appended LAST so it wins recency over the generic framing.
        String secondOrderNote = spec.secondOrder() == null || spec.secondOrder().isBlank()
                ? "" : " " + spec.secondOrder();
        if (!autoLaddered) {
            return base + ladderNote + secondOrderNote;
        }
        String ignoreSeedNote = String.format(
                " The miner seeded a placeholder bound `%s ≤ …` only to REACH this topic — IGNORE it: do "
                + "NOT spend calls on verify_conjecture testing that inequality. The cross-scale measurement "
                + "above (the auto scale-ladder) is the ONLY load-bearing claim of this paper.", lhs);
        return base + ignoreSeedNote + secondOrderNote;
    }

    private static String synthesizeConjectureFindings(
            com.emergentterrain.research.conjecture.Conjecture c) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Mining trace for %s ≤ %s (domain=%s):%n",
                c.lhs(), formatBoundBody(c), c.domain()));
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

    /**
     * 2026-07-05 (P-b72c-77627773) — measurement-frame findings seed. The bound-flavoured seed
     * ("Mining trace for lhs ≤ …; verify_conjecture may be invoked…") anchored the professor to
     * bound-testing a placeholder row whose intercept is a store artifact, not a calibrated
     * claim. Under the measurement frame the seed names the object and the measuring tool only —
     * no affine bound, no verify_conjecture prompt.
     */
    static String synthesizeMeasurementFindings(
            com.emergentterrain.research.conjecture.Conjecture c,
            com.emergentterrain.research.conjecture.ScalarOpenness.MeasurementSpec spec) {
        return String.format(
                "Measurement seed for %s (registered %s object). Any affine bound stored on the "
                        + "miner's row for this object is a placeholder artifact of the store, NOT a "
                        + "calibrated preregistered claim — its intercept is not scale-informed, so do "
                        + "not spend tool calls refuting or tightening it. The load-bearing "
                        + "investigation is the measurement itself, via the matching verify tool "
                        + "across a geometric scale ladder.",
                c.lhs(), spec.frame());
    }

    /** 2026-07-05 — measurement-frame conclusion seed (see synthesizeMeasurementFindings). */
    static String synthesizeMeasurementConclusion(
            com.emergentterrain.research.conjecture.Conjecture c) {
        return String.format(
                "No affine bound claim is preregistered for %s. The cross-scale measurement "
                        + "trajectory (and, where registered, the distance to the conjectured target) "
                        + "is the result.",
                c.lhs());
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

    /**
     * Brief Change 1 — run the well-posedness gate on a directive and, if it flags ill-posedness, prepend
     * the caveat to the hypothesis so the writer opens by stating it (caveat-first, never dropped — per
     * inform-don't-suppress). 2026-06-15: returns {@code null} to signal PRUNE when the verdict is
     * {@code representation_dependent} AND {@code prunePosednessRepresentationDependent} is on (the
     * base-10 density topic — the single flag-gated exception to caveat-first; other ill-posed classes
     * still get the caveat). Best-effort: returns the directive unchanged when the gate is absent,
     * disabled, admits, or throws. The caveat rides {@code hypothesis} → {@code templateHypothesis} →
     * the writer seed.
     */
    private DynamicProblem applyWellPosednessCaveat(DynamicProblem d) {
        WellPosednessGate gate = wellPosednessGate;
        if (gate == null || d == null) {
            return d;
        }
        // review-2026-06-15f Rec 2 — DETERMINISTIC base-dependent-density prune. The LLM verdict varies
        // run-to-run (the same isPrime∧isHappy density topic was caveated once and rejected once in the
        // same run, leaking past the verdict-keyed prune), so this predicate-name check prunes it
        // reliably and without an LLM call. Same flag as the verdict-based prune; OFF = no prune.
        if (prunePosednessRepresentationDependent
                && WellPosednessGate.isBaseDependentDensityTopic(d.predicate(), d.metric())) {
            log.info("research: PRUNED base-dependent density topic (deterministic — base-10 artifact, "
                    + "no representation-independent density) — predicate='{}' metric='{}'",
                    d.predicate(), d.metric());
            return null;   // skip this cycle; the author re-draws next tick
        }
        // 2026-07-04 (run-19 rec 2) — DETERMINISTIC disjoint-composite-density prune: an AND density
        // over a provably-disjoint pair is vacuously 0 (a theorem the papers keep re-proving) and an
        // OR density is the exact component sum (zero overlap, no interaction to test). 15 papers
        // across 11 batches, novelty 0.07–0.21 every time. Same flag as the density prunes above.
        if (prunePosednessRepresentationDependent
                && WellPosednessGate.isDisjointCompositeDensityTopic(d.predicate(), d.metric())) {
            log.info("research: PRUNED disjoint-composite density topic (deterministic — conjunction "
                            + "is vacuously empty / disjunction is the exact component sum) — "
                            + "predicate='{}' metric='{}'",
                    d.predicate(), d.metric());
            return null;   // skip this cycle; the author re-draws next tick
        }
        // Run-35 rec 1 (user-approved) — known-constant density topics get REDIRECTED to
        // second-order framing instead of pruned: the topic survives, but the paper's target
        // becomes the approach RATE to the known constant (the constant itself is a rediscovery;
        // P-825/P-826). Deterministic, no LLM call; the LLM verdict below is skipped because the
        // redirect directive already carries the sharper framing.
        if (knownDensityRedirectEnabled) {
            java.util.Optional<String> known =
                    WellPosednessGate.knownDensityOf(d.predicate(), d.metric());
            if (known.isPresent()) {
                log.info("research: known-density topic REDIRECTED to second-order — "
                        + "predicate='{}' known='{}'", d.predicate(), known.get());
                String directive = "KNOWN-CONSTANT NOTICE: the leading-order density of "
                        + d.predicate() + " is a KNOWN constant (" + known.get() + ") — "
                        + "re-measuring it is a rediscovery and scores near-zero novelty. This "
                        + "paper MUST target second-order behavior instead: subtract the known "
                        + "constant as the baseline and measure the DEVIATION — its sign, its "
                        + "size, and the rate at which it shrinks (or fails to) across a scale "
                        + "ladder. The deviation trajectory is the finding; the constant is not.";
                String redirected = directive
                        + (d.hypothesis() != null && !d.hypothesis().isBlank()
                                ? "\n\n" + d.hypothesis() : "");
                return new DynamicProblem(d.id(), d.description(), d.rangeStart(), d.rangeEnd(),
                        d.predicate(), d.metric(), d.baseline(), redirected);
            }
        }
        try {
            String topic = "Quantity/predicate: " + d.predicate() + "; metric: " + d.metric()
                    + (d.hypothesis() != null && !d.hypothesis().isBlank()
                            ? "; stated hypothesis: " + d.hypothesis() : "");
            WellPosednessGate.Verdict verdict = gate.assess(topic);
            // review-2026-06-15a Rec 1 — PRUNE the recurring base-10 density topic (representation-
            // dependent: no representation-independent density). Flag-gated exception to caveat-first;
            // logged as a boundary event (inform-don't-suppress: recorded, not silently dropped).
            if (prunePosednessRepresentationDependent
                    && WellPosednessGate.isRepresentationDependent(verdict)) {
                log.info("research: PRUNED representation-dependent topic (base-10 artifact, no "
                                + "representation-independent density) — predicate='{}' metric='{}' ({})",
                        d.predicate(), d.metric(),
                        verdict.explanation() == null || verdict.explanation().isBlank()
                                ? "representation_dependent" : verdict.explanation());
                return null;   // signal: skip this cycle's topic
            }
            String caveat = WellPosednessGate.caveatFor(verdict);
            if (caveat.isBlank()) {
                return d;
            }
            log.info("research: well-posedness caveat attached — predicate='{}' metric='{}'",
                    d.predicate(), d.metric());
            String newHyp = caveat
                    + (d.hypothesis() != null && !d.hypothesis().isBlank() ? "\n\n" + d.hypothesis() : "");
            return new DynamicProblem(d.id(), d.description(), d.rangeStart(), d.rangeEnd(),
                    d.predicate(), d.metric(), d.baseline(), newHyp);
        } catch (Exception e) {
            return d;   // never block the research cycle on the gate
        }
    }

    // ---- Dynamic cycle ----

    private void runDynamicCycle(UUID roomId, Researcher author, List<Researcher> pool,
                                 DynamicProblem directive, Random rng, long gameTick,
                                 boolean peerReviewEnabled, Context ctx) {
        // Lock the finite comparison design before the first census runs. The professor later
        // receives the same three ranges as required trace evidence; it cannot choose them after
        // seeing this initial result.
        PreRegistration predicateStudyPreReg = PredicateStudyPreRegistration.from(directive);
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
                    predicateStudyPreReg, dynClassification, null);

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
