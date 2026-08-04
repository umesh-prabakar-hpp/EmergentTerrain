package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 5 — unit tests for {@link ConjectureComposer}. Pure; pins the
 * substitution math, the guards, and the provenance/graph integration.
 */
class ConjectureComposerTest {

    private static Inequality ineq(String lhs, String rhs, double k, double c) {
        return new Inequality(lhs, rhs, k, c);
    }

    @Test
    void composesChainedBoundsWithCorrectMath() {
        // A <= 2*B + 0.1 ; B <= 3*C + 0.2  ==>  A <= 6*C + 0.5
        Inequality f1 = ineq("abundant_density", "prime_density", 2.0, 0.1);
        Inequality f2 = ineq("prime_density", "happy_density", 3.0, 0.2);
        List<ConjectureComposer.Composition> comps = ConjectureComposer.compose(List.of(f1, f2));

        assertThat(comps).hasSize(1);
        Inequality d = comps.get(0).derived();
        assertThat(d.lhs()).isEqualTo("abundant_density");
        assertThat(d.rhs()).isEqualTo("happy_density");
        assertThat(d.k()).isEqualTo(6.0, within(1e-9));
        assertThat(d.c()).isEqualTo(0.5, within(1e-9));   // 2*0.2 + 0.1
        // hypothesis is absent from both parents (A-by-C, not A-by-B or B-by-C)
        assertThat(d.rhs()).isNotEqualTo(f1.rhs()).isNotEqualTo(f2.lhs());
    }

    @Test
    void provenanceNamesBothParents() {
        Inequality f1 = ineq("a", "b", 2.0, 0.0);
        Inequality f2 = ineq("b", "c", 1.0, 0.0);
        ConjectureComposer.Composition comp = ConjectureComposer.compose(List.of(f1, f2)).get(0);
        assertThat(comp.parentKey1()).isEqualTo(ConjectureTrivialityFilter.descriptorKey(f1));
        assertThat(comp.parentKey2()).isEqualTo(ConjectureTrivialityFilter.descriptorKey(f2));
        DerivationNode node = comp.toNode();
        assertThat(node.derivesFrom()).containsExactly(comp.parentKey1(), comp.parentKey2());
        assertThat(node.output()).isEqualTo("a");
        assertThat(node.inputs()).containsExactly("c");
    }

    @Test
    void requiresMatchingPorts() {
        // f1.rhs (b) != f2.lhs (x) -> no chain
        assertThat(ConjectureComposer.compose(List.of(
                ineq("a", "b", 2.0, 0.0), ineq("x", "c", 2.0, 0.0)))).isEmpty();
    }

    @Test
    void skipsZeroSlopeFirstBound() {
        // f1: a <= 0*b + c  -> b not used, direction not informative -> no composition
        assertThat(ConjectureComposer.compose(List.of(
                ineq("a", "b", 0.0, 0.5), ineq("b", "c", 2.0, 0.0)))).isEmpty();
    }

    @Test
    void skipsDegenerateSelfLoop() {
        // a <= 2*b + c ; b <= 3*a + c  ->  A==C  ->  skip a <= k*a + c
        assertThat(ConjectureComposer.compose(List.of(
                ineq("a", "b", 2.0, 0.0), ineq("b", "a", 3.0, 0.0)))).isEmpty();
    }

    @Test
    void negativeSecondSlopeStillSound() {
        // A <= 2*B + 0 ; B <= -1*C + 5  ==>  A <= -2*C + 10  (valid bound, kept)
        Inequality f1 = ineq("a", "b", 2.0, 0.0);
        Inequality f2 = ineq("b", "c", -1.0, 5.0);
        Inequality d = ConjectureComposer.compose(List.of(f1, f2)).get(0).derived();
        assertThat(d.k()).isEqualTo(-2.0, within(1e-9));
        assertThat(d.c()).isEqualTo(10.0, within(1e-9));
    }

    @Test
    void derivationGraphFoldsCompositionsWithProvenance() {
        Inequality f1 = ineq("a", "b", 2.0, 0.0);
        Inequality f2 = ineq("b", "c", 3.0, 0.0);
        DerivationGraph g = ConjectureComposer.derivationGraph(List.of(f1, f2));

        // 2 base nodes + 1 composed node (a <= +k* c)
        assertThat(g.size()).isEqualTo(3);
        String composedId = ConjectureTrivialityFilter.descriptorKey(ineq("a", "c", 6.0, 0.0));
        DerivationNode composed = g.get(composedId).orElseThrow();
        // lineage of the composed node reaches both parents
        assertThat(g.lineage(composedId)).containsExactlyInAnyOrder(
                ConjectureTrivialityFilter.descriptorKey(f1),
                ConjectureTrivialityFilter.descriptorKey(f2));
        assertThat(composed.derivesFrom()).hasSize(2);
    }

    @Test
    void nullPoolIsSafe() {
        assertThat(ConjectureComposer.compose(null)).isEmpty();
        assertThat(ConjectureComposer.derivationGraph(null).size()).isZero();
    }
}
