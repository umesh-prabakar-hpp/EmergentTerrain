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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Groq-backed LlmPool. Queries the Groq /models endpoint at startup, runs a
 * short benchmark against each text-capable model, and selects the highest-scoring
 * one. Uses the OpenAI-compatible Groq API via Java's HttpClient — no extra deps.
 *
 * Active when app.ai.groq.enabled=true; marked @Primary so it wins over OllamaPool.
 * Set app.ai.groq.preferredModel to skip discovery and pin a specific model ID.
 */
@Component
@ConditionalOnProperty(name = "app.ai.groq.enabled", havingValue = "true")
public class GroqPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(GroqPool.class);

    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private static final Set<String> EXCLUDED_SUBSTRINGS = Set.of(
            "whisper", "prompt-guard", "orpheus", "safeguard");

    private static final String BENCH_PROMPT =
            "Write a 3-sentence research paper on bot movement near highway tiles. " +
            "Include Hypothesis, Findings (primaryValue=0.73, secondaryValue=0.61, botCount=142), " +
            "and Conclusion. Be concise and scientific.";

    private final String apiKey;
    private final int maxTokens;
    private final String activeModel;
    private final Semaphore slots;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GroqPool(
            @Value("${app.ai.groq.apiKey:}") String apiKey,
            @Value("${app.ai.groq.parallelism:3}") int parallelism,
            @Value("${app.ai.groq.maxTokens:600}") int maxTokens,
            @Value("${app.ai.groq.preferredModel:}") String preferredModel,
            ObjectMapper mapper) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GroqPool: app.ai.groq.apiKey is required when app.ai.groq.enabled=true");
        }
        this.apiKey    = apiKey;
        this.maxTokens = maxTokens;
        this.mapper    = mapper;
        this.slots     = new Semaphore(parallelism);
        this.http      = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        if (preferredModel != null && !preferredModel.isBlank()) {
            this.activeModel = preferredModel;
            log.info("GroqPool: using configured model={} parallelism={}", activeModel, parallelism);
        } else {
            this.activeModel = discoverAndSelectModel();
            log.info("GroqPool: selected model={} parallelism={}", activeModel, parallelism);
        }
    }

    // --- LlmPool ---

    @Override
    public String callSync(String prompt) {
        acquireSlot();
        try {
            return syncCall(activeModel, prompt);
        } finally {
            slots.release();
        }
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        acquireSlot();
        try {
            streamCall(activeModel, prompt, tokenConsumer);
        } finally {
            slots.release();
        }
    }

    @Override public String selectedModel() { return activeModel; }
    @Override public int backendCount()     { return 1; }
    @Override public int healthyCount()     { return 1; }

    // --- Model discovery and benchmarking ---

    private String discoverAndSelectModel() {
        List<String> candidates = fetchCandidateModels();
        log.info("GroqPool: benchmarking {} models: {}", candidates.size(), candidates);

        record Score(String id, int score, long ms) {}
        List<Score> scores = new ArrayList<>();

        for (String modelId : candidates) {
            long start = System.currentTimeMillis();
            try {
                String response = syncCall(modelId, BENCH_PROMPT);
                long elapsed = System.currentTimeMillis() - start;
                int score = scoreResponse(response);
                scores.add(new Score(modelId, score, elapsed));
                log.info("GroqPool benchmark model={} score={} ms={}", modelId, score, elapsed);
            } catch (Exception e) {
                log.warn("GroqPool benchmark skipped model={}: {}", modelId, e.getMessage());
            }
        }

        if (scores.isEmpty()) {
            throw new IllegalStateException("GroqPool: all model benchmarks failed");
        }

        return scores.stream()
                .filter(s -> s.score() >= 0)
                .max(Comparator.comparingInt(Score::score)
                        .thenComparingLong(s -> -s.ms()))
                .map(Score::id)
                .orElseGet(() -> candidates.get(0));
    }

    private List<String> fetchCandidateModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            List<String> models = new ArrayList<>();
            for (JsonNode node : root.path("data")) {
                String id = node.path("id").asText("");
                if (!id.isBlank() && isChatModel(id)) {
                    models.add(id);
                }
            }
            if (models.isEmpty()) throw new IllegalStateException("no chat models returned by Groq");
            return models;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GroqPool: failed to fetch models list", e);
        }
    }

    private boolean isChatModel(String id) {
        String lower = id.toLowerCase();
        return EXCLUDED_SUBSTRINGS.stream().noneMatch(lower::contains);
    }

    private int scoreResponse(String response) {
        if (response == null || response.isBlank()) return -100;
        if (response.contains("<think>")) return -1;  // raw chain-of-thought leaking into output
        String lower = response.toLowerCase();
        int score = 0;
        if (lower.contains("hypothesis")) score += 30;
        if (lower.contains("finding"))    score += 30;
        if (lower.contains("conclusion")) score += 30;
        if (response.length() >= 100)     score += 10;
        return score;
    }

    // --- HTTP calls ---

    private String syncCall(String modelId, String prompt) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(modelId, prompt, false)))
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
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(modelId, prompt, true)))
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

    private String buildPayload(String modelId, String prompt, boolean stream) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("model", modelId);
            node.put("stream", stream);
            node.put("max_tokens", maxTokens);
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
}
