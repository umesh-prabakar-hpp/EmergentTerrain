package com.emergentterrain.service;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.llm.JuliaMathException;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 24 §24.3 — end-to-end exercise of the {@code symbolic_regress}
 * tool through the {@link MathToolSuite} dispatcher. Confirms:
 * <ul>
 *   <li>Tool absent from {@code toolsJson()} when the Julia/PySR path
 *       is unavailable (null client, pysr disabled, or unreachable).</li>
 *   <li>Tool present in the schema when everything is wired.</li>
 *   <li>Dispatcher routes the call and formats the Pareto result.</li>
 *   <li>{@code busy} error code surfaces as a retry-friendly message.</li>
 *   <li>Bad sample shape produces a clear error from the JVM-side
 *       validator (no sidecar HTTP attempted).</li>
 * </ul>
 *
 * <p>Uses a stub {@link JuliaMathClient} that overrides {@code pysr} so
 * tests don't depend on a live sidecar or HTTP transport.
 */
class MathToolSuiteSymbolicRegressTest {

    @Test
    void notAdvertisedWhenJuliaClientAbsent() {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper);

        assertThat(suite.toolsJson(true)).doesNotContain("symbolic_regress");
    }

    @Test
    void notAdvertisedWhenPysrDisabled() {
        ObjectMapper mapper = new ObjectMapper();
        // pysrEnabled=false → isPysrAvailable() is false even if the
        // sidecar /healthz would succeed.
        JuliaMathClient client = pysrUnavailableClient();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);

        assertThat(suite.toolsJson(true)).doesNotContain("symbolic_regress");
    }

    @Test
    void advertisedWhenPysrAvailable() {
        ObjectMapper mapper = new ObjectMapper();
        JuliaMathClient client = stubPysrClient(new java.util.ArrayList<>());
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);

        String schema = suite.toolsJson(true);
        assertThat(schema).contains("symbolic_regress");
        assertThat(schema).contains("Pareto front");
        assertThat(schema).contains("var_names");
    }

    @Test
    void operatorsSchemaHasNoSlashBearingEnum_grokCompat() {
        // Phase 29 §1.A — xAI/grok rejects tool schemas whose enum string values
        // contain '/'. The operators property previously carried
        // enum:["+","-","*","/",...]; it must no longer ship a '/'-bearing enum
        // (the allowlist moved to the description; the sidecar enforces it).
        ObjectMapper mapper = new ObjectMapper();
        JuliaMathClient client = stubPysrClient(new java.util.ArrayList<>());
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper, null, client, null);

        String schema = suite.toolsJson(true);
        assertThat(schema).contains("symbolic_regress");
        assertThat(schema).doesNotContain("[\"+\",\"-\",\"*\",\"/\"");
    }

    @Test
    void dispatcherFormatsParetoFront() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Stub returns two Pareto entries; expect them rendered as a
        // Markdown table in the result string.
        List<JuliaMathClient.ParetoEntry> entries = List.of(
                new JuliaMathClient.ParetoEntry("x", 4.0, 1),
                new JuliaMathClient.ParetoEntry("x * y", 1.2e-9, 3));
        JuliaMathClient client = stubPysrClient(entries);
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "symbolic_regress",
                "arguments", Map.of(
                        "samples", List.of(
                                List.of(1.0, 2.0, 2.0),
                                List.of(2.0, 3.0, 6.0),
                                List.of(3.0, 4.0, 12.0)),
                        "var_names", List.of("x", "y"),
                        "target_name", "f",
                        "rationale", "test recovery of x*y")));

        String result = executor.apply(call);
        assertThat(result).startsWith("symbolic_regress:");
        assertThat(result).contains("Pareto front");
        assertThat(result).contains("| Complexity | MSE | Expression |");
        assertThat(result).contains("`x`");
        assertThat(result).contains("`x * y`");
    }

    @Test
    void notConfiguredErrorWhenClientAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, mapper);
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "symbolic_regress",
                "arguments", Map.of(
                        "samples", List.of(List.of(1.0, 2.0, 2.0)),
                        "var_names", List.of("x", "y"),
                        "rationale", "test")));

        String result = executor.apply(call);
        assertThat(result).contains("symbolic_regress not configured");
    }

    @Test
    void busyExceptionSurfacesRetryMessage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JuliaMathClient client = throwingPysrClient(
                new JuliaMathException("busy", "another /pysr in progress"));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);
        var executor = suite.buildExecutor(stubDraft());

        String call = mapper.writeValueAsString(Map.of(
                "name", "symbolic_regress",
                "arguments", Map.of(
                        "samples", List.of(List.of(1.0, 2.0, 2.0)),
                        "var_names", List.of("x", "y"),
                        "rationale", "test")));

        String result = executor.apply(call);
        assertThat(result).startsWith("symbolic_regress busy");
        assertThat(result).contains("Retry");
    }

    @Test
    void badSampleShapeSurfacesValidatorError() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        JuliaMathClient client = countingPysrClient(calls);
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);
        var executor = suite.buildExecutor(stubDraft());

        // var_names says 2 vars; row has 4 numbers → mismatch.
        String call = mapper.writeValueAsString(Map.of(
                "name", "symbolic_regress",
                "arguments", Map.of(
                        "samples", List.of(List.of(1.0, 2.0, 3.0, 4.0)),
                        "var_names", List.of("x", "y"),
                        "rationale", "test")));

        String result = executor.apply(call);
        assertThat(result).contains("must be a numeric row of length 3");
        // The validator failed BEFORE the JuliaMathClient.pysr call.
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void tooManySamplesIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        JuliaMathClient client = countingPysrClient(calls);
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, mapper, null, client, null);
        var executor = suite.buildExecutor(stubDraft());

        java.util.List<java.util.List<Double>> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            tooMany.add(List.of((double) i, (double) (i + 1), (double) i * (i + 1)));
        }
        String call = mapper.writeValueAsString(Map.of(
                "name", "symbolic_regress",
                "arguments", Map.of(
                        "samples", tooMany,
                        "var_names", List.of("x", "y"),
                        "rationale", "test")));

        String result = executor.apply(call);
        assertThat(result).contains("Too many samples");
        assertThat(calls.get()).isEqualTo(0);
    }

    // ── Stub builders ───────────────────────────────────────────────

    private static JuliaMathClient stubPysrClient(List<JuliaMathClient.ParetoEntry> entries) {
        return new JuliaMathClient(true, "http://stub", 1_000, 500,
                /*pysrEnabled*/ true, 20_000, 8, 40, 30,
                new ObjectMapper()) {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean isPysrAvailable() { return true; }
            @Override
            public PysrResult pysr(PysrRequest request) {
                return new PysrResult(entries, entries.size(), 1500);
            }
        };
    }

    private static JuliaMathClient pysrUnavailableClient() {
        return new JuliaMathClient(true, "http://stub", 1_000, 500,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                new ObjectMapper()) {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean isPysrAvailable() { return false; }
        };
    }

    private static JuliaMathClient throwingPysrClient(JuliaMathException toThrow) {
        return new JuliaMathClient(true, "http://stub", 1_000, 500,
                true, 20_000, 8, 40, 30,
                new ObjectMapper()) {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean isPysrAvailable() { return true; }
            @Override
            public PysrResult pysr(PysrRequest request) {
                throw toThrow;
            }
        };
    }

    private static JuliaMathClient countingPysrClient(AtomicReference<Integer> counter) {
        return new JuliaMathClient(true, "http://stub", 1_000, 500,
                true, 20_000, 8, 40, 30,
                new ObjectMapper()) {
            @Override
            public boolean isAvailable() { return true; }
            @Override
            public boolean isPysrAvailable() { return true; }
            @Override
            public PysrResult pysr(PysrRequest request) {
                counter.set(counter.get() + 1);
                return new PysrResult(List.of(), 0, 0);
            }
        };
    }

    private static PaperDraft stubDraft() {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "PHYSICS", "Symbolic regression test",
                "Test", "raw", "h", "c", "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
