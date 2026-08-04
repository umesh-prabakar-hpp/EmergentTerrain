package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 6 — unit tests for {@link Mechanism} (30.7 P1: model + DSL parse + predict lowering).
 */
class MechanismTest {

    @Test
    void parsesDslAndLowersToPredict() {
        // spread := sqrt(mean_divisor_count);  predict(mean_omega) := log(spread)
        Mechanism m = Mechanism.parse("""
                # a toy mechanism
                spread := sqrt(mean_divisor_count)
                predict(mean_omega) := log(spread)
                invariant: monotone_increasing(predict in mean_divisor_count)
                """);
        assertThat(m.target()).isEqualTo("mean_omega");
        assertThat(m.producedNames()).containsExactly("spread", "mean_omega");
        assertThat(m.declaredInvariants()).containsExactly("monotone_increasing(predict in mean_divisor_count)");

        // mean_divisor_count=16 → spread=4 → predict=log(4)
        double p = m.predict(Map.of("mean_divisor_count", 16.0));
        assertThat(p).isCloseTo(Math.log(4.0), within(1e-9));
    }

    @Test
    void intermediatesAreVisibleToLaterDefinitions() {
        // chained intermediates: a := 2*-ish via grammar; b references a.
        Mechanism m = Mechanism.parse("""
                a := square(x)
                predict(y) := (a / x)
                """);
        // x=3 → a=9 → predict=9/3=3
        assertThat(m.predict(Map.of("x", 3.0))).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void lastDefinitionIsTargetWhenNoPredictLine() {
        Mechanism m = Mechanism.parse("""
                u := log(x)
                v := sqrt(x)
                """);
        assertThat(m.target()).isEqualTo("v");
        assertThat(m.predict(Map.of("x", 9.0))).isCloseTo(3.0, within(1e-9));   // sqrt(9)
    }

    @Test
    void domainGuardPropagatesNaN() {
        Mechanism m = Mechanism.parse("predict(y) := log(x)");
        assertThat(m.predict(Map.of("x", 0.0))).isNaN();        // log(0) → NaN
        assertThat(m.predict(Map.of("z", 1.0))).isNaN();        // x missing → NaN
    }

    @Test
    void programmaticConstructionMatchesParse() {
        Mechanism built = new Mechanism(
                List.of(new Mechanism.Definition("y",
                        OperatorGrammar.parse("sqrt(x)"))), "y", List.of());
        assertThat(built.predict(Map.of("x", 25.0))).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void rejectsLineWithoutAssignment() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Mechanism.parse("this is not a definition"));
    }
}
