package com.emergentterrain.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, priority-aware concurrency limiter for one LLM provider pool
 * ({@link GenericOpenAICompatPool}). Room b72c4e10 throughput fix — two problems:
 *
 * <ul>
 *   <li><b>(A) bounded wait.</b> {@link #acquire} waits at most {@code maxWait} for a permit, then
 *       throws, instead of blocking indefinitely. A blocked LLM call must never stall the
 *       synchronous research cycle: a sandbox intent-review once blocked ~38 min on an unbounded
 *       {@code Semaphore.acquire()} (all permits held by a civic-simulation LLM flood), and that
 *       single stall produced 0 papers in a 37-minute run.</li>
 *   <li><b>(B) priority reservation.</b> Non-priority calls (no {@code LlmCallContext} role — the
 *       civic simulation, background work) are additionally capped by a second semaphore sized
 *       {@code parallelism-1}, so they can hold at most all-but-one permit. At least one main permit
 *       is therefore always reachable by a priority call (the research/paper pipeline, which tags
 *       its calls with a role), so a non-priority flood can no longer lock the pipeline out of the
 *       pool.</li>
 * </ul>
 *
 * <p>Pure concurrency primitive — no LLM, HTTP, or Spring dependency — so the reservation and
 * bounding invariants are unit-testable in isolation.
 */
final class LlmConcurrencyLimiter {

    private final Semaphore slots;
    private final Semaphore nonPriorityCap;
    private final long maxWaitNanos;

    LlmConcurrencyLimiter(int parallelism, Duration maxWait) {
        int p = Math.max(1, parallelism);
        this.slots = new Semaphore(p);
        // Reserve >=1 main permit for priority calls. With p=1 the cap is 1 (degenerate: priority and
        // non-priority share the single slot — nothing to reserve, but never zero permits).
        this.nonPriorityCap = new Semaphore(Math.max(1, p - 1));
        this.maxWaitNanos = Math.max(1L, maxWait.toNanos());
    }

    /** A held permit. Call {@link #release} exactly once, in a {@code finally}. */
    final class Permit {
        private final boolean cappedHeld;

        private Permit(boolean cappedHeld) {
            this.cappedHeld = cappedHeld;
        }

        void release() {
            slots.release();
            if (cappedHeld) {
                nonPriorityCap.release();
            }
        }
    }

    /**
     * Acquire a permit, waiting at most {@code maxWait}. A priority call takes a main permit
     * directly; a non-priority call first takes a {@link #nonPriorityCap} permit (so non-priority
     * concurrency can never consume the last main permit). Throws {@link RuntimeException} on
     * timeout or interrupt — the caller treats that as a fast failure, never an indefinite block.
     */
    Permit acquire(boolean priority) {
        boolean cappedHeld = false;
        if (!priority) {
            await(nonPriorityCap, "non-priority cap");
            cappedHeld = true;
        }
        try {
            await(slots, "slot");
        } catch (RuntimeException e) {
            if (cappedHeld) {
                nonPriorityCap.release();
            }
            throw e;
        }
        return new Permit(cappedHeld);
    }

    private void await(Semaphore sem, String what) {
        try {
            if (!sem.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                throw new RuntimeException("timed out acquiring " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted acquiring " + what, e);
        }
    }

    /** Test/observability: main permits currently available. */
    int availableSlots() {
        return slots.availablePermits();
    }

    /** Test/observability: non-priority permits currently available. */
    int availableNonPriorityPermits() {
        return nonPriorityCap.availablePermits();
    }
}
