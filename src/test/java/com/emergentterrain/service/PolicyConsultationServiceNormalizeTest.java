package com.emergentterrain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20.11 fixup follow-on — defensive normalization of LLM-returned
 * {@code summary} / {@code outlook} fields. The fast-tier model occasionally
 * returns {@code "summary": {...}} instead of a plain string; the normalizer
 * stringifies the object form so Jackson can bind into PolicyResponse without
 * throwing "Cannot deserialize value of type `java.lang.String` from Object
 * value", which had been dropping the entire consultation result.
 */
class PolicyConsultationServiceNormalizeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PolicyConsultationService svc =
            new PolicyConsultationService(/*llmService*/ null, mapper,
                    /*hintAcceptanceRate*/ 0.6);

    @Test void leaves_pure_string_summary_alone() {
        String in = "{\"summary\":\"trust is rising\",\"outlook\":\"hopeful\","
                + "\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        assertEquals(in, out, "no shape change → input returned verbatim");
    }

    @Test void coerces_object_summary_to_compact_json_string() throws Exception {
        String in = "{\"summary\":{\"nested\":\"v\",\"k2\":3},\"outlook\":\"cautious\","
                + "\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("summary").isTextual(), "summary now a string");
        assertEquals("{\"nested\":\"v\",\"k2\":3}", root.path("summary").asText());
        // outlook was already a string → untouched.
        assertEquals("cautious", root.path("outlook").asText());
    }

    @Test void coerces_both_summary_and_outlook_when_both_object() throws Exception {
        String in = "{\"summary\":{\"a\":1},\"outlook\":{\"mood\":\"low\"},"
                + "\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("summary").isTextual());
        assertTrue(root.path("outlook").isTextual());
        assertEquals("{\"a\":1}", root.path("summary").asText());
        assertEquals("{\"mood\":\"low\"}", root.path("outlook").asText());
    }

    @Test void coerces_array_summary_to_compact_json_string() throws Exception {
        // Less common but Jackson treats arrays the same as objects — bind fails
        // unless we stringify. Covers the LLM returning a list of bullets.
        String in = "{\"summary\":[\"a\",\"b\"],\"outlook\":\"stressed\","
                + "\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("summary").isTextual());
        assertEquals("[\"a\",\"b\"]", root.path("summary").asText());
    }

    @Test void returns_input_unchanged_on_unparseable_json() {
        String in = "not json at all";
        String out = svc.normalizeStringFields(in);
        // Defensive: defer to caller's readValue to surface the real error.
        assertEquals(in, out);
    }

    @Test void leaves_null_summary_alone() throws Exception {
        // Jackson binds null → null field, doesn't throw. Don't unnecessarily
        // touch it.
        String in = "{\"summary\":null,\"outlook\":\"stressed\",\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("summary").isNull());
    }

    @Test void coerces_numeric_summary_to_string() throws Exception {
        // Jackson would also reject {"summary": 42} as String binding — coerce.
        String in = "{\"summary\":42,\"outlook\":\"stressed\",\"actions\":[]}";
        String out = svc.normalizeStringFields(in);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("summary").isTextual());
        assertEquals("42", root.path("summary").asText());
    }
}
