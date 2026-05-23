package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 config consolidation: per-provider caps live under
 * {@code app.ai.providers.<name>.{budget,rate}} and are merged into
 * {@link BudgetProperties} / {@link TokenBudgetProperties} at startup. Pins both
 * the merge logic and the YAML binding into the reused cap types.
 */
class ProvidersPropertiesMergeTest {

    private static ProvidersProperties.Entry entry(double cost, long inTok, long outTok,
                                                   long daily, long minute) {
        ProvidersProperties.Entry e = new ProvidersProperties.Entry();
        e.getBudget().setMaxCostUsd(cost);
        e.getBudget().setMaxInputTokens(inTok);
        e.getBudget().setMaxOutputTokens(outTok);
        e.getRate().setDailyLimit(daily);
        e.getRate().setMinuteLimit(minute);
        return e;
    }

    private static ProvidersProperties withProviders(Map<String, ProvidersProperties.Entry> m) {
        ProvidersProperties p = new ProvidersProperties();
        p.setProviders(m);
        return p;
    }

    /** Mirrors the live config: groq has both, groq-fast budget-only, openrouter rate-only. */
    private static ProvidersProperties liveShape() {
        Map<String, ProvidersProperties.Entry> m = new LinkedHashMap<>();
        m.put("groq",       entry(8.00, 6_800_000, 5_000_000, 270_000, 8_000));
        m.put("groq-fast",  entry(2.00, 20_000_000, 12_500_000, 0, 0));        // no rate
        m.put("openrouter", entry(0,    0,          0,          500_000, 175_000)); // no budget
        return withProviders(m);
    }

    @Test
    void budgetMerge_addsOnlyEntriesWithA$Cap() {
        BudgetProperties bp = new BudgetProperties();
        bp.mergeProviderBudgets(liveShape());

        assertThat(bp.getProviders()).containsOnlyKeys("groq", "groq-fast"); // openrouter has no $ cap
        assertThat(bp.getProviders().get("groq").getMaxCostUsd()).isEqualTo(8.00);
        assertThat(bp.getProviders().get("groq-fast").getMaxOutputTokens()).isEqualTo(12_500_000L);
    }

    @Test
    void rateMerge_addsOnlyMeteredEntries() {
        TokenBudgetProperties tp = new TokenBudgetProperties();
        tp.mergeProviderRates(liveShape());

        assertThat(tp.getProviders()).containsOnlyKeys("groq", "openrouter"); // groq-fast unmetered
        assertThat(tp.getProviders().get("groq").getMinuteLimit()).isEqualTo(8_000L);
        assertThat(tp.getProviders().get("openrouter").getDailyLimit()).isEqualTo(500_000L);
    }

    @Test
    void merge_nullSource_isNoOp() {
        BudgetProperties bp = new BudgetProperties();
        bp.mergeProviderBudgets(null);
        assertThat(bp.getProviders()).isEmpty();

        TokenBudgetProperties tp = new TokenBudgetProperties();
        tp.mergeProviderRates(null);
        assertThat(tp.getProviders()).isEmpty();
    }

    @Test
    void binds_fromKebabCaseYaml_andIgnoresPoolFields() {
        Map<String, Object> src = new HashMap<>();
        src.put("app.ai.providers.groq.budget.max-cost-usd", "8.00");
        src.put("app.ai.providers.groq.budget.max-input-tokens", "6800000");
        src.put("app.ai.providers.groq.rate.daily-limit", "270000");
        src.put("app.ai.providers.groq.rate.minute-limit", "8000");
        // pool-definition fields on the same node must be ignored by ProvidersProperties:
        src.put("app.ai.providers.groq.base-url", "https://api.groq.com/openai/v1");
        src.put("app.ai.providers.groq.models[0]", "llama-3.3-70b-versatile");

        ConfigurationPropertySource cps = new MapConfigurationPropertySource(src);
        ProvidersProperties bound = new Binder(cps)
                .bind("app.ai", Bindable.of(ProvidersProperties.class))
                .orElseGet(ProvidersProperties::new);

        assertThat(bound.getProviders()).containsKey("groq");
        ProvidersProperties.Entry groq = bound.getProviders().get("groq");
        assertThat(groq.getBudget().getMaxCostUsd()).isEqualTo(8.00);
        assertThat(groq.getBudget().getMaxInputTokens()).isEqualTo(6_800_000L);
        assertThat(groq.getRate().getDailyLimit()).isEqualTo(270_000L);
        assertThat(groq.getRate().getMinuteLimit()).isEqualTo(8_000L);
    }
}
