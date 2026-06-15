package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 G4 within-bucket diversity — {@link ConjectureMinerService#diversifiedNextN}. Verifies the
 * family-rotation + live-priority-reachability that the bucket-only prior lacked (review-2026-06-15d:
 * max_prime_gap monoculture; dirichlet_error unreachable).
 */
class DiversifiedSelectionTest {

    private static Conjecture retired(String lhs, String rhs, Instant investigated) {
        Conjecture c = new Conjecture("math", lhs, rhs, 2.0, 0.0);
        c.setDroppedByKnob(ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE);
        c.setLastInvestigatedAt(investigated);
        return c;
    }

    private static Conjecture live(String lhs, String rhs) {
        return new Conjecture("math", lhs, rhs, 2.0, 0.0);   // droppedByKnob null = live
    }

    @Test
    void priorityTargetsBeatNonPriority() {
        List<Conjecture> pool = new ArrayList<>(List.of(
                retired("mean_omega", "deficient_density", null),     // rank 1 (not a priority target)
                retired("max_prime_gap", "prime_density", null)));    // rank 0 (priority)
        assertThat(ConjectureMinerService.diversifiedNextN(pool, 1))
                .singleElement().extracting(Conjecture::lhs).isEqualTo("max_prime_gap");
    }

    @Test
    void rotatesAcrossDistinctPriorityFamilies_noMonoculture() {
        // 19 max_prime_gap seeds (never investigated) vs 1 twin_vs_prime_sq seed — the legacy order
        // would serve max_prime_gap 19× before twin_vs_prime_sq. The family-rotation key must surface
        // twin_vs_prime_sq once max_prime_gap has been served (made recent).
        List<Conjecture> pool = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            pool.add(retired("max_prime_gap", "rhs" + i, null));   // family never investigated
        }
        pool.add(retired("twin_vs_prime_sq", "mertens_per_sqrt_W", null));

        // first pick: a rank-0 family (tie broken arbitrarily but deterministically)
        Conjecture first = ConjectureMinerService.diversifiedNextN(pool, 1).get(0);
        // simulate consumption: the served family is now recently investigated
        for (Conjecture c : pool) {
            if (c.lhs().equals(first.lhs())) {
                c.setLastInvestigatedAt(Instant.ofEpochMilli(1_000_000));
            }
        }
        // next pick must be the OTHER family (the just-served family sank)
        Conjecture second = ConjectureMinerService.diversifiedNextN(pool, 1).get(0);
        assertThat(second.lhs()).isNotEqualTo(first.lhs());
        assertThat(List.of(first.lhs(), second.lhs()))
                .containsExactlyInAnyOrder("max_prime_gap", "twin_vs_prime_sq");
    }

    @Test
    void liveOnlyPriorityTargetIsReachable_dirichletError() {
        // dirichlet_error exists ONLY as a live conjecture (0 retired seeds), behind many retired
        // priority seeds. The merged order must still reach it (the legacy tier-1-before-tier-2 could not).
        List<Conjecture> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // max_prime_gap retired AND recently investigated → its family sinks
            pool.add(retired("max_prime_gap", "rhs" + i, Instant.ofEpochMilli(9_000_000)));
        }
        pool.add(live("dirichlet_error", "self"));   // never investigated, live-only
        assertThat(ConjectureMinerService.diversifiedNextN(pool, 1))
                .singleElement().extracting(Conjecture::lhs).isEqualTo("dirichlet_error");
    }

    @Test
    void excludesNonServableDropReasonsAndEmptyIsSafe() {
        Conjecture knobDropped = new Conjecture("math", "max_prime_gap", "rhs", 2.0, 0.0);
        knobDropped.setDroppedByKnob("window_size");   // not counterexample, not live → excluded
        assertThat(ConjectureMinerService.diversifiedNextN(List.of(knobDropped), 1)).isEmpty();
        assertThat(ConjectureMinerService.diversifiedNextN(List.of(), 1)).isEmpty();
    }
}
