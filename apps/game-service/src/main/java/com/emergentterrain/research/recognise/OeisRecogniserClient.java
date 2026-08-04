package com.emergentterrain.research.recognise;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 26 §26.1 — JVM peer of the sidecar's {@code /recognise/sequence}
 * endpoint. Owns the Postgres cache; the sidecar only sees cache
 * misses.
 *
 * <h2>Cache-aside pattern</h2>
 *
 * <pre>
 *   lookup(seq):
 *     hash = sha256(comma-join(seq))
 *     if cache[hash] exists → return cached result (hit or miss)
 *     else:
 *       result = sidecar.POST /recognise/sequence {sequence: seq}
 *       cache[hash] = result
 *       return result
 * </pre>
 *
 * The cache persists both hits ("OEIS knows this — A000045") and
 * misses ("OEIS doesn't know this sequence"). Repeated lookups of
 * unknown sequences therefore still pay zero outbound HTTP cost.
 *
 * <h2>Failure surfaces</h2>
 *
 * Transport errors (sidecar unreachable, OEIS rate-limited, HTTP
 * timeout) surface as {@link OeisRecogniserException} with
 * {@code errorCode="transport"}; the orchestrating
 * {@code ConstantRecognitionService} catches that and degrades
 * gracefully. Cache write failures log at WARN and continue —
 * a flapping Postgres connection shouldn't block recognition.
 */
@Service
public class OeisRecogniserClient {

    private static final Logger log = LoggerFactory.getLogger(OeisRecogniserClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final long timeoutMs;

    private final JuliaMathClient juliaMathClient;
    private final OeisCacheRepository cacheRepo;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final AtomicLong totalCalls       = new AtomicLong();
    private final AtomicLong cacheHitCalls    = new AtomicLong();
    private final AtomicLong sidecarMissCalls = new AtomicLong();
    private final AtomicLong recognisedCalls  = new AtomicLong();
    private final AtomicLong transportFails   = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public OeisRecogniserClient(
            @Value("${app.julia.recognise.enabled:false}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.julia.recognise.timeoutMs:5000}") long timeoutMs,
            JuliaMathClient juliaMathClient,
            OeisCacheRepository cacheRepo,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.timeoutMs = timeoutMs;
        this.juliaMathClient = juliaMathClient;
        this.cacheRepo = cacheRepo;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Recognition is available when the flag is on AND the sidecar
     * (per JuliaMathClient's probe) is reachable.
     */
    public boolean isAvailable() {
        return enabled && juliaMathClient.isAvailable();
    }

    /**
     * Look up a sequence in OEIS via the cache-aside pipeline.
     *
     * @param sequence integer sequence; must have at least 4 terms
     *                 (Superseeker disambiguation floor)
     * @return {@link Optional} containing an {@link OeisHit} if OEIS
     *         recognised the sequence, {@code Optional.empty()} if
     *         recognition negatively cached or returned no match
     * @throws OeisRecogniserException on transport / config errors
     */
    public Optional<OeisHit> lookup(int[] sequence) {
        if (sequence == null || sequence.length < 4) {
            throw new IllegalArgumentException(
                    "OEIS Superseeker needs at least 4 terms; got "
                            + (sequence == null ? "null" : sequence.length));
        }
        totalCalls.incrementAndGet();

        if (!enabled) {
            throw new OeisRecogniserException("not_enabled",
                    "OeisRecogniserClient is disabled (app.julia.recognise.enabled=false)");
        }

        String hash = sequenceHash(sequence);

        // Cache read. A Postgres outage here logs+continues to the
        // sidecar; the recognise loop shouldn't block on infra
        // intermittency.
        try {
            Optional<OeisCacheEntry> cached = cacheRepo.findById(hash);
            if (cached.isPresent()) {
                cacheHitCalls.incrementAndGet();
                return cached.get().hit()
                        ? Optional.of(new OeisHit(
                                cached.get().oeisId(),
                                cached.get().name(),
                                cached.get().transformChain()))
                        : Optional.empty();
            }
        } catch (Exception e) {
            log.warn("OeisRecogniserClient: cache read failed (hash={}); " +
                    "falling through to sidecar: {}", hash, e.getMessage());
        }

        // Cache miss — hit the sidecar.
        sidecarMissCalls.incrementAndGet();
        Optional<OeisHit> result = callSidecar(sequence);

        // Cache write. Failures here don't surface — we have the
        // answer, the operator can see the WARN if it ever matters.
        try {
            OeisCacheEntry entry = new OeisCacheEntry(
                    hash,
                    result.map(OeisHit::oeisId).orElse(null),
                    result.map(OeisHit::name).orElse(null),
                    result.map(OeisHit::transformChain).orElse(null),
                    result.isPresent(),
                    Instant.now());
            cacheRepo.save(entry);
        } catch (Exception e) {
            log.warn("OeisRecogniserClient: cache write failed (hash={}): {}",
                    hash, e.getMessage());
        }

        result.ifPresent(h -> recognisedCalls.incrementAndGet());
        return result;
    }

    private Optional<OeisHit> callSidecar(int[] sequence) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("sequence", sequence);
        body.put("timeoutMs", timeoutMs);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new OeisRecogniserException("request_serialise",
                    "Failed to serialise /recognise/sequence body", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/recognise/sequence"))
                .timeout(Duration.ofMillis(timeoutMs + 5_000))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            transportFails.incrementAndGet();
            throw new OeisRecogniserException("transport",
                    "Sidecar unreachable at " + baseUrl + "/recognise/sequence: "
                            + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new OeisRecogniserException("response_parse",
                    "Sidecar returned non-JSON (status=" + resp.statusCode() + "): "
                            + truncate(resp.body(), 200), e);
        }

        if (!root.path("ok").asBoolean(false)) {
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            if ("transport".equals(code)) {
                // OEIS-side transport (rate-limit, 5xx, network) is a
                // degraded-not-failing situation — treat as no match
                // and let the cache record the absence. Caller still
                // sees Optional.empty().
                transportFails.incrementAndGet();
                log.info("OeisRecogniserClient: OEIS degraded ({}); treating as no match",
                        explanation);
                return Optional.empty();
            }
            throw new OeisRecogniserException(code,
                    "/recognise/sequence returned ok=false: " + code + ": " + explanation);
        }

        if (!root.path("recognized").asBoolean(false)) {
            return Optional.empty();
        }
        String oeisId = root.path("oeis_id").asText("");
        String name = root.path("name").asText("");
        String transformChain = root.path("transform_chain").asText("identity");
        if (oeisId.isEmpty()) {
            log.warn("OeisRecogniserClient: sidecar reported recognized=true but " +
                    "oeis_id missing; treating as no match");
            return Optional.empty();
        }
        return Optional.of(new OeisHit(oeisId, name, transformChain));
    }

    /**
     * SHA-256 hex of the canonical sequence string (comma-joined,
     * no spaces). Deterministic across JVM runs; collision-free for
     * practical input sizes.
     */
    static String sequenceHash(int[] sequence) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sequence.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(sequence[i]);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in JDK; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Snapshot of counters at call time. Read-only. */
    public Stats stats() {
        return new Stats(
                totalCalls.get(),
                cacheHitCalls.get(),
                sidecarMissCalls.get(),
                recognisedCalls.get(),
                transportFails.get());
    }

    public record Stats(
            long totalCalls,
            long cacheHitCalls,
            long sidecarMissCalls,
            long recognisedCalls,
            long transportFails) {}

    /**
     * Result of a successful OEIS lookup.
     *
     * @param oeisId         A-number, e.g. {@code "A000045"}
     * @param name           OEIS-canonical sequence name, e.g.
     *                       {@code "Fibonacci numbers: F(n) = ..."}
     * @param transformChain {@code "identity"} when the queried sequence
     *                       appears as a contiguous window in the OEIS
     *                       canonical data; {@code "transformed"} when
     *                       OEIS Superseeker matched via a binomial /
     *                       Möbius / partial-sum transform.
     */
    public record OeisHit(String oeisId, String name, String transformChain) {}
}
