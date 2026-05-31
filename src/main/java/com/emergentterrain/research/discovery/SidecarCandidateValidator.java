package com.emergentterrain.research.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 30 fork 2b Phase 3 — production {@link CandidateValidator}: POSTs the
 * candidate to the sidecar {@code /discover/validate} endpoint and maps the JSON
 * envelope to a {@link ValidationResult}. The endpoint is itself ENV-gated
 * ({@code ET_DISCOVER_EXEC}); if discovery is disabled there it returns a 403,
 * surfaced here as a {@code na} verdict so the caller sees it clearly.
 */
@Component
public class SidecarCandidateValidator implements CandidateValidator {

    private static final Logger log = LoggerFactory.getLogger(SidecarCandidateValidator.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String url;
    private final long timeoutMs;

    public SidecarCandidateValidator(
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.research.discovery.httpTimeoutMs:35000}") long timeoutMs,
            ObjectMapper mapper) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.url = b + "/discover/validate";
        this.timeoutMs = timeoutMs;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ValidationResult validate(String code, DiscoveryTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("checkpoints", task.checkpoints());
        body.put("anchors", task.anchors());          // Long keys serialise as strings — the endpoint expects that
        body.put("target", task.target());
        body.put("cpuSeconds", task.cpuSeconds());
        body.put("wallSeconds", task.wallSeconds());
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parse(mapper.readTree(resp.body()));
        } catch (Exception e) {
            log.warn("discovery validate: sidecar call failed ({}): {}", url, e.getMessage());
            return new ValidationResult("transport", "na", Map.of(), Map.of(), null, null,
                    "sidecar unreachable: " + e.getMessage());
        }
    }

    /**
     * Map the {@code /discover/validate} JSON envelope to a {@link ValidationResult}.
     * Package-private and static for unit testing without a live sidecar.
     */
    static ValidationResult parse(JsonNode root) {
        // Endpoint-level error (e.g. 403 disabled, 400 bad_request): ok=false.
        if (!root.path("ok").asBoolean(false) && !root.has("verdict")) {
            return new ValidationResult(root.path("error").asText("error"), "na",
                    Map.of(), Map.of(), null, null, root.path("explanation").asText(""));
        }
        String status = root.path("status").asText("");
        String verdict = root.path("verdict").asText("na");
        Map<Long, Long> values = readLongMap(root.get("values"));
        Map<Long, Long> oracle = readLongMap(root.get("oracle"));
        ValidationResult.FirstFail ff = null;
        JsonNode f = root.get("firstFail");
        if (f != null && f.isArray() && f.size() >= 3) {
            Long got = f.get(1).isNull() ? null : f.get(1).asLong();
            ff = new ValidationResult.FirstFail(f.get(0).asLong(), got, f.get(2).asLong());
        }
        Long targetValue = root.has("targetValue") && !root.get("targetValue").isNull()
                ? root.get("targetValue").asLong() : null;
        return new ValidationResult(status, verdict, values, oracle, ff, targetValue,
                root.path("detail").asText(""));
    }

    private static Map<Long, Long> readLongMap(JsonNode node) {
        Map<Long, Long> out = new TreeMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(Long.parseLong(e.getKey()), e.getValue().asLong()));
        }
        return out;
    }
}
