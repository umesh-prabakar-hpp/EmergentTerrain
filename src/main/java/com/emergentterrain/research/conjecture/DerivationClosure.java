package com.emergentterrain.research.conjecture;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Phase 30 Wave 6 — RAF closure / irreducibility (simulation-patterns #1/#2).
 * Over the {@link DerivationGraph} (findings-as-reactions, food = raw
 * observables), extracts the food-grounded self-supporting set ({@link #maxRAF})
 * and, for a target finding, its minimal load-bearing support
 * ({@link #irreducibleCore} = the iRAF) — the answer to "report the irreducible
 * derivation core as the publishable unit, not an isolated edge."
 *
 * <p><b>Scope honesty.</b> This model has no catalyst concept and derivation
 * graphs are acyclic, so RAF reduces to forward-grounding (CAF): a finding is in
 * the set iff its inputs are producible from food via the other findings. The
 * value is the irreducible-core analysis, which is non-trivial whenever a target
 * has more than one grounding path.
 *
 * <p>Pure, deterministic; not wired into the live pipeline.
 */
public final class DerivationClosure {

    private DerivationClosure() {}

    /**
     * Scalars producible from {@code food} using only the findings whose ids are
     * in {@code allowed} (null = all). Forward-chaining fixpoint; deterministic.
     */
    public static Set<String> producible(DerivationGraph g, Set<String> food, Set<String> allowed) {
        Set<String> have = new LinkedHashSet<>(food == null ? Set.of() : food);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DerivationNode n : g.nodes()) {
                if (allowed != null && !allowed.contains(n.id())) {
                    continue;
                }
                if (!have.contains(n.output()) && have.containsAll(n.inputs())) {
                    have.add(n.output());
                    changed = true;
                }
            }
        }
        return have;
    }

    /** maxRAF: the node ids grounded in {@code food} (the self-supporting set). */
    public static Set<String> maxRAF(DerivationGraph g, Set<String> food) {
        return g.groundedNodes(food);
    }

    /** True iff {@code targetId} is grounded in {@code food} using only {@code allowed} findings. */
    private static boolean grounds(DerivationGraph g, Set<String> food, Set<String> allowed, String targetId) {
        DerivationNode t = g.get(targetId).orElse(null);
        if (t == null || (allowed != null && !allowed.contains(targetId))) {
            return false;
        }
        return producible(g, food, allowed).containsAll(t.inputs());
    }

    /**
     * iRAF for a target: a minimal set of node ids (including the target) that
     * keeps {@code targetId} grounded in {@code food} — removing any member
     * ungrounds it. Empty if the target is not grounded by the full graph.
     * Greedy leave-one-out in node order; deterministic (one minimal core; a
     * distribution of cores would come from random-restart, deferred).
     */
    public static Set<String> irreducibleCore(DerivationGraph g, Set<String> food, String targetId) {
        Set<String> all = new LinkedHashSet<>();
        for (DerivationNode n : g.nodes()) {
            all.add(n.id());
        }
        if (!grounds(g, food, all, targetId)) {
            return Set.of();
        }
        Set<String> core = new LinkedHashSet<>(all);
        for (DerivationNode n : g.nodes()) {
            String id = n.id();
            if (id.equals(targetId)) {
                continue;   // the target itself is always load-bearing
            }
            Set<String> trial = new LinkedHashSet<>(core);
            trial.remove(id);
            if (grounds(g, food, trial, targetId)) {
                core = trial;   // id was redundant for grounding the target
            }
        }
        return core;
    }
}
