package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 30 Wave 1 (phases-for-human-like-performance/phase-30-conjecture/
 * IMPLEMENTATION-ORDER.md) — structural triviality + dedup for freshly-seeded
 * inequalities, complementing the confirmation-based knob filter
 * ({@link ConjectureEngine#filterKnobRestatements}). Deterministic on
 * {@code (lhs, rhs, k, c)} alone; pure and stateless.
 *
 * <p>Two concerns:
 * <ul>
 *   <li><b>Window-geometry drop.</b> Two content-free shapes are dropped:
 *       (a) a <b>tautology</b> — BOTH sides are monotone transforms of the harness
 *       window parameters ({@code log_window_size}, {@code sqrt_window_size},
 *       {@code log_window_start}), relating the window the harness chose rather than
 *       the data; and (b) a <b>geometry subject</b> — the LHS (the quantity being
 *       bounded) is a window-geometry scalar, whatever the RHS. A window coordinate is
 *       a free harness input, not a measured quantity, so it cannot be the meaningful
 *       subject of an upper bound (e.g. {@code log_window_start <= 2*max_divisor_count
 *       - 168.2258}, room b72c4e10 P-b72c-87, is degenerate). A geometry scalar on the
 *       RHS is still allowed — it can legitimately bound a content scalar, as in the
 *       binding-regime probe's {@code mertens_per_sqrt_W <= 2*log_window_start + c}
 *       (see {@link BindingRegimeProbe}). (Note {@code mertens_per_sqrt_W} carries
 *       {@code mertens_delta}, so it is NOT geometry; the flagship mertens bound is out
 *       of scope here.)</li>
 *   <li><b>Redundant restatement (dedup, not drop).</b> A degenerate
 *       {@code k=0} bound {@code lhs <= 0*rhs + c} does not depend on
 *       {@code rhs}, so the same constant bound on {@code lhs} is re-emitted
 *       against every irrelevant {@code rhs}. These collapse to one
 *       representative (the tightest {@code c}); the rest are redundant. A k=0
 *       constant bound is NOT itself trivial — e.g.
 *       {@code abundant_density <= 0.2515} is the real density bound — so it is
 *       deduped, never dropped.</li>
 * </ul>
 *
 * <p>Wired flag-gated OFF in {@link ConjectureMinerService} via
 * {@code app.research.conjectureMiner.trivialityFilterEnabled}. The
 * {@link #descriptorKey} primitive is also the key the Phase 30 Wave-3
 * quality-diversity archive will bin on.
 */
public final class ConjectureTrivialityFilter {

    /**
     * Scalars computed purely from harness window metadata — see
     * {@link MathInvariants} DERIVED: {@code log10}/{@code sqrt} of
     * {@code _window_size} / {@code _window_start}.
     */
    public static final Set<String> WINDOW_GEOMETRY_SCALARS =
            Set.of("log_window_size", "sqrt_window_size", "log_window_start");

    /** {@code |k|} at or below this counts as a degenerate constant bound (k=0). */
    public static final double K_EPSILON = 1e-9;

    private ConjectureTrivialityFilter() {}

    /** True iff both sides are window-geometry transforms (content-free). */
    public static boolean isWindowGeometryTautology(Inequality ineq) {
        return ineq != null
                && WINDOW_GEOMETRY_SCALARS.contains(ineq.lhs())
                && WINDOW_GEOMETRY_SCALARS.contains(ineq.rhs());
    }

    /**
     * True iff the LHS — the quantity being bounded — is a window-geometry scalar.
     * A window coordinate is a free harness input, not a measured quantity, so it
     * cannot be the meaningful SUBJECT of an upper bound, whatever the RHS. (A
     * geometry scalar on the RHS is fine and not flagged here — it can bound a content
     * scalar; see {@link BindingRegimeProbe}.) Subsumes {@link #isWindowGeometryTautology}.
     */
    public static boolean isWindowGeometrySubject(Inequality ineq) {
        return ineq != null && WINDOW_GEOMETRY_SCALARS.contains(ineq.lhs());
    }

    /** True iff {@code k≈0} — a constant ceiling on lhs, independent of rhs. */
    public static boolean isDegenerateConstant(Inequality ineq) {
        return ineq != null && Math.abs(ineq.k()) <= K_EPSILON;
    }

    /**
     * Short reason code if this inequality should be DROPPED as structurally
     * trivial, else empty. A degenerate-constant bound is NOT a drop — it is
     * deduped via {@link #descriptorKey}.
     */
    public static Optional<String> dropReason(Inequality ineq) {
        if (isWindowGeometryTautology(ineq)) {
            return Optional.of("window_geometry");
        }
        if (isWindowGeometrySubject(ineq)) {
            return Optional.of("window_geometry_lhs");
        }
        // 2026-06-06 review rec #2 — non-finite mined parameters. P-b72c-237
        // was served c = −Infinity (an unsatisfiable bound, trivially REFUTED
        // every window); a NaN/±Inf k or c can never express a real claim.
        if (ineq != null && (!Double.isFinite(ineq.k()) || !Double.isFinite(ineq.c()))) {
            return Optional.of("nonfinite_parameter");
        }
        // 2026-06-06 review rec #1 — derived-vs-component pairing. A derived
        // scalar bounded against (or bounding) one of its own definition's
        // components is structurally coupled whatever the parameters:
        // P-b72c-236 scored a record 0.888 on the identity a−d ≈ 2a−1.
        // Symmetric, and lhs==rhs is the degenerate case of the same rule.
        if (isComponentPairing(ineq)) {
            return Optional.of("derived_component");
        }
        return Optional.empty();
    }

    /** True iff lhs==rhs, or either side's DERIVED definition references the other. */
    static boolean isComponentPairing(Inequality ineq) {
        if (ineq == null || ineq.lhs() == null || ineq.rhs() == null) return false;
        if (ineq.lhs().equals(ineq.rhs())) return true;
        return MathInvariants.DERIVED_COMPONENTS
                        .getOrDefault(ineq.lhs(), Set.of()).contains(ineq.rhs())
                || MathInvariants.DERIVED_COMPONENTS
                        .getOrDefault(ineq.rhs(), Set.of()).contains(ineq.lhs());
    }

    /**
     * Canonical "same conjecture" descriptor. {@code k=0} bounds are
     * rhs-agnostic (the rhs is irrelevant), so they collapse across rhs; other
     * bounds key on {@code (lhs, sign-of-k, rhs)}. The intercept {@code c} is
     * excluded — {@code k} snaps to 2.0/0.0 and {@code c} is the only free fit,
     * so two re-mines of the same bound share a key.
     */
    public static String descriptorKey(Inequality ineq) {
        if (isDegenerateConstant(ineq)) {
            return ineq.lhs() + " <= const";
        }
        String dir = ineq.k() >= 0 ? "+" : "-";
        return ineq.lhs() + " <= " + dir + "k* " + ineq.rhs();
    }

    /** Survivors plus the entries removed, each with a reason code. */
    public record Reduction(List<Inequality> kept, List<Removed> removed) {}

    /** One removed inequality and why ({@code triviality:*} or {@code dedup:*}). */
    public record Removed(Inequality inequality, String reason) {}

    /**
     * Drop window-geometry tautologies, then collapse redundant restatements to
     * one representative (tightest {@code c}) per {@link #descriptorKey}.
     * First-occurrence ordering is preserved; fully deterministic.
     */
    public static Reduction reduce(List<Inequality> store) {
        List<Inequality> kept = new ArrayList<>();
        List<Removed> removed = new ArrayList<>();
        if (store == null || store.isEmpty()) {
            return new Reduction(kept, removed);
        }
        // 1. drop content-free window-geometry tautologies
        List<Inequality> afterDrop = new ArrayList<>();
        for (Inequality ineq : store) {
            Optional<String> r = dropReason(ineq);
            if (r.isPresent()) {
                removed.add(new Removed(ineq, "triviality:" + r.get()));
            } else {
                afterDrop.add(ineq);
            }
        }
        // 2. dedup: keep the tightest c per descriptor key, retire the rest
        Map<String, Inequality> representative = new LinkedHashMap<>();
        for (Inequality ineq : afterDrop) {
            String key = descriptorKey(ineq);
            Inequality cur = representative.get(key);
            if (cur == null) {
                representative.put(key, ineq);
            } else if (ineq.c() < cur.c()) {
                removed.add(new Removed(cur, "dedup:redundant_restatement"));
                representative.put(key, ineq);
            } else {
                removed.add(new Removed(ineq, "dedup:redundant_restatement"));
            }
        }
        kept.addAll(representative.values());
        return new Reduction(kept, removed);
    }
}
