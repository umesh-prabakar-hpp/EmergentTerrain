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
 * @param lastFailedX        the scale that just failed to compute (OOM/timeout/…), or 0;
 *                           lets the policy pick a reachable target instead of re-leaping
 * @param lastFailureReason  why {@code lastFailedX} failed (sandbox status / discrepancy),
 *                           or null. Surfaced to the LLM so it adjusts — we INFORM, we do
 *                           NOT clamp or suppress its choice (frontier-escalation-loop.md §10)
 */
public record NextScaleContext(long currentX, double margin, double computeFrontierX,
                               long defaultScaleFactor, List<EscalationCheckpoint> history,
                               long lastFailedX, String lastFailureReason) {

    public NextScaleContext {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Back-compatible constructor (no failure feedback). */
    public NextScaleContext(long currentX, double margin, double computeFrontierX,
                            long defaultScaleFactor, List<EscalationCheckpoint> history) {
        this(currentX, margin, computeFrontierX, defaultScaleFactor, history, 0, null);
    }

    /** Back-compatible constructor (no history — the simple policies don't use it). */
    public NextScaleContext(long currentX, double margin, double computeFrontierX, long defaultScaleFactor) {
        this(currentX, margin, computeFrontierX, defaultScaleFactor, List.of(), 0, null);
    }
}
