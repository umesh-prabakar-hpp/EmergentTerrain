package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 P7 — unit tests for the topic-clone scheduler backoff utility.
 * Each test is anchored either to a refresh-#3 corpus failure mode (the
 * divisor-sum-average cluster P-a328-534/539/553) or to a pure-math case
 * verifying the geometric-decay keep probability.
 */
class TopicCloneBackoffTest {

    // -------- countRecentRejections --------

    @Test
    void p16_p7_countsRejectedMatchingDomainAndTopic() {
        // The corpus-canonical case: three REJECTED divisor-sum-average
        // papers (P-534, 539, 553) all on (MATHEMATICS, Divisor Sum Average).
        List<ResearchPaper> history = List.of(
                paper("P-a328-534", "MATHEMATICS", "Divisor Sum Average", "REJECTED"),
                paper("P-a328-539", "MATHEMATICS", "Divisor Sum Average", "REJECTED"),
                paper("P-a328-553", "MATHEMATICS", "Divisor Sum Average", "REJECTED"),
                paper("P-a328-552", "MATHEMATICS", "collatz AND prime", "PUBLISHED"));

        assertThat(TopicCloneBackoff.countRecentRejections(
                history, "MATHEMATICS", "Divisor Sum Average"))
                .isEqualTo(3);
    }

    @Test
    void p16_p7_excludesNonRejectedStatuses() {
        // PUBLISHED, NULL_RESULT, NEEDS_REVISION, DISPUTED — none count.
        List<ResearchPaper> history = List.of(
                paper("P-1", "MATHEMATICS", "T", "PUBLISHED"),
                paper("P-2", "MATHEMATICS", "T", "NULL_RESULT"),
                paper("P-3", "MATHEMATICS", "T", "NEEDS_REVISION"),
                paper("P-4", "MATHEMATICS", "T", "DISPUTED"));
        assertThat(TopicCloneBackoff.countRecentRejections(
                history, "MATHEMATICS", "T")).isZero();
    }

    @Test
    void p16_p7_excludesDifferentDomainOrTopic() {
        List<ResearchPaper> history = List.of(
                paper("P-1", "MATHEMATICS", "OtherTopic", "REJECTED"),
                paper("P-2", "PHYSICS", "T", "REJECTED"),
                paper("P-3", "MATHEMATICS", "T", "REJECTED"));
        assertThat(TopicCloneBackoff.countRecentRejections(
                history, "MATHEMATICS", "T")).isEqualTo(1);
    }

    @Test
    void p16_p7_caseInsensitiveOnDomainAndTopic() {
        List<ResearchPaper> history = List.of(
                paper("P-1", "mathematics", "divisor sum average", "REJECTED"));
        assertThat(TopicCloneBackoff.countRecentRejections(
                history, "MATHEMATICS", "Divisor Sum Average")).isEqualTo(1);
    }

    @Test
    void p16_p7_nullOrEmptyHistoryReturnsZero() {
        assertThat(TopicCloneBackoff.countRecentRejections(null, "M", "T")).isZero();
        assertThat(TopicCloneBackoff.countRecentRejections(List.of(), "M", "T")).isZero();
    }

    // -------- keepProbability --------

    @Test
    void p16_p7_zeroRejectionsAlwaysKeeps() {
        assertThat(TopicCloneBackoff.keepProbability(0)).isEqualTo(1.0);
        assertThat(TopicCloneBackoff.keepProbability(-1)).isEqualTo(1.0);
    }

    @Test
    void p16_p7_geometricDecayPerRejection() {
        assertThat(TopicCloneBackoff.keepProbability(1)).isEqualTo(0.5);
        assertThat(TopicCloneBackoff.keepProbability(2)).isEqualTo(0.25);
        assertThat(TopicCloneBackoff.keepProbability(3)).isEqualTo(0.125);
        assertThat(TopicCloneBackoff.keepProbability(4)).isEqualTo(0.0625);
        // Strictly positive at high rejection counts; never returns 0
        // even for absurd inputs.
        assertThat(TopicCloneBackoff.keepProbability(20)).isPositive();
    }

    // -------- applyBackoff --------

    @Test
    void p16_p7_zeroRetriesIsNoBackoff() {
        // Even with rejection history, maxRetries=0 means the function
        // returns the initial topic unchanged — caller opted out.
        List<ResearchPaper> history = List.of(
                paper("P-1", "M", "T", "REJECTED"),
                paper("P-2", "M", "T", "REJECTED"));

        TopicCloneBackoff.ResampleSupplier resample =
                () -> new TopicCloneBackoff.TopicCandidate(99, "Other");
        int result = TopicCloneBackoff.applyBackoff(
                5, "T", "M", history, resample, 0, new Random(0L));
        assertThat(result).isEqualTo(5);
    }

    @Test
    void p16_p7_zeroRejectionsKeepsInitial() {
        // No rejections → 100% keep, regardless of RNG.
        TopicCloneBackoff.ResampleSupplier resample =
                () -> new TopicCloneBackoff.TopicCandidate(99, "Other");
        int result = TopicCloneBackoff.applyBackoff(
                7, "T", "M", List.of(), resample, 3, new Random(0L));
        assertThat(result).isEqualTo(7);
    }

    @Test
    void p16_p7_resamplesOnRejectionAndTakesFreshCandidate() {
        // Two rejections on the initial topic → keep prob 0.25. With an
        // RNG that rolls high first (>0.25), we should resample. Set up
        // the resample to deterministically return a fresh, uncooled
        // topic so applyBackoff exits on the next iteration.
        List<ResearchPaper> history = List.of(
                paper("P-1", "M", "Divisor Sum Average", "REJECTED"),
                paper("P-2", "M", "Divisor Sum Average", "REJECTED"));

        TopicCloneBackoff.ResampleSupplier resample =
                () -> new TopicCloneBackoff.TopicCandidate(42, "Stern-Brocot");
        // RNG seed chosen so first .nextDouble() ≥ 0.25 (resample), and
        // second .nextDouble() rolls within keepProb(0) = 1.0 (keep).
        Random rng = new Random(123L) {
            private final AtomicInteger calls = new AtomicInteger(0);
            @Override public double nextDouble() {
                return calls.getAndIncrement() == 0 ? 0.9 : 0.5;
            }
        };
        int result = TopicCloneBackoff.applyBackoff(
                5, "Divisor Sum Average", "M", history, resample, 3, rng);
        assertThat(result).isEqualTo(42);
    }

    @Test
    void p16_p7_maxRetriesAcceptsCooledCandidate() {
        // Every resample returns a topic that's also cooled. After maxRetries
        // attempts, applyBackoff returns the current candidate rather than
        // looping forever — never block on a fully cooled domain.
        List<ResearchPaper> history = List.of(
                paper("P-1", "M", "Cooled-A", "REJECTED"),
                paper("P-2", "M", "Cooled-A", "REJECTED"),
                paper("P-3", "M", "Cooled-B", "REJECTED"),
                paper("P-4", "M", "Cooled-B", "REJECTED"));

        // Resample alternates between cooled topics.
        AtomicInteger callCount = new AtomicInteger();
        TopicCloneBackoff.ResampleSupplier resample = () -> {
            int n = callCount.getAndIncrement();
            return n % 2 == 0
                    ? new TopicCloneBackoff.TopicCandidate(2, "Cooled-B")
                    : new TopicCloneBackoff.TopicCandidate(1, "Cooled-A");
        };
        // RNG that always rolls high → always resample.
        Random rng = new Random() {
            @Override public double nextDouble() { return 0.999; }
        };
        int result = TopicCloneBackoff.applyBackoff(
                1, "Cooled-A", "M", history, resample, 3, rng);
        // We made it through the retry budget; the returned index is one
        // of the cooled candidates (1 or 2 in this test).
        assertThat(result).isIn(1, 2);
    }

    @Test
    void p16_p7_nullResampleSupplierReturnsInitial() {
        int result = TopicCloneBackoff.applyBackoff(
                5, "T", "M", List.of(), null, 3, new Random());
        assertThat(result).isEqualTo(5);
    }

    @Test
    void p16_p7_resampleReturningNullKeepsCurrent() {
        List<ResearchPaper> history = List.of(
                paper("P-1", "M", "T", "REJECTED"));
        TopicCloneBackoff.ResampleSupplier nullResample = () -> null;
        Random rng = new Random() {
            @Override public double nextDouble() { return 0.99; }  // always resample
        };
        int result = TopicCloneBackoff.applyBackoff(
                5, "T", "M", history, nullResample, 3, rng);
        assertThat(result).isEqualTo(5);
    }

    private static ResearchPaper paper(String paperId, String domain, String topic, String status) {
        return new ResearchPaper(
                paperId, UUID.randomUUID(), "bot-1",
                domain, topic,
                "title", "hyp", "THEORETICAL", "findings", "conclusion",
                List.of("rev-1"), 0.42, status,
                100L, Instant.now(), 0, 0.0,
                "test-model", "test:path");
    }
}
