package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    /** Discovery that accepts with a fixed value and a per-x cost (ms). */
    private static MertensDiscoveryService discWithCost(long value, java.util.function.LongUnaryOperator costOfX) {
        return new MertensDiscoveryService(true, 1, req -> "code",
                (code, t) -> new ValidationResult("ok", "accept", Map.of(), Map.of(), null, value, "",
                        costOfX.applyAsLong(t.target())));
    }

    private static final ScaleValueSource SIEVE_HOLDS = x -> 212L;   // M(10^6)=212, |212|<sqrt(x) for x>=10^6

    @Test
    void escalatesWhileHoldingThenExhaustsRounds() {
        FrontierEscalationService svc = new FrontierEscalationService(true, 4, 30000.0, 0.0, discReturning(1037L));
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
        FrontierEscalationService svc = new FrontierEscalationService(true, 8, 30000.0, 0.0, discReturning(1037L));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, holdThenFail);

        assertThat(out.terminalState()).isEqualTo("counterexample_found");
        assertThat(out.checkpoints()).hasSize(2);
        assertThat(out.frontierReached()).isEqualTo(1_000_000L);       // last HELD scale
        assertThat(out.checkpoints().get(1).verdict()).isEqualTo(EscalationVerdict.COUNTEREXAMPLE);
    }

    @Test
    void stallsWhenDiscoveryCannotCompute() {
        FrontierEscalationService svc = new FrontierEscalationService(true, 8, 30000.0, 0.0, discFailing());
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
    void discoveryFailureFeedsBackThenStallsAfterMaxConsecutive() {
        // §10.1 — with maxConsecutiveFailures=3, a CANNOT_COMPUTE no longer ends the run on
        // the first failure: the loop keeps escalating (feeding the failure back) until 3
        // consecutive failures, then stalls. So we see 1 sieve rung + 3 discovery failures.
        FrontierEscalationService svc =
                new FrontierEscalationService(true, 8, 30000.0, 0.0, 3, discFailing());
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.terminalState()).isEqualTo("discovery_stalled");
        assertThat(out.checkpoints()).hasSize(4);                       // 1 sieve hold + 3 discovery failures
        assertThat(out.frontierReached()).isEqualTo(1_000_000L);
        assertThat(out.checkpoints().subList(1, 4))
                .allMatch(c -> c.verdict() == EscalationVerdict.CANNOT_COMPUTE);
        // the failed candidate code + sandbox status are captured for the knowledge event
        EscalationCheckpoint firstFail = out.checkpoints().get(1);
        assertThat(firstFail.methodCode()).isEqualTo("code");
        assertThat(firstFail.failureStatus()).isEqualTo("ast_reject");
    }

    @Test
    void disabledShortCircuits() {
        FrontierEscalationService svc = new FrontierEscalationService(false, 4, 30000.0, 0.0, discReturning(1037L));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);
        assertThat(out.ran()).isFalse();
        assertThat(out.terminalState()).isEqualTo("disabled");
        assertThat(out.checkpoints()).isEmpty();
    }

    @Test
    void predictsStallAndStopsEarly() {
        // discovery cost = x/1000 ms -> 10^7:1e4ms, 10^8:1e5ms => power law p=1, a=1e-3.
        // budget 5e5 ms -> x_c=5e8. After 10^8 holds, next 10^9 > x_c -> predicted_stall.
        FrontierEscalationService svc = new FrontierEscalationService(
                true, 8, 500_000.0, 1.0e12, discWithCost(1037L, x -> x / 1000));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.terminalState()).isEqualTo("predicted_stall");
        assertThat(out.frontierReached()).isEqualTo(100_000_000L);     // 10^8 held; 10^9 forecast to stall
        assertThat(out.prediction()).isNotNull();
        assertThat(out.prediction().valid()).isTrue();
        assertThat(out.prediction().exponent()).isCloseTo(1.0, within(0.05));
        assertThat(out.prediction().computeFrontierX()).isCloseTo(5.0e8, within(5.0e7));
        assertThat(out.prediction().stallsBeforeScience()).isTrue();   // x_c=5e8 < x_s=1e12
    }

    @Test
    void pivotsToVerifiableBranchOnPredictedStall() {
        FrontierEscalationService svc = new FrontierEscalationService(
                true, 8, 500_000.0, 1.0e12, discWithCost(1037L, x -> x / 1000));
        svc.setBranchPivot(new BranchPivotService(true, List.of(new BranchProposer() {
            public Branch branch() { return Branch.PROXY_REFORMULATION; }
            public PivotOutcome attempt(PivotRequest r) {
                return new PivotOutcome(Branch.PROXY_REFORMULATION, TrustLevel.VALIDATED_PROXY, true, "proxy validated");
            }
        })));
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.terminalState()).isEqualTo("pivoted_proxy_reformulation");
        assertThat(out.frontierReached()).isEqualTo(100_000_000L);
        assertThat(out.pivot()).isNotNull();
        assertThat(out.pivot().trust()).isEqualTo(TrustLevel.VALIDATED_PROXY);
        assertThat(out.pivot().validated()).isTrue();
    }

    @Test
    void pivotReachesHonestBoundaryWhenNoVerifiableBranch() {
        FrontierEscalationService svc = new FrontierEscalationService(
                true, 8, 500_000.0, 1.0e12, discWithCost(1037L, x -> x / 1000));
        svc.setBranchPivot(new BranchPivotService(true, List.of()));   // no proposers -> B5 boundary
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.terminalState()).isEqualTo("pivot_boundary");
        assertThat(out.pivot().branch()).isEqualTo(Branch.ANALYTIC);
        assertThat(out.pivot().trust()).isEqualTo(TrustLevel.LITERATURE_ONLY);
    }

    @Test
    void adaptivePolicyLeapsAcrossScales() {
        // high margin everywhere -> ×1000 leaps: 10^6 -> 10^9 -> 10^12 in 3 rounds
        // (vs 10^6 -> 10^7 -> 10^8 under the fixed-×10 default).
        FrontierEscalationService svc =
                new FrontierEscalationService(true, 3, 1.0e18, 0.0, discReturning(1037L));
        svc.setScalePolicy(new AdaptiveNextScalePolicy());
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        assertThat(out.checkpoints()).extracting(EscalationCheckpoint::x)
                .containsExactly(1_000_000L, 1_000_000_000L, 1_000_000_000_000L);
        assertThat(out.terminalState()).isEqualTo("exhausted_rounds");
        assertThat(out.frontierReached()).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void loopAllowsBackwardStepAndStopsOnRevisit() {
        // a policy that steps back to localize, then would revisit the start -> stop.
        NextScalePolicy zigzag = new NextScalePolicy() {
            int n = 0;
            public long nextScale(NextScaleContext c) { return (n++ == 0) ? 500_000L : 1_000_000L; }
        };
        FrontierEscalationService svc =
                new FrontierEscalationService(true, 8, 1.0e18, 0.0, discReturning(212L));
        svc.setScalePolicy(zigzag);
        EscalationOutcome out = svc.escalate(TASK, SIEVE_HOLDS, BoundPredicate.MERTENS);

        // 10^6 (holds) -> backward 5×10^5 (holds) -> next 10^6 already visited -> stop
        assertThat(out.checkpoints()).extracting(EscalationCheckpoint::x)
                .containsExactly(1_000_000L, 500_000L);
        assertThat(out.frontierReached()).isEqualTo(1_000_000L);   // backward step doesn't lower the frontier
    }

    @Test
    void localizesCounterexampleWithBisection() {
        long threshold = 500_000_000L;   // a monotone bound: holds below 5×10^8, fails at/above
        BoundPredicate stepBound = (x, v) ->
                x < threshold ? EscalationVerdict.HOLDS : EscalationVerdict.COUNTEREXAMPLE;
        FrontierEscalationService svc =
                new FrontierEscalationService(true, 8, 1.0e18, 0.0, discReturning(0L));
        svc.setLocalizeEnabled(true);

        // escalate 10^6→10^7→10^8 (hold) → 10^9 (fail) → bisect (10^8, 10^9] toward 5×10^8
        EscalationOutcome out = svc.escalate(TASK, x -> 0L, stepBound);

        assertThat(out.terminalState()).isEqualTo("counterexample_localized");
        assertThat(out.frontierReached()).isLessThan(threshold);            // lo holds
        assertThat(out.localizedHi()).isGreaterThanOrEqualTo(threshold);    // hi fails
        assertThat((double) out.localizedHi() / out.frontierReached())
                .isLessThanOrEqualTo(1.2);                                  // tight bracket (~10%)
    }

    @Test
    void mertensBoundClassifiesCorrectly() {
        // |212| < sqrt(10^6)=1000 -> holds ; |5000| > sqrt(10^6) -> counterexample
        assertThat(BoundPredicate.MERTENS.classify(1_000_000L, 212L)).isEqualTo(EscalationVerdict.HOLDS);
        assertThat(BoundPredicate.MERTENS.classify(1_000_000L, 5000L)).isEqualTo(EscalationVerdict.COUNTEREXAMPLE);
    }
}
