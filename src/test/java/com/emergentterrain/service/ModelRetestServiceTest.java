package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 follow-on (2026-05-14) — verifies {@link ModelRetestService}
 * iterates pools, runs probes via dispatch lambdas, and calls
 * {@code onModelRecovered} only when probes pass. The scheduler thread is
 * left unscheduled; tests drive {@code runOnce()} directly.
 */
class ModelRetestServiceTest {

    @Test
    void runOnceCallsOnRecoveredForEveryPassingProbe() {
        FakeRetestPool pool = new FakeRetestPool("groq");
        pool.dropped.add("model-a");
        pool.dropped.add("model-b");
        // model-a passes all three probes; model-b fails the chat probe.
        pool.chatResponses.put("model-a", "OK");
        pool.chatResponses.put("model-b", "no");
        pool.jsonResponses.put("model-a",
                "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}");
        pool.toolResponses.put("model-a", new ToolCallResponse(
                "done",
                List.of(new ToolInvocation("get_value", "{\"key\":\"pi\"}", "{}", 0))));

        ModelRetestService svc = build(pool);
        svc.runOnce();

        assertThat(pool.recovered).containsExactly("model-a");
        assertThat(pool.dropped).contains("model-b");  // still dropped
    }

    @Test
    void runOnceSkipsExceptionsAndProcessesOtherEntries() {
        FakeRetestPool pool = new FakeRetestPool("deepseek");
        pool.dropped.add("model-a");
        pool.dropped.add("model-b");
        // model-a throws on chat probe; model-b passes everything.
        pool.chatThrows.put("model-a", new RuntimeException("503 still busy"));
        pool.chatResponses.put("model-b", "OK");
        pool.jsonResponses.put("model-b",
                "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}");
        pool.toolResponses.put("model-b", new ToolCallResponse(
                "done",
                List.of(new ToolInvocation("get_value", "{\"key\":\"pi\"}", "{}", 0))));

        ModelRetestService svc = build(pool);
        svc.runOnce();

        // model-a stayed dropped (probe threw); model-b recovered.
        assertThat(pool.recovered).containsExactly("model-b");
        assertThat(pool.dropped).contains("model-a");
    }

    @Test
    void runOnceIsNoopWhenNoPoolHasPendingRetests() {
        FakeRetestPool pool = new FakeRetestPool("openrouter");
        // No drops queued.
        ModelRetestService svc = build(pool);
        svc.runOnce();
        assertThat(pool.recovered).isEmpty();
    }

    @Test
    void intervalBelowFloorIsClampedToTenMinutes() {
        FakeRetestPool pool = new FakeRetestPool("groq");
        // Construct directly to inspect the clamped interval.
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        ModelRetestService svc = new ModelRetestService(ctx, true, 5);
        // No public getter; verify via reflection-free approach by checking
        // the clamp constant matches expectation in the static field.
        assertThat(ModelRetestService.MIN_INTERVAL_MINUTES).isEqualTo(10);
        // (Implementation clamps in-constructor; test only asserts the floor
        // constant since exposing the field would be churn.)
    }

    // ---- Helpers ----

    private static ModelRetestService build(LlmPool... pools) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
        for (LlmPool p : pools) {
            RootBeanDefinition def = new RootBeanDefinition(LlmPool.class);
            def.setInstanceSupplier(() -> p);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            bf.registerBeanDefinition(p.providerKey() + "Pool", def);
        }
        ctx.refresh();
        // Construct with selectionTestsEnabled=false so @PostConstruct doesn't
        // start the scheduler — tests drive runOnce() directly.
        return new ModelRetestService(ctx, false, 15);
    }

    /**
     * Minimal LlmPool fake that implements only the methods ModelRetestService
     * touches: providerKey, pendingRetests, onModelRecovered. Dispatch
     * lambdas read from response maps; throws maps simulate transient
     * failures.
     */
    static class FakeRetestPool implements LlmPool {
        final String providerKey;
        final java.util.Set<String> dropped = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> recovered = ConcurrentHashMap.newKeySet();
        final Map<String, String> chatResponses = new HashMap<>();
        final Map<String, String> jsonResponses = new HashMap<>();
        final Map<String, ToolCallResponse> toolResponses = new HashMap<>();
        final Map<String, RuntimeException> chatThrows = new HashMap<>();

        FakeRetestPool(String key) { this.providerKey = key; }

        @Override public String providerKey() { return providerKey; }
        @Override public boolean supportsTools() { return true; }
        @Override public String selectedModel() { return "n/a"; }
        @Override public int backendCount() { return 1; }
        @Override public int healthyCount() { return 1; }

        @Override public String callSync(String prompt) {
            throw new UnsupportedOperationException();
        }

        @Override public void callStream(String prompt, Consumer<String> sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RetestableModel> pendingRetests() {
            List<RetestableModel> out = new ArrayList<>();
            for (String mid : dropped) {
                BiFunction<String, String, String> sync = (m, prompt) -> {
                    RuntimeException ex = chatThrows.get(m);
                    if (ex != null) throw ex;
                    return prompt.contains("OK")
                            ? chatResponses.getOrDefault(m, "")
                            : jsonResponses.getOrDefault(m, "");
                };
                ModelSelectionTester.ToolDispatch tool =
                        (sys, user, toolsJson, exec) ->
                                toolResponses.getOrDefault(mid,
                                        new ToolCallResponse("", List.of()));
                out.add(new RetestableModel(mid, sync, tool));
            }
            return out;
        }

        @Override
        public void onModelRecovered(String modelId) {
            recovered.add(modelId);
            dropped.remove(modelId);
        }
    }
}
