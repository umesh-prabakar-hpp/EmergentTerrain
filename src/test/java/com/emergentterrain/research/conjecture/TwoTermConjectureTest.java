package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 G2 Stage 1 — the two-term schema (lhs ≤ k1*rhs + k2*rhs2 + c) is plumbed through the
 * engine fit, mapper, update margin, and descriptor key, with the one-term path bit-identical
 * (rhs2==null, k2==0). Pure — no Spring, no DB.
 */
class TwoTermConjectureTest {

    private final ConjectureMapper mapper = new ConjectureMapper(new ObjectMapper());

    @Test
    void tightestK1K2C_returnsABoundThatHoldsEverySample() {
        double[] lhs  = {10, 14, 9, 16};
        double[] rhs  = {2, 3, 1, 4};
        double[] rhs2 = {1, 2, 1, 3};
        double[] fit = ConjectureEngine.tightestK1K2C(lhs, rhs, rhs2);
        assertThat(fit).isNotNull();
        double k1 = fit[0], k2 = fit[1], c = fit[2];
        for (int i = 0; i < lhs.length; i++) {
            assertThat(lhs[i]).isLessThanOrEqualTo(k1 * rhs[i] + k2 * rhs2[i] + c + 1e-9);
        }
    }

    @Test
    void tightestK1K2C_withZeroSecondTerm_reproducesOneTermFit() {
        double[] lhs = {10, 14, 9, 16};
        double[] rhs = {2, 3, 1, 4};
        double[] zero = {0, 0, 0, 0};
        double[] oneTerm = ConjectureEngine.tightestKC(lhs, rhs);
        double[] twoTerm = ConjectureEngine.tightestK1K2C(lhs, rhs, zero);
        assertThat(twoTerm[0]).isEqualTo(oneTerm[0]);   // same k1
        assertThat(twoTerm[2]).isEqualTo(oneTerm[1]);   // same c (k2 is free when rhs2≡0)
    }

    @Test
    void twoTermRoundtripsThroughMapper() {
        Inequality ineq = new Inequality("max_divisor_count", "mean_divisor_excess", 2.0, 5.0);
        ineq.setSecondTerm("dirichlet_error", 1.5);
        Conjecture e = mapper.toEntity(ineq, "math");
        assertThat(e.rhs2()).isEqualTo("dirichlet_error");
        assertThat(e.k2()).isEqualTo(1.5);
        Inequality back = mapper.toInequality(e);
        assertThat(back.isTwoTerm()).isTrue();
        assertThat(back.rhs2()).isEqualTo("dirichlet_error");
        assertThat(back.k2()).isEqualTo(1.5);
    }

    @Test
    void oneTermStaysOneTerm_backwardCompat() {
        Inequality back = mapper.toInequality(mapper.toEntity(new Inequality("a", "b", 1.0, 0.0), "math"));
        assertThat(back.isTwoTerm()).isFalse();
        assertThat(back.rhs2()).isNull();
        assertThat(back.k2()).isEqualTo(0.0);
    }

    @Test
    void updateUsesTheSecondTermInTheMargin() {
        // lhsX ≤ 1*rhsA + 1*rhsB + 0; a window with lhsX=100 ≫ rhsA+rhsB=2 must REFUTE (be retired).
        Inequality two = new Inequality("lhsX", "rhsA", 1.0, 0.0);
        two.setSecondTerm("rhsB", 1.0);
        List<Inequality> store = new ArrayList<>(List.of(two));
        Map<String, Double> s = new HashMap<>();
        s.put("lhsX", 100.0);
        s.put("rhsA", 1.0);
        s.put("rhsB", 1.0);
        s.put("_tick", 0.0);
        ConjectureEngine.update(store, "run1", "W1", List.of(s));
        assertThat(store).isEmpty();   // refuted on the two-term margin → removed
    }

    @Test
    void descriptorKeyDistinguishesTwoTermFromOneTerm() {
        Inequality one = new Inequality("max_divisor_count", "mean_divisor_excess", 2.0, 5.0);
        Inequality two = new Inequality("max_divisor_count", "mean_divisor_excess", 2.0, 5.0);
        two.setSecondTerm("dirichlet_error", 1.5);
        assertThat(ConjectureTrivialityFilter.descriptorKey(one))
                .isNotEqualTo(ConjectureTrivialityFilter.descriptorKey(two));
        // one-term key is unchanged from the legacy format (backward-compat)
        assertThat(ConjectureTrivialityFilter.descriptorKey(one))
                .isEqualTo("max_divisor_count <= +k* mean_divisor_excess");
    }
}
