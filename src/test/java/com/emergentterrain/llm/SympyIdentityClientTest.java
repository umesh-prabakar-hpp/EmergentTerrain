package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 25 §25.2 — unit tests for {@link SympyIdentityClient}.
 *
 * <p>Same stub-server pattern as {@link JuliaMathClientTest}: a
 * loopback {@code com.sun.net.httpserver.HttpServer} returns canned
 * responses so the client's parsing + safety + envelope logic gets
 * coverage without needing a real Python+SymPy chain in CI.
 *
 * <p>Live integration against a real sidecar + SymPy is implicit in
 * the sidecar's own runtests.jl (phase 25.1) — no separate Java
 * integration test in this slice.
 */
class SympyIdentityClientTest {

    private HttpServer stub;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<CannedResponse> nextResponse = new AtomicReference<>();

    private record CannedResponse(int status, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = stub.getAddress().getPort();
        stub.createContext("/", this::handleRequest);
        stub.setExecutor(Executors.newSingleThreadExecutor());
        stub.start();
    }

    @AfterEach
    void tearDown() {
        if (stub != null) stub.stop(0);
    }

    private void handleRequest(HttpExchange ex) throws IOException {
        lastRequestPath.set(ex.getRequestURI().getPath());
        byte[] requestBody = ex.getRequestBody().readAllBytes();
        lastRequestBody.set(new String(requestBody, StandardCharsets.UTF_8));
        CannedResponse cr = nextResponse.get();
        if (cr == null) {
            // Default healthz-ish ok envelope so calls don't all need to set one.
            cr = new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":1}");
        }
        byte[] body = cr.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(cr.status(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Build a JuliaMathClient pointing at the stub; serves as the sidecar
     * reachability authority for SympyIdentityClient. */
    private JuliaMathClient buildSidecar(boolean enabled) {
        JuliaMathClient client = new JuliaMathClient(
                enabled, "http://127.0.0.1:" + port, 2_000, 1_000, mapper);
        if (enabled) client.probeHealth();
        return client;
    }

    private SympyIdentityClient buildClient(boolean enabled) {
        return new SympyIdentityClient(
                enabled,
                "http://127.0.0.1:" + port,
                /*timeoutMs*/ 5_000,
                buildSidecar(true),
                mapper);
    }

    // ── Enablement / availability ────────────────────────────────────

    @Test
    void disabledClient_isAvailable_returnsFalse() {
        SympyIdentityClient client = buildClient(false);
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void enabledClient_withReachableSidecar_isAvailable() {
        SympyIdentityClient client = buildClient(true);
        assertThat(client.isEnabled()).isTrue();
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void disabledClient_throwsNotEnabled_onProveCall() {
        SympyIdentityClient client = buildClient(false);
        assertThatThrownBy(() ->
                client.prove("auto", "binomial(n, k)", "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("not_enabled"));
    }

    // ── Happy paths ─────────────────────────────────────────────────

    @Test
    void prove_returnsProved_andSurfacesCertificateAndAlgorithmUsed() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"proved\"," +
                "\"result\":\"2**n\"," +
                "\"certificate\":\"sympy.simplify(sympy.summation(...))\"," +
                "\"algorithm_used\":\"zeilberger_wz\"," +
                "\"elapsed_ms\":654}"));
        SympyIdentityClient client = buildClient(true);

        SympyIdentityClient.IdentityProof proof =
                client.prove("auto", "binomial(n, k)", "k", "0", "n",
                        Optional.empty());

        assertThat(proof.status()).isEqualTo(SympyIdentityClient.Status.PROVED);
        assertThat(proof.result()).isEqualTo("2**n");
        assertThat(proof.certificate()).contains("sympy.simplify");
        assertThat(proof.algorithmUsed()).isEqualTo("zeilberger_wz");
        assertThat(proof.wallMs()).isEqualTo(654);
        assertThat(lastRequestPath.get()).isEqualTo("/prove_identity");
    }

    @Test
    void prove_returnsNoClosedForm_whenSidecarReportsIt() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"no_closed_form\"," +
                "\"result\":null,\"certificate\":null," +
                "\"algorithm_used\":\"zeilberger_wz\",\"elapsed_ms\":14}"));
        SympyIdentityClient client = buildClient(true);

        SympyIdentityClient.IdentityProof proof =
                client.prove("auto", "1/k**2", "k", "1", "n", Optional.empty());

        assertThat(proof.status()).isEqualTo(SympyIdentityClient.Status.NO_CLOSED_FORM);
        assertThat(proof.result()).isNull();
        assertThat(proof.certificate()).isNull();
        assertThat(proof.algorithmUsed()).isEqualTo("zeilberger_wz");
    }

    @Test
    void prove_sendsAllRequiredFieldsAndTimeoutInBody() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"proved\",\"result\":\"n*(n+1)/2\"," +
                "\"certificate\":\"k*(k-1)/2\"," +
                "\"algorithm_used\":\"gosper\",\"elapsed_ms\":50}"));
        SympyIdentityClient client = buildClient(true);
        client.prove("gosper", "k", "k", "0", "n", Optional.of("n*(n+1)/2"));

        // The stub captured the bytes the client sent — parse and assert.
        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("algorithm").asText()).isEqualTo("gosper");
        assertThat(req.path("summand").asText()).isEqualTo("k");
        assertThat(req.path("var").asText()).isEqualTo("k");
        assertThat(req.path("lower").asText()).isEqualTo("0");
        assertThat(req.path("upper").asText()).isEqualTo("n");
        assertThat(req.path("target").asText()).isEqualTo("n*(n+1)/2");
        assertThat(req.path("timeoutMs").asLong()).isEqualTo(5_000);
    }

    // ── Safety filter ──────────────────────────────────────────────

    @Test
    void prove_rejectsDunderInjection_clientSide() {
        SympyIdentityClient client = buildClient(true);
        // Reset the request-path capture so we can assert no /prove_identity
        // call leaked past the JVM filter (the /healthz probe during buildClient
        // already set lastRequestPath to "/healthz").
        lastRequestPath.set(null);
        assertThatThrownBy(() ->
                client.prove("auto",
                        "__import__('os').system('rm -rf /')",
                        "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("safety_reject"))
                .hasMessageContaining("__");
        // JVM filter short-circuits before any HTTP call.
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void prove_rejectsSubprocessReference_clientSide() {
        SympyIdentityClient client = buildClient(true);
        lastRequestPath.set(null);
        assertThatThrownBy(() ->
                client.prove("auto", "subprocess.call(...)",
                        "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("safety_reject"))
                .hasMessageContaining("subprocess.");
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void prove_rejectsOversizeInput_clientSide() {
        SympyIdentityClient client = buildClient(true);
        lastRequestPath.set(null);
        String huge = "k+".repeat(300);  // 600 chars, over the 512 cap
        assertThatThrownBy(() ->
                client.prove("auto", huge, "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("safety_reject"))
                .hasMessageContaining("exceeds");
        assertThat(lastRequestPath.get()).isNull();
    }

    // ── Bad input validation ────────────────────────────────────────

    @Test
    void prove_rejectsNullSummand_withIllegalArgument() {
        SympyIdentityClient client = buildClient(true);
        assertThatThrownBy(() ->
                client.prove("auto", null, "k", "0", "n", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void prove_rejectsNonIdentifierVar_withIllegalArgument() {
        SympyIdentityClient client = buildClient(true);
        assertThatThrownBy(() ->
                client.prove("auto", "binomial(n, k)", "k+1", "0", "n", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("var");
    }

    // ── Error envelopes ─────────────────────────────────────────────

    @Test
    void prove_surfacesSidecarErrorCode() {
        nextResponse.set(new CannedResponse(400,
                "{\"ok\":false,\"error\":\"bad_request\"," +
                "\"explanation\":\"algorithm must be one of: gosper, ...\"," +
                "\"elapsed_ms\":0}"));
        SympyIdentityClient client = buildClient(true);
        assertThatThrownBy(() ->
                client.prove("magic_proof", "k", "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("bad_request"))
                .hasMessageContaining("algorithm must be one of");
    }

    @Test
    void prove_busyResponse_surfacesBusyErrorCode() {
        nextResponse.set(new CannedResponse(503,
                "{\"ok\":false,\"error\":\"busy\"," +
                "\"explanation\":\"Another /prove_identity call is in progress\"," +
                "\"elapsed_ms\":5000}"));
        SympyIdentityClient client = buildClient(true);
        assertThatThrownBy(() ->
                client.prove("auto", "k", "k", "0", "n", Optional.empty()))
                .isInstanceOf(SympyIdentityException.class)
                .satisfies(e -> assertThat(((SympyIdentityException) e).errorCode())
                        .isEqualTo("busy"));
    }

    // ── Counters ────────────────────────────────────────────────────

    @Test
    void stats_reflectProvedAndNoClosedFormCalls() {
        SympyIdentityClient client = buildClient(true);
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"proved\",\"result\":\"2**n\"," +
                "\"certificate\":\"c\",\"algorithm_used\":\"zeilberger_wz\"," +
                "\"elapsed_ms\":10}"));
        client.prove("auto", "binomial(n, k)", "k", "0", "n", Optional.empty());

        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"no_closed_form\",\"result\":null," +
                "\"certificate\":null,\"algorithm_used\":\"zeilberger_wz\"," +
                "\"elapsed_ms\":12}"));
        client.prove("auto", "1/k**2", "k", "1", "n", Optional.empty());

        SympyIdentityClient.ProverStats stats = client.stats();
        assertThat(stats.totalCalls()).isEqualTo(2);
        assertThat(stats.provedCalls()).isEqualTo(1);
        assertThat(stats.noClosedFormCalls()).isEqualTo(1);
        assertThat(stats.latencyMsTotal()).isPositive();
    }
}
