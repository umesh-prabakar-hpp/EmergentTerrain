package com.emergentterrain.research;

import java.util.List;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * Phase 16 P7 — topic-clone scheduler backoff.
 *
 * <p>When a (domain, topic) pair has accumulated REJECTED published papers in
 * the room's recent history, this utility lets the topic selector
 * probabilistically resample. The probability of *keeping* the originally
 * selected topic is {@code 0.5 ^ recentRejections}, so:
 * <ul>
 *   <li>0 rejections → keep (1.0)</li>
 *   <li>1 rejection → keep with p=0.5, resample with p=0.5</li>
 *   <li>2 rejections → keep with p=0.25</li>
 *   <li>3 rejections → keep with p=0.125</li>
 *   <li>4 rejections → keep with p=0.0625, etc.</li>
 * </ul>
 *
 * <p>Targets the divisor-sum-average cluster surfaced in REVIEW_REPORT
 * refresh #3: P-a328-534, P-539, P-553 were all REJECTED for the same
 * {@code (1/N)} vs {@code (1/N²)} convergence-fabrication pattern, yet the
 * scheduler kept re-picking the topic because nothing in the picker
 * reflected the failure history.
 *
 * <p>Stateless: rejection counts are derived from the room's recent
 * {@code ResearchPaper} list at call time. The "recent rolling window" is
 * implicit in the {@code recentPapers} the caller passes — typically the
 * top-N most recently published papers (default 5 per the wishlist scoping).
 *
 * <p>The actual resampling loop lives in the caller (e.g. {@code
 * ResearchCycleService}) because resampling requires re-running domain-aware
 * topic selection logic that this utility doesn't own. We expose {@link
 * #applyBackoff} as a higher-order helper that takes a {@code resample}
 * supplier; this keeps the utility decoupled from {@link
 * MathPhysicsEngine#selectTopicIndex}.
 */
public final class TopicCloneBackoff {

    private TopicCloneBackoff() {}

    /**
     * Count REJECTED papers in {@code recentPapers} matching the given
     * {@code (domain, topicName)} pair. Case-insensitive on both. Null or
     * blank statuses, papers with mismatched domain/topic, and papers in
     * any non-REJECTED status are excluded.
     */
    public static int countRecentRejections(List<ResearchPaper> recentPapers,
                                            String domain, String topicName) {
        if (recentPapers == null || recentPapers.isEmpty()) return 0;
        if (domain == null || topicName == null) return 0;
        int n = 0;
        for (ResearchPaper p : recentPapers) {
            if (p == null) continue;
            if (!"REJECTED".equalsIgnoreCase(p.status())) continue;
            if (p.domain() == null || !p.domain().equalsIgnoreCase(domain)) continue;
            if (p.topic() == null  || !p.topic().equalsIgnoreCase(topicName)) continue;
            n++;
        }
        return n;
    }

    /**
     * Probability the topic should be kept given {@code rejectionCount} prior
     * rejections. Returns {@code 0.5 ^ rejectionCount}, clamped to [0, 1].
     * A rejection count of 0 returns 1.0 (always keep); the function
     * decays geometrically with each additional rejection.
     */
    public static double keepProbability(int rejectionCount) {
        if (rejectionCount <= 0) return 1.0;
        return Math.pow(0.5, rejectionCount);
    }

    /**
     * 2026-06-08 (review rec — density-family down-weight) — yield-aware keep
     * probability. Generalizes the rejection backoff to a SECOND, gentler
     * cooling signal: a topic that keeps producing low-value NULL_RESULTs is
     * exhausted even though it never gets REJECTED. The density predicate
     * family (isAbundant/isPrime/isHappy) nulls almost every cycle at peer
     * 0.3–0.7 while conjecture-bound topics publish their refutations — so
     * counting low-yield nulls cools the floor without touching the
     * productive topics.
     *
     * <p>{@code 0.5^rejections · nullDecay^lowYieldNulls}. A single honest
     * null is fine (the system VALUES honest negatives), so {@code nullDecay}
     * (default 0.7) cools more slowly than a rejection: one null → keep 0.7,
     * three → 0.34, five → 0.17 — explore a topic, but move on once it's
     * repeatedly dry.
     */
    public static double keepProbability(int rejectionCount, int lowYieldNullCount,
                                         double nullDecay) {
        double p = keepProbability(Math.max(0, rejectionCount));
        if (lowYieldNullCount > 0) {
            p *= Math.pow(nullDecay, lowYieldNullCount);
        }
        return p;
    }

    /**
     * Count NULL_RESULT papers for {@code (domain, topicName)} whose peer score
     * is at or below {@code peerCeiling} — the "low-yield null" signal. A
     * high-peer null (a sharp, well-argued negative) is NOT low-yield and is
     * excluded, so the backoff only cools the genuinely dry topics.
     */
    public static int countRecentLowYieldNulls(List<ResearchPaper> recentPapers,
                                               String domain, String topicName,
                                               double peerCeiling) {
        if (recentPapers == null || recentPapers.isEmpty()) return 0;
        if (domain == null || topicName == null) return 0;
        int n = 0;
        for (ResearchPaper p : recentPapers) {
            if (p == null) continue;
            if (!"NULL_RESULT".equalsIgnoreCase(p.status())) continue;
            if (p.peerScore() > peerCeiling) continue;
            if (p.domain() == null || !p.domain().equalsIgnoreCase(domain)) continue;
            if (p.topic() == null  || !p.topic().equalsIgnoreCase(topicName)) continue;
            n++;
        }
        return n;
    }

    /**
     * Apply the backoff to a candidate topic index. Returns the original
     * {@code initialTopicIndex} when the topic should be kept, or invokes
     * {@code resample} up to {@code maxRetries} times when the keep-roll
     * fails. Each retry counts rejections for the new topic; if every
     * candidate is cooled, the last sampled value is returned (the bot
     * proceeds with the cooled topic rather than blocking).
     *
     * <p>{@code rng} is used for the keep-vs-resample decisions only — the
     * resample supplier owns its own RNG state.
     *
     * @param initialTopicIndex the topic index produced by the caller's
     *        primary selection logic
     * @param initialTopicName  the topic name the caller resolved from
     *        {@code initialTopicIndex} (used to count rejections against)
     * @param domain            the domain the topic belongs to
     * @param recentPapers      the room's recent papers
     * @param resample          a supplier that picks a new topic index AND
     *        its resolved name; called only when a resample is decided.
     *        The returned record is treated as a fresh candidate (with its
     *        own rejection count looked up).
     * @param maxRetries        upper bound on resample calls (≥ 0; 0 → no
     *        backoff, equivalent to returning {@code initialTopicIndex})
     * @param rng               source of randomness for the keep-roll
     * @return the final topic index after backoff
     */
    public static int applyBackoff(int initialTopicIndex,
                                   String initialTopicName,
                                   String domain,
                                   List<ResearchPaper> recentPapers,
                                   ResampleSupplier resample,
                                   int maxRetries,
                                   Random rng) {
        if (maxRetries <= 0 || resample == null || rng == null) return initialTopicIndex;

        int currentIndex     = initialTopicIndex;
        String currentTopic  = initialTopicName;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            int rejections = countRecentRejections(recentPapers, domain, currentTopic);
            double keepProb = keepProbability(rejections);
            if (rng.nextDouble() < keepProb) return currentIndex;
            // Decided to resample. If we've used all retries, return the
            // current candidate even though the keep-roll failed — never
            // block on an entirely cooled domain.
            if (attempt == maxRetries) return currentIndex;
            TopicCandidate next = resample.next();
            if (next == null) return currentIndex;
            currentIndex = next.index();
            currentTopic = next.name();
        }
        return currentIndex;
    }

    /**
     * 2026-06-08 — yield-aware backoff: same resample loop, but the keep-roll
     * combines rejections AND low-yield nulls ({@link #keepProbability(int,
     * int, double)}). When {@code peerCeiling}/{@code nullDecay} make the null
     * term a no-op (e.g. nullDecay=1.0), behaviour is identical to the
     * rejection-only {@link #applyBackoff}. Returns the index AND whether a
     * resample happened (so the caller can log only real moves).
     */
    public static int applyYieldAwareBackoff(int initialTopicIndex,
                                             String initialTopicName,
                                             String domain,
                                             List<ResearchPaper> recentPapers,
                                             ResampleSupplier resample,
                                             int maxRetries,
                                             double peerCeiling,
                                             double nullDecay,
                                             Random rng) {
        if (maxRetries <= 0 || resample == null || rng == null) return initialTopicIndex;
        int currentIndex    = initialTopicIndex;
        String currentTopic = initialTopicName;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            int rejections = countRecentRejections(recentPapers, domain, currentTopic);
            int lowYield = countRecentLowYieldNulls(recentPapers, domain, currentTopic, peerCeiling);
            double keepProb = keepProbability(rejections, lowYield, nullDecay);
            if (rng.nextDouble() < keepProb) return currentIndex;
            if (attempt == maxRetries) return currentIndex;
            TopicCandidate next = resample.next();
            if (next == null) return currentIndex;
            currentIndex = next.index();
            currentTopic = next.name();
        }
        return currentIndex;
    }

    /**
     * A topic candidate — index + resolved name. The caller's resample
     * function returns one of these per invocation. Kept as a record so
     * the contract is clear: a candidate carries enough information for
     * the next rejection-count lookup without re-invoking domain logic.
     */
    public record TopicCandidate(int index, String name) {}

    /**
     * Supplier-style interface so callers can plug in any topic-resampling
     * implementation (genome-weighted, uniform-over-domain, planner-driven,
     * etc.) without this utility taking a hard dependency on
     * {@link MathPhysicsEngine}.
     */
    @FunctionalInterface
    public interface ResampleSupplier {
        TopicCandidate next();
    }

    /** Returns true when {@code rejectionCount} would trigger an immediate-fall-through cool-down. */
    static boolean wouldAlmostCertainlyResample(int rejectionCount) {
        return rejectionCount >= 3;  // p_keep ≤ 0.125; expected to resample 7 of 8 times
    }

    /**
     * Convenience adapter for cases where the resample logic is identical
     * to the initial-selection logic and the caller just needs to retry it
     * up to {@code maxRetries} times. {@code indexSupplier} returns the
     * topic index; {@code nameResolver} maps the index back to its
     * canonical name (e.g. {@link MathPhysicsEngine#topicName}).
     */
    public static ResampleSupplier fromSuppliers(IntSupplier indexSupplier,
                                                 java.util.function.IntFunction<String> nameResolver) {
        return () -> {
            int idx = indexSupplier.getAsInt();
            return new TopicCandidate(idx, nameResolver.apply(idx));
        };
    }
}
