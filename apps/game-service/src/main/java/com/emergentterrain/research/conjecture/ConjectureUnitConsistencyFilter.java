package com.emergentterrain.research.conjecture;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 30 Wave 1 — unit/dimensional-consistency gate (sim #6, the "meters ≤ k·seconds" check).
 * Deterministic on {@code (lhs, rhs, k)}; same standalone shape as {@link ConjectureDimensionFilter}.
 *
 * <p>The math vocabulary has no literal units, but it has two genuinely different "dimensions":
 * <ul>
 *   <li><b>Number-theoretic, O(1)-bounded</b> quantities — the densities, the density difference
 *       {@code abundant_minus_deficient}, the convergent {@code omega_Omega_gap}, and the
 *       Mertens-conjecture-scaled {@code mertens_per_sqrt_W} (bounded in observed regimes). These do
 *       not grow without bound.</li>
 *   <li><b>Harness window-magnitudes</b> — {@code log_window_size}, {@code log_window_start},
 *       {@code sqrt_window_size}: not number theory at all, just "how big a window the harness
 *       happened to pick," and unbounded as the window grows.</li>
 * </ul>
 *
 * <p>A bound {@code bounded_nt ≤ k·window_magnitude + c} with {@code k>0} compares a number-theoretic
 * fact to a harness artifact of a different dimension and is <i>eventually vacuous</i> (the RHS → ∞
 * while the LHS stays O(1)). This generalises {@link ConjectureDimensionFilter} — which flags only
 * the four densities — to the full O(1)-bounded number-theoretic class (so e.g.
 * {@code omega_Omega_gap ≤ k·log_window_size} or {@code mertens_per_sqrt_W ≤ k·sqrt_window_size} is
 * caught too). Conservatively scoped to the eventually-vacuous {@code k>0} forward direction; the
 * reverse is eventually <i>false</i> and the refutation path handles it. {@code k≈0} constant bounds
 * are out of scope ({@link ConjectureTrivialityFilter} dedups those).
 *
 * <p>Wired flag-gated OFF via {@code app.research.conjectureMiner.unitConsistencyEnabled}.
 */
public final class ConjectureUnitConsistencyFilter {

    /** O(1)-bounded number-theoretic scalars (densities + the bounded derived quantities). */
    public static final Set<String> BOUNDED_NT_SCALARS = Stream.concat(
            ConjectureDimensionFilter.DENSITY_SCALARS.stream(),
            Stream.of("abundant_minus_deficient", "omega_Omega_gap", "mertens_per_sqrt_W",
                    // Phase 30 §5.2 (2026-06-05) — O(1)-in-observed-regimes like
                    // mertens_per_sqrt_W: Cramér-normalized gap and the HL twin ratio.
                    "cramer_gap_ratio", "twin_vs_prime_sq"))
            .collect(Collectors.toUnmodifiableSet());

    /** Harness window-magnitude scalars (unbounded, not number theory). */
    public static final Set<String> WINDOW_MAGNITUDE_SCALARS =
            ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS;

    private ConjectureUnitConsistencyFilter() {
    }

    public static boolean isBoundedNumberTheoretic(String scalar) {
        return BOUNDED_NT_SCALARS.contains(scalar);
    }

    public static boolean isWindowMagnitude(String scalar) {
        return WINDOW_MAGNITUDE_SCALARS.contains(scalar);
    }

    /**
     * Short reason code if this inequality is a unit mismatch to drop, else empty. Fires for
     * {@code bounded_nt ≤ k·window_magnitude + c} with {@code k > 0}.
     */
    public static Optional<String> dropReason(Inequality ineq) {
        if (ineq == null) {
            return Optional.empty();
        }
        if (ineq.k() > ConjectureTrivialityFilter.K_EPSILON
                && isBoundedNumberTheoretic(ineq.lhs())
                && isWindowMagnitude(ineq.rhs())) {
            return Optional.of("bounded_nt_vs_window_magnitude");
        }
        return Optional.empty();
    }

    public static boolean isUnitMismatch(Inequality ineq) {
        return dropReason(ineq).isPresent();
    }
}
