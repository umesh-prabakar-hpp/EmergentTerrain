package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22 §22.2 — unit tests for {@link JuliaMathClient}.
 *
 * <p>Uses a stub HTTP server bound to localhost on an OS-assigned
 * port (port=0 → OS picks). The stub records the most recent request
 * body so tests can assert on the wire format the client sent, and
 * returns canned responses so the client's parsing logic gets full
 * coverage without depending on a real Julia process.
 *
 * <p>The stub server uses {@code com.sun.net.httpserver.HttpServer}
 * — built into the JDK, no extra dependency. Slow startup (~100ms)
 * but adequate for the test sample size.
 *
 * <p>Integration against a live sidecar lives in
 * {@code JuliaMathClientIntegrationTest} (separate file) and skips
 * when the sidecar isn't running.
 */
class JuliaMathClientTest {

    private HttpServer stub;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Captures the most recent request body for assertion. */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    /** Captures the path of the most recent request. */
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    /** Next canned response — set by tests before invoking the client. */
    private final AtomicReference<CannedResponse> nextResponse = new AtomicReference<>();

    private record CannedResponse(int status, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = stub.getAddress().getPort();
        // One handler for every path — examine the request URI inside
        // to dispatch. Simpler than registering N contexts in setUp.
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
        // Drain the request body so the connection can be reused; record
        // the bytes for later assertion.
        byte[] requestBody = ex.getRequestBody().readAllBytes();
        lastRequestBody.set(new String(requestBody, StandardCharsets.UTF_8));
        CannedResponse cr = nextResponse.get();
        if (cr == null) {
            // Default to a generic ok response — most tests set their own.
            cr = new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":1}");
        }
        byte[] body = cr.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(cr.status(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Build a client pointing at the stub server. */
    private JuliaMathClient buildClient() {
        return buildClient(true);
    }

    private JuliaMathClient buildClient(boolean enabled) {
        JuliaMathClient client = new JuliaMathClient(
                enabled,
                "http://127.0.0.1:" + port,
                /*timeoutMs*/ 2_000,
                /*healthProbeTimeoutMs*/ 1_000,
                mapper);
        if (enabled) {
            // The stub's default /healthz response is the generic
            // {ok:true,elapsed_ms:1} — probe will succeed.
            client.probeHealth();
        }
        return client;
    }

    // ── Reachability ────────────────────────────────────────────────

    @Test
    void disabledClient_isAvailable_returnsFalse() {
        JuliaMathClient client = buildClient(false);
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void enabledClient_probeSucceeds_markedAvailable() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"status\":\"ready\",\"elapsed_ms\":0}"));
        JuliaMathClient client = buildClient(true);
        assertThat(client.isEnabled()).isTrue();
        assertThat(client.isReachable()).isTrue();
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void enabledClient_probeFails_markedUnreachable() {
        nextResponse.set(new CannedResponse(500, "{\"ok\":false}"));
        JuliaMathClient client = buildClient(true);
        assertThat(client.isReachable()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    // ── /eval ───────────────────────────────────────────────────────

    @Test
    void eval_happyPath_returnsParsedResult() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":\"5.0\",\"type\":\"Float64\",\"elapsed_ms\":3}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.EvalResult r = client.eval("floor(2.04e-6 / 400e-9)");

        assertThat(r.result()).isEqualTo("5.0");
        assertThat(r.type()).isEqualTo("Float64");
        assertThat(r.elapsedMs()).isEqualTo(3);
        // Verify the wire format the client sent.
        assertThat(lastRequestPath.get()).isEqualTo("/eval");
        JsonNode sent = readBody();
        assertThat(sent.path("expr").asText()).isEqualTo("floor(2.04e-6 / 400e-9)");
        assertThat(sent.path("timeoutMs").asLong()).isEqualTo(2_000);
    }

    @Test
    void eval_safetyRejected_throwsWithErrorCode() {
        nextResponse.set(new CannedResponse(400,
                "{\"ok\":false,\"error\":\"safety_rejected\",\"explanation\":\"forbidden token 'run('\"}"));
        JuliaMathClient client = buildClient();

        assertThatThrownBy(() -> client.eval("run(`ls`)"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("safety_rejected"));
    }

    @Test
    void eval_disabledClient_throwsNotEnabled() {
        JuliaMathClient client = buildClient(false);

        assertThatThrownBy(() -> client.eval("1+1"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("not_enabled"));
    }

    @Test
    void eval_timeout_propagatesErrorCode() {
        nextResponse.set(new CannedResponse(408,
                "{\"ok\":false,\"error\":\"timeout\",\"explanation\":\"exceeded 5000ms\"}"));
        JuliaMathClient client = buildClient();

        assertThatThrownBy(() -> client.eval("sum(1 for _ in 1:10^9)"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("timeout"));
    }

    // ── /solve ──────────────────────────────────────────────────────

    @Test
    void solve_happyPath_returnsRoots() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":[\"2\",\"-2\"],\"elapsed_ms\":12}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.SolveResult r = client.solve("x^2 - 4", "x");

        assertThat(r.symbolicFailed()).isFalse();
        assertThat(r.roots()).containsExactly("2", "-2");
        assertThat(lastRequestPath.get()).isEqualTo("/solve");
        JsonNode sent = readBody();
        assertThat(sent.path("var").asText()).isEqualTo("x");
    }

    @Test
    void solve_symbolicFailed_returnsFailedFlag() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"symbolic_failed\":true,\"hint\":\"try /nsolve\",\"elapsed_ms\":4}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.SolveResult r = client.solve("transcendental_thing(x)", "x");

        assertThat(r.symbolicFailed()).isTrue();
        assertThat(r.roots()).isEmpty();
    }

    // ── /nsolve ─────────────────────────────────────────────────────

    @Test
    void nsolve_happyPath_returnsRoot() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":2.0,\"elapsed_ms\":5}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.NumericRootResult r = client.nsolve("x^2 - 4", "x", 1.0);

        assertThat(r.root()).isEqualTo(2.0);
        JsonNode sent = readBody();
        assertThat(sent.path("x0").asDouble()).isEqualTo(1.0);
    }

    // ── /integrate ──────────────────────────────────────────────────

    @Test
    void integrate_happyPath_returnsValueAndErrorEstimate() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":2.0,\"error_estimate\":1.0e-12,\"elapsed_ms\":7}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.IntegralResult r = client.integrate("sin(x)", "x", 0.0, Math.PI);

        assertThat(r.value()).isEqualTo(2.0);
        assertThat(r.errorEstimate()).isEqualTo(1.0e-12);
        JsonNode sent = readBody();
        assertThat(sent.path("a").asDouble()).isEqualTo(0.0);
        assertThat(sent.path("b").asDouble()).isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void integrateSymbolicLimits_sendsStringLimits() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":2.0,\"error_estimate\":1.0e-12,\"elapsed_ms\":7}"));
        JuliaMathClient client = buildClient();

        client.integrateSymbolicLimits("sin(x)", "x", "0", "π");

        JsonNode sent = readBody();
        // String limits are sent as JSON strings, not as numbers.
        assertThat(sent.path("a").isTextual()).isTrue();
        assertThat(sent.path("b").asText()).isEqualTo("π");
    }

    // ── /sweep ──────────────────────────────────────────────────────

    @Test
    void sweep_happyPath_returnsAllStats() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"mean\":38.5,\"std\":29.7,\"min\":1.0,\"max\":100.0,\"n\":10,\"elapsed_ms\":4}"));
        JuliaMathClient client = buildClient();

        JuliaMathClient.SweepResult r = client.sweep("x^2", "x", 1.0, 10.0, 10);

        assertThat(r.mean()).isEqualTo(38.5);
        assertThat(r.min()).isEqualTo(1.0);
        assertThat(r.max()).isEqualTo(100.0);
        assertThat(r.n()).isEqualTo(10);
    }

    // ── Transport failure ──────────────────────────────────────────

    @Test
    void transport_failure_marksUnreachable() {
        // First make the client reachable.
        nextResponse.set(new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":0}"));
        JuliaMathClient client = buildClient(true);
        assertThat(client.isAvailable()).isTrue();

        // Now stop the stub server and trigger a call — the transport
        // failure should mark the client unreachable.
        stub.stop(0);
        stub = null;  // prevent tearDown double-stop

        assertThatThrownBy(() -> client.eval("1+1"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("transport"));
        assertThat(client.isReachable()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private JsonNode readBody() {
        try {
            return mapper.readTree(lastRequestBody.get());
        } catch (Exception e) {
            throw new AssertionError("Failed to parse request body: " + lastRequestBody.get(), e);
        }
    }
}
