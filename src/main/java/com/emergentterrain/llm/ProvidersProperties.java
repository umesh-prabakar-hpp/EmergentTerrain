package com.emergentterrain.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-provider caps co-located with the pool definition under
 * {@code app.ai.providers.<name>}. Each entry's {@code budget} (run-scoped
 * dollar/token caps) and {@code rate} (TPM/TPD limits) are merged at startup
 * into {@link BudgetProperties} and {@link TokenBudgetProperties} respectively
 * (see their {@code @PostConstruct} merge hooks), so the enforcers
 * ({@code RunBudgetTracker}, {@link TpmBudgetGate}, {@code SimulationGate}, the
 * reporters) keep reading their existing property beans unchanged.
 *
 * <p>This collapses what used to be three places per provider into one:
 * the pool def, its {@code budget}, and its {@code rate} now live together.
 * Two things stay outside:
 * <ul>
 *   <li><b>Globals</b> — {@code app.ai.budget.global} and the
 *       {@code app.tokens.budget} thresholds remain top-level.</li>
 *   <li><b>Pricing</b> — per-<i>model</i> $/Mtok stays in {@code llm-pricing.yml}
 *       (different granularity and edit lifecycle).</li>
 * </ul>
 *
 * <p>Only generic OpenAI-compat providers (those with an
 * {@code app.ai.providers.<name>} pool entry) can carry caps here; a non-pool
 * provider like local Ollama is unmetered and simply has no entry. Bound from
 * {@code app.ai}; the pool-definition fields (base-url, models, …) on the same
 * YAML node are ignored here — they're consumed by {@code GenericPoolsRegistrar}'s
 * own binder.
 */
@Component
@ConfigurationProperties("app.ai")
public class ProvidersProperties {

    private Map<String, Entry> providers = new LinkedHashMap<>();

    public Map<String, Entry> getProviders() { return providers; }
    public void setProviders(Map<String, Entry> providers) { this.providers = providers; }

    /** One provider's caps. Reuses the enforcer types so the merge is a plain copy. */
    public static class Entry {
        private BudgetProperties.Caps budget = new BudgetProperties.Caps();
        private TokenBudgetProperties.Provider rate = new TokenBudgetProperties.Provider();

        public BudgetProperties.Caps getBudget() { return budget; }
        public void setBudget(BudgetProperties.Caps budget) { this.budget = budget; }
        public TokenBudgetProperties.Provider getRate() { return rate; }
        public void setRate(TokenBudgetProperties.Provider rate) { this.rate = rate; }
    }
}
