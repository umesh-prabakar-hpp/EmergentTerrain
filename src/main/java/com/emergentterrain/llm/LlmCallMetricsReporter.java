package com.emergentterrain.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import jakarta.annotation.PreDestroy;
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
 *       {@code mode:outcome} call counts, {@code in/out} token totals, and the
 *       dollar {@code cost} for that model (priced via {@link LlmPricing}; an
 *       unpriced, non-free model is tagged {@code [unpriced→$0]}).</li>
 *   <li><b>by-role</b> — one row per role with the per-{@code provider/model}
 *       call counts that landed under that role, the role's total
 *       {@code in/out} token spend, and the role's {@code cost} (summed over
 *       its models, each at its own price). Calls that fired outside any
 *       {@code withRole(...)} scope land under {@code role=unknown}, which
 *       is itself diagnostic — large {@code unknown} totals mean a call
 *       site should probably wrap a role scope.</li>
 * </ol>
 *
 * <p>Provider/role/model rows are sorted alphabetically (TreeMap) so the
 * output is stable across ticks.
 *
 * <p>Dollar <b>cost</b> is derived from the token counters via
 * {@link LlmPricing} and printed on every tick (header, by-handle, by-role) —
 * independent of the default-OFF {@link RunBudgetReporter}, which is the only
 * other place cost lands and only when the run-budget feature is enabled.
 * {@link #reportFinal()} prints the same per-model cost / tokens / call-count
 * one last time on context close (normal shutdown or a budget-triggered stop).
 */
@Component
public class LlmCallMetricsReporter {

    private static final Logger log = LoggerFactory.getLogger(LlmCallMetricsReporter.class);

    private final MeterRegistry registry;
    private final LlmPricing pricing;

    public LlmCallMetricsReporter(MeterRegistry registry, LlmPricing pricing) {
        this.registry = registry;
        this.pricing = pricing;
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
        // provider/model kept beside each handle so the cost lookup never re-parses
        // the handle string (model IDs can contain '/', e.g. openai/gpt-oss-120b:free).
        Map<String, String[]> pmByHandle = new TreeMap<>();
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
            pmByHandle.putIfAbsent(handle, new String[]{provider, model});
        }

        // Tokens keyed by handle (for per-handle rows + cost) and by role→handle
        // (so a role's cost sums each of its models at that model's own price,
        // rather than pricing a blended token total across mixed-price models).
        Map<String, long[]> tokensByHandle = new TreeMap<>();                  // handle → [in, out]
        Map<String, Map<String, long[]>> tokensByRoleHandle = new TreeMap<>(); // role → handle → [in, out]
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
            tokensByRoleHandle.computeIfAbsent(role, k -> new TreeMap<>())
                    .computeIfAbsent(handle, k -> new long[2])[idx] += amount;
            pmByHandle.putIfAbsent(handle, new String[]{provider, model});
            if (idx == 0) totalIn += amount; else totalOut += amount;
        }

        double totalCost = 0;
        for (var e : tokensByHandle.entrySet()) {
            String[] pm = pmByHandle.get(e.getKey());
            totalCost += pricing.costUsd(pm[0], pm[1], e.getValue()[0], e.getValue()[1]);
        }

        log.info("llm-calls total={} handles={} tokens-in={} tokens-out={} cost=${}",
                fmt(totalCalls), byHandle.size(), totalIn, totalOut, money(totalCost));

        for (var entry : byHandle.entrySet()) {
            String handle = entry.getKey();
            double handleTotal = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            long[] toks = tokensByHandle.getOrDefault(handle, new long[2]);
            String[] pm = pmByHandle.get(handle);
            double cost = pricing.costUsd(pm[0], pm[1], toks[0], toks[1]);
            String note = pricing.hasPrice(pm[0], pm[1]) ? "" : " [unpriced→$0]";
            log.info("llm-calls   {} total={} {} tokens-in={} tokens-out={} cost=${}{}",
                    handle, fmt(handleTotal), entry.getValue(), toks[0], toks[1], money(cost), note);
        }

        log.info("llm-calls by-role:");
        for (var entry : byRole.entrySet()) {
            String role = entry.getKey();
            double roleTotal = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            Map<String, long[]> roleTokens = tokensByRoleHandle.getOrDefault(role, java.util.Map.of());
            long rin = 0, rout = 0;
            double rcost = 0;
            for (var te : roleTokens.entrySet()) {
                String[] pm = pmByHandle.get(te.getKey());
                rin  += te.getValue()[0];
                rout += te.getValue()[1];
                rcost += pricing.costUsd(pm[0], pm[1], te.getValue()[0], te.getValue()[1]);
            }
            log.info("llm-calls   role={} calls={} {} tokens-in={} tokens-out={} cost=${}",
                    role, fmt(roleTotal), entry.getValue(), rin, rout, money(rcost));
        }
    }

    /**
     * Final per-model tally, emitted exactly once when the context closes —
     * both on a normal shutdown (Spring's shutdown hook closes the context) and
     * on a budget-triggered {@link RunBudgetTracker} stop (its {@code
     * terminate()} calls {@code SpringApplication.exit} before {@code
     * System.exit}, which closes the context first). Unlike the periodic
     * {@link #report()} heartbeat — which prints calls + tokens but <em>no
     * cost</em> — this dumps cost / tokens / call-count for each
     * {@code provider/model}, with the dollar cost derived from the accumulated
     * {@link LlmCallMetrics#METRIC_TOKENS} counters via {@link LlmPricing}. It
     * is the one place a dollar figure lands in the {@code llm-calls} log, so an
     * operator gets a closing per-model cost tally even when the default-OFF
     * run-budget reporter never ran. Models with no price entry (and not free)
     * are tagged {@code [unpriced→$0]}, mirroring the budget tracker's WARN.
     */
    @PreDestroy
    public void reportFinal() {
        java.util.Collection<Counter> callCounters  = registry.find(LlmCallMetrics.METRIC).counters();
        java.util.Collection<Counter> tokenCounters = registry.find(LlmCallMetrics.METRIC_TOKENS).counters();

        if (callCounters.isEmpty()) {
            log.info("llm-calls FINAL (shutdown) total=0 (no LLM calls this run)");
            return;
        }

        // handle (provider/model) → call count and → [in, out] tokens, plus the
        // split provider/model kept beside it so the cost lookup never has to
        // re-parse the handle (model IDs can themselves contain '/', e.g.
        // openai/gpt-oss-120b:free).
        Map<String, Double> callsByHandle  = new TreeMap<>();
        Map<String, long[]> tokensByHandle = new TreeMap<>();
        Map<String, String[]> pmByHandle   = new TreeMap<>();
        double totalCalls = 0;
        for (Counter c : callCounters) {
            String provider = tag(c, "provider");
            String model    = tag(c, "model");
            String handle   = provider + "/" + model;
            callsByHandle.merge(handle, c.count(), Double::sum);
            pmByHandle.putIfAbsent(handle, new String[]{provider, model});
            totalCalls += c.count();
        }
        long totalIn = 0, totalOut = 0;
        for (Counter c : tokenCounters) {
            String provider = tag(c, "provider");
            String model    = tag(c, "model");
            String handle   = provider + "/" + model;
            long amount     = (long) c.count();
            int idx = "prompt".equals(tag(c, "kind")) ? 0 : 1; // prompt → in (0); completion → out (1)
            tokensByHandle.computeIfAbsent(handle, k -> new long[2])[idx] += amount;
            pmByHandle.putIfAbsent(handle, new String[]{provider, model});
            if (idx == 0) totalIn += amount; else totalOut += amount;
        }

        double totalCost = 0;
        for (var e : tokensByHandle.entrySet()) {
            String[] pm = pmByHandle.get(e.getKey());
            totalCost += pricing.costUsd(pm[0], pm[1], e.getValue()[0], e.getValue()[1]);
        }

        log.info("llm-calls FINAL (shutdown) total={} handles={} tokens-in={} tokens-out={} cost=${}",
                fmt(totalCalls), callsByHandle.size(), totalIn, totalOut, money(totalCost));

        for (var entry : callsByHandle.entrySet()) {
            String handle = entry.getKey();
            long[] toks   = tokensByHandle.getOrDefault(handle, new long[2]);
            String[] pm   = pmByHandle.get(handle);
            double cost   = pricing.costUsd(pm[0], pm[1], toks[0], toks[1]);
            String note   = pricing.hasPrice(pm[0], pm[1]) ? "" : " [unpriced→$0]";
            log.info("llm-calls FINAL   {} calls={} tokens-in={} tokens-out={} cost=${}{}",
                    handle, fmt(entry.getValue()), toks[0], toks[1], money(cost), note);
        }
    }

    private static String tag(Counter c, String key) {
        String v = c.getId().getTag(key);
        return v == null ? "unknown" : v;
    }

    /** USD with 4 decimals, locale-fixed so the decimal point never becomes a comma. */
    private static String money(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static String fmt(double v) {
        long asLong = (long) v;
        return (asLong == v) ? Long.toString(asLong) : Double.toString(v);
    }
}
