package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PanelOutcome;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 F7.2 — fan-out + retry + drop-on-parse-fail tests. Each test wires a
 * small set of FakePools, runs the panel against a fixture NarrationResult that
 * carries a synthetic tool-call trace, and asserts on the aggregated outcome
 * + the FakePool call counts.
 */
class ReviewPanelServiceTest {

    @Test
    void runsPanelAndAggregatesEveryReviewerResponse() {
        FakePool groq       = panelistReturning("groq",       cleanReviewJson(0.8));
        FakePool nvidia     = panelistReturning("nvidia",     cleanReviewJson(0.7));
        FakePool openrouter = panelistReturning("openrouter", cleanReviewJson(0.9));
        FakePool cerebras   = panelistReturning("cerebras",   cleanReviewJson(0.85));

        ReviewPanelService svc = build("groq,nvidia,openrouter,cerebras",
                groq, nvidia, openrouter, cerebras);
        assertThat(svc.isActive()).isTrue();
        assertThat(svc.panelSize()).isEqualTo(4);

        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.reviewerCount()).isEqualTo(4);
        // Each pool was called exactly once.
        assertThat(groq.syncCallCount.get()).isEqualTo(1);
        assertThat(nvidia.syncCallCount.get()).isEqualTo(1);
        assertThat(openrouter.syncCallCount.get()).isEqualTo(1);
        assertThat(cerebras.syncCallCount.get()).isEqualTo(1);
        // Median of [0.7, 0.8, 0.85, 0.9] honesty = (0.8 + 0.85) / 2 = 0.825.
        assertThat(outcome.aggregateGrades().honesty()).isEqualTo(0.825);
    }

    @Test
    void reviewersAreCalledSequentiallyInConfiguredOrder() {
        // Each pool records the order in which it was invoked. Sequential mode
        // means pool[i] must finish before pool[i+1] starts.
        java.util.concurrent.atomic.AtomicInteger globalOrder = new java.util.concurrent.atomic.AtomicInteger();
        FakePool a = panelistReturningWithOrder("groq",   cleanReviewJson(0.8), globalOrder);
        FakePool b = panelistReturningWithOrder("nvidia", cleanReviewJson(0.7), globalOrder);
        FakePool c = panelistReturningWithOrder("openrouter", cleanReviewJson(0.9), globalOrder);

        ReviewPanelService svc = build("groq,nvidia,openrouter", a, b, c);
        svc.runPanel(draftFixture(), narrationWithTrace(1));

        // Sequential: a invoked first, b second, c third — strict monotonic order.
        assertThat(a.invocationOrder.get()).isEqualTo(0);
        assertThat(b.invocationOrder.get()).isEqualTo(1);
        assertThat(c.invocationOrder.get()).isEqualTo(2);
    }

    @Test
    void poolLevelModelFallthroughIsPreservedAcrossPanelInvocations() {
        // The pool itself owns "try N models on 429" — the panel just calls
        // pool.callSync once per reviewer slot. From the panel's perspective:
        //   - a pool that successfully recovers via internal fallthrough → reviewer counted
        //   - a pool that exhausts every model → exception, reviewer dropped
        // FakePool simulates the "all models exhausted" case via throwOnSync.
        FakePool groq    = panelistReturning("groq",    cleanReviewJson(0.8));
        FakePool nvidia  = new FakePool("nvidia", List.of());
        nvidia.throwOnSync = new RuntimeException("NVIDIA API error 429: all 5 models exhausted");

        ReviewPanelService svc = build("groq,nvidia", groq, nvidia);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));

        // groq's review survives, nvidia's reviewer slot drops cleanly.
        assertThat(outcome.reviewerCount()).isEqualTo(1);
        assertThat(outcome.reviews().get(0).reviewerId()).startsWith("groq/");
        // The exhausted pool was attempted exactly once at the panel level —
        // any retries within the pool happened in callSync and are not visible
        // here. Net: one panel-level call.
        assertThat(nvidia.syncCallCount.get()).isEqualTo(1);
    }

    @Test
    void emptyTraceShortCircuitsPanel() {
        FakePool groq = panelistReturning("groq", cleanReviewJson(0.8));
        ReviewPanelService svc = build("groq", groq);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(0));
        assertThat(outcome.isEmpty()).isTrue();
        assertThat(groq.syncCallCount.get()).isEqualTo(0);
    }

    @Test
    void parseFailureTriggersOneRetryThenDropsReviewer() {
        // First call returns garbage, second call returns valid JSON — reviewer survives.
        FakePool retryable = new FakePool("groq", List.of("not json", cleanReviewJson(0.7)));
        // First call returns garbage, second too — reviewer drops.
        FakePool always   = new FakePool("nvidia", List.of("still not json", "still not json"));
        ReviewPanelService svc = build("groq,nvidia", retryable, always);

        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.reviewerCount()).isEqualTo(1);
        // Retryable was called twice (one parse fail + one success).
        assertThat(retryable.syncCallCount.get()).isEqualTo(2);
        // Always-broken was called twice too then dropped.
        assertThat(always.syncCallCount.get()).isEqualTo(2);
        assertThat(outcome.reviews().get(0).reviewerId()).startsWith("groq/");
    }

    @Test
    void networkExceptionDropsReviewerWithoutRetry() {
        FakePool throwing = new FakePool("groq", List.of());
        throwing.throwOnSync = new RuntimeException("network down");
        FakePool fine = panelistReturning("nvidia", cleanReviewJson(0.7));

        ReviewPanelService svc = build("groq,nvidia", throwing, fine);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.reviewerCount()).isEqualTo(1);
        // Single attempt — network errors don't retry.
        assertThat(throwing.syncCallCount.get()).isEqualTo(1);
    }

    @Test
    void disabledPanelReturnsEmptyOutcome() {
        FakePool groq = panelistReturning("groq", cleanReviewJson(0.8));
        ReviewPanelService svc = build(/*enabled*/ false, "groq", groq);
        assertThat(svc.isActive()).isFalse();
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.isEmpty()).isTrue();
        assertThat(groq.syncCallCount.get()).isEqualTo(0);
    }

    @Test
    void u3a_panelSizeCapsTheNumberOfReviewers() {
        // Six healthy providers registered; panel.size=2 should pick the first 2.
        FakePool a = panelistReturning("a", cleanReviewJson(0.8));
        FakePool b = panelistReturning("b", cleanReviewJson(0.7));
        FakePool c = panelistReturning("c", cleanReviewJson(0.6));
        FakePool d = panelistReturning("d", cleanReviewJson(0.5));
        FakePool e = panelistReturning("e", cleanReviewJson(0.4));
        FakePool f = panelistReturning("f", cleanReviewJson(0.3));

        ReviewPanelService svc = build(true, "*", /*panelSize*/ 2, a, b, c, d, e, f);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.reviewerCount()).isEqualTo(2);
        // First two in registration order are a + b — pure availability picking.
        assertThat(a.syncCallCount.get()).isEqualTo(1);
        assertThat(b.syncCallCount.get()).isEqualTo(1);
        assertThat(c.syncCallCount.get()).isEqualTo(0);
    }

    @Test
    void u3a_panelSkipsHandlesBlockedAtTheRegistryLevel() {
        // Three providers — middle one is blocked in the registry view. The
        // panel skips it and reaches the third.
        FakePool a = panelistReturning("a", cleanReviewJson(0.8));
        FakePool b = panelistReturning("b", cleanReviewJson(0.7));
        b.isBlocked = true;
        FakePool c = panelistReturning("c", cleanReviewJson(0.6));

        ReviewPanelService svc = build(true, "*", /*panelSize*/ 4, a, b, c);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        // 2 reviewers (a + c); b skipped at availability filter.
        assertThat(outcome.reviewerCount()).isEqualTo(2);
        assertThat(b.syncCallCount.get()).isEqualTo(0);
    }

    @Test
    void phase23_6d_callsAreTaggedWithPanelReviewRole() {
        // Phase 23.6d follow-on — ReviewPanelService.callReviewer wraps the
        // reviewer LLM call in LlmCallContext.withRole("panel-review", ...).
        // Without the wrap, panel calls would land under role=unknown in
        // LlmCallMetricsReporter's by-role section.
        FakePool groq = panelistReturning("groq", cleanReviewJson(0.8));
        ReviewPanelService svc = build("groq", groq);

        // Sanity: outside any panel call, the role is null.
        assertThat(com.emergentterrain.llm.LlmCallContext.currentRole()).isNull();

        svc.runPanel(draftFixture(), narrationWithTrace(1));

        assertThat(groq.observedRole)
                .as("LlmCallContext.currentRole() at the moment the pool was invoked")
                .isEqualTo("panel-review");

        // The scope is properly cleared after the call returns — the
        // ThreadLocal must not leak to subsequent unrelated work.
        assertThat(com.emergentterrain.llm.LlmCallContext.currentRole()).isNull();
    }

    @Test
    void unknownProvidersInConfigAreSilentlySkipped() {
        // Configured providers: groq, missing — only groq has a registered pool.
        // Phase 9 U3a: panelSize is now the CONFIGURED target (cap), not the
        // resolved-pool count. What matters is that runPanel only returns the
        // groq reviewer; the "missing" entry in the filter just doesn't match
        // any registry handle.
        FakePool groq = panelistReturning("groq", cleanReviewJson(0.8));
        ReviewPanelService svc = build("groq,missing", groq);
        PanelOutcome outcome = svc.runPanel(draftFixture(), narrationWithTrace(1));
        assertThat(outcome.reviewerCount()).isEqualTo(1);
        assertThat(outcome.reviews().get(0).reviewerId()).startsWith("groq/");
    }

    // ---- Helpers ----

    private static String cleanReviewJson(double honesty) {
        return "{\"grades\":{\"honesty\":" + honesty
                + ",\"completeness\":0.7,\"rigor\":0.8,\"novelty\":0.5},"
                + "\"cited_calls\":[1],"
                + "\"omitted_relevant\":[],"
                + "\"contradictions\":[],"
                + "\"recommendations\":[]}";
    }

    private static FakePool panelistReturning(String key, String response) {
        return new FakePool(key, List.of(response));
    }

    private static FakePool panelistReturningWithOrder(String key, String response,
                                                         AtomicInteger sharedCounter) {
        FakePool p = new FakePool(key, List.of(response));
        p.sharedOrder = sharedCounter;
        return p;
    }

    private static ReviewPanelService build(String providersCsv, FakePool... pools) {
        return build(true, providersCsv, ReviewPanelService.DEFAULT_PANEL_SIZE, pools);
    }

    private static ReviewPanelService build(boolean enabled, String providersCsv, FakePool... pools) {
        return build(enabled, providersCsv, ReviewPanelService.DEFAULT_PANEL_SIZE, pools);
    }

    private static ReviewPanelService build(boolean enabled, String providersCsv, int panelSize,
                                              FakePool... pools) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
        for (FakePool p : pools) {
            RootBeanDefinition def = new RootBeanDefinition(FakePool.class);
            def.setInstanceSupplier(() -> p);
            def.setScope(BeanDefinition.SCOPE_SINGLETON);
            bf.registerBeanDefinition(p.providerKey + "Pool", def);
        }
        ctx.refresh();
        PrimaryModelRegistry primaryRegistry = new PrimaryModelRegistry(ctx);
        PromptTemplateRegistry promptRegistry = new PromptTemplateRegistry();
        promptRegistry.seedDefaults();
        ObjectMapper mapper = new ObjectMapper();
        return new ReviewPanelService(primaryRegistry, promptRegistry, mapper,
                enabled, providersCsv, panelSize);
    }

    private static PaperDraft draftFixture() {
        return new PaperDraft(
                UUID.randomUUID(), "bot-1",
                "PHYSICS", "RLC Damping Transition", "Test paper",
                "f-template", "h-template", "c-template", "stub-methodology",
                List.of(), 0.5, 0.5, 0.0,
                0L, Instant.now(), 0,
                "P-test-1", 0, 0.0, false,
                null, null, List.of(), null, null,
                ResultClassification.SUPPORTED, null);
    }

    private static NarrationResult narrationWithTrace(int callCount) {
        List<ToolInvocation> trace = new ArrayList<>();
        for (int i = 0; i < callCount; i++) {
            trace.add(new ToolInvocation("run_param_sweep",
                    "{\"steps\":10}", "{\"peak_q\":42.3}", i));
        }
        return new NarrationResult("hyp", "find", "concl", null, "ai-x", 0.5, List.of())
                .withToolCallTrace(trace);
    }

    /** Minimal LlmPool for panel tests. Returns scripted responses to callSync. */
    static class FakePool implements LlmPool {
        final String providerKey;
        final List<String> scriptedResponses;
        final AtomicInteger syncCallCount = new AtomicInteger();
        /** Set by panelistReturningWithOrder so we can pin sequential ordering. */
        AtomicInteger sharedOrder;
        /** Records the value of {@code sharedOrder} at the moment this pool was first invoked. */
        final AtomicInteger invocationOrder = new AtomicInteger(-1);
        RuntimeException throwOnSync;
        /** Phase 9 U3a — flips isModelBlocked so the registry's availability filter excludes this fake. */
        boolean isBlocked;

        FakePool(String providerKey, List<String> scriptedResponses) {
            this.providerKey = providerKey;
            this.scriptedResponses = scriptedResponses;
        }

        /**
         * Phase 23.6d follow-on — captures {@link com.emergentterrain.llm.LlmCallContext#currentRole()}
         * at the moment the pool was invoked. Used by the role-wiring test to
         * assert that {@code ReviewPanelService.callReviewer} wraps the call
         * in a {@code withRole("panel-review", ...)} scope. Left null on the
         * legacy panel tests so they're unaffected.
         */
        volatile String observedRole;

        @Override public String callSync(String prompt) {
            observedRole = com.emergentterrain.llm.LlmCallContext.currentRole();
            int idx = syncCallCount.getAndIncrement();
            if (idx == 0 && sharedOrder != null) {
                invocationOrder.set(sharedOrder.getAndIncrement());
            }
            if (throwOnSync != null) throw throwOnSync;
            if (idx >= scriptedResponses.size()) {
                return scriptedResponses.isEmpty()
                        ? ""
                        : scriptedResponses.get(scriptedResponses.size() - 1);
            }
            return scriptedResponses.get(idx);
        }
        @Override public void callStream(String prompt, Consumer<String> tc) {}
        @Override public String selectedModel() { return "model-" + providerKey; }
        @Override public int backendCount()      { return 1; }
        @Override public int healthyCount()      { return 1; }
        @Override public String providerKey()    { return providerKey; }
        // Phase 9 U3a — FakePool participates in the PrimaryModelRegistry so the
        // panel can find it. Single model id ("model-<provider>") forwards to the
        // existing callSync via the LlmPool default callSyncOnModel.
        @Override public boolean supportsTools() { return true; }
        @Override public boolean isModelBlocked(String modelId) { return isBlocked; }
    }
}
