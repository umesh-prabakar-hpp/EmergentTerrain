package com.emergentterrain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test — makes a real Groq API call with the ranking prompt
 * and prints the raw response so we can see exactly what openai/gpt-oss-120b returns.
 *
 * Run with: mvn test -Dtest=GroqPoolRankingLiveTest
 */
class GroqPoolRankingLiveTest {

    private static final String API_KEY =
            System.getenv("GROQ_API_KEY") != null
            ? System.getenv("GROQ_API_KEY")
            : "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";

    private static final String MODEL = "openai/gpt-oss-120b";
    private static final int RANKING_MAX_TOKENS = 600;

    private static final List<String> CANDIDATES = List.of(
            "openai/gpt-oss-120b", "llama-3.3-70b-versatile");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Test
    void showRawRankingResponse() throws Exception {
        String prompt = buildRankingPrompt(CANDIDATES);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", MODEL);
        payload.put("stream", false);
        payload.put("max_tokens", RANKING_MAX_TOKENS);
        payload.put("temperature", 0.7);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("=== HTTP STATUS: " + resp.statusCode() + " ===");
        System.out.println("=== FULL API RESPONSE BODY ===");
        System.out.println(resp.body());
        System.out.println("=== END RESPONSE BODY ===");

        assertEquals(200, resp.statusCode(), "Groq API call failed: " + resp.body());

        JsonNode message = mapper.readTree(resp.body()).path("choices").get(0).path("message");
        String rawContent = message.path("content").asText("");
        if (rawContent.isBlank()) {
            rawContent = message.path("reasoning").asText("");
            System.out.println("=== content was empty, using reasoning field ===");
        }

        System.out.println("=== EXTRACTED CONTENT from " + MODEL + " ===");
        System.out.println("[" + rawContent + "]");
        System.out.println("=== END CONTENT (len=" + rawContent.length() + ") ===");

        List<String> parsed = GroqPool.parseRanking(mapper, rawContent, CANDIDATES);
        System.out.println("=== parseRanking result: " + parsed + " ===");

        assertFalse(parsed.isEmpty(),
                "parseRanking should extract at least one model name from response:\n" + rawContent);
    }

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
}
