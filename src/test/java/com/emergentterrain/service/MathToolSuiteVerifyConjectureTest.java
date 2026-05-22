package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrate;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23.5 — end-to-end exercise of the verify_conjecture tool through
 * the {@link MathToolSuite} dispatcher. Confirms:
 * <ul>
 *   <li>Tool schema appears in {@code toolsJson()} only when a verifier
 *       is wired (so non-conjecture deployments don't advertise it).</li>
 *   <li>Dispatcher routes the call to {@link ConjectureVerifier}.</li>
 *   <li>Held vs. refuted scenarios surface the right header line.</li>
 *   <li>Missing-config path returns the documented not-configured message.</li>
 * </ul>
 */
class MathToolSuiteVerifyConjectureTest {

    @Test
    void notAdvertisedWhenVerifierAbsent() {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper);

        assertThat(suite.toolsJson(true))
                .doesNotContain("verify_conjecture");
    }

    @Test
    void advertisedWhenVerifierWired() {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, new ConjectureVerifier());

        assertThat(suite.toolsJson(true)).contains("verify_conjecture");
        assertThat(suite.toolsJson(true)).contains("window_start");
    }

    @Test
    void dispatcherRoutesHeldCallToVerifier() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

        // Held-out window the Python miner didn't see (200_000), bound
        // looser than asymptotic abundant density (~0.247) so it holds.
        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density",
                        "rhs", "prime_density",
                        "k", 0.0,
                        "c", 0.30,
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);
        assertThat(result).startsWith("verify_conjecture:");
        assertThat(result).contains("HELD");
        assertThat(result).contains("worst margin");
        assertThat(result).contains("Per-window:");
    }

    @Test
    void dispatcherRoutesRefutedCallToVerifier() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

        // Tight bound that's wrong: claim abundant_density <= 0.05.
        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density",
                        "rhs", "prime_density",
                        "k", 0.0,
                        "c", 0.05,
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);
        assertThat(result).startsWith("verify_conjecture:");
        assertThat(result).contains("REFUTED");
    }

    @Test
    void notConfiguredErrorWhenVerifierAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper);
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density", "rhs", "prime_density",
                        "k", 0.0, "c", 0.5,
                        "window_start", 200_000, "window_size", 500)));

        String result = executor.apply(call);
        assertThat(result).contains("verify_conjecture not configured");
    }

    @Test
    void invalidArgsSurfaceCleanError() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, new ConjectureVerifier());
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "definitely_not_a_scalar",
                        "rhs", "prime_density",
                        "k", 0.0, "c", 0.5,
                        "window_start", 200_000, "window_size", 500)));

        String result = executor.apply(call);
        assertThat(result).startsWith("Invalid verify_conjecture call:");
    }

    /**
     * Build a ConjectureVerifier with a delegate wired through the
     * test-only seam. The §28.2 refactor moved substrate ownership to
     * MathConjectureSubstrateDelegate (Spring-autowired in production);
     * tests that construct ConjectureVerifier directly must inject the
     * delegate or {@code substrate.evaluateWindow} NPEs at line 145.
     *
     * <p>SPF sieve sized to cover the test's window range
     * (windowStart=200_000, numWindows × windowSize ≤ 1_500) with
     * headroom — keeps the per-test cost under 50 ms.
     */
    private static ConjectureVerifier verifierWithSubstrate() {
        MathConjectureSubstrate substrate = new MathConjectureSubstrate(210_000);
        MathConjectureSubstrateDelegate delegate =
                new MathConjectureSubstrateDelegate(substrate, /* juliaMathClient */ null);
        ConjectureVerifier verifier = new ConjectureVerifier();
        verifier.setSubstrateDelegate(delegate);
        return verifier;
    }

    /** Same shape as the other MathToolSuite tests' stubDraft. */
    private static PaperDraft stubDraft() {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "Conjecture Investigation",
                "Test", "raw", "h", "c", "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
