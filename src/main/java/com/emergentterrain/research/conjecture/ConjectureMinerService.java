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
    /** Cap on how many live bounds residual-structure discovery probes per pass (cost bound). */
    private static final int RESIDUAL_PROBE_CAP = 25;
    // Phase 30 Wave 5 — anomaly mining (30.1); observability hook, no persistence yet.
    private final boolean anomalyMiningEnabled;
    private final int anomalyMinConfirmed;
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
        this.dimensionFilterEnabled = props.isDimensionFilterEnabled();
        this.investigationRotationEnabled = props.isInvestigationRotationEnabled();
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
        this.anomalyMiningEnabled = props.isAnomalyMiningEnabled();
        this.anomalyMinConfirmed = props.getAnomalyMinConfirmed();
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
            if (e.droppedByKnob() == null) {
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

        for (Run run : RUNS) {
            List<Map<String, Double>> samples = materialize(run, activeAdmitted);
            String configHash = "W" + run.windowSize();
            knobValues.put(configHash, (double) run.windowSize());
            if (collectSamples) {
                allSamples.addAll(samples);
            }

            if (store.isEmpty()) {
                store = ConjectureEngine.seed(samples, MathInvariants.vocabulary(activeAdmitted));
                log.debug("seeded {} candidate inequalities from run {}", store.size(), run.name());
            } else {
                ConjectureEngine.UpdateResult ur = ConjectureEngine.update(
                        store, run.name(), configHash, samples);
                totalConfirmed += ur.confirmed();
                totalRetiredByCounterexample += ur.retired();
                persistRetired(ur.retiredItems(), byKey, DROPPED_BY_COUNTEREXAMPLE);
            }
        }

        // Phase 30 step 2.5 — derived (composed) conjectures are exempt from the
        // window_size knob filter when the flag is on (no-op otherwise). See
        // knobFilterExempting.
        ConjectureEngine.FilterResult fr = knobFilterExempting(
                store, derivedKeys, compositionKnobExemptEnabled,
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

        // Phase 30 Wave 1 — dimensional-mismatch drop (flag-gated OFF, independent
        // of the triviality flag). Drops density <= k*window_magnitude (k>0) seeds,
        // which are eventually vacuous. No-op when the flag is off.
        int dimensionDropped = 0;
        if (dimensionFilterEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
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
                        if (retireOrShadow(nullShadow, ineq, byKey, "stochastic_null:rhs_near_constant_cv")) {
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
                } else if (retireOrShadow(nullShadow, testable.get(i), byKey, mccReason)) {
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
            List<Inequality> ranked = store.stream()
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
                }
            }
            log.info("residual-structure discovery — probed {} bound(s), {} with residual structure beating the null",
                    probed, discovered);
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
            List<VocabularyExpansion.Target> targets = store.stream()
                    .sorted(Comparator.comparingInt((Inequality i) -> -i.confirmedRuns()))
                    .limit(VOCAB_TARGET_CAP)
                    .map(i -> new VocabularyExpansion.Target(i.lhs, i.rhs))
                    .toList();
            long nValidations = 0;
            for (Inequality ineq : store) {
                nValidations += ineq.confirmedRuns();
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
        int oeisAnnotated = 0;
        for (Inequality ineq : store) {
            String key = keyOf(ineq.lhs, ineq.rhs);
            Conjecture entity = byKey.get(key);
            if (entity == null) {
                entity = mapper.toEntity(ineq, DOMAIN);
            } else {
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

        log.info("conjecture mine pass — {} survivors ({} OEIS-annotated), {} retired (counterexample), {} dropped (knob filter), {} dropped (triviality/dedup), {} dropped (dimension), {} dropped (unit), {} dropped (fim), {} dropped (stochastic null), {} dropped (private holdout), {} dropped (replication), {} composed (persisted), {} ms",
                store.size(), oeisAnnotated, totalRetiredByCounterexample, fr.dropped(), trivialOrRedundant,
                dimensionDropped, unitDropped, fimDropped, nullDropped, gamedDropped, replicationDropped, compositionsPersisted, System.currentTimeMillis() - t0);
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
            ordered.limit((long) n - out.size()).forEach(out::add);
        }
        return out;
    }

    private static long timestampOf(Conjecture c) {
        Instant t = c.updatedAt() != null ? c.updatedAt() : c.createdAt();
        return t == null ? 0 : t.toEpochMilli();
    }

    /** Epoch-ms of last investigation; {@link Long#MIN_VALUE} = never (sorts first under rotation). */
    private static long investigatedAtOf(Conjecture c) {
        return c.lastInvestigatedAt() == null ? Long.MIN_VALUE : c.lastInvestigatedAt().toEpochMilli();
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
        conjecture.setLastInvestigatedAt(Instant.now());
        repo.save(conjecture);
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
        if (shadow) {
            log.info("W4 shadow — WOULD-RETIRE {} <= k*{} (reason {})", ineq.lhs, ineq.rhs, reason);
            return true;
        }
        persistRetired(List.of(ineq), byKey, reason);
        return false;
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
                mapper.apply(ineq, entity);
            }
            if (entity.droppedByKnob() == null) {
                entity.setDroppedByKnob(reason);
                entity.setUpdatedAt(Instant.now());
            }
            repo.save(entity);
        }
    }

    private static String keyOf(String lhs, String rhs) {
        return lhs + "|" + rhs;
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
        if (!exempt || derivedKeys == null || derivedKeys.isEmpty()) {
            return ConjectureEngine.filterKnobRestatements(store, axes, threshold, minConfigs);
        }
        List<Inequality> held = new ArrayList<>();
        java.util.Iterator<Inequality> it = store.iterator();
        while (it.hasNext()) {
            Inequality ineq = it.next();
            if (derivedKeys.contains(keyOf(ineq.lhs, ineq.rhs))) {
                held.add(ineq);
                it.remove();
            }
        }
        ConjectureEngine.FilterResult fr =
                ConjectureEngine.filterKnobRestatements(store, axes, threshold, minConfigs);
        store.addAll(held);   // derived survive the restatement heuristic (still counterexample-checked)
        return fr;
    }
}
