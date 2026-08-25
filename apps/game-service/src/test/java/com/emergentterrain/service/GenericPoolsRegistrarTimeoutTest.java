package com.emergentterrain.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the per-provider request-timeout resolution
 * ({@link GenericPoolsRegistrar#resolveRequestTimeoutSeconds}). The peer-review
 * panel runs reviewers on groq/openrouter; capping those lets a slow reviewer
 * fail fast instead of parking a draft for ~7 min until the stuck-draft reaper
 * reclaims it (2026-05-25: 1 paper of ~7). Uses MockEnvironment, no Mockito.
 */
class GenericPoolsRegistrarTimeoutTest {

    @Test
    void defaultsTo420WhenNothingConfigured() {
        MockEnvironment env = new MockEnvironment();
        assertThat(GenericPoolsRegistrar.resolveRequestTimeoutSeconds(env, "groq")).isEqualTo(420);
    }

    @Test
    void globalDefaultIsHonored() {
        // Regression guard: the registrar used to hard-code 420 and ignore this key.
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.ai.http.requestTimeoutSeconds", "300");
        assertThat(GenericPoolsRegistrar.resolveRequestTimeoutSeconds(env, "anything")).isEqualTo(300);
    }

    @Test
    void perProviderOverrideWinsOverGlobal_othersInheritGlobal() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.ai.http.requestTimeoutSeconds", "420")
                .withProperty("app.ai.http.provider-timeout-seconds.groq", "120")
                .withProperty("app.ai.http.provider-timeout-seconds.openrouter", "120");

        // Panel-reviewer providers are capped tight.
        assertThat(GenericPoolsRegistrar.resolveRequestTimeoutSeconds(env, "groq")).isEqualTo(120);
        assertThat(GenericPoolsRegistrar.resolveRequestTimeoutSeconds(env, "openrouter")).isEqualTo(120);
        // Any other un-overridden provider inherits the global default.
        assertThat(GenericPoolsRegistrar.resolveRequestTimeoutSeconds(env, "nvidia")).isEqualTo(420);
    }
}
