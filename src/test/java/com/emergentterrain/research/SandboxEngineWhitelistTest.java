package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sandbox-whitelist regression for Phase 5 A3.
 *
 * <p>The 4b reviewer ({@link com.emergentterrain.service.MathToolSuite}) and the
 * interpreter ({@link SandboxEngine}) must agree on which math primitives are allowed.
 * Before this commit the simulator rejected 18 narrator-emitted code attempts in a
 * single corpus run because trig + Math.PI were absent — forcing the narrator into
 * crude approximations (composite-failure 12 GPa series, prime-gap pseudo-physics).
 *
 * <p>These tests exercise the interpreter directly. The 4b review prompt is mirrored
 * by inspection in the code comment in {@link SandboxEngine#BUILTINS}.
 */
class SandboxEngineWhitelistTest {

    // -------- trig --------

    @Test
    void sinReturnsCorrectValue() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("sin(0)", 0, 1);
        assertThat(r.value()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void cosReturnsCorrectValue() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("cos(0)", 0, 1);
        assertThat(r.value()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void sinOfPiOverTwoEqualsOne() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("sin(PI / 2)", 0, 1);
        assertThat(r.value()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void cosOfPiEqualsMinusOne() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("cos(PI)", 0, 1);
        assertThat(r.value()).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void tanOfPiOverFourEqualsOne() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("tan(PI / 4)", 0, 1);
        assertThat(r.value()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void asinAcosAtanInverseTrig() {
        assertThat(SandboxEngine.evalExpr("asin(1)", 0, 1).value())
                .isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(SandboxEngine.evalExpr("acos(0)", 0, 1).value())
                .isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(SandboxEngine.evalExpr("atan(1)", 0, 1).value())
                .isCloseTo(Math.PI / 4, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void atan2OfOneOneEqualsPiOverFour() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("atan2(1, 1)", 0, 1);
        assertThat(r.value()).isCloseTo(Math.PI / 4, org.assertj.core.data.Offset.offset(1e-12));
    }

    // -------- exp + constants --------

    @Test
    void expOfOneEqualsE() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("exp(1)", 0, 1);
        assertThat(r.value()).isCloseTo(Math.E, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void piConstantAccessibleAsBareIdentifier() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("PI", 0, 1);
        assertThat(r.value()).isCloseTo(Math.PI, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void eConstantAccessibleAsBareIdentifier() {
        SandboxEngine.SandboxResult r = SandboxEngine.evalExpr("E", 0, 1);
        assertThat(r.value()).isCloseTo(Math.E, org.assertj.core.data.Offset.offset(1e-12));
    }

    // -------- composed expressions matching real narrator use cases --------

    @Test
    void arrheniusRatioComputableInSandbox() {
        // The exact Arrhenius +10K ratio at T=342 K, Ea=189 kJ/mol — closed-form
        // exp(189000/8.314 · (1/342 - 1/352)) ≈ 6.608. Until this commit the narrator
        // could not call exp() and had to inline crude Taylor approximations that
        // drifted out of physical range.
        String code = "var Ea = 189000; var R = 8.314; var T = 342;\n"
                    + "return exp(Ea / R * (1 / T - 1 / (T + 10)));";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isCloseTo(6.608, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void pendulumPeriodErrorComputableInSandbox() {
        // T(θ)/T₀ - 1 = sin²(θ/2)/4 + 9·sin⁴(θ/2)/64 + ... — matches the engine's exact
        // expansion at θ = 25° → 1.19%.
        String code = "var theta = 25 * PI / 180;\n"
                    + "var k = sin(theta / 2); var k2 = k * k;\n"
                    + "return (k2 / 4 + 9 * k2 * k2 / 64) * 100;";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isCloseTo(1.19, org.assertj.core.data.Offset.offset(0.02));
    }

    // -------- denied operations remain denied --------

    @Test
    void unknownFunctionsAreStillRejected() {
        assertThatThrownBy(() -> SandboxEngine.evalExpr("System(42)", 0, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unknown function: System");
    }

    @Test
    void mathDotPiSyntaxRemainsUnparseable() {
        // The narrator may still try `Math.PI` (with dot syntax). The sandbox grammar
        // doesn't support member access, so this must still fail — only the bare
        // identifier `PI` is supported. Documenting current behaviour, not a behaviour
        // change.
        assertThatThrownBy(() -> SandboxEngine.evalExpr("Math.PI", 0, 1))
                .isInstanceOf(RuntimeException.class);
    }
}
