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
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.2 — parity test pinning the legacy
 * {@link MathConjectureSubstrate} against the sidecar's
 * {@code /numbertheory/window} endpoint.
 *
 * <p>Tagged {@code @Tag("integration")} so it only runs when the
 * sidecar is up. Local dev runs without the sidecar skip cleanly
 * via {@code assumeTrue} on /healthz.
 *
 * <p>The §28.2 acceptance bar: byte-identical scalar maps between
 * the two paths over a fixed corpus of windows.
 */
@Tag("integration")
class MathConjectureSubstrateParityTest {

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

    /**
     * Three windows spanning the size envelope: a tiny start at low
     * n, a medium one in the middle, and a larger one near the
     * sidecar's NT_MAX_N cap. Bypass starts at 100 (substrate excludes
     * n < 2 so {@code start=0} produces a different result shape).
     */
    private static Stream<int[]> windows() {
        return Stream.of(
                new int[]{100, 50},
                new int[]{10_000, 200},
                new int[]{500_000, 1_000}
        );
    }

    @Test
    void sidecarPath_matchesLegacy_onSmallWindow() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        assertParity(100, 50);
    }

    @Test
    void sidecarPath_matchesLegacy_onMediumWindow() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        assertParity(10_000, 200);
    }

    @Test
    void sidecarPath_matchesLegacy_onLargeWindow() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        assertParity(500_000, 1_000);
    }

    /**
     * §28.2 byte-identical-parity check. Builds both the legacy
     * substrate and a live sidecar-routed client, runs the same
     * {@code (start, size)} window through each, and asserts the
     * returned LinkedHashMaps agree on both key order and floating-
     * point values.
     */
    private void assertParity(int start, int size) {
        // Legacy path.
        MathConjectureSubstrate legacy = new MathConjectureSubstrate(1_050_000);
        Map<String, Double> legacyMap = legacy.evaluateWindow(start, size);

        // Sidecar path — build a JuliaMathClient that points at the
        // running sidecar with the conjecture flag forced on.
        JuliaMathClient sidecar = new JuliaMathClient(
                /*enabled*/ true, SIDECAR_URL, 5_000, 3_000,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                /*conjectureEnabled*/ true, 5_000,
                new ObjectMapper());
        sidecar.probeHealth();
        assumeTrue(sidecar.isConjectureAvailable(),
                "JuliaMathClient.isConjectureAvailable() = false even though /healthz passed");

        JuliaMathClient.NumberTheoryWindow sideResult =
                sidecar.numberTheoryWindow(start, size);
        Map<String, Double> sideMap = sideResult.scalars();

        // Key sets must match exactly.
        assertThat(sideMap.keySet())
                .containsExactlyInAnyOrderElementsOf(legacyMap.keySet());

        // Key ORDER must also match — the doc requires "byte-identical".
        assertThat(sideMap.keySet().stream().toList())
                .as("scalar key order must match between legacy and sidecar")
                .isEqualTo(legacyMap.keySet().stream().toList());

        // Values must match. Floating-point equality is intentional —
        // both paths run the same algorithm; any drift is a parity bug
        // that we want to catch immediately, not absorb under a
        // tolerance window.
        for (Map.Entry<String, Double> e : legacyMap.entrySet()) {
            assertThat(sideMap.get(e.getKey()))
                    .as("scalar %s for window (start=%d size=%d)",
                            e.getKey(), start, size)
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void delegate_legacyOnlyPath_matchesDirectSubstrate() {
        // Even without the sidecar, the delegate's legacy path must
        // return identical results to a bare MathConjectureSubstrate.
        // This test doesn't require the sidecar to be up — runs in
        // every environment.
        MathConjectureSubstrate direct = new MathConjectureSubstrate(1_050_000);
        MathConjectureSubstrateDelegate delegate =
                new MathConjectureSubstrateDelegate(direct, /*juliaMathClient*/ null);

        for (int[] win : windows().toList()) {
            int start = win[0];
            int size = win[1];
            Map<String, Double> direct_  = direct.evaluateWindow(start, size);
            Map<String, Double> delegate_ = delegate.evaluateWindow(start, size);
            assertThat(delegate_).as("delegate legacy-only parity at start=%d size=%d",
                    start, size).isEqualTo(direct_);
        }
    }
}
