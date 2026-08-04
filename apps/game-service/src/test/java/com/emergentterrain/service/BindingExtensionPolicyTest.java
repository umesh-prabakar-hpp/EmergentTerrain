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
        // Only the same relation/grid refutation completes the demonstration.
        String heldArgs = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        String refutedArgs = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":-1,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        List<ToolInvocation> trace = List.of(
                verify(heldArgs, "verify_conjecture: x <= y HELD across 10 window(s). relative slack: 0.0018."),
                verify(refutedArgs, "verify_conjecture: x <= y REFUTED across 10 window(s). worst margin: +3.657"));
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
        assertThat(BindingExtensionPolicy.hasRelSlackAtMost("relative slack: 0.0500", 0.05)).isTrue();
        assertThat(BindingExtensionPolicy.hasRelSlackAtMost("relative slack: 0.0549", 0.05)).isFalse();
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
    void toolNamedTightening_preservesTheGridAndUsesTheExactVerifierCoefficient() throws Exception {
        String args = "{\"lhs\":\"richly_divisible_density\",\"rhs\":\"mean_divisor_excess\","
                + "\"k\":2,\"c\":-1.7076,\"window_start\":1500000,\"window_size\":5000,"
                + "\"num_windows\":20}";

        var completion = policy.forcedBindingTightening(List.of(verify(args,
                "verify_conjecture: richly_divisible_density <= +2.0000 * mean_divisor_excess + -1.7076 "
                        + "HELD across 20 window(s). relative slack: 0.1291. LOOSE: the tightest k "
                        + "that holds these windows is ~1.9358 — re-verify at k=1.9360 (keep c=-1.7076) "
                        + "for a TIGHT bound.")));

        assertThat(completion).isPresent();
        assertThat(completion.orElseThrow().coefficient()).isEqualTo("1.9360");
        com.fasterxml.jackson.databind.JsonNode tightened = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(completion.orElseThrow().argumentsJson());
        assertThat(tightened.path("k").asDouble()).isEqualTo(1.9360);
        assertThat(tightened.path("c").asDouble()).isEqualTo(-1.7076);
        assertThat(tightened.path("window_start").asLong()).isEqualTo(1_500_000L);
        assertThat(tightened.path("window_size").asInt()).isEqualTo(5_000);
        assertThat(tightened.path("num_windows").asInt()).isEqualTo(20);
    }

    @Test
    void toolNamedTightening_doesNotRepeatAnExactPriorAttemptOrTouchTwoTermBounds() {
        String oneTerm = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1.5,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        String tightened = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1.2,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        String loose = "verify_conjecture: x <= +1.5000 * y + +0.0000 HELD across 10 window(s). "
                + "relative slack: 0.2000. LOOSE: re-verify at k=1.2000 (keep c=0).";
        assertThat(policy.forcedBindingTightening(List.of(verify(oneTerm, loose),
                verify(tightened, "verify_conjecture: x <= +1.2000 * y + +0.0000 REFUTED across 10 window(s)."))))
                .isEmpty();

        String twoTerm = oneTerm.replace("\"k\":1.5", "\"rhs2\":\"z\",\"k\":1.5,\"k2\":0.4");
        assertThat(policy.forcedBindingTightening(List.of(verify(twoTerm, loose)))).isEmpty();
    }

    @Test
    void independentReplication_preservesTheBoundAndLocksADeterministicSecondGrid() throws Exception {
        String args = "{\"lhs\":\"max_prime_gap\",\"rhs\":\"log_sq_window_start\","
                + "\"k\":0.424,\"c\":0,\"window_start\":900000000,"
                + "\"window_size\":10000,\"num_windows\":10}";

        String replication = BindingExtensionPolicy.independentReplicationArguments(args).orElseThrow();
        com.fasterxml.jackson.databind.JsonNode parsed =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(replication);

        assertThat(parsed.path("lhs").asText()).isEqualTo("max_prime_gap");
        assertThat(parsed.path("rhs").asText()).isEqualTo("log_sq_window_start");
        assertThat(parsed.path("k").asDouble()).isEqualTo(0.424);
        assertThat(parsed.path("c").asDouble()).isZero();
        assertThat(parsed.path("window_size").asLong()).isEqualTo(10_000L);
        assertThat(parsed.path("num_windows").asInt()).isEqualTo(10);
        assertThat(parsed.path("window_start").asLong()).isEqualTo(1_350_000_000L);
    }

    @Test
    void toolNamedRefutation_doesNotRunAfterSameGridRefutation() {
        String heldArgs = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        String refutedArgs = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":-1,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        assertThat(policy.forcedBindingRefutation(List.of(
                verify(heldArgs, "verify_conjecture: x <= y HELD across 10 window(s). TIGHT HOLD — "
                        + "re-verify with c=-1.48048."),
                verify(refutedArgs, "verify_conjecture: x <= y REFUTED across 10 window(s).")))).isEmpty();
    }

    @Test
    void unrelatedRefutationDoesNotBlockTheToolNamedBindingCompletion() {
        String heldArgs = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        String unrelatedArgs = "{\"lhs\":\"x\",\"rhs\":\"z\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000,\"window_size\":500,\"num_windows\":10}";
        assertThat(policy.forcedBindingRefutation(List.of(
                verify(heldArgs, "verify_conjecture: x <= y HELD across 10 window(s). TIGHT HOLD — "
                        + "re-verify with c=-1.48048."),
                verify(unrelatedArgs, "verify_conjecture: x <= z REFUTED across 10 window(s).")))).isPresent();
    }
}
