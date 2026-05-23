package com.emergentterrain.research;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 28 §28.5 — unit test for {@link MathPhysicsEngineDelegate}.
 *
 * <p>The integration parity (sidecar process) is pinned by
 * {@link PhysicsTopicParityTest}. This file pins the delegate's
 * dispatch logic + stats accounting using a mocked JuliaMathClient,
 * so the test runs without a live sidecar.
 */
class MathPhysicsEngineDelegateTest {

    private static final String DOMAIN = "MATHEMATICS";
    private static final int    TOPIC  = 0;
    private static final int    PARAM1 = 3;
    private static final double PARAM2 = 0.0;

    @Test
    void disabled_passthrough_neverCallsSidecar() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(false, client);

        MathPhysicsEngine.ResearchResult result =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        assertThat(result).isNotNull();
        verify(client, never()).physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any());
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.totalCalls()).isEqualTo(1);
        assertThat(s.shadowCalls()).isZero();
        assertThat(s.parityMatches()).isZero();
        assertThat(s.parityMismatches()).isZero();
        assertThat(s.shadowReadEnabled()).isFalse();
    }

    @Test
    void enabled_but_clientNull_passthrough() {
        // Defends against partial wiring: flag is true but no
        // JuliaMathClient bean was injected.
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, null);
        MathPhysicsEngine.ResearchResult result =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        assertThat(result).isNotNull();
        assertThat(delegate.stats().shadowReadEnabled()).isFalse();
        assertThat(delegate.stats().shadowCalls()).isZero();
    }

    @Test
    void enabled_sidecarUnavailable_passthrough() {
        // Flag is true and client exists, but the sidecar isn't reachable.
        // Delegate must not blow up; it just skips the shadow read.
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(false);

        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, client);
        delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                MathPhysicsEngine.SearchParams.neutral());

        verify(client, never()).physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any());
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.totalCalls()).isEqualTo(1);
        assertThat(s.shadowCalls()).isZero();
    }

    @Test
    void enabled_sidecarReachable_parityMatch_bumpsMatchCounter() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.neutral();
        when(client.isAvailable()).thenReturn(true);

        // Build a wire response that mirrors the in-process result
        // byte-for-byte so parity should match.
        MathPhysicsEngine.ResearchResult jvm =
                MathPhysicsEngine.compute(DOMAIN, TOPIC, PARAM1, PARAM2, sp);
        JuliaMathClient.PhysicsTopicResultWire matching =
                new JuliaMathClient.PhysicsTopicResultWire(
                        jvm.primaryValue(),
                        jvm.secondaryValue(),
                        jvm.tertiaryValue(),
                        jvm.reviewValue(),
                        jvm.claimStatus().name(),
                        jvm.theoryConsistent(),
                        NullNode.getInstance());
        when(client.physicsTopic(eq(DOMAIN), eq(TOPIC), eq(PARAM1), eq(PARAM2), any()))
                .thenReturn(Optional.of(matching));

        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, client);
        delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2, sp);

        verify(client, times(1)).physicsTopic(eq(DOMAIN), eq(TOPIC), eq(PARAM1), eq(PARAM2), any());
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls()).isEqualTo(1);
        assertThat(s.parityMatches()).isEqualTo(1);
        assertThat(s.parityMismatches()).isZero();
        assertThat(s.sidecarErrors()).isZero();
    }

    @Test
    void enabled_sidecarReachable_parityMismatch_bumpsMismatchCounter() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(true);

        // Construct a wire response with a deliberately-bumped primaryValue
        // so the parity check trips.
        MathPhysicsEngine.ResearchResult jvm = MathPhysicsEngine.compute(
                DOMAIN, TOPIC, PARAM1, PARAM2,
                MathPhysicsEngine.SearchParams.neutral());
        JuliaMathClient.PhysicsTopicResultWire mismatched =
                new JuliaMathClient.PhysicsTopicResultWire(
                        jvm.primaryValue() + 1.0,        // diverge
                        jvm.secondaryValue(),
                        jvm.tertiaryValue(),
                        jvm.reviewValue(),
                        jvm.claimStatus().name(),
                        jvm.theoryConsistent(),
                        NullNode.getInstance());
        when(client.physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any()))
                .thenReturn(Optional.of(mismatched));

        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, client);
        MathPhysicsEngine.ResearchResult result = delegate.compute(
                DOMAIN, TOPIC, PARAM1, PARAM2,
                MathPhysicsEngine.SearchParams.neutral());

        // Crucially: the returned result is the IN-PROCESS result. Shadow
        // mode never serves sidecar values; it only counts.
        assertThat(result.primaryValue()).isEqualTo(jvm.primaryValue());
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls()).isEqualTo(1);
        assertThat(s.parityMatches()).isZero();
        assertThat(s.parityMismatches()).isEqualTo(1);
    }

    @Test
    void enabled_sidecarThrows_bumpsErrorCounter_returnsInProcessResult() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any()))
                .thenThrow(new RuntimeException("sidecar transport blew up"));

        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, client);
        MathPhysicsEngine.ResearchResult result = delegate.compute(
                DOMAIN, TOPIC, PARAM1, PARAM2,
                MathPhysicsEngine.SearchParams.neutral());

        // A sidecar error in shadow mode must NEVER block the in-process
        // path — the JVM result is returned unchanged.
        assertThat(result).isNotNull();
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls()).isEqualTo(1);
        assertThat(s.sidecarErrors()).isEqualTo(1);
        assertThat(s.parityMatches()).isZero();
        assertThat(s.parityMismatches()).isZero();
    }

    @Test
    void enabled_sidecarReturnsEmpty_bumpsEmptyCounter() {
        // Not-ported (Optional.empty) is the routing signal from the
        // sidecar for a (domain, topic) pair that isn't ported yet.
        // After §28.5h this should never happen for any of the 9 domains,
        // but the counter is here so a coverage regression is visible.
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any()))
                .thenReturn(Optional.empty());

        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(true, client);
        delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                MathPhysicsEngine.SearchParams.neutral());

        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls()).isEqualTo(1);
        assertThat(s.sidecarEmpty()).isEqualTo(1);
        assertThat(s.parityMatches()).isZero();
        assertThat(s.parityMismatches()).isZero();
    }

    // ── §28.6b primary-read cutover ──────────────────────────────────────

    /** A sidecar detail envelope with a sentinel max_gap so the composed
     *  result is unmistakably distinct from the real in-process result. */
    private static ObjectNode sentinelMathTopic0Detail() {
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

    @Test
    void primaryEnabled_coveredTopic_returnsComposedResult_notInProcess() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(true);

        ObjectNode detail = sentinelMathTopic0Detail();
        JuliaMathClient.PhysicsTopicResultWire wire =
                new JuliaMathClient.PhysicsTopicResultWire(
                        0.40, 9.0 / 60.0, 999.0, 0.40, "REFUTED", true, detail);
        when(client.physicsTopic(eq(DOMAIN), eq(TOPIC), eq(PARAM1), eq(PARAM2), any()))
                .thenReturn(Optional.of(wire));

        // Primary ON (shadow flag OFF — primary alone must still fetch + compose).
        MathPhysicsEngineDelegate delegate =
                new MathPhysicsEngineDelegate(false, true, client);
        MathPhysicsEngine.ResearchResult result =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        // The cutover served the sidecar-composed result, not the in-process one.
        assertThat(result.tertiaryValue()).isEqualTo(999.0);
        assertThat(result.findings()).contains("max_gap=999");
        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        // Composed prose is byte-identical to the shared template for this detail.
        assertThat(result).isEqualTo(
                SidecarResultComposer.composeFromDetail(DOMAIN, TOPIC, detail).get());
        // Parity is still recorded during cutover (sentinel diverges from in-process).
        MathPhysicsEngineDelegate.Stats s = delegate.stats();
        assertThat(s.shadowCalls()).isEqualTo(1);
        assertThat(s.parityMismatches()).isEqualTo(1);
    }

    @Test
    void primaryEnabled_uncoveredTopic_fallsBackToInProcess() {
        // PHYSICS:0 has no composer branch yet → primary read must fall back.
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(true);

        MathPhysicsEngine.ResearchResult jvm = MathPhysicsEngine.compute(
                "PHYSICS", 0, PARAM1, PARAM2, MathPhysicsEngine.SearchParams.neutral());
        JuliaMathClient.PhysicsTopicResultWire wire =
                new JuliaMathClient.PhysicsTopicResultWire(
                        jvm.primaryValue(), jvm.secondaryValue(), jvm.tertiaryValue(),
                        jvm.reviewValue(), jvm.claimStatus().name(), jvm.theoryConsistent(),
                        new ObjectMapper().createObjectNode());
        when(client.physicsTopic(any(), anyInt(), anyInt(), anyDouble(), any()))
                .thenReturn(Optional.of(wire));

        MathPhysicsEngineDelegate delegate =
                new MathPhysicsEngineDelegate(false, true, client);
        MathPhysicsEngine.ResearchResult result = delegate.compute(
                "PHYSICS", 0, PARAM1, PARAM2, MathPhysicsEngine.SearchParams.neutral());

        // Returned the in-process result unchanged — no composer for this topic.
        assertThat(result.findings()).isEqualTo(jvm.findings());
        assertThat(result.primaryValue()).isEqualTo(jvm.primaryValue());
    }

    @Test
    void convenienceOverload_usesNeutralSearchParams() {
        JuliaMathClient client = mock(JuliaMathClient.class);
        MathPhysicsEngineDelegate delegate = new MathPhysicsEngineDelegate(false, client);

        MathPhysicsEngine.ResearchResult fromOverload =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2);
        MathPhysicsEngine.ResearchResult fromExplicit =
                delegate.compute(DOMAIN, TOPIC, PARAM1, PARAM2,
                        MathPhysicsEngine.SearchParams.neutral());

        assertThat(fromOverload.primaryValue()).isEqualTo(fromExplicit.primaryValue());
        assertThat(fromOverload.secondaryValue()).isEqualTo(fromExplicit.secondaryValue());
        assertThat(fromOverload.claimStatus()).isEqualTo(fromExplicit.claimStatus());
    }
}
