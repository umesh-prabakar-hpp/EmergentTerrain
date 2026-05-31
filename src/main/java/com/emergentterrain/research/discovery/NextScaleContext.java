package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 6 — what a {@link NextScalePolicy} sees to
 * choose the next scale.
 *
 * @param currentX           the scale just tested
 * @param margin             headroom in [0,1]: 1 = far below the bound (lots of
 *                           room → leap), 0 = at/over the bound (refine)
 * @param computeFrontierX   the predicted compute frontier x_c (0 if unknown);
 *                           informational — the loop's stall check still gates it
 * @param defaultScaleFactor the task's fixed multiplier (fallback)
 */
public record NextScaleContext(long currentX, double margin, double computeFrontierX, long defaultScaleFactor) {}
