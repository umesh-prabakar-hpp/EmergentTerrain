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
        long frontierReached) {

    public EscalationOutcome {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    public static EscalationOutcome disabled() {
        return new EscalationOutcome(false, List.of(), "disabled", 0);
    }

    public static EscalationOutcome counterexample(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "counterexample_found", frontier);
    }

    public static EscalationOutcome stalled(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "discovery_stalled", frontier);
    }

    public static EscalationOutcome exhausted(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "exhausted_rounds", frontier);
    }
}
