package com.emergentterrain.service;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 22 §22.7 — end-to-end acceptance for the
 * {@code MathToolSuite} → {@code JuliaMathClient} → sidecar
 * delegation path. Verifies that when the Julia client is wired AND
 * reachable, the {@code run_symbolic} and {@code evaluate_symbolic}
 * tools actually return Julia-evaluated results stamped with
 * {@code [via=julia]} — the marker EmergentTerrain logs to
 * distinguish the back-end path in the operational log.
 *
 * <p>Skips (via {@link Assumptions#assumeTrue}) when the sidecar
 * isn't reachable at {@code localhost:8088} so CI runs without it
 * don't go red.
 *
 * <p>To run manually after bringing up the sidecar:
 * <pre>{@code
 *   cd julia-math-sidecar && ./scripts/start.sh &
 *   cd EmergentTerrain && mvn test \
 *       -Dtest=MathToolSuiteJuliaDelegationTest \
 *       -Dnet.bytebuddy.experimental=true
 * }</pre>
 */
class MathToolSuiteJuliaDelegationTest {

    private static final String BASE_URL = "http://localhost:8088";

    private static JuliaMathClient client;

    @BeforeAll
    static void probeSidecar() {
        client = new JuliaMathClient(
                /*enabled*/ true,
                BASE_URL,
                /*timeoutMs*/ 10_000,
                /*healthProbeTimeoutMs*/ 1_000,
                new ObjectMapper());
        Assumptions.assumeTrue(client.probeHealth(),
                "julia-math-sidecar not running on " + BASE_URL
                        + " — skipping end-to-end delegation tests. "
                        + "Start it via `cd julia-math-sidecar && ./scripts/start.sh`.");
    }

    /**
     * Invoke a private MathToolSuite method via reflection. The
     * production call path goes through {@code buildExecutor()} +
     * the tool dispatch switch, which is OK for an integration test
     * but adds enough ceremony that direct reflection on the private
     * handler reads cleaner. The tradeoff: reflection couples this
     * test to method names — if {@code runSymbolic} is renamed, the
     * test breaks loudly, which is what we want.
     */
    private static String invokePrivate(MathToolSuite suite, String name, ObjectNode args)
            throws Exception {
        Method m = MathToolSuite.class.getDeclaredMethod(name,
                com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        return (String) m.invoke(suite, args);
    }

    private static MathToolSuite buildSuiteWithRealJulia() {
        return new MathToolSuite(
                mock(LlmRouter.class),
                new ObjectMapper(),
                /*gpu*/ null,
                client);
    }

    @Test
    void runSymbolic_delegatesToJulia_stampsViaMarker() throws Exception {
        MathToolSuite suite = buildSuiteWithRealJulia();
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("expression", "floor(2.04e-6 / 400e-9)");
        args.put("rationale", "Bragg max diffraction order at 400nm");

        String result = invokePrivate(suite, "runSymbolic", args);

        assertThat(result).contains("[via=julia]");
        assertThat(result).contains("5.0");
        // Both back-ends share the "Symbolic result for X: output=Y"
        // wrapper — only the [via=julia] suffix distinguishes them.
        // Symja's response would END with "[Rationale: ...]" with no
        // via marker, so pin the via marker as the path-of-record.
    }

    @Test
    void runSymbolic_fallsThroughToSymja_onJuliaParseError() throws Exception {
        // Mathematica-syntax input — Julia's parser will fail and the
        // MathToolSuite fall-through should land on Symja, which DOES
        // understand this input. We don't pin Symja's exact output
        // (varies by version) but confirm Julia's via-marker is absent
        // (proving the fallback fired).
        MathToolSuite suite = buildSuiteWithRealJulia();
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("expression", "Solve[x^2 - 4 == 0, x]");  // Mathematica syntax
        args.put("rationale", "verify the fallback path stays intact");

        String result = invokePrivate(suite, "runSymbolic", args);

        // The [via=julia] marker MUST be absent — Julia rejected the
        // Mathematica syntax, Symja handled it.
        assertThat(result).doesNotContain("[via=julia]");
    }

    @Test
    void evaluateSymbolic_delegatesToJulia_withRelaxedRangeCap() throws Exception {
        // Phase 22.5 — Julia path accepts up to 100_000 range. Ask
        // for a 5000-element range that the Symja path would reject
        // (cap 1000). Successful response with [via=julia] proves
        // both the delegation AND the relaxed cap landed.
        MathToolSuite suite = buildSuiteWithRealJulia();
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("expression", "n^2");
        args.put("range_start", 1);
        args.put("range_end", 5001);  // 5000 elements; > 1000 Symja cap
        args.put("rationale", "verify the relaxed range works under Julia");

        String result = invokePrivate(suite, "evaluateSymbolic", args);

        assertThat(result).contains("[via=julia]");
        // Smoke check on the stats — mean of n^2 for n=1..5000 is
        // (1 + 5000)(2·5000+1) / 6 / 5000 ≈ 8334333.5, and max is
        // 5000^2 = 2.5e7.
        assertThat(result).contains("max=");
    }

    @Test
    void toolsJson_advertisesJuliaDescriptions_whenSidecarReachable() {
        // The `useJuliaDescriptions()` predicate flips the tool schema
        // to Julia-flavoured descriptions when the client is
        // available. Confirms the LLM would see the right prompt at
        // the next planning round.
        MathToolSuite suite = buildSuiteWithRealJulia();
        String json = suite.toolsJson(true);
        assertThat(json).contains("Symbolics.jl");
        assertThat(json).contains("NATIVE JULIA SYNTAX");
        assertThat(json).contains("100000");  // relaxed range cap
    }

    @Test
    void sidecarMetrics_observeDelegationCalls() throws Exception {
        // After making a delegation call, the JuliaMathClient's
        // counters MUST reflect it. Closes the loop with §22.6's
        // reporter — the JVM-side metrics capture what /metrics on
        // the sidecar would miss (any fall-through cases).
        var before = client.snapshot();
        MathToolSuite suite = buildSuiteWithRealJulia();
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("expression", "asin(0.154 / (2*0.112)) * 180 / π");
        args.put("rationale", "Bragg diffraction angle");
        invokePrivate(suite, "runSymbolic", args);
        var after = client.snapshot();

        assertThat(after.okCalls()).isGreaterThan(before.okCalls());
        assertThat(after.latencyMsTotal()).isGreaterThan(before.latencyMsTotal());
    }
}
