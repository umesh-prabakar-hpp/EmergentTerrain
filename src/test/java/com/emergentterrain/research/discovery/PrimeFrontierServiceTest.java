package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path A prime frontier orchestration — end-to-end with fakes (fake sieve source +
 * fake discovery → real {@link FrontierEscalationService} → rendered {@link FrontierPaper}),
 * mirroring {@link DivisorFrontierServiceTest}. Proves π(x) rides the SAME escalation engine
 * (sieve in-range, discovery beyond) and persists under bound "prime_pi".
 */
class PrimeFrontierServiceTest {

    /** A π(x) that sits on li(x), so {@link BoundPredicate#PRIME_PI} HOLDS at every scale. */
    private static long holdingPi(long x) {
        return Math.round(BoundPredicate.li((double) x));
    }

    /** Fake discovery returning the (holding) π(x) for whatever beyond-sieve target it's asked. */
    private static CandidateDiscoveryService discReturningPrime() {
        return new CandidateDiscoveryService(true, 1, r -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null,
                        holdingPi(t.target()), ""));
    }

    @Test
    void producesTrustFramedFrontierPaperViaSieveThenDiscovery() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discReturningPrime());
        ScaleValueSource sieve = PrimeFrontierServiceTest::holdingPi;     // x ≤ 2e6 in-range, holds
        PrimeFrontierService svc = new PrimeFrontierService(2_000_000L, false, escalation, sieve);

        FrontierPaper paper = svc.produce();

        assertThat(paper.title()).contains("pi(x)");
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
    void persistsUnderThePrimeBoundKeyNeverMertens() {
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
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discReturningPrime());
        PrimeFrontierService svc = new PrimeFrontierService(2_000_000L, false, escalation,
                (ScaleValueSource) PrimeFrontierServiceTest::holdingPi);
        svc.setKnowledge(knowledge);

        svc.produce();

        assertThat(bounds).isNotEmpty().allMatch("prime_pi"::equals);
    }

    @Test
    void disabledEscalationYieldsDisabledPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(false, 3, 30000.0, 0.0, discReturningPrime());
        PrimeFrontierService svc = new PrimeFrontierService(2_000_000L, false, escalation,
                (ScaleValueSource) PrimeFrontierServiceTest::holdingPi);
        FrontierPaper paper = svc.produce();
        assertThat(paper.outcome().ran()).isFalse();
    }
}
