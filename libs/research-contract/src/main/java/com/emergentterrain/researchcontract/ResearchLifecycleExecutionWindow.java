package com.emergentterrain.researchcontract;

import java.util.List;

/** Bounded, ordered lifecycle execution journal attached to one game snapshot. */
public record ResearchLifecycleExecutionWindow(String epoch, long firstSequence, long latestSequence,
                                              List<ResearchLifecycleExecution> events) {
    public ResearchLifecycleExecutionWindow {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
