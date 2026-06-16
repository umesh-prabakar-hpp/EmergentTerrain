package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.research.conjecture.ConjectureVerifier;
import com.emergentterrain.research.conjecture.MathConjectureSubstrateDelegate;
import com.fasterxml.jackson.databind.JsonNode;
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
        try {
            JsonNode root = mapper.readTree(suite.toolsJson(true));
            assertThat(root.isArray()).isTrue();
        } catch (Exception e) {
            throw new AssertionError("verifier-wired toolsJson should parse cleanly", e);
        }
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
        // REVIEW_REPORT b72c4e10 batch 1 rec #1 — the margin/holds convention legend is stated inline.
        assertThat(result).contains("verdict legend: margin = lhs - bound");
        assertThat(result).contains("per-window holds/REFUTED label is authoritative");
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
    void autoProbeRefutationIsSeparatedFromRequestedRange() throws Exception {
        // REVIEW_REPORT b72c4e10 §1 rec #3: when the appended binding-regime
        // probe (start=2000) refutes but every REQUESTED window holds, the
        // output must say so explicitly rather than letting the headline
        // REFUTED / "worst margin at start 2000" stand unqualified.
        ObjectMapper mapper = new ObjectMapper();
        ConjectureVerifier verifier = verifierWithSubstrate();
        verifier.setBoundaryProbeEnabled(true);   // probe is flag-gated; enable for this test
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper, null, null, verifier);
        var executor = suite.buildExecutor(stubDraft());

        // lhs=abundant_density (stub=0.247), bound = 2*log10(start) - 7.7621.
        // start=200000 -> bound=+2.840 -> 0.247 HOLDS; appended probe at
        // start=2000 -> bound=-1.160 -> 0.247 REFUTES. So the requested range
        // all holds; only the probe refutes.
        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density",
                        "rhs", "log_window_start",
                        "k", 2.0,
                        "c", -7.7621,
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);
        // Headline still reflects the global (correct) refutation...
        assertThat(result).contains("REFUTED");
        // ...but the requested range is broken out as all-HELD, attributing the
        // refutation solely to the probe.
        assertThat(result).contains("binding-regime auto-probe");
        assertThat(result).contains("NOT part of the requested range");
        assertThat(result).contains("Requested range (start>=200000): all 3 window(s) HELD");
        assertThat(result).contains("due solely to the appended probe");
    }

    @Test
    void logWindowScalarIsLabeledBaseTen() throws Exception {
        // REVIEW_REPORT b72c4e10 §1 rec #2: log_window_start is base-10, so the
        // output must say so (and not let a narrator read it as natural log).
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density",
                        "rhs", "log_window_start",
                        "k", 2.0,
                        "c", -7.7621,
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);
        assertThat(result).contains("log_window_start is base-10 log");
        assertThat(result).contains("ln(10)=2.302585");
        // §1 rec #2 refinement (P-b72c-24 mis-applied "multiply by ln(10)" to the
        // coefficient): the label must give the concrete natural-log slope
        // k/ln(10) and forbid the multiply. For k=2.0 that is 2/ln(10)=0.86859.
        assertThat(result).contains("natural-log slope is k/ln(10)=+0.86859");
        assertThat(result).contains("do NOT multiply");
        assertThat(result).doesNotContain("4.605");   // the wrong k*ln(10) value
    }

    @Test
    void nonLogScalarHasNoBaseLabel() throws Exception {
        // A non-log rhs (prime_density) must NOT carry the base-10 note.
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

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
        assertThat(result).doesNotContain("base-10 log");
    }

    @Test
    void noAutoProbeAnnotationWhenProbeDisabled() throws Exception {
        // Same call with the probe OFF: no appended window below the start, so
        // no auto-probe clause and the requested windows stand on their own.
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density",
                        "rhs", "log_window_start",
                        "k", 2.0,
                        "c", -7.7621,
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);
        assertThat(result).contains("HELD");
        assertThat(result).doesNotContain("binding-regime auto-probe");
        assertThat(result).doesNotContain("Requested range");
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
     * Build a ConjectureVerifier with a stub delegate wired through the
     * test-only seam. §28.6 made the delegate sidecar-only (the in-process
     * MathConjectureSubstrate was deleted), so this stub returns a fixed,
     * realistic per-window scalar map instead — no sidecar needed. With a
     * null sidecar client the verifier skips the batched /conjecture/verify
     * path and runs the per-window path against this stub.
     *
     * <p>{@code abundant_density=0.247} is the key value the two dispatcher
     * tests pivot on: it holds under the loose 0.30 bound and is refuted by
     * the tight 0.05 bound, matching the real density at these windows.
     */
    private static ConjectureVerifier verifierWithSubstrate() {
        MathConjectureSubstrateDelegate stub =
                new MathConjectureSubstrateDelegate((JuliaMathClient) null) {
                    @Override
                    public Map<String, Double> evaluateWindow(int start, int size) {
                        return Map.of(
                                "abundant_density",   0.247,
                                "deficient_density",  0.748,
                                "prime_density",      0.080,
                                "happy_density",      0.140,
                                "mean_omega",         2.90,
                                "mean_Omega",         3.70,
                                "mean_divisor_count", 15.0,
                                "max_divisor_count",  80.0,
                                "mertens_delta",     -3.0);
                    }
                };
        ConjectureVerifier verifier = new ConjectureVerifier();
        verifier.setSubstrateDelegate(stub);
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
