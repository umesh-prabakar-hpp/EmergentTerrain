package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 6 — the legacy fixed-step policy: next scale
 * is {@code currentX × defaultScaleFactor} (the ×10 the loop used before the seam).
 * Selected when {@code frontierAdaptiveScaleEnabled} is off, so behavior is
 * unchanged by default.
 */
public class DefaultNextScalePolicy implements NextScalePolicy {

    @Override
    public long nextScale(NextScaleContext ctx) {
        long next = ctx.currentX() * ctx.defaultScaleFactor();
        return next <= ctx.currentX() ? ctx.currentX() + 1 : next;   // overflow guard
    }
}
