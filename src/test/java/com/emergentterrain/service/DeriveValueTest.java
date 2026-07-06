package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Phase 33 R2 — the derive_value arithmetic evaluator. */
class DeriveValueTest {

    @Test
    void evaluatesTheMotivatingRateExpressions() {
        // P-792's '0.025 per decade' and P-807's ladder decrement — the class this tool closes.
        assertThat(MathToolSuite.evalArithmetic("(0.33674 - 0.23575) / 4"))
                .isCloseTo(0.0252475, within(1e-12));
        assertThat(MathToolSuite.evalArithmetic("(1.02989 - 0.92890) / 4"))
                .isCloseTo(0.0252475, within(1e-12));
    }

    @Test
    void precedenceParensFunctionsScientificNotation() {
        assertThat(MathToolSuite.evalArithmetic("2 + 3 * 4")).isEqualTo(14.0);
        assertThat(MathToolSuite.evalArithmetic("(2 + 3) * 4")).isEqualTo(20.0);
        assertThat(MathToolSuite.evalArithmetic("2 ^ 3 ^ 2")).isEqualTo(512.0);   // right-assoc
        assertThat(MathToolSuite.evalArithmetic("-3 + 1")).isEqualTo(-2.0);
        assertThat(MathToolSuite.evalArithmetic("ln(exp(2))")).isCloseTo(2.0, within(1e-12));
        assertThat(MathToolSuite.evalArithmetic("log10(1e8)")).isCloseTo(8.0, within(1e-12));
        assertThat(MathToolSuite.evalArithmetic("sqrt(abs(-16))")).isEqualTo(4.0);
        assertThat(MathToolSuite.evalArithmetic("1.5e6 / 5e3")).isEqualTo(300.0);
    }

    @Test
    void rejectsJunkAndVariables() {
        assertThatThrownBy(() -> MathToolSuite.evalArithmetic("k * 2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MathToolSuite.evalArithmetic("System.exit(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MathToolSuite.evalArithmetic("1 +"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MathToolSuite.evalArithmetic(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void divisionByZeroReportsNotFiniteInsteadOfThrowing() {
        assertThat(MathToolSuite.evalArithmetic("1 / 0")).isInfinite();
    }
}
