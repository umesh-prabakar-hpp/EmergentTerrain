package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 33 D1 — {@link BindingExtensionPolicy} detection. The P-b72c-77627788 shape (near-binding
 * hold, no refute, rounds exhausted) must fire; completed pairs and loose holds must not.
 */
class BindingExtensionPolicyTest {

    private static ToolInvocation verify(String result) {
        return new ToolInvocation("verify_conjecture", "{}", result, 0);
    }

    private final BindingExtensionPolicy policy = new BindingExtensionPolicy();

    @Test
    void nearBindingHoldWithoutRefute_fires_p788() {
        // The P-788 trace shape: tight re-verify HELD at rel-slack 0.0018, no REFUTED anywhere.
        List<ToolInvocation> trace = List.of(
                verify("verify_conjecture: x <= y HELD across 10 window(s). worst margin: +0.00002"
                        + " relative slack: 0.0018 (|margin|/|bound|, smaller = tighter)."));
        assertThat(policy.needsBindingCompletion(trace)).isTrue();
    }

    @Test
    void completedPair_doesNotFire() {
        // A REFUTED anywhere means the demonstration half exists — no extension.
        List<ToolInvocation> trace = List.of(
                verify("verify_conjecture: x <= y HELD across 10 window(s). relative slack: 0.0018."),
                verify("verify_conjecture: x <= y REFUTED across 10 window(s). worst margin: +3.657"));
        assertThat(policy.needsBindingCompletion(trace)).isFalse();
    }

    @Test
    void looseHold_doesNotFire() {
        // rel-slack 0.5725 (the Cramér k=1 shape) is not worth an extension.
        List<ToolInvocation> trace = List.of(
                verify("verify_conjecture: x <= y HELD across 10 window(s). relative slack: 0.5725."));
        assertThat(policy.needsBindingCompletion(trace)).isFalse();
    }

    @Test
    void nonVerificationTraces_areInert() {
        assertThat(policy.needsBindingCompletion(List.of(
                new ToolInvocation("run_symbolic", "{}", "Symbolic result: 42", 0)))).isFalse();
        assertThat(policy.needsBindingCompletion(List.of())).isFalse();
        assertThat(policy.needsBindingCompletion(null)).isFalse();
    }

    @Test
    void relSlackParser_handlesEdges() {
        assertThat(BindingExtensionPolicy.hasRelSlackBelow("relative slack: 0.0003 (x)", 0.01)).isTrue();
        assertThat(BindingExtensionPolicy.hasRelSlackBelow("relative slack: 0.0100", 0.01)).isFalse();
        assertThat(BindingExtensionPolicy.hasRelSlackBelow("no slack here", 0.01)).isFalse();
        assertThat(BindingExtensionPolicy.hasRelSlackBelow("relative slack: garbage", 0.01)).isFalse();
    }
}
