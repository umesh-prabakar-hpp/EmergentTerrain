package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9 U2 — tests for the aggregated 70B+ registry. Each test wires a
 * small set of FakePool beans and inspects the registry's view + dispatch
 * behaviour.
 */
class PrimaryModelRegistryTest {

    @Test
    void registryAggregatesEveryModelFromEverySupportsToolsPool() {
        FakePool groq   = pool("groq",   true, List.of("llama-3.3-70b", "gpt-oss-120b"));
        FakePool nvidia = pool("nvidia", true, List.of("meta/llama-3.3-70b", "nvidia/nemotron-3"));
        FakePool ollama = pool("ollama", false, List.of("gemma3:4b"));  // no tools

        PrimaryModelRegistry registry = build(groq, nvidia, ollama);

        // Ollama is excluded — supportsTools=false.
        assertThat(registry.totalModelCount()).isEqualTo(4);
        assertThat(registry.allModels()).extracting(ModelHandle::reviewerId)
                .containsExactly(
                        "groq/llama-3.3-70b",
                        "groq/gpt-oss-120b",
                        "nvidia/meta/llama-3.3-70b",
                        "nvidia/nvidia/nemotron-3");
    }

    @Test
    void availableModelsFiltersBlockedHandlesPreservingOrder() {
        FakePool groq   = pool("groq",   true, List.of("a", "b"));
        FakePool nvidia = pool("nvidia", true, List.of("c", "d"));

        // Block groq/a and nvidia/c.
        groq.blocked.add("a");
        nvidia.blocked.add("c");

        PrimaryModelRegistry registry = build(groq, nvidia);
        assertThat(registry.availableModels()).extracting(ModelHandle::reviewerId)
                .containsExactly("groq/b", "nvidia/d");
    }

    @Test
    void callSyncWalksAvailableModelsAndReturnsFirstSuccess() {
        FakePool groq   = pool("groq",   true, List.of("a"));
        FakePool nvidia = pool("nvidia", true, List.of("c"));
        groq.responses.put("a", "groq-a-response");
        nvidia.responses.put("c", "nvidia-c-response");

        PrimaryModelRegistry registry = build(groq, nvidia);
        assertThat(registry.callSync("hello")).isEqualTo("groq-a-response");
        // Only the first handle was attempted.
        assertThat(groq.callCounts.get("a").get()).isEqualTo(1);
        assertThat(nvidia.callCounts).doesNotContainKey("c");
    }

    @Test
    void callSyncFallsThroughToNextHandleOnException() {
        FakePool groq   = pool("groq",   true, List.of("a"));
        FakePool nvidia = pool("nvidia", true, List.of("c"));
        groq.throws_.put("a", new RuntimeException("429 quota"));
        nvidia.responses.put("c", "nvidia-c-recovered");

        PrimaryModelRegistry registry = build(groq, nvidia);
        assertThat(registry.callSync("hello")).isEqualTo("nvidia-c-recovered");
    }

    @Test
    void callSyncThrowsWhenEveryHandleExhausted() {
        FakePool groq   = pool("groq",   true, List.of("a"));
        FakePool nvidia = pool("nvidia", true, List.of("c"));
        groq.throws_.put("a", new RuntimeException("groq down"));
        nvidia.throws_.put("c", new RuntimeException("nvidia down"));

        PrimaryModelRegistry registry = build(groq, nvidia);
        assertThatThrownBy(() -> registry.callSync("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nvidia down");  // last exception preserved
    }

    @Test
    void callSyncThrowsCleanErrorWhenNoHandlesAvailable() {
        FakePool groq = pool("groq", true, List.of("a"));
        groq.blocked.add("a");

        PrimaryModelRegistry registry = build(groq);
        assertThatThrownBy(() -> registry.callSync("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no available models")
                .hasMessageContaining("1 handles blocked");
    }

    @Test
    void callWithToolsDispatchesPerModel() {
        FakePool groq = pool("groq", true, List.of("a", "b"));
        groq.toolResponses.put("a", ToolCallResponse.ofSynthesis("from-a"));

        PrimaryModelRegistry registry = build(groq);
        ToolCallResponse out = registry.callWithTools("sys", "user", "[]", args -> "tool-result");
        assertThat(out.synthesis()).isEqualTo("from-a");
        // Second model wasn't touched.
        assertThat(groq.toolCallCounts).doesNotContainKey("b");
    }

    @Test
    void callStreamDispatchesAndCollectsTokens() {
        FakePool groq = pool("groq", true, List.of("a"));
        groq.streamTokens.put("a", List.of("tok1 ", "tok2 ", "tok3"));

        PrimaryModelRegistry registry = build(groq);
        StringBuilder sink = new StringBuilder();
        registry.callStream("hello", sink::append);
        assertThat(sink.toString()).isEqualTo("tok1 tok2 tok3");
    }

    @Test
    void emptyRegistryThrowsImmediately() {
        // No pools at all — every call surface throws "no available models".
        PrimaryModelRegistry registry = build();
        assertThat(registry.totalModelCount()).isEqualTo(0);
        assertThatThrownBy(() -> registry.callSync("x"))
                .hasMessageContaining("no available models");
    }

    @Test
    void poolsWithoutToolsAreExcludedEvenIfTheyDeclareModels() {
        FakePool groq   = pool("groq",   true, List.of("a"));
        FakePool ollama = pool("ollama", false, List.of("gemma3:4b", "qwen2:7b"));

        PrimaryModelRegistry registry = build(groq, ollama);
        // Only the groq handle survives — ollama is fast-tier.
        assertThat(registry.allModels()).extracting(ModelHandle::reviewerId)
                .containsExactly("groq/a");
    }

    // ---- Helpers ----

    private static FakePool pool(String key, boolean supportsTools, List<String> modelIds) {
        FakePool p = new FakePool(key, supportsTools);
        p.modelIds.addAll(modelIds);
        return p;
    }

    private static PrimaryModelRegistry build(FakePool... pools) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
        for (FakePool p : pools) {
            RootBeanDefinition def = new RootBeanDefinition(FakePool.class);
            def.setInstanceSupplier(() -> p);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            bf.registerBeanDefinition(p.providerKey + "Pool", def);
        }
        ctx.refresh();
        return new PrimaryModelRegistry(ctx);
    }

    static class FakePool implements LlmPool {
        final String providerKey;
        final boolean supportsTools;
        final List<String> modelIds = new ArrayList<>();
        final Set<String> blocked = new java.util.HashSet<>();
        final Map<String, String> responses = new HashMap<>();
        final Map<String, RuntimeException> throws_ = new HashMap<>();
        final Map<String, ToolCallResponse> toolResponses = new HashMap<>();
        final Map<String, List<String>> streamTokens = new HashMap<>();
        final Map<String, AtomicInteger> callCounts = new HashMap<>();
        final Map<String, AtomicInteger> toolCallCounts = new HashMap<>();

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
            // Default surface — not used by the registry's per-model walk.
            throw new UnsupportedOperationException("test fake — use callSyncOnModel");
        }

        @Override public void callStream(String prompt, Consumer<String> sink) {
            throw new UnsupportedOperationException("test fake — use callStreamOnModel");
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
            List<String> tokens = streamTokens.getOrDefault(modelId, List.of());
            for (String t : tokens) sink.accept(t);
        }

        @Override public ToolCallResponse callWithToolsOnModel(
                String modelId, String sys, String user, String toolsJson,
                Function<String, String> exec) {
            toolCallCounts.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();
            RuntimeException ex = throws_.get(modelId);
            if (ex != null) throw ex;
            return toolResponses.getOrDefault(modelId, ToolCallResponse.ofSynthesis(""));
        }
    }
}
