package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-10 (lever F) — the per-paper accumulator that unions best-of-N candidates' professor traces
 * so a verified bound from a losing candidate still reaches the discovery→pool write-back.
 */
class InvestigationTraceAccumulatorTest {

    private static ToolInvocation inv(String id) {
        return new ToolInvocation("verify_conjecture", "{}", "result-" + id, 0, id);
    }

    @Test
    void unionsAcrossCandidatesThenDrains() {
        InvestigationTraceAccumulator acc = new InvestigationTraceAccumulator();
        acc.add("P-1", List.of(inv("call_1")));            // winner
        acc.add("P-1", List.of(inv("call_2"), inv("call_3"))); // a losing candidate
        List<ToolInvocation> union = acc.drain("P-1");
        assertThat(union).extracting(ToolInvocation::callId)
                .containsExactly("call_1", "call_2", "call_3");
        // drain is one-shot: the entry is removed.
        assertThat(acc.drain("P-1")).isEmpty();
    }

    @Test
    void drainUnknownPaper_isEmpty() {
        assertThat(new InvestigationTraceAccumulator().drain("nope")).isEmpty();
    }

    @Test
    void ignoresNullAndEmptyInputs() {
        InvestigationTraceAccumulator acc = new InvestigationTraceAccumulator();
        acc.add(null, List.of(inv("call_1")));
        acc.add("P-2", null);
        acc.add("P-2", List.of());
        acc.add("  ", List.of(inv("call_1")));
        assertThat(acc.drain("P-2")).isEmpty();
    }

    @Test
    void boundedEvictionDropsOldestPapers() {
        InvestigationTraceAccumulator acc = new InvestigationTraceAccumulator();
        // insert one past the cap; the eldest (P-0) is evicted, a recent one survives.
        for (int i = 0; i <= InvestigationTraceAccumulator.MAX_PAPERS; i++) {
            acc.add("P-" + i, List.of(inv("call_" + i)));
        }
        assertThat(acc.drain("P-0")).isEmpty();                                   // evicted
        assertThat(acc.drain("P-" + InvestigationTraceAccumulator.MAX_PAPERS)).hasSize(1); // kept
    }
}
