package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 1 divisor frontier walk — end-to-end with a fake sieve oracle: walks the
 * fixed ladder, classifies via {@link BoundPredicate#DIVISOR_ERROR}, persists each
 * rung to {@link FrontierKnowledgeService}, and renders a trust-framed
 * {@link FrontierPaper}. Never touches discovery (Tier 1 is sieve-range only).
 */
class DivisorFrontierServiceTest {

    /** Exact D(x) = Σ_{n≤x} d(n) for the small ladder rungs; large rungs use the asymptotic. */
    private static long dirichletSum(long x) {
        if (x <= 2_000_000L) {
            // exact-enough stand-in: round the leading asymptotic; the predicate band is √x so
            // any value within √x of x(ln x + 2γ−1) classifies as HOLDS, which is what we assert.
            double approx = x * (Math.log((double) x) + (2 * 0.5772156649015329 - 1));
            return Math.round(approx);
        }
        return -1;
    }

    @Test
    void producesTrustFramedFrontierPaperAllHeld() {
        ScaleValueSource sieve = DivisorFrontierServiceTest::dirichletSum;
        DivisorFrontierService svc = new DivisorFrontierService(true, sieve);

        FrontierPaper paper = svc.produce();

        assertThat(paper.title()).contains("divisor");
        assertThat(paper.outcome().ran()).isTrue();
        assertThat(paper.outcome().terminalState()).isEqualTo("exhausted_rounds");
        assertThat(paper.outcome().frontierReached()).isEqualTo(2_000_000L);
        assertThat(paper.findings())
                .contains("## Findings")
                .contains("FULL trust")
                .contains("HELD")
                .contains("sieve");
    }

    @Test
    void persistsEveryHeldRungUnderTheDivisorBoundKey() {
        List<String> recordedBounds = new ArrayList<>();
        List<Long> recordedXs = new ArrayList<>();
        FrontierKnowledgeService knowledge = new FrontierKnowledgeService(null, 2) {
            @Override public long frontier(String bound) { return 0; }
            @Override public boolean isConverged(String bound) { return false; }
            @Override public void recordCheckpoint(String bound, EscalationCheckpoint c) {
                recordedBounds.add(bound);
                recordedXs.add(c.x());
            }
        };
        DivisorFrontierService svc = new DivisorFrontierService(true, DivisorFrontierServiceTest::dirichletSum);
        svc.setKnowledge(knowledge);

        svc.produce();

        // one checkpoint persisted per ladder rung, all under the divisor key (never mertens_M)
        assertThat(recordedBounds).isNotEmpty().allMatch("divisor_D"::equals);
        assertThat(recordedXs).contains(1L, 10L, 1_000L, 100_000L, 1_000_000L, 2_000_000L);
    }

    @Test
    void scheduledRunNoOpsWhenFlagOff() {
        int[] calls = {0};
        ScaleValueSource counting = x -> { calls[0]++; return dirichletSum(x); };
        DivisorFrontierService svc = new DivisorFrontierService(false, counting);
        svc.scheduledDivisorRun();
        assertThat(calls[0]).isZero();   // disabled → no walk, no sieve calls
    }

    @Test
    void oracleNullRungRecordedAsBoundaryNotDropped() {
        // a sieve gap (null) at one rung must surface as a CANNOT_COMPUTE checkpoint, not vanish
        ScaleValueSource gappy = x -> x == 1_000L ? null : dirichletSum(x);
        DivisorFrontierService svc = new DivisorFrontierService(true, gappy);

        FrontierPaper paper = svc.produce();

        assertThat(paper.outcome().checkpoints())
                .anyMatch(c -> c.x() == 1_000L && c.verdict() == EscalationVerdict.CANNOT_COMPUTE);
        // the frontier still advances past the gap on the higher held rungs
        assertThat(paper.outcome().frontierReached()).isEqualTo(2_000_000L);
    }
}
