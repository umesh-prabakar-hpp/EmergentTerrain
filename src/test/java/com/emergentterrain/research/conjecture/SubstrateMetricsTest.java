package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 integration — unit tests for {@link SubstrateMetrics} and the
 * {@link ConjectureComposer#proposeNovel} step the metrics build on. Pure.
 */
class SubstrateMetricsTest {

    private static Inequality ineq(String lhs, String rhs, double k, double c) {
        return new Inequality(lhs, rhs, k, c);
    }

    @Test
    void countsRedundancyAndNovelCompositions() {
        // a<=2b, b<=3c  -> composable into a<=6c (novel, not in pool);
        // a duplicate of a<=2b adds redundancy.
        List<Inequality> pool = List.of(
                ineq("a", "b", 2.0, 0.0),
                ineq("a", "b", 2.0, 0.5),   // same relation (descriptor) -> redundant
                ineq("b", "c", 3.0, 0.0));
        SubstrateMetrics m = SubstrateMetrics.of(pool);

        assertThat(m.survivors()).isEqualTo(3);
        assertThat(m.distinctRelations()).isEqualTo(2);          // "a<=+k*b" and "b<=+k*c"
        assertThat(m.novelCompositions()).isEqualTo(1);          // a<=6c
        assertThat(m.summary()).contains("1 redundant").contains("1 novel");
    }

    @Test
    void noCompositionWhenNoPortsChain() {
        List<Inequality> pool = List.of(
                ineq("a", "b", 2.0, 0.0),
                ineq("x", "y", 2.0, 0.0));   // no shared port
        SubstrateMetrics m = SubstrateMetrics.of(pool);
        assertThat(m.distinctRelations()).isEqualTo(2);
        assertThat(m.novelCompositions()).isZero();
    }

    @Test
    void emptyAndNullPoolAreZero() {
        assertThat(SubstrateMetrics.of(null)).isEqualTo(new SubstrateMetrics(0, 0, 0));
        assertThat(SubstrateMetrics.of(List.of())).isEqualTo(new SubstrateMetrics(0, 0, 0));
    }

    @Test
    void proposeNovelFiltersTrivialAndDedupsAndCaps() {
        List<Inequality> pool = List.of(
                ineq("a", "b", 2.0, 0.0),
                ineq("b", "c", 3.0, 0.0),    // -> a<=6c
                ineq("a", "d", 2.0, 0.0),
                ineq("d", "e", 5.0, 0.0));   // -> a<=10e
        // two novel compositions available; cap to 1
        assertThat(ConjectureComposer.proposeNovel(pool, 0)).hasSize(2);
        assertThat(ConjectureComposer.proposeNovel(pool, 1)).hasSize(1);
    }

    @Test
    void proposeNovelCompositionsKeepsParentProvenanceAndMatchesProposeNovel() {
        // a<=2b, b<=3c -> a<=6c ; the Composition must name both parent descriptors,
        // and its derived bound must equal what proposeNovel returns (same filtering,
        // dedup, cap, order — proposeNovel is the derived-only projection).
        List<Inequality> pool = List.of(
                ineq("a", "b", 2.0, 0.0),
                ineq("b", "c", 3.0, 0.0));
        List<ConjectureComposer.Composition> comps =
                ConjectureComposer.proposeNovelCompositions(pool, 0);
        assertThat(comps).hasSize(1);
        ConjectureComposer.Composition c = comps.get(0);
        assertThat(c.derived().lhs()).isEqualTo("a");
        assertThat(c.derived().rhs()).isEqualTo("c");
        assertThat(c.derived().k()).isEqualTo(6.0);
        assertThat(c.parentKey1()).isEqualTo(ConjectureTrivialityFilter.descriptorKey(pool.get(0)));
        assertThat(c.parentKey2()).isEqualTo(ConjectureTrivialityFilter.descriptorKey(pool.get(1)));
        // projection equivalence
        List<Inequality> derived = ConjectureComposer.proposeNovel(pool, 0);
        assertThat(derived).hasSize(1);
        assertThat(derived.get(0).lhs()).isEqualTo(c.derived().lhs());
        assertThat(derived.get(0).rhs()).isEqualTo(c.derived().rhs());
        assertThat(derived.get(0).k()).isEqualTo(c.derived().k());
    }

    @Test
    void proposeNovelCompositionsCapMatchesProposeNovel() {
        List<Inequality> pool = List.of(
                ineq("a", "b", 2.0, 0.0),
                ineq("b", "c", 3.0, 0.0),    // -> a<=6c
                ineq("a", "d", 2.0, 0.0),
                ineq("d", "e", 5.0, 0.0));   // -> a<=10e
        assertThat(ConjectureComposer.proposeNovelCompositions(pool, 0)).hasSize(2);
        assertThat(ConjectureComposer.proposeNovelCompositions(pool, 1)).hasSize(1);
    }

    @Test
    void proposeNovelDropsDimensionMismatchCompositions() {
        // density <= 2*log_window_start (composed) would be a dimensional mismatch -> dropped.
        // build it: abundant_density <= 2*X ; X <= 1*log_window_start  -> abundant_density <= 2*log_window_start
        List<Inequality> pool = List.of(
                ineq("abundant_density", "mean_omega", 2.0, 0.0),
                ineq("mean_omega", "log_window_start", 1.0, 0.0));
        // composed: abundant_density <= 2*log_window_start  -> dimensional mismatch -> filtered out
        assertThat(ConjectureComposer.proposeNovel(pool, 0))
                .noneMatch(d -> d.lhs().equals("abundant_density") && d.rhs().equals("log_window_start"));
    }
}
