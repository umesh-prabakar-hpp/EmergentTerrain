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

    private static MertensDiscoveryService discAccepting(long value) {
        return new MertensDiscoveryService(true, 1, r -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null, value, ""));
    }

    @Test
    void producesTrustFramedFrontierPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(true, 3, 30000.0, 0.0, discAccepting(1037L));
        ScaleValueSource sieve = x -> -247L;     // M(2,000,000)=-247, |247|<sqrt(2e6) -> holds
        MertensFrontierService svc = new MertensFrontierService(2_000_000L, escalation, sieve);

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
    void disabledEscalationYieldsDisabledPaper() {
        FrontierEscalationService escalation =
                new FrontierEscalationService(false, 3, 30000.0, 0.0, discAccepting(1037L));
        MertensFrontierService svc = new MertensFrontierService(2_000_000L, escalation, x -> -247L);
        FrontierPaper paper = svc.produce();
        assertThat(paper.outcome().ran()).isFalse();
        assertThat(paper.findings()).contains("did not run");
    }
}
