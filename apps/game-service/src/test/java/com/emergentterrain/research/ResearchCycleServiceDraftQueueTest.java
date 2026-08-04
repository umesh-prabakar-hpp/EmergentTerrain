package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durable-fix coverage for the draft-backlog bookkeeping in
 * {@link ResearchCycleService}. The backlog gate (UniversityService.maybeTick)
 * skips new research cycles once {@code draftQueueSize() >= maxDraftBacklog}, so
 * a leaked slot dead-locks all research (observed 2026-05-25: 3 drafts narrated,
 * then hung post-audit before publishPaper; the slot was pinned forever and the
 * room produced zero new papers for 25+ minutes).
 *
 * <p>The fix tracks in-flight drafts in an idempotent map keyed by paperId and
 * adds a stuck-draft reaper. These tests pin both properties. No Mockito — the
 * service has a no-arg constructor and the queue methods touch no other bean.
 */
class ResearchCycleServiceDraftQueueTest {

    @Test
    void releaseIsIdempotent_backlogNeverGoesNegative() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.markDraftInFlight("P-1");
        svc.markDraftInFlight("P-2");
        assertThat(svc.draftQueueSize()).isEqualTo(2);

        svc.decrementDraftQueue("P-1");
        assertThat(svc.draftQueueSize()).isEqualTo(1);

        // Double-release of the same draft — publishPaper AND the narration
        // task's finally both fire for a normally-completing draft — must not
        // drive the backlog below the true in-flight count.
        svc.decrementDraftQueue("P-1");
        svc.decrementDraftQueue("P-1");
        assertThat(svc.draftQueueSize()).isEqualTo(1);

        // Releasing an unknown or null paperId is a harmless no-op.
        svc.decrementDraftQueue("never-enqueued");
        svc.decrementDraftQueue(null);
        assertThat(svc.draftQueueSize()).isEqualTo(1);
    }

    @Test
    void markIsIdempotentPerPaperId() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.markDraftInFlight("P-1");
        svc.markDraftInFlight("P-1"); // same id re-marked → still one slot
        assertThat(svc.draftQueueSize()).isEqualTo(1);
        svc.markDraftInFlight(null);  // null → no-op
        assertThat(svc.draftQueueSize()).isEqualTo(1);
    }

    @Test
    void shutdownSnapshotKeepsLastKnownPhaseUntilTheDraftSettles() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.markDraftInFlight("P-42");
        svc.updateDraftPhase("P-42", "narration:professor_resample:2");

        assertThat(svc.inFlightDraftSnapshots()).singleElement().satisfies(draft -> {
            assertThat(draft.paperId()).isEqualTo("P-42");
            assertThat(draft.phase()).isEqualTo("narration:professor_resample:2");
        });

        svc.decrementDraftQueue("P-42");
        assertThat(svc.inFlightDraftSnapshots()).isEmpty();
    }

    @Test
    void reaperReclaimsOnlyDraftsOlderThanTtl() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.markDraftInFlight("P-1");
        svc.markDraftInFlight("P-2");
        svc.markDraftInFlight("P-3");
        assertThat(svc.draftQueueSize()).isEqualTo(3);

        // TTL larger than any possible age → nothing reaped (legitimately-slow
        // narration, e.g. the 5-minute refinement calls, is not killed).
        assertThat(svc.reapStuckDrafts(Long.MAX_VALUE)).isZero();
        assertThat(svc.draftQueueSize()).isEqualTo(3);

        // TTL of -1 → every age (>= 0) exceeds it → all reaped; the backlog
        // self-heals to 0 so research can resume.
        assertThat(svc.reapStuckDrafts(-1L)).isEqualTo(3);
        assertThat(svc.draftQueueSize()).isZero();

        // Reaping an empty map is a no-op.
        assertThat(svc.reapStuckDrafts(-1L)).isZero();
    }
}
