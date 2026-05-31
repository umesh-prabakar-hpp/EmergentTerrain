package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b — the proxy-discovery loop, with fakes: a generator, the sandbox
 * executor (returns the candidate's raw values), and a fake oracle factory.
 */
class ProxyDiscoveryServiceTest {

    private static final DiscoveryTask TASK =
            new DiscoveryTask("", 1_000_000_000L, List.of(10L, 100L), Map.of(), 20, 28, 1);
    private static final ProxySpec SPEC = ProxyCatalog.OMEGA_SUM;

    /** Oracle: P(10)=100, P(100)=1000 (the overlap checkpoints). */
    private static ProxyOracleFactory factory() {
        return s -> new ProxyOracle() {
            public String name() { return s.name(); }
            public Long valueAt(long x) { return x == 10 ? 100L : x == 100 ? 1000L : null; }
        };
    }

    private static ValidationResult emitted(long at10, long at100, long atTarget) {
        return new ValidationResult("ok", "reject",   // endpoint's M(x) verdict — ignored by the proxy loop
                Map.of(10L, at10, 100L, at100, 1_000_000_000L, atTarget), Map.of(), null, null, "");
    }

    private static CandidateGenerator gen() {
        return req -> "candidate(x)=0";
    }

    @Test
    void acceptsWhenProxyMethodMatchesTheOracle() {
        ProxyDiscoveryService svc = new ProxyDiscoveryService(true, 3, gen(),
                (code, t) -> emitted(100, 1000, 42), factory());
        ProxyDiscoveryOutcome out = svc.discover(SPEC, TASK);
        assertThat(out.accepted()).isTrue();
        assertThat(out.proxyName()).isEqualTo("omega_sum");
        assertThat(out.targetValue()).isEqualTo(42L);
    }

    @Test
    void repairsThenAccepts() {
        Deque<ValidationResult> q = new ArrayDeque<>(List.of(
                emitted(100, 999, 42),     // wrong at 100 -> reject
                emitted(100, 1000, 42)));  // fixed
        ProxyDiscoveryService svc = new ProxyDiscoveryService(true, 3, gen(), (code, t) -> q.poll(), factory());
        ProxyDiscoveryOutcome out = svc.discover(SPEC, TASK);
        assertThat(out.accepted()).isTrue();
        assertThat(out.attempts()).isEqualTo(2);
    }

    @Test
    void exhaustsWhenNeverMatches() {
        ProxyDiscoveryService svc = new ProxyDiscoveryService(true, 3, gen(),
                (code, t) -> emitted(100, 999, 42), factory());
        ProxyDiscoveryOutcome out = svc.discover(SPEC, TASK);
        assertThat(out.accepted()).isFalse();
        assertThat(out.failureReason()).contains("within 3 attempt");
    }

    @Test
    void disabledShortCircuits() {
        ProxyDiscoveryService svc = new ProxyDiscoveryService(false, 3, gen(),
                (code, t) -> emitted(100, 1000, 42), factory());
        assertThat(svc.discover(SPEC, TASK).accepted()).isFalse();
        assertThat(svc.discover(SPEC, TASK).failureReason()).contains("disabled");
    }

    @Test
    void specTextDescribesTheProxyTerm() {
        assertThat(ProxyDiscoveryService.specText(ProxyCatalog.OMEGA_EXCESS))
                .contains("omega(n)").contains("Omega(n)").contains("Mobius");
        assertThat(ProxyDiscoveryService.term(ProxyCatalog.OMEGA_EXCESS)).contains("Omega(n)");
    }
}
