package com.emergentterrain.research.recognise;

import com.emergentterrain.llm.PySidecarHealth;
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
 * Phase 26 §26.2 — unit tests for {@link PslqRecogniserClient}.
 *
 * <p>Stub HTTP server returns canned sidecar responses so the
 * envelope/relation parsing + safety/validation paths get coverage
 * without a real mpmath chain in CI.
 */
class PslqRecogniserClientTest {

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

    private PySidecarHealth buildPyHealth() {
        PySidecarHealth health = new PySidecarHealth("http://127.0.0.1:" + port, 1_000);
        health.probeHealth();
        return health;
    }

    private PslqRecogniserClient buildClient(boolean enabled) {
        return new PslqRecogniserClient(
                enabled, 5_000, 200,
                buildPyHealth(), mapper);
    }

    // ── Enablement ───────────────────────────────────────────────────

    @Test
    void disabledClient_isAvailable_returnsFalse() {
        PslqRecogniserClient client = buildClient(false);
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void disabledClient_throwsNotEnabled() {
        PslqRecogniserClient client = buildClient(false);
        assertThatThrownBy(() -> client.recognise("3.14159", "BASIC"))
                .isInstanceOf(PslqRecogniserException.class)
                .satisfies(e -> assertThat(((PslqRecogniserException) e).errorCode())
                        .isEqualTo("not_enabled"));
    }

    // ── Happy path ───────────────────────────────────────────────────

    @Test
    void recognise_zetaThree_returnsPslqHit() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":true," +
                "\"name\":\"zeta(3)\"," +
                "\"basis_used\":\"BASIC\",\"precision_used\":200," +
                "\"relation\":[" +
                "[1,\"<target>\"],[0,\"1\"],[0,\"pi\"],[0,\"pi^2\"]," +
                "[0,\"e\"],[0,\"ln(2)\"],[0,\"ln(3)\"],[0,\"gamma\"],[-1,\"zeta(3)\"]" +
                "],\"elapsed_ms\":120}"));
        PslqRecogniserClient client = buildClient(true);

        Optional<PslqRecogniserClient.PslqHit> hit = client.recognise(
                "1.20205690315959428539973816151", "BASIC");

        assertThat(hit).isPresent();
        assertThat(hit.get().name()).isEqualTo("zeta(3)");
        assertThat(hit.get().basisUsed()).isEqualTo("BASIC");
        assertThat(hit.get().precisionUsed()).isEqualTo(200);
        assertThat(hit.get().relation()).hasSize(9);
        // First term is the target with coefficient 1
        assertThat(hit.get().relation().get(0).coefficient()).isEqualTo(1);
        assertThat(hit.get().relation().get(0).basisName()).isEqualTo("<target>");
        // Last term is -1 * zeta(3)
        assertThat(hit.get().relation().get(8).coefficient()).isEqualTo(-1);
        assertThat(hit.get().relation().get(8).basisName()).isEqualTo("zeta(3)");
        assertThat(lastRequestPath.get()).isEqualTo("/recognise/constant");
    }

    @Test
    void recognise_noClosedForm_returnsEmpty() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false," +
                "\"basis_used\":\"BASIC\",\"precision_used\":200," +
                "\"elapsed_ms\":42}"));
        PslqRecogniserClient client = buildClient(true);

        Optional<PslqRecogniserClient.PslqHit> hit = client.recognise(
                "0.789012345678901234567890123", "BASIC");

        assertThat(hit).isEmpty();
    }

    @Test
    void recognise_sendsBasisAndPrecisionInBody() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        PslqRecogniserClient client = buildClient(true);
        client.recognise("3.14159265358979", "PHYSICS", 100);

        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("value").asText()).isEqualTo("3.14159265358979");
        assertThat(req.path("basis").asText()).isEqualTo("PHYSICS");
        assertThat(req.path("precisionDigits").asInt()).isEqualTo(100);
        assertThat(req.path("timeoutMs").asLong()).isEqualTo(5_000);
    }

    @Test
    void recognise_defaultPrecision_usesClientDefault() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        PslqRecogniserClient client = buildClient(true);
        client.recognise("3.14159265358979", "BASIC");

        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("precisionDigits").asInt()).isEqualTo(200);  // default
    }

    // ── Input validation (client-side, never reaches sidecar) ─────────

    @Test
    void recognise_rejectsNullValue() {
        PslqRecogniserClient client = buildClient(true);
        assertThatThrownBy(() -> client.recognise(null, "BASIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value is required");
    }

    @Test
    void recognise_rejectsBlankValue() {
        PslqRecogniserClient client = buildClient(true);
        assertThatThrownBy(() -> client.recognise("   ", "BASIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value is required");
    }

    @Test
    void recognise_rejectsNonDecimalLiteral() {
        PslqRecogniserClient client = buildClient(true);
        // buildClient probed /healthz; reset so we can assert the JVM
        // filter short-circuited before any /recognise/constant call.
        lastRequestPath.set(null);
        // Python injection attempt
        assertThatThrownBy(() ->
                client.recognise("__import__('os').system('echo')", "BASIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain decimal literal");
        // Stub should never have been called for /recognise/constant.
        assertThat(lastRequestPath.get()).isNull();
    }

    @Test
    void recognise_rejectsOversizeValue() {
        PslqRecogniserClient client = buildClient(true);
        String huge = "0." + "1".repeat(2000);  // > 1024 chars
        assertThatThrownBy(() -> client.recognise(huge, "BASIC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void recognise_rejectsNullBasis() {
        PslqRecogniserClient client = buildClient(true);
        assertThatThrownBy(() -> client.recognise("3.14159", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis is required");
    }

    @Test
    void recognise_acceptsExponentNotation() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        PslqRecogniserClient client = buildClient(true);
        // Should pass the regex and reach the stub.
        client.recognise("3.14159265358979e0", "BASIC");
        assertThat(lastRequestPath.get()).isEqualTo("/recognise/constant");
    }

    // ── Error envelopes ─────────────────────────────────────────────

    @Test
    void recognise_unknownBasis_surfacesBadRequest() {
        nextResponse.set(new CannedResponse(400,
                "{\"ok\":false,\"error\":\"bad_request\"," +
                "\"explanation\":\"Unknown basis 'OOPS'\",\"elapsed_ms\":0}"));
        PslqRecogniserClient client = buildClient(true);
        assertThatThrownBy(() -> client.recognise("3.14159", "OOPS"))
                .isInstanceOf(PslqRecogniserException.class)
                .satisfies(e -> assertThat(((PslqRecogniserException) e).errorCode())
                        .isEqualTo("bad_request"));
    }

    @Test
    void recognise_sidecarUnreachable_throwsTransport() {
        PslqRecogniserClient client = new PslqRecogniserClient(
                true, 1_000, 200,
                new PySidecarHealth("http://127.0.0.1:1", 1_000), mapper);
        assertThatThrownBy(() -> client.recognise("3.14159", "BASIC"))
                .isInstanceOf(PslqRecogniserException.class)
                .satisfies(e -> assertThat(((PslqRecogniserException) e).errorCode())
                        .isEqualTo("transport"));
    }

    // ── Counters ────────────────────────────────────────────────────

    @Test
    void stats_reflectRecognisedAndSafetyReject() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":true,\"name\":\"pi\"," +
                "\"basis_used\":\"BASIC\",\"precision_used\":200," +
                "\"relation\":[[1,\"<target>\"],[-1,\"pi\"]]," +
                "\"elapsed_ms\":1}"));
        PslqRecogniserClient client = buildClient(true);
        client.recognise("3.14159265358979323846", "BASIC");

        // Safety reject — bumps safetyRejects, not totalCalls
        assertThatThrownBy(() ->
                client.recognise("rm -rf /", "BASIC"))
                .isInstanceOf(IllegalArgumentException.class);

        PslqRecogniserClient.Stats stats = client.stats();
        assertThat(stats.totalCalls()).isEqualTo(1);
        assertThat(stats.recognisedCalls()).isEqualTo(1);
        assertThat(stats.safetyRejects()).isEqualTo(1);
    }
}
