package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.emergentterrain.llm.SystemResourcesGate;

/**
 * Phase 17 — unit tests for {@link GateRetryQueue}. Covers enqueue
 * acceptance/rejection, oldest-first dispatch, max-attempts give-up,
 * gate-respect, capacity cap, callback wiring, and the give-up stamp
 * format. Standing up the bean directly (no Spring context) keeps the
 * tests fast and isolates the queue logic from the narration pipeline.
 */
class GateRetryQueueTest {

    private static PaperDraft draft(String paperId) {
        // Minimal PaperDraft — most fields are irrelevant to the queue logic;
        // GateRetryQueue only reads paperId. Fields ordered per the record's
        // canonical constructor.
        return new PaperDraft(
                UUID.randomUUID(),                 // roomId
                UUID.randomUUID().toString(),      // authorBotId
                "PHYSICS",                          // domain
                "Test Topic",                       // topicName
                "Test Title",                       // title
                "Test findings text.",              // rawFindings
                "Test hypothesis text.",            // templateHypothesis
                "Test conclusion text.",            // templateConclusion
                "EMPIRICAL",                        // methodology
                List.of(),                          // reviewerBotIds
                0.5,                                 // peerScore
                0.6,                                 // authorScore
                0.0,                                 // anomalyZScore
                0L,                                  // gameTick
                java.time.Instant.now(),            // publishedAt
                1,                                   // generation
                paperId,                             // paperId
                Integer.valueOf(1),                  // param1
                Double.valueOf(1.0),                 // param2
                false,                               // isFollowUp
                null,                                // predictedScore
                null,                                // priorMethodology
                List.of(),                           // recentPapers
                null,                                // researchHypothesis
                null,                                // preRegistration
                null,                                // resultClassification
                null);                               // plannerQuestionId
    }

    private static NarrationResult firstPass(String callPath) {
        return new NarrationResult(
                "Test hypothesis.",
                "Test findings.",
                "Test conclusion.",
                null,                                // followUp
                "test-model",                        // aiModel
                0.5,                                 // confidenceScore
                List.of(),                           // citedPaperIds
                callPath);
    }

    private static GateRetryQueue enabledQueue(SystemResourcesGate gate) {
        return new GateRetryQueue(true, 3, 100, gate);
    }

    private static GateRetryQueue disabledQueue() {
        return new GateRetryQueue(false, 3, 100, null);
    }

    @Test
    void disabledQueueRejectsEnqueue() {
        GateRetryQueue q = disabledQueue();
        boolean accepted = q.enqueue(draft("P-1"), firstPass("fp"), List.of(), "professor");
        assertThat(accepted).isFalse();
        assertThat(q.queueSize()).isZero();
    }

    @Test
    void enabledQueueAcceptsFirstEnqueue() {
        GateRetryQueue q = enabledQueue(null);
        boolean accepted = q.enqueue(draft("P-1"), firstPass("fp"), List.of("fp"), "professor");
        assertThat(accepted).isTrue();
        assertThat(q.queueSize()).isEqualTo(1);
        assertThat(q.contains("P-1")).isTrue();
    }

    @Test
    void enqueueIsIdempotentOnPaperId() {
        GateRetryQueue q = enabledQueue(null);
        boolean first  = q.enqueue(draft("P-1"), firstPass("fp1"), List.of(), "professor");
        boolean second = q.enqueue(draft("P-1"), firstPass("fp2"), List.of(), "professor");
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(q.queueSize()).isEqualTo(1);
    }

    @Test
    void enqueueRejectsAtCapacity() {
        GateRetryQueue q = new GateRetryQueue(true, 3, 2, null);
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.enqueue(draft("P-2"), firstPass(""), List.of(), "professor");
        boolean third = q.enqueue(draft("P-3"), firstPass(""), List.of(), "professor");
        assertThat(third).isFalse();
        assertThat(q.queueSize()).isEqualTo(2);
    }

    @Test
    void enqueueRejectsNullDraft() {
        GateRetryQueue q = enabledQueue(null);
        assertThat(q.enqueue(null, firstPass(""), List.of(), "professor")).isFalse();
    }

    @Test
    void enqueueSnapshotsCallPath() {
        // A mutable callPath passed by reference must not bleed later mutations
        // into the queued entry (the narrator's downstream stamps would
        // contaminate the give-up record otherwise).
        GateRetryQueue q = enabledQueue(null);
        List<String> mutablePath = new ArrayList<>(List.of("fp", "audit:4b:0gaps", "gate:resources:professor"));
        q.enqueue(draft("P-1"), firstPass(""), mutablePath, "professor");
        mutablePath.add("contamination-after-enqueue");
        GateRetryQueue.Entry e = q.peek("P-1");
        assertThat(e.callPath()).containsExactly("fp", "audit:4b:0gaps", "gate:resources:professor");
    }

    @Test
    void markCompletedRemovesEntry() {
        GateRetryQueue q = enabledQueue(null);
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        assertThat(q.contains("P-1")).isTrue();
        q.markCompleted("P-1");
        assertThat(q.contains("P-1")).isFalse();
        assertThat(q.queueSize()).isZero();
    }

    @Test
    void markCompletedOnMissingPaperIsSafe() {
        GateRetryQueue q = enabledQueue(null);
        q.markCompleted("P-does-not-exist");  // no throw
        assertThat(q.queueSize()).isZero();
    }

    @Test
    void tickSkipsWhenDisabled() {
        GateRetryQueue q = disabledQueue();
        AtomicInteger dispatchCount = new AtomicInteger();
        q.wireCallbacks(d -> dispatchCount.incrementAndGet(), (d, n) -> {});
        q.tick();
        assertThat(dispatchCount.get()).isZero();
    }

    @Test
    void tickSkipsWhenQueueEmpty() {
        GateRetryQueue q = enabledQueue(null);
        AtomicInteger dispatchCount = new AtomicInteger();
        q.wireCallbacks(d -> dispatchCount.incrementAndGet(), (d, n) -> {});
        q.tick();
        assertThat(dispatchCount.get()).isZero();
    }

    @Test
    void tickRespectsGate() {
        SystemResourcesGate gate = mock(SystemResourcesGate.class);
        when(gate.isOpen()).thenReturn(false);
        GateRetryQueue q = enabledQueue(gate);
        AtomicInteger dispatchCount = new AtomicInteger();
        q.wireCallbacks(d -> dispatchCount.incrementAndGet(), (d, n) -> {});
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.tick();
        assertThat(dispatchCount.get()).isZero();
        assertThat(q.queueSize()).isEqualTo(1);
    }

    @Test
    void tickSkipsWhenCallbacksNotWired() {
        GateRetryQueue q = enabledQueue(null);
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.tick();  // no NPE
        assertThat(q.queueSize()).isEqualTo(1);
    }

    @Test
    void tickDispatchesOldestEntry() {
        GateRetryQueue q = enabledQueue(null);
        AtomicReference<PaperDraft> dispatched = new AtomicReference<>();
        q.wireCallbacks(dispatched::set, (d, n) -> {});
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.enqueue(draft("P-2"), firstPass(""), List.of(), "professor");
        q.tick();
        // P-1 was enqueued first → lastAttemptAt=EPOCH → oldest → dispatched first.
        assertThat(dispatched.get()).isNotNull();
        assertThat(dispatched.get().paperId()).isEqualTo("P-1");
    }

    @Test
    void tickBumpsAttemptBeforeDispatch() {
        GateRetryQueue q = enabledQueue(null);
        // Capture the attempts value as observed at dispatch time.
        AtomicInteger observed = new AtomicInteger(-1);
        q.wireCallbacks(d -> observed.set(q.peek(d.paperId()).attempts()), (d, n) -> {});
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.tick();
        assertThat(observed.get()).isEqualTo(1);
    }

    @Test
    void tickGivesUpAfterMaxAttempts() {
        GateRetryQueue q = new GateRetryQueue(true, 2, 100, null);
        AtomicInteger dispatchCount = new AtomicInteger();
        AtomicReference<NarrationResult> givenUp = new AtomicReference<>();
        q.wireCallbacks(d -> dispatchCount.incrementAndGet(), (d, n) -> givenUp.set(n));
        q.enqueue(draft("P-1"), firstPass("fp:llama → gate:resources:professor"), List.of(), "professor");

        q.tick();  // attempt 1
        q.tick();  // attempt 2 — both still ≤ max, dispatch each time
        q.tick();  // attempts (2) >= max (2) → give up, no dispatch

        assertThat(dispatchCount.get()).isEqualTo(2);
        assertThat(givenUp.get()).isNotNull();
        assertThat(givenUp.get().callPath()).contains("gate-retry:gave-up:attempts=2");
        assertThat(q.queueSize()).isZero();
    }

    @Test
    void giveUpStampPreservesOriginalCallPath() {
        NarrationResult original = firstPass("fp:llama → audit:4b:0gaps → gate:resources:professor");
        NarrationResult stamped = GateRetryQueue.appendGiveUpStamp(original, 3);
        assertThat(stamped.callPath()).isEqualTo(
                "fp:llama → audit:4b:0gaps → gate:resources:professor → "
                        + "gate-retry:gave-up:attempts=3");
        // Other fields unchanged.
        assertThat(stamped.hypothesis()).isEqualTo(original.hypothesis());
        assertThat(stamped.findings()).isEqualTo(original.findings());
        assertThat(stamped.conclusion()).isEqualTo(original.conclusion());
    }

    @Test
    void giveUpStampHandlesNullCallPath() {
        NarrationResult original = firstPass(null);
        NarrationResult stamped = GateRetryQueue.appendGiveUpStamp(original, 2);
        assertThat(stamped.callPath()).isEqualTo("gate-retry:gave-up:attempts=2");
    }

    @Test
    void giveUpStampHandlesNullFirstPass() {
        assertThat(GateRetryQueue.appendGiveUpStamp(null, 1)).isNull();
    }

    @Test
    void dispatchExceptionDoesNotPropagate() {
        // A failing renarrate (e.g. router unavailable) must not kill the
        // scheduler thread — the entry stays in the queue for the next tick.
        GateRetryQueue q = enabledQueue(null);
        Consumer<PaperDraft> failing = d -> { throw new RuntimeException("router down"); };
        BiConsumer<PaperDraft, NarrationResult> noop = (d, n) -> {};
        q.wireCallbacks(failing, noop);
        q.enqueue(draft("P-1"), firstPass(""), List.of(), "professor");
        q.tick();  // does not throw
        assertThat(q.contains("P-1")).isTrue();
        assertThat(q.peek("P-1").attempts()).isEqualTo(1);
    }
}
