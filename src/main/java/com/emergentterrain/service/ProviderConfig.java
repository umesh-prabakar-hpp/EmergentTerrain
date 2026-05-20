package com.emergentterrain.service;

import java.util.List;

/**
 * Phase 20.11 — declarative provider configuration for
 * {@link GenericOpenAICompatPool}. Bound from the {@code app.ai.providers:}
 * YAML section by {@code ProviderConfigLoader} (follow-up commit); also
 * constructible directly from tests.
 *
 * <h2>Quirk flags</h2>
 * Most OpenAI-compat providers accept the same payload shape, but a few
 * differ in subtle ways. Express these as flags rather than per-provider
 * subclasses so adding a new provider stays a config-only edit.
 *
 * <ul>
 *   <li>{@code maxTokensField} — name of the output-cap field. Defaults to
 *       {@code "max_tokens"}; Anthropic's OpenAI-compat endpoint uses
 *       {@code "max_completion_tokens"}.</li>
 *   <li>{@code retryAfterSource} — where this provider exposes the
 *       retry-after on a 429. {@code HEADER} reads the standard HTTP
 *       header; {@code BODY} parses the error JSON body (Groq's style).</li>
 * </ul>
 *
 * @param providerKey       short identifier used in logs / role-overrides
 *                          / reviewerIds (e.g. {@code "anthropic"}).
 * @param baseUrl           HTTPS base URL ending at {@code /v1} (no
 *                          trailing slash; the pool appends
 *                          {@code /chat/completions}).
 * @param apiKey            bearer token used in the {@code Authorization}
 *                          header. Resolved from env via the loader's
 *                          {@code api-key-env} key; passed pre-resolved here.
 * @param models            static list of model ids to expose. Empty list
 *                          = use {@link #modelsDiscoveryPath} to discover
 *                          at startup; both unset = pool initializes with
 *                          no models and warns (legacy diagnostic shape).
 * @param modelsDiscoveryPath GET path (relative to {@code baseUrl}) that
 *                          returns an OpenAI-style {@code {data: [{id: ...}]}}
 *                          list. Pool discovers and registers each id at
 *                          startup. Null when models are hardcoded.
 * @param supportsTools     true when the provider supports the OpenAI
 *                          {@code tools} / {@code tool_choice} fields. Pool's
 *                          tool-call path is enabled only when this is true.
 * @param contextWindow     advisory; informs the budget gate's per-pool cap.
 * @param maxTokensField    response output cap field name. Default
 *                          {@code "max_tokens"}.
 * @param retryAfterSource  {@link RetryAfterSource#HEADER} or
 *                          {@link RetryAfterSource#BODY}.
 * @param role              Phase 7 E1.6 / Phase 20.11 — declared pool
 *                          role consumed by
 *                          {@link LlmRouter#poolsByRole}. Empty string
 *                          (default) means "no special role" — pool joins
 *                          the regular primary chain. Common values:
 *                          {@code "fast-audit"} (Cerebras-style fast
 *                          single-shot calls), {@code "professor-tools"}
 *                          (the agentic tool-loop stage).
 */
public record ProviderConfig(
        String providerKey,
        String baseUrl,
        String apiKey,
        List<String> models,
        String modelsDiscoveryPath,
        boolean supportsTools,
        int contextWindow,
        String maxTokensField,
        RetryAfterSource retryAfterSource,
        String role) {

    public enum RetryAfterSource { HEADER, BODY }

    /** Defaults convenience for the common case (OpenAI-compat, sticky tools, header retry-after, no role). */
    public static ProviderConfig openAiCompat(String providerKey, String baseUrl, String apiKey, List<String> models) {
        return new ProviderConfig(
                providerKey, baseUrl, apiKey, models, null,
                true, 32_000, "max_tokens", RetryAfterSource.HEADER, "");
    }
}
