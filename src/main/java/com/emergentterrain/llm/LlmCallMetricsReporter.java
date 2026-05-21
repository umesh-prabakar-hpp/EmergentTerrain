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
 * counter totals plus the companion {@link LlmCallMetrics#METRIC_TOKENS}
 * counters so an operator can see — without {@code grep}-piecing the log —
 * how many LLM calls have been issued and how many tokens they consumed,
 * broken down by both {@code provider/model} and {@code role} (the
 * {@code narration} / {@code professor-tools} / {@code panel-review} /
 * {@code fast-audit} category set from {@link LlmCallContext}).
 *
 * <p>Each tick (interval driven by {@code app.llm.callMetricsReportIntervalMs},
 * default 300 s) emits two sections:
 *
 * <ol>
 *   <li><b>by-handle</b> — one row per {@code provider/model} with
 *       {@code mode:outcome} call counts and {@code in/out} token totals.
 *       Same shape as before the Phase 23.6d expansion, with tokens
 *       appended.</li>
 *   <li><b>by-role</b> — one row per role with the per-{@code provider/model}
 *       call counts that landed under that role and the role's total
 *       {@code in/out} token spend. Calls that fired outside any
 *       {@code withRole(...)} scope land under {@code role=unknown}, which
 *       is itself diagnostic — large {@code unknown} totals mean a call
 *       site should probably wrap a role scope.</li>
 * </ol>
 *
 * <p>Provider/role/model rows are sorted alphabetically (TreeMap) so the
 * output is stable across ticks.
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
        Search callSearch  = registry.find(LlmCallMetrics.METRIC);
        Search tokenSearch = registry.find(LlmCallMetrics.METRIC_TOKENS);
        java.util.Collection<Counter> callCounters  = callSearch.counters();
        java.util.Collection<Counter> tokenCounters = tokenSearch.counters();

        if (callCounters.isEmpty()) {
            log.info("llm-calls total=0 (no LLM calls recorded yet)");
            return;
        }

        // by-handle: (provider/model) → { mode:outcome → count }
        Map<String, Map<String, Double>> byHandle = new TreeMap<>();
        // by-role:   role → { provider/model → count }
        Map<String, Map<String, Double>> byRole = new TreeMap<>();
        double totalCalls = 0;
        for (Counter c : callCounters) {
            String provider = tag(c, "provider");
            String model    = tag(c, "model");
            String mode     = tag(c, "mode");
            String outcome  = tag(c, "outcome");
            String role     = tag(c, "role");
            String handle   = provider + "/" + model;
            double count    = c.count();
            totalCalls += count;
            byHandle.computeIfAbsent(handle, k -> new LinkedHashMap<>())
                    .merge(mode + ":" + outcome, count, Double::sum);
            byRole.computeIfAbsent(role, k -> new LinkedHashMap<>())
                    .merge(handle, count, Double::sum);
        }

        // Token bins: keyed three ways so per-handle and per-role rows can read directly.
        Map<String, long[]> tokensByHandle = new TreeMap<>(); // handle → [in, out]
        Map<String, long[]> tokensByRole   = new TreeMap<>(); // role   → [in, out]
        long totalIn = 0, totalOut = 0;
        for (Counter c : tokenCounters) {
            String provider = tag(c, "provider");
            String model    = tag(c, "model");
            String kind     = tag(c, "kind");
            String role     = tag(c, "role");
            String handle   = provider + "/" + model;
            long amount     = (long) c.count();
            int idx = "prompt".equals(kind) ? 0 : 1; // prompt → in (0); completion → out (1)
            tokensByHandle.computeIfAbsent(handle, k -> new long[2])[idx] += amount;
            tokensByRole.computeIfAbsent(role, k -> new long[2])[idx] += amount;
            if (idx == 0) totalIn += amount; else totalOut += amount;
        }

        log.info("llm-calls total={} handles={} tokens-in={} tokens-out={}",
                fmt(totalCalls), byHandle.size(), totalIn, totalOut);

        for (var entry : byHandle.entrySet()) {
            String handle = entry.getKey();
            double handleTotal = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            long[] toks = tokensByHandle.getOrDefault(handle, new long[2]);
            log.info("llm-calls   {} total={} {} tokens-in={} tokens-out={}",
                    handle, fmt(handleTotal), entry.getValue(), toks[0], toks[1]);
        }

        log.info("llm-calls by-role:");
        for (var entry : byRole.entrySet()) {
            String role = entry.getKey();
            double roleTotal = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            long[] toks = tokensByRole.getOrDefault(role, new long[2]);
            log.info("llm-calls   role={} calls={} {} tokens-in={} tokens-out={}",
                    role, fmt(roleTotal), entry.getValue(), toks[0], toks[1]);
        }
    }

    private static String tag(Counter c, String key) {
        String v = c.getId().getTag(key);
        return v == null ? "unknown" : v;
    }

    private static String fmt(double v) {
        long asLong = (long) v;
        return (asLong == v) ? Long.toString(asLong) : Double.toString(v);
    }
}
