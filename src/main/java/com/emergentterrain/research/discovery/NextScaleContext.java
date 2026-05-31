package com.emergentterrain.research.discovery;

import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 6 — what a {@link NextScalePolicy} sees to
 * choose the next scale. The next scale may go forward (escalate) or backward
 * (localize/densify); the loop guards cycles via a visited-set, not monotonicity.
 *
 * @param currentX           the scale just tested
 * @param margin             headroom in [0,1]: 1 = far below the bound (lots of
 *                           room → leap), 0 = at/over the bound (refine)
 * @param computeFrontierX   the predicted compute frontier x_c (0 if unknown);
 *                           informational — the loop's stall check still gates it
 * @param defaultScaleFactor the task's fixed multiplier (fallback)
 * @param history            the checkpoint corpus so far (for an LLM/adaptive policy
 *                           to reason over the trend); empty for the simple policies
 */
public record NextScaleContext(long currentX, double margin, double computeFrontierX,
                               long defaultScaleFactor, List<EscalationCheckpoint> history) {

    public NextScaleContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Back-compatible constructor (no history — the simple policies don't use it). */
    public NextScaleContext(long currentX, double margin, double computeFrontierX, long defaultScaleFactor) {
        this(currentX, margin, computeFrontierX, defaultScaleFactor, List.of());
    }
}
