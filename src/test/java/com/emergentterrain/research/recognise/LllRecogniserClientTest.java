package com.emergentterrain.research.recognise;

import com.emergentterrain.llm.JuliaMathClient;
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
 * Phase 26 §26.3 — unit tests for {@link LllRecogniserClient}.
 *
 * <p>Same stub-server pattern as the PSLQ + OEIS sibling tests.
 * Verifies the two-flag gate (recognise.enabled AND
 * recognise.lll.enabled), envelope parsing into MinPolyHit, and
 * safety/validation paths.
 */
class LllRecogniserClientTest {

    private HttpServer stub;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
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
        lastRequestBody.set(new String(
                ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        CannedResponse cr = nextResponse.get();
        if (cr == null) cr = new CannedResponse(200, "{\"ok\":true,\"elapsed_ms\":1}");
        byte[] body = cr.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(cr.status(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private JuliaMathClient buildSidecar() {
        JuliaMathClient sidecar = new JuliaMathClient(
                true, "http://127.0.0.1:" + port, 2_000, 1_000, mapper);
        sidecar.probeHealth();
        return sidecar;
    }

    private LllRecogniserClient buildClient(boolean recogniseEnabled, boolean lllEnabled) {
        return new LllRecogniserClient(
                recogniseEnabled, lllEnabled,
                "http://127.0.0.1:" + port,
                5_000, 4, 200,
                buildSidecar(), mapper);
    }

    // ── Two-flag gate ────────────────────────────────────────────────

    @Test
    void disabledClient_isAvailable_returnsFalse() {
        LllRecogniserClient client = buildClient(false, false);
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void recogniseOnButLllOff_isAvailable_returnsFalse() {
        // Master flag on but the LLL sub-flag is the gating signal.
        LllRecogniserClient client = buildClient(true, false);
        assertThat(client.isEnabled()).isFalse();
    }

    @Test
    void bothFlagsOn_isAvailable_true() {
        LllRecogniserClient client = buildClient(true, true);
        assertThat(client.isEnabled()).isTrue();
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void recogniseDisabled_throws_notEnabled() {
        LllRecogniserClient client = buildClient(false, true);
        assertThatThrownBy(() -> client.recognise("1.414213562373"))
                .isInstanceOf(LllRecogniserException.class)
                .satisfies(e -> {
                    assertThat(((LllRecogniserException) e).errorCode())
                            .isEqualTo("not_enabled");
                    assertThat(e.getMessage()).contains("recognise.enabled");
                });
    }

    @Test
    void lllSubFlagDisabled_throws_notEnabled_distinctMessage() {
        LllRecogniserClient client = buildClient(true, false);
        assertThatThrownBy(() -> client.recognise("1.414213562373"))
                .isInstanceOf(LllRecogniserException.class)
                .satisfies(e -> {
                    assertThat(((LllRecogniserException) e).errorCode())
                            .isEqualTo("not_enabled");
                    // Message distinguishes master vs sub-flag for the
                    // operator reading logs.
                    assertThat(e.getMessage()).contains("lll.enabled");
                });
    }

    // ── Happy paths ──────────────────────────────────────────────────

    @Test
    void recognise_sqrt2PlusSqrt3_returnsMinPolyHit() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":true," +
                "\"minPoly\":\"x^4 - 10*x^2 + 1\"," +
                "\"degree\":4," +
                "\"coefficients\":[1,0,-10,0,1]," +
                "\"precision_used\":200," +
                "\"elapsed_ms\":700}"));
        LllRecogniserClient client = buildClient(true, true);

        Optional<LllRecogniserClient.MinPolyHit> hit = client.recognise(
                "3.14626436994197234232913506571557044551247712918732870123");

        assertThat(hit).isPresent();
        assertThat(hit.get().minPoly()).isEqualTo("x^4 - 10*x^2 + 1");
        assertThat(hit.get().degree()).isEqualTo(4);
        assertThat(hit.get().coefficients()).containsExactly(1, 0, -10, 0, 1);
        assertThat(hit.get().precisionUsed()).isEqualTo(200);
        assertThat(lastRequestPath.get()).isEqualTo("/recognise/minpoly");
    }

    @Test
    void recognise_noClosedForm_returnsEmpty() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false," +
                "\"precision_used\":200,\"degree_bound\":4," +
                "\"elapsed_ms\":12}"));
        LllRecogniserClient client = buildClient(true, true);
        Optional<LllRecogniserClient.MinPolyHit> hit = client.recognise(
                "3.14159265358979323846264338327950288419716939937510582097");
        assertThat(hit).isEmpty();
    }

    @Test
    void recognise_sendsDegreeBoundAndPrecisionInBody() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        LllRecogniserClient client = buildClient(true, true);
        client.recognise("1.41421356237309504880168872", 6, 300);

        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("value").asText()).isEqualTo("1.41421356237309504880168872");
        assertThat(req.path("degreeBound").asInt()).isEqualTo(6);
        assertThat(req.path("precisionDigits").asInt()).isEqualTo(300);
        assertThat(req.path("timeoutMs").asLong()).isEqualTo(5_000);
    }

    @Test
    void recognise_defaultDegreeAndPrecision() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        LllRecogniserClient client = buildClient(true, true);
        client.recognise("1.41421356237309504880168872");

        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("degreeBound").asInt()).isEqualTo(4);   // default
        assertThat(req.path("precisionDigits").asInt()).isEqualTo(200);  // default
    }

    // ── Input validation ─────────────────────────────────────────────

    @Test
    void recognise_rejectsNullValue() {
        LllRecogniserClient client = buildClient(true, true);
        assertThatThrownBy(() -> client.recognise(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognise_rejectsNonDecimalLiteral_doesNotCallSidecar() {
        LllRecogniserClient client = buildClient(true, true);
        lastRequestPath.set(null);  // clear /healthz capture from probe
        assertThatThrownBy(() ->
                client.recognise("__import__('os').system('echo')"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain decimal literal");
        assertThat(lastRequestPath.get()).isNull();
    }

    // ── Error envelopes ─────────────────────────────────────────────

    @Test
    void recognise_sidecarBadRequest_surfacesAsException() {
        nextResponse.set(new CannedResponse(400,
                "{\"ok\":false,\"error\":\"bad_request\"," +
                "\"explanation\":\"degreeBound out of range\"," +
                "\"elapsed_ms\":0}"));
        LllRecogniserClient client = buildClient(true, true);
        assertThatThrownBy(() -> client.recognise("1.414"))
                .isInstanceOf(LllRecogniserException.class)
                .satisfies(e -> assertThat(((LllRecogniserException) e).errorCode())
                        .isEqualTo("bad_request"));
    }

    @Test
    void recognise_sidecarUnreachable_throwsTransport() {
        LllRecogniserClient client = new LllRecogniserClient(
                true, true, "http://127.0.0.1:1", 1_000, 4, 200,
                buildSidecar(), mapper);
        assertThatThrownBy(() -> client.recognise("1.414"))
                .isInstanceOf(LllRecogniserException.class)
                .satisfies(e -> assertThat(((LllRecogniserException) e).errorCode())
                        .isEqualTo("transport"));
    }
}
