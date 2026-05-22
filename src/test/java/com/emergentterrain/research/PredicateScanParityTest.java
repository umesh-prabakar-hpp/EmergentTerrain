package com.emergentterrain.research;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.5a — parity pin for {@link DynamicComputeEngine#compute}
 * against the sidecar's {@code /predicate/scan} endpoint.
 *
 * <p>Tagged {@code @Tag("integration")} so it only runs when the
 * sidecar is up. The §28.5a acceptance bar is byte-identical
 * <em>numeric</em> output (count, fraction, mean, se, max,
 * deviation, status) — the JVM caller owns the prose templates so
 * we don't fight Java {@code String.format} vs Julia {@code @sprintf}
 * formatting drift.
 *
 * <p>The JVM verification path: run {@code DynamicComputeEngine.compute}
 * to get a {@link MathPhysicsEngine.ResearchResult}; extract numeric
 * fields. Run {@code JuliaMathClient.predicateScan} to get the
 * {@link JuliaMathClient.PredicateScanResultWire}. Assert the
 * corresponding fields match exactly.
 */
@Tag("integration")
class PredicateScanParityTest {

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

    private static JuliaMathClient liveClient() {
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ true, SIDECAR_URL, 10_000, 3_000,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                /*conjectureEnabled*/ true, 10_000,
                new ObjectMapper());
        client.probeHealth();
        return client;
    }

    // ── fraction_satisfying — single predicate ─────────────────────────

    @Test
    void isPrime_fraction_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Fraction of primes in [1, 200). Baseline 0.2 is roughly the
        // prime-density expectation around 1/log(n) for this range.
        DynamicProblem problem = problem("isPrime", "fraction_satisfying", 1, 200, 0.2);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isPrime", "fraction_satisfying", 1, 200, 0.2);

        // Numeric parity. tertiaryValue holds `n` (matches the JVM's
        // ResearchResult(findings, deviation, fraction, n) construction).
        assertThat(side.n()).isEqualTo((int) jvm.tertiaryValue());
        assertThat(side.fraction()).isEqualTo(jvm.secondaryValue());
        assertThat(side.deviation()).isEqualTo(jvm.primaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    @Test
    void isHappy_fraction_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        DynamicProblem problem = problem("isHappy", "fraction_satisfying", 1, 100, 0.15);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isHappy", "fraction_satisfying", 1, 100, 0.15);

        assertThat(side.fraction()).isEqualTo(jvm.secondaryValue());
        assertThat(side.deviation()).isEqualTo(jvm.primaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    // ── Composed predicate — AND / OR parsers ─────────────────────────

    @Test
    void andPredicate_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Happy primes — small set; fraction is single digits per
        // hundred. Tests the AND-parser path.
        DynamicProblem problem = problem("isHappy AND isPrime", "fraction_satisfying",
                1, 300, 0.05);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isHappy AND isPrime", "fraction_satisfying",
                        1, 300, 0.05);

        assertThat(side.fraction()).isEqualTo(jvm.secondaryValue());
        assertThat(side.deviation()).isEqualTo(jvm.primaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    @Test
    void orPredicate_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        DynamicProblem problem = problem("isAbundant OR isPerfect", "fraction_satisfying",
                1, 300, 0.25);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isAbundant OR isPerfect", "fraction_satisfying",
                        1, 300, 0.25);

        assertThat(side.fraction()).isEqualTo(jvm.secondaryValue());
        assertThat(side.deviation()).isEqualTo(jvm.primaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    // ── mean_of / max_of metrics ──────────────────────────────────────

    @Test
    void meanCollatzSteps_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Average collatz steps over primes in [1, 200). The expected
        // value for primes < 200 is ~50.
        DynamicProblem problem = problem("isPrime", "mean_of:collatzSteps",
                1, 200, 50.0);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isPrime", "mean_of:collatzSteps",
                        1, 200, 50.0);

        // For mean_of, JVM stores: primary=deviation, secondary=mean, tertiary=se.
        assertThat(side.mean()).isEqualTo(jvm.secondaryValue());
        assertThat(side.se()).isEqualTo(jvm.tertiaryValue());
        assertThat(side.deviation()).isEqualTo(jvm.primaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    @Test
    void maxSigmaSum_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Max sigma over abundant numbers in [12, 100).
        DynamicProblem problem = problem("isAbundant", "max_of:sigmaSum",
                12, 100, 50.0);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isAbundant", "max_of:sigmaSum",
                        12, 100, 50.0);

        // For max_of, JVM stores: primary=maxVal (cast to double),
        // secondary=mean, tertiary=se.
        assertThat(side.max()).isEqualTo((long) jvm.primaryValue());
        assertThat(side.mean()).isEqualTo(jvm.secondaryValue());
        assertThat(side.se()).isEqualTo(jvm.tertiaryValue());
        assertThat(side.status()).isEqualTo(jvm.claimStatus().name());
    }

    // ── Refutation case — exercises REFUTED status branch ──────────────

    @Test
    void primeFraction_refutedAgainstWildBaseline_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Predicted 80% prime density — actual is way below; should be
        // REFUTED on both paths.
        DynamicProblem problem = problem("isPrime", "fraction_satisfying", 1, 200, 0.8);
        MathPhysicsEngine.ResearchResult jvm = DynamicComputeEngine.compute(problem);
        JuliaMathClient.PredicateScanResultWire side =
                liveClient().predicateScan("isPrime", "fraction_satisfying", 1, 200, 0.8);

        assertThat(jvm.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        assertThat(side.status()).isEqualTo("REFUTED");
        assertThat(side.fraction()).isEqualTo(jvm.secondaryValue());
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private static DynamicProblem problem(String predicate, String metric,
                                           int rangeStart, int rangeEnd, double baseline) {
        return new DynamicProblem(
                /*id*/          "parity-test",
                /*description*/ "parity-test",
                rangeStart,
                rangeEnd,
                predicate,
                metric,
                baseline,
                /*hypothesis*/  "parity-test hypothesis");
    }
}
