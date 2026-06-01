package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.CandidateGenerator.GenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b Phase 3 — the generate→validate→repair loop, tested with
 * fakes (no live model or sidecar). Mirrors what fork 2a proved at dev time.
 */
class MertensDiscoveryServiceTest {

    private static final DiscoveryTask TASK = new DiscoveryTask(
            "M(x) = sum of the Möbius function mu(n) for n=1..x",
            100_000_000L, List.of(1L, 2L, 10L), Map.of(10_000_000L, 1037L), 10, 15, 3);

    private static ValidationResult reject() {
        return new ValidationResult("ok", "reject", Map.of(2L, 2L), Map.of(2L, 0L),
                new ValidationResult.FirstFail(2L, 2L, 0L), null, "");
    }

    private static ValidationResult accept() {
        return new ValidationResult("ok", "accept", Map.of(2L, 0L), Map.of(2L, 0L), null, 1928L, "");
    }

    /** Generator that returns scripted codes and records the last request. */
    private static final class FakeGen implements CandidateGenerator {
        final Deque<String> codes;
        GenerationRequest last;
        FakeGen(String... cs) { codes = new ArrayDeque<>(List.of(cs)); }
        public String generate(GenerationRequest r) { last = r; return codes.isEmpty() ? "fallback" : codes.poll(); }
    }

    private static final class FakeVal implements CandidateValidator {
        final Deque<ValidationResult> results;
        FakeVal(ValidationResult... rs) { results = new ArrayDeque<>(List.of(rs)); }
        public ValidationResult validate(String code, DiscoveryTask t) { return results.poll(); }
    }

    @Test
    void repairConvergesOnSecondAttempt() {
        FakeGen gen = new FakeGen("buggy-code", "fixed-code");
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3, gen, new FakeVal(reject(), accept()));

        DiscoveryOutcome out = svc.discover(TASK);

        assertThat(out.accepted()).isTrue();
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(out.code()).isEqualTo("fixed-code");
        assertThat(out.targetValue()).isEqualTo(1928L);
        assertThat(out.provenance()).isNotNull();
        assertThat(out.provenance().codeSha256()).hasSize(64);
        assertThat(out.provenance().target()).isEqualTo(100_000_000L);
        // the repair request carried the prior code + the moat discrepancy
        assertThat(gen.last.isRepair()).isTrue();
        assertThat(gen.last.previousCode()).isEqualTo("buggy-code");
        assertThat(gen.last.discrepancy()).isEqualTo("M(2) = 2 but the correct value is 0");
    }

    @Test
    void acceptsFirstTryWhenCorrect() {
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3,
                new FakeGen("good-code"), new FakeVal(accept()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isTrue();
        assertThat(out.attempts()).isEqualTo(1);
    }

    @Test
    void exhaustsAfterMaxAttempts() {
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3,
                new FakeGen("a", "b", "c"), new FakeVal(reject(), reject(), reject()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.attempts()).isEqualTo(3);
        assertThat(out.failureReason()).contains("within 3 attempt");
    }

    @Test
    void disabledShortCircuits() {
        // Even with fakes that would accept, the flag-off service does nothing.
        MertensDiscoveryService svc = new MertensDiscoveryService(false, 3,
                new FakeGen("x"), new FakeVal(accept()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.failureReason()).contains("disabled");
        assertThat(out.attempts()).isZero();
    }

    @Test
    void skipsWithoutGeneratingWhenEndpointUnavailable() {
        int[] genCalls = {0};
        CandidateGenerator gen = req -> { genCalls[0]++; return "code"; };
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3, gen, (c, t) -> accept());
        svc.setEndpointHealth(new DiscoveryEndpointHealth(true, "http://x", 60_000) {
            protected boolean doProbe() { return false; }   // endpoint unavailable
        });

        DiscoveryOutcome out = svc.discover(TASK);

        assertThat(out.accepted()).isFalse();
        assertThat(out.failureReason()).contains("unavailable");
        assertThat(genCalls[0]).isZero();   // pre-flight skipped BEFORE any generation -> no tokens
    }

    @Test
    void describeFormatsRepairSignal() {
        assertThat(MertensDiscoveryService.describe(reject()))
                .isEqualTo("M(2) = 2 but the correct value is 0");
        ValidationResult na = new ValidationResult("ast_reject", "na", Map.of(), Map.of(), null, null, "open");
        assertThat(MertensDiscoveryService.describe(na))
                .contains("did not run").contains("ast_reject");
    }

    @Test
    void describeSignalsResourceFailureWithoutPrescribingTheAlgorithm() {
        // §10 — the hint signals the failure + freedom, but does NOT hand the model the algorithm
        // (no recipe): the LLM must devise the efficient method itself; the moat verifies.
        ValidationResult t = new ValidationResult("timeout", "na", Map.of(), Map.of(), null, null, "time limit");
        String th = MertensDiscoveryService.describe(t);
        assertThat(th).contains("TOO SLOW").contains("more efficient algorithm").contains("free to use");
        assertThat(th).doesNotContain("M(n) = 1").doesNotContainIgnoringCase("recurrence")
                .doesNotContain("x^(2/3)").doesNotContainIgnoringCase("sieve");

        ValidationResult o = new ValidationResult("oom", "na", Map.of(), Map.of(), null, null, "oom");
        String oh = MertensDiscoveryService.describe(o);
        assertThat(oh).contains("MEMORY BUDGET").contains("grows with x");
        assertThat(oh).doesNotContain("x^(2/3)").doesNotContainIgnoringCase("recurrence");
    }

    @Test
    void sha256IsStableAndHex() {
        String a = MertensDiscoveryService.sha256("candidate(x)=0");
        assertThat(a).isEqualTo(MertensDiscoveryService.sha256("candidate(x)=0")).hasSize(64);
        assertThat(a).isNotEqualTo(MertensDiscoveryService.sha256("candidate(x)=1"));
    }

    /** A resource (timeout) failure — the candidate did not run within the budget. */
    private static ValidationResult timeout() {
        return new ValidationResult("timeout", "na", Map.of(), Map.of(), null, null, "time limit");
    }

    @Test
    void earlyExitsAfterRepeatedResourceFailure() {
        // §10 — threshold 2: bail after 2 consecutive timeouts even though 3 attempts are allowed,
        // because the scale (not the method) is the problem. Saves the 3rd (~wall_s) attempt.
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3,
                new FakeGen("a", "b", "c"), new FakeVal(timeout(), timeout(), timeout()));
        svc.setRepairEarlyExitThreshold(2);
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.attempts()).isEqualTo(2);              // bailed at 2, did not grind to 3
        assertThat(out.failureStatus()).isEqualTo("timeout");
    }

    @Test
    void wrongNumberFailuresResetStreakAndDoNotEarlyExit() {
        // a wrong-number failure is repairable (a better method may fix it), so the streak resets
        // and the loop runs all attempts — early-exit only fires on REPEATED resource failures.
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3,
                new FakeGen("a", "b", "c"), new FakeVal(reject(), reject(), reject()));
        svc.setRepairEarlyExitThreshold(2);
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.attempts()).isEqualTo(3);
    }

    @Test
    void earlyExitDisabledWhenThresholdZero() {
        MertensDiscoveryService svc = new MertensDiscoveryService(true, 3,
                new FakeGen("a", "b", "c"), new FakeVal(timeout(), timeout(), timeout()));
        svc.setRepairEarlyExitThreshold(0);   // disabled — grind all attempts
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.attempts()).isEqualTo(3);
    }
}
