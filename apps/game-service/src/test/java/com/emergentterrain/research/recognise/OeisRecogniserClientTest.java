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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 26 §26.1 — unit tests for {@link OeisRecogniserClient}.
 *
 * <p>Loopback {@code com.sun.net.httpserver.HttpServer} stubs the
 * sidecar's {@code /recognise/sequence} endpoint, and an in-memory
 * {@code OeisCacheRepository} stand-in covers the Postgres surface
 * without needing a live DB.
 */
class OeisRecogniserClientTest {

    private HttpServer stub;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<CannedResponse> nextResponse = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger sidecarHits =
            new java.util.concurrent.atomic.AtomicInteger();

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
        String path = ex.getRequestURI().getPath();
        lastRequestPath.set(path);
        byte[] requestBody = ex.getRequestBody().readAllBytes();
        lastRequestBody.set(new String(requestBody, StandardCharsets.UTF_8));
        if ("/recognise/sequence".equals(path)) {
            sidecarHits.incrementAndGet();
        }
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

    private OeisRecogniserClient buildClient(boolean enabled, OeisCacheRepository cache) {
        return new OeisRecogniserClient(
                enabled, "http://127.0.0.1:" + port, 5_000,
                buildSidecar(), cache, mapper);
    }

    // ── Enablement ───────────────────────────────────────────────────

    @Test
    void disabledClient_isAvailable_returnsFalse() {
        OeisRecogniserClient client = buildClient(false, new InMemoryCache());
        assertThat(client.isEnabled()).isFalse();
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void disabledClient_throwsNotEnabled() {
        OeisRecogniserClient client = buildClient(false, new InMemoryCache());
        assertThatThrownBy(() -> client.lookup(new int[]{1, 1, 2, 3, 5, 8, 13}))
                .isInstanceOf(OeisRecogniserException.class)
                .satisfies(e -> assertThat(((OeisRecogniserException) e).errorCode())
                        .isEqualTo("not_enabled"));
    }

    // ── Happy paths ──────────────────────────────────────────────────

    @Test
    void lookup_recognisesFibonacci_onCacheMiss() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":true," +
                "\"oeis_id\":\"A000045\"," +
                "\"name\":\"Fibonacci numbers: F(n) = F(n-1) + F(n-2)\"," +
                "\"transform_chain\":\"identity\"," +
                "\"elapsed_ms\":120}"));
        InMemoryCache cache = new InMemoryCache();
        OeisRecogniserClient client = buildClient(true, cache);

        Optional<OeisRecogniserClient.OeisHit> hit =
                client.lookup(new int[]{1, 1, 2, 3, 5, 8, 13});

        assertThat(hit).isPresent();
        assertThat(hit.get().oeisId()).isEqualTo("A000045");
        assertThat(hit.get().name()).contains("Fibonacci");
        assertThat(hit.get().transformChain()).isEqualTo("identity");
        assertThat(sidecarHits.get()).isEqualTo(1);
        // Cache should now have the entry
        assertThat(cache.findAll()).hasSize(1);
    }

    @Test
    void lookup_secondCall_hitsCacheNotSidecar() {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":true,\"oeis_id\":\"A000040\"," +
                "\"name\":\"The prime numbers.\",\"transform_chain\":\"identity\"," +
                "\"elapsed_ms\":50}"));
        InMemoryCache cache = new InMemoryCache();
        OeisRecogniserClient client = buildClient(true, cache);

        int[] primes = {2, 3, 5, 7, 11, 13, 17};
        Optional<OeisRecogniserClient.OeisHit> first = client.lookup(primes);
        Optional<OeisRecogniserClient.OeisHit> second = client.lookup(primes);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        // Both calls return the same hit; only one sidecar HTTP fired.
        assertThat(sidecarHits.get()).isEqualTo(1);
        OeisRecogniserClient.Stats stats = client.stats();
        assertThat(stats.totalCalls()).isEqualTo(2);
        assertThat(stats.cacheHitCalls()).isEqualTo(1);
        assertThat(stats.sidecarMissCalls()).isEqualTo(1);
    }

    @Test
    void lookup_negativeCache_skipsSidecarOnSecondCall() {
        // First call: sidecar reports no match.
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":42}"));
        InMemoryCache cache = new InMemoryCache();
        OeisRecogniserClient client = buildClient(true, cache);
        int[] unknown = {987654321, 987654322, 987654323, 987654324};

        Optional<OeisRecogniserClient.OeisHit> first = client.lookup(unknown);
        Optional<OeisRecogniserClient.OeisHit> second = client.lookup(unknown);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        // Negative result was cached; second call doesn't hit the sidecar.
        assertThat(sidecarHits.get()).isEqualTo(1);
    }

    @Test
    void lookup_sendsTimeoutAndSequenceInBody() throws Exception {
        nextResponse.set(new CannedResponse(200,
                "{\"ok\":true,\"recognized\":false,\"elapsed_ms\":1}"));
        OeisRecogniserClient client = buildClient(true, new InMemoryCache());
        client.lookup(new int[]{1, 2, 3, 4, 5});

        com.fasterxml.jackson.databind.JsonNode req =
                mapper.readTree(lastRequestBody.get());
        assertThat(req.path("sequence").isArray()).isTrue();
        assertThat(req.path("sequence").size()).isEqualTo(5);
        assertThat(req.path("timeoutMs").asLong()).isEqualTo(5_000);
    }

    // ── Degraded transport ──────────────────────────────────────────

    @Test
    void lookup_oeisRateLimited_returnsEmpty_andCachesMiss() {
        // Sidecar returns ok=false transport (e.g. OEIS rate-limited it).
        nextResponse.set(new CannedResponse(502,
                "{\"ok\":false,\"error\":\"transport\"," +
                "\"explanation\":\"OEIS /search returned status 429\"," +
                "\"elapsed_ms\":250}"));
        InMemoryCache cache = new InMemoryCache();
        OeisRecogniserClient client = buildClient(true, cache);

        Optional<OeisRecogniserClient.OeisHit> result =
                client.lookup(new int[]{1, 2, 3, 4, 5});

        // Transport-degraded → caller sees empty, recognition pipeline
        // can fall back to naked decimals.
        assertThat(result).isEmpty();
        // Should NOT cache transport degradation — let next call retry.
        // Actually we DO cache (recognized=false) per the v1 design; the
        // operator can clear if OEIS recovers and they want a re-probe.
        assertThat(cache.findAll()).hasSize(1);
    }

    @Test
    void lookup_sidecarUnreachable_throwsTransport() {
        // Build a client pointing at a port nothing is listening on.
        InMemoryCache cache = new InMemoryCache();
        OeisRecogniserClient client = new OeisRecogniserClient(
                true, "http://127.0.0.1:1", 1_000,
                buildSidecar(),  // sidecar healthz still works (different baseUrl on JuliaMathClient)
                cache, mapper);
        // Note: JuliaMathClient was probed at the stub's port; we manually
        // point the OEIS client at an unbound port to force the transport
        // failure on the actual /recognise/sequence call.

        assertThatThrownBy(() -> client.lookup(new int[]{1, 2, 3, 4, 5}))
                .isInstanceOf(OeisRecogniserException.class)
                .satisfies(e -> assertThat(((OeisRecogniserException) e).errorCode())
                        .isEqualTo("transport"));
    }

    // ── Input validation ─────────────────────────────────────────────

    @Test
    void lookup_rejectsShortSequence() {
        OeisRecogniserClient client = buildClient(true, new InMemoryCache());
        assertThatThrownBy(() -> client.lookup(new int[]{1, 1, 2}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 4");
    }

    @Test
    void lookup_rejectsNull() {
        OeisRecogniserClient client = buildClient(true, new InMemoryCache());
        assertThatThrownBy(() -> client.lookup(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Hash determinism ─────────────────────────────────────────────

    @Test
    void sequenceHash_sameInput_sameHash() {
        String h1 = OeisRecogniserClient.sequenceHash(new int[]{1, 1, 2, 3, 5});
        String h2 = OeisRecogniserClient.sequenceHash(new int[]{1, 1, 2, 3, 5});
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);  // SHA-256 hex
    }

    @Test
    void sequenceHash_differentInputs_differentHashes() {
        String h1 = OeisRecogniserClient.sequenceHash(new int[]{1, 1, 2, 3, 5});
        String h2 = OeisRecogniserClient.sequenceHash(new int[]{1, 1, 2, 3, 6});
        assertThat(h1).isNotEqualTo(h2);
    }

    // ── In-memory cache stand-in ────────────────────────────────────

    /**
     * Lightweight in-memory implementation of OeisCacheRepository so
     * tests don't need a Postgres connection. Implements only the
     * subset of JpaRepository the client actually calls.
     */
    private static final class InMemoryCache implements OeisCacheRepository {
        private final Map<String, OeisCacheEntry> data = new HashMap<>();

        @Override public Optional<OeisCacheEntry> findById(String hash) {
            return Optional.ofNullable(data.get(hash));
        }
        @Override public <S extends OeisCacheEntry> S save(S entry) {
            data.put(entry.sequenceHash(), entry);
            return entry;
        }
        @Override public boolean existsById(String s) { return data.containsKey(s); }
        @Override public long count() { return data.size(); }
        @Override public void deleteById(String s) { data.remove(s); }
        @Override public void delete(OeisCacheEntry entry) { data.remove(entry.sequenceHash()); }
        @Override public void deleteAll() { data.clear(); }
        @Override public List<OeisCacheEntry> findAll() { return new ArrayList<>(data.values()); }

        // Methods we don't use — minimal stubs.
        @Override public List<OeisCacheEntry> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<OeisCacheEntry> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public List<OeisCacheEntry> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends OeisCacheEntry> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() {}
        @Override public <S extends OeisCacheEntry> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends OeisCacheEntry> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<OeisCacheEntry> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { data.clear(); }
        @Override public OeisCacheEntry getOne(String s) { throw new UnsupportedOperationException(); }
        @Override public OeisCacheEntry getById(String s) { throw new UnsupportedOperationException(); }
        @Override public OeisCacheEntry getReferenceById(String s) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends OeisCacheEntry> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
    }
}
