package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 32 §32.5 step 1 (phase-32-openalex-corpus.md item A) — pure-HTTP OpenAlex client
 * (zero-heavy-deps per the ARC precedent; {@code java.net.http}, no SDK). Domain-neutral: it
 * speaks the OpenAlex Works/Topics API, nothing number-theory-specific lives here.
 *
 * <p>Facts probed live 2026-07-04 against {@code api.openalex.org}:
 * <ul>
 *   <li>keyless calls WORK on a free anonymous allowance (~$0.10/day; keyed keys get $1/day);</li>
 *   <li>every response carries authoritative spend headers — {@code x-ratelimit-cost-usd} (this
 *       call) and {@code x-ratelimit-remaining-usd} — plus {@code meta.cost_usd} in list bodies;</li>
 *   <li>topic ids resolve via {@code /topics?search=…} (Analytic Number Theory = T11166).</li>
 * </ul>
 *
 * <p>Every live call goes through the {@link OpenAlexCreditLedger} (pre-call ceiling check with
 * the static class estimate; post-call record of the ACTUAL header cost) and the
 * {@link OpenAlexResponseCache} (a repeat request is a hit — zero network, zero spend). The
 * {@code api_key} parameter is appended ONLY on the wire; cache keys and logs carry the
 * key-free URL so the secret never reaches disk. Calls are paced ≥120 ms apart (10/s hard cap).
 */
public class OpenAlexClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexClient.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final long MIN_INTERVAL_MS = 120;

    private final String baseUrl;
    private final String apiKey;      // may be blank — anonymous tier works, smaller allowance
    private final String mailto;      // polite-pool identification; optional
    private final OpenAlexCreditLedger ledger;
    private final OpenAlexResponseCache cache;
    private final HttpClient http;

    private long lastCallAtMs = 0;

    public OpenAlexClient(String baseUrl, String apiKey, String mailto,
                          OpenAlexCreditLedger ledger, OpenAlexResponseCache cache) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openalex.org" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mailto = mailto == null ? "" : mailto.trim();
        this.ledger = ledger;
        this.cache = cache;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** List works by filter (cost class LIST, $0.0001). {@code params} may add select/sort/cursor. */
    public JsonNode listWorks(Map<String, String> params) {
        return get("/works", params, OpenAlexCreditLedger.COST_LIST);
    }

    /** Full-text search over works (cost class SEARCH, $0.001). */
    public JsonNode searchWorks(String query, Map<String, String> extraParams) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search", query);
        if (extraParams != null) params.putAll(extraParams);
        return get("/works", params, OpenAlexCreditLedger.COST_SEARCH);
    }

    /** Singleton lookup by OpenAlex id / DOI URL (≈ free). */
    public JsonNode getWork(String idOrDoi) {
        return get("/works/" + idOrDoi, Map.of(), OpenAlexCreditLedger.COST_SINGLETON);
    }

    /** Topics search — used once per build to resolve subfield/topic ids (cost class SEARCH). */
    public JsonNode searchTopics(String query) {
        return get("/topics", Map.of("search", query, "select", "id,display_name,subfield"),
                OpenAlexCreditLedger.COST_SEARCH);
    }

    /**
     * Core request path: cache lookup → ledger ceiling check → paced HTTP GET → record actual
     * header cost → cache store. Cache key and log lines use the key-free URL.
     */
    public JsonNode get(String path, Map<String, String> params, double estimatedCostUsd) {
        String publicUrl = buildUrl(path, params, false);
        var cached = cache != null ? cache.get(publicUrl) : java.util.Optional.<String>empty();
        if (cached.isPresent()) {
            return parse(cached.get(), publicUrl);
        }
        if (ledger != null) {
            ledger.assertBudget(estimatedCostUsd);
        }
        pace();
        String wireUrl = buildUrl(path, params, true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(wireUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("OpenAlex request failed: " + publicUrl + ": " + e.getMessage(), e);
        }
        double actualCost = headerCost(response, estimatedCostUsd);
        if (ledger != null) {
            ledger.record(actualCost);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RuntimeException("OpenAlex auth rejected (HTTP " + response.statusCode()
                    + ") for " + publicUrl + " — check the OPENALEX_API_KEY secret and whether the "
                    + "key parameter convention changed (see developers.openalex.org). Body: "
                    + clip(response.body()));
        }
        if (response.statusCode() == 429) {
            throw new OpenAlexCreditLedger.BudgetExceededException(
                    "OpenAlex rate/credit limit hit (HTTP 429) — the free allowance is exhausted "
                            + "for today; resume tomorrow (the JSONL cache makes completed pages free).");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAlex HTTP " + response.statusCode() + " for "
                    + publicUrl + ": " + clip(response.body()));
        }
        if (cache != null) {
            cache.put(publicUrl, response.body());
        }
        log.debug("openalex GET {} cost=${} remaining=${}", publicUrl, actualCost,
                response.headers().firstValue("x-ratelimit-remaining-usd").orElse("?"));
        return parse(response.body(), publicUrl);
    }

    /**
     * Reconstruct a plain-text abstract from OpenAlex's {@code abstract_inverted_index}
     * (word → [positions]). Pure and static — the one piece of response decoding with real
     * logic, so it is unit-tested.
     */
    public static String reconstructAbstract(JsonNode invertedIndex) {
        if (invertedIndex == null || !invertedIndex.isObject() || invertedIndex.isEmpty()) {
            return "";
        }
        TreeMap<Integer, String> byPosition = new TreeMap<>();
        var fields = invertedIndex.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            for (JsonNode pos : entry.getValue()) {
                byPosition.put(pos.asInt(), entry.getKey());
            }
        }
        return String.join(" ", byPosition.values());
    }

    private String buildUrl(String path, Map<String, String> params, boolean withKey) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);
        Map<String, String> all = new LinkedHashMap<>(params == null ? Map.of() : params);
        if (!mailto.isBlank()) {
            all.put("mailto", mailto);
        }
        if (withKey && !apiKey.isBlank()) {
            all.put("api_key", apiKey);
        }
        boolean first = true;
        for (var e : all.entrySet()) {
            sb.append(first ? '?' : '&').append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private double headerCost(HttpResponse<String> response, double fallback) {
        var header = response.headers().firstValue("x-ratelimit-cost-usd");
        if (header.isPresent()) {
            try {
                return Double.parseDouble(header.get());
            } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private void pace() {
        long now = System.currentTimeMillis();
        long wait = lastCallAtMs + MIN_INTERVAL_MS - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAtMs = System.currentTimeMillis();
    }

    private static JsonNode parse(String body, String url) {
        try {
            return OM.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException("OpenAlex response not JSON for " + url + ": " + clip(body));
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
