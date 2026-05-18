package com.emergentterrain.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Periodic heartbeat that prints the running {@link LlmCallMetrics#METRIC}
 * counter totals so an operator can see — without {@code grep}-piecing the
 * log — how many LLM calls have been issued, broken down by
 * provider/model/mode/outcome.
 *
 * <p>Two lines per tick (interval driven by {@code app.llm.callMetricsReportIntervalMs}):
 * a single "llm-calls total=N" header followed by per-(provider,model) rows
 * with mode and outcome subtotals. Provider rows are sorted alphabetically
 * so the output is stable across ticks.
 */
@Component
public class LlmCallMetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(LlmCallMetricsReporter.class);

    private final MeterRegistry registry;

    public LlmCallMetricsReporter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${app.llm.callMetricsReportIntervalMs:300000}",
               initialDelayString = "${app.llm.callMetricsReportIntervalMs:300000}")
    public void report() {
        Search search = registry.find(LlmCallMetrics.METRIC);
        java.util.Collection<Counter> counters = search.counters();
        if (counters.isEmpty()) {
            log.info("llm-calls total=0 (no LLM calls recorded yet)");
            return;
        }

        // Group counts by (provider, model) → { mode/outcome → count }.
        Map<String, Map<String, Double>> byHandle = new TreeMap<>();
        double total = 0;
        for (Counter c : counters) {
            String provider = c.getId().getTag("provider");
            String model    = c.getId().getTag("model");
            String mode     = c.getId().getTag("mode");
            String outcome  = c.getId().getTag("outcome");
            String handle   = provider + "/" + model;
            String key      = mode + ":" + outcome;
            double count    = c.count();
            total += count;
            byHandle.computeIfAbsent(handle, k -> new LinkedHashMap<>())
                    .merge(key, count, Double::sum);
        }

        log.info("llm-calls total={} handles={}", fmt(total), byHandle.size());
        for (var entry : byHandle.entrySet()) {
            double handleTotal = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            log.info("llm-calls   {} total={} {}",
                    entry.getKey(), fmt(handleTotal), entry.getValue());
        }
    }

    private static String fmt(double v) {
        long asLong = (long) v;
        return (asLong == v) ? Long.toString(asLong) : Double.toString(v);
    }
}
