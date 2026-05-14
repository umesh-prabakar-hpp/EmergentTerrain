package com.emergentterrain.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9 U6 — tests for the aggregated fast-tier registry. Mirror of
 * {@link PrimaryModelRegistryTest} but for non-tool pools.
 */
class FastModelRegistryTest {

    @Test
    void registryAggregatesNonToolPoolsAcrossModels() {
        FakePool ollama  = pool("ollama", false, List.of("gemma3:4b", "qwen2:7b"));
        FakePool groq    = pool("groq",   true,  List.of("llama-3.3-70b"));  // tool-capable; excluded
        FakePool tiny    = pool("tiny",   false, List.of("phi3"));

        FastModelRegistry registry = build(ollama, groq, tiny);

        // Tool-capable groq excluded; fast tier = ollama × 2 models + tiny × 1 model.
        assertThat(registry.totalModelCount()).isEqualTo(3);
        assertThat(registry.allModels()).extracting(ModelHandle::reviewerId)
                .containsExactly("ollama/gemma3:4b", "ollama/qwen2:7b", "tiny/phi3");
    }

    @Test
    void availableModelsFiltersBlocked() {
        FakePool ollama = pool("ollama", false, List.of("a", "b"));
        ollama.blocked.add("a");
        FastModelRegistry registry = build(ollama);
        assertThat(registry.availableModels()).extracting(ModelHandle::reviewerId)
                .containsExactly("ollama/b");
    }

    @Test
    void callSyncFallsThroughOn429() {
        FakePool ollama = pool("ollama", false, List.of("a", "b"));
        ollama.throws_.put("a", new RuntimeException("Ollama down for model a"));
        ollama.responses.put("b", "from-b");

        FastModelRegistry registry = build(ollama);
        assertThat(registry.callSync("hello")).isEqualTo("from-b");
    }

    @Test
    void callSyncThrowsCleanErrorWhenNoModelsAvailable() {
        FakePool ollama = pool("ollama", false, List.of("a"));
        ollama.blocked.add("a");
        FastModelRegistry registry = build(ollama);
        assertThatThrownBy(() -> registry.callSync("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no available models");
    }

    @Test
    void callWithToolsThrowsUnsupportedOperation() {
        FakePool ollama = pool("ollama", false, List.of("a"));
        FastModelRegistry registry = build(ollama);
        assertThatThrownBy(() ->
                registry.callWithTools("sys", "user", "[]", args -> "tool-result"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("primary-tier capability");
    }

    @Test
    void emptyRegistryThrowsOnCallSync() {
        FastModelRegistry registry = build();
        assertThat(registry.totalModelCount()).isEqualTo(0);
        assertThatThrownBy(() -> registry.callSync("x"))
                .hasMessageContaining("no available models");
    }

    @Test
    void callStreamCollectsTokens() {
        FakePool ollama = pool("ollama", false, List.of("a"));
        ollama.streamTokens.put("a", List.of("hello ", "world"));

        FastModelRegistry registry = build(ollama);
        StringBuilder sink = new StringBuilder();
        registry.callStream("ping", sink::append);
        assertThat(sink.toString()).isEqualTo("hello world");
    }

    // ---- Helpers ----

    private static FakePool pool(String key, boolean supportsTools, List<String> modelIds) {
        FakePool p = new FakePool(key, supportsTools);
        p.modelIds.addAll(modelIds);
        return p;
    }

    private static FastModelRegistry build(FakePool... pools) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
        for (FakePool p : pools) {
            RootBeanDefinition def = new RootBeanDefinition(FakePool.class);
            def.setInstanceSupplier(() -> p);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            bf.registerBeanDefinition(p.providerKey + "Pool", def);
        }
        ctx.refresh();
        return new FastModelRegistry(ctx);
    }

    static class FakePool implements LlmPool {
        final String providerKey;
        final boolean supportsTools;
        final List<String> modelIds = new java.util.ArrayList<>();
        final Set<String> blocked = new HashSet<>();
        final Map<String, String> responses = new HashMap<>();
        final Map<String, RuntimeException> throws_ = new HashMap<>();
        final Map<String, List<String>> streamTokens = new HashMap<>();
        final Map<String, AtomicInteger> callCounts = new HashMap<>();

        FakePool(String providerKey, boolean supportsTools) {
            this.providerKey = providerKey;
            this.supportsTools = supportsTools;
        }

        @Override public String providerKey()    { return providerKey; }
        @Override public boolean supportsTools() { return supportsTools; }
        @Override public List<String> modelIds() { return modelIds; }
        @Override public boolean isModelBlocked(String modelId) { return blocked.contains(modelId); }
        @Override public String selectedModel()  { return modelIds.isEmpty() ? "n/a" : modelIds.get(0); }
        @Override public int backendCount()      { return 1; }
        @Override public int healthyCount()      { return 1; }

        @Override public String callSync(String prompt) {
            throw new UnsupportedOperationException("use callSyncOnModel");
        }
        @Override public void callStream(String prompt, Consumer<String> sink) {
            throw new UnsupportedOperationException("use callStreamOnModel");
        }
        @Override public String callSyncOnModel(String modelId, String prompt) {
            callCounts.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();
            RuntimeException ex = throws_.get(modelId);
            if (ex != null) throw ex;
            return responses.getOrDefault(modelId, "");
        }
        @Override public void callStreamOnModel(String modelId, String prompt, Consumer<String> sink) {
            callCounts.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();
            RuntimeException ex = throws_.get(modelId);
            if (ex != null) throw ex;
            for (String t : streamTokens.getOrDefault(modelId, List.of())) sink.accept(t);
        }
    }
}
