package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import com.emergentterrain.llm.LlmCallContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI-compatible provider contract: the application must preserve assistant tool calls and
 * tool results across continuation requests, so a local adapter may ask for more evidence after
 * inspecting the first result. This uses no model, credentials, or Spring context.
 */
class GenericOpenAICompatPoolToolLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> requestIds = new ArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void retainsFullTranscriptAcrossTwoToolRounds() {
        ProviderConfig config = ProviderConfig.openAiCompat(
                "local-ai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", List.of("local-ai"));
        GenericOpenAICompatPool pool = new GenericOpenAICompatPool(
                config, mapper, 1, Duration.ofSeconds(5), false);

        ToolCallResponse result = LlmCallContext.withPaperId("P-contract-1", () -> pool.callWithTools(
                "Use the supplied application tools for every arithmetic operation.",
                "First multiply 6 by 7, then add 100 to the observed result.",
                "[{\"type\":\"function\",\"function\":{\"name\":\"multiply\",\"parameters\":{\"type\":\"object\"}}},"
                        + "{\"type\":\"function\",\"function\":{\"name\":\"add\",\"parameters\":{\"type\":\"object\"}}}]",
                request -> execute(request)));

        assertThat(serverFailure.get()).isNull();
        assertThat(result.synthesis()).isEqualTo("FINAL=142");
        assertThat(result.trace()).hasSize(2);
        assertThat(result.trace()).extracting(invocation -> invocation.functionName())
                .containsExactly("multiply", "add");
        assertThat(result.trace()).extracting(invocation -> invocation.round())
                .containsExactly(0, 1);
        assertThat(requests).hasSize(3);
        assertThat(requestIds).containsOnly("paper-P-contract-1");

        JsonNode continuation = requests.get(1).path("messages");
        assertThat(continuation.toString()).contains("provider-call-1", "multiply", "42");
        JsonNode finalTurn = requests.get(2).path("messages");
        assertThat(finalTurn.toString()).contains("provider-call-2", "add", "142");
    }

    private String execute(String request) {
        try {
            String name = mapper.readTree(request).path("name").asText();
            return switch (name) {
                case "multiply" -> "42";
                case "add" -> "142";
                default -> "unexpected tool";
            };
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            requestIds.add(exchange.getRequestHeaders().getFirst("X-EmergentTerrain-Request-Id"));
            int turn = requests.size();
            JsonNode response = switch (turn) {
                case 1 -> completion("tool_calls", "provider-call-1", "multiply", "{\"a\":6,\"b\":7}");
                case 2 -> completion("tool_calls", "provider-call-2", "add", "{\"a\":42,\"b\":100}");
                case 3 -> finalCompletion("FINAL=142");
                default -> throw new IllegalStateException("unexpected request turn " + turn);
            };
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
            byte[] body = "{\"error\":\"contract server failed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private JsonNode completion(String finishReason, String id, String name, String arguments) {
        var response = mapper.createObjectNode();
        var choice = response.putArray("choices").addObject();
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        var call = message.putArray("tool_calls").addObject();
        call.put("id", id);
        call.put("type", "function");
        call.putObject("function").put("name", name).put("arguments", arguments);
        choice.put("finish_reason", finishReason);
        response.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
        return response;
    }

    private JsonNode finalCompletion(String content) {
        var response = mapper.createObjectNode();
        var choice = response.putArray("choices").addObject();
        choice.putObject("message").put("role", "assistant").put("content", content);
        choice.put("finish_reason", "stop");
        response.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
        return response;
    }
}
