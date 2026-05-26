package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ResearchNarrationService#runSupervised} — the wall-clock cap that
 * keeps a hung narration stage (observed in the professor-tools tool loop) from
 * parking a draft until the stuck-draft reaper reclaims it ~10 min later. On
 * timeout the work must be interrupted (so the thread + HTTP connection unwind)
 * and the backlog slot released; the slot must also be released on normal
 * completion and on failure. No Mockito — static helper + real virtual threads.
 */
class NarrationTimeoutTest {

    @Test
    void settlesOnceOnNormalCompletion() {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger ran = new AtomicInteger();
        var settled = new CopyOnWriteArrayList<String>();

        ResearchNarrationService.runSupervised(ex, ran::incrementAndGet, 5_000, "P-1", settled::add);

        assertThat(ran.get()).isEqualTo(1);
        assertThat(settled).containsExactly("P-1"); // slot released exactly once
    }

    @Test
    void interruptsHungWorkAndReleasesSlotOnTimeout() throws Exception {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        var settled = new CopyOnWriteArrayList<String>();
        Runnable hang = () -> {
            try {
                Thread.sleep(30_000);            // simulates a hung LLM/HTTP call
            } catch (InterruptedException ie) {
                interrupted.countDown();         // cancel(true) interrupted us
            }
        };

        long t0 = System.currentTimeMillis();
        ResearchNarrationService.runSupervised(ex, hang, 200, "P-2", settled::add);
        long elapsedMs = System.currentTimeMillis() - t0;

        assertThat(elapsedMs).isLessThan(5_000);                       // returned promptly, not after 30s
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();   // hung work was actually interrupted
        assertThat(settled).containsExactly("P-2");                    // slot released despite the hang
    }

    @Test
    void releasesSlotWhenWorkThrows() {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        var settled = new CopyOnWriteArrayList<String>();

        ResearchNarrationService.runSupervised(ex,
                () -> { throw new RuntimeException("boom"); }, 5_000, "P-3", settled::add);

        assertThat(settled).containsExactly("P-3"); // failure path still releases the slot
    }

    @Test
    void zeroTimeoutMeansNoTimeoutNotInstantKill() {
        // timeoutMs <= 0 disables the cap (waits indefinitely) — it must NOT
        // instant-timeout via get(0,...) and kill every narration.
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger ran = new AtomicInteger();
        var settled = new CopyOnWriteArrayList<String>();

        ResearchNarrationService.runSupervised(ex, ran::incrementAndGet, 0, "P-0", settled::add);

        assertThat(ran.get()).isEqualTo(1);          // work ran to completion, not killed
        assertThat(settled).containsExactly("P-0");
    }

    @Test
    void nullSettleCallbackIsTolerated() {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger ran = new AtomicInteger();
        // Before wireUniversityCallback runs, draftSettledCallback is null — must not NPE.
        ResearchNarrationService.runSupervised(ex, ran::incrementAndGet, 5_000, "P-4", null);
        assertThat(ran.get()).isEqualTo(1);
    }
}
