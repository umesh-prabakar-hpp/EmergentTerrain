package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 3 — the concrete B3/B4 proposers.
 */
class BranchProposerImplTest {

    private static EscalationCheckpoint cp(long x, long value) {
        return new EscalationCheckpoint(x, value, EscalationVerdict.HOLDS, "discovery", 1);
    }

    private static PivotRequest reqWithCorpus(long target, List<EscalationCheckpoint> corpus) {
        return new PivotRequest("M(x) = sum mu(n)", target, List.of(1L, 2L), Map.of(),
                new StallPrediction(true, 0.7, 1e-3, 5e8, true, "b"), corpus);
    }

    // ---------- B4 predictive model ----------

    @Test
    void b4FitsAndPredictsHoldsForBoundedRatio() {
        // ratio |M|/sqrt(x) ~ 0.2 across scales -> extrapolates < 1 -> HOLD
        var corpus = List.of(cp(1_000_000L, 200L), cp(10_000_000L, 600L), cp(100_000_000L, 1900L));
        PivotOutcome out = new B4PredictiveModelProposer().attempt(reqWithCorpus(1_000_000_000_000L, corpus));
        assertThat(out.branch()).isEqualTo(Branch.PREDICTIVE_MODEL);
        assertThat(out.trust()).isEqualTo(TrustLevel.MODEL_FIT);
        assertThat(out.validated()).isTrue();
        assertThat(out.finding()).contains("R^2").contains("NOT a proof").contains("HOLD");
    }

    @Test
    void b4PredictsFailWhenRatioClimbsPastOne() {
        // ratio climbs 0.6 -> 0.9 -> 1.3 -> extrapolation to 1e14 exceeds 1 -> FAIL
        var corpus = List.of(cp(1_000_000L, 600L), cp(100_000_000L, 9000L), cp(10_000_000_000L, 130_000L));
        PivotOutcome out = new B4PredictiveModelProposer().attempt(reqWithCorpus(100_000_000_000_000L, corpus));
        assertThat(out.validated()).isTrue();
        assertThat(out.finding()).contains("FAIL");
    }

    @Test
    void b4NotValidatedWithoutEnoughData() {
        var out = new B4PredictiveModelProposer().attempt(reqWithCorpus(1_000_000_000L, List.of(cp(1_000_000L, 200L))));
        assertThat(out.validated()).isFalse();
        assertThat(out.finding()).contains("insufficient data");
    }

    // ---------- B3 proxy reformulation (reuses the discovery moat) ----------

    private static CandidateDiscoveryService disc(boolean enabled, boolean accepts) {
        return new CandidateDiscoveryService(enabled, 1, r -> "code",
                (code, t) -> accepts
                        ? new ValidationResult("ok", "accept", Map.of(), Map.of(), null, 62366L, "")
                        : new ValidationResult("ast_reject", "na", Map.of(), Map.of(), null, null, ""));
    }

    @Test
    void b3ValidatesWhenReformulatedMethodPasses() {
        PivotOutcome out = new B3ProxyReformulationProposer(disc(true, true))
                .attempt(reqWithCorpus(1_000_000_000_000L, List.of()));
        assertThat(out.branch()).isEqualTo(Branch.PROXY_REFORMULATION);
        assertThat(out.trust()).isEqualTo(TrustLevel.VALIDATED_PROXY);
        assertThat(out.validated()).isTrue();
        assertThat(out.finding()).contains("62366").contains("VALIDATED_PROXY");
    }

    @Test
    void b3DefersWhenNoCheaperMethodValidates() {
        PivotOutcome out = new B3ProxyReformulationProposer(disc(true, false))
                .attempt(reqWithCorpus(1_000_000_000_000L, List.of()));
        assertThat(out.validated()).isFalse();
    }

    @Test
    void b3DefersWhenDiscoveryDisabled() {
        PivotOutcome out = new B3ProxyReformulationProposer(disc(false, true))
                .attempt(reqWithCorpus(1_000_000_000_000L, List.of()));
        assertThat(out.validated()).isFalse();
        assertThat(out.finding()).contains("unavailable");
    }
}
