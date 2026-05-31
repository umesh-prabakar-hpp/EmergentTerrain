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
        MertensDiscoveryService svc = new MertensDiscoveryService(true, gen, new FakeVal(reject(), accept()));

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
        MertensDiscoveryService svc = new MertensDiscoveryService(true,
                new FakeGen("good-code"), new FakeVal(accept()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isTrue();
        assertThat(out.attempts()).isEqualTo(1);
    }

    @Test
    void exhaustsAfterMaxAttempts() {
        MertensDiscoveryService svc = new MertensDiscoveryService(true,
                new FakeGen("a", "b", "c"), new FakeVal(reject(), reject(), reject()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.attempts()).isEqualTo(3);
        assertThat(out.failureReason()).contains("within 3 attempt");
    }

    @Test
    void disabledShortCircuits() {
        // Even with fakes that would accept, the flag-off service does nothing.
        MertensDiscoveryService svc = new MertensDiscoveryService(false,
                new FakeGen("x"), new FakeVal(accept()));
        DiscoveryOutcome out = svc.discover(TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.failureReason()).contains("disabled");
        assertThat(out.attempts()).isZero();
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
    void sha256IsStableAndHex() {
        String a = MertensDiscoveryService.sha256("candidate(x)=0");
        assertThat(a).isEqualTo(MertensDiscoveryService.sha256("candidate(x)=0")).hasSize(64);
        assertThat(a).isNotEqualTo(MertensDiscoveryService.sha256("candidate(x)=1"));
    }
}
