package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 20.11 step 2 — registers one {@link GenericOpenAICompatPool} Spring
 * bean per entry in the {@code app.ai.providers:} YAML section, BEFORE
 * any regular beans are instantiated. The pools then participate in
 * {@code ctx.getBeansOfType(LlmPool.class)} that {@link PrimaryModelRegistry}
 * / {@link FastModelRegistry} / {@link LlmRouter} use to discover pools —
 * so adding a new provider becomes a config-only edit, no code change.
 *
 * <h2>Why a BeanDefinitionRegistryPostProcessor</h2>
 * Regular {@code @Component} beans run their {@code @PostConstruct} AFTER
 * the registries have already been constructed and snapshotted their pool
 * list. Registering a singleton at that point is invisible to them.
 * {@link BeanDefinitionRegistryPostProcessor} runs at bean-definition time
 * (before any instantiation), so the bean definitions it adds participate
 * in the normal Spring lifecycle — autowired, lifecycle hooks, the whole
 * deal.
 *
 * <h2>YAML schema</h2>
 * <pre>
 * app.ai.providers:
 *   anthropic:
 *     base-url: https://api.anthropic.com/v1
 *     api-key-env: ANTHROPIC_API_KEY
 *     models: [claude-opus-4-7, claude-sonnet-4-6]
 *     supports-tools: true
 *     context-window: 200000
 *     max-tokens-field: max_completion_tokens
 *     retry-after-source: HEADER
 *     parallelism: 3
 *   deepseek:
 *     base-url: https://api.deepseek.com/v1
 *     api-key-env: DEEPSEEK_API_KEY
 *     models: [deepseek-chat]
 * </pre>
 *
 * <h2>Step 2 limitations</h2>
 * <ul>
 *   <li>Pool's {@code supportsTools()} is hardcoded to {@code false}
 *       regardless of the YAML flag — the tool-call path is a stub.
 *       The yaml flag is captured in {@link ProviderConfig} for the
 *       follow-up commit to honour.</li>
 *   <li>Model discovery ({@code modelsDiscoveryPath}) not yet implemented —
 *       only the static {@code models} list is honoured.</li>
 * </ul>
 */
@Component
public class GenericPoolsRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(GenericPoolsRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        Map<String, RawProviderConfig> providers = Binder.get(environment)
                .bind("app.ai.providers",
                        Bindable.mapOf(String.class, RawProviderConfig.class))
                .orElse(new LinkedHashMap<>());
        if (providers.isEmpty()) {
            log.info("GenericPoolsRegistrar: no app.ai.providers entries — generic pool registration is dormant.");
            return;
        }
        for (Map.Entry<String, RawProviderConfig> e : providers.entrySet()) {
            String providerKey = e.getKey();
            RawProviderConfig raw = e.getValue();
            // 2026-05-29 — per-provider kill switch. enabled:false dormants the
            // provider without deleting its config block; the bean is never
            // registered, so it stays out of every registry/router.
            if (!raw.enabled()) {
                log.info("GenericPoolsRegistrar({}): enabled=false — skipping registration (config block preserved for re-enable).",
                        providerKey);
                continue;
            }
            // Phase 20.11 §migration — env-var lookup wins; otherwise fall
            // back to the literal `api-key` value (which itself may be a
            // Spring placeholder like ${ENV:default} that Binder already
            // resolved). Mirrors the legacy `apiKey: ${ENV:hardcoded}`
            // pattern so a flip preserves existing key-resolution behavior.
            String apiKey = raw.apiKeyEnv() == null ? "" : environment.getProperty(raw.apiKeyEnv(), "");
            if (apiKey.isBlank() && raw.getApiKey() != null && !raw.getApiKey().isBlank()) {
                apiKey = raw.getApiKey();
            }
            if (apiKey.isBlank()) {
                log.warn("GenericPoolsRegistrar({}): api-key-env={} and api-key both empty — pool will register but every call will 401. "
                        + "Set the env var or fill api-key in app.ai.providers.{}.",
                        providerKey, raw.apiKeyEnv(), providerKey);
                // On an empty key, surface whether the external-secrets loader injected
                // its property source and which source (if any) defines this key —
                // distinguishes "loader didn't inject" from "value present but
                // empty/shadowed". Only logs on this failure path; kept as a permanent
                // aid (it pinned the 2026-05-24 EnvironmentPostProcessor mis-registration).
                if (environment instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
                    boolean extSecretsPresent = ce.getPropertySources().contains("externalSecrets");
                    String definedBy = "(none)";
                    StringBuilder srcNames = new StringBuilder();
                    for (org.springframework.core.env.PropertySource<?> ps : ce.getPropertySources()) {
                        if (srcNames.length() > 0) srcNames.append(", ");
                        srcNames.append(ps.getName());
                        if (raw.apiKeyEnv() != null && definedBy.equals("(none)") && ps.containsProperty(raw.apiKeyEnv())) {
                            definedBy = ps.getName();
                        }
                    }
                    log.warn("GenericPoolsRegistrar({}) key-diag: externalSecrets-source-present={}, key '{}' defined-by={}, all-sources=[{}]",
                            providerKey, extSecretsPresent, raw.apiKeyEnv(), definedBy, srcNames);
                }
            }
            ProviderConfig cfg = raw.toProviderConfig(providerKey, apiKey);
            int parallelism = raw.parallelism() == null ? 3 : raw.parallelism();

            String beanName = "openAiCompatPool_" + providerKey;
            GenericBeanDefinition def = new GenericBeanDefinition();
            def.setBeanClass(GenericOpenAICompatPool.class);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            def.getConstructorArgumentValues().addIndexedArgumentValue(0, cfg);
            // ObjectMapper resolved by-type at instantiation; matches the
            // existing pool constructors' style. Must use the Class-typed
            // RuntimeBeanReference overload — the String-typed one looks
            // up by bean *name*, and Spring Boot registers the auto-
            // configured ObjectMapper as "jacksonObjectMapper", not its FQCN.
            def.getConstructorArgumentValues().addIndexedArgumentValue(
                    1, new org.springframework.beans.factory.config.RuntimeBeanReference(
                            ObjectMapper.class));
            def.getConstructorArgumentValues().addIndexedArgumentValue(2, parallelism);
            // Per-request HTTP timeout: the global default
            // (app.ai.http.requestTimeoutSeconds — previously hard-coded here and
            // therefore unread) with an optional per-provider override
            // (app.ai.http.provider-timeout-seconds.<provider>). The peer-review
            // panel runs its reviewers sequentially on groq/openrouter; capping
            // those tight lets a slow/congested reviewer call fail fast so the
            // panel degrades-and-proceeds, instead of parking a draft in the
            // post-narration pipeline until the stuck-draft reaper reclaims it
            // (2026-05-25: drafts stalled ~7 min entering the panel, reaped at the
            // 10-min TTL → 1 paper of ~7).
            int requestTimeoutSec = resolveRequestTimeoutSeconds(environment, providerKey);
            def.getConstructorArgumentValues().addIndexedArgumentValue(
                    3, Duration.ofSeconds(requestTimeoutSec));
            // Phase 9 U5 parity — Selection tests on by default; same key the
            // legacy pools + OllamaPool read so a single global toggle still
            // works across every pool implementation.
            boolean selectionTestsEnabled = environment.getProperty(
                    "app.ai.selection-tests.enabled", Boolean.class, true);
            def.getConstructorArgumentValues().addIndexedArgumentValue(
                    4, selectionTestsEnabled);
            registry.registerBeanDefinition(beanName, def);

            log.info("GenericPoolsRegistrar({}): registered bean={} baseUrl={} models={} contextWindow={} supportsTools={} requestTimeoutSec={}",
                    providerKey, beanName, cfg.baseUrl(), cfg.models(), cfg.contextWindow(), cfg.supportsTools(), requestTimeoutSec);
        }
    }

    /**
     * Per-request HTTP timeout (seconds) for a provider pool: the global default
     * {@code app.ai.http.requestTimeoutSeconds} (420 if unset), overridden per
     * provider by {@code app.ai.http.provider-timeout-seconds.<provider>}. The
     * override caps the panel-reviewer providers (groq/openrouter) so a slow
     * reviewer fails fast.
     * Package-private + static for unit testing against a MockEnvironment.
     */
    static int resolveRequestTimeoutSeconds(org.springframework.core.env.Environment env, String providerKey) {
        int global = env.getProperty("app.ai.http.requestTimeoutSeconds", Integer.class, 420);
        return env.getProperty(
                "app.ai.http.provider-timeout-seconds." + providerKey, Integer.class, global);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No-op; bean definitions registered in postProcessBeanDefinitionRegistry.
    }

    /**
     * Raw YAML shape; matched against {@link ProviderConfig} via {@link #toProviderConfig}.
     * Bound by Spring Boot's {@link Binder} — kebab-case keys map to camelCase fields.
     * All fields are nullable; defaults applied in {@link #toProviderConfig}.
     */
    public static class RawProviderConfig {
        private String baseUrl;
        private String apiKeyEnv;
        /**
         * Phase 20.11 §migration — literal API-key fallback. Supports
         * Spring placeholder syntax ({@code ${ENV:default}}) so an
         * operator can mirror the legacy {@code app.ai.&lt;pool&gt;.apiKey:
         * ${ENV:hardcoded}} pattern verbatim. {@code apiKeyEnv} is
         * consulted first; if its env var is unset, this value is
         * used as the bearer token.
         */
        private String apiKey;
        private List<String> models;
        private String modelsDiscoveryPath;
        private Boolean supportsTools;
        private Integer contextWindow;
        private String maxTokensField;
        private ProviderConfig.RetryAfterSource retryAfterSource;
        private Integer parallelism;
        /** Phase 20.11 §migration — declared pool role; empty = primary chain. */
        private String role;
        /** Phase 20.11 §migration — extra HTTP headers (e.g. OpenRouter HTTP-Referer/X-Title). */
        private Map<String, String> extraHeaders;
        /** Phase 20.11 §migration — response-side backend blacklist (e.g. OpenRouter Venice). */
        private List<String> excludedBackends;
        /** Phase 20.11 §migration — body substrings on a 404 that mark the model as permanently disabled (NVIDIA paid-tier). */
        private List<String> permanent404Markers;
        /** Phase 20.11 §migration — body substrings on a 429 that bump the block window to longBlockMs (NVIDIA monthly-credits). */
        private List<String> longBlockBodyMarkers;
        /** Phase 20.11 §migration — minimum block window in ms when a longBlockBodyMarkers match fires (24h = 86_400_000 for NVIDIA credits). */
        private Long longBlockMs;
        /** Phase 20.11 §migration — Groq fast-tier flag (publishes models via fastTierModelIds()). */
        private Boolean fastTier;
        /** Phase 20.11 fixup — output-token cap sent as max_tokens. See ProviderConfig.maxOutputTokens. */
        private Integer maxOutputTokens;
        /** Lever 1 — provider honors response_format json_schema (strict). See ProviderConfig.supportsJsonSchema. */
        private Boolean supportsJsonSchema;
        /**
         * 2026-05-29 — per-provider kill switch. Null/true → the pool registers
         * normally; explicit false → GenericPoolsRegistrar skips it so the bean
         * never enters PrimaryModelRegistry / FastModelRegistry / LlmRouter.
         * Lets an operator dormant a provider in config without deleting its
         * block (host/model/budget config is preserved for a one-line re-enable).
         */
        private Boolean enabled;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String v) { this.apiKeyEnv = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public List<String> getModels() { return models; }
        public void setModels(List<String> v) { this.models = v; }
        public String getModelsDiscoveryPath() { return modelsDiscoveryPath; }
        public void setModelsDiscoveryPath(String v) { this.modelsDiscoveryPath = v; }
        public Boolean getSupportsTools() { return supportsTools; }
        public void setSupportsTools(Boolean v) { this.supportsTools = v; }
        public Integer getContextWindow() { return contextWindow; }
        public void setContextWindow(Integer v) { this.contextWindow = v; }
        public String getMaxTokensField() { return maxTokensField; }
        public void setMaxTokensField(String v) { this.maxTokensField = v; }
        public ProviderConfig.RetryAfterSource getRetryAfterSource() { return retryAfterSource; }
        public void setRetryAfterSource(ProviderConfig.RetryAfterSource v) { this.retryAfterSource = v; }
        public Integer getParallelism() { return parallelism; }
        public void setParallelism(Integer v) { this.parallelism = v; }
        public String getRole() { return role; }
        public void setRole(String v) { this.role = v; }
        public Map<String, String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(Map<String, String> v) { this.extraHeaders = v; }
        public List<String> getExcludedBackends() { return excludedBackends; }
        public void setExcludedBackends(List<String> v) { this.excludedBackends = v; }
        public List<String> getPermanent404Markers() { return permanent404Markers; }
        public void setPermanent404Markers(List<String> v) { this.permanent404Markers = v; }
        public List<String> getLongBlockBodyMarkers() { return longBlockBodyMarkers; }
        public void setLongBlockBodyMarkers(List<String> v) { this.longBlockBodyMarkers = v; }
        public Long getLongBlockMs() { return longBlockMs; }
        public void setLongBlockMs(Long v) { this.longBlockMs = v; }
        public Boolean getFastTier() { return fastTier; }
        public void setFastTier(Boolean v) { this.fastTier = v; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer v) { this.maxOutputTokens = v; }
        public Boolean getSupportsJsonSchema() { return supportsJsonSchema; }
        public void setSupportsJsonSchema(Boolean v) { this.supportsJsonSchema = v; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean v) { this.enabled = v; }

        // Record-style accessors for use from GenericPoolsRegistrar.
        public String apiKeyEnv() { return apiKeyEnv; }
        public Integer parallelism() { return parallelism; }
        /** Null/true → register; explicit false → skip (per-provider kill switch). */
        public boolean enabled() { return enabled == null || enabled; }

        ProviderConfig toProviderConfig(String providerKey, String apiKey) {
            return new ProviderConfig(
                    providerKey,
                    baseUrl == null ? "" : baseUrl,
                    apiKey,
                    models == null ? List.of() : models,
                    modelsDiscoveryPath,
                    supportsTools != null && supportsTools,
                    contextWindow == null ? 32_000 : contextWindow,
                    maxTokensField == null ? "max_tokens" : maxTokensField,
                    retryAfterSource == null ? ProviderConfig.RetryAfterSource.HEADER : retryAfterSource,
                    role == null ? "" : role.trim(),
                    extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders),
                    excludedBackends == null || excludedBackends.isEmpty()
                            ? java.util.Set.of()
                            : java.util.Set.copyOf(excludedBackends),
                    permanent404Markers == null || permanent404Markers.isEmpty()
                            ? java.util.Set.of()
                            : java.util.Set.copyOf(permanent404Markers),
                    longBlockBodyMarkers == null || longBlockBodyMarkers.isEmpty()
                            ? java.util.Set.of()
                            : java.util.Set.copyOf(longBlockBodyMarkers),
                    longBlockMs == null ? 0L : longBlockMs,
                    fastTier != null && fastTier,
                    maxOutputTokens == null ? 4096 : maxOutputTokens,
                    supportsJsonSchema != null && supportsJsonSchema);
        }
    }
}
