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

    // ── not_ported routing ────────────────────────────────────────────

    @Test
    void mathTopic2_notYetPorted_returnsEmpty() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // MATHEMATICS topic 2 (goldbachPartitions) isn't ported yet.
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("MATHEMATICS", 2, 3, 0.0, sp);
        assertThat(side).as("MATHEMATICS topic 2 not yet ported").isEmpty();
    }

    @Test
    void physicsDomain_notYetPorted_returnsEmpty() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        // No PHYSICS domain port yet — Optional.empty() across the board.
        Optional<JuliaMathClient.PhysicsTopicResultWire> side =
                liveClient().physicsTopic("PHYSICS", 0, 3, 0.0, sp);
        assertThat(side).as("PHYSICS domain not yet ported").isEmpty();
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
