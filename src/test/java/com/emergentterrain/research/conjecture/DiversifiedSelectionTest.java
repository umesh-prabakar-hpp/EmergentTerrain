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
    void prefersTwoTermRowWithinAFamily_overRetiredOneTermSeeds() {
        // review-g Rec 2: within the same (least-recently-investigated) family, a LIVE two-term row must
        // be served BEFORE retired one-term seeds — otherwise the generated composite never surfaces.
        Conjecture retiredOneTerm = retired("max_prime_gap", "prime_density", null);
        Conjecture twoTerm = live("max_prime_gap", "log_sq_window_start");
        twoTerm.setRhs2("cramer_gap_ratio");
        twoTerm.setK2(2.0);
        // pool order deliberately puts the retired seed first to prove it's the comparator, not input order
        List<Conjecture> pool = new ArrayList<>(List.of(retiredOneTerm, twoTerm));
        Conjecture first = ConjectureMinerService.diversifiedNextN(pool, 1).get(0);
        assertThat(first.rhs2()).isEqualTo("cramer_gap_ratio");   // the two-term row won
    }

    @Test
    void excludesNonServableDropReasonsAndEmptyIsSafe() {
        Conjecture knobDropped = new Conjecture("math", "max_prime_gap", "rhs", 2.0, 0.0);
        knobDropped.setDroppedByKnob("window_size");   // not counterexample, not live → excluded
        assertThat(ConjectureMinerService.diversifiedNextN(List.of(knobDropped), 1)).isEmpty();
        assertThat(ConjectureMinerService.diversifiedNextN(List.of(), 1)).isEmpty();
    }

    @Test
    void servableOnly_keepsLiveAndCounterexample_dropsOtherKnobs() {
        Conjecture liveRow = live("dirichlet_error", "_oscillation_");
        Conjecture cex = retired("max_prime_gap", "prime_density", null);
        Conjecture nullDropped = new Conjecture("math", "dirichlet_error", "mertens_delta", 0.0, 2.34);
        nullDropped.setDroppedByKnob("stochastic_null:mcc_benjamini_hochberg");
        Conjecture fimDropped = new Conjecture("math", "dirichlet_error", "prime_density", 2.0, 2.16);
        fimDropped.setDroppedByKnob("fim:slope_unpinned");
        assertThat(ConjectureMinerService.servableOnly(
                List.of(liveRow, cex, nullDropped, fimDropped)))
                .containsExactly(liveRow, cex);
    }

    @Test
    void qdCollapseOverServablePool_cannotEvictAFamilysOnlyLiveRow() {
        // The 2026-07-04 G5-flagship blocker, replicated with nextN's exact QD cell key + elite
        // comparator: dirichlet_error's only servable row (k=0 live `_oscillation_`, cr=1) keys as the
        // degenerate cell "dirichlet_error <= const" — the SAME cell as the k=0 stochastic_null-dropped
        // mertens_delta row (cr=2). Offered the RAW pool, the non-servable row wins the cell and the
        // live row is evicted (the bug); offered the SERVABLE pool (the fix in nextN), the live row is
        // the cell's only occupant and survives.
        com.emergentterrain.research.conjecture.ConjectureMapper mapper =
                new ConjectureMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        Conjecture liveOsc = new Conjecture("math", "dirichlet_error", "_oscillation_", 0.0, 0.00157);
        liveOsc.setConfirmedRuns(1);
        Conjecture nullDropped = new Conjecture("math", "dirichlet_error", "mertens_delta", 0.0, 2.34);
        nullDropped.setDroppedByKnob("stochastic_null:mcc_benjamini_hochberg");
        nullDropped.setConfirmedRuns(2);

        java.util.function.Function<Conjecture, String> cellKey =
                c -> ScalarOpenness.selectionRank(c.lhs()) + ":"
                        + ConjectureTrivialityFilter.descriptorKey(mapper.toInequality(c));
        // Same degenerate cell — the collision that made the eviction possible.
        assertThat(cellKey.apply(liveOsc)).isEqualTo(cellKey.apply(nullDropped));

        // Raw pool: the higher-cr non-servable row wins the cell (documents the former bug's mechanism).
        QualityDiversityArchive<Conjecture> raw = new QualityDiversityArchive<>(
                cellKey, java.util.Comparator.comparingInt(Conjecture::confirmedRuns));
        raw.offerAll(List.of(liveOsc, nullDropped));
        assertThat(raw.elites()).containsExactly(nullDropped);

        // Servable pool (the fix): the live row survives and the family stays reachable.
        QualityDiversityArchive<Conjecture> fixed = new QualityDiversityArchive<>(
                cellKey, java.util.Comparator.comparingInt(Conjecture::confirmedRuns));
        fixed.offerAll(ConjectureMinerService.servableOnly(List.of(liveOsc, nullDropped)));
        assertThat(fixed.elites()).containsExactly(liveOsc);
        assertThat(ConjectureMinerService.diversifiedNextN(new ArrayList<>(fixed.elites()), 1))
                .singleElement().extracting(Conjecture::lhs).isEqualTo("dirichlet_error");
    }
}
