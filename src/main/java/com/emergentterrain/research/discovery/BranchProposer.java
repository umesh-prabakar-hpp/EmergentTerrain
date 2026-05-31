package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 3 — one verifiable innovative branch (B3
 * proxy reformulation or B4 predictive model). The LLM proposes the specifics and
 * the proposer validates them (B3 via the moat on the overlap; B4 via fit on the
 * overlap), returning a trust-tagged {@link PivotOutcome}. Injected into
 * {@link BranchPivotService} so the pivot policy is testable with fakes and the
 * concrete proposers (which reuse the discovery moat) land at the live-wiring
 * step.
 *
 * <p>The harness deliberately has no {@code BranchProposer} for B5 (analytic):
 * non-constructive methods aren't verifiable here, so that branch is the boundary,
 * reached only when every proposer fails.
 */
public interface BranchProposer {

    /** Which branch this proposer represents (orders the pivot — lower ordinal tried first). */
    Branch branch();

    /** Attempt this branch; the returned {@link PivotOutcome#validated()} gates acceptance. */
    PivotOutcome attempt(PivotRequest request);
}
