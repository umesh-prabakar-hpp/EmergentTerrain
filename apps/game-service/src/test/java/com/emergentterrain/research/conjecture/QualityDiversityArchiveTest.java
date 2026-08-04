package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 3 — unit tests for {@link QualityDiversityArchive}. Pure; no
 * Spring/DB. The generic tests use String items (cell = first char, quality =
 * length); the conjecture tests use the {@link QualityDiversityArchive#forConjectures}
 * binding.
 */
class QualityDiversityArchiveTest {

    private static QualityDiversityArchive<String> stringArchive() {
        return new QualityDiversityArchive<>(
                s -> s.substring(0, 1),                       // cell = first char
                Comparator.comparingInt(String::length));     // longer = better elite
    }

    @Test
    void keepsBestPerCellAndDistinctCellsCoexist() {
        QualityDiversityArchive<String> a = stringArchive();
        assertThat(a.offer("a1")).isTrue();      // new cell "a"
        assertThat(a.offer("a222")).isTrue();    // improves cell "a"
        assertThat(a.offer("a3")).isFalse();     // shorter -> rejected
        assertThat(a.offer("b1")).isTrue();      // new cell "b"

        assertThat(a.cellCount()).isEqualTo(2);
        assertThat(a.eliteFor("a")).contains("a222");
        assertThat(a.eliteFor("b")).contains("b1");
        assertThat(a.elites()).containsExactlyInAnyOrder("a222", "b1");
    }

    @Test
    void tiesKeepTheIncumbent() {
        QualityDiversityArchive<String> a = stringArchive();
        a.offer("a11");                          // length 3
        assertThat(a.offer("a22")).isFalse();    // also length 3 -> not strictly better
        assertThat(a.eliteFor("a")).contains("a11");
    }

    @Test
    void offerAllCountsAdmissions() {
        QualityDiversityArchive<String> a = stringArchive();
        // a-cell: a1 admitted, a222 admitted (improves), a3 rejected; b-cell: bb admitted
        int admitted = a.offerAll(List.of("a1", "a222", "a3", "bb"));
        assertThat(admitted).isEqualTo(3);
        assertThat(a.cellCount()).isEqualTo(2);
    }

    @Test
    void firstOccupiedCellOrderIsPreserved() {
        QualityDiversityArchive<String> a = stringArchive();
        a.offerAll(List.of("b1", "a1", "c1"));
        assertThat(a.elites()).containsExactly("b1", "a1", "c1");   // insertion order, deterministic
    }

    // ---- conjecture binding ----

    private static Inequality conj(String lhs, String rhs, double k, int confirmedRuns) {
        Inequality e = new Inequality(lhs, rhs, k, 0.0);
        e.confirmedRuns = confirmedRuns;   // package-private field, same-package test
        return e;
    }

    @Test
    void conjectureArchiveKeepsMostConfirmedPerRelation() {
        QualityDiversityArchive<Inequality> a = QualityDiversityArchive.forConjectures();
        a.offer(conj("abundant_density", "prime_density", 2.0, 3));   // same relation, 3 runs
        a.offer(conj("abundant_density", "prime_density", 2.0, 7));   // same relation, 7 runs -> wins
        a.offer(conj("prime_density", "happy_density", 2.0, 1));      // distinct relation -> new cell

        assertThat(a.cellCount()).isEqualTo(2);
        assertThat(a.eliteFor("abundant_density <= +k* prime_density"))
                .map(Inequality::confirmedRuns).contains(7);
    }

    @Test
    void degenerateConstantBoundsCollapseRhsAgnostically() {
        QualityDiversityArchive<Inequality> a = QualityDiversityArchive.forConjectures();
        // same constant bound on abundant_density restated against different rhs (k=0)
        a.offer(conj("abundant_density", "mertens_delta", 0.0, 2));
        a.offer(conj("abundant_density", "prime_density", 0.0, 5));
        assertThat(a.cellCount()).isEqualTo(1);                       // one "abundant_density <= const" cell
        assertThat(a.eliteFor("abundant_density <= const"))
                .map(Inequality::confirmedRuns).contains(5);
    }

    @Test
    void nullsAreSafe() {
        QualityDiversityArchive<String> a = stringArchive();
        assertThat(a.offer(null)).isFalse();
        assertThat(a.offerAll(null)).isZero();
        assertThat(a.eliteFor("nope")).isEmpty();
    }
}
