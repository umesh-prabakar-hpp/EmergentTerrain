package com.emergentterrain.llm;

import java.time.Instant;

/**
 * Single LLM call's token spend. Provider-agnostic: any pool (Groq today,
 * Anthropic / OpenAI / future) reports through this same record.
 */
public record TokenUsage(
        String provider,
        String model,
        long promptTokens,
        long completionTokens,
        Instant instant) {

    public long total() {
        return promptTokens + completionTokens;
    }
}
