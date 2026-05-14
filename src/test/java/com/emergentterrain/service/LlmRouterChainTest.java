package com.emergentterrain.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Phase 7 E1.5 multi-provider routing semantics:
 *   • primaryChain is ordered by app.ai.primaryProviderOrder
 *   • allBlocked() on one pool falls through to the next
 *   • RuntimeException on one pool falls through to the next
 *   • supportsProfessorTools() = any pool in chain supports tools
 *   • primaryFullyExhausted() = every pool in chain reports blocked
 *   • Fast pool resolution
 */
class LlmRouterChainTest {

    @Test
    void primaryChain_followsConfiguredOrder() {
        FakePool groq   = new FakePool("groq", true, false, "groq-response");
        FakePool nvidia = new FakePool("nvidia", true, false, "nvidia-response");
        FakePool openrouter = new FakePool("openrouter", true, false, "or-response");
        FakePool ollama = new FakePool("ollama", false, false, "ollama-response");

        LlmRouter router = buildRouter(
                "nvidia,groq,openrouter", "ollama",
                groq, nvidia, openrouter, ollama);

        assertThat(router.primaryProviderChain())
                .containsExactly("nvidia", "groq", "openrouter");
        assertThat(router.primaryModel()).isEqualTo("model-nvidia");
        assertThat(router.callPrimary("hi")).isEqualTo("nvidia-response");
        assertThat(nvidia.syncCallCount).isEqualTo(1);
        assertThat(groq.syncCallCount).isEqualTo(0);
    }

    @Test
    void primaryChain_skipsBlockedPoolAndCallsNext() {
        FakePool groq   = new FakePool("groq", true, true,  "groq-response");      // blocked
        FakePool nvidia = new FakePool("nvidia", true, false, "nvidia-response");
        FakePool ollama = new FakePool("ollama", false, false, "ollama-response");

        LlmRouter router = buildRouter(
                "groq,nvidia", "ollama",
                groq, nvidia, ollama);

        assertThat(router.callPrimary("hi")).isEqualTo("nvidia-response");
        assertThat(groq.syncCallCount).isEqualTo(0);
        assertThat(nvidia.syncCallCount).isEqualTo(1);
    }

    @Test
    void primaryChain_fallsThroughOnRuntimeException() {
        FakePool groq   = new FakePool("groq", true, false, null);  // throws
        groq.throwOnSync = new RuntimeException("groq-failed");
        FakePool nvidia = new FakePool("nvidia", true, false, "nvidia-response");
        FakePool ollama = new FakePool("ollama", false, false, "ollama-response");

        LlmRouter router = buildRouter(
                "groq,nvidia", "ollama",
                groq, nvidia, ollama);

        assertThat(router.callPrimary("hi")).isEqualTo("nvidia-response");
        assertThat(groq.syncCallCount).isEqualTo(1);
        assertThat(nvidia.syncCallCount).isEqualTo(1);
    }

    @Test
    void primaryChain_throwsLastExceptionWhenAllPoolsFail() {
        FakePool groq   = new FakePool("groq", true, false, null);
        groq.throwOnSync   = new RuntimeException("groq-failed");
        FakePool nvidia = new FakePool("nvidia", true, false, null);
        nvidia.throwOnSync = new RuntimeException("nvidia-failed");
        FakePool ollama = new FakePool("ollama", false, false, "ollama-response");

        LlmRouter router = buildRouter(
                "groq,nvidia", "ollama",
                groq, nvidia, ollama);

        assertThatThrownBy(() -> router.callPrimary("hi"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nvidia-failed");
    }

    @Test
    void primaryFullyExhausted_trueOnlyWhenEveryPoolIsBlocked() {
        FakePool groq   = new FakePool("groq", true, true,  "x");
        FakePool nvidia = new FakePool("nvidia", true, false, "x");
        FakePool ollama = new FakePool("ollama", false, false, "x");

        LlmRouter router = buildRouter(
                "groq,nvidia", "ollama",
                groq, nvidia, ollama);

        // groq blocked, nvidia not blocked → chain isn't fully exhausted
        assertThat(router.primaryFullyExhausted()).isFalse();

        nvidia.blocked = true;
        assertThat(router.primaryFullyExhausted()).isTrue();
    }

    @Test
    void supportsProfessorTools_trueIfAnyChainPoolSupportsTools() {
        FakePool ollama = new FakePool("ollama", false, false, "x");
        FakePool groq   = new FakePool("groq", true, false, "x");

        // Order: ollama (no tools), groq (tools) — chain has at least one tool-capable pool
        LlmRouter router = buildRouter("ollama,groq", "ollama", groq, ollama);
        assertThat(router.supportsProfessorTools()).isTrue();
    }

    @Test
    void callWithTools_skipsNonToolPoolsAndBlockedPools() {
        FakePool ollama = new FakePool("ollama", false, false, "x");
        FakePool groq   = new FakePool("groq", true, true,  "x"); // blocked
        FakePool nvidia = new FakePool("nvidia", true, false, "nvidia-tools-response");

        LlmRouter router = buildRouter(
                "ollama,groq,nvidia", "ollama",
                ollama, groq, nvidia);

        String out = router.callPrimaryWithTools("sys", "user", "[]", args -> "tool-result");
        assertThat(out).isEqualTo("nvidia-tools-response");
        assertThat(ollama.toolCallCount).isEqualTo(0);   // doesn't support tools
        assertThat(groq.toolCallCount).isEqualTo(0);     // blocked
        assertThat(nvidia.toolCallCount).isEqualTo(1);
    }

    @Test
    void fastPool_resolvesByProviderKey() {
        FakePool groq   = new FakePool("groq", true, false, "groq");
        FakePool ollama = new FakePool("ollama", false, false, "ollama");

        LlmRouter router = buildRouter("groq", "ollama", groq, ollama);
        assertThat(router.callFast("ping")).isEqualTo("ollama");
        assertThat(router.fastModel()).isEqualTo("model-ollama");
    }

    @Test
    void fastPool_fallsBackWhenConfiguredProviderMissing() {
        FakePool groq   = new FakePool("groq", true, false, "groq");

        // No ollama in registry; fast falls back to first non-primary or primary
        LlmRouter router = buildRouter("groq", "ollama", groq);
        // Only one pool registered — fast pool degrades to the primary
        assertThat(router.fastModel()).isEqualTo("model-groq");
    }

    @Test
    void chain_skipsUnknownProvidersInConfig() {
        FakePool groq   = new FakePool("groq", true, false, "x");
        FakePool ollama = new FakePool("ollama", false, false, "x");

        // "anthropic" doesn't exist as a bean — should be silently skipped
        LlmRouter router = buildRouter("anthropic,groq", "ollama", groq, ollama);
        assertThat(router.primaryProviderChain()).containsExactly("groq");
    }

    @Test
    void chain_fallsBackToAnyAvailablePoolWhenOrderListIsEmpty() {
        FakePool groq = new FakePool("groq", true, false, "x");
        // All configured keys missing → router uses the first registered pool
        LlmRouter router = buildRouter("nothing,nope", "ollama", groq);
        assertThat(router.primaryProviderChain()).containsExactly("groq");
    }

    // ── Phase 7 E1.6 — role-based routing ───────────────────────────────────

    @Test
    void callForRole_routesToTaggedPoolWhenAvailable() {
        FakePool ollama   = new FakePool("ollama", false, false, "ollama-response");
        FakePool cerebras = new FakePool("cerebras", true, false, "cerebras-fast-response")
                .withRole("fast-audit");

        LlmRouter router = buildRouter("groq", "ollama", ollama, cerebras);

        String out = router.callForRole("fast-audit", "audit this");
        assertThat(out).isEqualTo("cerebras-fast-response");
        assertThat(cerebras.syncCallCount).isEqualTo(1);
        assertThat(ollama.syncCallCount).isEqualTo(0);
    }

    @Test
    void callForRole_fallsBackToFastWhenRolePoolBlocked() {
        FakePool ollama   = new FakePool("ollama", false, false, "ollama-fallback");
        FakePool cerebras = new FakePool("cerebras", true, true,  "cerebras-blocked")
                .withRole("fast-audit");

        LlmRouter router = buildRouter("groq", "ollama", ollama, cerebras);

        String out = router.callForRole("fast-audit", "audit this");
        assertThat(out).isEqualTo("ollama-fallback");
        assertThat(cerebras.syncCallCount).isEqualTo(0);  // skipped (blocked)
        assertThat(ollama.syncCallCount).isEqualTo(1);
    }

    @Test
    void callForRole_fallsBackToFastWhenRolePoolThrows() {
        FakePool ollama   = new FakePool("ollama", false, false, "ollama-fallback");
        FakePool cerebras = new FakePool("cerebras", true, false, null).withRole("fast-audit");
        cerebras.throwOnSync = new RuntimeException("transient cerebras failure");

        LlmRouter router = buildRouter("groq", "ollama", ollama, cerebras);

        String out = router.callForRole("fast-audit", "audit this");
        assertThat(out).isEqualTo("ollama-fallback");
        assertThat(cerebras.syncCallCount).isEqualTo(1);   // tried, threw
        assertThat(ollama.syncCallCount).isEqualTo(1);     // fallback
    }

    @Test
    void callForRole_fallsBackToFastWhenNoPoolTaggedForRole() {
        FakePool ollama = new FakePool("ollama", false, false, "ollama-only");
        FakePool groq   = new FakePool("groq", true, false, "groq-only");

        LlmRouter router = buildRouter("groq", "ollama", groq, ollama);

        String out = router.callForRole("fast-audit", "audit this");
        assertThat(out).isEqualTo("ollama-only");
    }

    @Test
    void roleAvailable_reportsCorrectly() {
        FakePool ollama   = new FakePool("ollama", false, false, "x");
        FakePool cerebras = new FakePool("cerebras", true, false, "x").withRole("fast-audit");

        LlmRouter router = buildRouter("groq", "ollama", ollama, cerebras);

        assertThat(router.roleAvailable("fast-audit")).isTrue();
        assertThat(router.roleAvailable("nonexistent-role")).isFalse();

        cerebras.blocked = true;
        assertThat(router.roleAvailable("fast-audit")).isFalse();
    }

    // ── Phase 7 E3 — JSON / fast pool probes for the three-way routing ──────

    @Test
    void e3HasJsonPathPrimaryTracksPrimaryChainHealth() {
        FakePool groq   = new FakePool("groq", true, false, "x");
        FakePool nvidia = new FakePool("nvidia", true, false, "x");
        FakePool ollama = new FakePool("ollama", false, false, "x");

        LlmRouter router = buildRouter("groq,nvidia", "ollama", groq, nvidia, ollama);
        assertThat(router.hasJsonPathPrimary()).isTrue();

        groq.blocked = true;
        assertThat(router.hasJsonPathPrimary()).isTrue();   // nvidia still up

        nvidia.blocked = true;
        assertThat(router.hasJsonPathPrimary()).isFalse();  // chain exhausted
    }

    @Test
    void e3HasFastPoolReflectsFastPoolHealth() {
        FakePool groq   = new FakePool("groq", true, false, "x");
        FakePool ollama = new FakePool("ollama", false, false, "x");

        LlmRouter router = buildRouter("groq", "ollama", groq, ollama);
        assertThat(router.hasFastPool()).isTrue();

        ollama.blocked = true;
        assertThat(router.hasFastPool()).isFalse();
    }

    // ── Fake pool + router builder ──────────────────────────────────────────

    private static class FakePool implements LlmPool {
        final String key;
        final boolean tools;
        boolean blocked;
        final String syncResponse;
        String roleTag = "";
        RuntimeException throwOnSync;
        int syncCallCount = 0;
        int toolCallCount = 0;

        FakePool(String key, boolean tools, boolean blocked, String syncResponse) {
            this.key = key;
            this.tools = tools;
            this.blocked = blocked;
            this.syncResponse = syncResponse;
        }

        FakePool withRole(String role) { this.roleTag = role; return this; }

        @Override public String callSync(String prompt) {
            syncCallCount++;
            if (throwOnSync != null) throw throwOnSync;
            return syncResponse;
        }
        @Override public void callStream(String prompt, Consumer<String> c) {
            syncCallCount++;
            if (throwOnSync != null) throw throwOnSync;
            c.accept(syncResponse);
        }
        @Override public String selectedModel()  { return "model-" + key; }
        @Override public int backendCount()      { return 1; }
        @Override public int healthyCount()      { return 1; }
        @Override public String providerKey()    { return key; }
        @Override public boolean supportsTools() { return tools; }
        @Override public boolean allBlocked()    { return blocked; }
        @Override public Instant earliestRecovery() { return Instant.now(); }
        @Override public String role()           { return roleTag; }
        @Override public String callWithTools(String sys, String user, String tools,
                                              Function<String, String> exec) {
            toolCallCount++;
            if (throwOnSync != null) throw throwOnSync;
            return syncResponse;
        }
    }

    private LlmRouter buildRouter(String primaryOrder, String fastProvider, FakePool... pools) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
        for (FakePool p : pools) {
            RootBeanDefinition def = new RootBeanDefinition(FakePool.class);
            def.setInstanceSupplier(() -> p);
            bf.registerBeanDefinition(p.providerKey() + "Pool", def);
        }
        ctx.refresh();
        return new LlmRouter(ctx, primaryOrder, fastProvider);
    }
}
