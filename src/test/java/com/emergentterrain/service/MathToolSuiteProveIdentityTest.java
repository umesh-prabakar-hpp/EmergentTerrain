package com.emergentterrain.service;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.llm.SympyIdentityClient;
import com.emergentterrain.llm.SympyIdentityException;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 25 §25.3 — end-to-end exercise of the {@code prove_identity}
 * tool through the {@link MathToolSuite} dispatcher. Confirms:
 * <ul>
 *   <li>Tool absent from {@code toolsJson()} when SympyIdentityClient
 *       is null OR app.julia.sympy.enabled is false OR the sidecar is
 *       unreachable.</li>
 *   <li>Tool present in the schema when everything is wired.</li>
 *   <li>Dispatcher routes the call and formats the proof result with
 *       certificate verbatim so the panel can sanity-check.</li>
 *   <li>{@code no_closed_form} surfaces honestly, telling the LLM the
 *       claim must remain CONJECTURE/EMPIRICAL_RESULT.</li>
 *   <li>{@code busy} error code surfaces as a retry-friendly message.</li>
 *   <li>{@code safety_reject} from the JVM filter surfaces with a clear
 *       rephrase hint.</li>
 *   <li>Bad inputs return validator-style errors without touching the
 *       client.</li>
 * </ul>
 *
 * <p>Uses a stub {@link SympyIdentityClient} that overrides
 * {@code isAvailable()} and {@code prove(...)} so tests don't depend on
 * a live sidecar, PyCall, or SymPy installation.
 */
class MathToolSuiteProveIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void notAdvertisedWhenSympyClientAbsent() {
        MathToolSuite suite = new MathToolSuite((LlmRouter) null, MAPPER);
        assertThat(suite.toolsJson(true)).doesNotContain("prove_identity");
    }

    @Test
    void notAdvertisedWhenSympyDisabled() {
        SympyIdentityClient client = unavailableSympyClient();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        assertThat(suite.toolsJson(true)).doesNotContain("prove_identity");
    }

    @Test
    void advertisedWhenAvailable() {
        SympyIdentityClient client = stubSympyClient(
                new SympyIdentityClient.IdentityProof(
                        SympyIdentityClient.Status.PROVED,
                        "2**n",
                        "sympy.summation(binomial(n, k), (k, 0, n))",
                        "zeilberger_wz",
                        50));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);

        String schema = suite.toolsJson(true);
        assertThat(schema).contains("prove_identity");
        assertThat(schema).contains("Gosper");
        assertThat(schema).contains("Zeilberger");
        assertThat(schema).contains("EvidenceLabel.THEOREM");
    }

    @Test
    void dispatcherFormatsProvedResult() throws Exception {
        SympyIdentityClient client = stubSympyClient(
                new SympyIdentityClient.IdentityProof(
                        SympyIdentityClient.Status.PROVED,
                        "2**n",
                        "sympy.simplify(sympy.summation(binomial(n, k), (k, 0, n)))",
                        "zeilberger_wz",
                        654));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "algorithm", "auto",
                        "summand", "binomial(n, k)",
                        "var", "k",
                        "lower", "0",
                        "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("**PROVED**");
        assertThat(result).contains("zeilberger_wz");
        assertThat(result).contains("`2**n`");
        assertThat(result).contains("sympy.simplify");  // certificate verbatim
        assertThat(result).contains("EvidenceLabel.THEOREM");
        assertThat(result).contains("654 ms");
    }

    @Test
    void dispatcherFormatsNoClosedForm() throws Exception {
        SympyIdentityClient client = stubSympyClient(
                new SympyIdentityClient.IdentityProof(
                        SympyIdentityClient.Status.NO_CLOSED_FORM,
                        null, null,
                        "zeilberger_wz",
                        14));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "algorithm", "auto",
                        "summand", "1/k**2",
                        "var", "k",
                        "lower", "1",
                        "upper", "n",
                        "rationale", "harmonic sum probe")));
        String result = executor.apply(call);

        assertThat(result).contains("**no_closed_form**");
        assertThat(result).contains("CONJECTURE");
        assertThat(result).doesNotContain("EvidenceLabel.THEOREM");
    }

    @Test
    void dispatcherSurfacesBusyAsRetryableHint() throws Exception {
        SympyIdentityClient client = throwingSympyClient(
                new SympyIdentityException("busy",
                        "Another /prove_identity call is in progress; tried for 5000ms"));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "summand", "binomial(n, k)", "var", "k",
                        "lower", "0", "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("prove_identity busy");
        assertThat(result).contains("Retry");
    }

    @Test
    void dispatcherSurfacesSafetyRejectWithRephraseHint() throws Exception {
        SympyIdentityClient client = throwingSympyClient(
                new SympyIdentityException("safety_reject",
                        "Client-side filter rejected summand: contains forbidden pattern '__'"));
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "summand", "__import__('os').system('echo bad')",
                        "var", "k", "lower", "0", "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("safety-rejected");
        assertThat(result).contains("Rephrase");
    }

    @Test
    void dispatcherRejectsMissingSummandWithoutCallingClient() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SympyIdentityClient client = countingSympyClient(calls);
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "var", "k", "lower", "0", "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("Missing 'summand'");
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void dispatcherRejectsBadAlgorithm() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SympyIdentityClient client = countingSympyClient(calls);
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "algorithm", "lean_tactics",
                        "summand", "k", "var", "k", "lower", "0", "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("Invalid 'algorithm'");
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void dispatcherErrorWhenSidecarPathUnavailable() throws Exception {
        SympyIdentityClient client = unavailableSympyClient();
        MathToolSuite suite = new MathToolSuite(
                (LlmRouter) null, MAPPER, null, null, null, client);
        var executor = suite.buildExecutor(stubDraft());

        String call = MAPPER.writeValueAsString(Map.of(
                "name", "prove_identity",
                "arguments", Map.of(
                        "summand", "binomial(n, k)", "var", "k",
                        "lower", "0", "upper", "n",
                        "rationale", "test")));
        String result = executor.apply(call);

        assertThat(result).contains("not configured");
        assertThat(result).contains("app.julia.sympy.enabled=true");
    }

    // ── Stub builders ────────────────────────────────────────────────

    private static JuliaMathClient availableSidecarStub() {
        return new JuliaMathClient(true, "http://stub", 1_000, 500, MAPPER) {
            @Override public boolean isAvailable() { return true; }
        };
    }

    private static SympyIdentityClient stubSympyClient(
            SympyIdentityClient.IdentityProof cannedProof) {
        return new SympyIdentityClient(
                /*enabled*/ true, "http://stub", /*timeoutMs*/ 5_000,
                availableSidecarStub(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public IdentityProof prove(String algorithm, String summand,
                    String var, String lower, String upper, Optional<String> target) {
                return cannedProof;
            }
        };
    }

    private static SympyIdentityClient throwingSympyClient(SympyIdentityException toThrow) {
        return new SympyIdentityClient(
                true, "http://stub", 5_000, availableSidecarStub(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public IdentityProof prove(String algorithm, String summand,
                    String var, String lower, String upper, Optional<String> target) {
                throw toThrow;
            }
        };
    }

    private static SympyIdentityClient unavailableSympyClient() {
        return new SympyIdentityClient(
                false, "http://stub", 5_000, availableSidecarStub(), MAPPER) {
            @Override public boolean isAvailable() { return false; }
        };
    }

    private static SympyIdentityClient countingSympyClient(
            java.util.concurrent.atomic.AtomicInteger calls) {
        return new SympyIdentityClient(
                true, "http://stub", 5_000, availableSidecarStub(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public IdentityProof prove(String algorithm, String summand,
                    String var, String lower, String upper, Optional<String> target) {
                calls.incrementAndGet();
                return new IdentityProof(Status.PROVED, "0", "stub", "stub", 0);
            }
        };
    }

    private static PaperDraft stubDraft() {
        // Same shape as MathToolSuiteSymbolicRegressTest's stub — copied
        // verbatim to keep this test independent of that one.
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", "prove_identity test",
                "Test", "raw", "h", "c", "EMPIRICAL", java.util.List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, java.util.List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED, null);
    }
}
