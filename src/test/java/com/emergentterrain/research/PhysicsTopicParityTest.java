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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.5b — parity pin for {@link MathPhysicsEngine#compute}
 * against the sidecar's {@code /physics/topic} endpoint. Vertical
 * slice: MATHEMATICS topic 0 (primeConstellations) only — other
 * (domain, topicIdx) pairs are confirmed to return Optional.empty()
 * (the not_ported routing signal) so JVM callers fall back to the
 * in-process MathPhysicsEngine.compute.
 *
 * <p>Numeric parity bar (same as §28.5a /predicate/scan): the
 * scalars + claim status match byte-identically. Prose templates
 * stay JVM-side, so we don't fight String.format vs @sprintf.
 */
@Tag("integration")
class PhysicsTopicParityTest {

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

    // ── MATHEMATICS topic 0 (primeConstellations) — the ported slice ──

    @Test
    void mathTopic0_neutralParams_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 0, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 0, param1, param2, sp);

        assertThat(side).as("sidecar should have ported MATHEMATICS topic 0").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();

        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.reviewValue())   .isEqualTo(jvm.reviewValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
        assertThat(s.theoryConsistent()).isEqualTo(jvm.theoryConsistent());
    }

    @Test
    void mathTopic0_smallParam1_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Small param1 → tight window (param1*10 start, param1*15 width).
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 1;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 0, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 0, param1, param2, sp);

        assertThat(side).isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    @Test
    void mathTopic0_customSearchParams_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Non-neutral search params to exercise the outer-loop
        // stepSize * 200 spacing.
        MathPhysicsEngine.SearchParams sp = new MathPhysicsEngine.SearchParams(
                0.2, 0.0, 20, 0.5, 0.0);
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 0, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 0, param1, param2, sp);

        assertThat(side).isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 1 — collatzStoppingTimes (ported §28.5b-cont) ──

    @Test
    void mathTopic1_collatz_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 1, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 1, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 1 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 4 — happyNumbers (ported §28.5b-cont) ──

    @Test
    void mathTopic4_happy_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 2;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 4, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 4, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 4 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        // claimStatus is set by the outer wrapper based on
        // maxDeviation across ALL searched windows (not just `best`),
        // so this exercises the outer-wrapper branch on both sides.
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 9+ — squarefreeDensity (default branch) ──

    @Test
    void mathTopic9_squarefreeDensity_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // Topic 9 is JVM computeMath's `default` branch — anything ≥ 9
        // routes to squarefreeDensity. Asymptotic 6/π² ≈ 0.6079.
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 9, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 9, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 9 (default) should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 2 — goldbachPartitions (ported §28.5b-cont2) ──

    @Test
    void mathTopic2_goldbach_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 2, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 2, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 2 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 6 — divisorSumAverage (ported §28.5b-cont2) ──

    @Test
    void mathTopic6_divisorSumAverage_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // Sweeps N upward from max(10, param1*4) through candidateBreadth
        // values; asymptotic π²/12 ≈ 0.8225.
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 6, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 6, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 6 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 7 — riemannEulerProduct (ported §28.5b-cont2) ──

    @Test
    void mathTopic7_riemannEulerProduct_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedS = 1.5 + param2/9; truncation 5000 on the zeta sum,
        // primes ≤ 200 on the Euler product. Slow-ish per fixture (~1s).
        int param1 = 0;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 7, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 7, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 7 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 8 — sternBrocotDepth (ported §28.5b-cont2) ──

    @Test
    void mathTopic8_sternBrocotDepth_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 8, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 8, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 8 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 3 — aliquotSequences (ported §28.5b-cont3) ──

    @Test
    void mathTopic3_aliquot_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // Aliquot walks Set-based cycle detection + ALIQUOT_X_CAP
        // overflow. seedStart = max(2, param1); count = 40. Small
        // param1 keeps the per-iteration work bounded.
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 3, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 3, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 3 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        // claimStatus is set by the outer wrapper after combining
        // predominantTerminates + trendIncreases — exercises the
        // multi-window logic on both sides.
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── MATHEMATICS topic 5 — mertensPartialSums (ported §28.5b-cont3) ──

    @Test
    void mathTopic5_mertens_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedStart = max(10, param1*5); width = 200. The Möbius
        // primitive port lands in physics_primitives.jl alongside
        // is_prime/sigma_sum/is_squarefree.
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("MATHEMATICS", 5, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 5, param1, param2, sp);

        assertThat(side).as("MATHEMATICS topic 5 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── PHYSICS topic 0 — projectileWithDrag (ported §28.5c) ──

    @Test
    void physicsTopic0_projectileDrag_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedV0 = 10 + param2·5; k = param1/1000. Euler-integrates a
        // ballistic trajectory under quadratic drag — sensitive to
        // sin/cos/sqrt bit-identity across the JVM and Julia stacks
        // because 5000 Euler steps amplify any ulp-scale divergence.
        // The riemann/euler-product parity (5000-term zeta sum) is the
        // existence proof that Math.sin/cos/pow agree byte-identically.
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("PHYSICS", 0, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 0, param1, param2, sp);

        assertThat(side).as("PHYSICS topic 0 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── PHYSICS topic 1 — pendulumBreakdown (ported §28.5c-cont) ──

    @Test
    void physicsTopic1_pendulumBreakdown_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedL = 0.1 + param1/200. Inner Maclaurin-style series for
        // the elliptic-integral period, swept over θ in 5°..80°.
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("PHYSICS", 1, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 1, param1, param2, sp);

        assertThat(side).as("PHYSICS topic 1 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── PHYSICS topic 2 — vanDerWaalsDeviation (ported §28.5c-cont) ──

    @Test
    void physicsTopic2_vanDerWaals_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedT = 200 + param1·3 K, tolerance = 0.01. 30-step Newton
        // iteration per (P, T) pair — pure arithmetic, no transcendentals.
        int param1 = 4;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("PHYSICS", 2, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 2, param1, param2, sp);

        assertThat(side).as("PHYSICS topic 2 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── PHYSICS topic 3 — diffractionSurvey (ported §28.5c-cont2) ──

    @Test
    void physicsTopic3_diffractionSurvey_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedD = 1.0 + param1/50; λ_ref = 400 + param2·40. Sweeps
        // 7 fixed wavelengths to bracket maxOrder/minOrder.
        int param1 = 3;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("PHYSICS", 3, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 3, param1, param2, sp);

        assertThat(side).as("PHYSICS topic 3 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── PHYSICS topic 4 — rlcDampingTransition (ported §28.5c-cont2) ──

    @Test
    void physicsTopic4_rlcDamping_matchesLegacy() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // seedL = 0.001 + param1/10000; C = 1e-6 + param2·1e-7. Outer
        // loop uses a DIVISIVE step (L decreases) per JVM line 1461 —
        // this exercises that branch.
        int param1 = 5;
        double param2 = 0.0;

        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute("PHYSICS", 4, param1, param2, sp);
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 4, param1, param2, sp);

        assertThat(side).as("PHYSICS topic 4 should be ported").isPresent();
        JuliaMathClient.PhysicsTopicResultWire s = side.get();
        assertThat(s.primaryValue())  .isEqualTo(jvm.primaryValue());
        assertThat(s.secondaryValue()).isEqualTo(jvm.secondaryValue());
        assertThat(s.tertiaryValue()) .isEqualTo(jvm.tertiaryValue());
        assertThat(s.claimStatus())   .isEqualTo(jvm.claimStatus().name());
    }

    // ── not_ported routing — remaining PHYSICS topics + other domains ──

    @Test
    void physicsTopic5_notYetPorted_returnsEmpty() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // PHYSICS topic 5 (wienPeak) — pending; deferred.
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 5, 3, 0.0, sp);
        assertThat(side).as("PHYSICS topic 5 not yet ported").isEmpty();
    }

    @Test
    void biologyDomain_notYetPorted_returnsEmpty() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("BIOLOGY", 0, 3, 0.0, sp);
        assertThat(side).as("BIOLOGY domain not yet ported").isEmpty();
    }
}
