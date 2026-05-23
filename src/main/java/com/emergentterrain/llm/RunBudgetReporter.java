package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Periodic heartbeat that logs the running LLM spend tracked by
 * {@link RunBudgetTracker}, so an operator can see how close each provider is to
 * its cap <em>before</em> a cap actually trips (the tracker itself only logs on
 * disable/stop).
 *
 * <p>One line per provider that has seen traffic plus a {@code TOTAL} line,
 * every {@code app.ai.budget.report-interval-ms} (default 5 min). No-op when the
 * budget is disabled or nothing has been dispatched yet, so it's silent unless
 * the feature is in use.
 */
@Component
public class RunBudgetReporter {

    private static final Logger log = LoggerFactory.getLogger(RunBudgetReporter.class);

    private final RunBudgetTracker tracker;
    private final BudgetProperties props;

    public RunBudgetReporter(RunBudgetTracker tracker, BudgetProperties props) {
        this.tracker = tracker;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.ai.budget.report-interval-ms:300000}")
    public void report() {
        if (!props.isEnabled()) {
            return;
        }
        Map<String, RunBudgetTracker.Usage> byProvider = tracker.usageByProvider();
        if (byProvider.isEmpty()) {
            return;  // nothing dispatched yet — stay quiet
        }
        for (Map.Entry<String, RunBudgetTracker.Usage> e : byProvider.entrySet()) {
            log.info(formatLine(e.getKey(), e.getValue(), props.getProviders().get(e.getKey())));
        }
        log.info(formatLine("TOTAL", tracker.globalUsage(), props.getGlobal()));
    }

    /** One heartbeat line; shows the cap alongside the running total when one is set. */
    static String formatLine(String label, RunBudgetTracker.Usage u, BudgetProperties.Caps cap) {
        String callsCap = (cap != null && cap.getMaxCalls() > 0) ? "/" + cap.getMaxCalls() : "";
        String costCap = (cap != null && cap.getMaxCostUsd() > 0) ? String.format("/$%.2f", cap.getMaxCostUsd()) : "";
        return String.format(
                "run-budget %s: calls=%d%s in=%d out=%d cost=$%.4f%s%s",
                label, u.calls(), callsCap, u.inputTokens(), u.outputTokens(),
                u.costUsd(), costCap, u.exhausted() ? " [EXHAUSTED]" : "");
    }
}
