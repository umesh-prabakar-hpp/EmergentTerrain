package com.emergentterrain.research;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 28 §28.6 — unit test for the sidecar-only {@link MathPhysicsEngineDelegate}.
 *
 * <p>After the §28.6 legacy removal the delegate has no in-process fallback: the
 * Julia sidecar is the sole source of physics-topic compute, and the JVM composes
 * the {@link MathPhysicsEngine.ResearchResult} from the sidecar's numeric
 * {@code detail} via {@link SidecarResultComposer}. So this test pins two things:
 * <ul>
 *   <li>the served result is the composer's output for the sidecar detail
 *       (no other path can produce it);</li>
 *   <li>every failure mode — null client, unavailable sidecar, empty wire,
 *       transport exception, unknown domain — surfaces as {@link IllegalStateException}
 *       rather than a silent fallback or NPE.</li>
 * </ul>
 *
 * <p>Mockito can't mock {@link JuliaMathClient} on this shell's JDK (see the
 * project notes), so the sidecar is stood in by a hand-written {@link FakeClient}
 * subclass — no Mockito on the delegate's collaborator.
 */
class MathPhysicsEngineDelegateTest {

    private static final String DOMAIN = "MATHEMATICS";
    private static final int    TOPIC  = 0;
    private static final int    PARAM1 = 3;
    private static final double PARAM2 = 0.0;

    /**
     * A valid MATHEMATICS:0 (prime-constellation) sidecar detail envelope with a
     * sentinel max_gap so the composed result is unmistakably the composer's
     * output and not some default.
     */
    private static ObjectNode mathTopic0Detail() {
        ObjectNode d = new ObjectMapper().createObjectNode();
        d.put("windowStart",  500);
        d.put("windowEnd",    560);
        d.put("primeCount",   9);
        d.put("twinCount",    1);
        d.put("cousinCount",  2);
        d.put("sexyCount",    1);
        d.put("maxGap",       999);          // sentinel — real windows never gap 999
        d.put("hlPrediction", 1.5);
        d.put("hlDeviation",  0.40);         // REFUTED band
        d.put("primeDensity", 9.0 / 60.0);
        d.put("twinDensity",  0.125);
        return d;
    }

    private static JuliaMathClient.PhysicsTopicResultWire wireFor(ObjectNode detail) {
        return new JuliaMathClient.PhysicsTopicResultWire(
                detail.path("hlDeviation").asDouble(),
                detail.path("primeDensity").asDouble(),
                detail.path("maxGap").asDouble(),
                detail.path("hlDeviation").asDouble(),
                "REFUTED", true, detail);
    }

    @Test
    void nullClient_compute_throwsIllegalState() {
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate((JuliaMathClient) null);

        assertThatThrownBy(() ->
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sidecar");
    }

    @Test
    void unavailableSidecar_compute_throwsIllegalState() {
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ false, Optional.empty(), null));

        assertThatThrownBy(() ->
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void emptyWire_compute_throwsIllegalState() {
        // not-ported / transport drop → Optional.empty() from the client.
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ true, Optional.empty(), null));

        assertThatThrownBy(() ->
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no result");
    }

    @Test
    void sidecarThrows_compute_wrapsInIllegalState() {
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ true, Optional.empty(),
                        new RuntimeException("sidecar transport blew up")));

        assertThatThrownBy(() ->
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/physics/topic failed")
                .hasRootCauseMessage("sidecar transport blew up");
    }

    @Test
    void unknownDomain_compute_throwsIllegalState() {
        // The composer has a branch for every real domain; a bogus domain string
        // is the only way composeFromDetail returns empty → surfaced as a throw.
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ true,
                        Optional.of(wireFor(mathTopic0Detail())), null));

        assertThatThrownBy(() ->
                delegate.compute("NONSENSE", TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no composer");
    }

    @Test
    void coveredTopic_compute_servesComposerOutput() {
        ObjectNode detail = mathTopic0Detail();
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ true, Optional.of(wireFor(detail)), null));

        MathPhysicsEngine.ResearchResult result =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        // The served result is exactly the composer's output for this detail —
        // the delegate has no other way to produce a ResearchResult.
        assertThat(result).isEqualTo(
                SidecarResultComposer.composeFromDetail(DOMAIN, TOPIC, detail).orElseThrow());
        assertThat(result.tertiaryValue()).isEqualTo(999.0);
        assertThat(result.findings()).contains("max_gap=999");
        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
    }

    @Test
    void convenienceOverload_matchesNeutralSearchParams() {
        ObjectNode detail = mathTopic0Detail();
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(
                new FakeClient(/*available*/ true, Optional.of(wireFor(detail)), null));

        MathPhysicsEngine.ResearchResult fromOverload =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2);
        MathPhysicsEngine.ResearchResult fromExplicit =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        // Both compose from the same sidecar detail → identical result.
        assertThat(fromOverload).isEqualTo(fromExplicit);
    }

    /**
     * Hand-written {@link JuliaMathClient} stand-in (Mockito can't mock it on this
     * shell's JDK). Constructed disabled so the real HTTP plumbing is never armed;
     * the two methods the delegate calls — {@link #isAvailable()} and
     * {@link #physicsTopic} — are overridden to return canned responses.
     */
    private static final class FakeClient extends JuliaMathClient {
        private final boolean available;
        private final Optional<PhysicsTopicResultWire> wire;
        private final RuntimeException toThrow;

        FakeClient(boolean available, Optional<PhysicsTopicResultWire> wire,
                   RuntimeException toThrow) {
            super(/*enabled*/ false, "http://localhost:0", 100L, 100L, new ObjectMapper());
            this.available = available;
            this.wire = wire;
            this.toThrow = toThrow;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Optional<PhysicsTopicResultWire> physicsTopic(
                String domain, int topicIdx, int param1, double param2,
                MathPhysicsEngine.SearchParams sp) {
            if (toThrow != null) {
                throw toThrow;
            }
            return wire;
        }
    }
}
