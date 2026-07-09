package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Phase 32 §32.5d — JVM client for the py-sidecar's offline domain-reference lookups
 * ({@code POST /lookup/oeis}, {@code /lookup/dlmf}; port 8089; sidecar {@code domain_refs.jl}).
 * Fully local, offline request path. Fail-soft: any transport/parse problem returns a short
 * "unavailable" string so a lookup never blocks a paper (like {@link GroundingClient}).
 */
public class DomainRefClient {

    private static final Logger log = LoggerFactory.getLogger(DomainRefClient.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    public DomainRefClient(String pySidecarUrl) {
        this.baseUrl = pySidecarUrl == null || pySidecarUrl.isBlank()
                ? "http://127.0.0.1:8089" : pySidecarUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    private JsonNode post(String path, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode node = OM.readTree(resp.body());
            // ok_response merges the value fields into the TOP level (matches/sections live there,
            // NOT under a "value" key — same shape GroundingClient reads).
            return node.path("ok").asBoolean(false) ? node : null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("domain-ref {} failed ({}) — lookup unavailable", path, e.getMessage());
            return null;
        }
    }

    /** OEIS integer-sequence identification. {@code terms} are the leading terms (as strings). */
    public String lookupOeis(List<String> terms, int topK) {
        ObjectNode body = OM.createObjectNode();
        ArrayNode t = body.putArray("terms");
        terms.forEach(t::add);
        body.put("topK", topK);
        JsonNode v = post("/lookup/oeis", body);
        if (v == null) {
            return "lookup_oeis unavailable (domain-reference sidecar not reachable or OEIS not built).";
        }
        JsonNode matches = v.path("matches");
        if (!matches.isArray() || matches.isEmpty()) {
            return "lookup_oeis: no OEIS sequence matches these terms — this integer sequence is NOT "
                    + "a known catalogued one (a reportable observation). Make no OEIS citation.";
        }
        StringBuilder sb = new StringBuilder("lookup_oeis: matching OEIS sequence(s) — this sequence "
                + "IS known; cite the A-number:\n");
        for (JsonNode m : matches) {
            sb.append("- ").append(m.path("id").asText()).append(": ")
              .append(m.path("name").asText());
            if (!m.path("terms_preview").asText("").isBlank()) {
                sb.append(" [").append(m.path("terms_preview").asText()).append("]");
            }
            sb.append(" (").append(m.path("url").asText()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    /** NIST DLMF special-function section lookup (BM25). */
    public String lookupDlmf(String query, int topK) {
        ObjectNode body = OM.createObjectNode();
        body.put("query", query == null ? "" : query);
        body.put("topK", topK);
        JsonNode v = post("/lookup/dlmf", body);
        if (v == null) {
            return "lookup_dlmf unavailable (domain-reference sidecar not reachable or DLMF not built).";
        }
        JsonNode secs = v.path("sections");
        if (!secs.isArray() || secs.isEmpty()) {
            return "lookup_dlmf: no DLMF section matched — no authoritative special-function reference "
                    + "for this query.";
        }
        StringBuilder sb = new StringBuilder("lookup_dlmf: NIST DLMF section(s) — authoritative "
                + "special-function reference; formulas quotable, cite as 'DLMF §<id>':\n");
        for (JsonNode s : secs) {
            sb.append("- DLMF §").append(s.path("id").asText()).append(" — ")
              .append(s.path("title").asText());
            if (!s.path("snippet").asText("").isBlank()) {
                sb.append("\n    ").append(s.path("snippet").asText());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
