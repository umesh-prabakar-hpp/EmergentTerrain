package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 6 — unit tests for {@link DerivationClosure} (RAF / iRAF over
 * the derivation graph). Pure; constructed chains where food-grounding is
 * meaningful.
 */
class DerivationClosureTest {

    /** node(id, output, inputs...) */
    private static DerivationNode node(String id, String output, String... inputs) {
        return new DerivationNode(id, output, List.of(inputs));
    }

    @Test
    void maxRafIsTheFoodGroundedSet() {
        DerivationGraph g = new DerivationGraph()
                .add(node("p", "A", "x"))     // grounded by food x
                .add(node("q", "B", "A"))     // grounded by p's output
                .add(node("u", "E", "z"));    // z never producible -> ungrounded
        Set<String> raf = DerivationClosure.maxRAF(g, Set.of("x"));
        assertThat(raf).containsExactlyInAnyOrder("p", "q");
        assertThat(raf).doesNotContain("u");
    }

    @Test
    void irreducibleCoreOfALinearChainIsTheWholeChain() {
        DerivationGraph g = new DerivationGraph()
                .add(node("p", "A", "x"))
                .add(node("q", "B", "A"))
                .add(node("r", "D", "B"));    // target
        Set<String> core = DerivationClosure.irreducibleCore(g, Set.of("x"), "r");
        assertThat(core).containsExactlyInAnyOrder("p", "q", "r");   // every link load-bearing
    }

    @Test
    void irreducibleCoreDropsRedundantGroundingPaths() {
        DerivationGraph g = new DerivationGraph()
                .add(node("p", "A", "x"))     // long path: x -> A -> B
                .add(node("q", "B", "A"))
                .add(node("s", "B", "x"))     // short path: x -> B directly (redundant w/ p,q for B)
                .add(node("r", "D", "B"));    // target needs B
        Set<String> core = DerivationClosure.irreducibleCore(g, Set.of("x"), "r");
        // the short path (s) suffices; the long path (p,q) is redundant for grounding r
        assertThat(core).containsExactlyInAnyOrder("s", "r");
        assertThat(core).doesNotContain("p", "q");
    }

    @Test
    void ungroundedTargetHasEmptyCore() {
        DerivationGraph g = new DerivationGraph()
                .add(node("t", "E", "z"));    // z not in food, no producer
        assertThat(DerivationClosure.irreducibleCore(g, Set.of("x"), "t")).isEmpty();
        assertThat(DerivationClosure.irreducibleCore(g, Set.of("x"), "missing")).isEmpty();
    }

    @Test
    void axiomTargetCoreIsJustItself() {
        DerivationGraph g = new DerivationGraph()
                .add(node("ax", "A"))          // no inputs -> axiom, grounded by anything
                .add(node("other", "B", "A"));
        assertThat(DerivationClosure.irreducibleCore(g, Set.of(), "ax")).containsExactly("ax");
    }

    @Test
    void integratesWithComposerProvenanceChain() {
        // a <= 2*b ; b <= 3*c  ==>  a <= 6*c. Food = {c}. The composed finding's
        // core is the two parents + itself.
        DerivationGraph g = ConjectureComposer.derivationGraph(List.of(
                new Inequality("a", "b", 2.0, 0.0),
                new Inequality("b", "c", 3.0, 0.0)));
        String composed = ConjectureTrivialityFilter.descriptorKey(new Inequality("a", "c", 6.0, 0.0));
        String pAB = ConjectureTrivialityFilter.descriptorKey(new Inequality("a", "b", 2.0, 0.0));
        String pBC = ConjectureTrivialityFilter.descriptorKey(new Inequality("b", "c", 3.0, 0.0));

        // with food {c}: b grounded by pBC, a grounded by pAB; the composed a<=6c
        // node grounds directly off c, so its irreducible core is just itself.
        assertThat(DerivationClosure.maxRAF(g, Set.of("c")))
                .contains(composed, pAB, pBC);
        assertThat(DerivationClosure.irreducibleCore(g, Set.of("c"), composed))
                .containsExactly(composed);   // a<=6c needs only c (in food)
    }
}
