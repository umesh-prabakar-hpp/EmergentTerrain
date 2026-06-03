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
            new Run("P10k_holdout", 10_000, new int[]{15_000, 150_000, 600_000})
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
        this.multipleComparisonsEnabled = props.isMultipleComparisonsEnabled();
        this.mccMethod = MultipleComparisonsCorrection.Method.parse(props.getMultipleComparisonsMethod());
        this.privateValidatorEnabled = props.isPrivateValidatorEnabled();
        this.replicationValidatorEnabled = props.isReplicationValidatorEnabled();
        this.replicationFolds = props.getReplicationFolds();
        this.replicationMinPassRate = props.getReplicationMinPassRate();
        this.unitConsistencyEnabled = props.isUnitConsistencyEnabled();
        this.identifiabilityEnabled = props.isIdentifiabilityEnabled();
        this.identifiabilityMaxRelStdErr = props.getIdentifiabilityMaxRelStdErr();
        log.info("conjecture miner ready — substrate via sidecar delegate ({} OEIS catalog entries), "
                + "trivialityFilter={} dimensionFilter={} investigationRotation={} substrateMetrics={} "
                + "compositionPersist={}{} compositionKnobExempt={} stochasticNull={} privateValidator={} replication={} "
                + "unitConsistency={} identifiability={}",
                OeisCatalog.size(), trivialityFilterEnabled ? "ON" : "OFF",
                dimensionFilterEnabled ? "ON" : "OFF", investigationRotationEnabled ? "ON" : "OFF",
                substrateMetricsEnabled ? "ON" : "OFF",
                compositionPersistEnabled ? "ON" : "OFF",
                compositionPersistEnabled ? " (cap=" + compositionPersistCap + ")" : "",
                compositionKnobExemptEnabled ? "ON" : "OFF",
                stochasticNullEnabled
                        ? "ON (alpha=" + stochasticNullAlpha + ", mcc="
                                + (multipleComparisonsEnabled ? mccMethod.name().toLowerCase() : "off") + ")"
                        : "OFF",
                privateValidatorEnabled ? "ON" : "OFF",
                replicationValidatorEnabled
                        ? "ON (" + replicationFolds + "-fold, minPass=" + replicationMinPassRate + ")"
                        : "OFF",
                unitConsistencyEnabled ? "ON" : "OFF",
                identifiabilityEnabled ? "ON (maxRelSE=" + identifiabilityMaxRelStdErr + ")" : "OFF");
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
        boolean collectSamples = stochasticNullEnabled || replicationValidatorEnabled || identifiabilityEnabled;
        List<Map<String, Double>> allSamples =
                collectSamples ? new ArrayList<>() : List.of();

        for (Run run : RUNS) {
            List<Map<String, Double>> samples = materialize(run);
            String configHash = "W" + run.windowSize();
            knobValues.put(configHash, (double) run.windowSize());
            if (collectSamples) {
                allSamples.addAll(samples);
            }

            if (store.isEmpty()) {
                store = ConjectureEngine.seed(samples, MathInvariants.vocabulary());
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
        if (stochasticNullEnabled) {
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
                        persistRetired(List.of(ineq), byKey, "stochastic_null:rhs_near_constant_cv");
                        nullDropped++;
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
                } else {
                    persistRetired(List.of(testable.get(i)), byKey, mccReason);
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
        if (privateValidatorEnabled) {
            List<Map<String, Double>> privateSamples = new ArrayList<>();
            for (Run run : PRIVATE_RUNS) {
                privateSamples.addAll(materialize(run));
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
                    persistRetired(List.of(ineq), byKey, "private_validator:failed_holdout");
                    gamedDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            log.info("private validator — tested={} gamed (passed public, failed private)={} gamingRate={}",
                    tested, gamedDropped, String.format("%.3f",
                            PrivateHoldoutValidator.gamingRate(tested, gamedDropped)));
            store = kept;
        }

        // Phase 30 Wave 4 component 4 — ensemble replication (K-fold CV stability, flag-gated OFF).
        // Refits (k,c) per fold and tests out-of-fold; a bound whose fit doesn't generalise across
        // folds (stochastic-pass rate below the minimum) is replication-fragile and retired. The
        // pass rate is the quantified replication score. No-op when the flag is off.
        int replicationDropped = 0;
        if (replicationValidatorEnabled) {
            List<Inequality> kept = new ArrayList<>(store.size());
            for (Inequality ineq : store) {
                long seed = keyOf(ineq.lhs, ineq.rhs).hashCode();
                ConjectureReplicationValidator.Result rr = ConjectureReplicationValidator.evaluate(
                        allSamples, ineq.lhs, ineq.rhs, ineq.k, replicationFolds, ConjectureEngine.TOL,
                        replicationMinPassRate, seed);
                if (rr.flagged()) {
                    persistRetired(List.of(ineq), byKey, "replication:fragile_passRate_"
                            + String.format("%.2f", rr.passRate()));
                    replicationDropped++;
                } else {
                    kept.add(ineq);
                }
            }
            store = kept;
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
            for (ConjectureComposer.Composition comp
                    : ConjectureComposer.proposeNovelCompositions(store, 0)) {
                if (compositionPersistCap > 0 && compositionsPersisted >= compositionPersistCap) {
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
        List<Conjecture> all = repo.findByDomain(domain);
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

        // Tier 2: live, highest confirmation first, tightest slack as tiebreaker.
        if (out.size() < n) {
            all.stream()
                    .filter(c -> c.droppedByKnob() == null)
                    .sorted(Comparator
                            .<Conjecture>comparingInt(c -> -c.confirmedRuns())
                            .thenComparingDouble(Conjecture::p99Slack))
                    .limit((long) n - out.size())
                    .forEach(out::add);
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

    private List<Map<String, Double>> materialize(Run run) {
        List<Map<String, Double>> samples = new ArrayList<>(run.offsets().length);
        for (int offset : run.offsets()) {
            try {
                Map<String, Double> raw = substrate.evaluateWindow(offset, run.windowSize());
                samples.add(MathInvariants.extract(raw, offset, run.windowSize()));
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
