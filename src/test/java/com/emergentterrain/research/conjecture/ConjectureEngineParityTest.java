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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 28 §28.3 — parity pin for the JVM-side
 * {@link ConjectureEngine#seed} / {@link ConjectureEngine#update}
 * against the sidecar's {@code /conjecture/seed} and
 * {@code /conjecture/update} routes.
 *
 * <p>Tagged {@code @Tag("integration")} so it only runs when the
 * sidecar is up. The §28.3 acceptance bar is byte-identical output
 * (same Inequality ordering, same {@code k}/{@code c} values, same
 * confirmed/retired counts, same Witness payloads) between the two
 * paths.
 */
@Tag("integration")
class ConjectureEngineParityTest {

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

    /**
     * A small but non-trivial fixture: four samples on a five-name
     * vocabulary with a mix of correlated and uncorrelated series. The
     * vocabulary contains a perfect-correlation pair (a, b) that should
     * be filtered out as a near-identity, plus several pairs that should
     * survive with various (k, c) fits.
     */
    private static List<Map<String, Double>> fixtureSamples() {
        return List.of(
                Map.of("a", 1.0, "b", 2.0, "c", 0.3, "d", 5.0, "e", 0.05),
                Map.of("a", 2.0, "b", 4.0, "c", 0.7, "d", 5.5, "e", 0.12),
                Map.of("a", 3.0, "b", 6.0, "c", 0.4, "d", 4.8, "e", 0.21),
                Map.of("a", 4.0, "b", 8.0, "c", 0.9, "d", 6.1, "e", 0.30)
        );
    }

    private static List<String> fixtureVocab() {
        return List.of("a", "b", "c", "d", "e");
    }

    // ── seed() parity ────────────────────────────────────────────────

    @Test
    void seed_byteIdenticalAcrossJvmAndSidecar() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        List<Map<String, Double>> samples = fixtureSamples();
        List<String> vocab = fixtureVocab();

        // In-process JVM result.
        List<Inequality> jvm = ConjectureEngine.seed(samples, vocab);

        // Sidecar result.
        JuliaMathClient client = liveClient();
        List<Inequality> sidecar = client.conjectureSeed(samples, vocab);

        assertInequalityListsEqual(jvm, sidecar, "seed");
    }

    // ── update() parity ──────────────────────────────────────────────

    @Test
    void update_byteIdenticalAcrossJvmAndSidecar() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);

        // Seed once via the JVM so both paths start from the same store.
        List<Map<String, Double>> samples = fixtureSamples();
        List<String> vocab = fixtureVocab();
        List<Inequality> seedStore = ConjectureEngine.seed(samples, vocab);

        // Build a second-run sample set with one row that refutes some
        // conjectures and one row that tightens others. The "_tick"
        // field exercises the tick-from-sample magic in both impls.
        Map<String, Double> tightenRow = new LinkedHashMap<>();
        tightenRow.put("a", 1.2);
        tightenRow.put("b", 2.4);
        tightenRow.put("c", 0.35);
        tightenRow.put("d", 5.1);
        tightenRow.put("e", 0.08);
        tightenRow.put("_tick", 1000.0);

        Map<String, Double> refuteRow = new LinkedHashMap<>();
        refuteRow.put("a", 5.0);
        refuteRow.put("b", 2.0);
        refuteRow.put("c", 9.9);   // big margin on c-vs-anything pairs
        refuteRow.put("d", 1.0);
        refuteRow.put("e", 9.9);
        refuteRow.put("_tick", 1001.0);
        List<Map<String, Double>> run2 = List.of(tightenRow, refuteRow);

        // In-process JVM (mutates the store in place).
        List<Inequality> jvmStore = deepCopyStore(seedStore);
        ConjectureEngine.UpdateResult jvmResult =
                ConjectureEngine.update(jvmStore, "W500_low", "W500", run2);

        // Sidecar path. Start from an identical deep-copy of the seed.
        List<Inequality> sidecarStartingStore = deepCopyStore(seedStore);
        JuliaMathClient client = liveClient();
        JuliaMathClient.UpdateResultWire sideResult =
                client.conjectureUpdate(sidecarStartingStore, "W500_low", "W500", run2);

        // Counts match.
        assertThat(sideResult.confirmed())
                .as("confirmed count").isEqualTo(jvmResult.confirmed());
        assertThat(sideResult.retired())
                .as("retired count").isEqualTo(jvmResult.retired());

        // Retired items match.
        assertInequalityListsEqual(jvmResult.retiredItems(),
                sideResult.retiredItems(), "update retired");

        // Surviving store matches.
        assertInequalityListsEqual(jvmStore, sideResult.store(), "update store");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static List<Inequality> deepCopyStore(List<Inequality> src) {
        List<Inequality> out = new ArrayList<>(src.size());
        for (Inequality e : src) {
            out.add(Inequality.fromFields(
                    e.lhs(), e.rhs(), e.k(), e.c(),
                    e.confirmedRuns(),
                    new ArrayList<>(e.confirmedConfigs()),
                    new ArrayList<>(e.refutedBy()),
                    e.p99Slack(),
                    new LinkedHashMap<>(e.slackByConfig()),
                    e.droppedByKnob()));
        }
        return out;
    }

    private static void assertInequalityListsEqual(
            List<Inequality> expected, List<Inequality> actual, String label) {
        assertThat(actual).as("%s: result size", label).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Inequality e = expected.get(i);
            Inequality a = actual.get(i);
            assertThat(a.lhs()).as("%s[%d] lhs", label, i).isEqualTo(e.lhs());
            assertThat(a.rhs()).as("%s[%d] rhs", label, i).isEqualTo(e.rhs());
            assertThat(a.k()).as("%s[%d] k", label, i).isEqualTo(e.k());
            assertThat(a.c()).as("%s[%d] c", label, i).isEqualTo(e.c());
            assertThat(a.confirmedRuns()).as("%s[%d] confirmedRuns", label, i)
                    .isEqualTo(e.confirmedRuns());
            assertThat(a.confirmedConfigs()).as("%s[%d] confirmedConfigs", label, i)
                    .containsExactlyElementsOf(e.confirmedConfigs());
            assertThat(a.refutedBy()).as("%s[%d] refutedBy", label, i)
                    .containsExactlyElementsOf(e.refutedBy());
            assertThat(a.p99Slack()).as("%s[%d] p99Slack", label, i)
                    .isEqualTo(e.p99Slack());
            assertThat(a.slackByConfig()).as("%s[%d] slackByConfig", label, i)
                    .containsAllEntriesOf(e.slackByConfig());
            assertThat(a.droppedByKnob()).as("%s[%d] droppedByKnob", label, i)
                    .isEqualTo(e.droppedByKnob());
        }
    }
}
