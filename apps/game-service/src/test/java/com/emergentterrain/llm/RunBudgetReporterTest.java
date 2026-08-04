package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the operator-visibility surface: the tracker's usage snapshot and the
 * heartbeat line format ({@link RunBudgetReporter#formatLine}).
 */
class RunBudgetReporterTest {

    private static LlmPricing pricingGroqLlama() {
        LlmPricing p = new LlmPricing();
        LlmPricing.ModelPrice mp = new LlmPricing.ModelPrice();
        mp.setInUsdPerMtok(0.59);
        mp.setOutUsdPerMtok(0.79);
        LlmPricing.ProviderPrices gp = new LlmPricing.ProviderPrices();
        gp.getModels().put("llama-3.3-70b-versatile", mp);
        p.getProviders().put("groq", gp);
        return p;
    }

    @Test
    void snapshot_reflectsRecordedCallsAndCost() {
        BudgetProperties props = new BudgetProperties();
        props.setEnabled(true);                 // no caps → just counts, no disable/stop
        RunBudgetTracker t = new RunBudgetTracker(props, pricingGroqLlama(), null);

        t.recordCall("groq");
        t.recordUsageAndEnforce("groq", "llama-3.3-70b-versatile", 1_000_000, 1_000_000);  // $1.38

        RunBudgetTracker.Usage u = t.usageByProvider().get("groq");
        assertThat(u.calls()).isEqualTo(1);
        assertThat(u.inputTokens()).isEqualTo(1_000_000);
        assertThat(u.outputTokens()).isEqualTo(1_000_000);
        assertThat(u.costUsd()).isEqualTo(1.38, within(1e-9));
        assertThat(u.exhausted()).isFalse();
        assertThat(t.globalUsage().costUsd()).isEqualTo(1.38, within(1e-9));
    }

    @Test
    void formatLine_showsCapsAndExhaustedFlag() {
        RunBudgetTracker.Usage u = new RunBudgetTracker.Usage(5, 100, 200, 1.2345, true);
        BudgetProperties.Caps cap = new BudgetProperties.Caps();
        cap.setMaxCalls(10);
        cap.setMaxCostUsd(5.0);

        String line = RunBudgetReporter.formatLine("groq", u, cap);

        assertThat(line)
                .contains("calls=5/10")
                .contains("in=100")
                .contains("out=200")
                .contains("cost=$1.2345")
                .contains("/$5.00")
                .contains("[EXHAUSTED]");
    }

    @Test
    void formatLine_noCaps_omitsLimitsAndFlag() {
        RunBudgetTracker.Usage u = new RunBudgetTracker.Usage(3, 0, 0, 0.0, false);
        String line = RunBudgetReporter.formatLine("TOTAL", u, null);
        assertThat(line)
                .contains("run-budget TOTAL: calls=3")
                .doesNotContain("[EXHAUSTED]")
                .doesNotContain("/$");
    }
}
