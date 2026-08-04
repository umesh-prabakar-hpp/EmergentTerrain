package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22 §22.2 — live integration test against a real
 * {@code julia-math-sidecar} on {@code localhost:8088}. Skips
 * (via {@link Assumptions#assumeTrue}) when the sidecar is
 * unreachable so CI runs without it don't go red.
 *
 * <p>To run manually after bringing up the sidecar:
 * <pre>{@code
 *   cd julia-math-sidecar && ./scripts/start.sh &
 *   cd EmergentTerrain && mvn test \
 *       -Dtest=JuliaMathClientIntegrationTest \
 *       -Dnet.bytebuddy.experimental=true
 * }</pre>
 *
 * <p>Test cases mirror the smoke checks in
 * {@code julia-math-sidecar/test/corpus_fixtures.jl} but run from
 * the Java side so we exercise the full HTTP serialise + parse
 * round-trip the production code uses.
 */
class JuliaMathClientIntegrationTest {

    private static final String BASE_URL = "http://localhost:8088";

    private static JuliaMathClient client;

    @BeforeAll
    static void probeSidecar() {
        // Build a real client pointing at the conventional dev port.
        // 1-second probe timeout — if the sidecar isn't running we
        // want to skip fast, not block CI.
        client = new JuliaMathClient(
                /*enabled*/ true,
                BASE_URL,
                /*timeoutMs*/ 10_000,
                /*healthProbeTimeoutMs*/ 1_000,
                new ObjectMapper());
        boolean reachable = client.probeHealth();
        // Every @Test below gets skipped when this assumption fails.
        // Assumptions vs @Disabled: assumeTrue produces "skipped"
        // surefire output; @Disabled produces "ignored". Skipped is
        // the right semantic — the test is valid, the precondition
        // wasn't met.
        Assumptions.assumeTrue(reachable,
                "julia-math-sidecar not running on " + BASE_URL
                        + " — skipping live integration tests. "
                        + "Start it via `cd julia-math-sidecar && ./scripts/start.sh`.");
    }

    @Test
    void eval_braggFloor() {
        // Cu Kα diffraction floor — matches the corpus P-756 fixture.
        JuliaMathClient.EvalResult r = client.eval("floor(2.04e-6 / 400e-9)");
        assertThat(r.result()).isEqualTo("5.0");
        assertThat(r.type()).isEqualTo("Float64");
    }

    @Test
    void eval_braggAngle() {
        // First-order Bragg angle for Cu Kα — matches P-785 fixture.
        // λ=0.154nm, d=0.112nm → θ = asin(λ/(2d)) ≈ 43.43°.
        JuliaMathClient.EvalResult r =
                client.eval("asin(0.154 / (2*0.112)) * 180 / π");
        assertThat(Double.parseDouble(r.result()))
                .isCloseTo(43.43, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void nsolve_quadraticRoot() {
        // x^2 - 4 = 0 from initial guess 1.0 → converges to 2.0.
        JuliaMathClient.NumericRootResult r = client.nsolve("x^2 - 4", "x", 1.0);
        assertThat(r.root()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void integrate_sinOverZeroToPi() {
        // ∫₀^π sin(x) dx = 2 — textbook closed form.
        JuliaMathClient.IntegralResult r =
                client.integrateSymbolicLimits("sin(x)", "x", "0", "π");
        assertThat(r.value()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-6));
        // QuadGK gives a tight error bound on a smooth integrand.
        assertThat(r.errorEstimate()).isLessThan(1e-10);
    }

    @Test
    void sweep_quadraticStatistics() {
        // x² over [1, 10] with 10 samples — min=1, max=100, n=10.
        JuliaMathClient.SweepResult r = client.sweep("x^2", "x", 1.0, 10.0, 10);
        assertThat(r.n()).isEqualTo(10);
        assertThat(r.min()).isEqualTo(1.0);
        assertThat(r.max()).isEqualTo(100.0);
    }

    @Test
    void safetyFilter_rejectsForbiddenToken() {
        // Pkg manipulation must reject — as of Phase 22.3 the JVM-side
        // filter catches this BEFORE the HTTP request, so we expect
        // `client_safety_rejected`. A separate sidecar-side test in
        // julia-math-sidecar/test/runtests.jl covers the server's
        // filter for inputs that somehow bypass the client filter.
        try {
            client.eval("(using Pkg; Pkg.add(\"Foo\"))");
            org.junit.jupiter.api.Assertions.fail(
                    "Expected JuliaMathException for forbidden token");
        } catch (JuliaMathException e) {
            assertThat(e.errorCode()).isEqualTo("client_safety_rejected");
        }
    }

    @Test
    void corpus_P693_tirInvertedIndices_domainError() {
        // P-693 archetype: asin(1.577/1.50) — argument > 1.
        // Julia surfaces this as DomainError → eval_error envelope.
        try {
            client.eval("asin(1.577 / 1.50)");
            org.junit.jupiter.api.Assertions.fail(
                    "Expected DomainError for asin(x) with |x|>1");
        } catch (JuliaMathException e) {
            assertThat(e.errorCode()).isEqualTo("eval_error");
            assertThat(e.getMessage()).contains("not defined for |x| > 1");
        }
    }
}
