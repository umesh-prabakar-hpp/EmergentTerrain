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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 26 §26.3 — JVM peer of the sidecar's
 * {@code /recognise/minpoly} endpoint. Minimal-polynomial recovery
 * for algebraic numbers via PSLQ on the polynomial basis
 * [1, α, α², …, α^d]. Class is named after the historical "LLL"
 * framing in the phase doc; the implementation uses PSLQ-on-powers
 * (mathematically equivalent for this narrow problem, no Nemo.jl
 * dep required). See the sidecar commit 8392437 for the algorithm
 * rationale.
 *
 * <h2>Two-flag gate</h2>
 *
 * Even when {@code app.julia.recognise.enabled=true}, this client
 * stays inert unless {@code app.julia.recognise.lll.enabled=true}.
 * Reason: PSLQ-on-powers at high degree (up to 12) can produce
 * spurious relations more easily than constant recognition. The
 * sub-flag lets an operator turn on OEIS + PSLQ-constants without
 * also taking on minimal-polynomial recovery's larger false-positive
 * surface.
 *
 * <h2>No cache</h2>
 *
 * Same reasoning as PslqRecogniserClient — each value × degree
 * combination is unique to its paper, so cache reuse is rare.
 */
@Service
public class LllRecogniserClient {

    private static final Logger log = LoggerFactory.getLogger(LllRecogniserClient.class);

    private final boolean enabled;
    private final boolean lllEnabled;
    private final String baseUrl;
    private final long timeoutMs;
    private final int defaultDegreeBound;
    private final int defaultPrecisionDigits;

    private final JuliaMathClient juliaMathClient;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final AtomicLong totalCalls       = new AtomicLong();
    private final AtomicLong recognisedCalls  = new AtomicLong();
    private final AtomicLong transportFails   = new AtomicLong();
    private final AtomicLong safetyRejects    = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public LllRecogniserClient(
            @Value("${app.julia.recognise.enabled:false}") boolean enabled,
            @Value("${app.julia.recognise.lll.enabled:false}") boolean lllEnabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.julia.recognise.timeoutMs:5000}") long timeoutMs,
            @Value("${app.julia.recognise.lll.degreeBound:4}") int defaultDegreeBound,
            @Value("${app.julia.recognise.pslqPrecisionDigits:200}") int defaultPrecisionDigits,
            JuliaMathClient juliaMathClient,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.lllEnabled = lllEnabled;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.timeoutMs = timeoutMs;
        this.defaultDegreeBound = defaultDegreeBound;
        this.defaultPrecisionDigits = defaultPrecisionDigits;
        this.juliaMathClient = juliaMathClient;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled()   { return enabled && lllEnabled; }
    public boolean isAvailable() {
        return isEnabled() && juliaMathClient.isAvailable();
    }

    /**
     * Attempt to recover a minimal polynomial for {@code value}
     * under the requested degree bound. Returns Optional.empty()
     * when PSLQ found no relation OR found one with coefficients
     * above the sidecar's noise cap.
     *
     * @param value           decimal string of the value to recognise
     * @param degreeBound     null → use the configured default
     * @param precisionDigits null → use the configured default
     */
    public Optional<MinPolyHit> recognise(String value, Integer degreeBound,
                                           Integer precisionDigits) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        if (value.length() > PslqRecogniserClient.MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "value exceeds " + PslqRecogniserClient.MAX_VALUE_LENGTH + "-char cap");
        }
        if (!PslqRecogniserClient.VALUE_PATTERN.matcher(value).matches()) {
            safetyRejects.incrementAndGet();
            throw new IllegalArgumentException(
                    "value must be a plain decimal literal "
                            + "(sign, digits, optional fraction, optional exponent)");
        }
        totalCalls.incrementAndGet();

        if (!enabled) {
            throw new LllRecogniserException("not_enabled",
                    "Recognition is disabled (app.julia.recognise.enabled=false)");
        }
        if (!lllEnabled) {
            throw new LllRecogniserException("not_enabled",
                    "LLL minpoly recovery is disabled "
                            + "(app.julia.recognise.lll.enabled=false)");
        }

        int deg = degreeBound != null ? degreeBound : defaultDegreeBound;
        int prec = precisionDigits != null ? precisionDigits : defaultPrecisionDigits;

        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("value", value);
        body.put("degreeBound", deg);
        body.put("precisionDigits", prec);
        body.put("timeoutMs", timeoutMs);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LllRecogniserException("request_serialise",
                    "Failed to serialise /recognise/minpoly body", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/recognise/minpoly"))
                .timeout(Duration.ofMillis(timeoutMs + 5_000))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            transportFails.incrementAndGet();
            throw new LllRecogniserException("transport",
                    "Sidecar unreachable at " + baseUrl + "/recognise/minpoly: "
                            + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new LllRecogniserException("response_parse",
                    "Sidecar returned non-JSON (status=" + resp.statusCode() + "): "
                            + truncate(resp.body(), 200), e);
        }

        if (!root.path("ok").asBoolean(false)) {
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            throw new LllRecogniserException(code,
                    "/recognise/minpoly returned ok=false: " + code + ": " + explanation);
        }

        if (!root.path("recognized").asBoolean(false)) {
            return Optional.empty();
        }

        String minPoly = root.path("minPoly").asText("");
        int degree = root.path("degree").asInt(-1);
        int precisionUsed = root.path("precision_used").asInt(prec);

        List<Integer> coefficients = new ArrayList<>();
        JsonNode coeffsNode = root.path("coefficients");
        if (coeffsNode.isArray()) {
            for (JsonNode c : coeffsNode) {
                coefficients.add(c.asInt(0));
            }
        }

        if (minPoly.isEmpty() || degree < 0 || coefficients.isEmpty()) {
            log.warn("LllRecogniserClient: sidecar reported recognized=true " +
                    "but minPoly/degree/coefficients incomplete; treating as no match");
            return Optional.empty();
        }

        recognisedCalls.incrementAndGet();
        return Optional.of(new MinPolyHit(minPoly, degree, coefficients, precisionUsed));
    }

    /** Convenience overload using the configured defaults. */
    public Optional<MinPolyHit> recognise(String value) {
        return recognise(value, null, null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public Stats stats() {
        return new Stats(
                totalCalls.get(),
                recognisedCalls.get(),
                transportFails.get(),
                safetyRejects.get());
    }

    public record Stats(
            long totalCalls,
            long recognisedCalls,
            long transportFails,
            long safetyRejects) {}

    /**
     * Result of a successful minimal-polynomial recovery.
     *
     * @param minPoly       human-readable polynomial string suitable
     *                      for direct citation, e.g.
     *                      {@code "x^4 - 10*x^2 + 1"}, {@code "x^2 - x - 1"},
     *                      {@code "x^3 - 2"}. Leading coefficient
     *                      normalised positive.
     * @param degree        actual degree (highest nonzero coefficient)
     * @param coefficients  ascending integer coefficients [a0, a1, ..., ad]
     *                      such that {@code sum(a_i * value^i) ≈ 0}
     * @param precisionUsed working precision (decimal digits) the
     *                      sidecar ran the PSLQ search at
     */
    public record MinPolyHit(
            String minPoly,
            int degree,
            List<Integer> coefficients,
            int precisionUsed) {}
}
