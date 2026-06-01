package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.CandidateGenerator.GenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SHARED generate→validate→repair loop — generic over the validation result, with the
 * recipe-free resource hints + early-exit centralized so all domains share one implementation.
 */
class DiscoveryRepairLoopTest {

    /** A minimal domain result: did it pass, sandbox status, and the "what was wrong" text. */
    private record V(boolean ok, String status, String why) {}

    private static CandidateGenerator gen(String... codes) {
        Deque<String> q = new ArrayDeque<>(List.of(codes));
        return r -> q.isEmpty() ? "fallback" : q.poll();
    }

    private static DiscoveryRepairLoop.Result<V> run(int maxAttempts, int earlyExit,
                                                     CandidateGenerator g, V... results) {
        Deque<V> rs = new ArrayDeque<>(List.of(results));
        return DiscoveryRepairLoop.run("spec", "test", maxAttempts, earlyExit, g,
                code -> rs.poll(), V::ok, V::status, V::why);
    }

    @Test
    void augmentAddsNeutralResourceSignalButNoRecipe() {
        String t = DiscoveryRepairLoop.augment("base", "timeout");
        assertThat(t).startsWith("base").contains("TOO SLOW").contains("more efficient algorithm")
                .contains("free to use");
        assertThat(t).doesNotContainIgnoringCase("sieve").doesNotContainIgnoringCase("recurrence")
                .doesNotContain("x^(2/3)");
        assertThat(DiscoveryRepairLoop.augment("base", "oom")).contains("MEMORY BUDGET").contains("grows with");
        assertThat(DiscoveryRepairLoop.augment("base", "ok")).isEqualTo("base");   // non-resource passes through
    }

    @Test
    void returnsWinningResultOnAccept() {
        DiscoveryRepairLoop.Result<V> r = run(3, 2, gen("a", "b"),
                new V(false, "ok", "wrong"), new V(true, "ok", null));
        assertThat(r.accepted()).isTrue();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.code()).isEqualTo("b");
        assertThat(r.winning().ok()).isTrue();
    }

    @Test
    void earlyExitsAfterRepeatedResourceFailureAndCapturesLast() {
        DiscoveryRepairLoop.Result<V> r = run(6, 2, gen("a", "b", "c"),
                new V(false, "timeout", "slow"), new V(false, "timeout", "slow"), new V(false, "timeout", "slow"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.attempts()).isEqualTo(2);                 // bailed at 2, not 6
        assertThat(r.lastStatus()).isEqualTo("timeout");
        assertThat(r.lastFailedCode()).isEqualTo("b");
        assertThat(r.lastDiscrepancy()).contains("TOO SLOW");  // hint appended by the loop
    }

    @Test
    void nonResourceFailuresResetTheStreak() {
        // wrong (status ok) between timeouts keeps the consecutive count from reaching the threshold
        DiscoveryRepairLoop.Result<V> r = run(4, 2, gen("a", "b", "c", "d"),
                new V(false, "timeout", "slow"), new V(false, "ok", "wrong"),
                new V(false, "timeout", "slow"), new V(false, "ok", "wrong"));
        assertThat(r.accepted()).isFalse();
        assertThat(r.attempts()).isEqualTo(4);                 // never 2-in-a-row resource → ran all
    }
}
