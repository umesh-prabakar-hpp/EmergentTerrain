package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 4 — renders an {@link EscalationOutcome} into
 * honest, trust-framed paper Findings (frontier-escalation-loop.md §5). Every
 * computed value is FULL trust; the terminal narrative names what happened; a
 * pivot result is reported with its {@link TrustLevel} verbatim (and a plain-words
 * gloss of what that trust means); and a regime caveat keeps "held" from reading as
 * "resolved". Pure — no value laundering across trust levels.
 */
public final class FrontierFindingsRenderer {

    private FrontierFindingsRenderer() {}

    public static String render(EscalationOutcome out, String quantity, String boundLabel) {
        if (out == null || !out.ran()) {
            return "_Frontier escalation did not run (disabled) — no findings._";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Findings — frontier escalation of ").append(boundLabel).append("\n\n");
        sb.append("The bound was escalated across scales (10× per round). Each computed value below is "
                + "**FULL trust** (exact): by the sieve for x ≤ 2,000,000, and by the runtime-discovery "
                + "moat (validated against ground truth on the overlap) beyond it.\n\n");

        sb.append("| x | ").append(quantity).append(" | |").append(quantity)
                .append("|/√x | verdict | source |\n|---|---|---|---|---|\n");
        for (EscalationCheckpoint c : out.checkpoints()) {
            String val = c.value() == null ? "—" : String.valueOf(c.value());
            String ratio = c.value() == null ? "—"
                    : String.format("%.4f", Math.abs((double) c.value()) / Math.sqrt((double) c.x()));
            sb.append("| ").append(c.x()).append(" | ").append(val).append(" | ").append(ratio)
                    .append(" | ").append(c.verdict().name().toLowerCase())
                    .append(" | ").append(c.source()).append(" |\n");
        }
        sb.append("\n**Terminal state — ").append(out.terminalState()).append(".** ")
                .append(terminalNarrative(out)).append("\n\n");

        if (out.prediction() != null && out.prediction().valid()) {
            sb.append("**Compute forecast.** ").append(out.prediction().basis()).append("\n\n");
        }
        if (out.pivot() != null) {
            sb.append(pivotSection(out.pivot()));
        }

        sb.append("**Regime caveat.** A bound holding across the computed scales is not a proof: "
                + "the scale at which such a bound is known (or expected) to fail can lie far beyond the "
                + "reach of any validated computation here. Values are reported as **computed** or "
                + "**modeled** with their trust level, never as a resolution of the conjecture.\n");
        return sb.toString();
    }

    private static String terminalNarrative(EscalationOutcome out) {
        long frontier = out.frontierReached();
        String last = out.checkpoints().isEmpty() ? "?"
                : String.valueOf(out.checkpoints().get(out.checkpoints().size() - 1).x());
        return switch (out.terminalState()) {
            case "counterexample_found" -> "A counterexample was found at x=" + last
                    + " — the bound FAILS there. This is the genuine result, not a non-result.";
            case "counterexample_localized" -> "The crossing was localized by bisection to the bracket "
                    + "(x=" + frontier + ", x=" + out.localizedHi() + "] — the bound holds at the lower "
                    + "end and fails at the upper. (For a non-monotone bound this is the bracket "
                    + "boundary, not a proven first failure.)";
            case "exhausted_rounds" -> "The bound HELD at every escalated scale, up to x=" + frontier
                    + " (the round cap was reached before any counterexample or stall).";
            case "discovery_stalled" -> "Computation could not be validated at x=" + last
                    + " (reactive stall); the validated frontier reached is x=" + frontier + ".";
            case "predicted_stall" -> "Escalation halted: the cost forecast predicts the next scale would "
                    + "exceed the compute budget. Validated frontier reached: x=" + frontier + ".";
            case "pivot_boundary" -> "Computation was forecast to stall beyond x=" + frontier
                    + "; no verifiable branch validated, so the analytic boundary was reached (see below).";
            default -> {
                if (out.terminalState().startsWith("pivoted_")) {
                    yield "Computation was forecast to stall beyond x=" + frontier
                            + "; the loop pivoted to a different branch (see below).";
                }
                yield "Reached state " + out.terminalState() + " with validated frontier x=" + frontier + ".";
            }
        };
    }

    private static String pivotSection(PivotOutcome pivot) {
        String gloss = switch (pivot.trust() == null ? TrustLevel.LITERATURE_ONLY : pivot.trust()) {
            case FULL -> "a moat-validated exact value";
            case VALIDATED_PROXY -> "an exact value of a tractable reformulation — full trust, but confirm "
                    + "it answers the intended question";
            case MODEL_FIT -> "an EXTRAPOLATION under a fitted model — not a computed value and not a proof; "
                    + "weigh it by the reported fit quality";
            case LITERATURE_ONLY -> "outside this harness's verification — ground any claim in cited "
                    + "literature; do not present it as derived here";
        };
        return "**Pivot — branch " + pivot.branch() + " (trust: " + pivot.trust() + ").** "
                + pivot.finding() + "\n\n> Trust gloss: " + gloss + ".\n\n";
    }
}
