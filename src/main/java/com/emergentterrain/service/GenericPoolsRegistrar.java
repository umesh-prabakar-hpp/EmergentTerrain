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
            String apiKey = raw.apiKeyEnv() == null ? "" : environment.getProperty(raw.apiKeyEnv(), "");
            if (apiKey.isBlank()) {
                log.warn("GenericPoolsRegistrar({}): api-key-env={} resolves to empty string — pool will register but every call will 401. "
                        + "Set the env var or remove the provider from app.ai.providers.",
                        providerKey, raw.apiKeyEnv());
            }
            ProviderConfig cfg = raw.toProviderConfig(providerKey, apiKey);
            int parallelism = raw.parallelism() == null ? 3 : raw.parallelism();

            String beanName = "openAiCompatPool_" + providerKey;
            GenericBeanDefinition def = new GenericBeanDefinition();
            def.setBeanClass(GenericOpenAICompatPool.class);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            def.getConstructorArgumentValues().addIndexedArgumentValue(0, cfg);
            // ObjectMapper resolved by-type at instantiation; matches the
            // existing pool constructors' style.
            def.getConstructorArgumentValues().addIndexedArgumentValue(
                    1, new org.springframework.beans.factory.config.RuntimeBeanReference(
                            ObjectMapper.class.getName()));
            def.getConstructorArgumentValues().addIndexedArgumentValue(2, parallelism);
            def.getConstructorArgumentValues().addIndexedArgumentValue(
                    3, Duration.ofSeconds(420)); // matches app.ai.http.requestTimeoutSeconds default
            registry.registerBeanDefinition(beanName, def);

            log.info("GenericPoolsRegistrar({}): registered bean={} baseUrl={} models={} contextWindow={} supportsTools={}",
                    providerKey, beanName, cfg.baseUrl(), cfg.models(), cfg.contextWindow(), cfg.supportsTools());
        }
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
        private List<String> models;
        private String modelsDiscoveryPath;
        private Boolean supportsTools;
        private Integer contextWindow;
        private String maxTokensField;
        private ProviderConfig.RetryAfterSource retryAfterSource;
        private Integer parallelism;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String v) { this.apiKeyEnv = v; }
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

        // Record-style accessors for use from GenericPoolsRegistrar.
        public String apiKeyEnv() { return apiKeyEnv; }
        public Integer parallelism() { return parallelism; }

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
                    retryAfterSource == null ? ProviderConfig.RetryAfterSource.HEADER : retryAfterSource);
        }
    }
}
