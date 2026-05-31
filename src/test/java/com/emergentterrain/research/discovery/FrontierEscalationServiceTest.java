package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 1 — the escalation skeleton, tested with
 * fakes: a sieve source, a discovery service (backed by a fake validator), and a
 * bound predicate. No live model or sidecar.
 */
class FrontierEscalationServiceTest {

    private static final EscalationTask TASK = new EscalationTask(
            "M(x) = sum of mu(n) for n=1..x", 1_000_000L, 10L, List.of(1L, 2L), Map.of(), 20, 28);

    /** Discovery that always accepts with a fixed value (M(x)-ish, holding). */
    private static MertensDiscoveryService discReturning(long value) {
        return new MertensDiscoveryService(true, 1, req -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null, value, ""));
    }

    /** Discovery that never validates a candidate. */
    private static MertensDiscoveryService discFailing() {
        return new MertensDiscoveryService(true, 1, req -> "code",
                (code, t) -> new ValidationResult("ast_reject", "na", Map.of(), Map.of(), null, null, ""));
    }

    private static final ScaleValueSource SIEVE_HOLDS = x -> 212L;   // M(10^6)=212, |212|<sqrt(x) for x>=10^6

    @Test
    void escalatesWhileHoldingThenExhaustsRounds() {
        FrontierEscalationService svc = new FrontierEscalationService(true, 4, discReturning(1037L));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.ran()).isTrue();
        assertThat(out.terminalState()).isEqualTo("exhausted_rounds");
        assertThat(out.checkpoints()).hasSize(4);
        assertThat(out.frontierReached()).isEqualTo(1_000_000_000L);   // 10^6 * 10^3
        // first rung from the sieve, the rest from discovery (crossed NT_MAX_N)
        assertThat(out.checkpoints().get(0).source()).isEqualTo("sieve");
        assertThat(out.checkpoints().get(1).source()).isEqualTo("discovery");
        assertThat(out.checkpoints()).allMatch(c -> c.verdict() == EscalationVerdict.HOLDS);
    }

    @Test
    void stopsOnCounterexample() {
        BoundPredicate holdThenFail = new BoundPredicate() {
            int n = 0;
            public EscalationVerdict classify(long x, long v) {
                return (n++ == 0) ? EscalationVerdict.HOLDS : EscalationVerdict.COUNTEREXAMPLE;
            }
        };
        FrontierEscalationService svc = new FrontierEscalationService(true, 8, discReturning(1037L));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, holdThenFail);

        assertThat(out.terminalState()).isEqualTo("counterexample_found");
        assertThat(out.checkpoints()).hasSize(2);
        assertThat(out.frontierReached()).isEqualTo(1_000_000L);       // last HELD scale
        assertThat(out.checkpoints().get(1).verdict()).isEqualTo(EscalationVerdict.COUNTEREXAMPLE);
    }

    @Test
    void stallsWhenDiscoveryCannotCompute() {
        FrontierEscalationService svc = new FrontierEscalationService(true, 8, discFailing());
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.terminalState()).isEqualTo("discovery_stalled");
        assertThat(out.checkpoints()).hasSize(2);                      // 10^6 holds (sieve), 10^7 can't compute
        assertThat(out.frontierReached()).isEqualTo(1_000_000L);
        EscalationCheckpoint stall = out.checkpoints().get(1);
        assertThat(stall.verdict()).isEqualTo(EscalationVerdict.CANNOT_COMPUTE);
        assertThat(stall.source()).isEqualTo("discovery");
        assertThat(stall.value()).isNull();
    }

    @Test
    void disabledShortCircuits() {
        FrontierEscalationService svc = new FrontierEscalationService(false, 4, discReturning(1037L));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);
        assertThat(out.ran()).isFalse();
        assertThat(out.terminalState()).isEqualTo("disabled");
        assertThat(out.checkpoints()).isEmpty();
    }

    @Test
    void mertensBoundClassifiesCorrectly() {
        // |212| < sqrt(10^6)=1000 -> holds ; |5000| > sqrt(10^6) -> counterexample
        assertThat(BoundPredicate.MERTENS.classify(1_000_000L, 212L)).isEqualTo(EscalationVerdict.HOLDS);
        assertThat(BoundPredicate.MERTENS.classify(1_000_000L, 5000L)).isEqualTo(EscalationVerdict.COUNTEREXAMPLE);
    }
}
