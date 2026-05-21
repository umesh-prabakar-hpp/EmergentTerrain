package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Math-domain scalar vocabulary. Mirrors
 * {@code scripts/conjecture-miner/math/invariants.py}.
 *
 * <p>{@link #BASE} = scalars produced directly by
 * {@link MathConjectureSubstrate#evaluateWindow}. {@link #DERIVED} =
 * compositions that read both BASE values and window metadata
 * ({@code _window_start}, {@code _window_size}).
 *
 * <p>{@link #extract} flattens one window evaluation into the named-scalar
 * map the engine consumes. Window metadata is carried through but kept
 * OUT of {@link #vocabulary()} so it doesn't appear as a conjecture term.
 */
public final class MathInvariants {

    public static final List<String> BASE = List.of(
            "abundant_density",
            "deficient_density",
            "prime_density",
            "happy_density",
            "mean_omega",
            "mean_Omega",
            "mean_divisor_count",
            "max_divisor_count",
            "mertens_delta"
    );

    public static final Map<String, Function<Map<String, Double>, Double>> DERIVED = derivedMap();

    private static Map<String, Function<Map<String, Double>, Double>> derivedMap() {
        Map<String, Function<Map<String, Double>, Double>> m = new LinkedHashMap<>();
        m.put("abundant_minus_deficient",
                r -> r.get("abundant_density") - r.get("deficient_density"));
        m.put("omega_Omega_gap",
                r -> r.get("mean_Omega") - r.get("mean_omega"));
        m.put("log_window_size",
                r -> {
                    double w = r.getOrDefault("_window_size", 0.0);
                    return w > 0 ? Math.log10(w) : 0.0;
                });
        m.put("sqrt_window_size",
                r -> Math.sqrt(r.getOrDefault("_window_size", 0.0)));
        m.put("log_window_start",
                r -> {
                    double s = r.getOrDefault("_window_start", 0.0);
                    return s > 0 ? Math.log10(s) : 0.0;
                });
        // Natural Mertens-conjecture scaling: |M(N)| / sqrt(N) stays O(1)
        // in observed regimes even though the original Mertens conjecture
        // is asymptotically false.
        m.put("mertens_per_sqrt_W",
                r -> {
                    double w = r.getOrDefault("_window_size", 0.0);
                    return w > 0 ? r.get("mertens_delta") / Math.sqrt(w) : 0.0;
                });
        return m;
    }

    private MathInvariants() {}

    /**
     * Flatten one window evaluation into the engine's named-scalar map.
     * Pulls BASE values, computes DERIVED, and carries metadata
     * ({@code _window_start}, {@code _window_size}, {@code _tick}) through
     * for downstream use.
     */
    public static Map<String, Double> extract(Map<String, Double> rawWindow, long windowStart, long windowSize) {
        Map<String, Double> flat = new LinkedHashMap<>();
        flat.put("_window_size", (double) windowSize);
        flat.put("_window_start", (double) windowStart);
        flat.put("_tick", (double) windowStart);

        for (String name : BASE) {
            flat.put(name, rawWindow.getOrDefault(name, 0.0));
        }
        for (Map.Entry<String, Function<Map<String, Double>, Double>> e : DERIVED.entrySet()) {
            try {
                flat.put(e.getKey(), e.getValue().apply(flat));
            } catch (NullPointerException | ArithmeticException ex) {
                flat.put(e.getKey(), 0.0);
            }
        }
        return flat;
    }

    /** All names available to conjectures, in deterministic order. */
    public static List<String> vocabulary() {
        List<String> v = new ArrayList<>(BASE);
        v.addAll(DERIVED.keySet());
        return v;
    }
}
