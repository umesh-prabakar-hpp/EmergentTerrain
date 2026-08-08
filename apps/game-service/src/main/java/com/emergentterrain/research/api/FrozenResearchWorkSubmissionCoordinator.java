package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.springframework.stereotype.Service;

/** Game-side handoff seam; selection remains outside this coordinator until whole-cycle parity is approved. */
@Service
public class FrozenResearchWorkSubmissionCoordinator {
    private final GameResearchWorkMapper mapper;
    private final ResearchWorkOutboxService outbox;

    public FrozenResearchWorkSubmissionCoordinator(GameResearchWorkMapper mapper, ResearchWorkOutboxService outbox) {
        this.mapper = mapper;
        this.outbox = outbox;
    }

    public void submit(FrozenResearchWorkSubmission submission) {
        outbox.enqueueReserved(submission.request(), submission.reservation());
    }

    /** Relays only an already observed queue decision; this method never reads or mutates the queue. */
    public void submitFrozenConjecture(ResearchWorkRequest request, ResearchConjectureQueueSelection selection) {
        submit(mapper.frozenConjectureSubmission(request, selection));
    }

    /** Relays a selection reproduced from an already frozen queue snapshot; it never reads a live queue. */
    public void submitFrozenConjectureFromSnapshot(ResearchWorkRequest request,
                                                    ResearchConjectureQueueSnapshot snapshot) {
        submit(mapper.frozenConjectureSubmissionFromSnapshot(request, snapshot));
    }

    /** Persists either branch of a frozen snapshot observation without querying or mutating a live queue. */
    public void submitFrozenQueueSnapshot(ResearchWorkRequest request, ResearchConjectureQueueSnapshot snapshot) {
        FrozenResearchQueueSnapshotPreparation preparation = mapper.frozenQueueSnapshotPreparation(request, snapshot);
        if (preparation.sourceReservation().isPresent()) {
            outbox.enqueueReserved(preparation.request(), preparation.sourceReservation().get());
        } else {
            outbox.enqueue(preparation.request());
        }
    }
}
