package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 5 — compositional derivation (30.3, simulation-patterns #15
 * "compose + flatten"). Chains two bounds that share a port into a single
 * derived bound on a new pair, with provenance to both parents. The derived
 * finding's hypothesis is absent from either parent — a derivable-but-non-trivial
 * claim (Phase 30 Stage 1) — and it creates the {@code derivesFrom} edges that
 * make Wave-3 clade-value selection and Wave-6 RAF closure meaningful.
 *
 * <p><b>The math (direction-preserving substitution).</b> Given
 * {@code A <= k1*B + c1} with {@code k1 >= 0} and {@code B <= k2*C + c2}, since
 * {@code k1 >= 0} we have {@code k1*B <= k1*(k2*C + c2)}, hence
 * <pre>  A <= (k1*k2)*C + (k1*c2 + c1).</pre>
 * The result holds wherever both parents hold (a logical consequence), so it is
 * a sound upper bound — it is "flat" already (a single re-checkable inequality).
 *
 * <p>Guards: {@code k1 > 0} (so the shared port {@code B} is genuinely used and
 * the direction is preserved), the ports must match ({@code f1.rhs == f2.lhs}),
 * and {@code A != C} (no degenerate self-referential {@code A <= k*A + c}).
 * Trivial/near-vacuous results are left for the W1 gates + QD archive to judge.
 *
 * <p>Pure, deterministic; not wired into the live pipeline.
 */
public final class ConjectureComposer {

    private ConjectureComposer() {}

    /** A derived bound plus the descriptor keys of the two parents it was composed from. */
    public record Composition(Inequality derived, String parentKey1, String parentKey2) {

        /** The derived finding as a graph node with provenance to both parents. */
        public DerivationNode toNode() {
            List<String> inputs = ConjectureTrivialityFilter.isDegenerateConstant(derived)
                    ? List.of()
                    : List.of(derived.rhs());
            return new DerivationNode(
                    ConjectureTrivialityFilter.descriptorKey(derived),
                    derived.lhs(), inputs, List.of(parentKey1, parentKey2));
        }
    }

    /**
     * All sound 1-step compositions over the pool: for every ordered pair
     * {@code (f1, f2)} with {@code f1.rhs == f2.lhs}, {@code f1.k > 0} and
     * {@code f1.lhs != f2.rhs}, emit {@code f1.lhs <= (f1.k*f2.k)*f2.rhs +
     * (f1.k*f2.c + f1.c)} with provenance to both. Deterministic (input order).
     */
    public static List<Composition> compose(List<Inequality> pool) {
        List<Composition> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        for (Inequality f1 : pool) {
            if (f1 == null || f1.k() <= ConjectureTrivialityFilter.K_EPSILON) {
                continue;   // k1<=0: shared port B not used / direction not preserved
            }
            for (Inequality f2 : pool) {
                if (f2 == null || !f1.rhs().equals(f2.lhs())) {
                    continue;   // ports don't chain (need B = f1.rhs = f2.lhs)
                }
                String a = f1.lhs();
                String c = f2.rhs();
                if (a.equals(c)) {
                    continue;   // degenerate self-loop A <= k*A + c
                }
                double k = f1.k() * f2.k();
                double cc = f1.k() * f2.c() + f1.c();
                Inequality derived = new Inequality(a, c, k, cc);
                out.add(new Composition(derived,
                        ConjectureTrivialityFilter.descriptorKey(f1),
                        ConjectureTrivialityFilter.descriptorKey(f2)));
            }
        }
        return out;
    }

    /**
     * Sound 1-step compositions, made publishable: filtered through the W1 gates
     * ({@link ConjectureTrivialityFilter} window-geometry tautology +
     * {@link ConjectureDimensionFilter} density-vs-window-magnitude), deduped by
     * descriptor key (one per derived relation), and capped at {@code cap}
     * ({@code cap <= 0} = uncapped). The reusable "what novel relations can
     * composition add" step — used by the substrate-metrics integration and, once
     * flipped, by the persisting step. Deterministic.
     */
    public static List<Inequality> proposeNovel(List<Inequality> pool, int cap) {
        List<Inequality> out = new ArrayList<>();
        for (Composition comp : proposeNovelCompositions(pool, cap)) {
            out.add(comp.derived());
        }
        return out;
    }

    /**
     * Like {@link #proposeNovel}, but keeps the full {@link Composition} (derived
     * bound + both parent descriptor keys) so the persisting step can record
     * provenance. Same filtering (W1 gates), same dedup-by-descriptor (one per
     * derived relation, first parent pair wins), same cap semantics
     * ({@code cap <= 0} = uncapped), same deterministic order. {@link #proposeNovel}
     * is the derived-only projection of this. Deterministic.
     */
    public static List<Composition> proposeNovelCompositions(List<Inequality> pool, int cap) {
        Map<String, Composition> byKey = new LinkedHashMap<>();
        for (Composition comp : compose(pool)) {
            Inequality d = comp.derived();
            if (ConjectureTrivialityFilter.dropReason(d).isPresent()
                    || ConjectureDimensionFilter.dropReason(d).isPresent()) {
                continue;
            }
            byKey.putIfAbsent(ConjectureTrivialityFilter.descriptorKey(d), comp);
        }
        List<Composition> out = new ArrayList<>(byKey.values());
        if (cap > 0 && out.size() > cap) {
            return new ArrayList<>(out.subList(0, cap));
        }
        return out;
    }

    /**
     * Build a derivation graph from the pool and fold in 1-step compositions as
     * nodes with provenance — but only when the composed relation is new (a
     * descriptor key not already present), so mined findings keep their identity.
     */
    public static DerivationGraph derivationGraph(List<Inequality> pool) {
        DerivationGraph g = DerivationGraph.fromConjectures(pool);
        for (Composition comp : compose(pool)) {
            DerivationNode node = comp.toNode();
            if (g.get(node.id()).isEmpty()) {
                g.add(node);
            }
        }
        return g;
    }
}
