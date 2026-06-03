package com.emergentterrain.research.conjecture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 30 Wave 3 — clade/lineage-value selection with pseudo-count smoothing (ext #2, #6).
 *
 * <p>The legacy investigation order ranks live conjectures by <em>fit</em> (most confirmed, tightest
 * slack), which keeps re-serving the same well-fit family. Wave 3 instead samples by
 * <strong>clade-value × inverse-popularity</strong>, treating fit as an eligibility gate only:
 * <ul>
 *   <li><b>Clade-value</b> — a conjecture is valuable if its <em>descendants</em> (the conjectures
 *       composed from it, via {@code derivedFrom} provenance) are productive. Productivity is the
 *       descendants' mean {@code confirmedRuns}, Laplace-smoothed by a pseudo-count so a node with no
 *       descendants falls back to a neutral prior rather than scoring 0. (Until iterated composition
 *       deepens the derivation graph most nodes have no descendants, so this term is ≈ the prior and
 *       the inverse-popularity term dominates; it strengthens as lineages grow.)</li>
 *   <li><b>Inverse-popularity</b> — {@code 1 / (1 + cellPopulation)} for the conjecture's descriptor
 *       cell (the caller supplies the cell key, e.g. the lhs variable). Down-weights over-represented
 *       cells so the queue covers diverse relations instead of clustering on one family.</li>
 * </ul>
 *
 * <p>Pure, stateless, deterministic (stable order; ties broken by confirmedRuns then key). The
 * {@code derivedFrom} format mirrors the miner: {@code "lhs|rhs  ∘  lhs|rhs"}, each token a
 * {@code keyOf} parent key. Flag-gated OFF via {@code app.research.conjectureMiner.cladeValueSelectionEnabled}.
 */
public final class CladeValueSelector {

    /** Prior mean productivity (confirmedRuns) for a node whose descendants we haven't observed. */
    static final double PRIOR_PRODUCTIVITY = 1.0;
    /** Pseudo-count strength of the prior (Laplace smoothing). */
    static final double PRIOR_WEIGHT = 1.0;

    private CladeValueSelector() {
    }

    public record Scored(Conjecture conjecture, double cladeValue, double inversePopularity, double score) {
    }

    private static String keyOf(Conjecture c) {
        return c.lhs() + "|" + c.rhs();
    }

    /** Score each candidate by clade-value × inverse-popularity (deterministic order). */
    public static List<Scored> score(List<Conjecture> candidates, Function<Conjecture, String> cellKey) {
        List<Scored> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return out;
        }
        // Map each parent key → the candidate keys derived from it (one composition step).
        Map<String, Conjecture> byKey = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (Conjecture c : candidates) {
            byKey.put(keyOf(c), c);
        }
        for (Conjecture c : candidates) {
            String df = c.derivedFrom();
            if (df == null || df.isBlank()) {
                continue;
            }
            for (String parent : df.split("∘")) {
                String pk = parent.trim();
                if (!pk.isEmpty()) {
                    children.computeIfAbsent(pk, k -> new ArrayList<>()).add(keyOf(c));
                }
            }
        }
        // Inverse-popularity cell populations.
        Map<String, Integer> cellPop = new HashMap<>();
        for (Conjecture c : candidates) {
            cellPop.merge(cellKey.apply(c), 1, Integer::sum);
        }

        for (Conjecture c : candidates) {
            double clade = cladeValue(keyOf(c), children, byKey);
            double invPop = 1.0 / (1.0 + cellPop.getOrDefault(cellKey.apply(c), 1));
            out.add(new Scored(c, clade, invPop, clade * invPop));
        }
        out.sort(Comparator.<Scored>comparingDouble(s -> -s.score())
                .thenComparingInt(s -> -s.conjecture().confirmedRuns())
                .thenComparing(s -> keyOf(s.conjecture())));
        return out;
    }

    /** Convenience: the candidates in selection order. */
    public static List<Conjecture> rank(List<Conjecture> candidates, Function<Conjecture, String> cellKey) {
        List<Conjecture> ranked = new ArrayList<>();
        for (Scored s : score(candidates, cellKey)) {
            ranked.add(s.conjecture());
        }
        return ranked;
    }

    /** Laplace-smoothed mean confirmedRuns over the transitive descendants of {@code key}. */
    private static double cladeValue(String key, Map<String, List<String>> children,
                                     Map<String, Conjecture> byKey) {
        long sum = 0;
        int count = 0;
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(key, List.of()));
        while (!queue.isEmpty()) {
            String d = queue.poll();
            if (!seen.add(d)) {
                continue;   // cycle / diamond guard
            }
            Conjecture dc = byKey.get(d);
            if (dc != null) {
                sum += dc.confirmedRuns();
                count++;
            }
            queue.addAll(children.getOrDefault(d, List.of()));
        }
        return (sum + PRIOR_PRODUCTIVITY * PRIOR_WEIGHT) / (count + PRIOR_WEIGHT);
    }
}
