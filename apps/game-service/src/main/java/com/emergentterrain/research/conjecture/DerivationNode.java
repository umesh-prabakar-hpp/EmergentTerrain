package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Phase 30 Wave 2 — one finding in the {@link DerivationGraph}. A finding
 * constrains an {@code output} scalar given its {@code inputs} (its typed
 * ports), and may be DERIVED-FROM other findings ({@code derivesFrom}
 * provenance).
 *
 * <p>For a mined inequality {@code lhs <= k*rhs + c}: {@code output = lhs},
 * {@code inputs = [rhs]} — empty for a {@code k=0} constant bound, which is an
 * axiom with no dependency. Composition (Wave 5) creates nodes whose inputs are
 * other findings' outputs and whose {@code derivesFrom} names the parents; RAF
 * closure (Wave 6) and clade-value selection (Wave 3) traverse this structure.
 *
 * <p>{@code @JsonProperty} on the components makes the record round-trip through
 * a vanilla {@code ObjectMapper} (no parameter-names module required).
 */
public record DerivationNode(
        @JsonProperty("id") String id,
        @JsonProperty("output") String output,
        @JsonProperty("inputs") List<String> inputs,
        @JsonProperty("derivesFrom") List<String> derivesFrom) {

    public DerivationNode {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        derivesFrom = derivesFrom == null ? List.of() : List.copyOf(derivesFrom);
    }

    /** Convenience overload for a primitive/relational finding with no provenance. */
    public DerivationNode(String id, String output, List<String> inputs) {
        this(id, output, inputs, List.of());
    }

    /** True iff this finding has no input dependency (a primitive/axiom). Derived, not serialized. */
    @JsonIgnore
    public boolean isAxiom() {
        return inputs.isEmpty();
    }
}
