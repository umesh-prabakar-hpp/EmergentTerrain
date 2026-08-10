package com.emergentterrain.research.conjecture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 23.4 — decides whether a paper-writing cycle should be sourced
 * from the mined conjecture queue (vs. the catalog topic-weight roll).
 *
 * <p>Sits between {@link ConjectureQueue} (the queue) and the
 * upstream paper-generation pipeline (which lives in
 * {@code ResearchCycleService}). The wiring on the upstream side comes
 * with 23.5 — the {@code verify_conjecture} tool — because the full
 * acceptance (mechanism section + adversarial check) requires that tool
 * to exist. This bean ships now so the wiring change in 23.5 is
 * a one-line call rather than a feature flag plus a new bean.
 *
 * <p>Config flag {@code app.research.conjectureMiner.paperShare}
 * (default {@code 0.0}) is the probability that a given cycle pulls
 * from the queue. {@code 1.0} = every paper from the queue (test mode);
 * {@code 0.0} = never (default, current behavior).
 *
 * <p>{@link ConjectureQueue} is consumed as an {@link Optional} so the
 * bean stays available when the miner's own {@code @ConditionalOnProperty}
 * gate is off. Spring auto-injects {@code Optional.empty()} when no bean
 * matches; in that case {@link #selectConjecture} short-circuits regardless
 * of {@code paperShare}.
 */
@Component
public class ConjectureTopicProvider {

    private static final Logger log = LoggerFactory.getLogger(ConjectureTopicProvider.class);
    /** Mirrors the generic frozen queue contract's bounded audit surface. */
    public static final int MAX_QUEUE_OBSERVATION_CANDIDATES = 64;

    private final Optional<ConjectureQueue> queue;
    private final double paperShare;
    /**
     * 2026-05-29 — allow-substrate gate. Only these (lowercase) substrate keys
     * may source a conjecture paper; a cycle whose mapped substrate
     * ({@link #conjectureDomainKey}) is not in this set falls through to the
     * catalog path. Pinned to {@code math} today (the only substrate the miner
     * populates), which focuses testing on the math substrate. Add
     * {@code physics}/{@code materials} here as Phase 23.7 substrates come
     * online, enabling them one at a time. Config:
     * {@code app.research.conjectureMiner.allowedSubstrates} (CSV).
     */
    private final Set<String> allowedSubstrates;

    public ConjectureTopicProvider(
            Optional<ConjectureQueue> queue,
            @Value("${app.research.conjectureMiner.paperShare:0.0}") double paperShare,
            @Value("${app.research.conjectureMiner.allowedSubstrates:math}") String allowedSubstratesCsv) {
        this.queue = queue;
        if (paperShare < 0.0 || paperShare > 1.0) {
            throw new IllegalArgumentException(
                    "app.research.conjectureMiner.paperShare must be in [0,1]; got " + paperShare);
        }
        this.paperShare = paperShare;
        this.allowedSubstrates = Arrays.stream(allowedSubstratesCsv.split(","))
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        log.info("ConjectureTopicProvider: paperShare={} allowedSubstrates={} queuePresent={}",
                paperShare, allowedSubstrates, queue.isPresent());
    }

    /**
     * Phase 30 Wave 7 — 30.6: a promoted anomaly awaiting its
     * UNEXPLAINED_OBSERVATION investigation, or empty. No {@code paperShare}
     * roll — a gate-surviving anomaly is rare by construction and self-limiting
     * (one paper per promotion stamp; see the miner's {@code nextPromotedAnomaly}).
     * Same substrate gate as ordinary conjecture selection.
     */
    public Optional<Conjecture> selectPromotedAnomaly(String domain) {
        if (queue.isEmpty()) {
            return Optional.empty();
        }
        String substrate = conjectureDomainKey(domain);
        if (!allowedSubstrates.contains(substrate)) {
            return Optional.empty();
        }
        return queue.get().nextPromotedAnomaly(substrate);
    }

    /**
     * Roll the paperShare probability; if it fires AND the queue is
     * present AND non-empty, return the next conjecture. Otherwise return
     * empty (caller falls back to the catalog path).
     */
    public Optional<Conjecture> selectConjecture(String domain, Random rng) {
        if (paperShare <= 0.0 || queue.isEmpty()) {
            return Optional.empty();
        }
        String substrate = conjectureDomainKey(domain);
        if (!allowedSubstrates.contains(substrate)) {   // allow-substrate gate
            return Optional.empty();
        }
        if (rng.nextDouble() >= paperShare) {
            return Optional.empty();
        }
        return queue.get().next(substrate);
    }

    /**
     * Phase 30 Wave 0 — mark a selected conjecture as investigated (consumption),
     * delegating to the underlying queue so {@link ConjectureMinerService#nextN}
     * can rotate away from it. No-op when no queue is wired.
     */
    public void markInvestigated(Conjecture conjecture) {
        queue.ifPresent(q -> q.markInvestigated(conjecture));
    }

    /**
     * Consumes an exact, previously observed source for the Phase 42 transactional handoff.
     * This method never reads or selects from the queue; callers must supply the already
     * observed candidate and hold the surrounding game transaction.
     */
    public void consumeObservedConjecture(Conjecture conjecture) {
        if (conjecture == null || conjecture.id() == null) {
            throw new IllegalArgumentException("A consumed conjecture must have a persistent source id");
        }
        queue.orElseThrow(() -> new IllegalStateException(
                "Cannot consume an observed conjecture because no queue is configured"))
                .markInvestigated(conjecture);
    }

    /**
     * Captures the legacy promoted-first / ordinary queue inputs without rolling, selecting, or
     * consuming them. This is an audit seam for Phase 42; callers must still leave live routing
     * disabled until selection and consumption are atomically parity-proven.
     */
    public Optional<QueueObservation> observeQueue(String domain, int candidateLimit) {
        if (candidateLimit < 1 || candidateLimit > MAX_QUEUE_OBSERVATION_CANDIDATES) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and "
                    + MAX_QUEUE_OBSERVATION_CANDIDATES);
        }
        if (queue.isEmpty()) return Optional.empty();
        String substrate = conjectureDomainKey(domain);
        if (!allowedSubstrates.contains(substrate)) return Optional.empty();
        Optional<Conjecture> promoted = queue.get().nextPromotedAnomaly(substrate);
        List<Conjecture> ordinary = queue.get().nextN(substrate, candidateLimit);
        if (promoted.isPresent()) {
            String promotedId = sourceId(promoted.get());
            ordinary = ordinary.stream().filter(candidate -> !promotedId.equals(sourceId(candidate))).toList();
        }
        return Optional.of(new QueueObservation(substrate, paperShare,
                promoted.map(List::of).orElseGet(List::of), ordinary));
    }

    private static String sourceId(Conjecture conjecture) {
        return conjecture.id() == null ? "transient:" + System.identityHashCode(conjecture)
                : conjecture.id().toString();
    }

    /**
     * 2026-05-29 — bridge the engine domain (e.g. "MATHEMATICS", as carried by
     * Researcher.getDomain() and passed in by ResearchCycleService) to the
     * conjecture-store domain key (e.g. "math", ConjectureMinerService.DOMAIN).
     * The miner persists conjectures under the lowercase substrate key; the
     * research cycle passes the uppercase engine domain, so without this
     * translation next() queries the wrong key (findByDomain is exact-match)
     * and always returns empty — making the conjecture path unreachable on
     * every cycle regardless of paperShare. Physics/materials are pre-wired for
     * Phase 23.7; their queues stay empty (→ catalog fallback) until those
     * substrates exist.
     */
    static String conjectureDomainKey(String engineDomain) {
        if (engineDomain == null) return "";
        return switch (engineDomain.toUpperCase()) {
            case "MATHEMATICS" -> "math";
            case "PHYSICS"     -> "physics";
            case "MATERIALS"   -> "materials";
            default            -> engineDomain.toLowerCase();
        };
    }

    public double paperShare() {
        return paperShare;
    }

    /** The allow-substrate gate set (lowercase substrate keys). */
    public Set<String> allowedSubstrates() {
        return allowedSubstrates;
    }

    /** Immutable, game-local queue observation. It is not a queue reservation or consumption record. */
    public record QueueObservation(String substrate, double ordinarySelectionProbability,
                                   List<Conjecture> promotedCandidates, List<Conjecture> ordinaryCandidates) {
        public QueueObservation {
            promotedCandidates = promotedCandidates == null ? List.of() : List.copyOf(promotedCandidates);
            ordinaryCandidates = ordinaryCandidates == null ? List.of() : List.copyOf(ordinaryCandidates);
        }
    }
}
