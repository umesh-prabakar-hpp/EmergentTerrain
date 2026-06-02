package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 4 — the Mertens frontier orchestration,
 * end-to-end with fakes (fake sieve source + fake discovery → real escalation →
 * rendered FrontierPaper).
 */
class MertensFrontierServiceTest {

    private static CandidateDiscoveryService discAccepting(long value) {
        return new CandidateDiscoveryService(true, 1, r -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null, value, ""));
    }

    @Test
    void producesTrustFramedFrontierPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discAccepting(1037L));
        ScaleValueSource sieve = x -> -247L;     // M(2,000,000)=-247, |247|<sqrt(2e6) -> holds
        MertensFrontierService svc = new MertensFrontierService(2_000_000L, false, escalation, sieve);

        FrontierPaper paper = svc.produce();

        assertThat(paper.title()).contains("Mertens");
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
        // knowledge reports an accumulated frontier of 5e8 (>> the 2e6 sieve range)
        FrontierKnowledgeService knowledge = new FrontierKnowledgeService(null, 2) {
            @Override public long frontier(String bound) { return 500_000_000L; }
            @Override public boolean isConverged(String bound) { return false; }
            @Override public Recall recall(String bound) {
                return new Recall(java.util.List.of(), 500_000_000L, java.util.Optional.empty(), java.util.List.of());
            }
            @Override public void recordCheckpoint(String bound, EscalationCheckpoint c) { /* no store */ }
        };
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discAccepting(1037L));
        int[] sieveCalls = {0};
        ScaleValueSource counting = x -> { sieveCalls[0]++; return -247L; };
        MertensFrontierService svc = new MertensFrontierService(2_000_000L, false, escalation, counting);
        svc.setKnowledge(knowledge);

        FrontierPaper paper = svc.produce();

        // resumed at 5e8 -> the first rung is discovery (> NT_MAX_N), so the sieve is never queried
        assertThat(sieveCalls[0]).isZero();
        assertThat(paper.outcome().frontierReached()).isGreaterThanOrEqualTo(500_000_000L);
        assertThat(paper.outcome().checkpoints().get(0).x()).isEqualTo(500_000_000L);
    }

    @Test
    void disabledEscalationYieldsDisabledPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(false, 3, 30000.0, 0.0, discAccepting(1037L));
        MertensFrontierService svc = new MertensFrontierService(2_000_000L, false, escalation, x -> -247L);
        FrontierPaper paper = svc.produce();
        assertThat(paper.outcome().ran()).isFalse();
        assertThat(paper.findings()).contains("did not run");
    }

    @Test
    void scheduledRunNoOpsUntilScheduleFlagOn() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discAccepting(1037L));
        int[] sieveCalls = {0};
        ScaleValueSource counting = x -> { sieveCalls[0]++; return -247L; };

        // schedule OFF -> no produce(), sieve never queried
        new MertensFrontierService(2_000_000L, false, escalation, counting).scheduledFrontierRun();
        assertThat(sieveCalls[0]).isZero();

        // schedule ON -> produce() runs, sieve queried at the first rung
        new MertensFrontierService(2_000_000L, true, escalation, counting).scheduledFrontierRun();
        assertThat(sieveCalls[0]).isPositive();
    }
}
