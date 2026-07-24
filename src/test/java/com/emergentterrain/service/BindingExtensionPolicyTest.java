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

    private static ToolInvocation verify(String args, String result) {
        return new ToolInvocation("verify_conjecture", args, result, 0);
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

    @Test
    void toolNamedRefutation_preservesTheHeldGridAndUsesTheExactIntercept() {
        String args = "{\"lhs\":\"cramer_gap_ratio\",\"rhs\":\"deficient_density\","
                + "\"rhs2\":\"omega_excess_density\",\"k\":2,\"k2\":2,"
                + "\"c\":-1.4005,\"window_start\":1200007,\"window_size\":5000,"
                + "\"num_windows\":10}";
        var completion = policy.forcedBindingRefutation(List.of(verify(args,
                "verify_conjecture: x <= y HELD across 10 window(s). TIGHT HOLD — one call "
                        + "from the demonstrated binding pair: re-verify with c=-1.48048 "
                        + "(same k/k2; it should REFUTE).")));

        assertThat(completion).isPresent();
        assertThat(completion.orElseThrow().functionName()).isEqualTo("verify_conjecture");
        assertThat(completion.orElseThrow().argumentsJson()).isEqualTo(args);
        assertThat(completion.orElseThrow().intercept()).isEqualTo("-1.48048");
    }

    @Test
    void toolNamedRefutation_doesNotRunAfterAnyRefutedVerification() {
        assertThat(policy.forcedBindingRefutation(List.of(
                verify("verify_conjecture: x <= y HELD across 10 window(s). TIGHT HOLD — "
                        + "re-verify with c=-1.48048."),
                verify("verify_conjecture: x <= y REFUTED across 10 window(s).")))).isEmpty();
    }
}
