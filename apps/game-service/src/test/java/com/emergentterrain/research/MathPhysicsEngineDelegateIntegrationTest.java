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
 * Phase 28 §28.6 — live-sidecar integration test for the sidecar-only
 * {@link MathPhysicsEngineDelegate}.
 *
 * <p>After the §28.6 legacy removal there is no in-process engine to compare
 * against, so the acceptance bar is no longer cross-path parity — it is that the
 * sidecar + {@link SidecarResultComposer} together serve a well-formed
 * {@link MathPhysicsEngine.ResearchResult} for <strong>every</strong>
 * (domain, topic) pair the engine claims to cover (all 9 domains, every topic per
 * {@code topicCount} — 63 total). A missing {@code detail} key, an unknown domain,
 * or a transport drop would surface as a thrown {@link IllegalStateException}
 * here, failing the test loudly rather than silently degrading.
 *
 * <p>Skipped via {@code assumeTrue} when the sidecar isn't reachable; tagged
 * {@code @Tag("integration")} so it only runs when explicitly selected against a
 * running sidecar.
 */
@Tag("integration")
class MathPhysicsEngineDelegateIntegrationTest {

    private static final String SIDECAR_URL =
            System.getenv().getOrDefault("JULIA_SIDECAR_URL", "http://localhost:8088");

    private static final String[] DOMAINS = {
            "MATHEMATICS", "PHYSICS", "MATERIALS", "INTERDISCIPLINARY", "BIOLOGY",
            "CHEMISTRY", "ARCHITECTURE_TERRESTRIAL", "ARCHITECTURE_LIQUID",
            "ARCHITECTURE_SPACE"};

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
     * The full §28.6 acceptance loop: fire one compute per (domain, topic) against
     * the live sidecar and assert the composed result is well-formed. Any topic the
     * sidecar can't serve — missing detail key, unknown domain, transport error —
     * throws out of {@code compute}, failing the test.
     */
    @Test
    void servesWellFormedResultForEveryTopic_acrossAllDomains() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(client);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();

        for (String domain : DOMAINS) {
            int topicCount = MathPhysicsEngine.topicCount(domain);
            for (int topic = 0; topic < topicCount; topic++) {
                MathPhysicsEngine.ResearchResult served =
                        delegate.compute(domain, topic, 3, 0.0, sp);

                assertThat(served).as("%s:%d served result", domain, topic).isNotNull();
                assertThat(served.findings()).as("%s:%d findings", domain, topic).isNotBlank();
                assertThat(served.claimStatus()).as("%s:%d claimStatus", domain, topic).isNotNull();
                assertThat(Double.isFinite(served.primaryValue()))
                        .as("%s:%d primaryValue finite", domain, topic).isTrue();
                assertThat(Double.isFinite(served.reviewValue()))
                        .as("%s:%d reviewValue finite", domain, topic).isTrue();
            }
        }
    }

    /**
     * The composer is a pure function of the sidecar detail, so two computes of the
     * same topic must yield byte-identical results — a determinism check on the
     * whole sidecar → wire → compose path.
     */
    @Test
    void composedResultIsDeterministic() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(client);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();

        MathPhysicsEngine.ResearchResult first  = delegate.compute("MATHEMATICS", 0, 3, 0.0, sp);
        MathPhysicsEngine.ResearchResult second = delegate.compute("MATHEMATICS", 0, 3, 0.0, sp);

        assertThat(second).isEqualTo(first);
    }

    /**
     * The no-SearchParams convenience overload must still reach the sidecar and
     * serve a composed result (it just supplies a neutral genome).
     */
    @Test
    void convenienceOverload_servesResult() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        JuliaMathClient client = liveClient();
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(client);

        MathPhysicsEngine.ResearchResult result = delegate.compute("MATHEMATICS", 0, 3, 0.0);

        assertThat(result).isNotNull();
        assertThat(result.findings()).isNotBlank();
    }
}
