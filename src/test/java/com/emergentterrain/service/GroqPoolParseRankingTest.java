package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises GroqPool.parseRanking() with every response format a model might produce,
 * so we can diagnose why openai/gpt-oss-120b consistently returns ranked 0/2.
 */
class GroqPoolParseRankingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CANDIDATES = List.of(
            "openai/gpt-oss-120b", "llama-3.3-70b-versatile");

    // --- matchCandidate ---

    @Test
    void matchCandidate_exactMatch() {
        var valid = Set.copyOf(CANDIDATES);
        assertEquals("llama-3.3-70b-versatile",
                GroqPool.matchCandidate("llama-3.3-70b-versatile", valid));
    }

    @Test
    void matchCandidate_suffixMatch() {
        var valid = Set.copyOf(CANDIDATES);
        // Model drops the "openai/" namespace prefix
        assertEquals("openai/gpt-oss-120b",
                GroqPool.matchCandidate("gpt-oss-120b", valid));
    }

    @Test
    void matchCandidate_noMatch() {
        var valid = Set.copyOf(CANDIDATES);
        assertNull(GroqPool.matchCandidate("some-unknown-model", valid));
    }

    // --- parseRanking: well-formed inputs ---

    @Test
    void parseRanking_properJsonArray() {
        String response = "[\"openai/gpt-oss-120b\", \"llama-3.3-70b-versatile\"]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(List.of("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), result);
    }

    @Test
    void parseRanking_jsonArrayShortNames() {
        // Model drops namespace prefix inside the JSON array
        String response = "[\"gpt-oss-120b\", \"llama-3.3-70b-versatile\"]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(List.of("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), result,
                "suffix matching should resolve gpt-oss-120b → openai/gpt-oss-120b");
    }

    @Test
    void parseRanking_jsonArrayInMarkdownBlock() {
        String response = "```json\n[\"openai/gpt-oss-120b\", \"llama-3.3-70b-versatile\"]\n```";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertFalse(result.isEmpty(), "should parse array inside markdown code block");
        assertEquals(2, result.size());
    }

    @Test
    void parseRanking_jsonArrayWithPreamble() {
        String response = "Based on scientific and mathematical reasoning capabilities:\n" +
                "[\"openai/gpt-oss-120b\", \"llama-3.3-70b-versatile\"]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(List.of("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), result);
    }

    // --- parseRanking: formats that require fallback ---

    @Test
    void parseRanking_unquotedArray() {
        // Invalid JSON — no quotes around names
        String response = "[openai/gpt-oss-120b, llama-3.3-70b-versatile]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertFalse(result.isEmpty(), "fallback text scan should recover from unquoted array");
        assertEquals(2, result.size());
    }

    @Test
    void parseRanking_numberedList() {
        String response = "1. openai/gpt-oss-120b\n2. llama-3.3-70b-versatile";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertFalse(result.isEmpty(), "fallback text scan should handle numbered list");
        assertEquals(2, result.size());
        assertEquals("openai/gpt-oss-120b", result.get(0));
    }

    @Test
    void parseRanking_numberedListShortNames() {
        // Model uses short names in numbered list
        String response = "1. gpt-oss-120b\n2. llama-3.3-70b-versatile";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertFalse(result.isEmpty(), "fallback should suffix-match short names in numbered list");
        assertEquals(2, result.size());
        assertEquals("openai/gpt-oss-120b", result.get(0));
    }

    @Test
    void parseRanking_proseWithModelNames() {
        String response = "I would rank openai/gpt-oss-120b first for its superior reasoning, " +
                "followed by llama-3.3-70b-versatile.";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(2, result.size());
        // First mention should be first in result
        assertEquals("openai/gpt-oss-120b", result.get(0));
    }

    @Test
    void parseRanking_thinkBlockStripped() {
        String response = "<think>Let me reason about this...</think>\n" +
                "[\"llama-3.3-70b-versatile\", \"openai/gpt-oss-120b\"]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b"), result);
    }

    @Test
    void parseRanking_reasoningFieldContent() {
        // openai/gpt-oss-120b exhausts its token budget on internal chain-of-thought and
        // emits empty content. The caller falls back to the "reasoning" field text, which
        // mentions both model names but no JSON array. Text scan should still find them.
        String reasoningText =
                "We need to rank the two models: openai/gpt-oss-120b, llama-3.3-70b-versatile. " +
                "openai/gpt-oss-120b is a 120B parameter model, likely stronger for scientific " +
                "reasoning. llama-3.3-70b-versatile is a 70B model.";
        var result = GroqPool.parseRanking(MAPPER, reasoningText, CANDIDATES);
        assertEquals(2, result.size(), "text scan should find both model names in reasoning text");
        // openai/gpt-oss-120b appears first in the text
        assertEquals("openai/gpt-oss-120b", result.get(0));
    }

    // --- parseRanking: degenerate cases ---

    @Test
    void parseRanking_nullResponse() {
        assertTrue(GroqPool.parseRanking(MAPPER, null, CANDIDATES).isEmpty());
    }

    @Test
    void parseRanking_blankResponse() {
        assertTrue(GroqPool.parseRanking(MAPPER, "   ", CANDIDATES).isEmpty());
    }

    @Test
    void parseRanking_noModelNamesAtAll() {
        String response = "I cannot determine a ranking without more context.";
        assertTrue(GroqPool.parseRanking(MAPPER, response, CANDIDATES).isEmpty(),
                "should return empty when response contains no known model names");
    }

    @Test
    void parseRanking_singleModel() {
        // Only one model mentioned — partial ranking is still returned
        String response = "[\"openai/gpt-oss-120b\"]";
        var result = GroqPool.parseRanking(MAPPER, response, CANDIDATES);
        assertEquals(1, result.size());
        assertEquals("openai/gpt-oss-120b", result.get(0));
    }
}
