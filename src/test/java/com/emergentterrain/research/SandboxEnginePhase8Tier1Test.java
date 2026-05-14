package com.emergentterrain.research;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 Tier 1 regression — reflective {@code java.lang.Math} dispatch,
 * SI physical constants, and number-theory helpers wired in from
 * {@link MathPhysicsEngine}. Companion to {@link SandboxEngineWhitelistTest}
 * (Phase 5 A3 trig/exp).
 *
 * <p>Tier 1 stops maintaining a hand-edited whitelist; any {@code public static
 * double *(double[, double[, double]])} method on {@code Math.class} becomes
 * callable without a code change. These tests pin the shape of that contract
 * so a future JDK that adds/removes a Math method doesn't silently flip the
 * sandbox's behaviour.
 */
class SandboxEnginePhase8Tier1Test {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    // ── Math.* reflective dispatch ────────────────────────────────────────────

    @Test
    void log10ReachableViaReflection() {
        assertThat(SandboxEngine.evalExpr("log10(1000)", 0, 1).value())
                .isCloseTo(3.0, EPS);
    }

    @Test
    void sinhCoshTanhReachableViaReflection() {
        assertThat(SandboxEngine.evalExpr("sinh(0)", 0, 1).value()).isCloseTo(0.0,  EPS);
        assertThat(SandboxEngine.evalExpr("cosh(0)", 0, 1).value()).isCloseTo(1.0,  EPS);
        assertThat(SandboxEngine.evalExpr("tanh(0)", 0, 1).value()).isCloseTo(0.0,  EPS);
    }

    @Test
    void cbrtSignumHypotReachableViaReflection() {
        assertThat(SandboxEngine.evalExpr("cbrt(27)",      0, 1).value()).isCloseTo(3.0, EPS);
        assertThat(SandboxEngine.evalExpr("signum(-7.5)",  0, 1).value()).isCloseTo(-1.0, EPS);
        assertThat(SandboxEngine.evalExpr("hypot(3, 4)",   0, 1).value()).isCloseTo(5.0, EPS);
    }

    @Test
    void degreesRadiansRoundTrip() {
        assertThat(SandboxEngine.evalExpr("toDegrees(PI)", 0, 1).value()).isCloseTo(180.0, EPS);
        assertThat(SandboxEngine.evalExpr("toRadians(180)", 0, 1).value()).isCloseTo(Math.PI, EPS);
    }

    @Test
    void expm1Log1pNumericallyStableNearZero() {
        // The narrator reaches for expm1/log1p when arguments are tiny and the
        // naive exp(x)-1 / log(1+x) lose precision. Pin that those are reachable.
        assertThat(SandboxEngine.evalExpr("expm1(0)", 0, 1).value()).isCloseTo(0.0, EPS);
        assertThat(SandboxEngine.evalExpr("log1p(0)", 0, 1).value()).isCloseTo(0.0, EPS);
    }

    @Test
    void arityMismatchProducesClearError() {
        // pow expects 2 args. Calling with 1 must yield a readable diagnostic,
        // not a swallowed NaN or wrong-overload silent coercion.
        assertThatThrownBy(() -> SandboxEngine.evalExpr("pow(2)", 0, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pow")
                .hasMessageContaining("expects 2");
    }

    @Test
    void randomIsExplicitlyExcluded() {
        // Math.random() is stateful and would make sandbox runs nondeterministic.
        // discoverMathMethods skips it; calling it must hit the "Unknown function:"
        // BUILTINS gate.
        assertThatThrownBy(() -> SandboxEngine.evalExpr("random()", 0, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unknown function: random");
    }

    // ── SI physical constants ────────────────────────────────────────────────

    @Test
    void wienConstantUsableInDisplacementLaw() {
        // λ_max · T = b. At T = 5778 K (Sun's photosphere) → λ_max ≈ 501 nm.
        String code = "var T = 5778; return WIEN_B / T;";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isCloseTo(5.015e-7, Offset.offset(1e-9));
    }

    @Test
    void stefanBoltzmannConstantUsableInTotalFlux() {
        // P/A = σ T⁴. At T = 5778 K → ~6.32e7 W/m².
        String code = "var T = 5778; return STEFAN_BOLTZMANN_SIGMA * T * T * T * T;";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 0, 1, 0, 0.0);
        assertThat(r.value()).isCloseTo(6.319e7, Offset.offset(1e5));
    }

    @Test
    void planckConstantsAccessibleAndConsistent() {
        // hbar = h / (2π) within a tiny tolerance.
        SandboxEngine.SandboxResult ratio =
                SandboxEngine.evalExpr("PLANCK_H / (2 * PI * PLANCK_HBAR)", 0, 1);
        assertThat(ratio.value()).isCloseTo(1.0, Offset.offset(1e-6));
    }

    @Test
    void allNewConstantsResolveAsBareIdentifiers() {
        String[] names = {
                "WIEN_B", "STEFAN_BOLTZMANN_SIGMA", "PLANCK_H", "PLANCK_HBAR",
                "BOLTZMANN_K", "SPEED_OF_LIGHT", "AVOGADRO", "ELEMENTARY_CHARGE",
                "GRAVITATIONAL_G", "ELECTRON_MASS", "PROTON_MASS",
                "VACUUM_PERMITTIVITY", "VACUUM_PERMEABILITY"
        };
        for (String n : names) {
            SandboxEngine.SandboxResult r = SandboxEngine.evalExpr(n, 0, 1);
            assertThat(r.value())
                    .as("constant %s must resolve to a finite non-zero value", n)
                    .isFinite()
                    .isNotEqualTo(0.0);
        }
    }

    // ── Number-theory helpers ────────────────────────────────────────────────

    @Test
    void mobiusOfSmallIntegersMatchesMathPhysicsEngine() {
        // μ(1)=1, μ(2)=-1, μ(4)=0 (square factor), μ(6)=1 (two distinct primes).
        assertThat(SandboxEngine.evalExpr("mobius(1)", 0, 1).value()).isCloseTo( 1.0, EPS);
        assertThat(SandboxEngine.evalExpr("mobius(2)", 0, 1).value()).isCloseTo(-1.0, EPS);
        assertThat(SandboxEngine.evalExpr("mobius(4)", 0, 1).value()).isCloseTo( 0.0, EPS);
        assertThat(SandboxEngine.evalExpr("mobius(6)", 0, 1).value()).isCloseTo( 1.0, EPS);
    }

    @Test
    void isSquarefreeAgreesWithEngine() {
        // 1, 2, 3, 5, 6, 7 squarefree; 4, 8, 9, 12 not.
        assertThat(SandboxEngine.evalExpr("isSquarefree(6)",  0, 1).value()).isCloseTo(1.0, EPS);
        assertThat(SandboxEngine.evalExpr("isSquarefree(12)", 0, 1).value()).isCloseTo(0.0, EPS);
    }

    @Test
    void mertensPartialSumAgreesWithEngine() {
        // M(10) = -1 by direct computation.
        assertThat(SandboxEngine.evalExpr("mertensPartialSum(10)", 0, 1).value())
                .isCloseTo(-1.0, EPS);
    }

    @Test
    void sternBrocotDepthOfFibonacciPairMatchesEuclideanSteps() {
        // 13 / 8 (consecutive Fibonacci) → Euclidean steps:
        //   (13, 8) → (8, 5) → (5, 3) → (3, 2) → (2, 1) → (1, 0)  — 5 steps.
        // Matches the engine's documented length-of-CF semantic at
        // MathPhysicsEngine#sternBrocotDepthOf.
        SandboxEngine.SandboxResult r =
                SandboxEngine.evalExpr("sternBrocotDepthOf(13, 8)", 0, 1);
        assertThat(r.value()).isCloseTo(5.0, EPS);
    }

    // ── Static checker consistency ───────────────────────────────────────────

    @Test
    void staticCheckerApprovesNewMathStarCalls() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "var x = log10(100); var y = hypot(3, 4); return x + y;");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void staticCheckerApprovesNewNumberTheoryHelpers() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "var c = 0;\n"
              + "for (var n = 1; n < 50; n++) { if (isSquarefree(n) && mobius(n) != 0) { c++; } }\n"
              + "return c;");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }

    @Test
    void staticCheckerStillRejectsTrulyUnknownNames() {
        SandboxStaticChecker.Result r = SandboxStaticChecker.check(
                "return System(42);");
        assertThat(r).isInstanceOf(SandboxStaticChecker.Result.Rejected.class);
    }
}
