package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 6 — unit tests for {@link RafReport} (sim #1 RAF-closure reporting).
 */
class RafReportTest {

    private static Inequality reaction(String produces, String from) {
        return new Inequality(produces, from, 1.0, 0.0);   // node: output=lhs, input=[rhs]
    }

    @Test
    void chainIsGroundedAndIRafTracksTheSupportDepth() {
        // A (food) -> B -> C. Both B and C are grounded; C's iRAF needs B (and itself).
        List<Inequality> pool = List.of(reaction("B", "A"), reaction("C", "B"));
        RafReport.Report r = RafReport.of(pool, Set.of("A"), pool);

        assertThat(r.findings()).isEqualTo(2);
        assertThat(r.maxRafSize()).isEqualTo(2);
        assertThat(r.ungrounded()).isZero();

        int cCore = r.cores().stream()
                .filter(c -> c.target().contains("C ")).findFirst().orElseThrow().size();
        int bCore = r.cores().stream()
                .filter(c -> c.target().contains("B ")).findFirst().orElseThrow().size();
        assertThat(cCore).isEqualTo(2);   // C needs B + itself
        assertThat(bCore).isEqualTo(1);   // B's rhs is food → it alone is load-bearing
    }

    @Test
    void findingWithNonFoodUnproducibleInputIsUngrounded() {
        // D <- X, where X is neither food nor produced by any finding → D is in no RAF.
        List<Inequality> pool = List.of(reaction("B", "A"), reaction("C", "B"), reaction("D", "X"));
        RafReport.Report r = RafReport.of(pool, Set.of("A"), pool);
        assertThat(r.findings()).isEqualTo(3);
        assertThat(r.maxRafSize()).isEqualTo(2);
        assertThat(r.ungrounded()).isEqualTo(1);
    }

    @Test
    void directBoundsOverFoodAreTriviallyGrounded() {
        // Two independent direct bounds, both rhs in food → all grounded, each iRAF = {itself}.
        List<Inequality> pool = List.of(reaction("mean_omega", "prime_density"),
                reaction("mean_Omega", "prime_density"));
        RafReport.Report r = RafReport.of(pool, Set.of("prime_density"), pool);
        assertThat(r.ungrounded()).isZero();
        assertThat(r.cores()).allMatch(c -> c.size() == 1);
    }

    @Test
    void emptyAndNullSafe() {
        assertThat(RafReport.of(List.of(), Set.of("A"), List.of()).findings()).isZero();
        assertThat(RafReport.of(null, Set.of("A"), null).findings()).isZero();
    }
}
