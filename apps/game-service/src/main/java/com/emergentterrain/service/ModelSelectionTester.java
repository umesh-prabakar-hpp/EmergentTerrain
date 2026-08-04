package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Phase 9 U5 — universal startup selection-test runner. Every pool calls this
 * to validate each candidate model before adding it to {@code activeModels}.
 *
 * <p>Three probes per model:
 * <ol>
 *   <li><b>Chat</b> — {@code "Reply with the single word OK."} Response must
 *       contain {@code OK} (case-insensitive). Tests basic responsiveness.</li>
 *   <li><b>JSON-mode</b> — {@code "Return JSON with name=Euler, value=2.71828,
 *       good=true."} Response must parse as JSON (with markdown stripping) and
 *       contain those three fields with values close to the requested ones.</li>
 *   <li><b>Tool-call</b> — {@code "Call get_value with key=pi."} The probe
 *       supplies a one-arg {@code get_value(key:string)} tool; the model's
 *       trace must include a call to {@code get_value} with {@code key=pi}.
 *       Skipped for pools that don't support tools.</li>
 * </ol>
 *
 * <p>No timeout drops — per operator direction (2026-05-15), slow models pass
 * if they eventually return a valid response. Latency is captured in the
 * returned {@link SelectionResult} for log visibility but never used as a
 * drop criterion.
 *
 * <p>The class is intentionally stateless and dependency-free: each pool
 * passes its own dispatch lambdas (so we don't acquire the pool's slot
 * semaphore twice or duplicate the round-robin logic).
 */
public final class ModelSelectionTester {

    private static final Logger log = LoggerFactory.getLogger(ModelSelectionTester.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String CHAT_PROMPT =
            "Reply with the single word OK.";
    static final String JSON_PROMPT =
            "Return ONLY a JSON object with three fields: name=\"Euler\", "
                    + "value=2.71828, good=true. No prose, no markdown, just the JSON.";
    static final String TOOL_PROMPT =
            "Call the get_value tool with key=\"pi\". Use the tool; do not respond in prose.";
    static final String GET_VALUE_TOOL_JSON = "["
            + "{\"type\":\"function\",\"function\":{"
            +   "\"name\":\"get_value\","
            +   "\"description\":\"Look up a value by key.\","
            +   "\"parameters\":{\"type\":\"object\","
            +     "\"properties\":{\"key\":{\"type\":\"string\"}},"
            +     "\"required\":[\"key\"]"
            +   "}"
            + "}}]";

    private ModelSelectionTester() {}

    public interface ToolDispatch {
        ToolCallResponse call(String systemPrompt, String userPrompt,
                              String toolsJson, Function<String, String> executor);
    }

    public record SelectionResult(boolean passed, String reason,
                                   long chatMs, long jsonMs, Long toolMs) {
        public static SelectionResult fail(String reason) {
            return new SelectionResult(false, reason, -1, -1, null);
        }
        public static SelectionResult pass(long chatMs, long jsonMs, Long toolMs) {
            return new SelectionResult(true, "passed", chatMs, jsonMs, toolMs);
        }
    }

    /**
     * Filter candidate models by their selection-test outcome. Models that
     * pass every probe are returned, in input order. Models that fail any
     * probe are dropped with a {@code WARN PoolName: selection-test model=X
     * FAILED reason=...} log line; passes get an {@code INFO} line with the
     * per-probe latency breakdown.
     *
     * @param providerKey       used in log messages — typically the pool's providerKey
     * @param candidates        models to probe, in preferred order
     * @param syncDispatch      {@code (modelId, prompt) → response} — pool's per-model sync entry
     * @param toolDispatch      {@code (modelId) → ToolDispatch} for tool-capable pools, null for fast tier
     */
    public static List<String> filterPassing(
            String providerKey,
            List<String> candidates,
            java.util.function.BiFunction<String, String, String> syncDispatch,
            java.util.function.Function<String, ToolDispatch> toolDispatch) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        // Phase 20.11 fixup — parallelize the per-model probes within this
        // pool. Each model's three probes still run sequentially (a tool
        // probe depends on the chat+JSON probes passing), but two models on
        // the same provider don't have to queue behind each other. Cuts
        // startup time on multi-model pools from N × slowest to max(model
        // latencies). The legacy implementation walked candidates serially
        // — fine for Groq's 2 primary models, painful for NVIDIA's 5 (the
        // 2026-05-20 08:01 startup spent 107s here serially; the slowest
        // single model was qwen3-next-80b at 28s, so parallel saves ~75%).
        //
        // Virtual threads: one per model, ephemeral, no pool to size. The
        // pool's own slot semaphore still throttles concurrent HTTP work
        // (the dispatch lambdas acquire/release inside syncDispatch /
        // toolDispatch), so the parallelism is bounded by the pool's
        // declared parallelism rather than going to N-wide on every call.
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<java.util.Map.Entry<String, SelectionResult>>> futures =
                    new ArrayList<>(candidates.size());
            for (String modelId : candidates) {
                futures.add(executor.submit(() -> java.util.Map.entry(
                        modelId,
                        test(providerKey, modelId,
                                p -> syncDispatch.apply(modelId, p),
                                toolDispatch == null ? null : toolDispatch.apply(modelId)))));
            }
            // Preserve input order in the passed list so downstream registry
            // routing stays deterministic (the registry walks handles in
            // resolution order on tie-break, and tests assert specific
            // orderings against the YAML model list).
            java.util.Map<String, SelectionResult> resultsByModel =
                    new java.util.LinkedHashMap<>();
            for (var f : futures) {
                try {
                    var entry = f.get();
                    resultsByModel.put(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            providerKey + ": selection-test thread failed", e);
                }
            }

            List<String> passed = new ArrayList<>();
            for (String modelId : candidates) {
                SelectionResult r = resultsByModel.get(modelId);
                if (r == null) continue;  // defensive; shouldn't happen
                if (r.passed()) {
                    passed.add(modelId);
                    if (r.toolMs() != null) {
                        log.info("{}: selection-test model={} PASSED chat={}ms json={}ms tools={}ms",
                                providerKey, modelId, r.chatMs(), r.jsonMs(), r.toolMs());
                    } else {
                        log.info("{}: selection-test model={} PASSED chat={}ms json={}ms",
                                providerKey, modelId, r.chatMs(), r.jsonMs());
                    }
                } else {
                    log.warn("{}: selection-test model={} FAILED reason={} — dropping from activeModels",
                            providerKey, modelId, r.reason());
                }
            }
            log.info("{}: selection-tests complete passed={}/{} models={}",
                    providerKey, passed.size(), candidates.size(), passed);
            return List.copyOf(passed);
        }
    }

    /**
     * Run the three (or two, for non-tool pools) probes against one model.
     * Visible for direct unit-testing; production callers go through
     * {@link #filterPassing}.
     */
    static SelectionResult test(String providerKey, String modelId,
                                 Function<String, String> sync,
                                 ToolDispatch tool) {
        // 1. Chat probe — bare responsiveness.
        long chatStart = System.currentTimeMillis();
        String chatResp;
        try { chatResp = sync.apply(CHAT_PROMPT); }
        catch (RuntimeException e) {
            if (is429(e)) return rateLimited(providerKey, modelId, "chat-probe", e);
            return SelectionResult.fail("chat-probe threw: " + e.getMessage());
        }
        long chatMs = System.currentTimeMillis() - chatStart;
        if (chatResp == null || !chatResp.toUpperCase().contains("OK")) {
            return SelectionResult.fail("chat-probe did not contain 'OK': "
                    + truncate(chatResp));
        }

        // 2. JSON-mode probe — structured-output capability.
        long jsonStart = System.currentTimeMillis();
        String jsonResp;
        try { jsonResp = sync.apply(JSON_PROMPT); }
        catch (RuntimeException e) {
            if (is429(e)) return rateLimited(providerKey, modelId, "json-probe", e);
            return SelectionResult.fail("json-probe threw: " + e.getMessage());
        }
        long jsonMs = System.currentTimeMillis() - jsonStart;
        String jsonFailure = checkJsonResponse(jsonResp);
        if (jsonFailure != null) return SelectionResult.fail("json-probe " + jsonFailure);

        // 3. Tool-call probe — only for tool-capable pools.
        if (tool == null) {
            return SelectionResult.pass(chatMs, jsonMs, null);
        }
        long toolStart = System.currentTimeMillis();
        ToolCallResponse toolResp;
        try {
            toolResp = tool.call("", TOOL_PROMPT, GET_VALUE_TOOL_JSON,
                    args -> "{\"key\":\"pi\",\"value\":\"3.14159\"}");
        } catch (RuntimeException e) {
            if (is429(e)) return rateLimited(providerKey, modelId, "tool-probe", e);
            return SelectionResult.fail("tool-probe threw: " + e.getMessage());
        }
        long toolMs = System.currentTimeMillis() - toolStart;
        String toolFailure = checkToolResponse(toolResp);
        if (toolFailure != null) return SelectionResult.fail("tool-probe " + toolFailure);

        return SelectionResult.pass(chatMs, jsonMs, toolMs);
    }

    /**
     * Phase 9 follow-on — 429 during a selection probe is transient. The model
     * is not broken; the quota window is exhausted by some other process or a
     * preceding test. Don't drop the model from {@code activeModels} — the
     * runtime block state will pick it back up when the window resets.
     */
    private static boolean is429(RuntimeException e) {
        String m = e.getMessage();
        return m != null && m.contains("429");
    }

    private static SelectionResult rateLimited(String providerKey, String modelId,
                                                 String probeName, RuntimeException e) {
        log.warn("{}: selection-test model={} {} hit 429 — keeping model "
                        + "(runtime block state will track recovery): {}",
                providerKey, modelId, probeName, truncate(e.getMessage()));
        // Treat as a (tentative) pass so the model stays in activeModels.
        return SelectionResult.pass(0, 0, null);
    }

    /**
     * Lenient JSON-response check. Extracts a JSON object from the response
     * (stripping markdown {@code ```json} fences if present), parses it, and
     * validates the three expected fields are present with reasonable values.
     * Returns null on success or a short failure reason on rejection.
     */
    static String checkJsonResponse(String raw) {
        if (raw == null || raw.isBlank()) return "returned blank response";
        String trimmed = extractJsonObject(raw);
        if (trimmed == null) return "no JSON object found in response: " + truncate(raw);
        JsonNode node;
        try { node = MAPPER.readTree(trimmed); }
        catch (Exception e) { return "JSON parse failed: " + truncate(trimmed); }
        if (!node.isObject()) return "response is not a JSON object";
        if (node.path("name").isMissingNode() || !node.path("name").asText("")
                .toLowerCase().contains("euler")) {
            return "missing or wrong 'name' (expected Euler)";
        }
        if (node.path("value").isMissingNode() || !node.path("value").isNumber()) {
            return "missing or non-numeric 'value'";
        }
        double v = node.path("value").asDouble();
        if (Math.abs(v - 2.71828) > 0.01) {
            return "wrong 'value' " + v + " (expected ~2.71828)";
        }
        if (!node.path("good").isBoolean() || !node.path("good").asBoolean()) {
            return "missing or false 'good'";
        }
        return null;
    }

    /**
     * Robust JSON-object extractor — handles bare JSON, markdown-fenced JSON,
     * and prose-with-embedded-JSON. Returns the substring from the first
     * {@code {} to the matching {@code }}, or null when no balanced object
     * is found.
     */
    static String extractJsonObject(String raw) {
        int open = raw.indexOf('{');
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(open, i + 1);
            }
        }
        return null;
    }

    /**
     * Validate the tool-call probe's response. Pass requires: trace non-empty,
     * first call's function name is {@code get_value}, and its arguments
     * parse to an object with {@code key=pi}.
     */
    static String checkToolResponse(ToolCallResponse resp) {
        if (resp == null) return "returned null response";
        if (resp.trace() == null || resp.trace().isEmpty()) {
            return "trace was empty — model did not invoke get_value";
        }
        var inv = resp.trace().get(0);
        if (!"get_value".equals(inv.functionName())) {
            return "first call was '" + inv.functionName() + "' not 'get_value'";
        }
        try {
            JsonNode args = MAPPER.readTree(inv.argsJson() == null ? "{}" : inv.argsJson());
            if (!"pi".equalsIgnoreCase(args.path("key").asText(""))) {
                return "get_value called with key=" + args.path("key").asText() + " (expected pi)";
            }
        } catch (Exception e) {
            return "argsJson parse failed: " + truncate(inv.argsJson());
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
