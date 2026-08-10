package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 23.4 contract tests for {@link ConjectureTopicProvider}.
 *
 * <p>The provider lives between the queue and the upstream paper pipeline.
 * Wiring on the upstream side ships with 23.5 — these tests pin the bean's
 * gate semantics so the eventual one-line caller behaves as documented.
 */
class ConjectureTopicProviderTest {

    @Test
    void zeroPaperShareNeverSelects() {
        ConjectureQueue miner = mock(ConjectureQueue.class);
        ConjectureTopicProvider provider = build(miner, 0.0);

        // Run with many seeds — must always return empty.
        for (int seed = 0; seed < 50; seed++) {
            assertFalse(provider.selectConjecture("math", new Random(seed)).isPresent(),
                    "share=0.0 must never select");
        }
        // And the miner should never have been consulted (we short-circuit).
        verify(miner, never()).next(any());
    }

    @Test
    void fullPaperShareAlwaysSelectsWhenQueueNonEmpty() {
        Conjecture c = new Conjecture("math", "a", "b", 0.0, 0.5);
        ConjectureQueue miner = mock(ConjectureQueue.class);
        when(miner.next("math")).thenReturn(Optional.of(c));

        ConjectureTopicProvider provider = build(miner, 1.0);

        for (int seed = 0; seed < 20; seed++) {
            Optional<Conjecture> picked = provider.selectConjecture("math", new Random(seed));
            assertTrue(picked.isPresent(), "share=1.0 must always select");
            assertEquals("a", picked.get().lhs());
        }
    }

    @Test
    void fullPaperShareReturnsEmptyWhenQueueEmpty() {
        ConjectureQueue miner = mock(ConjectureQueue.class);
        when(miner.next("math")).thenReturn(Optional.empty());

        ConjectureTopicProvider provider = build(miner, 1.0);

        // Even with share=1.0, an empty queue gives back Optional.empty()
        // so the caller can fall back to the catalog path.
        assertFalse(provider.selectConjecture("math", new Random(0)).isPresent());
    }

    @Test
    void shortCircuitsWhenMinerBeanAbsent() {
        // ObjectProvider.getIfAvailable returns null when the miner's
        // @ConditionalOnProperty gate is off — provider must treat that
        // gracefully and not blow up.
        ConjectureTopicProvider provider = buildNoMiner(1.0);
        assertFalse(provider.selectConjecture("math", new Random(0)).isPresent());
    }

    @Test
    void halfShareFiresAboutHalfTheTime() {
        Conjecture c = new Conjecture("math", "a", "b", 0.0, 0.5);
        ConjectureQueue miner = mock(ConjectureQueue.class);
        when(miner.next("math")).thenReturn(Optional.of(c));

        ConjectureTopicProvider provider = build(miner, 0.5);

        int hits = 0;
        int trials = 1000;
        Random rng = new Random(42);  // fixed seed → deterministic test
        for (int i = 0; i < trials; i++) {
            if (provider.selectConjecture("math", rng).isPresent()) hits++;
        }
        // 0.5 ± 0.05 is comfortably 5σ for binomial n=1000.
        assertTrue(hits > 425 && hits < 575,
                "share=0.5 should fire ~500/1000 times; got " + hits);
    }

    @Test
    void rejectsOutOfRangeShare() {
        assertThrows(IllegalArgumentException.class, () -> build(mock(ConjectureQueue.class), -0.01));
        assertThrows(IllegalArgumentException.class, () -> build(mock(ConjectureQueue.class), 1.01));
    }

    @Test
    void capturesABoundedReadOnlyQueueObservationWithoutConsumption() {
        Conjecture promoted = conjecture("promoted");
        Conjecture ordinary = conjecture("ordinary");
        ConjectureQueue queue = mock(ConjectureQueue.class);
        when(queue.nextPromotedAnomaly("math")).thenReturn(Optional.of(promoted));
        when(queue.nextN("math", 2)).thenReturn(List.of(promoted, ordinary));

        var observation = build(queue, 0.35).observeQueue("MATHEMATICS", 2);

        assertTrue(observation.isPresent());
        assertEquals("math", observation.get().substrate());
        assertEquals(0.35, observation.get().ordinarySelectionProbability());
        assertEquals(List.of(promoted), observation.get().promotedCandidates());
        assertEquals(List.of(ordinary), observation.get().ordinaryCandidates());
        verify(queue).nextPromotedAnomaly("math");
        verify(queue).nextN("math", 2);
        verify(queue, never()).markInvestigated(any());
    }

    @Test
    void doesNotObserveUnavailableOrDisallowedQueues() {
        ConjectureQueue queue = mock(ConjectureQueue.class);

        assertFalse(buildNoMiner(1.0).observeQueue("MATHEMATICS", 1).isPresent());
        assertFalse(new ConjectureTopicProvider(Optional.of(queue), 1.0, "physics")
                .observeQueue("MATHEMATICS", 1).isPresent());
        verify(queue, never()).nextPromotedAnomaly(any());
        verify(queue, never()).nextN(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsAnObservationLimitOutsideTheBoundedContractSurface() {
        ConjectureTopicProvider provider = build(mock(ConjectureQueue.class), 1.0);

        assertThrows(IllegalArgumentException.class, () -> provider.observeQueue("MATHEMATICS", 0));
        assertThrows(IllegalArgumentException.class, () -> provider.observeQueue("MATHEMATICS",
                ConjectureTopicProvider.MAX_QUEUE_OBSERVATION_CANDIDATES + 1));
    }

    // ---------- helpers ----------

    private static ConjectureTopicProvider build(ConjectureQueue queue, double share) {
        return new ConjectureTopicProvider(Optional.of(queue), share, "math");
    }

    private static ConjectureTopicProvider buildNoMiner(double share) {
        return new ConjectureTopicProvider(Optional.empty(), share, "math");
    }

    private static Conjecture conjecture(String lhs) {
        return new Conjecture("math", lhs, "rhs", 1.0, 0.0);
    }
}
