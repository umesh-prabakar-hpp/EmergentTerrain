package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 3 — the pivot policy: try verifiable
 * branches in trust order, take the first that validates, else the honest B5
 * boundary. Tested with fake proposers.
 */
class BranchPivotServiceTest {

    private static final PivotRequest REQ = new PivotRequest(
            "M(x) = sum of mu(n) for n=1..x", 1_000_000_000L, List.of(1L, 2L), Map.of(),
            new StallPrediction(true, 1.0, 1.0e-3, 5.0e8, true, "basis"));

    private static BranchProposer proposer(Branch b, boolean validates) {
        return new BranchProposer() {
            public Branch branch() { return b; }
            public PivotOutcome attempt(PivotRequest r) {
                TrustLevel t = b == Branch.PROXY_REFORMULATION ? TrustLevel.VALIDATED_PROXY : TrustLevel.MODEL_FIT;
                return new PivotOutcome(b, t, validates, b + (validates ? " validated" : " failed"));
            }
        };
    }

    @Test
    void prefersB3WhenItValidates() {
        // injected out of order; the service sorts by trust (B3 before B4)
        BranchPivotService svc = new BranchPivotService(true, List.of(
                proposer(Branch.PREDICTIVE_MODEL, true), proposer(Branch.PROXY_REFORMULATION, true)));
        PivotOutcome out = svc.pivot(REQ);
        assertThat(out.branch()).isEqualTo(Branch.PROXY_REFORMULATION);
        assertThat(out.trust()).isEqualTo(TrustLevel.VALIDATED_PROXY);
        assertThat(out.validated()).isTrue();
    }

    @Test
    void fallsToB4WhenB3Fails() {
        BranchPivotService svc = new BranchPivotService(true, List.of(
                proposer(Branch.PROXY_REFORMULATION, false), proposer(Branch.PREDICTIVE_MODEL, true)));
        PivotOutcome out = svc.pivot(REQ);
        assertThat(out.branch()).isEqualTo(Branch.PREDICTIVE_MODEL);
        assertThat(out.trust()).isEqualTo(TrustLevel.MODEL_FIT);
    }

    @Test
    void honestBoundaryWhenAllFail() {
        BranchPivotService svc = new BranchPivotService(true, List.of(
                proposer(Branch.PROXY_REFORMULATION, false), proposer(Branch.PREDICTIVE_MODEL, false)));
        PivotOutcome out = svc.pivot(REQ);
        assertThat(out.branch()).isEqualTo(Branch.ANALYTIC);
        assertThat(out.trust()).isEqualTo(TrustLevel.LITERATURE_ONLY);
        assertThat(out.validated()).isFalse();
        assertThat(out.finding()).contains("non-constructive").contains("literature");
    }

    @Test
    void honestBoundaryIsTheProdDefault() {
        // no proposers wired yet -> always the boundary (correct, honest behavior)
        BranchPivotService svc = new BranchPivotService(true, List.of());
        assertThat(svc.pivot(REQ).branch()).isEqualTo(Branch.ANALYTIC);
    }

    @Test
    void disabledShortCircuits() {
        BranchPivotService svc = new BranchPivotService(false, List.of(proposer(Branch.PROXY_REFORMULATION, true)));
        PivotOutcome out = svc.pivot(REQ);
        assertThat(out.validated()).isFalse();
        assertThat(out.finding()).contains("disabled");
    }
}
