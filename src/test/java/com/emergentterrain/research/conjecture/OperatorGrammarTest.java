package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 5 — unit tests for {@link OperatorGrammar} (30.5 P1, candidate generation).
 */
class OperatorGrammarTest {

    // prime_density = bounded-NT, mean_omega = growing-NT (neither set), log_window_size = window-magnitude.
    private static final List<String> SCALARS = List.of("prime_density", "mean_omega", "log_window_size");

    private static List<String> renders(int depth) {
        return OperatorGrammar.enumerate(SCALARS, depth).stream().map(OperatorGrammar.Expr::render).toList();
    }

    @Test
    void depthOneEnumeratesUnaryAndBinaryWithExpectedCount() {
        List<String> r = renders(1);
        // 4 unary × 3 scalars = 12; binary over the 3 pairs: (prime_density,mean_omega) and
        // (mean_omega,log_window_size) each give 1 MUL + 2 DIV = 3; (prime_density,log_window_size)
        // is dropped by the dimension guard. 12 + 3 + 3 = 18.
        assertThat(r).hasSize(18);
        assertThat(r).contains("log(prime_density)", "reciprocal(mean_omega)",
                "(mean_omega * prime_density)", "(mean_omega / log_window_size)");
    }

    @Test
    void dimensionGuardDropsBoundedTimesWindow() {
        List<String> r = renders(1);
        // prime_density (bounded-NT) with log_window_size (window-magnitude) must never appear together.
        assertThat(r).noneMatch(s -> s.contains("prime_density") && s.contains("log_window_size"));
    }

    @Test
    void noSelfDegeneracies() {
        List<String> r = renders(1);
        assertThat(r).doesNotContain("(prime_density * prime_density)", "(prime_density / prime_density)");
    }

    @Test
    void commutativeMultiplyIsCanonicalised() {
        List<String> r = renders(1);
        // exactly one ordering of the product is emitted (alphabetical by render).
        assertThat(r).contains("(mean_omega * prime_density)");
        assertThat(r).doesNotContain("(prime_density * mean_omega)");
    }

    @Test
    void baseScalarsAreNotReEmitted() {
        assertThat(renders(1)).doesNotContainAnyElementsOf(SCALARS);
    }

    @Test
    void allDepthOneExpressionsHaveDepthOne() {
        assertThat(OperatorGrammar.enumerate(SCALARS, 1)).allMatch(e -> e.depth() == 1);
    }

    @Test
    void depthTwoComposesAndStaysDeterministicAndDeduped() {
        List<String> r2a = renders(2);
        List<String> r2b = renders(2);
        assertThat(r2a).isEqualTo(r2b);                 // deterministic
        assertThat(r2a).doesNotHaveDuplicates();        // deduped
        assertThat(r2a.size()).isGreaterThan(renders(1).size());   // composition adds candidates
        assertThat(r2a).contains("log(log(mean_omega))");          // a depth-2 example
    }

    @Test
    void evalAppliesOperatorsAndGuards() {
        OperatorGrammar.Expr logPd = OperatorGrammar.Expr.unary(
                OperatorGrammar.Unary.LOG, OperatorGrammar.Expr.leaf("prime_density"));
        assertThat(logPd.eval(Map.of("prime_density", 0.1))).isCloseTo(Math.log(0.1), within(1e-9));
        // log of a non-positive value → NaN
        assertThat(logPd.eval(Map.of("prime_density", 0.0))).isNaN();

        OperatorGrammar.Expr ratio = OperatorGrammar.Expr.binary(OperatorGrammar.Binary.DIV,
                OperatorGrammar.Expr.leaf("a"), OperatorGrammar.Expr.leaf("b"));
        assertThat(ratio.eval(Map.of("a", 6.0, "b", 3.0))).isCloseTo(2.0, within(1e-9));
        assertThat(ratio.eval(Map.of("a", 6.0, "b", 0.0))).isNaN();      // divide by zero
        assertThat(ratio.eval(Map.of("a", 6.0))).isNaN();               // missing operand
    }

    @Test
    void emptyAndNullSafe() {
        assertThat(OperatorGrammar.enumerate(List.of(), 1)).isEmpty();
        assertThat(OperatorGrammar.enumerate(null, 1)).isEmpty();
    }

    @Test
    void parseIsTheInverseOfRender() {
        for (String r : List.of("prime_density", "log(prime_density)", "(mean_omega * prime_density)",
                "(mean_omega / log_window_size)", "reciprocal(square(mertens_delta))",
                "((a * b) / c)", "log((a / b))")) {
            assertThat(OperatorGrammar.parse(r).render()).isEqualTo(r);
        }
    }

    @Test
    void parsedExpressionEvaluates() {
        OperatorGrammar.Expr e = OperatorGrammar.parse("(a / b)");
        assertThat(e.eval(Map.of("a", 6.0, "b", 3.0))).isEqualTo(2.0);
    }

    @Test
    void parseRejectsMalformed() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OperatorGrammar.parse("(a * )"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OperatorGrammar.parse("bogus(x"));
    }
}
