package com.emergentterrain.research.conjecture;

import com.emergentterrain.repo.ConjectureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 23.1J runtime — periodically mines math-domain conjectures and
 * persists the surviving set. Gated on
 * {@code app.research.conjectureMiner.enabled} (default {@code false}) so
 * JVM startup is unaffected until the operator opts in. With the flag
 * off, the bean isn't created and the SPF sieve isn't allocated.
 *
 * <p>Each scheduled tick:
 * <ol>
 *   <li>Load existing live conjectures for the {@code math} domain.</li>
 *   <li>Run the engine over the canned set of (window size, offsets)
 *       triples (matches the Python prototype + parity test).</li>
 *   <li>Apply the knob-restatement filter on the {@code window_size} axis.</li>
 *   <li>Persist survivors with tightened {@code c} values; mark retired
 *       items in the DB with a sentinel {@code droppedByKnob} so they're
 *       not picked up by future runs but retain their witness audit trail.</li>
 * </ol>
 *
 * <p>The sieve is built once (in the constructor) and reused across
 * scheduled ticks. Default interval is 5 minutes — adjusts via
 * {@code app.research.conjectureMiner.intervalMs}.
 */
@Service
@ConditionalOnProperty(name = "app.research.conjectureMiner.enabled", havingValue = "true")
public class ConjectureMinerService implements ConjectureQueue {

    private static final Logger log = LoggerFactory.getLogger(ConjectureMinerService.class);

    /** Sentinel droppedByKnob value for counterexample-refuted conjectures (vs. real knob name for filter drops). */
    public static final String DROPPED_BY_COUNTEREXAMPLE = "_counterexample_";

    private static final String DOMAIN = "math";

    /**
     * 2026-06-10 — decision A. The failure-region floor (x): a bound a professor verified HELD here
     * (stamped {@code investigationVerifiedAt} by {@link ConjectureInvestigationWriteBackService})
     * is asymptotic — it is only ever claimed at x &gt;= this floor, so a counterexample BELOW it is
     * expected and must not retire the stamped row. Matches the write-back's {@code FAILURE_REGION_MIN_X}.
     */
    static final long FAILURE_REGION_FLOOR = 1_000_000L;

    /** Mining plan — matches the Python prototype's RUNS exactly. */
    private record Run(String name, int windowSize, int[] offsets) {}

    private static final List<Run> RUNS = List.of(
            new Run("W500_low",   500,    new int[]{100, 1_000, 5_000, 10_000, 50_000, 100_000}),
            new Run("W5k_mid",    5_000,  new int[]{5_000, 50_000, 100_000, 500_000}),
            new Run("W50k_high",  50_000, new int[]{50_000, 500_000, 1_000_000})
    );

    // Phase 30 Wave 4 component 3 — PRIVATE held-out windows for the gaming-rate validator.
    // Offsets are disclosed to NEITHER the fitter (never fed to ConjectureEngine) NOR the paper
    // author (disjoint from the verify_conjecture prompt's {100,1k,5k,10k,50k,100k,500k,1M}), so a
    // bound that holds here genuinely generalises rather than being tuned/cherry-picked. All ≤ NT_MAX_N.
    private static final List<Run> PRIVATE_RUNS = List.of(
            new Run("P1k_holdout",  1_000,  new int[]{250, 2_500, 25_000, 250_000, 750_000}),
            // 1.5M (window ends at 1.51M ≤ MAX_N=2M) is a deep-N stress holdout: scalars that decay
            // with N (e.g. prime_density) are weakest here, so a genuinely overfit bound whose RHS
            // slips out from under the LHS as N grows shows a 2nd breach here. (Measured: on the
            // current pool happy_density<=2*prime_density still HOLDS at 1.5M, margin≈-0.005 — its
            // lone 250k breach is a happy_density plateau, not monotone divergence — so the >=2-window
            // gate correctly keeps sparing it. The window stays as honest deep-N coverage, not tuned
            // to force a retirement.)
            new Run("P10k_holdout", 10_000, new int[]{15_000, 150_000, 600_000, 1_500_000})
    );

    private static final double KNOB_FILTER_THRESHOLD = 0.95;
    private static final int KNOB_FILTER_MIN_CONFIGS = 3;

    private final ConjectureRepository repo;
    private final ConjectureMapper mapper;
    private final OeisAnnotator oeisAnnotator;
    /**
     * Phase 28 §28.6 — number-theory-window calls go through the sidecar-only
     * delegate (the in-process MathConjectureSubstrate was removed). The miner
     * therefore requires {@code app.julia.conjecture.enabled=true} and a
     * reachable sidecar; {@code evaluateWindow} throws otherwise and the mining
     * pass aborts as a recoverable per-cycle error.
     */
    private final MathConjectureSubstrateDelegate substrate;

    /**
     * Phase 30 Wave 1 — when ON, structurally-trivial seeds are removed each
     * mine pass via {@link ConjectureTrivialityFilter}: window-geometry
     * tautologies are dropped and degenerate k=0 constant bounds are deduped
     * (rhs-agnostic) to one representative. Default OFF (flag-gated rollout);
     * with it OFF this service behaves exactly as before. See
     * phase-30-conjecture/IMPLEMENTATION-ORDER.md Wave 1.
     */
    private final boolean trivialityFilterEnabled;
    private final boolean incrementalReseedEnabled;
    private final boolean verifiedAnchorKnobExemptEnabled;
    private final boolean opennessPriorEnabled;
    private final boolean opennessSelectionEnabled;
    private final boolean opennessDiversityEnabled;
    private final boolean oscillatingTargetFimExemptEnabled;

    /**
     * Phase 30 Wave 1 — when ON, dimensional-mismatch seeds are dropped each
     * mine pass via {@link ConjectureDimensionFilter}: a [0,1] density bounded
     * by a harness window-magnitude function (k>0) is eventually vacuous.
     * Default OFF; independent of {@link #trivialityFilterEnabled} so the two
     * gates can be A/B'd separately.
     */
    private final boolean dimensionFilterEnabled;

    /**
     * Phase 30 Wave 0 — when ON, {@link #nextN}'s refuted (Tier-1) queue serves
     * least-recently-investigated first (via {@code lastInvestigatedAt}), so the
     * paper pipeline rotates through distinct conjectures instead of re-serving
     * the same most-recently-refuted one every cycle. Default OFF (unchanged
     * peek ordering). See phase-30-conjecture/IMPLEMENTATION-ORDER.md Wave 0.
     */
    private final boolean investigationRotationEnabled;

    /**
     * 2026-06-10 — decision A: when ON, a row stamped {@code investigationVerifiedAt} is spared from
     * empirical retirement on sub-failure-region evidence (a counterexample below
     * {@link #FAILURE_REGION_FLOOR}, or the stochastic-null / private-holdout / replication
     * heuristics). It remains subject to structural gates and to a genuine counterexample at
     * x &gt;= the floor. Default OFF. See {@link ConjectureInvestigationWriteBackService}.
     */
    private final boolean respectInvestigationVerified;

    /**
     * Phase 30 integration (observability) — when ON, each mine pass logs a
     * {@link SubstrateMetrics} snapshot over the live survivors (redundancy +
     * composition novelty potential). Read-only; the first integration step that
     * wires the substrate over the live pool without changing behavior. Default OFF.
     */
    private final boolean substrateMetricsEnabled;

    /**
     * Phase 30 integration step 2 — when ON, each mine pass persists up to
     * {@link #compositionPersistCap} novel 1-step compositions (W1-filtered,
     * deduped, not already present) as new live conjectures carrying
     * {@code derivedFrom} provenance, so the paper pipeline can investigate
     * derivable-but-non-trivial claims. They enter the empirical update loop on
     * the next pass like any mined bound. Default OFF; gated by the signal that
     * {@link #substrateMetricsEnabled} logs. No-op when OFF.
     */
    private final boolean compositionPersistEnabled;

    /**
     * Phase 30 integration step 2 — max novel compositions persisted per mine
     * pass (a small cap keeps corpus growth controlled and reviewable). Only
     * consulted when {@link #compositionPersistEnabled} is ON. {@code <= 0} =
     * uncapped (not recommended in the live pipeline).
     */
    private final int compositionPersistCap;

    /**
     * Phase 30 integration step 2.5 — when ON, derived (composed) conjectures are
     * exempt from the {@code window_size} knob-restatement filter. A cross-quantity
     * derived relation (e.g. {@code deficient_density <= 4*abundant_density + c})
     * has slack that trends monotonically with window_size as its constituents
     * converge, which the Spearman restatement heuristic mis-reads as "restates the
     * knob" and drops — culling every persisted composition within one pass. Derived
     * items still run through {@code update()} (genuine counterexamples retire them);
     * only the restatement heuristic is skipped. Safe because such relations already
     * cleared the W1 gates in {@code proposeNovel}. Default OFF. See
     * phase-30-conjecture/IMPLEMENTATION-ORDER.md step 2.5.
     */
    private final boolean compositionKnobExemptEnabled;
    // Phase 30 Wave 4 — generative stochastic null (flag-gated OFF; see ConjectureStochasticNull).
    private final boolean stochasticNullEnabled;
    private final int stochasticNullPermutations;
    private final double stochasticNullAlpha;
    private final double stochasticNullMinCv;
    // Phase 30 Wave 4 — shadow mode (log would-retire without retiring) for the W4 gates.
    private final boolean w4ShadowEnabled;
    // Phase 30 Wave 4 component 2 — multiple-comparisons correction over the null p-values.
    private final boolean multipleComparisonsEnabled;
    private final MultipleComparisonsCorrection.Method mccMethod;
    // Phase 30 Wave 4 component 3 — private held-out validator + gaming-rate metric (anti-HARKing).
    private final boolean privateValidatorEnabled;
    // Phase 30 Wave 4 component 4 — ensemble replication (K-fold CV stability) + stochastic-pass.
    private final boolean replicationValidatorEnabled;
    private final int replicationFolds;
    private final double replicationMinPassRate;
    // Phase 30 Wave 1 (finish) — unit/dimensional-consistency + FIM identifiability gates.
    private final boolean unitConsistencyEnabled;
    private final boolean identifiabilityEnabled;
    private final double identifiabilityMaxRelStdErr;
    // Phase 30 Wave 3 — clade-value × inverse-popularity selection for the live (Tier-2) queue.
    private final boolean cladeValueSelectionEnabled;
    // Phase 30 Wave 3 — sublinear generation budget (throttles composition vs validations).
    private final boolean sublinearBudgetEnabled;
    private final double sublinearBudgetAlpha;
    // Phase 30 Wave 5 — residual-structure discovery (30.2); observability hook, no persistence yet.
    private final boolean residualStructureEnabled;
    private final boolean twoTermEnabled;
    /** Cap on how many live bounds residual-structure discovery probes per pass (cost bound). */
    private static final int RESIDUAL_PROBE_CAP = 25;
    // Phase 30 Wave 5 — anomaly mining (30.1); observability hook, no persistence yet.
    private final boolean anomalyMiningEnabled;
    private final int anomalyMinConfirmed;
    /** Phase 30 Wave 7 — 30.6 promotion gate (needs the verifier; see setConjectureVerifier). */
    private final boolean anomalyPromotionEnabled;
    private final int promotionRefutationAttempts;
    /** Hold-regime probe anchor for the promotion gate (inside the miner's training regime). */
    static final long PROMOTION_HOLD_START = 100_000L;
    static final int PROMOTION_WINDOW_SIZE = 1_000;

    /**
     * 2026-06-06 — docket head-of-line-blocking fix: candidates the gate already
     * REFUTED this run, keyed {@code lhs|rhs} (strengthened from
     * {@code lhs|rhs|confirmedRuns} after the 03:48 session showed the
     * runs-suffixed key was ineffective for an oscillating candidate: it
     * re-confirms in its hold regime every pass, so its runs — and the key —
     * kept changing, and it interleaved back onto the docket forever). A gate
     * refutation is STRUCTURAL (fresh hold-regime windows violate the bound);
     * accumulating more confirmations cannot cure that, so the verdict stands
     * for the run. In-memory: a restart grants one fresh hearing, harmlessly
     * (verifier-only, deterministic, zero LLM spend).
     */
    private final java.util.Set<String> promotionRefuted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Optional — the promotion gate's re-verification surface. Set by the narration
     * layer (which owns the verifier bean); absent in unit tests and when conjecture
     * verification is unwired, in which case promotion is silently skipped.
     */
    private volatile ConjectureVerifier conjectureVerifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConjectureVerifier(ConjectureVerifier verifier) {
        this.conjectureVerifier = verifier;
    }
    // Phase 30 Wave 5 — vocabulary expansion (30.5 P3a admission); observability, no persistence yet.
    private final boolean vocabularyExpansionEnabled;
    private final int vocabularyExpansionDepth;
    private static final int VOCAB_CANDIDATE_CAP = 60;   // grammar candidates probed per pass
    private static final int VOCAB_TARGET_CAP = 3;       // top confirmed bounds used as MDL targets
    private static final int VOCAB_ADMIT_CAP = 8;        // hard ceiling on admitted scalars per pass
    private static final int VOCAB_NEW_CONJECTURE_CAP = 20;   // new conjectures seeded from admitted scalars/pass
    // Phase 30 Wave 6 — RAF-closure reporting (sim #1); observability over the derivation graph.
    private final boolean rafClosureEnabled;
    private static final int RAF_TARGET_CAP = 5;   // top confirmed findings to report iRAFs for

    /**
     * Phase 30 Wave 5 (30.5 P3b) — optional persistence for admitted derived scalars. Setter-injected
     * (so existing constructor call sites are unchanged); only touched when vocabulary expansion is on.
     */
    private com.emergentterrain.repo.DerivedScalarRepository derivedScalarRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDerivedScalarRepository(com.emergentterrain.repo.DerivedScalarRepository repo) {
        this.derivedScalarRepository = repo;
    }

    /**
     * Reconstruct the previously-admitted derived scalars: read the {@code derived_scalars} rows and
     * rebuild each {@link OperatorGrammar.Expr} by re-enumerating the (deterministic, non-recursive)
     * grammar and matching by render — no expression deserialiser needed. Rows whose name no longer
     * re-enumerates (vocabulary changed) are dropped. Empty when the feature/repo is off.
     */
    private List<DerivedScalar> loadActiveAdmitted() {
        if (!vocabularyExpansionEnabled || derivedScalarRepository == null) {
            return List.of();
        }
        List<DerivedScalarEntity> rows = derivedScalarRepository.findByDomain(DOMAIN);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, OperatorGrammar.Expr> byRender = new HashMap<>();
        for (OperatorGrammar.Expr e : OperatorGrammar.enumerate(
                MathInvariants.vocabulary(), vocabularyExpansionDepth)) {
            byRender.put(e.render(), e);
        }
        List<DerivedScalar> out = new ArrayList<>();
        for (DerivedScalarEntity row : rows) {
            OperatorGrammar.Expr e = byRender.get(row.name());
            if (e != null) {
                out.add(new DerivedScalar(row.name(), e, row.gainBits()));
            }
        }
        return out;
    }

    public ConjectureMinerService(
            ConjectureRepository repo,
            ConjectureMapper mapper,
            OeisAnnotator oeisAnnotator,
            MathConjectureSubstrateDelegate substrate,
            ConjectureMinerProperties props) {
        this.repo = repo;
        this.mapper = mapper;
        this.oeisAnnotator = oeisAnnotator;
        this.substrate = substrate;
        this.trivialityFilterEnabled = props.isTrivialityFilterEnabled();
        this.incrementalReseedEnabled = props.isIncrementalReseedEnabled();
        this.verifiedAnchorKnobExemptEnabled = props.isVerifiedAnchorKnobExemptEnabled();
        this.opennessPriorEnabled = props.isOpennessPriorEnabled();
        this.opennessSelectionEnabled = props.isOpennessSelectionEnabled();
        this.opennessDiversityEnabled = props.isOpennessDiversityEnabled();
        this.oscillatingTargetFimExemptEnabled = props.isOscillatingTargetFimExemptEnabled();
        this.dimensionFilterEnabled = props.isDimensionFilterEnabled();
        this.investigationRotationEnabled = props.isInvestigationRotationEnabled();
        this.respectInvestigationVerified = props.isRespectInvestigationVerified();
        this.substrateMetricsEnabled = props.isSubstrateMetricsEnabled();
        this.compositionPersistEnabled = props.isCompositionPersistEnabled();
        this.compositionPersistCap = props.getCompositionPersistCap();
        this.compositionKnobExemptEnabled = props.isCompositionKnobExemptEnabled();
        this.stochasticNullEnabled = props.isStochasticNullEnabled();
        this.stochasticNullPermutations = props.getStochasticNullPermutations();
        this.stochasticNullAlpha = props.getStochasticNullAlpha();
        this.stochasticNullMinCv = props.getStochasticNullMinCv();
        this.w4ShadowEnabled = props.isW4ShadowEnabled();
        this.multipleComparisonsEnabled = props.isMultipleComparisonsEnabled();
        this.mccMethod = MultipleComparisonsCorrection.Method.parse(props.getMultipleComparisonsMethod());
        this.privateValidatorEnabled = props.isPrivateValidatorEnabled();
        this.replicationValidatorEnabled = props.isReplicationValidatorEnabled();
        this.replicationFolds = props.getReplicationFolds();
        this.replicationMinPassRate = props.getReplicationMinPassRate();
        this.unitConsistencyEnabled = props.isUnitConsistencyEnabled();
        this.identifiabilityEnabled = props.isIdentifiabilityEnabled();
        this.identifiabilityMaxRelStdErr = props.getIdentifiabilityMaxRelStdErr();
        this.cladeValueSelectionEnabled = props.isCladeValueSelectionEnabled();
        this.sublinearBudgetEnabled = props.isSublinearBudgetEnabled();
        this.sublinearBudgetAlpha = props.getSublinearBudgetAlpha();
        this.residualStructureEnabled = props.isResidualStructureEnabled();
        this.twoTermEnabled = props.isTwoTermEnabled();
        this.anomalyMiningEnabled = props.isAnomalyMiningEnabled();
        this.anomalyMinConfirmed = props.getAnomalyMinConfirmed();
        this.anomalyPromotionEnabled = props.isAnomalyPromotionEnabled();
        this.promotionRefutationAttempts = props.getPromotionRefutationAttempts();
        this.vocabularyExpansionEnabled = props.isVocabularyExpansionEnabled();
        this.vocabularyExpansionDepth = props.getVocabularyExpansionDepth();
        this.rafClosureEnabled = props.isRafClosureEnabled();
        log.info("conjecture miner ready — substrate via sidecar delegate ({} OEIS catalog entries), "
                + "trivialityFilter={} dimensionFilter={} investigationRotation={} substrateMetrics={} "
                + "compositionPersist={}{} compositionKnobExempt={} stochasticNull={} privateValidator={} replication={} "
                + "unitConsistency={} identifiability={} cladeValueSelection={} sublinearBudget={} residualStructure={} anomalyMining={} vocabularyExpansion={} rafClosure={}",
                OeisCatalog.size(), trivialityFilterEnabled ? "ON" : "OFF",
                dimensionFilterEnabled ? "ON" : "OFF", investigationRotationEnabled ? "ON" : "OFF",
                substrateMetricsEnabled ? "ON" : "OFF",
                compositionPersistEnabled ? "ON" : "OFF",
                compositionPersistEnabled ? " (cap=" + compositionPersistCap + ")" : "",
                compositionKnobExemptEnabled ? "ON" : "OFF",
                stochasticNullEnabled
                        ? "ON (alpha=" + stochasticNullAlpha + ", mcc="
                                + (multipleComparisonsEnabled ? mccMethod.name().toLowerCase() : "off") + ")"
                        : (w4ShadowEnabled ? "SHADOW" : "OFF"),
                privateValidatorEnabled ? "ON" : "OFF",
                replicationValidatorEnabled
                        ? "ON (" + replicationFolds + "-fold, minPass=" + replicationMinPassRate + ")"
                        : "OFF",
                unitConsistencyEnabled ? "ON" : "OFF",
                identifiabilityEnabled ? "ON (maxRelSE=" + identifiabilityMaxRelStdErr + ")" : "OFF",
                cladeValueSelectionEnabled ? "ON" : "OFF",
                sublinearBudgetEnabled ? "ON (alpha=" + sublinearBudgetAlpha + ")" : "OFF",
                residualStructureEnabled ? "ON" : "OFF",
                anomalyMiningEnabled ? "ON (minConfirmed=" + anomalyMinConfirmed + ")" : "OFF",
                vocabularyExpansionEnabled ? "ON (depth=" + vocabularyExpansionDepth + ")" : "OFF",
                rafClosureEnabled ? "ON" : "OFF");
        if (respectInvestigationVerified) {
            log.info("conjecture miner — decision A ON: investigation-verified rows spared from "
                    + "empirical retirement below the failure-region floor (x={})", FAILURE_REGION_FLOOR);
        }
    }

    /**
     * Mine one pass. Idempotent against the DB: re-running with the same
     * windows just re-tightens c values (asymptotically converges to the
     * worst observed margin across all confirming samples).
     */
    @Scheduled(fixedDelayString = "${app.research.conjectureMiner.intervalMs:300000}")
    public synchronized void mine() {
        long t0 = System.currentTimeMillis();
        List<Conjecture> existing = repo.findByDomain(DOMAIN);
        Map<String, Conjecture> byKey = new HashMap<>(existing.size() * 2);
        for (Conjecture e : existing) {
            byKey.put(keyOf(e.lhs(), e.rhs()), e);
        }

        // Phase 30 Wave 5 (30.5 P3b) — the previously-admitted derived scalars, reconstructed from
        // the derived_scalars table. Empty unless vocabulary expansion is on (so materialize/seed are
        // byte-identical when off). These are extracted into every window below, which is what makes
        // conjectures referencing a derived scalar re-evaluable across passes.
        List<DerivedScalar> activeAdmitted = loadActiveAdmitted();

        // Only re-process live ones (droppedByKnob is null) — retired
        // conjectures stay in the DB for their witness audit trail but
        // shouldn't re-enter the seed/update loop.
        List<Inequality> store = new ArrayList<>();
        // Phase 30 step 2.5 — track which live keys are derived (composed), so the
        // knob filter can exempt them (their window-correlated slack is expected,
        // not a restatement). Empty unless compositions have been persisted.
        java.util.Set<String> derivedKeys = new java.util.HashSet<>();
        for (Conjecture e : existing) {
            // Lever D — a non-BOUND row (CONSTANT/OSCILLATION measured-invariant survivor) is a verified
            // MEASUREMENT, not an inequality. It stays live + selectable in the DB but must NOT enter the
            // seed/update loop: a one-sided counterexample test would refute a converging/oscillating
            // quantity (e.g. a CONSTANT row's lhs sits just above its limit on some windows).
            if (e.droppedByKnob() == null && e.isBound()) {
                store.add(mapper.toInequality(e));
                if (e.derivedFrom() != null) {
                    derivedKeys.add(keyOf(e.lhs(), e.rhs()));
                }
            }
        }
        log.debug("loaded {} existing conjectures ({} live, {} derived)",
                existing.size(), store.size(), derivedKeys.size());

        Map<String, Double> knobValues = new LinkedHashMap<>();
        int totalConfirmed = 0;
        int totalRetiredByCounterexample = 0;
        // Phase 30 Wave 4 — accumulate every window sample so the stochastic-null and replication
        // gates can work across the full sample set. Only collected when one of those gates is on
        // (otherwise it stays empty and costs nothing).
        boolean collectSamples = stochasticNullEnabled || replicationValidatorEnabled
                || identifiabilityEnabled || residualStructureEnabled || vocabularyExpansionEnabled
                || w4ShadowEnabled;   // shadow needs the samples the W4 gates run over
        List<Map<String, Double>> allSamples =
                collectSamples ? new ArrayList<>() : List.of();

        boolean firstPass = true;
        for (Run run : RUNS) {
            List<Map<String, Double>> samples = materialize(run, activeAdmitted);
            String configHash = "W" + run.windowSize();
            knobValues.put(configHash, (double) run.windowSize());
            if (collectSamples) {
                allSamples.addAll(samples);
            }

            boolean storeWasEmpty = store.isEmpty();
            if (storeWasEmpty) {
                store = ConjectureEngine.seed(samples, MathInvariants.vocabulary(activeAdmitted));
                log.debug("seeded {} candidate inequalities from run {}", store.size(), run.name());
            } else {
                ConjectureEngine.UpdateResult ur = ConjectureEngine.update(
                        store, run.name(), configHash, samples);
                totalConfirmed += ur.confirmed();
                // Decision A: an investigation-verified bound is asymptotic — refuted below the
                // failure-region floor is expected, not a refutation of what the professor claimed.
                // Spare those (re-add to store so in-region runs/gates still re-test them); a
                // counterexample AT x >= the floor still retires normally.
                List<Inequality> toRetire = ur.retiredItems();
                if (respectInvestigationVerified && !toRetire.isEmpty()) {
                    List<Inequality> spared = new ArrayList<>();
                    toRetire = sparingSubRegionVerified(toRetire, byKey, spared);
                    store.addAll(spared);
                }
                totalRetiredByCounterexample += toRetire.size();   // spared rows are not retirements
                persistRetired(toRetire, byKey, DROPPED_BY_COUNTEREXAMPLE);
            }

            // 2026-06-14 — surgical incremental re-seed (flag-gated OFF). The full seed above only
            // fires on an EMPTY pool, so a base-vocabulary addition (e.g. wigert_bound) would stay
            // dormant in a populated pool until a destructive wipe. On the FIRST pass over a NON-empty
            // pool, inject the genuinely-NEW vocabulary (lhs,rhs) pairs — those absent from the pool
            // entirely (live AND retired = byKey.keySet()) — seeded from this run's samples like the
            // empty-pool path, so the held-out runs below update-test them. Skipped when the pool was
            // empty (the seed already enumerated every pair). New-only: never re-floods retired pairs,
            // so paper-type mix (paperShare-gated) and volume are unchanged — it adds a handful of
            // high-value candidates and lets the downstream filters judge them like any seed.
            if (incrementalReseedEnabled && firstPass && !storeWasEmpty) {
                List<Inequality> newPairs = newVocabularyPairSeeds(
                        samples, MathInvariants.vocabulary(activeAdmitted), byKey.keySet(), store);
                if (!newPairs.isEmpty()) {
                    store.addAll(newPairs);
                    Inequality ex = newPairs.get(0);
                    log.info("incremental re-seed — {} new vocabulary pair(s) seeded into the live pool "
                            + "(new-only, e.g. {} <= k*{} + c)", newPairs.size(), ex.lhs(), ex.rhs());
                }
            }
            firstPass = false;
        }

        // Phase 30 step 2.5 — derived (composed) conjectures are exempt from the
        // window_size knob filter when the flag is on (no-op otherwise). See
        // knobFilterExempting.
        // 2026-06-14 (review-c Rec 1) — also exempt investigation-verified GROWTH ANCHORS (a bound
        // whose rhs is a window-geometry scalar): its slack is window-correlated by construction, so
        // the restatement heuristic was retiring verified survivors (the 0.649 Cramér bound). Built
        // from `existing` (verified status persists across the write-back re-tighten), gated by flag.
        java.util.Set<String> verifiedAnchorKeys = new java.util.HashSet<>();
        if (verifiedAnchorKnobExemptEnabled) {
            for (Conjecture e : existing) {
                if (e.investigationVerifiedAt() != null && e.isBound()
                        && ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS.contains(e.rhs())) {
                    verifiedAnchorKeys.add(keyOf(e.lhs(), e.rhs()));
                }
            }
        }
        ConjectureEngine.FilterResult fr = knobFilterExempting(
                store, derivedKeys, compositionKnobExemptEnabled, verifiedAnchorKeys,
                List.of(new ConjectureEngine.KnobAxis("window_size", knobValues)),
                KNOB_FILTER_THRESHOLD,
                KNOB_FILTER_MIN_CONFIGS);
        for (Inequality dropped : fr.droppedItems()) {
            persistRetired(List.of(dropped), byKey, dropped.droppedByKnob());
        }

        // Phase 30 Wave 1 — structural triviality + dedup (flag-gated OFF). Drops
        // window-geometry tautologies and collapses degenerate k=0 constant bounds
        // (rhs-agnostic) to one representative. No-op when the flag is off.
        int trivialOrRedundant = 0;
        if (trivialityFilterEnabled) {
            ConjectureTrivialityFilter.Reduction red = ConjectureTrivialityFilter.reduce(store);
            for (ConjectureTrivialityFilter.Removed rm : red.removed()) {
                persistRetired(List.of(rm.inequality()), byKey, rm.reason());
            }
            trivialOrRedundant = red.removed().size();
            store = red.kept();
        }

        // Phase 30 G1 (open-behavior-generation-activation.md) — openness prior. Drop SLOPED affine
        // bounds on settled-leading-order objects (mean_omega/mean_Omega ~ ln ln x, mean_divisor_count
        // ~ ln x, the densities) whose rhs is a flat feature — the trivial-refutation class that
        // dominates the corpus. Keeps k≈0 constant bounds (the real density-limit claim) and
        // growth-anchor bounds (rhs a window-geometry scalar), so settled LHS survive ONLY as
        // growth-anchor / non-affine-frame topics. No-op when the flag is off. The professor can still
        // run a flat verify_conjecture to establish the binding region — this only shapes which TOPICS
        // are seeded, not what investigation can verify.
        int opennessDropped = 0;
        if (opennessPriorEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                if (ScalarOpenness.isTrivialAffineOnSettled(ineq)) {
                    persistRetired(List.of(ineq), byKey, "openness:trivial_affine_on_settled");
                    opennessDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
        }

        // Phase 30 Wave 1 — dimensional-mismatch drop (flag-gated OFF, independent
        // of the triviality flag). Drops density <= k*window_magnitude (k>0) seeds,
        // which are eventually vacuous. No-op when the flag is off.
        int dimensionDropped = 0;
        if (dimensionFilterEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                if (ineq.isTwoTerm()) { kept.add(ineq); continue; }   // G2 — one-rhs filter doesn't apply
                java.util.Optional<String> r = ConjectureDimensionFilter.dropReason(ineq);
                if (r.isPresent()) {
                    persistRetired(List.of(ineq), byKey, "dimension:" + r.get());
                    dimensionDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
        }

        // Phase 30 Wave 1 (finish) — unit/dimensional-consistency drop (flag-gated OFF). Generalises
        // the density check to all O(1)-bounded number-theoretic scalars bounded by a harness
        // window-magnitude (k>0) — a unit mismatch that's eventually vacuous. No-op when off.
        int unitDropped = 0;
        if (unitConsistencyEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                if (ineq.isTwoTerm()) { kept.add(ineq); continue; }   // G2 — one-rhs filter doesn't apply
                java.util.Optional<String> r = ConjectureUnitConsistencyFilter.dropReason(ineq);
                if (r.isPresent()) {
                    persistRetired(List.of(ineq), byKey, "unit:" + r.get());
                    unitDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
        }

        // Phase 30 Wave 1 (finish) — FIM identifiability drop (flag-gated OFF). For a bound that
        // claims a slope (k≠0), drops it when the OLS slope's relative standard error shows the data
        // doesn't pin the slope (consistent with k=0) — an over-parameterised "constant the data
        // doesn't pin". Needs the accumulated samples. No-op when off.
        int fimDropped = 0;
        if (identifiabilityEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                // G2 — the FIM slope check is one-rhs; a two-term bound's slope is jointly determined,
                // and it already passed the residual-null gate. Exempt it.
                if (ineq.isTwoTerm()) { kept.add(ineq); continue; }
                // review-2026-06-15e Rec 2 — an OSCILLATING open target (dirichlet_error = Δ(x)/x^{1/4})
                // has an unpinned affine slope BY NATURE; FIM was retiring every one of its seeds,
                // leaving the open Dirichlet target unreachable through the queue. Exempt it (flag-gated)
                // so a seed stays live + servable; when served, the professor pivots to verify_oscillation
                // (the right frame, with the c35f90e no-scale_rhs fix). OFF = prior behaviour.
                if (oscillatingTargetFimExemptEnabled && ScalarOpenness.isOscillatingTarget(ineq.lhs)) {
                    kept.add(ineq);
                    continue;
                }
                ConjectureIdentifiabilityFilter.Result ir = ConjectureIdentifiabilityFilter.evaluate(
                        allSamples, ineq.lhs, ineq.rhs, ineq.k, identifiabilityMaxRelStdErr);
                if (ir.flagged()) {
                    persistRetired(List.of(ineq), byKey, "fim:" + ir.reason());
                    fimDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
        }

        // Phase 30 Wave 4 — generative stochastic null (flag-gated OFF). Permutation-tests
        // the lhs↔rhs association across all accumulated samples; a bound that doesn't beat
        // the no-effect null, or whose rhs is near-constant, is a marginal-range artifact,
        // not a real constraint — retire it. Two-phase so the optional multiple-comparisons
        // correction (W4 component 2) can judge the whole family of p-values together rather
        // than each bound at a raw threshold. No-op when the flag is off.
        int nullDropped = 0;
        boolean nullShadow = !stochasticNullEnabled && w4ShadowEnabled;
        if (stochasticNullEnabled || nullShadow) {
            List<Inequality> kept = new ArrayList<>(store.size());
            List<Inequality> testable = new ArrayList<>();   // got a usable p-value
            List<Double> testablePs = new ArrayList<>();
            for (Inequality ineq : store) {
                // Deterministic per-conjecture seed so the verdict is reproducible run-to-run.
                long seed = keyOf(ineq.lhs, ineq.rhs).hashCode();
                ConjectureStochasticNull.Result nr = ConjectureStochasticNull.evaluate(
                        allSamples, ineq.lhs, ineq.rhs, stochasticNullPermutations,
                        stochasticNullAlpha, stochasticNullMinCv, seed);
                switch (nr.reason()) {
                    case "rhs_near_constant_cv" -> {     // degenerate, not a p-value test
                        if (retireOrShadow(nullShadow, ineq, byKey, String.format(
                                "stochastic_null:rhs_near_constant_cv_%.4f", nr.rhsCv()))) {
                            kept.add(ineq);
                        } else {
                            nullDropped++;
                        }
                    }
                    case "insufficient_samples" -> kept.add(ineq);   // no power → keep
                    default -> {
                        testable.add(ineq);
                        testablePs.add(nr.pValue());
                    }
                }
            }
            // Phase 2 — keep iff the no-effect H0 is rejected. With MCC on, "rejected" is at
            // the corrected family level (Bonferroni/BH over the surviving denominator);
            // otherwise it's the raw per-test threshold p ≤ alpha.
            boolean[] keepIt;
            String mccReason;
            if (multipleComparisonsEnabled && !testable.isEmpty()) {
                double[] ps = new double[testablePs.size()];
                for (int i = 0; i < ps.length; i++) {
                    ps[i] = testablePs.get(i);
                }
                keepIt = MultipleComparisonsCorrection.reject(ps, mccMethod, stochasticNullAlpha);
                mccReason = "stochastic_null:mcc_" + mccMethod.name().toLowerCase();
            } else {
                keepIt = new boolean[testable.size()];
                for (int i = 0; i < keepIt.length; i++) {
                    keepIt[i] = testablePs.get(i) <= stochasticNullAlpha;
                }
                mccReason = "stochastic_null:null_not_beaten";
            }
            for (int i = 0; i < testable.size(); i++) {
                if (keepIt[i]) {
                    kept.add(testable.get(i));
                    continue;
                }
                // Embed the verdict-time evidence (per-conjecture p, and the family size the
                // BH/Bonferroni threshold was computed over) so the persisted reason is a
                // self-contained audit witness — distinguishes "clearly null" (p >> alpha)
                // from "passed raw, killed by the family correction" (p <= alpha), the class
                // the shadow A/B could not observe. Same precedent as the private validator's
                // windows/worst-margin suffix.
                String reason = String.format("%s_p%.4f", mccReason, testablePs.get(i))
                        + (multipleComparisonsEnabled ? "_m" + testable.size() : "");
                if (retireOrShadow(nullShadow, testable.get(i), byKey, reason)) {
                    kept.add(testable.get(i));
                } else {
                    nullDropped++;
                }
            }
            store = kept;
        }

        // Phase 30 Wave 4 component 3 — private held-out validator + gaming-rate metric
        // (flag-gated OFF). Evaluate each surviving bound on PRIVATE windows the fitter never
        // saw and the author can't name; a bound that holds on the public runs but fails here
        // "passed public, failed private" — overfit/gamed — is retired. The gaming rate
        // (failed-private / tested) is logged as the headline anti-HARKing signal. No-op off.
        int gamedDropped = 0;
        boolean privateShadow = !privateValidatorEnabled && w4ShadowEnabled;
        if (privateValidatorEnabled || privateShadow) {
            List<Map<String, Double>> privateSamples = new ArrayList<>();
            for (Run run : PRIVATE_RUNS) {
                privateSamples.addAll(materialize(run, activeAdmitted));
            }
            List<Inequality> kept = new ArrayList<>(store.size());
            int tested = 0;
            for (Inequality ineq : store) {
                PrivateHoldoutValidator.Verdict v = PrivateHoldoutValidator.evaluate(
                        ineq.lhs, ineq.rhs, ineq.k, ineq.c, privateSamples, ConjectureEngine.TOL);
                if (v.samplesEvaluated() == 0) {
                    kept.add(ineq);   // no private evidence (scalar absent) → don't accuse
                    continue;
                }
                tested++;
                if (v.failedHoldout()) {
                    String reason = String.format("private_validator:failed_holdout_windows_%d_worst_%.4f",
                            v.violatingWindows(), v.worstMargin());
                    if (retireOrShadow(privateShadow, ineq, byKey, reason)) {
                        kept.add(ineq);
                    } else {
                        gamedDropped++;
                    }
                } else {
                    kept.add(ineq);
                }
            }
            log.info("private validator{} — tested={} gamed (passed public, failed private)={} gamingRate={}",
                    privateShadow ? " [SHADOW]" : "", tested, gamedDropped, String.format("%.3f",
                            PrivateHoldoutValidator.gamingRate(tested, gamedDropped)));
            store = kept;
        }

        // Phase 30 Wave 4 component 4 — ensemble replication (K-fold CV stability, flag-gated OFF).
        // Refits (k,c) per fold and tests out-of-fold; a bound whose fit doesn't generalise across
        // folds (stochastic-pass rate below the minimum) is replication-fragile and retired. The
        // pass rate is the quantified replication score. No-op when the flag is off.
        int replicationDropped = 0;
        boolean replicationShadow = !replicationValidatorEnabled && w4ShadowEnabled;
        if (replicationValidatorEnabled || replicationShadow) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                long seed = keyOf(ineq.lhs, ineq.rhs).hashCode();
                ConjectureReplicationValidator.Result rr = ConjectureReplicationValidator.evaluate(
                        allSamples, ineq.lhs, ineq.rhs, ineq.k, replicationFolds, ConjectureEngine.TOL,
                        replicationMinPassRate, seed);
                if (rr.flagged() && !retireOrShadow(replicationShadow, ineq, byKey,
                        "replication:fragile_passRate_" + String.format("%.2f", rr.passRate()))) {
                    replicationDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
        }

        // Phase 30 Wave 5 — residual-structure discovery (30.2, flag-gated OFF). For the most-
        // confirmed live bounds, probe whether the rhs-residual carries structure another scalar
        // explains beyond the linear null (ResidualStructureMiner). Observability only for now — a
        // discovered residual relation is a Stage-1 generated candidate, but the 2-term conjecture
        // schema can't persist lhs~rhs+z yet, so we log candidates rather than create entities.
        if (residualStructureEnabled) {
            List<String> vocab = MathInvariants.vocabulary();
            int probed = 0;
            int discovered = 0;
            int promoted = 0;
            // G2 Stage 3 — existing two-term (lhs|rhs|rhs2) keys, so a re-discovered residual relation
            // is not re-persisted every pass.
            java.util.Set<String> existingTwoTermKeys = new java.util.HashSet<>();
            for (Conjecture e : existing) {
                if (e.rhs2() != null) {
                    existingTwoTermKeys.add(e.lhs() + "|" + e.rhs() + "|" + e.rhs2());
                }
            }
            List<Inequality> ranked = store.stream()
                    .filter(i -> !i.isTwoTerm())   // probe residuals of one-term bounds only
                    .sorted(Comparator.comparingInt((Inequality i) -> -i.confirmedRuns()))
                    .limit(RESIDUAL_PROBE_CAP)
                    .toList();
            for (Inequality ineq : ranked) {
                probed++;
                long seed = keyOf(ineq.lhs, ineq.rhs).hashCode();
                java.util.Optional<ResidualStructureMiner.Candidate> cand =
                        ResidualStructureMiner.mine(allSamples, ineq.lhs, ineq.rhs, vocab,
                                stochasticNullPermutations, stochasticNullAlpha, seed);
                if (cand.isPresent()) {
                    discovered++;
                    ResidualStructureMiner.Candidate c = cand.get();
                    log.info("residual-structure candidate — {} <= k*{} leaves residual explained by {} "
                            + "(|rho|={}, p={})", ineq.lhs, ineq.rhs, c.explainer(),
                            String.format("%.3f", c.residualAbsRho()), String.format("%.4f", c.pValue()));
                    // G2 Stage 3 — PROMOTE: fit (k1,k2,c) and persist a two-term conjecture (flag-gated).
                    // A relation absent from either single-term parent; the next pass loads + verifies it.
                    if (twoTermEnabled) {
                        String key3 = ineq.lhs + "|" + ineq.rhs + "|" + c.explainer();
                        Inequality two = fitTwoTerm(allSamples, ineq.lhs, ineq.rhs, c.explainer());
                        if (two != null && existingTwoTermKeys.add(key3)) {
                            repo.save(mapper.toEntity(two, DOMAIN));
                            promoted++;
                            log.info("residual-structure PROMOTED two-term conjecture — {} <= {}*{} + "
                                            + "{}*{} + {}", two.lhs(), trimD(two.k()), two.rhs(),
                                    trimD(two.k2()), two.rhs2(), trimD(two.c()));
                        }
                    }
                }
            }
            log.info("residual-structure discovery — probed {} bound(s), {} with residual structure "
                    + "beating the null, {} promoted (two-term)", probed, discovered, promoted);
        }

        // Phase 30 Wave 5 — anomaly mining (30.1, flag-gated OFF). Surface counterexample-refuted
        // conjectures that were a real pattern first (confirmed ≥ minConfirmed) and broke
        // surprisingly hard, surviving a multiple-comparisons correction over the candidate family.
        // Observability only — an UNEXPLAINED_OBSERVATION paper from an anomaly is Wave 7.
        if (anomalyMiningEnabled) {
            List<Inequality> refutedPool = new ArrayList<>();
            for (Conjecture c : existing) {
                if (DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob())) {
                    refutedPool.add(mapper.toInequality(c));
                }
            }
            List<AnomalyMiner.Anomaly> anomalies = AnomalyMiner.mine(
                    refutedPool, anomalyMinConfirmed, ConjectureEngine.TOL, mccMethod, stochasticNullAlpha);
            for (AnomalyMiner.Anomaly a : anomalies) {
                log.info("anomaly candidate — {} <= k*{} held {} runs then broke by margin {} (surprise p={})",
                        a.lhs(), a.rhs(), a.confirmedRuns(), String.format("%.4f", a.worstMargin()),
                        String.format("%.2e", a.pValue()));
            }
            log.info("anomaly mining — {} refuted candidate(s) examined, {} surviving anomalies past MCC",
                    refutedPool.size(), anomalies.size());

            // Phase 30 Wave 7 — 30.6 adversarial promotion. The TOP MCC survivor (one per
            // pass — promotion is rare by construction) that has no OEIS match and no prior
            // promotion stamp is re-verified at fresh windows in BOTH regimes, each attempt
            // trying to refute the anomaly's regime-dependence. Survivors are stamped
            // (targeted JPQL — updatable=false column) and the paper pipeline serves them
            // as UNEXPLAINED_OBSERVATION investigations. Skipped silently when the verifier
            // isn't wired (unit tests / verification off).
            ConjectureVerifier verifier = conjectureVerifier;
            if (anomalyPromotionEnabled && verifier != null && !anomalies.isEmpty()) {
                // 2026-06-06 — walk DOWN the MCC ranking past already-refuted (at current
                // evidence) / already-promoted / OEIS-matched candidates: one fresh hearing
                // per pass instead of re-hearing the blocked top forever.
                for (AnomalyMiner.Anomaly cand : anomalies) {
                    String key = keyOf(cand.lhs(), cand.rhs());
                    Conjecture entity = byKey.get(key);
                    if (entity == null || entity.promotedAnomalyAt() != null) continue;
                    boolean oeisMatch = entity.oeisMatchJson() != null
                            && !entity.oeisMatchJson().isEmpty()
                            && entity.oeisMatchJson().contains("\"has_match\":true");
                    if (oeisMatch) continue;
                    if (promotionRefuted.contains(key)) continue;   // structural refutation stands

                    Inequality ineq = mapper.toInequality(entity);
                    AnomalyPromotionGate.Verdict v = AnomalyPromotionGate.replicate(
                            ineq, verifier::verify,
                            PROMOTION_HOLD_START, PROMOTION_WINDOW_SIZE,
                            promotionRefutationAttempts);
                    if (v.survived()) {
                        repo.stampPromotedAnomaly(entity.id(), Instant.now());
                        log.info("ANOMALY PROMOTED — {} <= {}*{} + {} survived {} adversarial "
                                        + "refutation attempts (held {} runs, no OEIS match); queued "
                                        + "for UNEXPLAINED_OBSERVATION investigation",
                                cand.lhs(), entity.k(), cand.rhs(), entity.c(),
                                v.attempts(), cand.confirmedRuns());
                    } else {
                        promotionRefuted.add(key);
                        log.info("anomaly promotion gate — {} <= k*{} REFUTED ({})",
                                cand.lhs(), cand.rhs(), v.reason());
                    }
                    break;   // one hearing per pass, as before
                }
            }
        }

        // Phase 30 Wave 5 — vocabulary expansion (30.5 P3, flag-gated OFF). Enumerate grammar
        // candidates (excluding the already-admitted), admit those that earn their MDL keep against the
        // top confirmed bounds' residuals (held-out-confirmed, beats existing + the null), capped by
        // the sublinear budget; persist the newly-admitted scalars (P3b) and seed new conjecture pairs
        // that touch them so they enter the empirical loop (re-evaluable next pass via materialize).
        if (vocabularyExpansionEnabled && !allSamples.isEmpty()) {
            List<String> vocab = MathInvariants.vocabulary(activeAdmitted);   // existing = base + admitted
            java.util.Set<String> known = new java.util.HashSet<>(vocab);
            List<OperatorGrammar.Expr> candidates = OperatorGrammar
                    .enumerate(MathInvariants.vocabulary(), vocabularyExpansionDepth).stream()
                    .filter(e -> !known.contains(e.render()))   // don't re-admit existing scalars
                    .limit(VOCAB_CANDIDATE_CAP)
                    .toList();
            // Targets: live survivors first. When the live pool is thin — the post-W4
            // steady state is 0 survivors, which made this whole block a silent no-op
            // (empty targets AND nValidations=0 ⇒ sublinear budget 0) — fall back to
            // the anomaly stratum: counterexample-retired bounds that held ≥
            // anomalyMinConfirmed runs first. Those are the relations with enough
            // held-out evidence to carry residual structure worth explaining; a
            // refuted BOUND still has a fittable lhs~rhs relation, which is all the
            // MDL residual test consumes. (2026-06-05, unblocking the 30.5 A/B.)
            List<VocabularyExpansion.Target> targets = new ArrayList<>(store.stream()
                    .sorted(Comparator.comparingInt((Inequality i) -> -i.confirmedRuns()))
                    .limit(VOCAB_TARGET_CAP)
                    .map(i -> new VocabularyExpansion.Target(i.lhs, i.rhs))
                    .toList());
            long nValidations = 0;
            for (Inequality ineq : store) {
                nValidations += ineq.confirmedRuns();
            }
            if (targets.size() < VOCAB_TARGET_CAP) {
                java.util.Set<String> taken = new java.util.HashSet<>();
                for (VocabularyExpansion.Target t : targets) {
                    taken.add(t.lhs() + "≤" + t.rhs());
                }
                // Threshold 1, not anomalyMinConfirmed: the corpus's historical
                // confirmedRuns were clobbered to ≤1 by the pre-high-water re-save
                // loop (see ConjectureMapper.copyMutableFields), so ≥5 selects an
                // empty set today. ≥1 = held at least one full run before breaking —
                // weak but real held-out evidence; the desc sort keeps the best
                // evidence leading, and as the high-water mark re-accumulates the
                // same code naturally serves stronger targets.
                List<Inequality> anomalyStratum = new ArrayList<>();
                for (Conjecture c : existing) {
                    if (DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob())
                            && c.confirmedRuns() >= 1) {
                        anomalyStratum.add(mapper.toInequality(c));
                    }
                }
                anomalyStratum.sort(Comparator.comparingInt((Inequality i) -> -i.confirmedRuns));
                for (Inequality ineq : anomalyStratum) {
                    if (targets.size() >= VOCAB_TARGET_CAP) break;
                    if (taken.add(ineq.lhs + "≤" + ineq.rhs)) {
                        targets.add(new VocabularyExpansion.Target(ineq.lhs, ineq.rhs));
                        nValidations += ineq.confirmedRuns;
                    }
                }
            }
            int budget = SublinearGenerationBudget.budget(nValidations, sublinearBudgetAlpha, VOCAB_ADMIT_CAP);
            MdlGate.Params params = new MdlGate.Params(
                    vocab.size() + 6, stochasticNullPermutations, stochasticNullAlpha, 0xCAB5EEDL);
            List<DerivedScalar> admitted = VocabularyExpansion.admit(
                    candidates, allSamples, targets, vocab, budget, params);

            int newConjectures = 0;
            if (!admitted.isEmpty()) {
                // Persist the newly-admitted scalars (survive restarts → re-extracted next pass).
                if (derivedScalarRepository != null) {
                    for (DerivedScalar dsc : admitted) {
                        if (!derivedScalarRepository.existsByDomainAndName(DOMAIN, dsc.name())) {
                            derivedScalarRepository.save(new DerivedScalarEntity(
                                    DOMAIN, dsc.name(), dsc.expr().depth(), dsc.gainBits(), java.time.Instant.now()));
                        }
                        log.info("vocabulary-expansion admitted scalar {} (net gain {} bits)",
                                dsc.name(), String.format("%.2f", dsc.gainBits()));
                    }
                }
                // Seed conjecture pairs that touch a newly-admitted scalar, so 30.5 generates
                // (additive, dedup'd, capped — mirrors composition persistence).
                List<DerivedScalar> augmentedSet = new ArrayList<>(activeAdmitted);
                augmentedSet.addAll(admitted);
                java.util.Set<String> newNames = new java.util.HashSet<>();
                for (DerivedScalar d : admitted) {
                    newNames.add(d.name());
                }
                List<Map<String, Double>> augmentedSamples = new ArrayList<>(allSamples.size());
                for (Map<String, Double> s : allSamples) {
                    Map<String, Double> m = new LinkedHashMap<>(s);
                    for (DerivedScalar d : admitted) {
                        double v = d.eval(m);
                        m.put(d.name(), Double.isFinite(v) ? v : 0.0);
                    }
                    augmentedSamples.add(m);
                }
                for (Inequality ineq : ConjectureEngine.seed(
                        augmentedSamples, MathInvariants.vocabulary(augmentedSet))) {
                    if (newConjectures >= VOCAB_NEW_CONJECTURE_CAP) {
                        break;
                    }
                    if (!newNames.contains(ineq.lhs) && !newNames.contains(ineq.rhs)) {
                        continue;   // only NEW pairs that actually use a newly-admitted scalar
                    }
                    String key = keyOf(ineq.lhs, ineq.rhs);
                    if (byKey.containsKey(key)) {
                        continue;
                    }
                    Conjecture entity = mapper.toEntity(ineq, DOMAIN);
                    entity.setDerivedFrom("vocab:" + (newNames.contains(ineq.lhs) ? ineq.lhs : ineq.rhs));
                    repo.save(entity);
                    byKey.put(key, entity);
                    newConjectures++;
                }
            }
            log.info("vocabulary expansion — {} candidate(s) probed, {} admitted (budget {}), {} new conjectures seeded",
                    candidates.size(), admitted.size(), budget, newConjectures);
        }

        // Phase 30 Wave 6 — RAF-closure reporting (sim #1, flag-gated OFF). Over the live findings'
        // derivation graph (rhs→lhs, food = the observable vocabulary), report the irreducible core
        // (iRAF) of the top confirmed findings as the publishable unit, and the count of findings
        // grounded in no RAF. Observability only — surfaces retirement candidates, doesn't retire.
        if (rafClosureEnabled) {
            java.util.Set<String> food = new java.util.HashSet<>(MathInvariants.vocabulary(activeAdmitted));
            List<Inequality> rafTargets = store.stream()
                    .sorted(Comparator.comparingInt((Inequality i) -> -i.confirmedRuns()))
                    .limit(RAF_TARGET_CAP)
                    .toList();
            RafReport.Report rr = RafReport.of(store, food, rafTargets);
            for (RafReport.Core core : rr.cores()) {
                if (core.size() > 1) {   // size 1 = a directly-grounded edge; >1 = a real chain
                    log.info("RAF closure — finding [{}] has an irreducible core of {} findings (publishable unit)",
                            core.target(), core.size());
                }
            }
            log.info("RAF closure — {} findings, maxRAF {}, {} grounded in no RAF (retirement candidates)",
                    rr.findings(), rr.maxRafSize(), rr.ungrounded());
        }

        // Persist survivors: update existing entities in place, insert new ones.
        // freshen() so a survivor's save can't carry a pass-start-stale
        // lastInvestigatedAt over a mid-pass markInvestigated stamp.
        int oeisAnnotated = 0;
        for (Inequality ineq : store) {
            String key = keyOf(ineq.lhs, ineq.rhs);
            Conjecture entity = byKey.get(key);
            if (entity == null) {
                entity = mapper.toEntity(ineq, DOMAIN);
            } else {
                entity = freshen(entity, byKey, key);
                mapper.apply(ineq, entity);
            }
            String annotation = oeisAnnotator.annotate(ineq.lhs, ineq.rhs);
            if (annotation != null) {
                entity.setOeisMatchJson(annotation);
                oeisAnnotated++;
            }
            repo.save(entity);
        }

        // Phase 30 integration (observability, flag-gated OFF) — wire the substrate
        // over the live survivors and log its view. Read-only; no behavior change.
        if (substrateMetricsEnabled) {
            log.info("conjecture mine pass — {}", SubstrateMetrics.of(store).summary());
        }

        // Phase 30 integration step 2 — persist novel compositions (flag-gated OFF,
        // capped). Sound 1-step derived bounds (W1-filtered, deduped) whose relation
        // isn't already in the pool become new live conjectures carrying derivedFrom
        // provenance, so the paper pipeline can investigate derivable-but-non-trivial
        // claims. They join the empirical update loop on the next mine pass like any
        // mined bound (confirmedRuns starts at 0). No-op when OFF.
        int compositionsPersisted = 0;
        if (compositionPersistEnabled) {
            // Phase 30 Wave 3 — sublinear generation budget: when on, the per-pass composition cap is
            // floor(n_validations^alpha) (n_validations = cumulative confirmedRuns over the live pool),
            // clamped to compositionPersistCap — so generation only approaches the cap as validation
            // work accrues. When off, the fixed cap applies (today's behaviour).
            // Legacy semantics: compositionPersistCap <= 0 means "unlimited". The sublinear budget,
            // when on, always enforces its computed cap (including 0 = generate none) — so it is NOT
            // subject to the unlimited shortcut.
            int effectiveCompositionCap = compositionPersistCap;
            boolean capUnlimited = !sublinearBudgetEnabled && compositionPersistCap <= 0;
            if (sublinearBudgetEnabled) {
                long nValidations = 0;
                for (Inequality ineq : store) {
                    nValidations += ineq.confirmedRuns();
                }
                effectiveCompositionCap = SublinearGenerationBudget.budget(
                        nValidations, sublinearBudgetAlpha, compositionPersistCap);
                log.debug("sublinear generation budget — nValidations={} alpha={} → cap={} (hardCap={})",
                        nValidations, sublinearBudgetAlpha, effectiveCompositionCap, compositionPersistCap);
            }
            for (ConjectureComposer.Composition comp
                    : ConjectureComposer.proposeNovelCompositions(store, 0)) {
                if (!capUnlimited && compositionsPersisted >= effectiveCompositionCap) {
                    break;
                }
                Inequality derived = comp.derived();
                String key = keyOf(derived.lhs, derived.rhs);
                if (byKey.containsKey(key)) {
                    continue;   // relation already persisted (mined, or composed in a prior pass)
                }
                Conjecture entity = mapper.toEntity(derived, DOMAIN);
                entity.setDerivedFrom(comp.parentKey1() + "  ∘  " + comp.parentKey2());
                String annotation = oeisAnnotator.annotate(derived.lhs, derived.rhs);
                if (annotation != null) {
                    entity.setOeisMatchJson(annotation);
                }
                repo.save(entity);
                byKey.put(key, entity);   // guard a second composition with the same pair this pass
                compositionsPersisted++;
            }
            if (compositionsPersisted > 0) {
                log.info("conjecture mine pass — persisted {} novel composition(s) as derived conjectures",
                        compositionsPersisted);
            }
        }

        log.info("conjecture mine pass — {} survivors ({} OEIS-annotated), {} retired (counterexample), {} dropped (knob filter), {} dropped (triviality/dedup), {} dropped (openness), {} dropped (dimension), {} dropped (unit), {} dropped (fim), {} dropped (stochastic null), {} dropped (private holdout), {} dropped (replication), {} composed (persisted), {} ms",
                store.size(), oeisAnnotated, totalRetiredByCounterexample, fr.dropped(), trivialOrRedundant,
                opennessDropped, dimensionDropped, unitDropped, fimDropped, nullDropped, gamedDropped, replicationDropped, compositionsPersisted, System.currentTimeMillis() - t0);
    }

    /** Live (not retired) conjectures for a domain — convenience accessor; raw list, no ordering. */
    public List<Conjecture> liveConjectures(String domain) {
        return repo.findByDomainAndDroppedByKnobIsNull(domain);
    }

    /**
     * Phase 23.3 — single highest-priority conjecture from the queue for
     * the paper-writing pipeline. Priority tiers, in order:
     * <ol>
     *   <li><b>Recently refuted by counterexample</b> — paper-worthy
     *       refutation material. Highest signal-per-paper because the
     *       conjecture survived seeding only to be broken by held-out data.</li>
     *   <li><b>Live conjectures with high confirmation</b> — the
     *       robust-bound papers. Sorted by confirmedRuns desc, then
     *       p99Slack asc (tighter = more publishable).</li>
     * </ol>
     *
     * <p>Returns empty when the DB has no conjectures for this domain
     * (e.g., the miner hasn't run yet, or the flag is off).
     */
    @Override
    public Optional<Conjecture> next(String domain) {
        List<Conjecture> picks = nextN(domain, 1);
        return picks.isEmpty() ? Optional.empty() : Optional.of(picks.get(0));
    }

    /**
     * Batch variant of {@link #next}. Returns up to {@code n} conjectures
     * in priority order. Tier-1 items are exhausted before tier-2 items
     * are emitted; ordering within a tier follows the rules in
     * {@link #next}'s javadoc.
     */
    @Override
    public List<Conjecture> nextN(String domain, int n) {
        if (n <= 0) {
            return List.of();
        }
        // Investigation gate — never serve a window-geometry conjecture as a paper, whatever its
        // retirement state. The live-store reduce() triviality drop (which my geometry rules feed)
        // only sees LIVE conjectures; a degenerate geometry bound is typically refuted by
        // counterexample in its seeding pass and removed from the store BEFORE reduce() runs, then
        // served from tier 1 (DROPPED_BY_COUNTEREXAMPLE) without ever passing triviality. Filtering
        // here is the decisive gate for "becomes a paper" — it covers both tiers. (Room b72c4e10
        // P-b72c-86/87: log_window_start bounds reached publication via exactly this bypass.)
        List<Conjecture> all = repo.findByDomain(domain).stream()
                .filter(c -> ConjectureTrivialityFilter.dropReason(mapper.toInequality(c)).isEmpty())
                .collect(java.util.stream.Collectors.toList());
        if (all.isEmpty()) {
            return List.of();
        }
        // Phase 30 G4 within-bucket diversity — when on, replace the tier-1-before-tier-2 order with a
        // family-rotating, openness-ranked order over the merged servable pool: distinct priority LHS
        // rotate (no single-family monoculture) and LIVE priority targets with no retired seed (e.g.
        // dirichlet_error) become reachable. No-op when off (legacy tier order below).
        if (opennessDiversityEnabled) {
            return diversifiedNextN(all, n);
        }
        List<Conjecture> out = new ArrayList<>(Math.min(n, all.size()));

        // Tier 1: retired by counterexample. Legacy order = most-recently-refuted
        // first. Phase 30 Wave 0 rotation (flag-gated) = least-recently-INVESTIGATED
        // first (then most-recently-refuted as tiebreaker), so the pipeline rotates
        // through distinct conjectures instead of re-serving the same one each cycle.
        Comparator<Conjecture> mostRecentlyRefuted =
                (a, b) -> Long.compare(timestampOf(b), timestampOf(a));
        Comparator<Conjecture> tier1Order = investigationRotationEnabled
                ? Comparator.<Conjecture>comparingLong(ConjectureMinerService::investigatedAtOf)
                        .thenComparing(mostRecentlyRefuted)
                : mostRecentlyRefuted;
        // Phase 30 G4 (open-behavior-generation-activation.md) — openness selection prior. Promote the
        // actively-progressing open / second-order / growth-capture LHS (ScalarOpenness.selectionRank=0)
        // ahead of the rest, so the now-clean pool is actually drawn from (the post-G1 bottleneck was
        // selection: growing-LHS topics surfaced only by luck). The existing rotation order is the
        // within-bucket tiebreak, so high-value targets rotate among themselves. No-op when off.
        if (opennessSelectionEnabled) {
            tier1Order = Comparator.<Conjecture>comparingInt(c -> ScalarOpenness.selectionRank(c.lhs()))
                    .thenComparing(tier1Order);
        }
        all.stream()
                .filter(c -> DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob()))
                .sorted(tier1Order)
                .limit(n)
                .forEach(out::add);

        // Tier 2: live conjectures. Legacy = highest confirmation first, tightest slack tiebreak.
        // Phase 30 Wave 3 (flag-gated): rank by clade-value × inverse-popularity instead — fit is
        // an eligibility gate only (still live), not the ranking key — so the queue diversifies
        // across relations rather than re-serving the best-fit family.
        if (out.size() < n) {
            List<Conjecture> live = all.stream()
                    .filter(c -> c.droppedByKnob() == null)
                    .collect(java.util.stream.Collectors.toList());
            java.util.stream.Stream<Conjecture> ordered = cladeValueSelectionEnabled
                    ? CladeValueSelector.rank(live, Conjecture::lhs).stream()
                    : live.stream().sorted(Comparator
                            .<Conjecture>comparingInt(c -> -c.confirmedRuns())
                            .thenComparingDouble(Conjecture::p99Slack));
            // G4 openness prior — stable re-sort puts priority targets first while preserving the
            // clade / confirmed-runs order within each rank bucket (Stream.sorted is stable). No-op off.
            if (opennessSelectionEnabled) {
                ordered = ordered.sorted(
                        Comparator.comparingInt(c -> ScalarOpenness.selectionRank(c.lhs())));
            }
            ordered.limit((long) n - out.size()).forEach(out::add);
        }
        return out;
    }

    private static long timestampOf(Conjecture c) {
        Instant t = c.updatedAt() != null ? c.updatedAt() : c.createdAt();
        return t == null ? 0 : t.toEpochMilli();
    }

    /**
     * Phase 30 G4 within-bucket diversity (open-behavior-generation-activation.md). Orders the merged
     * servable pool (retired-by-counterexample OR live; other drop-reasons excluded, same as the legacy
     * tiers) by, in priority:
     * <ol>
     *   <li>{@link ScalarOpenness#selectionRank} — priority targets first;</li>
     *   <li><b>least-recently-investigated LHS FAMILY</b> (the family's most-recent investigation time,
     *       ascending) — so after a family is served it sinks and a DIFFERENT priority LHS surfaces on
     *       the next call: family rotation, the fix for the single-family monoculture;</li>
     *   <li>retired-by-counterexample before live (the flat seed establishes the binding region for the
     *       capture loop; a live-only family like dirichlet_error is reachable here, unlike the legacy
     *       tier-1-before-tier-2 order);</li>
     *   <li>most-recently-refuted / updated, as a stable final tiebreak.</li>
     * </ol>
     * Package-private + static for unit testing. {@code all} is the triviality-filtered pool.
     */
    static List<Conjecture> diversifiedNextN(List<Conjecture> all, int n) {
        if (n <= 0 || all == null || all.isEmpty()) {
            return List.of();
        }
        List<Conjecture> servable = all.stream()
                .filter(c -> c.droppedByKnob() == null
                        || DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob()))
                .collect(java.util.stream.Collectors.toList());
        if (servable.isEmpty()) {
            return List.of();
        }
        // family (lhs) → most-recent investigation across the family; a just-served family sinks.
        Map<String, Long> familyTouched = new HashMap<>();
        for (Conjecture c : servable) {
            familyTouched.merge(c.lhs(), investigatedAtOf(c), Math::max);
        }
        Comparator<Conjecture> order = Comparator
                .<Conjecture>comparingInt(c -> ScalarOpenness.selectionRank(c.lhs()))
                .thenComparingLong(c -> familyTouched.getOrDefault(c.lhs(), Long.MIN_VALUE))
                // G2 Stage 4 (review-g Rec 2) — within a rotated family, PREFER a two-term row (rhs2 set):
                // generated composite structure should be investigated before re-refuting flat one-term
                // seeds. Without this the LIVE two-term rows sat behind big retired one-term piles
                // (max_prime_gap 19 retired) and never surfaced. Dormant when no two-term rows exist.
                .thenComparingInt(c -> c.rhs2() != null ? 0 : 1)
                .thenComparingInt(c -> c.droppedByKnob() == null ? 1 : 0)
                .thenComparing((a, b) -> Long.compare(timestampOf(b), timestampOf(a)));
        return servable.stream().sorted(order).limit(n)
                .collect(java.util.stream.Collectors.toList());
    }

    /** Epoch-ms of last investigation; {@link Long#MIN_VALUE} = never (sorts first under rotation). */
    private static long investigatedAtOf(Conjecture c) {
        return c.lastInvestigatedAt() == null ? Long.MIN_VALUE : c.lastInvestigatedAt().toEpochMilli();
    }

    /**
     * Phase 30 Wave 7 — 30.6: the next promoted anomaly awaiting its
     * UNEXPLAINED_OBSERVATION investigation. A row qualifies while it has a
     * promotion stamp NEWER than its last investigation (or was never
     * investigated) — so each promotion yields exactly one paper:
     * {@code markInvestigated} on consumption pushes {@code lastInvestigatedAt}
     * past {@code promotedAnomalyAt}, retiring it from this queue until a future
     * re-promotion re-stamps it. Geometry-trivial rows are excluded like
     * everywhere else.
     */
    @Override
    public java.util.Optional<Conjecture> nextPromotedAnomaly(String domain) {
        return repo.findByDomain(domain).stream()
                .filter(c -> c.promotedAnomalyAt() != null)
                .filter(c -> c.lastInvestigatedAt() == null
                        || c.lastInvestigatedAt().isBefore(c.promotedAnomalyAt()))
                .filter(c -> ConjectureTrivialityFilter.dropReason(mapper.toInequality(c)).isEmpty())
                .min(Comparator.comparing(Conjecture::promotedAnomalyAt));
    }

    /**
     * Phase 30 Wave 0 — record that this conjecture was selected for a paper,
     * so {@link #nextN} rotates away from it on the next cycle. Best-effort: a
     * null or transient conjecture is ignored.
     */
    @Override
    public void markInvestigated(Conjecture conjecture) {
        if (conjecture == null) {
            return;
        }
        Instant now = Instant.now();
        conjecture.setLastInvestigatedAt(now);   // keep the caller's in-memory view consistent
        // Targeted column UPDATE instead of a whole-entity save — a save() here both
        // loses to and competes with the miner's pass-long stale-snapshot saves
        // (see ConjectureRepository.stampInvestigated). Fall back to save() for a
        // transient (unsaved) conjecture, which has no row to stamp.
        if (conjecture.id() == null || repo.stampInvestigated(conjecture.id(), now) == 0) {
            repo.save(conjecture);
        }
    }

    private List<Map<String, Double>> materialize(Run run, List<DerivedScalar> admitted) {
        List<Map<String, Double>> samples = new ArrayList<>(run.offsets().length);
        for (int offset : run.offsets()) {
            try {
                Map<String, Double> raw = substrate.evaluateWindow(offset, run.windowSize());
                // admitted is empty unless vocabulary expansion is on → byte-identical extract when off.
                samples.add(MathInvariants.extract(raw, offset, run.windowSize(), admitted));
            } catch (RuntimeException e) {
                // 2026-05-29 — a single window failing (e.g. sidecar timeout on a large
                // number-theory window, or contention on the single-thread 8088) must NOT
                // crash the whole @Scheduled mine pass. Skip it and continue with the
                // windows that did evaluate; the pass mines from the remaining samples.
                log.warn("conjecture mine: skipping window offset={} size={} — {}",
                        offset, run.windowSize(), e.getMessage());
            }
        }
        return samples;
    }

    /**
     * Phase 30 Wave 4 — shared retire/shadow decision for a flagged W4 conjecture. In shadow mode
     * (the gate's own flag OFF but {@code w4ShadowEnabled} ON) it LOGS a would-retire and returns
     * {@code true} (KEEP — nothing persisted/removed); otherwise it retires for real and returns
     * {@code false} (DROP). Lets the W4 gates be A/B-validated against the live pool with no risk.
     */
    private boolean retireOrShadow(boolean shadow, Inequality ineq, Map<String, Conjecture> byKey, String reason) {
        // Decision A: the stochastic-null / private-holdout / replication heuristics are empirical
        // objections a professor's failure-region verification supersedes — none of them tests AT the
        // failure region with the discriminating power of verify_conjecture, and the offending
        // signal (near-constant rhs, public/private slack, fold instability) is a small-window
        // artifact for an asymptotic bound. Spare a stamped row from all three (KEEP it live).
        if (respectInvestigationVerified && investigationVerified(ineq, byKey)) {
            log.info("decision-A — sparing investigation-verified {} <= k*{} from empirical retire "
                    + "(reason {}); professor's failure-region verification supersedes",
                    ineq.lhs, ineq.rhs, reason);
            return true;
        }
        if (shadow) {
            log.info("W4 shadow — WOULD-RETIRE {} <= k*{} (reason {})", ineq.lhs, ineq.rhs, reason);
            return true;
        }
        persistRetired(List.of(ineq), byKey, reason);
        return false;
    }

    /** True when the row backing this inequality carries a professor's failure-region verification. */
    private boolean investigationVerified(Inequality ineq, Map<String, Conjecture> byKey) {
        Conjecture e = byKey.get(keyOf(ineq.lhs, ineq.rhs));
        return e != null && e.investigationVerifiedAt() != null;
    }

    /**
     * Decision A — partition counterexample-retired items: a stamped (investigation-verified) row
     * whose worst refuting window is BELOW {@link #FAILURE_REGION_FLOOR} is moved into {@code sparedOut}
     * (kept live, re-tested by in-region runs) instead of retired. Everything else — unstamped rows,
     * and stamped rows genuinely refuted at x &gt;= the floor — is returned for normal retirement.
     */
    private List<Inequality> sparingSubRegionVerified(
            List<Inequality> retired, Map<String, Conjecture> byKey, List<Inequality> sparedOut) {
        List<Inequality> stillRetire = new ArrayList<>(retired.size());
        for (Inequality ineq : retired) {
            long tick = worstWitnessTick(ineq);
            if (spareFromCounterexample(investigationVerified(ineq, byKey), tick)) {
                sparedOut.add(ineq);
                log.info("decision-A — sparing investigation-verified {} <= k*{} from counterexample "
                        + "at x={} (< failure-region floor {}); verified asymptotically at the failure region",
                        ineq.lhs, ineq.rhs, tick, FAILURE_REGION_FLOOR);
            } else {
                stillRetire.add(ineq);
            }
        }
        return stillRetire;
    }

    /**
     * Decision A's boundary, pure for unit testing: spare a counterexample-retire only when the row is
     * investigation-verified AND the refuting window is BELOW {@link #FAILURE_REGION_FLOOR}. An
     * unverified row, or a verified row refuted at or above the floor, is NOT spared (retires normally).
     */
    static boolean spareFromCounterexample(boolean verified, long witnessTick) {
        return verified && witnessTick < FAILURE_REGION_FLOOR;
    }

    /** The x (window start) of the most recent refuting witness, or {@link Long#MAX_VALUE} if none
     *  (treated as in-region so a witness-less retire is never spared). */
    static long worstWitnessTick(Inequality ineq) {
        List<Witness> w = ineq.refutedBy();
        return w.isEmpty() ? Long.MAX_VALUE : w.get(w.size() - 1).tick();
    }

    /**
     * Phase 31 §31.1 — optional memory capture for retirements (write-only;
     * the miner never depends on it). Optional-autowired so tests and
     * memory-less contexts construct unchanged.
     */
    private volatile com.emergentterrain.research.memory.ResearchMemoryService researchMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResearchMemory(
            com.emergentterrain.research.memory.ResearchMemoryService researchMemory) {
        this.researchMemory = researchMemory;
    }

    private void persistRetired(
            List<Inequality> retired,
            Map<String, Conjecture> byKey,
            String reason) {
        for (Inequality ineq : retired) {
            String key = keyOf(ineq.lhs, ineq.rhs);
            Conjecture entity = byKey.get(key);
            if (entity == null) {
                // Newly-seeded then immediately retired in the same pass — persist anyway so the witness lands.
                entity = mapper.toEntity(ineq, DOMAIN);
            } else {
                entity = freshen(entity, byKey, key);
                mapper.apply(ineq, entity);
            }
            if (entity.droppedByKnob() == null) {
                entity.setDroppedByKnob(reason);
                entity.setUpdatedAt(Instant.now());
            }
            repo.save(entity);
            // 31.1 capture — one FACT per retirement, content-hash deduped so a
            // re-seed/re-retire cycle of the same inequality reinforces rather
            // than duplicating (the [[feedback_no_reseed_skip]] churn stays).
            com.emergentterrain.research.memory.ResearchMemoryService mem = researchMemory;
            if (mem != null && mem.enabled()) {
                mem.capture(
                        com.emergentterrain.research.memory.ResearchMemoryEntity.TYPE_FACT,
                        String.format("conjecture retired: %s <= %s*%s + %s — reason=%s runs=%d",
                                ineq.lhs, trimD(ineq.k), ineq.rhs, trimD(ineq.c),
                                reason, entity.confirmedRuns()),
                        "miner:" + reason,
                        "conjecture/" + ineq.lhs, DOMAIN, null, null);
            }
        }
    }

    private static String trimD(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * 2026-06-14 — the new-pairs-only core of the incremental re-seed (pure + static, so it is unit
     * tested directly). Runs the full {@link ConjectureEngine#seed} over the vocabulary, then keeps
     * ONLY the fitted pairs whose {@code (lhs,rhs)} key is absent from BOTH the persisted pool
     * ({@code knownKeys} = every existing row, live AND retired) and the current live {@code store}.
     * That activates a base-vocabulary addition (e.g. wigert_bound) without a wipe while never
     * re-flooding already-seen (incl. retired) pairs — the surgical contract that keeps paper-type
     * mix and volume unchanged. Returns an empty list once the new pairs have persisted (steady state).
     */
    static List<Inequality> newVocabularyPairSeeds(
            List<Map<String, Double>> samples, List<String> vocabulary,
            java.util.Set<String> knownKeys, List<Inequality> liveStore) {
        java.util.Set<String> known = new java.util.HashSet<>(knownKeys);
        for (Inequality s : liveStore) {
            known.add(keyOf(s.lhs(), s.rhs()));
        }
        List<Inequality> out = new ArrayList<>();
        for (Inequality seeded : ConjectureEngine.seed(samples, vocabulary)) {
            if (!known.contains(keyOf(seeded.lhs(), seeded.rhs()))) {
                out.add(seeded);
            }
        }
        return out;
    }

    /**
     * Re-fetch the current row for a pass-start snapshot entity so saving it cannot
     * clobber columns written mid-pass by other writers — markInvestigated's rotation
     * stamp in particular. The byKey map is loaded once at the top of {@code mine()}
     * and a pass now runs ~10 s (empty-pool re-seed churn); saving those stale
     * snapshots rewrites every column, which erased {@code lastInvestigatedAt} stamps
     * landing mid-pass and re-served the same conjecture to concurrent paper cycles
     * (P-b72c-115/116 duplicate, 2026-06-04). Falls back to the snapshot when the
     * row is gone; refreshes the byKey entry so later writes this pass reuse it.
     */
    private Conjecture freshen(Conjecture snapshot, Map<String, Conjecture> byKey, String key) {
        if (snapshot.id() == null) {
            return snapshot;
        }
        Conjecture fresh = repo.findById(snapshot.id()).orElse(snapshot);
        if (fresh != snapshot) {
            byKey.put(key, fresh);
        }
        return fresh;
    }

    private static String keyOf(String lhs, String rhs) {
        return lhs + "|" + rhs;
    }

    /** One named scalar's series across the samples (missing → 0.0). */
    private static double[] column(List<Map<String, Double>> samples, String name) {
        double[] a = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            a[i] = samples.get(i).getOrDefault(name, 0.0);
        }
        return a;
    }

    /**
     * Phase 30 G2 Stage 3 — fit a residual-discovered two-term relation {@code lhs ~ rhs + z} into a
     * concrete bound {@code lhs ≤ k1*rhs + k2*z + c} via {@link ConjectureEngine#tightestK1K2C} over the
     * samples. Returns the two-term {@link Inequality}, or null if the fit is unavailable (empty/ragged
     * samples). Pure + package-private for unit testing.
     */
    static Inequality fitTwoTerm(List<Map<String, Double>> samples, String lhs, String rhs, String z) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        double[] fit = ConjectureEngine.tightestK1K2C(
                column(samples, lhs), column(samples, rhs), column(samples, z));
        if (fit == null) {
            return null;
        }
        Inequality two = new Inequality(lhs, rhs, fit[0], fit[2]);
        two.setSecondTerm(z, fit[1]);
        return two;
    }

    /**
     * Phase 30 step 2.5 — run the knob-restatement filter, optionally exempting
     * derived (composed) keys. When {@code exempt} is off (or no derived keys),
     * this is exactly {@link ConjectureEngine#filterKnobRestatements} over the
     * whole store (legacy behavior). When on, derived items are pulled aside,
     * the rest are filtered in place, and the derived items are added back — so a
     * cross-quantity relation's window-correlated slack no longer counts as a knob
     * restatement. {@code store} is mutated in place to hold the survivors (mined
     * survivors + all derived); the returned {@link ConjectureEngine.FilterResult}
     * lists only the dropped mined items. Package-private for unit tests.
     */
    static ConjectureEngine.FilterResult knobFilterExempting(
            List<Inequality> store,
            java.util.Set<String> derivedKeys,
            boolean exempt,
            List<ConjectureEngine.KnobAxis> axes,
            double threshold,
            int minConfigs) {
        return knobFilterExempting(store, derivedKeys, exempt, java.util.Set.of(),
                axes, threshold, minConfigs);
    }

    /**
     * 2026-06-14 — knob-filter exemption widened to VERIFIED GROWTH ANCHORS (room b72c4e10
     * review-c, Rec 1). A growth-anchor bound (rhs ∈ {@link ConjectureTrivialityFilter#WINDOW_GEOMETRY_SCALARS},
     * e.g. {@code max_prime_gap ≤ k·log_sq_window_start}) has slack that correlates with window_size
     * BY CONSTRUCTION — the RHS is a window function — so the restatement heuristic flags it exactly
     * as it would a knob restatement, and it was silently retiring investigation-verified survivors
     * (the durable 0.649 Cramér bound was dropped this way after a legitimate re-tighten). This is the
     * same window-correlated-slack situation that already exempts composed bounds; here the protection
     * is gated on investigation-verified status (the professor confirmed it holds at the failure
     * region), so spurious unverified window-correlated SEEDS are still filtered. {@code exemptAnchorKeys}
     * is the verified-anchor key set (already flag-gated + filtered to geometry-RHS by the caller;
     * empty = off). Both exempt sets are held aside, filtered out, then re-added.
     */
    static ConjectureEngine.FilterResult knobFilterExempting(
            List<Inequality> store,
            java.util.Set<String> derivedKeys,
            boolean exempt,
            java.util.Set<String> exemptAnchorKeys,
            List<ConjectureEngine.KnobAxis> axes,
            double threshold,
            int minConfigs) {
        java.util.Set<String> holdKeys = new java.util.HashSet<>();
        if (exempt && derivedKeys != null) {
            holdKeys.addAll(derivedKeys);
        }
        if (exemptAnchorKeys != null) {
            holdKeys.addAll(exemptAnchorKeys);
        }
        if (holdKeys.isEmpty()) {
            return ConjectureEngine.filterKnobRestatements(store, axes, threshold, minConfigs);
        }
        List<Inequality> held = new ArrayList<>();
        java.util.Iterator<Inequality> it = store.iterator();
        while (it.hasNext()) {
            Inequality ineq = it.next();
            if (holdKeys.contains(keyOf(ineq.lhs, ineq.rhs))) {
                held.add(ineq);
                it.remove();
            }
        }
        ConjectureEngine.FilterResult fr =
                ConjectureEngine.filterKnobRestatements(store, axes, threshold, minConfigs);
        store.addAll(held);   // exempt items survive the restatement heuristic (still counterexample-checked)
        return fr;
    }
}
