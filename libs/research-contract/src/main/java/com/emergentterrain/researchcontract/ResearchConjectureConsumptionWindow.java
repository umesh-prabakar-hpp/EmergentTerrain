package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.Objects;

/** Bounded, contiguous, read-only journal of completed legacy conjecture consumptions. */
public record ResearchConjectureConsumptionWindow(String epoch, long firstSequence, long latestSequence,
                                                  List<ResearchConjectureConsumptionEvent> events) {
    public ResearchConjectureConsumptionWindow {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("epoch is required");
        }
        events = events == null ? List.of() : List.copyOf(events);
        if (events.isEmpty()) {
            if (firstSequence != 0L || latestSequence != 0L) {
                throw new IllegalArgumentException("an empty window must use zero sequence bounds");
            }
        } else {
            if (firstSequence < 1L || latestSequence < firstSequence
                    || events.size() != latestSequence - firstSequence + 1L) {
                throw new IllegalArgumentException("window sequence bounds must be contiguous");
            }
            for (int index = 0; index < events.size(); index++) {
                ResearchConjectureConsumptionEvent event = Objects.requireNonNull(events.get(index), "event");
                if (event.sequence() != firstSequence + index) {
                    throw new IllegalArgumentException("window events must be sequence ordered");
                }
            }
        }
    }
}
