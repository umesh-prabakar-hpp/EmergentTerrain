package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 1 — unit tests for {@link ConjectureTrivialityFilter}. Pure,
 * no Spring/DB/sidecar. Pins the two design decisions that came out of the
 * existing miner tests: window-geometry tautologies are dropped; degenerate
 * k=0 bounds are DEDUPED (rhs-agnostic), never dropped, so legitimate constant
 * density bounds (e.g. abundant_density <= 0.2515) survive as the representative.
 */
class ConjectureTrivialityFilterTest {

    private static Inequality ineq(String lhs, String rhs, double k, double c) {
        return new Inequality(lhs, rhs, k, c);
    }

    // ---- window-geometry tautology (drop) ----

    @Test
    void bothSidesWindowGeometryIsDropped() {
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("log_window_start", "log_window_size", 2.0, -1.0)))
                .contains("window_geometry");
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("log_window_size", "sqrt_window_size", 2.0, 0.0)))
                .contains("window_geometry");
    }

    @Test
    void oneNumberTheoreticSideIsNotDropped() {
        // density bounded by a window-geometry scalar is trivial-ish but NOT a
        // pure tautology — keep it (a later wave judges it). The geometry scalar is on
        // the RHS (a ceiling), not the subject, so the geometry-subject rule is silent.
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("abundant_density", "log_window_size", 2.0, -7.6))).isEmpty();
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("abundant_density", "prime_density", 2.0, 0.09))).isEmpty();
    }

    @Test
    void windowGeometrySubjectIsDroppedEvenWithContentRhs() {
        // Room b72c4e10 Rec 3: a window coordinate as the LHS (the bounded quantity) is
        // degenerate whatever the RHS — e.g. P-b72c-87 log_window_start <= 2*max_divisor_count.
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("log_window_start", "max_divisor_count", 2.0, -168.2258)))
                .contains("window_geometry_lhs");
        assertThat(ConjectureTrivialityFilter.isWindowGeometrySubject(
                ineq("sqrt_window_size", "mean_divisor_count", 2.0, 0.0))).isTrue();
    }

    @Test
    void geometryScalarOnRhsIsNotASubjectDrop() {
        // A geometry scalar bounding a CONTENT lhs is allowed (binding-regime probe case);
        // the geometry-subject rule must not fire on the RHS.
        assertThat(ConjectureTrivialityFilter.isWindowGeometrySubject(
                ineq("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621))).isFalse();
        assertThat(ConjectureTrivialityFilter.dropReason(
                ineq("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621))).isEmpty();
    }

    @Test
    void mertensBoundIsNotGeometryTautology() {
        // mertens_per_sqrt_W carries mertens_delta, so it is NOT geometry; the
        // flagship bound must stay out of this filter's scope.
        Inequality mertens = ineq("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621);
        assertThat(ConjectureTrivialityFilter.isWindowGeometryTautology(mertens)).isFalse();
        assertThat(ConjectureTrivialityFilter.dropReason(mertens)).isEmpty();
    }

    // ---- degenerate constant: deduped, NOT dropped ----

    @Test
    void degenerateConstantIsNotDroppedAndIsRhsAgnostic() {
        Inequality a = ineq("abundant_density", "mertens_delta", 0.0, 0.2515);
        Inequality b = ineq("abundant_density", "prime_density", 0.0, 0.2600);
        assertThat(ConjectureTrivialityFilter.isDegenerateConstant(a)).isTrue();
        assertThat(ConjectureTrivialityFilter.dropReason(a)).isEmpty();   // not a drop
        // rhs-agnostic descriptor: both collapse to the same constant-bound key
        assertThat(ConjectureTrivialityFilter.descriptorKey(a))
                .isEqualTo("abundant_density <= const")
                .isEqualTo(ConjectureTrivialityFilter.descriptorKey(b));
    }

    @Test
    void nonZeroDescriptorKeysOnLhsSignRhs() {
        assertThat(ConjectureTrivialityFilter.descriptorKey(
                ineq("abundant_density", "prime_density", 2.0, 0.09)))
                .isEqualTo("abundant_density <= +k* prime_density");
        // intercept c is excluded — two re-mines of the same bound share a key
        assertThat(ConjectureTrivialityFilter.descriptorKey(ineq("a", "b", 2.0, 0.09)))
                .isEqualTo(ConjectureTrivialityFilter.descriptorKey(ineq("a", "b", 2.0, -3.14)));
    }

    // ---- reduce(): drop + dedup together ----

    @Test
    void reduceCollapsesRedundantConstantBoundsToTightest() {
        // same constant bound on abundant_density restated against 3 irrelevant rhs
        List<Inequality> store = List.of(
                ineq("abundant_density", "mertens_delta", 0.0, 0.2600),
                ineq("abundant_density", "prime_density", 0.0, 0.2515),   // tightest
                ineq("abundant_density", "happy_density", 0.0, 0.2700));
        ConjectureTrivialityFilter.Reduction red = ConjectureTrivialityFilter.reduce(store);

        assertThat(red.kept()).hasSize(1);
        assertThat(red.kept().get(0).c()).isEqualTo(0.2515);             // tightest survives
        assertThat(red.removed()).hasSize(2);
        assertThat(red.removed()).allSatisfy(
                r -> assertThat(r.reason()).isEqualTo("dedup:redundant_restatement"));
    }

    @Test
    void reduceDropsGeometryTautologyAndKeepsRealRelations() {
        List<Inequality> store = List.of(
                ineq("log_window_start", "log_window_size", 2.0, -1.0),   // tautology -> drop
                ineq("abundant_density", "prime_density", 2.0, 0.09),     // real -> keep
                ineq("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621)); // keep
        ConjectureTrivialityFilter.Reduction red = ConjectureTrivialityFilter.reduce(store);

        assertThat(red.kept()).extracting(Inequality::lhs)
                .containsExactlyInAnyOrder("abundant_density", "mertens_per_sqrt_W");
        assertThat(red.removed()).hasSize(1);
        assertThat(red.removed().get(0).reason()).isEqualTo("triviality:window_geometry");
    }

    @Test
    void reduceIsDeterministicAndPreservesFirstOccurrenceOrder() {
        List<Inequality> store = List.of(
                ineq("prime_density", "happy_density", 2.0, 0.05),
                ineq("abundant_density", "prime_density", 2.0, 0.09));
        ConjectureTrivialityFilter.Reduction red = ConjectureTrivialityFilter.reduce(store);
        assertThat(red.kept()).extracting(Inequality::lhs)
                .containsExactly("prime_density", "abundant_density");
        assertThat(red.removed()).isEmpty();
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(ConjectureTrivialityFilter.reduce(null).kept()).isEmpty();
        assertThat(ConjectureTrivialityFilter.reduce(List.of()).removed()).isEmpty();
        assertThat(ConjectureTrivialityFilter.dropReason(null)).isEmpty();
        assertThat(ConjectureTrivialityFilter.isDegenerateConstant(null)).isFalse();
    }
}
