package com.emergentterrain.research.discovery;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 frontier-escalation Phase 3 — the stall context handed to a branch
 * proposer: the quantity {@code spec}, the {@code target} scale that stalled, the
 * overlap {@code checkpoints}/{@code anchors} a proxy/model must validate against,
 * and the {@link StallPrediction} that triggered the pivot.
 */
public record PivotRequest(
        String spec,
        long target,
        List<Long> checkpoints,
        Map<Long, Long> anchors,
        StallPrediction prediction,
        List<EscalationCheckpoint> corpus) {

    public PivotRequest {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
        corpus = corpus == null ? List.of() : List.copyOf(corpus);
    }

    /** Back-compatible constructor (no corpus — B4 then has no data to model). */
    public PivotRequest(String spec, long target, List<Long> checkpoints,
                        Map<Long, Long> anchors, StallPrediction prediction) {
        this(spec, target, checkpoints, anchors, prediction, List.of());
    }
}
