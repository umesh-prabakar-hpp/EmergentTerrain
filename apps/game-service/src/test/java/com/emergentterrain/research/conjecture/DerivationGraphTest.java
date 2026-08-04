package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 2 — unit tests for the derivation-graph substrate. Pure; the
 * round-trip uses a vanilla {@link ObjectMapper} to prove the {@code @JsonProperty}
 * record serialization works without the parameter-names module.
 */
class DerivationGraphTest {

    @Test
    void buildFromConjecturesGivesTypedPorts() {
        DerivationGraph g = DerivationGraph.fromConjectures(List.of(
                new Inequality("abundant_density", "prime_density", 2.0, 0.09),   // relational
                new Inequality("abundant_density", "mertens_delta", 0.0, 0.2515)  // k=0 axiom
        ));
        assertThat(g.size()).isEqualTo(2);

        DerivationNode relational = g.get("abundant_density <= +k* prime_density").orElseThrow();
        assertThat(relational.output()).isEqualTo("abundant_density");
        assertThat(relational.inputs()).containsExactly("prime_density");
        assertThat(relational.isAxiom()).isFalse();

        DerivationNode axiom = g.get("abundant_density <= const").orElseThrow();
        assertThat(axiom.inputs()).isEmpty();
        assertThat(axiom.isAxiom()).isTrue();   // k=0 constant bound = no input dependency
    }

    @Test
    void producersAndConsumersIndexPorts() {
        DerivationGraph g = new DerivationGraph()
                .add(new DerivationNode("n1", "A", List.of("x")))
                .add(new DerivationNode("n2", "B", List.of("A")))
                .add(new DerivationNode("n3", "A", List.of("y")));
        assertThat(g.producersOf("A")).extracting(DerivationNode::id)
                .containsExactlyInAnyOrder("n1", "n3");
        assertThat(g.consumersOf("A")).extracting(DerivationNode::id).containsExactly("n2");
        assertThat(g.producersOf("nope")).isEmpty();
    }

    @Test
    void lineageWalksDerivesFromTransitively() {
        // composition chain: A derives from B; B derives from C
        DerivationGraph g = new DerivationGraph()
                .add(new DerivationNode("C", "c", List.of(), List.of()))
                .add(new DerivationNode("B", "b", List.of("c"), List.of("C")))
                .add(new DerivationNode("A", "a", List.of("b"), List.of("B")));
        assertThat(g.lineage("A")).containsExactlyInAnyOrder("B", "C");
        assertThat(g.lineage("C")).isEmpty();
    }

    @Test
    void lineageIsCycleSafe() {
        DerivationGraph g = new DerivationGraph()
                .add(new DerivationNode("X", "x", List.of(), List.of("Y")))
                .add(new DerivationNode("Y", "y", List.of(), List.of("X")));
        assertThat(g.lineage("X")).containsExactlyInAnyOrder("X", "Y");   // terminates
    }

    @Test
    void groundedNodesForwardChainFromFood() {
        DerivationGraph g = new DerivationGraph()
                .add(new DerivationNode("n1", "A", List.of("x")))      // x in food -> grounded
                .add(new DerivationNode("n2", "B", List.of("A")))      // A produced by n1 -> grounded
                .add(new DerivationNode("n3", "D", List.of("E")));     // E never producible -> not grounded
        Set<String> grounded = g.groundedNodes(Set.of("x"));
        assertThat(grounded).containsExactlyInAnyOrder("n1", "n2");
        assertThat(grounded).doesNotContain("n3");
    }

    @Test
    void roundTripsThroughVanillaObjectMapper() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DerivationGraph g = new DerivationGraph()
                .add(new DerivationNode("A", "a", List.of("b"), List.of("B")))
                .add(new DerivationNode("B", "b", List.of(), List.of()));

        String json = g.toJson(om);
        DerivationGraph back = DerivationGraph.fromJson(om, json);

        assertThat(back.size()).isEqualTo(2);
        DerivationNode a = back.get("A").orElseThrow();
        assertThat(a.output()).isEqualTo("a");
        assertThat(a.inputs()).containsExactly("b");
        assertThat(a.derivesFrom()).containsExactly("B");
        assertThat(back.lineage("A")).containsExactly("B");
    }

    @Test
    void nullsAndEmptyAreSafe() {
        assertThat(DerivationGraph.fromConjectures(null).size()).isZero();
        assertThat(new DerivationGraph().lineage("missing")).isEmpty();
        assertThat(new DerivationGraph().groundedNodes(null)).isEmpty();
    }
}
