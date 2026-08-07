package com.emergentterrain.research.api;

import org.springframework.stereotype.Service;

/** Game-side handoff seam; selection remains outside this coordinator until whole-cycle parity is approved. */
@Service
public class FrozenResearchWorkSubmissionCoordinator {
    private final ResearchWorkOutboxService outbox;

    public FrozenResearchWorkSubmissionCoordinator(ResearchWorkOutboxService outbox) {
        this.outbox = outbox;
    }

    public void submit(FrozenResearchWorkSubmission submission) {
        outbox.enqueueReserved(submission.request(), submission.reservation());
    }
}
