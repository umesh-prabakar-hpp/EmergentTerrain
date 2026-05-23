package com.emergentterrain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-model price lookup, bound from the separate {@code llm-pricing.yml}
 * (imported via {@code spring.config.import}). Prices are USD per 1,000,000
 * tokens, split input / output.
 *
 * <p>Used by {@link RunBudgetTracker} to convert a call's token usage into a
 * dollar cost. Pricing varies by <em>model</em>, not just provider (on Groq
 * alone gpt-oss-120b is $0.15/$0.60 while llama-3.3-70b is $0.59/$0.79), so the
 * table is keyed provider → model.
 *
 * <p>Free tiers (e.g. OpenRouter {@code :free}) always cost $0: they are listed
 * under {@code free-models} in the config and also hard-coded in
 * {@link #HARDCODED_FREE} as a safety net and for the simulation tests.
 */
@Component
@ConfigurationProperties("llm-pricing")
public class LlmPricing {

    /**
     * Models billed at $0 regardless of any per-model entry. Hard-coded so the
     * simulation tests and the cost calculator agree on the free set even when
     * the lookup config is absent. The {@code free-models} config list adds to
     * this set.
     */
    public static final Set<String> HARDCODED_FREE = Set.of(
            "openai/gpt-oss-120b:free"
    );

    private List<String> freeModels = new java.util.ArrayList<>();
    private Map<String, ProviderPrices> providers = new LinkedHashMap<>();

    /** Dollar cost of one call. Free or unpriced models return 0.0. */
    public double costUsd(String provider, String model, long inputTokens, long outputTokens) {
        if (isFree(model)) return 0.0;
        ModelPrice mp = priceFor(provider, model);
        if (mp == null) return 0.0;
        return inputTokens  / 1_000_000.0 * mp.getInUsdPerMtok()
             + outputTokens / 1_000_000.0 * mp.getOutUsdPerMtok();
    }

    /** True if a price is known (or the model is free). False ⇒ caller should WARN. */
    public boolean hasPrice(String provider, String model) {
        return isFree(model) || priceFor(provider, model) != null;
    }

    public boolean isFree(String model) {
        return model != null && (HARDCODED_FREE.contains(model) || freeModels.contains(model));
    }

    private ModelPrice priceFor(String provider, String model) {
        ProviderPrices pp = providers.get(provider);
        if (pp == null) return null;
        return pp.getModels().get(model);
    }

    public List<String> getFreeModels() { return freeModels; }
    public void setFreeModels(List<String> freeModels) { this.freeModels = freeModels; }

    public Map<String, ProviderPrices> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderPrices> providers) { this.providers = providers; }

    public static class ProviderPrices {
        private Map<String, ModelPrice> models = new LinkedHashMap<>();
        public Map<String, ModelPrice> getModels() { return models; }
        public void setModels(Map<String, ModelPrice> models) { this.models = models; }
    }

    public static class ModelPrice {
        private double inUsdPerMtok = 0.0;
        private double outUsdPerMtok = 0.0;
        public double getInUsdPerMtok() { return inUsdPerMtok; }
        public void setInUsdPerMtok(double v) { this.inUsdPerMtok = v; }
        public double getOutUsdPerMtok() { return outUsdPerMtok; }
        public void setOutUsdPerMtok(double v) { this.outUsdPerMtok = v; }
    }
}
