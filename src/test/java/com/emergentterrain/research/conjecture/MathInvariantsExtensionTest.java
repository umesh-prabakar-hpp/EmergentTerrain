package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 5 — the flag-OFF byte-identity invariant for the 30.5 vocabulary-expansion overloads:
 * with no admitted scalars, {@code vocabulary(...)} / {@code extract(...)} reproduce the base paths
 * exactly (so {@code ConjectureEngineParityTest} and the mined survivor set are unaffected).
 */
class MathInvariantsExtensionTest {

    private static Map<String, Double> raw() {
        return Map.of("prime_density", 0.12, "mean_omega", 2.1, "mean_Omega", 2.8,
                "mertens_delta", -3.0, "abundant_density", 0.24, "deficient_density", 0.74,
                "happy_density", 0.14, "mean_divisor_count", 6.0, "max_divisor_count", 12.0);
    }

    @Test
    void emptyAdmittedReproducesBaseVocabulary() {
        assertThat(MathInvariants.vocabulary(List.of())).isEqualTo(MathInvariants.vocabulary());
        assertThat(MathInvariants.vocabulary(null)).isEqualTo(MathInvariants.vocabulary());
    }

    @Test
    void emptyAdmittedReproducesBaseExtract() {
        assertThat(MathInvariants.extract(raw(), 1000, 500, List.of()))
                .isEqualTo(MathInvariants.extract(raw(), 1000, 500));
    }

    @Test
    void admittedScalarsAreAppendedAndComputed() {
        DerivedScalar logPd = new DerivedScalar("log(prime_density)",
                OperatorGrammar.Expr.unary(OperatorGrammar.Unary.LOG,
                        OperatorGrammar.Expr.leaf("prime_density")), 1.0);

        // vocabulary gains the admitted name (after the base names, which are unchanged).
        List<String> base = MathInvariants.vocabulary();
        List<String> extended = MathInvariants.vocabulary(List.of(logPd));
        assertThat(extended).startsWith(base.toArray(new String[0]));
        assertThat(extended).endsWith("log(prime_density)");

        // extract computes the admitted scalar from the flattened base map.
        Map<String, Double> flat = MathInvariants.extract(raw(), 1000, 500, List.of(logPd));
        assertThat(flat).containsKeys("prime_density", "log(prime_density)");
        assertThat(flat.get("log(prime_density)")).isCloseTo(Math.log(0.12), within(1e-9));
    }
}
