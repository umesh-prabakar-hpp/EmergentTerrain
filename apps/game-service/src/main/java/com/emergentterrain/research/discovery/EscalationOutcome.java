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
        StallPrediction prediction,
        PivotOutcome pivot,
        long localizedHi) {

    public EscalationOutcome {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    public static EscalationOutcome disabled() {
        return new EscalationOutcome(false, List.of(), "disabled", 0, null, null, 0);
    }

    public static EscalationOutcome counterexample(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "counterexample_found", frontier, null, null, 0);
    }

    /**
     * Counterexample localized by bisection — the bound holds up to
     * {@code frontierReached} (lo) and fails by {@code localizedHi}; the crossing
     * is in {@code (lo, hi]}. (For a non-monotone bound the function may re-cross
     * inside the bracket — this localizes the bracket boundary, not a proven first
     * failure.)
     */
    public static EscalationOutcome counterexampleLocalized(List<EscalationCheckpoint> cps, long lo, long hi) {
        return new EscalationOutcome(true, cps, "counterexample_localized", lo, null, null, hi);
    }

    public static EscalationOutcome stalled(List<EscalationCheckpoint> cps, long frontier) {
        return new EscalationOutcome(true, cps, "discovery_stalled", frontier, null, null, 0);
    }

    public static EscalationOutcome exhausted(List<EscalationCheckpoint> cps, long frontier, StallPrediction p) {
        return new EscalationOutcome(true, cps, "exhausted_rounds", frontier, p, null, 0);
    }

    /**
     * Phase 2 — the predictor anticipates the next rung will exceed the compute
     * budget, so the loop stops *before* wasting it. Used only when no branch
     * pivot is wired/enabled; otherwise {@link #pivoted} replaces this.
     */
    public static EscalationOutcome predictedStall(List<EscalationCheckpoint> cps, long frontier, StallPrediction p) {
        return new EscalationOutcome(true, cps, "predicted_stall", frontier, p, null, 0);
    }

    /**
     * Phase 3 — on a predicted stall the loop pivoted to a different branch. The
     * terminal state is {@code pivoted_<branch>} when a verifiable branch (B3/B4)
     * validated, or {@code pivot_boundary} when only the B5 analytic boundary
     * remained. The {@link PivotOutcome} carries the trust level verbatim.
     */
    public static EscalationOutcome pivoted(List<EscalationCheckpoint> cps, long frontier,
                                            StallPrediction p, PivotOutcome pivot) {
        String terminal = pivot != null && pivot.validated()
                ? "pivoted_" + pivot.branch().name().toLowerCase()
                : "pivot_boundary";
        return new EscalationOutcome(true, cps, terminal, frontier, p, pivot, 0);
    }
}
