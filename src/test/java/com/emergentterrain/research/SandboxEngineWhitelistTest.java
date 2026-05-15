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

    // ── Phase 10 — while loop + ===/!== ─────────────────────────────────────

    @Test
    void whileLoop_executesAndConverges() {
        // Newton's method for sqrt(param1), bounded by abs(err) condition rather
        // than a counter. The MAX_ITERATIONS guard still applies.
        String code = """
                var x = param2;
                while (abs(x * x - param1) > 0.0001) {
                  x = (x + param1 / x) / 2;
                }
                return x;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 64, 8.0);
        assertThat(r.value()).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void whileLoop_respectsIterationLimit() {
        // Infinite loop must terminate via MAX_ITERATIONS rather than hang.
        String code = """
                var i = 0;
                while (i < 1000000) {
                  i = i + 1;
                }
                return i;
                """;
        assertThatThrownBy(() -> SandboxEngine.run(code, 1, 2, 0, 0.0))
                .isInstanceOf(SandboxEngine.LimitExceeded.class)
                .hasMessageContaining("Loop limit");
    }

    @Test
    void strictEquality_matchesLooseEquality() {
        // === is a lexer alias for ==; semantics are identical because every
        // value is a double.
        String codeStrict = "return (7 === 7) + (7 === 8) * 10;";
        String codeLoose  = "return (7 ==  7) + (7 ==  8) * 10;";
        assertThat(SandboxEngine.run(codeStrict, 1, 2, 0, 0.0).value())
                .isEqualTo(SandboxEngine.run(codeLoose, 1, 2, 0, 0.0).value())
                .isEqualTo(1.0);
    }

    @Test
    void strictInequality_matchesLooseInequality() {
        String codeStrict = "return (7 !== 8) + (7 !== 7) * 10;";
        String codeLoose  = "return (7 !=  8) + (7 !=  7) * 10;";
        assertThat(SandboxEngine.run(codeStrict, 1, 2, 0, 0.0).value())
                .isEqualTo(SandboxEngine.run(codeLoose, 1, 2, 0, 0.0).value())
                .isEqualTo(1.0);
    }

    // ── Phase 10 — recordSample structured stats ────────────────────────────

    @Test
    void recordSample_aggregatesNCountSumMeanMinMax() {
        // Record samples 10, 20, 30 → n=3, mean=20, min=10, max=30, std=√(200/3)≈8.165.
        // Use the recordSample return value to feed sum, mirroring what an LLM
        // would write to combine "record" + "use" in a single expression.
        String code = """
                var sum = 0;
                sum = sum + recordSample(10);
                sum = sum + recordSample(20);
                sum = sum + recordSample(30);
                return sum;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isEqualTo(60.0);
        assertThat(r.recordedN()).isEqualTo(3);
        assertThat(r.recordedMean()).isEqualTo(20.0);
        assertThat(r.recordedMin()).isEqualTo(10.0);
        assertThat(r.recordedMax()).isEqualTo(30.0);
        assertThat(r.recordedStd()).isCloseTo(Math.sqrt(200.0 / 3.0),
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void recordSample_inLoop_producesUsableStatistics() {
        // Realistic shape: filter happy numbers in [100,300), record Collatz
        // steps, return the count. Same problem as P-a328-361. The harness now
        // gets n / mean / std / max / min back from a single tool call.
        String code = """
                var c = 0;
                for (var n = start; n < end; n++) {
                  if (isHappy(n)) {
                    recordSample(collatzSteps(n));
                    c = c + 1;
                  }
                }
                return c;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 100, 300, 0, 0.0);
        // We don't assert exact stats (depends on isHappy / collatzSteps which are
        // deterministic but their exact values aren't the point of this test).
        // We just assert that the stream was recorded and is internally consistent.
        assertThat(r.recordedN()).isEqualTo((int) r.value());
        assertThat(r.recordedN()).isGreaterThan(0);
        assertThat(r.recordedMin()).isLessThanOrEqualTo(r.recordedMean());
        assertThat(r.recordedMax()).isGreaterThanOrEqualTo(r.recordedMean());
        assertThat(r.recordedStd()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void recordSample_zeroCalls_leavesNaNStats() {
        // A run with no recordSample() calls returns NaN for the stats and n=0,
        // so consumers know there's no aggregated data to format.
        String code = "return 42;";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isEqualTo(42.0);
        assertThat(r.recordedN()).isZero();
        assertThat(r.recordedMean()).isNaN();
        assertThat(r.recordedStd()).isNaN();
    }

    // ── Phase 10 — bounded arrays ───────────────────────────────────────────

    @Test
    void arrayLiteralAndIndexedRead() {
        String code = """
                var xs = [10, 20, 30, 40];
                return xs[2];
                """;
        assertThat(SandboxEngine.run(code, 1, 2, 0, 0.0).value()).isEqualTo(30.0);
    }

    @Test
    void arrayIndexedWriteAndCompoundAssign() {
        // Direct write `xs[0] = 99`, then a compound assign `xs[1] += 5`.
        // Reads after each verify the writes landed in the correct slot.
        String code = """
                var xs = [1, 2, 3];
                xs[0] = 99;
                xs[1] += 5;
                return xs[0] * 100 + xs[1] * 10 + xs[2];
                """;
        // xs = [99, 7, 3] → 99*100 + 7*10 + 3 = 9973
        assertThat(SandboxEngine.run(code, 1, 2, 0, 0.0).value()).isEqualTo(9973.0);
    }

    @Test
    void lenBuiltinReturnsArrayLength() {
        String code = """
                var xs = [5, 10, 15, 20, 25, 30, 35];
                return len(xs);
                """;
        assertThat(SandboxEngine.run(code, 1, 2, 0, 0.0).value()).isEqualTo(7.0);
    }

    @Test
    void linearRegressionViaArrays() {
        // The P-a328-352 use case that fabricated coefficients because arrays
        // weren't available. With arrays, the same regression is straightforward.
        // Fit stress = m*gap + b on (12,440), (11,430), (10,410), (9,400).
        // Expected slope ≈ 14, intercept ≈ 273.
        String code = """
                var gaps    = [12, 11, 10, 9];
                var stress  = [440, 430, 410, 400];
                var n  = len(gaps);
                var sx = 0; var sy = 0; var sxy = 0; var sx2 = 0;
                for (var i = 0; i < n; i++) {
                  var x = gaps[i]; var y = stress[i];
                  sx  += x;  sy += y;  sxy += x*y;  sx2 += x*x;
                }
                var slope = (n*sxy - sx*sy) / (n*sx2 - sx*sx);
                var intercept = (sy - slope*sx) / n;
                recordSample(slope);
                recordSample(intercept);
                return slope;
                """;
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isCloseTo(14.0, org.assertj.core.data.Offset.offset(0.5));
        // recordSample collected slope and intercept — useful for the caller
        // to read multiple correlated stats out of one sandbox run.
        assertThat(r.recordedN()).isEqualTo(2);
        assertThat(r.recordedMin()).isCloseTo(14.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(r.recordedMax()).isCloseTo(273.0, org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    void arrayLengthCap_rejected() {
        // 1025 elements > MAX_ARRAY_LEN (1024). Bound at declaration so user
        // code can't accidentally OOM the JVM via a tabulated dataset.
        StringBuilder sb = new StringBuilder("var xs = [");
        for (int i = 0; i < 1025; i++) { if (i > 0) sb.append(','); sb.append(i); }
        sb.append("]; return xs[0];");
        assertThatThrownBy(() -> SandboxEngine.run(sb.toString(), 1, 2, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exceeds cap");
    }

    @Test
    void outOfBoundsRead_raises() {
        String code = "var xs = [1, 2, 3]; return xs[5];";
        assertThatThrownBy(() -> SandboxEngine.run(code, 1, 2, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void usingArrayAsScalar_raises() {
        // `xs * 2` is meaningless and must raise rather than silently producing
        // a wrong number.
        String code = "var xs = [1, 2, 3]; return xs * 2;";
        assertThatThrownBy(() -> SandboxEngine.run(code, 1, 2, 0, 0.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("array");
    }

    @Test
    void arrayLiteralAsRhsOfReassignment_raises() {
        // Arrays can only be created at declaration. `xs = [...]` after the
        // initial decl would replace a scalar with an array or rebind the array
        // — both are confusing semantics, so we reject.
        String code = "var xs = 0; xs = [1, 2, 3]; return xs[0];";
        assertThatThrownBy(() -> SandboxEngine.run(code, 1, 2, 0, 0.0))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void signedDevPercent_carriesSignThroughDeterministically() {
        // P-a328-363 use case: observed = 0.0261, expected = 0.0189 (HL prediction).
        // Truth: observed is 38% ABOVE the expected. The LLM narrated it as
        // "30-40% below". signedDevPercent gives the deterministic signed value.
        String code = "return signedDevPercent(0.0261, 0.0189);";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isCloseTo(38.1, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void signedDevPercent_negativeWhenObservedBelowExpected() {
        String code = "return signedDevPercent(50, 100);";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isEqualTo(-50.0);
    }

    @Test
    void signedDevPercent_handlesZeroExpected() {
        // Avoid divide-by-zero by returning 0.0 — caller can treat as
        // "no meaningful comparison".
        String code = "return signedDevPercent(5, 0);";
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);
        assertThat(r.value()).isZero();
    }

    @Test
    void staticCheckerApprovesArrays() {
        // The static-check pass must allow array constructs (the runtime
        // enforces correctness; the checker only enforces syntax + whitelist).
        String code = """
                var ys = [1, 2, 3, 4];
                var s = 0;
                for (var i = 0; i < len(ys); i++) {
                  s = s + ys[i];
                  ys[i] *= 2;
                }
                return s;
                """;
        assertThat(SandboxStaticChecker.check(code))
                .isInstanceOf(SandboxStaticChecker.Result.Approved.class);
    }
}
