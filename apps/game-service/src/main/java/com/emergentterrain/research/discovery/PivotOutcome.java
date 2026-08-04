package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 3 — the result of a branch pivot: which
 * branch was taken, at what trust level, whether it validated, and the finding
 * (trust-tagged text the narrator carries verbatim).
 */
public record PivotOutcome(Branch branch, TrustLevel trust, boolean validated, String finding) {

    public static PivotOutcome disabled() {
        return new PivotOutcome(null, null, false, "branch pivot disabled");
    }

    /**
     * The honest boundary (B5): computation stalled and no *verifiable* branch
     * validated, so the only path to the science frontier is non-constructive —
     * which this evaluate-and-check harness cannot verify. Reported as a result,
     * not laundered into a derived value.
     */
    public static PivotOutcome boundary(String why) {
        return new PivotOutcome(Branch.ANALYTIC, TrustLevel.LITERATURE_ONLY, false, why);
    }
}
