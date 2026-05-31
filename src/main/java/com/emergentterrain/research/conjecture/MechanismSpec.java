package com.emergentterrain.research.conjecture;

import java.util.Map;

/**
 * Phase 30.7 — the canonical, runnable form of a declared mechanism (the "DSL
 * lowered to IR"). A narrator emits a {@code (kind, params)} spec alongside the
 * prose; {@link MechanismVerifier} runs it. Numeric params keyed by name keep
 * the spec simple and schema-compatible:
 *
 * <ul>
 *   <li>{@code HEURISTIC_DERIVATION} — {@code predictedK}, {@code predictedC}
 *       (the constants the heuristic derives), optional {@code tolerance}.</li>
 *   <li>{@code PROBABILISTIC_MODEL} — {@code mu}, {@code sigma} (the model for
 *       the LHS statistic), {@code rhsValue} (a representative RHS at which to
 *       evaluate the bound), {@code coverage} (the claimed P(bound holds)),
 *       optional {@code samples}, {@code seed}.</li>
 * </ul>
 */
public record MechanismSpec(MechanismKind kind, Map<String, Double> params) {

    public MechanismSpec {
        kind = kind == null ? MechanismKind.NONE : kind;
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public Double param(String key) {
        return params.get(key);
    }

    public double param(String key, double dflt) {
        return params.getOrDefault(key, dflt);
    }
}
