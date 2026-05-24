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

    // ── Scheduled re-probe (sticky-unreachable recovery) ────────────

    @Test
    void scheduledReprobe_noopWhenDisabled() {
        JuliaMathClient client = buildClient(false);
        // Stub returns ok by default — if scheduledReprobe ran the
        // request anyway, reachable would flip to true.
        lastRequestPath.set(null);
        client.scheduledReprobe();
        assertThat(client.isReachable()).isFalse();
        // No HTTP made.
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void scheduledReprobe_noopWhenAlreadyReachable() {
        nextResponse.set(new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":0}"));
        JuliaMathClient client = buildClient(true);
        assertThat(client.isReachable()).isTrue();
        // After successful init, mark the stub path as null so we can
        // detect any additional probe attempt.
        lastRequestPath.set(null);
        client.scheduledReprobe();
        // Should NOT re-probe since reachable is already true.
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void scheduledReprobe_recoversFromTransientFailure() {
        // Simulate the timeout-at-init scenario: first probe fails,
        // reachable is stuck false, scheduled re-probe runs and the
        // sidecar is now responsive.
        nextResponse.set(new CannedResponse(500, "{\"ok\":false}"));
        JuliaMathClient client = buildClient(true);
        assertThat(client.isReachable()).isFalse();
        // Now simulate the sidecar recovering — next response is 200.
        nextResponse.set(new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":0}"));
        client.scheduledReprobe();
        assertThat(client.isReachable()).isTrue();
    }

    // ── Phase 24 §24.2 — /pysr binding ──────────────────────────────

    /** Build a pysr-enabled client pointing at the stub. */
    private JuliaMathClient buildPysrClient() {
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ true,
                "http://127.0.0.1:" + port,
                /*timeoutMs*/ 2_000,
                /*healthProbeTimeoutMs*/ 1_000,
                /*pysrEnabled*/ true,
                /*pysrTimeoutMs*/ 20_000,
                /*pysrPopulations*/ 8,
                /*pysrIterations*/ 40,
                /*pysrComplexityCeiling*/ 30,
                mapper);
        client.probeHealth();
        return client;
    }

    private JuliaMathClient.PysrRequest sampleRequest() {
        double[][] samples = {{1.0, 2.0, 2.0}, {2.0, 3.0, 6.0}, {3.0, 4.0, 12.0}};
        return new JuliaMathClient.PysrRequest(
                samples,
                java.util.List.of("x", "y"),
                "f",
                java.util.List.of("+", "-", "*"),
                /*populations*/ 0,
                /*iterations*/ 0,
                /*complexityCeiling*/ 0,
                /*timeoutMs*/ 0,
                /*seed*/ null);
    }

    @Test
    void pysr_isPysrAvailable_reflectsFlag() {
        JuliaMathClient pysrOn = buildPysrClient();
        assertThat(pysrOn.isPysrEnabled()).isTrue();
        assertThat(pysrOn.isPysrAvailable()).isTrue();

        JuliaMathClient pysrOff = buildClient();   // pysrEnabled=false via back-compat ctor
        assertThat(pysrOff.isPysrEnabled()).isFalse();
        assertThat(pysrOff.isPysrAvailable()).isFalse();
    }

    @Test
    void pysr_happyPath_parsesParetoFront() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"pareto\":["
                + "{\"expr\":\"x\",\"mse\":4.0,\"complexity\":1},"
                + "{\"expr\":\"x * y\",\"mse\":1.0e-9,\"complexity\":3}"
                + "],\"evaluated\":42,\"elapsed_ms\":1500}"));
        JuliaMathClient client = buildPysrClient();
        JuliaMathClient.PysrResult result = client.pysr(sampleRequest());

        assertThat(result.evaluated()).isEqualTo(42);
        assertThat(result.elapsedMs()).isEqualTo(1500);
        assertThat(result.pareto()).hasSize(2);
        // Wire ordering preserved — caller sees complexity-ascending.
        assertThat(result.pareto().get(0).expression()).isEqualTo("x");
        assertThat(result.pareto().get(0).complexity()).isEqualTo(1);
        assertThat(result.pareto().get(1).expression()).isEqualTo("x * y");
        assertThat(result.pareto().get(1).mse()).isLessThan(1e-6);
    }

    @Test
    void pysr_busyResponse_surfacesAsException() {
        nextResponse.set(new CannedResponse(503,
                "{\"ok\":false,\"error\":\"busy\","
                + "\"explanation\":\"another /pysr in progress\"}"));
        JuliaMathClient client = buildPysrClient();

        assertThatThrownBy(() -> client.pysr(sampleRequest()))
                .isInstanceOf(JuliaMathException.class)
                .hasMessageContaining("busy");
    }

    @Test
    void pysr_appliesDefaultsForZeroFields() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"pareto\":[],\"evaluated\":0,\"elapsed_ms\":1}"));
        JuliaMathClient client = buildPysrClient();
        client.pysr(sampleRequest());

        JsonNode body = readBody();
        // Defaults from the buildPysrClient constructor should appear
        // on the wire — caller passed zeros.
        assertThat(body.path("populations").asInt()).isEqualTo(8);
        assertThat(body.path("iterations").asInt()).isEqualTo(40);
        assertThat(body.path("complexityCeiling").asInt()).isEqualTo(30);
        assertThat(body.path("timeoutMs").asLong()).isEqualTo(20_000L);
        // Caller-supplied fields appear verbatim.
        assertThat(body.path("targetName").asText()).isEqualTo("f");
        assertThat(body.path("samples").isArray()).isTrue();
        assertThat(body.path("varNames").isArray()).isTrue();
        // Phase 24 §24.6 Lever 2 is dormant: autoEnsemble defaults OFF on
        // the wire when neither the client flag nor the request sets it.
        assertThat(body.path("autoEnsemble").asBoolean(true)).isFalse();
    }

    @Test
    void pysr_autoEnsembleOverride_sentOnWire() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"pareto\":[],\"evaluated\":0,\"elapsed_ms\":1,"
                + "\"ensemble\":true,\"variants\":[\"normalized\",\"raw\"]}"));
        JuliaMathClient client = buildPysrClient();
        double[][] samples = {{1.0, 2.0, 2.0}, {2.0, 3.0, 6.0}};
        // Per-request override (Lever 2) flips autoEnsemble on even though
        // the client default is off. Uses the 12-arg PysrRequest carrying
        // all three §24.6 toggles.
        JuliaMathClient.PysrRequest req = new JuliaMathClient.PysrRequest(
                samples, java.util.List.of("x", "y"), "f",
                java.util.List.of("+", "-", "*"),
                0, 0, 0, 0, null,
                /*autoNormalize*/ null, /*autoRetry*/ null,
                /*autoEnsemble*/ Boolean.TRUE);
        client.pysr(req);

        JsonNode body = readBody();
        assertThat(body.path("autoEnsemble").asBoolean(false)).isTrue();
    }

    @Test
    void pysr_rejectsBadVarName_clientSide() {
        JuliaMathClient client = buildPysrClient();
        double[][] samples = {{1.0, 2.0, 2.0}};
        // Var name with a dot — not a valid identifier; safety filter
        // should reject before HTTP.
        JuliaMathClient.PysrRequest bad = new JuliaMathClient.PysrRequest(
                samples,
                java.util.List.of("x.evil"),
                "f", null, 0, 0, 0, 0, null);
        // Wrong shape but the var-name check fires first.
        lastRequestPath.set(null);
        assertThatThrownBy(() -> client.pysr(bad))
                .isInstanceOf(JuliaMathException.class)
                .hasMessageContaining("Variable name");
        // No HTTP made.
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void pysr_rejectsBadRowWidth_clientSide() {
        JuliaMathClient client = buildPysrClient();
        // varNames says 2 vars → rows must have 3 columns (2 + target).
        double[][] wrongWidth = {{1.0, 2.0}};
        JuliaMathClient.PysrRequest bad = new JuliaMathClient.PysrRequest(
                wrongWidth,
                java.util.List.of("x", "y"),
                "f", null, 0, 0, 0, 0, null);
        lastRequestPath.set(null);
        assertThatThrownBy(() -> client.pysr(bad))
                .isInstanceOf(JuliaMathException.class)
                .hasMessageContaining("must be a numeric row of length 3");
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void pysr_bumpsPysrCountersOnSuccess() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"pareto\":[],\"evaluated\":0,\"elapsed_ms\":150}"));
        JuliaMathClient client = buildPysrClient();
        var before = client.snapshot();

        client.pysr(sampleRequest());

        var after = client.snapshot();
        assertThat(after.pysrCalls()).isEqualTo(before.pysrCalls() + 1);
        // Shared counters also tick because pysr() goes through post().
        assertThat(after.totalCalls()).isEqualTo(before.totalCalls() + 1);
        assertThat(after.okCalls()).isEqualTo(before.okCalls() + 1);
        // Pysr latency should have measurable delta.
        assertThat(after.pysrLatencyMsTotal())
                .isGreaterThanOrEqualTo(before.pysrLatencyMsTotal());
    }

    // ── Phase 28 §28.1 — /numbertheory/window binding ────────────────

    /** Build a conjecture-enabled client pointing at the stub. */
    private JuliaMathClient buildConjectureClient() {
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ true,
                "http://127.0.0.1:" + port,
                /*timeoutMs*/ 2_000,
                /*healthProbeTimeoutMs*/ 1_000,
                /*pysrEnabled*/ false,
                /*pysrTimeoutMs*/ 20_000,
                /*pysrPopulations*/ 8,
                /*pysrIterations*/ 40,
                /*pysrComplexityCeiling*/ 30,
                /*conjectureEnabled*/ true,
                /*conjectureTimeoutMs*/ 3_000,
                mapper);
        client.probeHealth();
        return client;
    }

    @Test
    void numberTheoryWindow_isConjectureAvailable_reflectsFlag() {
        JuliaMathClient on = buildConjectureClient();
        assertThat(on.isConjectureEnabled()).isTrue();
        assertThat(on.isConjectureAvailable()).isTrue();

        JuliaMathClient off = buildClient();   // conjectureEnabled=false via back-compat ctor
        assertThat(off.isConjectureEnabled()).isFalse();
        assertThat(off.isConjectureAvailable()).isFalse();
    }

    @Test
    void numberTheoryWindow_happyPath_preservesScalarOrdering() {
        // Wire result is an array of [key, value] pairs — the order must
        // round-trip into the result's LinkedHashMap unchanged so the
        // §28.2 parity test against the JVM substrate can compare key
        // sequences directly.
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,"
                + "\"result\":["
                + "[\"abundant_density\",0.24],"
                + "[\"deficient_density\",0.76],"
                + "[\"prime_density\",0.2],"
                + "[\"happy_density\",0.14],"
                + "[\"mean_omega\",1.94],"
                + "[\"mean_Omega\",2.68],"
                + "[\"mean_divisor_count\",5.9],"
                + "[\"max_divisor_count\",16.0],"
                + "[\"mertens_delta\",-1.0]"
                + "],"
                + "\"start\":100,\"size\":50,"
                + "\"elapsed_ms\":3}"));
        JuliaMathClient client = buildConjectureClient();

        JuliaMathClient.NumberTheoryWindow r = client.numberTheoryWindow(100, 50);

        assertThat(r.start()).isEqualTo(100);
        assertThat(r.size()).isEqualTo(50);
        assertThat(r.elapsedMs()).isEqualTo(3);
        assertThat(r.scalars()).containsKeys(
                "abundant_density", "deficient_density", "prime_density",
                "happy_density", "mean_omega", "mean_Omega",
                "mean_divisor_count", "max_divisor_count", "mertens_delta");
        // The map must be a LinkedHashMap with the exact wire ordering.
        assertThat(r.scalars().keySet().toArray()).containsExactly(
                "abundant_density", "deficient_density", "prime_density",
                "happy_density", "mean_omega", "mean_Omega",
                "mean_divisor_count", "max_divisor_count", "mertens_delta");
        assertThat(r.scalars().get("prime_density")).isEqualTo(0.2);
        assertThat(r.scalars().get("max_divisor_count")).isEqualTo(16.0);

        // Request body shape — start, size, and per-call timeoutMs go over.
        assertThat(lastRequestPath.get()).isEqualTo("/numbertheory/window");
        JsonNode body = readBody();
        assertThat(body.path("start").asInt()).isEqualTo(100);
        assertThat(body.path("size").asInt()).isEqualTo(50);
        assertThat(body.path("timeoutMs").asLong()).isEqualTo(3_000);
    }

    @Test
    void numberTheoryWindow_disabledClient_throwsNotEnabled() {
        // `enabled=false` on the main Julia path short-circuits before
        // the request leaves the JVM, regardless of conjecture flag.
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ false,
                "http://127.0.0.1:" + port,
                2_000, 1_000,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                /*conjectureEnabled*/ true, 3_000,
                mapper);
        assertThatThrownBy(() -> client.numberTheoryWindow(100, 50))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("not_enabled"));
    }

    @Test
    void numberTheoryWindow_rejectsNonPositiveSize() {
        JuliaMathClient client = buildConjectureClient();
        assertThatThrownBy(() -> client.numberTheoryWindow(100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> client.numberTheoryWindow(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    void numberTheoryWindow_rejectsNegativeStart() {
        JuliaMathClient client = buildConjectureClient();
        assertThatThrownBy(() -> client.numberTheoryWindow(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start");
    }

    @Test
    void numberTheoryWindow_sidecarErrorEnvelope_surfacesAsException() {
        nextResponse.set(new CannedResponse(400,
                "{\"ok\":false,\"error\":\"bad_request\","
                + "\"explanation\":\"Window exceeds NT_MAX_N\"}"));
        JuliaMathClient client = buildConjectureClient();
        assertThatThrownBy(() -> client.numberTheoryWindow(100, 50))
                .isInstanceOf(JuliaMathException.class)
                .satisfies(e -> assertThat(((JuliaMathException) e).errorCode())
                        .isEqualTo("bad_request"));
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
