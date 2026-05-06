package com.emergentterrain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Groq-backed LlmPool. At startup, fetches the live model list from the Groq /models
 * endpoint, runs a two-phase evaluation per model (peer ranking + hypothesis-falsification
 * test), scores test responses via Ollama, combines Borda count and Ollama quality into a
 * final score, and selects the top-N models (app.ai.groq.primaryModelCount, default 2).
 * Runtime calls fall back through the ranked list on error.
 *
 * If Groq is unreachable at startup the application exits immediately with a clear message.
 * Active when app.ai.groq.enabled=true.
 * Set app.ai.groq.preferredModels (comma-separated) to skip discovery and pin models.
 */
@Component
@ConditionalOnProperty(name = "app.ai.groq.enabled", havingValue = "true")
public class GroqPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(GroqPool.class);

    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private static final Set<String> EXCLUDED_SUBSTRINGS = Set.of(
            "whisper", "prompt-guard", "orpheus", "safeguard");

    // Only consider models at or above this parameter threshold (MoE models are
    // sized by their per-token active parameters, e.g. 8x7b is treated as 7B)
    private static final int MIN_MODEL_SIZE_B = 70;

    private static final long INTER_CALL_DELAY_MS  = 1500; // between models
    private static final long INTRA_CALL_DELAY_MS  = 2000; // between ranking and test for same model
    private static final int  MAX_RETRIES          = 4;
    private static final long INITIAL_BACKOFF_MS   = 2000;

    // Ranking call only needs to return a short JSON array of names
    private static final int RANKING_MAX_TOKENS = 150;

    // Hypothesis-falsification test — correct answer: REFUTED, critical angle ~25 degrees
    private static final String TEST_PROMPT =
            "A physics simulation computed pendulum period errors (L=0.85 m, T0=1.846 s):\n" +
            "5deg->0.05%  10deg->0.19%  15deg->0.43%  20deg->0.77%  25deg->1.20%  30deg->1.74%\n\n" +
            "Hypothesis: The small-angle approximation error exceeds 1% below theta=20 degrees.\n\n" +
            "Assess this hypothesis. Reply with ONLY this JSON, no other text:\n" +
            "{\"verdict\":\"SUPPORTED or REFUTED or PARTIAL\"," +
            "\"critical_angle_deg\":<integer>," +
            "\"reasoning\":\"one concise sentence citing the specific data\"}";

    // Max Ollama score per model: verdict(10) + angle(10) + reasoning(10)
    private static final int MAX_OLLAMA_SCORE = 30;

    private final String apiKey;
    private final int maxTokens;
    private final String ollamaJudgeUrl;
    private final String ollamaJudgeModel;
    private final List<String> activeModels;
    private final Semaphore slots;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GroqPool(
            @Value("${app.ai.groq.apiKey:}") String apiKey,
            @Value("${app.ai.groq.parallelism:3}") int parallelism,
            @Value("${app.ai.groq.maxTokens:600}") int maxTokens,
            @Value("${app.ai.groq.primaryModelCount:10}") int primaryModelCount,
            @Value("${app.ai.groq.preferredModels:}") String preferredModels,
            @Value("${app.ai.ollamaJudgeUrl:http://localhost:11434}") String ollamaJudgeUrl,
            @Value("${app.ai.ollamaJudgeModel:gemma3:4b}") String ollamaJudgeModel,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GroqPool: app.ai.groq.apiKey is required when app.ai.groq.enabled=true");
        }
        this.apiKey          = apiKey;
        this.maxTokens       = maxTokens;
        this.ollamaJudgeUrl  = ollamaJudgeUrl;
        this.ollamaJudgeModel = ollamaJudgeModel;
        this.mapper          = mapper;
        this.slots           = new Semaphore(parallelism);
        this.http            = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        if (preferredModels != null && !preferredModels.isBlank()) {
            this.activeModels = List.of(preferredModels.split(","))
                    .stream().map(String::trim).filter(s -> !s.isBlank()).toList();
            log.info("GroqPool: using configured models={} parallelism={}", activeModels, parallelism);
        } else {
            this.activeModels = discoverAndRankModels(primaryModelCount);
            log.info("GroqPool: selected models={} parallelism={}", activeModels, parallelism);
        }
    }

    // --- LlmPool ---

    @Override
    public String callSync(String prompt) {
        acquireSlot();
        try {
            return callSyncWithFallback(prompt);
        } finally {
            slots.release();
        }
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        acquireSlot();
        try {
            callStreamWithFallback(prompt, tokenConsumer);
        } finally {
            slots.release();
        }
    }

    @Override public String selectedModel()        { return activeModels.get(0); }
    @Override public List<String> selectedModels() { return activeModels; }
    @Override public int backendCount()            { return 1; }
    @Override public int healthyCount()            { return 1; }

    // --- Runtime fallback wrappers ---

    private String callSyncWithFallback(String prompt) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            try {
                return syncCall(modelId, prompt, maxTokens);
            } catch (RuntimeException e) {
                last = e;
                int next = (start + i + 1) % size;
                if (i < size - 1) {
                    log.warn("GroqPool: model={} failed, trying next model={}: {}",
                            modelId, activeModels.get(next), e.getMessage());
                }
            }
        }
        throw last;
    }

    private void callStreamWithFallback(String prompt, Consumer<String> tokenConsumer) {
        int size  = activeModels.size();
        int start = rrIndex.getAndIncrement() % size;
        RuntimeException last = null;
        for (int i = 0; i < size; i++) {
            String modelId = activeModels.get((start + i) % size);
            try {
                streamCall(modelId, prompt, tokenConsumer);
                return;
            } catch (RuntimeException e) {
                last = e;
                int next = (start + i + 1) % size;
                if (i < size - 1) {
                    log.warn("GroqPool: model={} stream failed, trying next model={}: {}",
                            modelId, activeModels.get(next), e.getMessage());
                }
            }
        }
        throw last;
    }

    // --- Model discovery: peer ranking + hypothesis test + Ollama scoring ---

    private List<String> discoverAndRankModels(int primaryModelCount) {
        List<String> candidates = fetchCandidateModels();
        int n = candidates.size();
        log.info("GroqPool: evaluating {} models (live list): {}", n, candidates);

        String rankingPrompt = buildRankingPrompt(candidates);
        Map<String, Integer> bordaScores  = new HashMap<>();
        Map<String, String>  testResponses = new LinkedHashMap<>();
        for (String c : candidates) bordaScores.put(c, 0);

        for (int i = 0; i < n; i++) {
            String modelId = candidates.get(i);
            if (i > 0) sleep(INTER_CALL_DELAY_MS);

            // Phase 1: ranking call — low token budget, just a JSON array of names
            try {
                String rankResponse = syncCallWithBackoff(modelId, rankingPrompt, RANKING_MAX_TOKENS);
                List<String> ranking = parseRanking(rankResponse, candidates);
                for (int rank = 0; rank < ranking.size(); rank++) {
                    bordaScores.merge(ranking.get(rank), n - 1 - rank, Integer::sum);
                }
                log.info("GroqPool ranking: rater={} ranked {}/{}", modelId, ranking.size(), n);
            } catch (Exception e) {
                log.warn("GroqPool ranking skipped rater={}: {}", modelId, e.getMessage());
            }

            // Phase 2: hypothesis-falsification test — full token budget, quality matters
            sleep(INTRA_CALL_DELAY_MS);
            try {
                String testResponse = syncCallWithBackoff(modelId, TEST_PROMPT, maxTokens);
                testResponses.put(modelId, testResponse);
                log.info("GroqPool test: model={} responded len={}", modelId, testResponse.length());
            } catch (Exception e) {
                log.warn("GroqPool test skipped model={}: {}", modelId, e.getMessage());
            }
        }

        // Phase 3: Ollama scores all test responses as neutral judge
        Map<String, Integer> ollamaScores = Collections.emptyMap();
        if (!testResponses.isEmpty()) {
            try {
                ollamaScores = scoreWithOllama(testResponses, candidates);
                log.info("GroqPool: Ollama quality scores={}", ollamaScores);
            } catch (Exception e) {
                log.warn("GroqPool: Ollama scoring failed — using Borda only: {}", e.getMessage());
            }
        }

        // Combine: 50% normalised Borda + 50% normalised Ollama score
        int maxBorda = n > 1 ? n * (n - 1) : 1;
        Map<String, Integer> finalOllama = ollamaScores;

        List<String> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare(combined(b, bordaScores, finalOllama, maxBorda),
                                                  combined(a, bordaScores, finalOllama, maxBorda)))
                .collect(Collectors.toList());

        // Log full breakdown
        log.info("GroqPool evaluation breakdown (borda/ollama/combined):");
        for (String m : ranked) {
            double nb = (double) bordaScores.getOrDefault(m, 0) / maxBorda;
            double no = (double) finalOllama.getOrDefault(m, 0) / MAX_OLLAMA_SCORE;
            log.info("  {} borda={}({})  ollama={}({})  combined={}",
                    m,
                    bordaScores.getOrDefault(m, 0), String.format("%.2f", nb),
                    finalOllama.getOrDefault(m, 0),  String.format("%.2f", no),
                    String.format("%.3f", 0.5 * nb + 0.5 * no));
        }

        int take = Math.min(primaryModelCount, ranked.size());
        List<String> selected = Collections.unmodifiableList(ranked.subList(0, take));
        log.info("GroqPool: top{}={}", take, selected);
        return selected;
    }

    private double combined(String m, Map<String, Integer> borda,
                            Map<String, Integer> ollama, int maxBorda) {
        return 0.5 * borda.getOrDefault(m, 0) / maxBorda
             + 0.5 * ollama.getOrDefault(m, 0) / MAX_OLLAMA_SCORE;
    }

    // --- Ranking prompt and parser ---

    private String buildRankingPrompt(List<String> candidates) {
        return "You are selecting AI models for a scientific research simulation covering " +
               "physics, mathematics, and materials science.\n" +
               "Rank ALL models below from best (first) to worst (last) for scientific " +
               "reasoning and mathematical rigor. Every model must appear exactly once — no ties.\n\n" +
               "Models: " + String.join(", ", candidates) + "\n\n" +
               "Reply with ONLY a JSON array of model names in ranked order, best first. " +
               "Use exact model names. No other text:\n" +
               "[\"best-model\", \"second-model\", ...]";
    }

    private List<String> parseRanking(String response, List<String> candidates) {
        if (response == null || response.isBlank()) return List.of();
        String cleaned = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = cleaned.indexOf('[');
        int end   = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("GroqPool: ranking response has no JSON array: {}",
                    cleaned.substring(0, Math.min(cleaned.length(), 400)));
            return List.of();
        }
        try {
            JsonNode arr = mapper.readTree(cleaned.substring(start, end + 1));
            if (!arr.isArray()) {
                log.debug("GroqPool: ranking response parsed but root is not an array: {}", cleaned.substring(start, Math.min(end + 1, start + 200)));
                return List.of();
            }
            Set<String> valid = Set.copyOf(candidates);
            List<String> result = new ArrayList<>();
            for (JsonNode node : arr) {
                String name = node.asText("").trim();
                String matched = matchCandidate(name, valid);
                if (matched != null && !result.contains(matched)) result.add(matched);
            }
            if (result.isEmpty()) {
                log.warn("GroqPool: ranking array had no recognized model names: {}",
                        cleaned.substring(start, Math.min(end + 1, start + 300)));
            }
            return result;
        } catch (Exception e) {
            log.debug("GroqPool: failed to parse ranking: {}", e.getMessage());
            return List.of();
        }
    }

    // Exact match first; then suffix match to handle models that drop their namespace prefix
    // e.g. "gpt-oss-120b" → "openai/gpt-oss-120b", "llama-3.3-70b-versatile" → exact
    private String matchCandidate(String name, Set<String> valid) {
        if (valid.contains(name)) return name;
        return valid.stream()
                .filter(c -> c.endsWith("/" + name))
                .findFirst()
                .orElse(null);
    }

    // --- Ollama quality scoring ---

    private Map<String, Integer> scoreWithOllama(Map<String, String> testResponses,
                                                  List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are evaluating AI model responses to a physics hypothesis-assessment task.\n\n");
        sb.append("Task given to each model:\n").append(TEST_PROMPT).append("\n\n");
        sb.append("Correct answer:\n");
        sb.append("- verdict: REFUTED (error first exceeds 1% at 25 degrees, not below 20)\n");
        sb.append("- critical_angle_deg: 25\n");
        sb.append("- reasoning should cite the 1.20% value at 25 degrees\n\n");
        sb.append("Rate each response 0-10 on three dimensions:\n");
        sb.append("- verdict_correct: 10=REFUTED chosen, 5=PARTIAL, 0=SUPPORTED or absent\n");
        sb.append("- angle_correct: 10=25, 7=within 2 degrees, 3=within 10 degrees, 0=wrong or absent\n");
        sb.append("- reasoning_quality: 10=cites specific data, 5=correct but vague, 0=wrong or absent\n\n");
        sb.append("Model responses:\n");
        for (Map.Entry<String, String> e : testResponses.entrySet()) {
            sb.append("[").append(e.getKey()).append("]: ")
              .append(e.getValue().replace("\n", " ")).append("\n");
        }
        sb.append("\nReply with ONLY this JSON using exact model IDs as keys, no other text:\n");
        sb.append("{\"model-id\":{\"verdict_correct\":N,\"angle_correct\":N,\"reasoning_quality\":N},...}");

        String response = callOllamaSync(sb.toString());
        return parseOllamaScores(response, candidates);
    }

    private Map<String, Integer> parseOllamaScores(String response, List<String> candidates) {
        if (response == null || response.isBlank()) return Map.of();
        String cleaned = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return Map.of();
        try {
            JsonNode root = mapper.readTree(cleaned.substring(start, end + 1));
            Map<String, Integer> result = new HashMap<>();
            for (String candidate : candidates) {
                JsonNode node = root.get(candidate);
                if (node == null || node.isMissingNode()) continue;
                int verdict   = node.path("verdict_correct").asInt(0);
                int angle     = node.path("angle_correct").asInt(0);
                int reasoning = node.path("reasoning_quality").asInt(0);
                result.put(candidate, verdict + angle + reasoning);
            }
            return result;
        } catch (Exception e) {
            log.debug("GroqPool: failed to parse Ollama scores: {}", e.getMessage());
            return Map.of();
        }
    }

    private String callOllamaSync(String prompt) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", ollamaJudgeModel);
            payload.put("stream", false);
            ArrayNode messages = payload.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaJudgeUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Ollama judge error " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readTree(resp.body()).path("message").path("content").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GroqPool: Ollama judge call failed", e);
        }
    }

    // --- Model list fetch ---

    private List<String> fetchCandidateModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                log.error("================================================================================");
                log.error("GroqPool: Groq API key is invalid (401). Check GROQ_API_KEY. Exiting.");
                log.error("================================================================================");
                throw new IllegalStateException("GroqPool: invalid Groq API key — application cannot start");
            }
            if (resp.statusCode() != 200) {
                log.error("================================================================================");
                log.error("GroqPool: Groq API returned {} — service may be unavailable. Exiting.", resp.statusCode());
                log.error("================================================================================");
                throw new IllegalStateException("GroqPool: Groq returned " + resp.statusCode() + " — application cannot start");
            }
            JsonNode root = mapper.readTree(resp.body());
            List<String> models    = new ArrayList<>();
            List<String> skipped   = new ArrayList<>();
            for (JsonNode node : root.path("data")) {
                String id = node.path("id").asText("");
                if (id.isBlank()) continue;
                if (isChatModel(id)) {
                    models.add(id);
                } else {
                    skipped.add(id);
                }
            }
            if (!skipped.isEmpty()) {
                log.info("GroqPool: skipped {} models below {}B (or excluded): {}",
                        skipped.size(), MIN_MODEL_SIZE_B, skipped);
            }
            if (models.isEmpty()) {
                log.error("GroqPool: Groq returned no usable chat models. Exiting.");
                throw new IllegalStateException("GroqPool: no chat models — application cannot start");
            }
            return models;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("================================================================================");
            log.error("GroqPool: Groq API is unreachable — check network / GROQ_API_KEY. Exiting.");
            log.error("================================================================================");
            throw new RuntimeException("GroqPool: Groq unreachable — application cannot start", e);
        }
    }

    private boolean isChatModel(String id) {
        String lower = id.toLowerCase();
        if (EXCLUDED_SUBSTRINGS.stream().anyMatch(lower::contains)) return false;
        OptionalInt size = extractModelSizeB(lower);
        return size.isPresent() && size.getAsInt() >= MIN_MODEL_SIZE_B;
    }

    // Returns the largest Nb value found in the model ID.
    // "8x7b" → 7 (per-token active params for MoE), "70b" → 70, "120b" → 120.
    private OptionalInt extractModelSizeB(String lower) {
        Matcher m = Pattern.compile("(\\d+)b").matcher(lower);
        int max = -1;
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v > max) max = v;
        }
        return max >= 0 ? OptionalInt.of(max) : OptionalInt.empty();
    }

    // --- HTTP calls ---

    private String syncCallWithBackoff(String modelId, String prompt, int tokenLimit) {
        long backoffMs = INITIAL_BACKOFF_MS;
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return syncCall(modelId, prompt, tokenLimit);
            } catch (RuntimeException e) {
                last = e;
                String msg = e.getMessage();
                if (msg != null && msg.contains("429") && attempt < MAX_RETRIES) {
                    // TPD (daily quota) exhaustion — retry-after is hours/minutes, skip immediately
                    if (msg.contains("tokens per day") || msg.contains("TPD")) {
                        log.warn("GroqPool: TPD daily quota exhausted for model={} — skipping ({})",
                                modelId, extractRetryAfterLabel(msg));
                        throw e;
                    }
                    // TPM (per-minute) — use Groq's retry-after when available
                    long waitMs = parseRetryAfterMs(msg).orElse(backoffMs);
                    log.warn("GroqPool: 429 model={} attempt={} retrying in {}ms", modelId, attempt + 1, waitMs);
                    sleep(waitMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                } else {
                    throw e;
                }
            }
        }
        throw last;
    }

    // Groq embeds retry-after as "4.82s" or "43m51.312s" or "1h23m45s"
    private OptionalLong parseRetryAfterMs(String message) {
        // "Xm Y.Zs" form
        Matcher mm = Pattern.compile("(\\d+)m(\\d+\\.?\\d*)s").matcher(message);
        if (mm.find()) {
            try {
                long ms = Long.parseLong(mm.group(1)) * 60_000
                        + (long)(Double.parseDouble(mm.group(2)) * 1000) + 500;
                return OptionalLong.of(ms);
            } catch (NumberFormatException ignored) {}
        }
        // "X.Zs" form
        Matcher ms = Pattern.compile("try again in (\\d+\\.?\\d*)s").matcher(message);
        if (ms.find()) {
            try {
                return OptionalLong.of((long)(Double.parseDouble(ms.group(1)) * 1000) + 500);
            } catch (NumberFormatException ignored) {}
        }
        return OptionalLong.empty();
    }

    private String extractRetryAfterLabel(String message) {
        Matcher m = Pattern.compile("try again in ([^.\"]+)").matcher(message);
        return m.find() ? m.group(1).trim() : "unknown wait";
    }

    private String syncCall(String modelId, String prompt, int tokenLimit) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(modelId, prompt, false, tokenLimit)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Groq API error " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readTree(resp.body())
                    .path("choices").get(0).path("message").path("content").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GroqPool sync call failed", e);
        }
    }

    private void streamCall(String modelId, String prompt, Consumer<String> tokenConsumer) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(modelId, prompt, true, maxTokens)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                    .forEach(line -> {
                        try {
                            JsonNode chunk = mapper.readTree(line.substring(6));
                            JsonNode content = chunk.path("choices").get(0)
                                    .path("delta").path("content");
                            if (!content.isMissingNode() && !content.isNull()) {
                                String token = content.asText();
                                if (!token.isEmpty()) tokenConsumer.accept(token);
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GroqPool stream call failed", e);
        }
    }

    private String buildPayload(String modelId, String prompt, boolean stream, int tokenLimit) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", stream);
            node.put("max_tokens", tokenLimit);
            node.put("temperature", 0.7);
            ArrayNode messages = node.putArray("messages");
            messages.addObject().put("role", "user").put("content", prompt);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("GroqPool: failed to build payload", e);
        }
    }

    private void acquireSlot() {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for GroqPool slot", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
