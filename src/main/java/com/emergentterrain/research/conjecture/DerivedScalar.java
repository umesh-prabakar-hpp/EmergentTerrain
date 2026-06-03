package com.emergentterrain.research.conjecture;

import java.util.Map;

/**
 * Phase 30 Wave 5 — 30.5 P3: an MDL-admitted derived scalar (a generated vocabulary term).
 *
 * <p>The product of {@link VocabularyExpansion}: a grammar candidate ({@link OperatorGrammar.Expr})
 * that earned its keep under {@link MdlGate}. The {@code name} is the expression's canonical render
 * (e.g. {@code "log(mean_divisor_count)"}), used as the scalar key in the extended vocabulary;
 * {@code gainBits} is its net MDL gain (for provenance / ranking).
 *
 * <p>Separate from {@link MathInvariants#BASE} by design — the admitted set is passed *into* the
 * parity-safe {@code vocabulary(admitted)} / {@code extract(..., admitted)} overloads, never mutates
 * the base registry, so with the feature off the substrate is byte-identical to today.
 */
public record DerivedScalar(String name, OperatorGrammar.Expr expr, double gainBits) {

    /** Evaluate this scalar on an already-flattened window map (post base/derived extraction). */
    public double eval(Map<String, Double> flatWindow) {
        return expr.eval(flatWindow);
    }
}
