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
import java.util.regex.Pattern;

/**
 * Phase 26 §26.2 — JVM peer of the sidecar's
 * {@code /recognise/constant} endpoint. PSLQ integer-relation search
 * against named basis sets ({@code BASIC} / {@code PHYSICS}) via
 * mpmath under PyCall.jl.
 *
 * <h2>No cache</h2>
 *
 * Unlike {@link OeisRecogniserClient}, PslqRecogniserClient does NOT
 * cache. The cache key would have to include the full value string,
 * the basis name, and the precision; collisions are too rare in
 * practice (each refined constant in a paper is unique to that
 * computation) and the savings don't justify a Postgres write per
 * call. PSLQ runs in ~50 ms warm-path against the BASIC basis at
 * 200 digits anyway.
 *
 * <h2>Pre-flight safety</h2>
 *
 * The value string is checked against {@link #VALUE_PATTERN} on the
 * JVM side so a malformed input never burns sidecar time. This
 * mirrors the sidecar's PSLQ_VALUE_PATTERN — kept in sync.
 */
@Service
public class PslqRecogniserClient {

    private static final Logger log = LoggerFactory.getLogger(PslqRecogniserClient.class);

    /**
     * Mirrors {@code PSLQ_VALUE_PATTERN} in
     * {@code julia-math-sidecar/src/handlers.jl}. A plain decimal
     * literal, with optional sign, optional fraction, and optional
     * exponent. Defense in depth — sidecar runs the same check.
     */
    static final Pattern VALUE_PATTERN =
            Pattern.compile("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");

    static final int MAX_VALUE_LENGTH = 1024;

    private final boolean enabled;
    private final String baseUrl;
    private final long timeoutMs;
    private final int defaultPrecisionDigits;

    private final JuliaMathClient juliaMathClient;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final AtomicLong totalCalls       = new AtomicLong();
    private final AtomicLong recognisedCalls  = new AtomicLong();
    private final AtomicLong transportFails   = new AtomicLong();
    private final AtomicLong safetyRejects    = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public PslqRecogniserClient(
            @Value("${app.julia.recognise.enabled:false}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.julia.recognise.timeoutMs:5000}") long timeoutMs,
            @Value("${app.julia.recognise.pslqPrecisionDigits:200}") int defaultPrecisionDigits,
            JuliaMathClient juliaMathClient,
            ObjectMapper mapper) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.timeoutMs = timeoutMs;
        this.defaultPrecisionDigits = defaultPrecisionDigits;
        this.juliaMathClient = juliaMathClient;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAvailable() {
        return enabled && juliaMathClient.isAvailable();
    }

    /**
     * Attempt to recognise {@code value} as a known constant under
     * the named basis set. Returns an empty Optional when PSLQ
     * found no clean integer relation OR found one but with
     * coefficients above the sidecar's noisy-relation cap.
     *
     * @param value     decimal string of the constant to recognise
     *                  (≤ {@link #MAX_VALUE_LENGTH} chars, no
     *                  embedded math expressions)
     * @param basis     name of the basis set ({@code "BASIC"} or
     *                  {@code "PHYSICS"}); the sidecar checks the
     *                  allowlist
     * @param precisionDigits working precision for the PSLQ call;
     *                  null → client default ({@code app.julia.recognise.pslqPrecisionDigits})
     * @throws PslqRecogniserException with {@code errorCode()}
     *         reflecting the sidecar's envelope
     * @throws IllegalArgumentException for null/empty value or
     *         value that doesn't match the decimal-literal pattern
     */
    public Optional<PslqHit> recognise(String value, String basis,
                                        Integer precisionDigits) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "value exceeds " + MAX_VALUE_LENGTH + "-char cap");
        }
        if (!VALUE_PATTERN.matcher(value).matches()) {
            safetyRejects.incrementAndGet();
            throw new IllegalArgumentException(
                    "value must be a plain decimal literal "
                            + "(sign, digits, optional fraction, optional exponent)");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("basis is required (e.g. 'BASIC')");
        }
        totalCalls.incrementAndGet();

        if (!enabled) {
            throw new PslqRecogniserException("not_enabled",
                    "PslqRecogniserClient is disabled (app.julia.recognise.enabled=false)");
        }

        int prec = precisionDigits != null ? precisionDigits : defaultPrecisionDigits;

        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("value", value);
        body.put("basis", basis);
        body.put("precisionDigits", prec);
        body.put("timeoutMs", timeoutMs);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PslqRecogniserException("request_serialise",
                    "Failed to serialise /recognise/constant body", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/recognise/constant"))
                .timeout(Duration.ofMillis(timeoutMs + 5_000))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            transportFails.incrementAndGet();
            throw new PslqRecogniserException("transport",
                    "Sidecar unreachable at " + baseUrl + "/recognise/constant: "
                            + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new PslqRecogniserException("response_parse",
                    "Sidecar returned non-JSON (status=" + resp.statusCode() + "): "
                            + truncate(resp.body(), 200), e);
        }

        if (!root.path("ok").asBoolean(false)) {
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            throw new PslqRecogniserException(code,
                    "/recognise/constant returned ok=false: " + code + ": " + explanation);
        }

        if (!root.path("recognized").asBoolean(false)) {
            return Optional.empty();
        }

        String name = root.path("name").asText("");
        String basisUsed = root.path("basis_used").asText(basis);
        int precisionUsed = root.path("precision_used").asInt(prec);

        // The relation field is a JSON array of [coefficient, basis_name]
        // pairs. Round-trip into Java records for the audit chain.
        List<RelationTerm> relation = new ArrayList<>();
        JsonNode relNode = root.path("relation");
        if (relNode.isArray()) {
            for (JsonNode pair : relNode) {
                if (pair.isArray() && pair.size() == 2) {
                    int coeff = pair.get(0).asInt(0);
                    String basisName = pair.get(1).asText("");
                    relation.add(new RelationTerm(coeff, basisName));
                }
            }
        }

        if (name.isEmpty()) {
            log.warn("PslqRecogniserClient: sidecar reported recognized=true " +
                    "but name missing; treating as no match");
            return Optional.empty();
        }

        recognisedCalls.incrementAndGet();
        return Optional.of(new PslqHit(name, relation, basisUsed, precisionUsed));
    }

    /** Convenience overload using the configured default precision. */
    public Optional<PslqHit> recognise(String value, String basis) {
        return recognise(value, basis, null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Snapshot of counters at call time. */
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
     * A single term in a PSLQ integer relation, e.g. ({@code -1},
     * {@code "pi"}). The relation expresses
     * {@code sum(coefficient * basis_element) ≈ 0}.
     */
    public record RelationTerm(int coefficient, String basisName) {}

    /**
     * Result of a successful PSLQ recognition.
     *
     * @param name        human-readable expression for the recognised
     *                    value, e.g. {@code "pi"}, {@code "-zeta(3)"},
     *                    {@code "(pi + e) / 2"}. Suitable for direct
     *                    citation by the narration layer.
     * @param relation    raw integer relation as
     *                    {@code [(a0, "<target>"), (a1, name1), ...]}
     *                    pairs. Preserves the full audit trail so the
     *                    coverage probe + RecognitionCitationValidator
     *                    can re-verify.
     * @param basisUsed   which basis name produced the match
     *                    ({@code "BASIC"}, {@code "PHYSICS"}).
     * @param precisionUsed working precision (in decimal digits) the
     *                    sidecar ran the search at.
     */
    public record PslqHit(
            String name,
            List<RelationTerm> relation,
            String basisUsed,
            int precisionUsed) {}
}
