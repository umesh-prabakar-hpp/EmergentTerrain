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
    void eval_safetyRejected_caughtByClientFilter() {
        // As of Phase 22.3, the JVM-side filter rejects forbidden
        // tokens before the HTTP call. The sidecar's filter is
        // covered by the sidecar's own test suite — what we pin here
        // is that the JVM-side reject fires and surfaces a
        // distinguishable error code (client_safety_rejected vs the
        // server's safety_rejected).
        JuliaMathClient client = buildClient();

        assertThatThrownBy(() -> client.eval("run(`ls`)"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("client_safety_rejected"));
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

    // ── Client-side safety filter (Phase 22.3) ─────────────────────

    @Test
    void clientSideFilter_rejectsBeforeHttp() {
        // No nextResponse set — if the filter lets this through to
        // the stub server, the request would succeed with the
        // default {ok:true} response. We expect the filter to throw
        // BEFORE the HTTP call happens.
        JuliaMathClient client = buildClient();
        // Reset request capture so we can assert it was never set.
        lastRequestPath.set(null);
        lastRequestBody.set(null);

        assertThatThrownBy(() -> client.eval("(using Pkg; Pkg.add(\"Foo\"))"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> {
                    JuliaMathException jme = (JuliaMathException) e;
                    assertThat(jme.errorCode()).isEqualTo("client_safety_rejected");
                    assertThat(jme.getMessage()).contains("using Pkg");
                });
        // Confirm the stub server never saw the request.
        assertThat(lastRequestPath.get()).isNull();
        assertThat(lastRequestBody.get()).isNull();
    }

    @Test
    void clientSideFilter_rejectsBadVarIdentifier() {
        // The `var` field gets used as a Julia identifier server-side
        // — it's not eval'd as code, but a non-identifier string
        // (e.g. with spaces, special chars, or "eval(") would cause
        // an opaque parse error. Validate locally.
        JuliaMathClient client = buildClient();

        assertThatThrownBy(() -> client.nsolve("x^2 - 4", "x or eval(", 1.0))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("client_safety_rejected"));
    }

    @Test
    void clientSideFilter_acceptsValidIdentifier() {
        // Sanity — Greek letters / underscores / digits work fine as
        // Julia identifiers. But the @Value regex only matches ASCII
        // [A-Za-z_][A-Za-z0-9_]* so we test the ASCII case here.
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":2.0,\"elapsed_ms\":5}"));
        JuliaMathClient client = buildClient();

        client.nsolve("theta_max^2 - 4", "theta_max", 1.0);  // must not throw

        // Confirm the request did fire.
        assertThat(lastRequestPath.get()).isEqualTo("/nsolve");
    }

    @Test
    void clientSideFilter_rejectsForbiddenInSymbolicLimit() {
        // /integrate accepts string limits like "π" or "exp(1)".
        // A malicious limit like "run(`ls`)" would be a code-injection
        // vector — the filter catches it.
        JuliaMathClient client = buildClient();
        lastRequestPath.set(null);

        assertThatThrownBy(() ->
                client.integrateSymbolicLimits("sin(x)", "x", "0", "run(`ls`)"))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("client_safety_rejected"));
        // Confirm the stub server never saw the request.
        assertThat(lastRequestPath.get()).isNull();
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

    // ── Phase 22 §22.6 — counter tracking ──────────────────────────

    @Test
    void counters_okCallIncrementsOkAndLatency() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"result\":\"5.0\",\"type\":\"Float64\",\"elapsed_ms\":3}"));
        JuliaMathClient client = buildClient();
        // probeHealth() in buildClient counted as 1 total call. Take
        // a baseline before the eval call we're measuring.
        var before = client.snapshot();

        client.eval("1+1");

        var after = client.snapshot();
        assertThat(after.totalCalls()).isEqualTo(before.totalCalls() + 1);
        assertThat(after.okCalls()).isEqualTo(before.okCalls() + 1);
        // The successful call recorded latency on the wire.
        assertThat(after.latencyMsTotal()).isGreaterThanOrEqualTo(before.latencyMsTotal());
    }

    @Test
    void counters_clientSafetyRejectIncrementsSafetyOnly() {
        JuliaMathClient client = buildClient();
        var before = client.snapshot();

        assertThatThrownBy(() -> client.eval("(using Pkg; Pkg.add(\"X\"))"))
                .isInstanceOf(JuliaMathException.class);

        var after = client.snapshot();
        assertThat(after.safetyRejectCalls()).isEqualTo(before.safetyRejectCalls() + 1);
        // No HTTP happened so latency should NOT have grown.
        assertThat(after.latencyMsTotal()).isEqualTo(before.latencyMsTotal());
        assertThat(after.okCalls()).isEqualTo(before.okCalls());
    }

    @Test
    void counters_evalErrorIncrementsEvalError() {
        nextResponse.set(new CannedResponse(500,
                "{\"ok\":false,\"error\":\"eval_error\",\"explanation\":\"boom\"}"));
        JuliaMathClient client = buildClient();
        var before = client.snapshot();

        assertThatThrownBy(() -> client.eval("undefined_symbol(x)"))
                .isInstanceOf(JuliaMathException.class);

        var after = client.snapshot();
        assertThat(after.evalErrorCalls()).isEqualTo(before.evalErrorCalls() + 1);
        // HTTP DID happen — latency should have grown.
        assertThat(after.latencyMsTotal()).isGreaterThanOrEqualTo(before.latencyMsTotal());
    }

    @Test
    void counters_transportFailIncrementsTransportOnly() {
        // Make probeHealth succeed first, then stop the stub.
        nextResponse.set(new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":0}"));
        JuliaMathClient client = buildClient(true);
        var before = client.snapshot();

        stub.stop(0);
        stub = null;  // prevent tearDown double-stop

        assertThatThrownBy(() -> client.eval("1+1"))
                .isInstanceOf(JuliaMathException.class);

        var after = client.snapshot();
        assertThat(after.transportFailCalls()).isEqualTo(before.transportFailCalls() + 1);
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
