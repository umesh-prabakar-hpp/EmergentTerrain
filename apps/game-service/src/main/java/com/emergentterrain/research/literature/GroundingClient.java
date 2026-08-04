package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 32 §32.5 step 4 — JVM client for the py-sidecar's offline BM25 grounding endpoint
 * ({@code POST /ground/claim}, port 8089; sidecar {@code literature_grounding.jl}). Fully local
 * request path: the corpus was built offline; no network beyond loopback.
 *
 * <p>Failure-soft by contract: any transport/parse problem returns {@code null} and the caller
 * falls back to the curated-corpus behavior — grounding must never block or degrade a paper the
 * way a hard dependency would (the py-sidecar may be down, cold, or corpus-less).
 */
public class GroundingClient {

    private static final Logger log = LoggerFactory.getLogger(GroundingClient.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** One scored reference. {@code key} is the citation key (OpenAlex id, e.g. "W123").
     *  {@code field}/{@code subfield}/{@code topic} are the OpenAlex subject tags (blank for
     *  pre-rebuild corpus rows) — the signal the professor's subject check reads to reject a
     *  cross-domain near-miss that overlaps only on a generic word. */
    public record Evidence(String key, String title, int year, String venue, String authors,
                           String doi, String field, String subfield, String topic,
                           double score, String snippet) {}

    /** verdict ∈ {EVIDENCE, SILENT}; under SILENT the evidence entries are NEAR MISSES. */
    public record GroundingResult(String verdict, double confidence, int corpusDocs,
                                  List<Evidence> evidence) {
        public boolean isEvidence() { return "EVIDENCE".equals(verdict); }
    }

    private final String baseUrl;
    private final HttpClient http;

    public GroundingClient(String pySidecarUrl) {
        this.baseUrl = pySidecarUrl == null || pySidecarUrl.isBlank()
                ? "http://127.0.0.1:8089" : pySidecarUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Ground a claim against the local corpus; {@code null} on any failure (caller falls back). */
    public GroundingResult ground(String claim, int topK) {
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("claim", claim);
            body.put("topK", topK);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/ground/claim"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("grounding: /ground/claim HTTP {} — falling back to curated corpus",
                        response.statusCode());
                return null;
            }
            JsonNode node = OM.readTree(response.body());
            if (!node.path("ok").asBoolean(false)) {
                log.warn("grounding: /ground/claim not ok ({}) — falling back",
                        node.path("error").asText(node.path("message").asText("?")));
                return null;
            }
            List<Evidence> evidence = new ArrayList<>();
            for (JsonNode e : node.path("evidence")) {
                evidence.add(new Evidence(
                        e.path("key").asText(""), e.path("title").asText(""),
                        e.path("year").asInt(0), e.path("venue").asText(""),
                        e.path("authors").asText(""), e.path("doi").asText(""),
                        e.path("field").asText(""), e.path("subfield").asText(""),
                        e.path("topic").asText(""),
                        e.path("score").asDouble(0.0), e.path("snippet").asText("")));
            }
            return new GroundingResult(node.path("verdict").asText("SILENT"),
                    node.path("confidence").asDouble(0.0),
                    node.path("corpus_docs").asInt(0), evidence);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("grounding: /ground/claim failed ({}) — falling back to curated corpus",
                    e.getMessage());
            return null;
        }
    }
}
