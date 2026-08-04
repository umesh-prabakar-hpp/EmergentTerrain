package com.emergentterrain.research;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.emergentterrain.llm.SystemResourcesGate;

/**
 * Phase 17 D3-ext follow-on — retry queue for papers whose professor or
 * revision stage was killed by {@link SystemResourcesGate} during host
 * pressure. The first-attempt path (in
 * {@code ResearchNarrationService.maybeCritiqueAndReviseWithTools}) defers
 * the publish by returning null when this queue accepts the draft; the
 * existing {@code publishPaper(draft, null)} handler discards cleanly
 * (no DB row, no disk write, no side-effects). The queue then re-dispatches
 * the draft when the resources gate recovers, up to {@code max-attempts}
 * times. On give-up, the queue publishes the cached first-pass as
 * NEEDS_REVISION so the paper is still visible — same content the
 * pre-Phase-17 code would have shipped immediately, just delayed and
 * stamped with {@code gate-retry:gave-up:attempts=N}.
 *
 * <h2>Why in-memory</h2>
 * Each entry holds an in-flight {@link PaperDraft} that the planner has
 * already accounted for; persisting it would duplicate the planner's own
 * state. JVM restart loses the queue, which is acceptable — the
 * dropped drafts get re-narrated fresh on the next planner cycle since
 * the draft was never persisted. The durable record of a give-up paper
 * is the persisted NEEDS_REVISION row written by the give-up branch.
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>Entries keyed by paperId in a {@link ConcurrentHashMap} — enqueue
 *       and tick safely interleave.</li>
 *   <li>Tick is single-threaded by Spring's scheduler (default pool of 1
 *       for {@code @Scheduled}) — one retry dispatch per interval prevents
 *       re-flooding the gate.</li>
 *   <li>Capacity cap is best-effort: a brief over-capacity window during
 *       concurrent enqueues is tolerated; the next tick prunes.</li>
 * </ul>
 *
 * <h2>Loop prevention</h2>
 * <ul>
 *   <li>{@code attempts} is incremented <em>before</em> the dispatch,
 *       so a retry that hits the gate again still consumes one attempt.</li>
 *   <li>The retry's narration runs with a fresh callPath; if it re-hits
 *       {@code gate:resources:professor} it tries to enqueue again — the
 *       paperId-keyed map makes that a no-op until the existing entry is
 *       either completed or given up.</li>
 *   <li>Successful retry publish calls {@link #markCompleted(String)} via
 *       the renarration hook in {@code ResearchNarrationService.narratePaper},
 *       which removes the entry before the next tick sees it.</li>
 * </ul>
 */
@Component
public class GateRetryQueue {

    private static final Logger log = LoggerFactory.getLogger(GateRetryQueue.class);

    /**
     * Single entry in the retry queue. Immutable; tick replaces by `put`.
     * The {@code seq} field is a monotonic sequence number assigned at
     * enqueue (and bumped on each dispatch) so {@link #pickOldest()} has
     * a deterministic FIFO ordering even when many entries land in the
     * same {@link Instant} — {@code Instant.now()} can repeat at
     * sub-millisecond resolution on fast hardware.
     */
    record Entry(
            PaperDraft draft,
            NarrationResult firstPass,
            List<String> callPath,
            int attempts,
            Instant lastAttemptAt,
            long seq,
            String stage) {
        Entry withAttempt(int newAttempts, Instant when, long newSeq) {
            return new Entry(draft, firstPass, callPath, newAttempts, when, newSeq, stage);
        }
    }

    private final boolean enabled;
    private final int maxAttempts;
    private final int maxQueueSize;
    private final SystemResourcesGate resourcesGate;

    private final ConcurrentMap<String, Entry> queue = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong seqCounter =
            new java.util.concurrent.atomic.AtomicLong();
    private final AtomicReference<Consumer<PaperDraft>> retryDispatch = new AtomicReference<>();
    private final AtomicReference<BiConsumer<PaperDraft, NarrationResult>> giveUpPublisher =
            new AtomicReference<>();

    public GateRetryQueue(
            @Value("${app.research.gate-retry.enabled:false}") boolean enabled,
            @Value("${app.research.gate-retry.max-attempts:3}") int maxAttempts,
            @Value("${app.research.gate-retry.max-queue-size:100}") int maxQueueSize,
            @Autowired(required = false) SystemResourcesGate resourcesGate) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.resourcesGate = resourcesGate;
        log.info("GateRetryQueue: enabled={} maxAttempts={} maxQueueSize={} resourcesGateWired={}",
                enabled, maxAttempts, maxQueueSize, resourcesGate != null);
    }

    /**
     * Wire the renarrate callback and the give-up publisher. Called by
     * {@code ResearchNarrationService} after both beans exist (same pattern
     * as the university-narration callback wiring). The retry tick is a
     * safe no-op until both callbacks are set.
     */
    public void wireCallbacks(Consumer<PaperDraft> retryDispatch,
                              BiConsumer<PaperDraft, NarrationResult> giveUpPublisher) {
        this.retryDispatch.set(retryDispatch);
        this.giveUpPublisher.set(giveUpPublisher);
        log.info("GateRetryQueue: callbacks wired (retryDispatch + giveUpPublisher)");
    }

    /**
     * Try to enqueue a gate-skipped draft. Returns true when accepted —
     * caller should then suppress the immediate publish (return null from
     * the narration pipeline) so the retry path owns the publish decision.
     * Returns false when the queue is disabled, at capacity, or already
     * holds this paperId — caller falls back to its existing
     * publish-as-NEEDS_REVISION path.
     */
    public boolean enqueue(PaperDraft draft, NarrationResult firstPass,
                            List<String> callPath, String stage) {
        if (!enabled || draft == null || draft.paperId() == null) return false;
        if (queue.size() >= maxQueueSize) {
            log.warn("GateRetryQueue: at capacity {} — dropping enqueue for paperId={} stage={}",
                    maxQueueSize, draft.paperId(), stage);
            return false;
        }
        // Snapshot the callPath so later mutations in the narrator don't
        // affect the give-up stamp content.
        List<String> snapshot = callPath != null ? List.copyOf(callPath) : List.of();
        Entry candidate = new Entry(draft, firstPass, snapshot, 0, Instant.now(),
                seqCounter.incrementAndGet(), stage);
        Entry prior = queue.putIfAbsent(draft.paperId(), candidate);
        if (prior != null) {
            log.debug("GateRetryQueue: paperId={} already queued (attempts={}) — enqueue no-op",
                    draft.paperId(), prior.attempts());
            return false;
        }
        log.info("GateRetryQueue: enqueued paperId={} stage={} queueSize={}",
                draft.paperId(), stage, queue.size());
        return true;
    }

    /**
     * Called after the narration pipeline successfully publishes a retried
     * draft. Removes the entry so the next tick doesn't redispatch.
     * Safe no-op when the paperId isn't in the queue (the common case for
     * fresh, non-retry narration completions).
     */
    public void markCompleted(String paperId) {
        Entry removed = queue.remove(paperId);
        if (removed != null) {
            log.info("GateRetryQueue: completed paperId={} after {} attempt(s) stage={}",
                    paperId, removed.attempts(), removed.stage());
        }
    }

    @Scheduled(fixedDelayString = "${app.research.gate-retry.poll-interval-ms:30000}",
            initialDelay = 30_000L)
    public void tick() {
        if (!enabled) return;
        if (queue.isEmpty()) return;
        if (resourcesGate != null && !resourcesGate.isOpen()) {
            log.debug("GateRetryQueue: tick — gate not open, skipping (queueSize={})", queue.size());
            return;
        }
        Consumer<PaperDraft> dispatch = retryDispatch.get();
        BiConsumer<PaperDraft, NarrationResult> giveUp = giveUpPublisher.get();
        if (dispatch == null || giveUp == null) {
            log.debug("GateRetryQueue: tick — callbacks not yet wired, skipping");
            return;
        }
        Entry oldest = pickOldest();
        if (oldest == null) return;

        // Already met the cap on prior ticks — release this entry via the
        // give-up branch so the paper still gets to disk.
        if (oldest.attempts() >= maxAttempts) {
            queue.remove(oldest.draft().paperId());
            publishGiveUp(oldest, giveUp);
            return;
        }

        // Bump attempts BEFORE dispatch so a retry that re-hits the gate
        // still consumes one attempt (the gate re-fire path calls enqueue
        // again, which is a no-op because we're still in the map).
        // Refresh the seq number too so this entry moves to the back of
        // the FIFO line — fair sharing if multiple entries are queued.
        Entry bumped = oldest.withAttempt(oldest.attempts() + 1, Instant.now(),
                seqCounter.incrementAndGet());
        queue.put(oldest.draft().paperId(), bumped);
        log.info("GateRetryQueue: dispatching retry paperId={} attempt={}/{} stage={}",
                oldest.draft().paperId(), bumped.attempts(), maxAttempts, oldest.stage());
        try {
            dispatch.accept(oldest.draft());
        } catch (Throwable t) {
            log.warn("GateRetryQueue: retry dispatch threw for paperId={} attempt={}: {}",
                    oldest.draft().paperId(), bumped.attempts(), t.toString());
            // Don't propagate — the scheduler thread must survive. If the
            // entry's still in the map next tick we'll try again or give up.
        }
    }

    /**
     * Publish the cached first-pass as NEEDS_REVISION on give-up. Append a
     * {@code gate-retry:gave-up:attempts=N} stamp to the call_path so the
     * persisted call_path explains why the paper is on disk despite the
     * gate-skip stamps it carries.
     */
    private void publishGiveUp(Entry entry,
                                BiConsumer<PaperDraft, NarrationResult> giveUp) {
        log.warn("GateRetryQueue: giving up paperId={} stage={} after {} attempts — "
                        + "publishing cached first-pass as NEEDS_REVISION",
                entry.draft().paperId(), entry.stage(), entry.attempts());
        try {
            NarrationResult stamped = appendGiveUpStamp(entry.firstPass(), entry.attempts());
            giveUp.accept(entry.draft(), stamped);
        } catch (Throwable t) {
            log.error("GateRetryQueue: give-up publish failed paperId={}: {}",
                    entry.draft().paperId(), t.toString());
        }
    }

    /**
     * Append a give-up marker to the cached first-pass's call_path.
     * Returns a new {@link NarrationResult} — the original is treated as
     * immutable for safety.
     */
    static NarrationResult appendGiveUpStamp(NarrationResult firstPass, int attempts) {
        if (firstPass == null) return null;
        String original = firstPass.callPath();
        String stamp = "gate-retry:gave-up:attempts=" + attempts;
        String combined = (original == null || original.isBlank())
                ? stamp
                : original + " → " + stamp;
        return firstPass.withCallPath(combined);
    }

    private Entry pickOldest() {
        return queue.values().stream()
                .min(Comparator.comparingLong(Entry::seq))
                .orElse(null);
    }

    // ---- Package-private accessors for tests ----

    int queueSize() { return queue.size(); }

    boolean contains(String paperId) { return queue.containsKey(paperId); }

    Entry peek(String paperId) { return queue.get(paperId); }
}
