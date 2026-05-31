package com.emergentterrain.research.discovery;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 frontier-escalation Phase 1 — what to escalate. The bound being
 * tested and the ≤NT_MAX_N value source are passed separately to
 * {@link FrontierEscalationService#escalate} (they carry behavior, not data), so
 * this stays a plain data record. {@code spec}/{@code checkpoints}/{@code anchors}
 * are forwarded into the {@link DiscoveryTask} when a rung lands beyond NT_MAX_N.
 *
 * @param spec        the quantity {@code candidate(x)} must compute (for discovery)
 * @param startScale  first x to test (typically near NT_MAX_N)
 * @param scaleFactor multiplier per rung (e.g. 10)
 * @param checkpoints discovery overlap checkpoints (1..NT_MAX_N)
 * @param anchors     discovery beyond-range anchors
 * @param cpuSeconds  per-candidate sandbox CPU budget
 * @param wallSeconds per-candidate sandbox wall budget
 */
public record EscalationTask(
        String spec,
        long startScale,
        long scaleFactor,
        List<Long> checkpoints,
        Map<Long, Long> anchors,
        int cpuSeconds,
        int wallSeconds) {

    public EscalationTask {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
        if (startScale < 1) {
            startScale = 1;
        }
        if (scaleFactor < 2) {
            scaleFactor = 10;
        }
    }
}
