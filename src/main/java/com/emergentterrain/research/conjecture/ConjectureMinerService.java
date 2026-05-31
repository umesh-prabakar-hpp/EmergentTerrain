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

    public ConjectureMinerService(
            ConjectureRepository repo,
            ConjectureMapper mapper,
            OeisAnnotator oeisAnnotator,
            MathConjectureSubstrateDelegate substrate,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.research.conjectureMiner.trivialityFilterEnabled:false}")
            boolean trivialityFilterEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.research.conjectureMiner.dimensionFilterEnabled:false}")
            boolean dimensionFilterEnabled) {
        this.repo = repo;
        this.mapper = mapper;
        this.oeisAnnotator = oeisAnnotator;
        this.substrate = substrate;
        this.trivialityFilterEnabled = trivialityFilterEnabled;
        this.dimensionFilterEnabled = dimensionFilterEnabled;
        log.info("conjecture miner ready — substrate via sidecar delegate ({} OEIS catalog entries), "
                + "trivialityFilter={} dimensionFilter={}", OeisCatalog.size(),
                trivialityFilterEnabled ? "ON" : "OFF", dimensionFilterEnabled ? "ON" : "OFF");
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
        for (Conjecture e : existing) {
            if (e.droppedByKnob() == null) {
                store.add(mapper.toInequality(e));
            }
        }
        log.debug("loaded {} existing conjectures ({} live)", existing.size(), store.size());

        Map<String, Double> knobValues = new LinkedHashMap<>();
        int totalConfirmed = 0;
        int totalRetiredByCounterexample = 0;

        for (Run run : RUNS) {
            List<Map<String, Double>> samples = materialize(run);
            String configHash = "W" + run.windowSize();
            knobValues.put(configHash, (double) run.windowSize());

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

        ConjectureEngine.FilterResult fr = ConjectureEngine.filterKnobRestatements(
                store,
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

        log.info("conjecture mine pass — {} survivors ({} OEIS-annotated), {} retired (counterexample), {} dropped (knob filter), {} dropped (triviality/dedup), {} dropped (dimension), {} ms",
                store.size(), oeisAnnotated, totalRetiredByCounterexample, fr.dropped(), trivialOrRedundant,
                dimensionDropped, System.currentTimeMillis() - t0);
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

        // Tier 1: retired by counterexample, most recent first.
        all.stream()
                .filter(c -> DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob()))
                .sorted((a, b) -> Long.compare(timestampOf(b), timestampOf(a)))
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
}
