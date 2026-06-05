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
 * {@link MathConjectureSubstrateDelegate#evaluateWindow} (the sidecar's
 * {@code /numbertheory/window} response). {@link #DERIVED} =
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
            "mertens_delta",
            // Phase 30 §5.2 vocabulary enrichment (2026-06-05) — open-behavior
            // scalars per the triviality diagnosis's own prescription ("target
            // objects whose growth/extrema are genuinely unknown"): the largest
            // prime gap inside the window (Cramér territory) and the twin-pair
            // density (Hardy–Littlewood territory). Computed sidecar-side in the
            // same single window pass (numbertheory.jl); mirrored in the
            // sidecar's math_invariants.jl.
            "max_prime_gap",
            "twin_prime_density"
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
        // Phase 30 §5.2 (2026-06-05) — the two normalized open-behavior forms.
        // cramer_gap_ratio: max in-window prime gap over ln²(window center) —
        // conjecturally O(1) (Cramér), with genuinely unknown extreme behavior;
        // exactly the "O(1) object with open fluctuations" class the triviality
        // diagnosis prescribed. twin_vs_prime_sq: twin density over prime
        // density squared — under Hardy–Littlewood this tends to 2C₂ ≈ 1.3203,
        // a REAL mathematical constant the miner/recognizer can discover.
        m.put("cramer_gap_ratio",
                r -> {
                    double start = r.getOrDefault("_window_start", 0.0);
                    double w = r.getOrDefault("_window_size", 0.0);
                    double center = start + w / 2.0;
                    if (center <= 1.0) return 0.0;
                    double lc = Math.log(center);
                    return r.getOrDefault("max_prime_gap", 0.0) / (lc * lc);
                });
        m.put("twin_vs_prime_sq",
                r -> {
                    double p = r.getOrDefault("prime_density", 0.0);
                    return p > 0 ? r.getOrDefault("twin_prime_density", 0.0) / (p * p) : 0.0;
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

    /**
     * Phase 30 Wave 5 (30.5) — vocabulary extended with MDL-admitted derived scalars. The no-arg
     * {@link #vocabulary()} is the base path and is left untouched; {@code admitted} is appended only
     * when vocabulary expansion is on, so an empty/absent list reproduces the base vocabulary exactly
     * (the flag-OFF byte-identity invariant the design depends on).
     */
    public static List<String> vocabulary(List<DerivedScalar> admitted) {
        List<String> v = vocabulary();
        if (admitted != null) {
            for (DerivedScalar d : admitted) {
                v.add(d.name());
            }
        }
        return v;
    }

    /**
     * Phase 30 Wave 5 (30.5) — {@link #extract} plus the admitted derived scalars, computed on the
     * flattened base/derived map (so a derived scalar can reference any base/derived value). A
     * non-finite result is stored as {@code 0.0}, mirroring the DERIVED failure handling above. With
     * {@code admitted} empty/null this is byte-identical to {@link #extract(Map, long, long)}.
     */
    public static Map<String, Double> extract(Map<String, Double> rawWindow, long windowStart,
                                              long windowSize, List<DerivedScalar> admitted) {
        Map<String, Double> flat = extract(rawWindow, windowStart, windowSize);
        if (admitted != null) {
            for (DerivedScalar d : admitted) {
                double v = d.eval(flat);
                flat.put(d.name(), Double.isFinite(v) ? v : 0.0);
            }
        }
        return flat;
    }
}
