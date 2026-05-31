package com.emergentterrain.research.discovery;

import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 1 — the result of an escalation run: the
 * checkpoint corpus, a terminal state, and {@code frontierReached} (the largest
 * x at which the bound still HELD). Terminal states:
 * <ul>
 *   <li>{@code disabled} — the flag is off; nothing ran.</li>
 *   <li>{@code counterexample_found} — the bound failed; the real result.</li>
 *   <li>{@code discovery_stalled} — a value couldn't be computed (Phase-1 stop;
 *       the Phase-2 predictor will anticipate this and pivot instead).</li>
 *   <li>{@code exhausted_rounds} — hit the round cap with the bound still holding.</li>
 * </ul>
 */
public record EscalationOutcome(
        boolean ran,
        List<EscalationCheckpoint> checkpoints,
        String terminalState,
        long frontierReached,
        StallPrediction prediction) {

    public EscalationOutcome {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    public static EscalationOutcome disabled() {
        return new EscalationOutcome(false, List.of(), "disabled", 0, null);
    }

    public static EscalationOutcome counterexample(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "counterexample_found", frontier, null);
    }

    public static EscalationOutcome stalled(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "discovery_stalled", frontier, null);
    }

    public static EscalationOutcome exhausted(List<EscalationCheckpoint> cps, long frontier, StallPrediction p) {
        return new EscalationOutcome(true, cps, "exhausted_rounds", frontier, p);
    }

    /**
     * Phase 2 — the predictor anticipates the next rung will exceed the compute
     * budget, so the loop stops *before* wasting it (rather than grinding to a
     * reactive {@code discovery_stalled}). The Phase-3 pivot replaces the stop.
     */
    public static EscalationOutcome predictedStall(List<EscalationCheckpoint> cps, long frontier, StallPrediction p) {
        return new EscalationOutcome(true, cps, "predicted_stall", frontier, p);
    }
}
