package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            "twin_prime_density",
            // 2026-06-13 second-order BASE scalars — computed in the sidecar sieve
            // (numbertheory.jl) and passed through here, so Java/Julia agree by construction.
            // Erdős–Kac variance ratio Var(Ω)/ln ln x → 1 (verify_constant target 1); Dirichlet
            // divisor error Δ(x)/x^{1/4} (oscillates, bounded iff the Dirichlet conjecture holds —
            // verify_oscillation). Both require a sidecar restart to register.
            "omega_ek_ratio",
            "dirichlet_error",
            // 2026-06-16 object-substrate-expansion.md Stage 1 — per-integer object densities computed in
            // the sidecar sieve (numbertheory.jl nt_evaluate_window) and passed through here, so Java/Julia
            // agree by construction. squarefree (Ω==ω → 6/π², validation); richly-divisible (d>ω²) and
            // omega-excess (Ω−ω≥2) are uncharted natural densities. Require a sidecar restart to register.
            "squarefree_density",
            "richly_divisible_density",
            "omega_excess_density"
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
        // 2026-06-08 — growth anchors so the single-feature affine miner can
        // propose a bound that TRACKS a growing LHS instead of pinning the RHS
        // near a constant feature. Every k·feature+c bound on a growing quantity
        // (mean_divisor_count ~ log x; max_prime_gap ~ ln²x by Cramér) is
        // trivially refuted when the feature is flat; an RHS whose own growth
        // matches the LHS lets e.g. max_prime_gap ≤ k·log_sq_window_start + c
        // (the Cramér form) actually hold across the sieve range. Like
        // log_window_start these are window-geometry RHS terms (barred as an LHS
        // by ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS).
        m.put("sqrt_window_start",
                r -> Math.sqrt(r.getOrDefault("_window_start", 0.0)));
        m.put("log_sq_window_start",
                r -> {
                    double start = r.getOrDefault("_window_start", 0.0);
                    double w = r.getOrDefault("_window_size", 0.0);
                    double center = start + w / 2.0;
                    if (center <= 1.0) return 0.0;
                    double lc = Math.log(center);   // natural log — the Cramér ln²x scale
                    return lc * lc;
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
        // 2026-06-13 (second-order step) — mean_divisor_count grows (~ln x), so it is not a
        // constant/bound target; its SECOND-ORDER content is the constant after the main term: the local
        // average of d(n) over a window tends to ln(center) + 2γ (γ = Euler–Mascheroni), so
        // mean_divisor_count − ln(center) converges to ≈ 2γ ≈ 1.1544 — a verify_constant target that
        // recovers Euler's gamma from the divisor function (the leading log law is textbook; this is the
        // result). Natural log of the window center, matching log_sq_window_start / cramer_gap_ratio.
        // Mirrored IDENTICALLY in the sidecar's math_invariants.jl (same iteration order). NOTE: empirical
        // — verify_constant with no target measures the limit, which pins the constant regardless of the
        // exact local-vs-cumulative correction (2γ vs 2γ−1).
        m.put("mean_divisor_excess",
                r -> {
                    double start = r.getOrDefault("_window_start", 0.0);
                    double w = r.getOrDefault("_window_size", 0.0);
                    double center = start + w / 2.0;
                    return center > 1.0
                            ? r.getOrDefault("mean_divisor_count", 0.0) - Math.log(center)
                            : 0.0;
                });
        // 2026-06-14 (room b72c4e10 REVIEW_REPORT §1 rec #1) — the WIGERT growth anchor
        // for max_divisor_count. max_divisor_count grows super-logarithmically (Wigert: the
        // maximal order of d(n) is exp((ln2+o(1))·ln n/ln ln n)), so every flat-feature RHS
        // (mean_divisor_excess→2γ, dirichlet_error, omega_ek_ratio→1) makes k·rhs+c near-constant
        // and is REFUTED by construction — the exact failure mode of P-b72c-77627584/-585/-587,
        // whose own conclusions all name this anchor as the cure. This is the divisor twin of
        // log_sq_window_start (the Cramér ln²x anchor that lets max_prime_gap ≤ k·log_sq_window_start
        // + c actually hold; live survivor a7c881a9, k=0.649). Unit-coefficient form exp(ln x/ln ln x):
        // since the true Wigert constant ln2 ≈ 0.693 < 1, this DOMINATES d(n) asymptotically (a safe
        // upper proxy), while at reachable x the measured Wigert coordinate sits at ≈1.0 (verify_growth
        // reported 1.0008/1.019/1.018/1.010 across the batch) so a k∈grid + tight c HOLDS on the very
        // windows the flat bounds died on. Pure window geometry (center only) — barred as an LHS by
        // ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS, valid as RHS. Mirrored IDENTICALLY in the
        // sidecar's math_invariants.jl (same guards) for offline-miner parity.
        m.put("wigert_bound",
                r -> {
                    double start = r.getOrDefault("_window_start", 0.0);
                    double w = r.getOrDefault("_window_size", 0.0);
                    double center = start + w / 2.0;
                    if (center <= Math.E) return 0.0;   // need ln(center) > 1 so ln ln(center) > 0
                    double lc = Math.log(center);
                    double llc = Math.log(lc);
                    double v = Math.exp(lc / llc);
                    return Double.isFinite(v) ? v : 0.0;
                });
        // 2026-06-14 (room b72c4e10 REVIEW_REPORT §1 rec #1 redux) — the ln ln x growth anchor for
        // mean_omega / mean_Omega. By Hardy–Ramanujan/Erdős–Kac the average number of (distinct)
        // prime factors over a window near x is ln ln x + B₁ (Mertens B₁ ≈ 0.2615 for ω; +B₂ for Ω),
        // a SLOWLY GROWING quantity — so every flat-feature RHS (dirichlet_error, mean_divisor_excess
        // →2γ, omega_ek_ratio→1.27, deficient_density≈0.75) makes k·rhs+c near-constant and is REFUTED
        // by construction (P-b72c-77627595/-596/-598/-599, whose conclusions all name THIS anchor).
        // The third member of the growth-anchor family — log_sq_window_start (ln²x, Cramér / max_prime_gap)
        // and wigert_bound (exp(ln x/ln ln x), Wigert / max_divisor_count) being the others — so a bound
        // mean_omega ≤ k·log_log_window_start + c can track the growth and hold, with B₁ recoverable as
        // the intercept the way 2γ was. Natural log of the window CENTER (matching the family's
        // convention, not the base-10 log_window_start). Pure window geometry → barred as an LHS in
        // ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS, valid as RHS. Mirrored IDENTICALLY in the
        // sidecar's math_invariants.jl (same guards) for offline-miner parity.
        m.put("log_log_window_start",
                r -> {
                    double start = r.getOrDefault("_window_start", 0.0);
                    double w = r.getOrDefault("_window_size", 0.0);
                    double center = start + w / 2.0;
                    if (center <= 1.0) return 0.0;      // need ln(center) > 0 for ln ln(center) to be real
                    double lc = Math.log(center);
                    double llc = Math.log(lc);
                    return Double.isFinite(llc) ? llc : 0.0;
                });
        return m;
    }

    /**
     * 2026-06-06 review rec #1 — which scalars each DERIVED form is computed
     * FROM (window-geometry inputs excluded; those have their own W1 rule).
     * Source of truth for the derived-vs-component triviality exclusion: a
     * conjecture pairing a derived scalar against one of its own components
     * is structurally coupled regardless of parameters — P-b72c-236 scored an
     * all-time-record 0.888 on the *identity* a−d ≈ 2a−1 (densities sum to
     * ≈1), and P-b72c-235 spent a session slot refuting another dressing of
     * the same pairing. Maintained by hand next to {@link #derivedMap()};
     * keep the two in sync when adding a derived scalar.
     */
    public static final Map<String, Set<String>> DERIVED_COMPONENTS = Map.of(
            "abundant_minus_deficient", Set.of("abundant_density", "deficient_density"),
            "omega_Omega_gap", Set.of("mean_omega", "mean_Omega"),
            "mertens_per_sqrt_W", Set.of("mertens_delta"),
            "cramer_gap_ratio", Set.of("max_prime_gap"),
            "twin_vs_prime_sq", Set.of("twin_prime_density", "prime_density"),
            "mean_divisor_excess", Set.of("mean_divisor_count"));

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
