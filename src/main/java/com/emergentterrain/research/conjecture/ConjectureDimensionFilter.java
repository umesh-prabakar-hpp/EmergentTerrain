package com.emergentterrain.research.conjecture;

import java.util.Optional;
import java.util.Set;

/**
 * Phase 30 Wave 1 (phases-for-human-like-performance/phase-30-conjecture/
 * IMPLEMENTATION-ORDER.md) — dimensional-consistency gate. Complements
 * {@link ConjectureTrivialityFilter}; deterministic on {@code (lhs, rhs, k)}.
 *
 * <p>In a purely dimensionless math vocabulary a literal unit check has no
 * bite ({@code k} absorbs any scale), but there is a real <i>math
 * realization</i>: a {@code [0,1]} number-theoretic <b>density</b> bounded by a
 * harness <b>window-magnitude</b> function ({@code log_window_size},
 * {@code sqrt_window_size}, {@code log_window_start}) with {@code k>0} is a
 * dimensional category error — it compares a density to "how big a window the
 * harness happened to pick" — and is <i>eventually vacuous</i>: the
 * window-magnitude RHS grows without bound while the density LHS stays in
 * {@code [0,1]}, so the bound is trivially satisfied for large windows.
 *
 * <p>Scope is deliberately narrow and conservative:
 * <ul>
 *   <li>Only the {@code density ≤ k·window_magnitude + c} direction with
 *       {@code k>0} is flagged. The reverse ({@code window_magnitude ≤ k·density})
 *       is eventually <i>false</i>, which the refutation path already handles.</li>
 *   <li>{@code mertens_per_sqrt_W} is NOT a density (it carries
 *       {@code mertens_delta}), so the flagship mertens bound stays out of scope.</li>
 *   <li>{@code k≈0} constant bounds are out of scope — those are deduped by
 *       {@link ConjectureTrivialityFilter}, not a dimensional mismatch.</li>
 * </ul>
 *
 * <p>The scalar→class maps below are the math-domain realization; a future
 * physics/materials substrate would carry real units here. Wired flag-gated
 * OFF in {@link ConjectureMinerService} via
 * {@code app.research.conjectureMiner.dimensionFilterEnabled}.
 */
public final class ConjectureDimensionFilter {

    /** Number-theoretic densities — proper fractions in {@code [0,1]}. */
    public static final Set<String> DENSITY_SCALARS = Set.of(
            "abundant_density", "deficient_density", "prime_density", "happy_density");

    /** Harness window-magnitude functions (unbounded, grow with the chosen window). */
    public static final Set<String> WINDOW_MAGNITUDE_SCALARS =
            ConjectureTrivialityFilter.WINDOW_GEOMETRY_SCALARS;

    private ConjectureDimensionFilter() {}

    public static boolean isDensity(String scalar) {
        return DENSITY_SCALARS.contains(scalar);
    }

    public static boolean isWindowMagnitude(String scalar) {
        return WINDOW_MAGNITUDE_SCALARS.contains(scalar);
    }

    /**
     * Short reason code if this inequality is a dimensional mismatch that should
     * be dropped, else empty. Fires only for
     * {@code density ≤ k·window_magnitude + c} with {@code k > 0}.
     */
    public static Optional<String> dropReason(Inequality ineq) {
        if (ineq == null) {
            return Optional.empty();
        }
        if (ineq.k() > ConjectureTrivialityFilter.K_EPSILON
                && isDensity(ineq.lhs())
                && isWindowMagnitude(ineq.rhs())) {
            return Optional.of("density_vs_window_magnitude");
        }
        return Optional.empty();
    }

    public static boolean isDimensionalMismatch(Inequality ineq) {
        return dropReason(ineq).isPresent();
    }
}
