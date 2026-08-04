package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 divisor frontier orchestration — end-to-end with fakes (fake sieve source +
 * fake discovery → real {@link FrontierEscalationService} → rendered {@link FrontierPaper}),
 * mirroring {@link MertensFrontierServiceTest}. Proves the divisor bound rides the SAME
 * escalation engine (sieve in-range, discovery beyond) and persists under bound "divisor_D".
 */
class DivisorFrontierServiceTest {

    private static final double TWO_GAMMA_MINUS_1 = 2 * 0.5772156649015329 - 1;

    /** Asymptotic D(x) — within the √x band, so {@link BoundPredicate#DIVISOR_ERROR} HOLDS. */
    private static long dirichlet(long x) {
        return Math.round(x * (Math.log((double) x) + TWO_GAMMA_MINUS_1));
    }

    /** Fake discovery that returns the (holding) D(x) for whatever beyond-sieve target it's asked. */
    private static CandidateDiscoveryService discReturningDivisor() {
        return new CandidateDiscoveryService(true, 1, r -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null,
                        dirichlet(t.target()), ""));
    }

    @Test
    void producesTrustFramedFrontierPaperViaSieveThenDiscovery() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discReturningDivisor());
        ScaleValueSource sieve = DivisorFrontierServiceTest::dirichlet;     // x ≤ 2e6 in-range, holds
        DivisorFrontierService svc = new DivisorFrontierService(2_000_000L, false, escalation, sieve);

        FrontierPaper paper = svc.produce();

        assertThat(paper.title()).contains("divisor");
        assertThat(paper.outcome().ran()).isTrue();
        assertThat(paper.outcome().terminalState()).isEqualTo("exhausted_rounds");
        assertThat(paper.findings())
                .contains("## Findings")
                .contains("FULL trust")
                .contains("HELD")
                .contains("sieve")
                .contains("discovery");
    }

    @Test
    void resumesFromPersistedFrontierInsteadOfReWalking() {
        FrontierKnowledgeService knowledge = new FrontierKnowledgeService(null, 2) {
            @Override public long frontier(String bound) { return 500_000_000L; }
            @Override public boolean isConverged(String bound) { return false; }
            @Override public Recall recall(String bound) {
                return new Recall(java.util.List.of(), 500_000_000L, java.util.Optional.empty(), java.util.List.of());
            }
            @Override public void recordCheckpoint(String bound, EscalationCheckpoint c) { /* no store */ }
        };
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discReturningDivisor());
        int[] sieveCalls = {0};
        ScaleValueSource counting = x -> { sieveCalls[0]++; return dirichlet(x); };
        DivisorFrontierService svc = new DivisorFrontierService(2_000_000L, false, escalation, counting);
        svc.setKnowledge(knowledge);

        FrontierPaper paper = svc.produce();

        // resumed at 5e8 (>> the 2e6 sieve range) → the first rung is discovery, sieve never queried
        assertThat(sieveCalls[0]).isZero();
        assertThat(paper.outcome().frontierReached()).isGreaterThanOrEqualTo(500_000_000L);
        assertThat(paper.outcome().checkpoints().get(0).x()).isEqualTo(500_000_000L);
    }

    @Test
    void persistsUnderTheDivisorBoundKeyNeverMertens() {
        java.util.List<String> bounds = new java.util.ArrayList<>();
        FrontierKnowledgeService knowledge = new FrontierKnowledgeService(null, 2) {
            @Override public long frontier(String bound) { return 0; }
            @Override public boolean isConverged(String bound) { return false; }
            @Override public Recall recall(String bound) {
                return new Recall(java.util.List.of(), 0, java.util.Optional.empty(), java.util.List.of());
            }
            @Override public void recordCheckpoint(String bound, EscalationCheckpoint c) { bounds.add(bound); }
        };
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discReturningDivisor());
        DivisorFrontierService svc = new DivisorFrontierService(2_000_000L, false, escalation,
                (ScaleValueSource) DivisorFrontierServiceTest::dirichlet);
        svc.setKnowledge(knowledge);

        svc.produce();

        assertThat(bounds).isNotEmpty().allMatch("divisor_D"::equals);
    }

    @Test
    void disabledEscalationYieldsDisabledPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(false, 3, 30000.0, 0.0, discReturningDivisor());
        DivisorFrontierService svc = new DivisorFrontierService(2_000_000L, false, escalation,
                (ScaleValueSource) DivisorFrontierServiceTest::dirichlet);
        FrontierPaper paper = svc.produce();
        assertThat(paper.outcome().ran()).isFalse();
    }
}
