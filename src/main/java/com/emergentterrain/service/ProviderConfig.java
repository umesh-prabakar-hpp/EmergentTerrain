package com.emergentterrain.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * @param extraHeaders      Phase 20.11 §migration — additional HTTP
 *                          headers to attach to every chat-completions
 *                          request from this pool. OpenRouter requires
 *                          {@code HTTP-Referer} + {@code X-Title} for
 *                          its free-tier routing analytics; this map
 *                          carries them. Null or empty = no extra
 *                          headers (the default). Authorization +
 *                          Content-Type are always set by the pool and
 *                          must not be duplicated here.
 * @param excludedBackends  Phase 20.11 §migration — response-side
 *                          backend blacklist. Mirrors OpenRouter's
 *                          existing semantics: when a successful
 *                          response carries
 *                          {@code body.provider} in this set, the
 *                          model is blocked for 5 minutes to force re-
 *                          routing; when a 429 carries
 *                          {@code error.metadata.provider_name} in
 *                          this set, the block window is extended to 5
 *                          minutes (covers backends with global free-
 *                          tier caps like Venice). Empty set (default)
 *                          = no exclusions.
 * @param permanent404Markers Phase 20.11 §migration — NVIDIA parity.
 *                          When a 404 response body contains any
 *                          substring in this set, the model is
 *                          permanently disabled (block expiry set to
 *                          {@link Long#MAX_VALUE}). Mirrors NvidiaPool's
 *                          paid-tier {@code "Not found for account"}
 *                          detection. Empty set (default) = no
 *                          permanent disables.
 * @param longBlockBodyMarkers Phase 20.11 §migration — NVIDIA parity.
 *                          When a 429 response body contains any
 *                          substring in this set, the block window is
 *                          bumped to at least {@link #longBlockMs}.
 *                          Mirrors NvidiaPool's monthly-credits
 *                          classification (credits don't reset until
 *                          the next billing cycle; a 60s default would
 *                          re-attack the API ~525k times that month).
 * @param longBlockMs       Phase 20.11 §migration — minimum block
 *                          window in milliseconds when a body marker
 *                          matches. {@code 0} (default) = feature off.
 *                          Typical NVIDIA value: 86_400_000 (24h).
 * @param fastTier          Phase 20.11 §migration — Groq parity.
 *                          When {@code true}, the pool publishes its
 *                          {@link #models} list via
 *                          {@link com.emergentterrain.service.LlmPool#fastTierModelIds()}
 *                          so {@code FastModelRegistry} picks them up
 *                          for the fast-audit role. Typical pairing:
 *                          {@code fast-tier: true} + {@code supports-tools:
 *                          false} so {@code PrimaryModelRegistry}'s
 *                          {@code supportsTools} filter excludes the
 *                          same models from the primary chain. Default
 *                          {@code false} = primary-only pool.
 * @param maxOutputTokens   Phase 20.11 fixup — value sent as the
 *                          {@link #maxTokensField} on every chat-
 *                          completions request. This is the OUTPUT cap
 *                          (completion tokens), not the context window.
 *                          The previous incarnation passed
 *                          {@link #contextWindow} (32000 typical) as
 *                          {@code max_tokens}, which exceeded per-model
 *                          caps on several fast models (allam-2-7b
 *                          caps at 4096, llama-4-scout at 8192) and
 *                          tripped 400 invalid_request on every call.
 *                          Default {@code 4096} — safe for every modern
 *                          OpenAI-compat model. Bump per-provider when
 *                          longer outputs are needed (legacy GroqPool
 *                          used 2000 for primary narration).
 * @param supportsJsonSchema Lever 1 — true when the provider honors
 *                          OpenAI's {@code response_format:
 *                          {type:"json_schema", strict:true}}. When a
 *                          caller sets a schema via
 *                          {@link com.emergentterrain.llm.LlmCallContext#withJsonSchema}
 *                          AND this is true, the pool emits the schema so
 *                          the provider enforces the output shape; otherwise
 *                          it falls back to the {@code json_object} branch
 *                          (today's behaviour). Default {@code false} —
 *                          opt-in per provider so a provider that 400s on an
 *                          unknown {@code response_format.type} is never sent
 *                          one.
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
        String role,
        Map<String, String> extraHeaders,
        Set<String> excludedBackends,
        Set<String> permanent404Markers,
        Set<String> longBlockBodyMarkers,
        long longBlockMs,
        boolean fastTier,
        int maxOutputTokens,
        boolean supportsJsonSchema) {

    public enum RetryAfterSource { HEADER, BODY }

    /** Defaults convenience for the common case (OpenAI-compat, sticky tools, header retry-after, no role, no extra headers, no exclusions, no NVIDIA-style parity markers, primary-tier, conservative 4096 output cap, json_schema off). */
    public static ProviderConfig openAiCompat(String providerKey, String baseUrl, String apiKey, List<String> models) {
        return new ProviderConfig(
                providerKey, baseUrl, apiKey, models, null,
                true, 32_000, "max_tokens", RetryAfterSource.HEADER, "", Map.of(),
                Set.of(), Set.of(), Set.of(), 0L, false, 4096, false);
    }
}
