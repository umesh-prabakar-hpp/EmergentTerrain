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
        // rec #1 follow-on — strict-vs-tolerance hold split on a HELD result.
        assertThat(result).contains("hold strictly (margin <= 0)");
        assertThat(result).contains("hold only within tolerance");
    }

    @Test
    void successfulOscillationVerificationCountsTowardD3Successes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
        var executor = suite.buildExecutor(stubDraft(), metrics);

        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_oscillation",
                "arguments", Map.of(
                        "lhs", "mertens_delta",
                        "window_start", 200_000,
                        "window_size", 500,
                        "num_windows", 3)));

        String result = executor.apply(call);

        assertThat(result).startsWith("verify_oscillation:");
        assertThat(metrics.verifyOscillationSuccessCount()).isEqualTo(1);
        assertThat(metrics.totalSuccesses()).isEqualTo(1);
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

    @org.junit.jupiter.api.Test
    void scalarDefinitions_appendedForKnownNames_p786() throws Exception {
        // 2026-07-05 (run-23 rec 1, P-b72c-77627786): the exact instrument formula rides along in
        // the tool result so instrument hypotheses (e.g. "the excess baseline uses window_start")
        // resolve inside the trace. Center-vs-start is the pinned ambiguity.
        ObjectMapper om = new ObjectMapper();
        JsonNode args = om.readTree(
                "{\"lhs\":\"mean_divisor_excess\",\"rhs\":\"twin_vs_prime_sq\"}");
        String out = MathToolSuite.appendScalarDefinitions("verify_constant: …", args);
        org.assertj.core.api.Assertions.assertThat(out)
                .contains("definitions (exact instrument formulas):")
                .contains("mean_divisor_excess = mean_divisor_count - ln(window_center)")
                .contains("NOT window_start")
                .contains("twin_vs_prime_sq = twin_prime_density / prime_density^2");
        // Unknown names / absent slots: result unchanged.
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.appendScalarDefinitions("r", om.readTree("{\"lhs\":\"nope\"}")))
                .isEqualTo("r");
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.appendScalarDefinitions("r", om.readTree("{}"))).isEqualTo("r");
    }

    @org.junit.jupiter.api.Test
    void capFixHint_namesTheCopyableFix_p788() {
        // 2026-07-05 (run-24 rec 2): the P-788 shape — window_size 10000 against the 100k cap —
        // must yield the literal corrected parameter, not just the diagnosis.
        org.assertj.core.api.Assertions.assertThat(MathToolSuite.capFixHint(10000, 100_000))
                .isEqualTo("FIX: keep window_size=10000 and use num_windows ≤ 10.");
        // window_size alone over the cap → the fix is the window size itself.
        org.assertj.core.api.Assertions.assertThat(MathToolSuite.capFixHint(200_000, 100_000))
                .contains("window_size ≤ 100000");
    }

    @org.junit.jupiter.api.Test
    void ladderRungWindowSize_widensUnderBudget_p793() {
        // run-25 rec 1: the P-793 shape (window_size 5000, num_windows 20, budget 5e6) must
        // widen each rung window to 250k integers — ~15k twins per rung at 1e9 instead of ~300.
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.ladderRungWindowSize(5000, 20, 5_000_000L)).isEqualTo(250_000);
        // budget 0 = legacy shape, untouched.
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.ladderRungWindowSize(5000, 20, 0L)).isEqualTo(5000);
        // never below the caller's ask (tiny budget), never above the sanity cap (huge budget).
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.ladderRungWindowSize(5000, 20, 1_000L)).isEqualTo(5000);
        org.assertj.core.api.Assertions.assertThat(
                MathToolSuite.ladderRungWindowSize(5000, 1, 9_999_999_999L)).isEqualTo(50_000_000);
    }

    @org.junit.jupiter.api.Test
    void scaleLadderFailsBeforeSchedulingWhenJuliaConjectureSidecarIsUnavailable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, new ConjectureVerifier());

        String result = suite.buildExecutor(stubDraft()).apply(mapper.writeValueAsString(Map.of(
                "name", "verify_constant",
                "arguments", Map.of("lhs", "richly_divisible_density", "scale_ladder", true))));

        assertThat(result).startsWith("verify_constant scale-ladder unavailable:")
                .contains("No broad logical range or chunk was scheduled");
    }

    @org.junit.jupiter.api.Test
    void tightHold_namesTheLiteralRefutingIntercept_p796() throws Exception {
        // run-26 rec 2: a TIGHT hold previously got NO hint (LOOSE branches fire on large slack
        // only) — P-796 reached rel-slack 0.0026 and spent its extension re-holding. The tool
        // must now name the exact refuting intercept and forbid further holds.
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());
        // c barely above the abundant density (~0.247) → tight hold, small relative slack.
        String call = mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density", "rhs", "prime_density",
                        "k", 0.0, "c", 0.2523,
                        "window_start", 200_000, "window_size", 500, "num_windows", 3)));
        String result = executor.apply(call);
        assertThat(result).contains("HELD")
                .contains("TIGHT HOLD — one call from the demonstrated binding pair")
                .contains("re-verify with c=")
                .contains("Do NOT re-verify more holds");
    }

    @org.junit.jupiter.api.Test
    void bindingInstructionUsesThePublicationGateTightnessBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, verifierWithSubstrate());
        var executor = suite.buildExecutor(stubDraft());

        // abundant_density is fixed at 0.247. This intercept yields exactly 5% relative slack,
        // which publication accepts as a tight binding result.
        String atBoundary = executor.apply(mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density", "rhs", "prime_density",
                        "k", 0.0, "c", 0.23523809523809525,
                        "window_start", 200_000, "window_size", 500, "num_windows", 3))));
        assertThat(atBoundary).contains("HELD").contains("TIGHT HOLD");

        // This is the live-run regression shape (about 5.49% slack): it must be narrated as
        // loose/tighten-needed rather than prompting a false demonstrated-binding claim.
        String aboveBoundary = executor.apply(mapper.writeValueAsString(Map.of(
                "name", "verify_conjecture",
                "arguments", Map.of(
                        "lhs", "abundant_density", "rhs", "prime_density",
                        "k", 0.0, "c", 0.23416,
                        "window_start", 200_000, "window_size", 500, "num_windows", 3))));
        assertThat(aboveBoundary).contains("HELD").contains("LOOSE:")
                .doesNotContain("TIGHT HOLD");
    }

    @org.junit.jupiter.api.Test
    void toolsJsonParses_withReferenceReaderWired() throws Exception {
        // 2026-07-06 REGRESSION GUARD: the read_reference schema shipped with text-block \"
        // escapes that collapse to RAW quotes -> invalid tools JSON -> EVERY professor path
        // errored and the run published zero papers. Any conditionally-advertised tool must be
        // covered by a wired-parse test like this one.
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, null, new ConjectureVerifier());
        java.nio.file.Path corpus = java.nio.file.Files.createTempDirectory("cores");
        java.nio.file.Files.createDirectories(corpus.resolve("cores"));
        suite.setReferenceReader(
                new com.emergentterrain.research.literature.ReferenceCoreReader(corpus.toString()));
        String json = suite.toolsJson(true);
        org.assertj.core.api.Assertions.assertThat(json).contains("read_reference");
        JsonNode root = mapper.readTree(json);   // throws on the regression
        org.assertj.core.api.Assertions.assertThat(root.isArray()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void deriveValue_advertisedAlways_andComputesWithProvenanceNote() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper);
        String json = suite.toolsJson(true);
        org.assertj.core.api.Assertions.assertThat(json).contains("derive_value");
        org.assertj.core.api.Assertions.assertThat(mapper.readTree(json).isArray()).isTrue();

        var executor = suite.buildExecutor(stubDraft());
        String result = executor.apply(mapper.writeValueAsString(Map.of(
                "name", "derive_value",
                "arguments", Map.of(
                        "expression", "(0.33674 - 0.23575) / 4",
                        "label", "mean per-decade Wigert-gap decrement"))));
        org.assertj.core.api.Assertions.assertThat(result)
                .startsWith("derive_value:")
                .contains("0.02524")
                .contains("mean per-decade Wigert-gap decrement")
                .contains("carries provenance");
        String bad = executor.apply(mapper.writeValueAsString(Map.of(
                "name", "derive_value", "arguments", Map.of("expression", "k * 2"))));
        org.assertj.core.api.Assertions.assertThat(bad).contains("could not parse");
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
