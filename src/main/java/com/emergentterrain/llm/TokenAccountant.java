package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Aggregates LLM token usage across providers in rolling time windows.
 *
 * Every LLM pool — Groq, Ollama, future providers — calls
 * {@link #record(TokenUsage)} once per completed request. The accountant keeps
 * a per-provider deque of entries; queries sum entries within the requested
 * window (minute or day). Entries older than 24h are swept on access.
 *
 * Intentionally provider-agnostic: adding a new top-tier model (Claude, GPT-4,
 * etc.) is one extra string in {@link TokenUsage#provider()}; no changes here.
 */
@Component
public class TokenAccountant {

    private static final Logger log = LoggerFactory.getLogger(TokenAccountant.class);

    private static final long DAY_MS    = 86_400_000L;
    private static final long MINUTE_MS = 60_000L;

    private final ConcurrentMap<String, ConcurrentLinkedDeque<Entry>> byProvider =
            new ConcurrentHashMap<>();

    public void record(TokenUsage usage) {
        if (usage == null) return;
        long total = usage.total();
        if (total <= 0) return;
        byProvider.computeIfAbsent(usage.provider(), k -> new ConcurrentLinkedDeque<>())
                .add(new Entry(usage.instant().toEpochMilli(), total, usage.model()));
        if (log.isTraceEnabled()) {
            log.trace("token-spend provider={} model={} prompt={} completion={}",
                    usage.provider(), usage.model(),
                    usage.promptTokens(), usage.completionTokens());
        }
    }

    /**
     * Phase 9 G9 — internal API for pre-debit reservations and corrections.
     * Accepts negative deltas so {@link TpmBudgetGate} can record a tentative
     * reservation of (say) 800 tokens and later correct it to a 550-token
     * actual via {@code recordDelta(provider, -250)}.
     *
     * <p>The public {@link #record(TokenUsage)} API still rejects non-positive
     * totals to preserve its "completed call" semantics.
     */
    public void recordDelta(String provider, long delta) {
        if (delta == 0 || provider == null) return;
        // Adjustment entries carry no model — they are pre-debit reservations
        // or post-call corrections, not standalone calls. Tier classifiers
        // see {@code null} for these and either skip or bucket as "unknown".
        byProvider.computeIfAbsent(provider, k -> new ConcurrentLinkedDeque<>())
                .add(new Entry(System.currentTimeMillis(), delta, null));
    }

    public long dailyTotal(String provider)  { return sumWindow(provider, DAY_MS); }
    public long minuteTotal(String provider) { return sumWindow(provider, MINUTE_MS); }

    public long dailyTotalAll() {
        long sum = 0;
        for (String p : byProvider.keySet()) sum += sumWindow(p, DAY_MS);
        return sum;
    }

    public long minuteTotalAll() {
        long sum = 0;
        for (String p : byProvider.keySet()) sum += sumWindow(p, MINUTE_MS);
        return sum;
    }

    public Map<String, Long> dailyByProvider() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String p : byProvider.keySet()) out.put(p, sumWindow(p, DAY_MS));
        return out;
    }

    /**
     * Sum tokens across all providers within {@code windowMs}, restricted to
     * entries whose model name matches {@code modelMatch}. Entries with a
     * {@code null} model (pre-debit reservations / corrections from
     * {@link #recordDelta}) are passed through to the predicate so callers
     * can choose to include or exclude them. Returns 0 when no entries match.
     *
     * <p>Used by {@link TokenTierReporter} to bucket tokens into
     * primary-tier / fast-tier / unknown without per-tier deques.
     */
    public long tokensByModel(long windowMs, java.util.function.Predicate<String> modelMatch) {
        long now    = System.currentTimeMillis();
        long cutoff = now - windowMs;
        long sum = 0;
        for (ConcurrentLinkedDeque<Entry> d : byProvider.values()) {
            for (Entry e : d) {
                if (e.t >= cutoff && modelMatch.test(e.model)) sum += e.tokens;
            }
        }
        return sum;
    }

    /**
     * Count entries (i.e. completed LLM calls) across all providers within
     * {@code windowMs} whose model matches {@code modelMatch}. Adjustment
     * entries (null model from {@link #recordDelta}) are passed to the
     * predicate. Useful for "tokens per call" averages alongside
     * {@link #tokensByModel}.
     */
    public long callsByModel(long windowMs, java.util.function.Predicate<String> modelMatch) {
        long now    = System.currentTimeMillis();
        long cutoff = now - windowMs;
        long n = 0;
        for (ConcurrentLinkedDeque<Entry> d : byProvider.values()) {
            for (Entry e : d) {
                if (e.t >= cutoff && modelMatch.test(e.model)) n++;
            }
        }
        return n;
    }

    private long sumWindow(String provider, long windowMs) {
        ConcurrentLinkedDeque<Entry> d = byProvider.get(provider);
        if (d == null) return 0;
        long now    = System.currentTimeMillis();
        long cutoff = now - windowMs;
        long sweep  = now - DAY_MS;
        Entry head;
        while ((head = d.peekFirst()) != null && head.t < sweep) {
            d.pollFirst();
        }
        long sum = 0;
        for (Entry e : d) {
            if (e.t >= cutoff) sum += e.tokens;
        }
        return sum;
    }

    private static final class Entry {
        final long t;
        final long tokens;
        /** Model identifier for a {@link #record(TokenUsage)} entry; {@code null}
         *  for adjustment entries produced by {@link #recordDelta}. */
        final String model;
        Entry(long t, long tokens, String model) {
            this.t = t;
            this.tokens = tokens;
            this.model = model;
        }
    }
}
