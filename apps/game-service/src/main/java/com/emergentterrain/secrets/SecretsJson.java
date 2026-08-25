package com.emergentterrain.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a vault command's JSON stdout into {@code ENV_VAR_NAME -> value}.
 *
 * <p>Accepts two shapes:
 * <ul>
 *   <li>an <b>object</b> {@code {"GROQ_API_KEY": "gsk_...", ...}} — the simple form;</li>
 *   <li>an <b>array</b> {@code [{"key":"GROQ_API_KEY","value":"gsk_..."}, ...]} —
 *       the shape emitted by a command-line secret source.</li>
 * </ul>
 */
final class SecretsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecretsJson() {}

    static Map<String, String> parse(String json) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root = MAPPER.readTree(json);
        if (root.isArray()) {
            for (JsonNode n : root) {
                JsonNode k = n.get("key");
                JsonNode v = n.get("value");
                if (k != null && !k.isNull() && !k.asText().isBlank()) {
                    out.put(k.asText(), v == null || v.isNull() ? "" : v.asText());
                }
            }
        } else if (root.isObject()) {
            root.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        } else {
            throw new IllegalArgumentException(
                    "expected a JSON object or array of {key,value}, got " + root.getNodeType());
        }
        return out;
    }
}
