package com.emergentterrain.research;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-domain+topic peerScore distributions using Welford's online algorithm.
 * Returns the z-score of a new value before incorporating it, so the caller can
 * annotate a paper with how anomalous it is relative to prior work in that slot.
 * Z-scores are meaningful only after MIN_SAMPLES papers; returns 0.0 before that.
 */
class AnomalyTracker {

    private static final int MIN_SAMPLES = 5;

    // stats[key] = double[3] { count, mean, M2 }
    private final ConcurrentHashMap<String, double[]> stats = new ConcurrentHashMap<>();

    /**
     * Compute the z-score of value against the current distribution for this
     * domain+topic, then update the distribution to include value.
     * Returns 0.0 if fewer than MIN_SAMPLES prior values exist.
     */
    double observe(String domain, String topic, double value) {
        String key = domain + ":" + topic;
        double[] s = stats.computeIfAbsent(key, k -> new double[3]);
        double zScore = 0.0;
        synchronized (s) {
            if (s[0] >= MIN_SAMPLES && s[0] > 1) {
                double stddev = Math.sqrt(s[2] / (s[0] - 1));
                zScore = stddev > 1e-9 ? (value - s[1]) / stddev : 0.0;
            }
            s[0]++;
            double delta = value - s[1];
            s[1] += delta / s[0];
            s[2] += delta * (value - s[1]);
        }
        return zScore;
    }
}
