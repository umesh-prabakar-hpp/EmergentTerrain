package com.emergentterrain.research;

import com.emergentterrain.llm.JuliaMathClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 28 §28.5 — dispatch wrapper around {@link MathPhysicsEngine#compute}
 * and the sidecar's {@code /physics/topic} endpoint (via
 * {@link JuliaMathClient#physicsTopic}).
 *
 * <h2>Dispatch rule</h2>
 *
 * The §28.5b-§28.5h sidecar ports cover all 9 MathPhysicsEngine
 * domains × ~5–10 topics each (~63 topics total) with byte-identical
 * {@code primaryValue/secondaryValue/tertiaryValue/claimStatus}
 * agreement, pinned by {@link PhysicsTopicParityTest}.
 *
 * <p>This delegate ships in <strong>shadow-read mode</strong> only:
 * the in-process compute path is the source of truth on every call.
 * When {@code app.julia.physics.topic.enabled=true} AND the sidecar is
 * reachable, the delegate ALSO calls the sidecar in parallel, compares
 * scalars + claim status against the in-process result, and updates
 * the parity-match / parity-mismatch counters. The returned
 * {@link MathPhysicsEngine.ResearchResult} is unchanged.
 *
 * <h2>Why shadow read first</h2>
 *
 * The §28.5b–§28.5h ports landed under a synthetic parity test using
 * {@link MathPhysicsEngine.SearchParams#neutral()}. Real bots use
 * genome-sampled SearchParams across the full
 * {@code stepSize × candidateBreadth} grid. Shadow mode lets the
 * operator confirm parity under live load before the §28.6 cutover
 * flips the read path to sidecar-primary. Stats exposed via
 * {@link #stats()} are the burn-in signal: a clean run with zero
 * {@code parityMismatch} over N days unblocks §28.6.
 *
 * <h2>Why a delegate, not a static replacement</h2>
 *
 * Same reasoning as {@link com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate}:
 * the in-process path is correct, fast, and operator-trusted. Cutting
 * over without a burn-in window would regress on the first sidecar
 * outage. The delegate seam keeps the flip reversible.
 *
 * <h2>§28.6b — primary-read cutover</h2>
 *
 * When {@code app.julia.physics.topic.primary=true}, the delegate returns the
 * {@link SidecarResultComposer}-built ResearchResult for any topic the composer
 * covers, instead of the in-process result — prose included, composed JVM-side
 * from the sidecar's numeric {@code detail} (approach B). The in-process compute
 * still runs and parity is still recorded during this transition, so the cutover
 * is observable and reversible; dropping the in-process call from the hot path is
 * the final legacy-removal step once prose-parity burn-in is clean. Topics
 * without a composer branch fall back to the in-process result, so primary read
 * lights up topic-by-topic rather than all-or-nothing.
 */
@Service
public class MathPhysicsEngineDelegate {

    private static final Logger log = LoggerFactory.getLogger(MathPhysicsEngineDelegate.class);

    /**
     * Emit an INFO-level heartbeat with the stats snapshot every N
     * successful shadow calls. Gives the operator a passive burn-in
     * readout without standing up an actuator endpoint or scraping
     * stats via JMX. Chosen so a typical paper-write workload (~26
     * compute calls per paper, ~30 papers/hour) heartbeats roughly
     * once every 1–2 hours.
     */
    private static final long HEARTBEAT_INTERVAL = 1_000L;

    private final JuliaMathClient juliaMathClient;
    private final boolean shadowReadEnabled;
    /**
     * Phase 28 §28.6b — sidecar-primary cutover. When true (and a
     * JuliaMathClient bean is present), {@link #compute} returns the
     * {@link SidecarResultComposer}-built result for any topic the composer
     * covers, instead of the in-process result. The in-process compute still
     * runs and parity is still recorded, so divergence stays visible during
     * burn-in — flipping this flag is fully reversible and never silences the
     * parity counters. Default OFF per the flag-gated-dormant convention.
     */
    private final boolean primaryReadEnabled;

    private final AtomicLong totalCalls       = new AtomicLong();
    private final AtomicLong shadowCalls      = new AtomicLong();
    private final AtomicLong parityMatches    = new AtomicLong();
    private final AtomicLong parityMismatches = new AtomicLong();
    private final AtomicLong sidecarErrors    = new AtomicLong();
    private final AtomicLong sidecarEmpty     = new AtomicLong();

    /**
     * Single constructor — used for both Spring injection and tests.
     * The {@code @Value} default-OFF flag and the
     * {@code @Autowired(required=false)} client both keep the delegate
     * safe to instantiate in test slice contexts where no
     * JuliaMathClient bean is wired.
     */
    @Autowired
    public MathPhysicsEngineDelegate(
            @Value("${app.julia.physics.topic.enabled:false}") boolean shadowReadEnabled,
            @Value("${app.julia.physics.topic.primary:false}") boolean primaryReadEnabled,
            @Autowired(required = false) JuliaMathClient juliaMathClient) {
        this.juliaMathClient    = juliaMathClient;
        // Shadow read activates only when the flag AND a JuliaMathClient
        // bean are both present — defends against partial wiring in
        // test slice contexts.
        this.shadowReadEnabled  = shadowReadEnabled && juliaMathClient != null;
        // Primary read implies a sidecar fetch even if shadow read is off;
        // same client-present guard.
        this.primaryReadEnabled = primaryReadEnabled && juliaMathClient != null;
        log.info("MathPhysicsEngineDelegate: ready, shadowReadEnabled={} " +
                "primaryReadEnabled={} (shadowFlag={} primaryFlag={} client={})",
                this.shadowReadEnabled, this.primaryReadEnabled,
                shadowReadEnabled, primaryReadEnabled, juliaMathClient != null);
    }

    /**
     * Two-arg convenience constructor — primary read defaults OFF. Retained so
     * the existing shadow-mode tests and call sites compile unchanged; the
     * {@code @Autowired} three-arg constructor above is the one Spring uses.
     */
    public MathPhysicsEngineDelegate(boolean shadowReadEnabled, JuliaMathClient juliaMathClient) {
        this(shadowReadEnabled, false, juliaMathClient);
    }

    /**
     * Compute the ResearchResult for {@code (domain, topicIndex)} with
     * the given search parameters. Always returns the in-process result;
     * when shadow mode is on, also fires a sidecar request and updates
     * parity counters.
     *
     * <p>Mirrors the signature of
     * {@link MathPhysicsEngine#compute(String, int, int, double, MathPhysicsEngine.SearchParams)}
     * so the delegate is a drop-in replacement for direct static calls
     * in {@link ResearchCycleService}, {@link BotLifecycleService}, and
     * {@link ReplicationService}.
     */
    public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                                     int param1, double param2,
                                                     MathPhysicsEngine.SearchParams sp) {
        totalCalls.incrementAndGet();
        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);

        // A sidecar fetch is needed for shadow-parity recording OR for the
        // primary-read cutover. Skip it (return in-process) when neither flag
        // is on or the sidecar is unreachable.
        if ((!shadowReadEnabled && !primaryReadEnabled) || !juliaMathClient.isAvailable()) {
            return jvm;
        }

        shadowCalls.incrementAndGet();
        try {
            Optional<JuliaMathClient.PhysicsTopicResultWire> wire =
                    juliaMathClient.physicsTopic(domain, topicIndex, param1, param2, sp);
            if (wire.isEmpty()) {
                // Not-ported response from sidecar — shouldn't happen
                // once §28.5h is in, but track it so a partial-coverage
                // regression is visible in stats.
                sidecarEmpty.incrementAndGet();
                return jvm;
            }
            // Always record parity when we have the wire — keeps the burn-in
            // signal alive in primary mode too, so a regression after cutover
            // is still logged rather than silently served.
            recordParity(jvm, wire.get(), domain, topicIndex);

            if (primaryReadEnabled) {
                Optional<MathPhysicsEngine.ResearchResult> composed =
                        SidecarResultComposer.composeFromDetail(
                                domain, topicIndex, wire.get().detail());
                if (composed.isPresent()) {
                    return composed.get();
                }
                // Composer has no branch for this topic yet — fall through to
                // the in-process result. The cutover lights up topic-by-topic.
            }
        } catch (RuntimeException e) {
            sidecarErrors.incrementAndGet();
            log.warn("MathPhysicsEngineDelegate: sidecar physicsTopic failed " +
                    "(domain={} topic={}): {} — counted as error, " +
                    "in-process result returned unchanged",
                    domain, topicIndex, e.getMessage());
        }
        return jvm;
    }

    /**
     * Convenience overload for callers that haven't built a SearchParams
     * — uses the neutral genome, same as
     * {@link MathPhysicsEngine#compute(String, int, int, double)}.
     */
    public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                                     int param1, double param2) {
        return compute(domain, topicIndex, param1, param2,
                MathPhysicsEngine.SearchParams.neutral());
    }

    /**
     * Compare the in-process result against the sidecar wire result.
     * Bumps {@link #parityMatches} on byte-identity, {@link #parityMismatches}
     * with a WARN log otherwise. The four-scalar check matches the
     * {@link PhysicsTopicParityTest} acceptance bar exactly.
     */
    private void recordParity(MathPhysicsEngine.ResearchResult jvm,
                              JuliaMathClient.PhysicsTopicResultWire wire,
                              String domain, int topicIndex) {
        boolean match = Double.compare(jvm.primaryValue(),   wire.primaryValue())   == 0
                     && Double.compare(jvm.secondaryValue(), wire.secondaryValue()) == 0
                     && Double.compare(jvm.tertiaryValue(),  wire.tertiaryValue())  == 0
                     && Double.compare(jvm.reviewValue(),    wire.reviewValue())    == 0
                     && jvm.claimStatus().name().equals(wire.claimStatus());

        if (match) {
            long matches = parityMatches.incrementAndGet();
            // Periodic burn-in heartbeat. Logs at parityMatches in
            // {1000, 2000, 3000, ...} so the operator can confirm
            // shadow read is alive and divergence-free without
            // scraping stats() through a custom endpoint.
            if (matches % HEARTBEAT_INTERVAL == 0) {
                Stats s = stats();
                log.info("MathPhysicsEngineDelegate: burn-in heartbeat — " +
                        "totalCalls={} shadowCalls={} parityMatches={} " +
                        "parityMismatches={} sidecarErrors={} sidecarEmpty={}",
                        s.totalCalls(), s.shadowCalls(), s.parityMatches(),
                        s.parityMismatches(), s.sidecarErrors(), s.sidecarEmpty());
            }
            return;
        }

        parityMismatches.incrementAndGet();
        log.warn("MathPhysicsEngineDelegate: parity MISMATCH (domain={} topic={}) " +
                "jvm=(p={}, s={}, t={}, r={}, status={}) " +
                "sidecar=(p={}, s={}, t={}, r={}, status={})",
                domain, topicIndex,
                jvm.primaryValue(),  jvm.secondaryValue(),  jvm.tertiaryValue(),
                jvm.reviewValue(),   jvm.claimStatus().name(),
                wire.primaryValue(), wire.secondaryValue(), wire.tertiaryValue(),
                wire.reviewValue(),  wire.claimStatus());
    }

    /**
     * Snapshot of dispatch counters. The §28.6 cutover precondition is
     * {@code parityMismatches == 0} AND {@code parityMatches > N_threshold}
     * over the burn-in window.
     */
    public Stats stats() {
        return new Stats(
                totalCalls.get(),
                shadowCalls.get(),
                parityMatches.get(),
                parityMismatches.get(),
                sidecarErrors.get(),
                sidecarEmpty.get(),
                shadowReadEnabled);
    }

    public record Stats(
            long totalCalls,
            long shadowCalls,
            long parityMatches,
            long parityMismatches,
            long sidecarErrors,
            long sidecarEmpty,
            boolean shadowReadEnabled) {}
}
