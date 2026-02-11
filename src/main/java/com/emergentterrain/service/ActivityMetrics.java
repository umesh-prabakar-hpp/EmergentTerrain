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
