package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.4 — parity pin for {@link ConjectureVerifier#verify}
 * against the sidecar's {@code /conjecture/verify} endpoint.
 *
 * <p>Tagged {@code @Tag("integration")} so it only runs when the
 * sidecar is up. Acceptance: byte-identical {@code VerifyResult}
 * between (a) the legacy in-process per-window path and (b) the
 * sidecar's batched verify path.
 *
 * <p>The §28.4 dispatch in ConjectureVerifier auto-selects sidecar
 * when {@code isConjectureAvailable()} is true; this test exercises
 * both branches by constructing two ConjectureVerifier instances
 * with different sidecar wiring.
 */
@Tag("integration")
class ConjectureVerifierParityTest {

    private static final String SIDECAR_URL =
            System.getenv().getOrDefault("JULIA_SIDECAR_URL", "http://localhost:8088");

    private static boolean sidecarUp;

    @BeforeAll
    static void probeSidecar() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(SIDECAR_URL + "/healthz"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            sidecarUp = resp.statusCode() == 200;
        } catch (Exception e) {
            sidecarUp = false;
        }
    }

    private static JuliaMathClient liveSidecar() {
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ true, SIDECAR_URL, 10_000, 3_000,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                /*conjectureEnabled*/ true, 10_000,
                new ObjectMapper());
        client.probeHealth();
        return client;
    }

    /**
     * Build a {@code ConjectureVerifier} whose substrate delegate
     * routes through the live sidecar — exercises both the
     * per-window /numbertheory/window path AND the batched
     * /conjecture/verify path depending on which entry point is
     * hit.
     */
    private static ConjectureVerifier sidecarVerifier() {
        MathConjectureSubstrateDelegate delegate =
                new MathConjectureSubstrateDelegate(
                        new MathConjectureSubstrate(1_050_000),
                        liveSidecar());
        ConjectureVerifier v = new ConjectureVerifier();
        v.setSubstrateDelegate(delegate);
        return v;
    }

    /**
     * Legacy-only verifier: delegate has null JuliaMathClient so
     * every call routes through the in-process substrate.
     */
    private static ConjectureVerifier legacyVerifier() {
        MathConjectureSubstrateDelegate delegate =
                new MathConjectureSubstrateDelegate(
                        new MathConjectureSubstrate(1_050_000),
                        /*juliaMathClient*/ null);
        ConjectureVerifier v = new ConjectureVerifier();
        v.setSubstrateDelegate(delegate);
        return v;
    }

    // ── BASE-vocabulary cases ────────────────────────────────────────

    @Test
    void verify_baseLhsRhs_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        // Build a non-trivial inequality and check it across 3 disjoint
        // windows. Coefficients chosen so the conjecture holds at this
        // window: prime_density is typically ~0.1-0.2 in these ranges,
        // bounded by ~0.5 + something small.
        String lhs = "prime_density";
        String rhs = "deficient_density";
        double k = 0.5;
        double c = 0.1;
        long windowStart = 1_000;
        int windowSize = 500;
        int numWindows = 3;

        ConjectureVerifier.VerifyResult legacy =
                legacyVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        ConjectureVerifier.VerifyResult sidecar =
                sidecarVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);

        assertVerifyResultsEqual(legacy, sidecar);
    }

    // ── DERIVED-vocabulary cases — exercises math_invariants_extract ──

    @Test
    void verify_derivedLhs_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        // omega_Omega_gap = mean_Omega - mean_omega; routed through
        // the sidecar's math_invariants_extract DERIVED path.
        String lhs = "omega_Omega_gap";
        String rhs = "prime_density";
        double k = 1.0;
        double c = 5.0;
        long windowStart = 5_000;
        int windowSize = 1_000;
        int numWindows = 2;

        ConjectureVerifier.VerifyResult legacy =
                legacyVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        ConjectureVerifier.VerifyResult sidecar =
                sidecarVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);

        assertVerifyResultsEqual(legacy, sidecar);
    }

    @Test
    void verify_logWindowSize_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        // log_window_size = log10(window_size). The DERIVED scalar
        // depends ONLY on window metadata, which means it's identical
        // across all windows of the same size. Good test of the
        // metadata-extraction path.
        String lhs = "log_window_size";
        String rhs = "sqrt_window_size";
        double k = 0.1;
        double c = 100.0;
        long windowStart = 10_000;
        int windowSize = 200;
        int numWindows = 2;

        ConjectureVerifier.VerifyResult legacy =
                legacyVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        ConjectureVerifier.VerifyResult sidecar =
                sidecarVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);

        assertVerifyResultsEqual(legacy, sidecar);
    }

    // ── Refutation case — exercises the held=false code path ─────────

    @Test
    void verify_refuted_matchesLegacy_andHeldFalse() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        // Pick an inequality that should easily violate: prime_density
        // is < 1 but we claim it's bounded above by a very negative
        // intercept. Margin will be large and positive → held=false.
        String lhs = "prime_density";
        String rhs = "deficient_density";
        double k = 0.0;
        double c = -10.0;  // very wrong intercept
        long windowStart = 100;
        int windowSize = 100;
        int numWindows = 2;

        ConjectureVerifier.VerifyResult legacy =
                legacyVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        ConjectureVerifier.VerifyResult sidecar =
                sidecarVerifier().verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);

        assertThat(legacy.held()).as("refutation should not hold").isFalse();
        assertVerifyResultsEqual(legacy, sidecar);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void assertVerifyResultsEqual(
            ConjectureVerifier.VerifyResult expected,
            ConjectureVerifier.VerifyResult actual) {
        assertThat(actual.lhs()).isEqualTo(expected.lhs());
        assertThat(actual.rhs()).isEqualTo(expected.rhs());
        assertThat(actual.k()).isEqualTo(expected.k());
        assertThat(actual.c()).isEqualTo(expected.c());
        assertThat(actual.windowsEvaluated()).isEqualTo(expected.windowsEvaluated());
        assertThat(actual.held()).isEqualTo(expected.held());
        assertThat(actual.worstMargin()).as("worstMargin")
                .isEqualTo(expected.worstMargin());
        assertThat(actual.worstWindowStart()).as("worstWindowStart")
                .isEqualTo(expected.worstWindowStart());

        List<ConjectureVerifier.WindowReport> expWindows =
                expected.windows();
        List<ConjectureVerifier.WindowReport> actWindows =
                actual.windows();
        assertThat(actWindows).as("windows size")
                .hasSize(expWindows.size());
        for (int i = 0; i < expWindows.size(); i++) {
            ConjectureVerifier.WindowReport e = expWindows.get(i);
            ConjectureVerifier.WindowReport a = actWindows.get(i);
            assertThat(a.windowStart()).as("windows[%d].windowStart", i)
                    .isEqualTo(e.windowStart());
            assertThat(a.lhsValue()).as("windows[%d].lhsValue", i)
                    .isEqualTo(e.lhsValue());
            assertThat(a.rhsValue()).as("windows[%d].rhsValue", i)
                    .isEqualTo(e.rhsValue());
            assertThat(a.margin()).as("windows[%d].margin", i)
                    .isEqualTo(e.margin());
            assertThat(a.holds()).as("windows[%d].holds", i)
                    .isEqualTo(e.holds());
        }
    }
}
