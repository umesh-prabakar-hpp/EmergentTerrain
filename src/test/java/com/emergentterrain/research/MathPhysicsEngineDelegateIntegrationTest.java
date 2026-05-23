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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.5 — live-sidecar integration test for the
 * {@link MathPhysicsEngineDelegate}'s shadow-read path. The
 * counterpart to the unit test {@link MathPhysicsEngineDelegateTest}
 * (which mocks JuliaMathClient): this one wires a real client
 * against a running sidecar and asserts the parity counters tick
 * up correctly under live traffic.
 *
 * <p>Acceptance bar — the burn-in stand-in for the §28.6 flag flip:
 * after firing one compute call per ported (domain, topic) pair,
 * the delegate's stats show:
 * <ul>
 *   <li>{@code shadowCalls = totalCalls} (every call shadow-read)</li>
 *   <li>{@code parityMatches = shadowCalls} (every call matched)</li>
 *   <li>{@code parityMismatches = 0} (zero divergence)</li>
 *   <li>{@code sidecarErrors = 0} (no transport failures)</li>
 *   <li>{@code sidecarEmpty = 0} (no not-ported responses; all
 *       9 domains land after §28.5h)</li>
 * </ul>
 *
 * <p>Skipped via {@code assumeTrue} when the sidecar isn't reachable;
 * the test is the only consumer of the live shadow path so it's
 * tagged {@code @Tag("integration")} for parity with the rest of the
 * §28.5 test suite.
 */
@Tag("integration")
class MathPhysicsEngineDelegateIntegrationTest {

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
                            .GET().build(),
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

    /**
     * One representative (domain, topic, param1, param2) per JVM
     * domain. Mirrors the param choices in {@link PhysicsTopicParityTest}
     * so any mismatch here also surfaces there — gives a fast,
     * single-test signal of cross-domain parity health.
     */
    private static final List<TopicCall> CALLS = List.of(
            new TopicCall("MATHEMATICS",              0, 3, 0.0),
            new TopicCall("MATHEMATICS",              5, 3, 0.0),  // mertens
            new TopicCall("PHYSICS",                  0, 3, 0.0),  // projectile
            new TopicCall("PHYSICS",                  9, 5, 0.0),  // stefan-boltzmann (default)
            new TopicCall("MATERIALS",                0, 4, 0.0),  // composite failure
            new TopicCall("MATERIALS",                3, 5, 0.0),  // arrhenius (Math.exp)
            new TopicCall("INTERDISCIPLINARY",        0, 5, 0.0),  // collatz-rlc
            new TopicCall("INTERDISCIPLINARY",        6, 3, 0.0),  // schottky (exp + bound)
            new TopicCall("BIOLOGY",                  1, 5, 0.0),  // lotka-volterra (ODE)
            new TopicCall("BIOLOGY",                  4, 0, 0.0),  // hardy-weinberg (default)
            new TopicCall("CHEMISTRY",                3, 5, 0.0),  // van't hoff (exp + log)
            new TopicCall("CHEMISTRY",                4, 3, 0.0),  // compressibility (default)
            new TopicCall("ARCHITECTURE_TERRESTRIAL", 1, 3, 0.0),
            new TopicCall("ARCHITECTURE_TERRESTRIAL", 4, 5, 0.0),  // daylight (default)
            new TopicCall("ARCHITECTURE_LIQUID",      3, 5, 0.0),  // drag (Cd transitions)
            new TopicCall("ARCHITECTURE_LIQUID",      4, 3, 0.0),  // henry's law (default)
            new TopicCall("ARCHITECTURE_SPACE",       1, 4, 0.0),  // vacuum thermal (pow 0.25)
            new TopicCall("ARCHITECTURE_SPACE",       4, 3, 0.0)); // orbital decay (default)

    private record TopicCall(String domain, int topicIdx, int param1, double param2) {}

    @Test
    void shadowReadOnLiveSidecar_zeroMismatches_acrossAllDomains() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate =
                new MathPhysicsEngineDelegate(/*shadowReadEnabled*/ true, client);

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        for (TopicCall c : CALLS) {
            MathPhysicsEngine.ResearchResult result =
                    delegate.compute(c.domain(), c.topicIdx(), c.param1(), c.param2(), sp);
            // Shadow mode must always return the in-process value, even
            // on a match. Double-check by reconstructing the in-process
            // result and asserting identity at the scalar level.
            MathPhysicsEngine.ResearchResult jvm = MathPhysicsEngine.compute(
                    c.domain(), c.topicIdx(), c.param1(), c.param2(), sp);
            assertThat(result.primaryValue()).isEqualTo(jvm.primaryValue());
            assertThat(result.claimStatus()).isEqualTo(jvm.claimStatus());
        }

        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.totalCalls())       .isEqualTo(CALLS.size());
        assertThat(s.shadowCalls())      .isEqualTo(CALLS.size());
        // The burn-in success criterion for the §28.6 flag flip.
        assertThat(s.parityMatches())    .isEqualTo(CALLS.size());
        assertThat(s.parityMismatches()) .isZero();
        assertThat(s.sidecarErrors())    .isZero();
        assertThat(s.sidecarEmpty())     .isZero();
        assertThat(s.shadowReadEnabled()).isTrue();
    }

    /**
     * Compute called with the convenience overload (no SearchParams)
     * must still go through the shadow-read path. Catches a regression
     * where the overload bypassed the delegate's stat tracking.
     */
    @Test
    void convenienceOverload_runsShadowRead() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate =
                new MathPhysicsEngineDelegate(true, client);

        delegate.compute("MATHEMATICS", 0, 3, 0.0);

        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls())     .isEqualTo(1);
        assertThat(s.parityMatches())   .isEqualTo(1);
        assertThat(s.parityMismatches()).isZero();
    }

    /**
     * Phase 28 §28.6b — the cross-language half of the prose-parity story.
     * With {@code app.julia.physics.topic.primary} on, the delegate serves the
     * {@link SidecarResultComposer}-built result for MATHEMATICS:0. This asserts
     * that result is byte-identical to the in-process compute across <em>every</em>
     * ResearchResult field — findings, theoryCheck, claimAssessment, hypothesis,
     * equation prose plus the four scalars and claimStatus — proving the sidecar
     * emits the topic-0 {@code detail} keys with byte-equal values and that the
     * JVM composes the same prose from them. As more composer branches land, add
     * the corresponding (domain, topic) calls here.
     */
    @Test
    void primaryReadOnLiveSidecar_proseByteIdenticalToInProcess_mathematics0() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate =
                new MathPhysicsEngineDelegate(/*shadow*/ false, /*primary*/ true, client);

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        for (TopicCall c : List.of(new TopicCall("MATHEMATICS", 0, 3, 0.0))) {
            MathPhysicsEngine.ResearchResult served =
                    delegate.compute(c.domain(), c.topicIdx(), c.param1(), c.param2(), sp);
            MathPhysicsEngine.ResearchResult jvm = MathPhysicsEngine.compute(
                    c.domain(), c.topicIdx(), c.param1(), c.param2(), sp);
            // Full-record equality — the §28.6b byte-exact acceptance bar.
            assertThat(served).isEqualTo(jvm);
        }
        // Zero divergence recorded on the scalar parity check too.
        assertThat(delegate.stats().parityMismatches()).isZero();
    }
}
