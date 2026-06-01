package com.emergentterrain.research.discovery;

import java.util.List;
import java.util.Map;

/**
 * Phase 30 fork 2b Phase 3 — a self-verifying numeric-method discovery task.
 * Domain-neutral on purpose (the per-invariant gate that fills {@code spec} is
 * Phase 4); carries everything {@link MertensDiscoveryService} needs to run the
 * generate→validate→repair loop and the sidecar {@code /discover/validate} call.
 *
 * @param spec        what {@code candidate(x)} must compute, in plain words
 *                    (e.g. "M(x) = sum of the Möbius function mu(n) for n=1..x")
 * @param target      the value we want (may exceed the oracle's NT_MAX_N range)
 * @param checkpoints overlap-range points (1..NT_MAX_N) judged against the oracle
 * @param anchors     beyond-range external truth (e.g. {10_000_000 → 1037})
 * @param cpuSeconds  per-candidate CPU budget in the sandbox
 * @param wallSeconds per-candidate wall budget in the sandbox
 * @param maxAttempts cap on generate→validate→repair rounds (>= 1)
 * @param quantity    which trusted oracle the sidecar validates against —
 *                    {@code "mertens"} (cumulative μ → M(x)) or {@code "divisor"}
 *                    (cumulative d(n) → D(x)). Defaults to {@code "mertens"}.
 */
public record DiscoveryTask(
        String spec,
        long target,
        List<Long> checkpoints,
        Map<Long, Long> anchors,
        int cpuSeconds,
        int wallSeconds,
        int maxAttempts,
        String quantity) {

    public DiscoveryTask {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (cpuSeconds < 1) {
            cpuSeconds = 10;
        }
        if (wallSeconds < 1) {
            wallSeconds = 15;
        }
        quantity = (quantity == null || quantity.isBlank()) ? "mertens" : quantity;
    }

    /** Back-compatible constructor — defaults {@code quantity} to {@code "mertens"}. */
    public DiscoveryTask(String spec, long target, List<Long> checkpoints, Map<Long, Long> anchors,
                         int cpuSeconds, int wallSeconds, int maxAttempts) {
        this(spec, target, checkpoints, anchors, cpuSeconds, wallSeconds, maxAttempts, "mertens");
    }
}
