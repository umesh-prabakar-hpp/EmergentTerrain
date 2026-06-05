package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins {@link RunBudgetTracker}'s enforcement: a per-provider cap disables just
 * that provider (router routes around it), while a global ceiling — or every
 * used provider being exhausted — stops the whole run. Also checks the $-cost
 * maths and that free / disabled paths never enforce.
 *
 * <p>{@link RunBudgetTracker#terminate(int)} is overridden so the
 * "stop the app" path records an exit code instead of killing the test JVM.
 */
class RunBudgetTrackerTest {

    /** A tracker whose JVM-termination is captured rather than executed. */
    private static final class TestableTracker extends RunBudgetTracker {
        final AtomicInteger terminatedWith = new AtomicInteger(Integer.MIN_VALUE);
        TestableTracker(BudgetProperties props, LlmPricing pricing) {
            super(props, pricing, null);
        }
        @Override protected void terminate(int exitCode) {
            terminatedWith.set(exitCode);
        }
    }

    private static BudgetProperties.Caps caps(double cost, long calls, long in, long out) {
        BudgetProperties.Caps c = new BudgetProperties.Caps();
        c.setMaxCostUsd(cost);
        c.setMaxCalls(calls);
        c.setMaxInputTokens(in);
        c.setMaxOutputTokens(out);
        return c;
    }

    /** Pricing with groq llama-3.3-70b at $0.59 in / $0.79 out per Mtok. */
    private static LlmPricing pricingWithGroqLlama() {
        LlmPricing p = new LlmPricing();
        LlmPricing.ModelPrice mp = new LlmPricing.ModelPrice();
        mp.setInUsdPerMtok(0.59);
        mp.setOutUsdPerMtok(0.79);
        LlmPricing.ProviderPrices gp = new LlmPricing.ProviderPrices();
        gp.getModels().put("llama-3.3-70b-versatile", mp);
        p.getProviders().put("groq", gp);
        return p;
    }

    // ---- 2026-06-05: graceful drain ----------------------------------------

    @Test
    void drainThreshold_stopsNewResearch_butKeepsServingCalls() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCalls(10);
        props.getGlobal().setDrainStartCalls(5);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 4; i++) t.recordCall("groq");
        assertThat(t.isDraining()).isFalse();
        assertThat(t.allowNewResearch()).isTrue();

        t.recordCall("groq");   // 5th call crosses drain-start
        assertThat(t.isDraining()).isTrue();
        assertThat(t.allowNewResearch()).isFalse();
        // Draining is NOT a stop: in-flight work keeps calling.
        assertThatCode(() -> t.checkProviderAvailable("groq")).doesNotThrowAnyException();
        assertThat(t.isStopRequested()).isFalse();
        assertThat(t.terminatedWith).hasValue(Integer.MIN_VALUE);
    }

    @Test
    void notifyQuiescent_whileDraining_stopsCleanly() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCalls(10);
        props.getGlobal().setDrainStartCalls(2);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        t.notifyQuiescent("too early");          // not draining yet → no-op
        assertThat(t.isStopRequested()).isFalse();

        t.recordCall("groq");
        t.recordCall("groq");                    // drain
        assertThat(t.isDraining()).isTrue();

        t.notifyQuiescent("in-flight drafts complete");
        assertThat(t.isStopRequested()).isTrue();
        assertThat(t.terminatedWith).hasValue(RunBudgetTracker.EXIT_CODE);
    }

    @Test
    void hardCap_remainsTheBackstop_pastDrain() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCalls(4);
        props.getGlobal().setDrainStartCalls(2);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 4; i++) t.recordCall("groq");   // never quiesces
        assertThat(t.isStopRequested()).isTrue();
        assertThat(t.terminatedWith).hasValue(RunBudgetTracker.EXIT_CODE);
    }

    @Test
    void discretionarySpend_blockedWhileDraining_allowedBefore() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCalls(10);
        props.getGlobal().setDrainStartCalls(2);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        assertThat(t.allowDiscretionarySpend()).isTrue();
        t.recordCall("groq");
        t.recordCall("groq");                    // drain
        assertThat(t.allowDiscretionarySpend()).isFalse();
    }

    @Test
    void drainDisabledByDefault_zeroThresholdNeverDrains() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCalls(100);     // drainStartCalls left at 0
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 50; i++) t.recordCall("groq");
        assertThat(t.isDraining()).isFalse();
        assertThat(t.allowNewResearch()).isTrue();
    }

    // ---- 2026-06-05: auth-class failure auto-disable -----------------------

    @Test
    void fiveConsecutiveAuthFailures_disableProvider_othersUnaffected() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());
        t.recordCall("groq");   // a second live provider keeps the run alive

        for (int i = 0; i < RunBudgetTracker.AUTH_FAILURE_DISABLE_THRESHOLD - 1; i++) {
            t.recordAuthClassFailure("openrouter", 403);
        }
        assertThatCode(() -> t.checkProviderAvailable("openrouter")).doesNotThrowAnyException();

        t.recordAuthClassFailure("openrouter", 403);   // 5th — trips
        assertThat(t.isExhausted("openrouter")).isTrue();
        assertThatExceptionOfType(ProviderBudgetExhaustedException.class)
                .isThrownBy(() -> t.checkProviderAvailable("openrouter"));
        // The other provider and the run survive.
        assertThatCode(() -> t.checkProviderAvailable("groq")).doesNotThrowAnyException();
        assertThat(t.isStopRequested()).isFalse();
    }

    @Test
    void successfulCallResetsAuthFailureStreak() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 4; i++) t.recordAuthClassFailure("openrouter", 403);
        t.recordUsageAndEnforce("openrouter", "any-model", 10, 10);   // success → reset
        for (int i = 0; i < 4; i++) t.recordAuthClassFailure("openrouter", 403);

        assertThat(t.isExhausted("openrouter")).isFalse();
    }

    @Test
    void transientStatusesNeverCountTowardAutoDisable() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 20; i++) {
            t.recordAuthClassFailure("openrouter", 429);
            t.recordAuthClassFailure("openrouter", 500);
            t.recordAuthClassFailure("openrouter", 404);
        }
        assertThat(t.isExhausted("openrouter")).isFalse();
    }

    // ---- disabled feature -------------------------------------------------

    @Test
    void disabled_neverEnforces() {
        BudgetProperties props = new BudgetProperties();   // enabled defaults to false
        props.getProviders().put("groq", caps(0.0001, 1, 1, 1));
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> t.checkProviderAvailable("groq")).doesNotThrowAnyException();
            t.recordCall("groq");
            t.recordUsageAndEnforce("groq", "llama-3.3-70b-versatile", 1_000_000, 1_000_000);
        }
        assertThat(t.isExhausted("groq")).isFalse();
        assertThat(t.isStopRequested()).isFalse();
        assertThat(t.terminatedWith).hasValue(Integer.MIN_VALUE);
    }

    // ---- per-provider call cap → disable that provider, keep the others ---

    @Test
    void perProviderCallCap_disablesProvider_othersStayAvailable() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getProviders().put("groq", caps(0, 2, 0, 0));   // 2 calls max
        // openrouter is uncapped → never exhausts, keeps the app alive
        TestableTracker t = new TestableTracker(props, new LlmPricing());

        // mark openrouter as "in use" so all-exhausted can't trip on groq alone
        t.checkProviderAvailable("openrouter");
        t.recordCall("openrouter");

        t.checkProviderAvailable("groq");
        t.recordCall("groq");                 // 1
        t.checkProviderAvailable("groq");
        t.recordCall("groq");                 // 2 → reaches cap → disabled

        assertThat(t.isExhausted("groq")).isTrue();
        assertThatExceptionOfType(ProviderBudgetExhaustedException.class)
                .isThrownBy(() -> t.checkProviderAvailable("groq"));
        // the other provider is unaffected, and the app keeps running
        assertThatCode(() -> t.checkProviderAvailable("openrouter")).doesNotThrowAnyException();
        assertThat(t.isStopRequested()).isFalse();
    }

    // ---- per-provider cost cap + $-cost maths -----------------------------

    @Test
    void perProviderCostCap_usesPerModelPricing() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getProviders().put("groq", caps(1.00, 0, 0, 0));   // $1.00 cost cap
        // openrouter present + uncapped so the run isn't stopped by all-exhausted
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());
        t.recordUsageAndEnforce("openrouter", "x", 1, 1);

        // 1M in @ $0.59 + 1M out @ $0.79 = $1.38 ≥ $1.00 cap
        t.recordUsageAndEnforce("groq", "llama-3.3-70b-versatile", 1_000_000, 1_000_000);

        assertThat(t.costUsdFor("groq")).isEqualTo(1.38, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(t.isExhausted("groq")).isTrue();
        assertThat(t.isStopRequested()).isFalse();
    }

    // ---- global ceiling → stop the whole app ------------------------------

    @Test
    void globalCostCeiling_stopsApp() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxCostUsd(1.00);
        TestableTracker t = new TestableTracker(props, pricingWithGroqLlama());

        t.recordUsageAndEnforce("groq", "llama-3.3-70b-versatile", 1_000_000, 1_000_000); // $1.38

        assertThat(t.totalCostUsd()).isEqualTo(1.38, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(t.isStopRequested()).isTrue();
        assertThat(t.terminatedWith).hasValue(RunBudgetTracker.EXIT_CODE);
    }

    // ---- every used provider exhausted → stop -----------------------------

    @Test
    void allUsedProvidersExhausted_stopsApp() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getProviders().put("groq", caps(0, 1, 0, 0));
        props.getProviders().put("fast", caps(0, 1, 0, 0));
        TestableTracker t = new TestableTracker(props, new LlmPricing());

        t.recordCall("groq");        // groq exhausted; fast not yet used → no stop
        assertThat(t.isExhausted("groq")).isTrue();
        assertThat(t.isStopRequested()).isFalse();

        t.recordCall("fast");   // now every used provider is exhausted → stop
        assertThat(t.isExhausted("fast")).isTrue();
        assertThat(t.isStopRequested()).isTrue();
        assertThat(t.terminatedWith).hasValue(RunBudgetTracker.EXIT_CODE);
    }

    // ---- free model is $0 -------------------------------------------------

    @Test
    void freeModel_costsNothing() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getProviders().put("openrouter", caps(0.01, 0, 0, 0));   // tiny cost cap
        TestableTracker t = new TestableTracker(props, new LlmPricing());

        // hard-coded free model → $0 regardless of token volume
        t.recordUsageAndEnforce("openrouter", "openai/gpt-oss-120b:free", 5_000_000, 5_000_000);

        assertThat(t.costUsdFor("openrouter")).isEqualTo(0.0);
        assertThat(t.isExhausted("openrouter")).isFalse();
        assertThat(t.isStopRequested()).isFalse();
    }

    // ---- global token ceiling --------------------------------------------

    @Test
    void globalOutputTokenCeiling_stopsApp() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);
        props.getGlobal().setMaxOutputTokens(1000);
        TestableTracker t = new TestableTracker(props, new LlmPricing());

        t.recordUsageAndEnforce("groq", "some-model", 10, 600);
        assertThat(t.isStopRequested()).isFalse();
        t.recordUsageAndEnforce("groq", "some-model", 10, 600);  // 1200 ≥ 1000
        assertThat(t.isStopRequested()).isTrue();
        assertThat(t.terminatedWith).hasValue(RunBudgetTracker.EXIT_CODE);
    }
}
