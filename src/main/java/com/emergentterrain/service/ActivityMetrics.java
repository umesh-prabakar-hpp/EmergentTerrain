package com.emergentterrain.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Component
public class ActivityMetrics {
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastTotals = new ConcurrentHashMap<>();

    public void increment(String key) {
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /**
     * Phase 20.4 — additive variant for summable quantities (token totals,
     * cumulative durations, etc.). Same per-key LongAdder backing; the
     * delta-snapshot logic and total-snapshot logic both treat this as an
     * accumulator, so a row's totals show the running sum while deltas
     * show the per-interval increment. No-op when {@code n} is non-positive.
     */
    public void add(String key, long n) {
        if (n <= 0) return;
        counters.computeIfAbsent(key, k -> new LongAdder()).add(n);
    }

    public Map<String, Long> snapshotDeltas() {
        Map<String, Long> deltas = new ConcurrentHashMap<>();
        counters.forEach((key, adder) -> {
            long total = adder.sum();
            AtomicLong last = lastTotals.computeIfAbsent(key, k -> new AtomicLong(0));
            long delta = total - last.getAndSet(total);
            if (delta != 0) {
                deltas.put(key, delta);
            }
        });
        return deltas;
    }

    public Map<String, Long> snapshotTotals() {
        Map<String, Long> totals = new ConcurrentHashMap<>();
        counters.forEach((key, adder) -> totals.put(key, adder.sum()));
        return totals;
    }
}
