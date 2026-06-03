package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 3 — unit tests for {@link CladeValueSelector} (clade-value × inverse-popularity).
 */
class CladeValueSelectorTest {

    private static Conjecture c(String lhs, String rhs, int confirmedRuns, String derivedFrom) {
        Conjecture e = new Conjecture("math", lhs, rhs, 1.0, 0.0);
        e.setConfirmedRuns(confirmedRuns);
        if (derivedFrom != null) {
            e.setDerivedFrom(derivedFrom);
        }
        return e;
    }

    @Test
    void inversePopularityDownweightsCrowdedCells() {
        // Five conjectures share lhs "mean_omega"; one stands alone on "prime_density". With no
        // derivedFrom edges every clade-value is the prior, so the rare cell wins on inverse-popularity.
        List<Conjecture> in = List.of(
                c("mean_omega", "a", 9, null),
                c("mean_omega", "b", 9, null),
                c("mean_omega", "c", 9, null),
                c("mean_omega", "d", 9, null),
                c("prime_density", "z", 1, null));   // lonely cell, low fit
        List<Conjecture> ranked = CladeValueSelector.rank(in, Conjecture::lhs);
        assertThat(ranked.get(0).lhs()).isEqualTo("prime_density");   // rare cell first, NOT highest fit
    }

    @Test
    void cladeValueRewardsProductiveDescendants() {
        // Two equally-lonely-cell parents; "parent_hi" has a highly-confirmed descendant, "parent_lo"
        // a barren one. Clade-value lifts parent_hi above parent_lo despite equal own-fit.
        Conjecture hi = c("parent_hi", "x", 0, null);
        Conjecture lo = c("parent_lo", "y", 0, null);
        Conjecture childHi = c("child_hi", "x", 40, "parent_hi|x");   // derived from parent_hi
        Conjecture childLo = c("child_lo", "y", 0, "parent_lo|y");
        List<CladeValueSelector.Scored> scored =
                CladeValueSelector.score(List.of(hi, lo, childHi, childLo), Conjecture::lhs);
        double hiClade = scored.stream().filter(s -> s.conjecture() == hi).findFirst().get().cladeValue();
        double loClade = scored.stream().filter(s -> s.conjecture() == lo).findFirst().get().cladeValue();
        assertThat(hiClade).isGreaterThan(loClade);
    }

    @Test
    void transitiveDescendantsCounted() {
        // grandparent -> parent -> child(confirmed); clade-value sees the grandchild productivity.
        Conjecture gp = c("gp", "x", 0, null);
        Conjecture p = c("p", "x", 0, "gp|x");
        Conjecture ch = c("ch", "x", 30, "p|x");
        var scored = CladeValueSelector.score(List.of(gp, p, ch), Conjecture::lhs);
        double gpClade = scored.stream().filter(s -> s.conjecture() == gp).findFirst().get().cladeValue();
        // mean over {p:0, ch:30} smoothed = (30 + 1*1)/(2+1) ≈ 10.33 > prior 1.0
        assertThat(gpClade).isGreaterThan(CladeValueSelector.PRIOR_PRODUCTIVITY);
    }

    @Test
    void deterministicAndStable() {
        List<Conjecture> in = List.of(
                c("a", "p", 5, null), c("b", "q", 5, null), c("c", "r", 5, null));
        assertThat(CladeValueSelector.rank(in, Conjecture::lhs))
                .containsExactlyElementsOf(CladeValueSelector.rank(in, Conjecture::lhs));
    }

    @Test
    void emptyAndNullSafe() {
        assertThat(CladeValueSelector.rank(List.of(), Conjecture::lhs)).isEmpty();
        assertThat(CladeValueSelector.rank(null, Conjecture::lhs)).isEmpty();
    }
}
