package com.emergentterrain.research;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 28 §28.6b — pins the JVM-side wiring of {@link SidecarResultComposer}:
 * that it reads the exact {@code detail} keys the sidecar emits, parses their
 * types correctly, and stamps the per-topic equation — so the composed result
 * is byte-identical to what {@link MathPhysicsEngine#composePrimeConstellations}
 * (the shared prose template) produces.
 *
 * <p>This is the JVM half of the §28.6b parity story. The cross-language half —
 * that the live sidecar actually emits these keys with byte-equal values — is
 * pinned by {@link MathPhysicsEngineDelegateIntegrationTest} against a running
 * sidecar. Together they close the loop without re-deriving any format string.
 */
class SidecarResultComposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A representative MATHEMATICS:0 detail envelope (PARTIAL band, hlDeviation=0.22). */
    private static ObjectNode mathTopic0Detail() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("windowStart",  100);
        d.put("windowEnd",    160);
        d.put("primeCount",   11);
        d.put("twinCount",    2);
        d.put("cousinCount",  1);
        d.put("sexyCount",    3);
        d.put("maxGap",       8);
        d.put("hlPrediction", 2.5);
        d.put("hlDeviation",  0.22);
        d.put("primeDensity", 11.0 / 60.0);
        d.put("twinDensity",  0.2);
        return d;
    }

    @Test
    void mathematics0_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = mathTopic0Detail();

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composePrimeConstellations(
                        100, 160, 11, 2, 1, 3, 8, 2.5, 0.22, 11.0 / 60.0, 0.2)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 0));

        Optional<MathPhysicsEngine.ResearchResult> actual =
                SidecarResultComposer.composeFromDetail("MATHEMATICS", 0, d);

        assertThat(actual).isPresent();
        // Record equality covers all 11 fields — findings, theoryCheck,
        // claimAssessment, hypothesis, equation prose plus the four scalars,
        // theoryConsistent flag, and claimStatus — byte-for-byte.
        assertThat(actual.get()).isEqualTo(expected);
    }

    @Test
    void mathematics0_stampsTheClosedFormEquation() {
        Optional<MathPhysicsEngine.ResearchResult> actual =
                SidecarResultComposer.composeFromDetail("MATHEMATICS", 0, mathTopic0Detail());

        assertThat(actual).isPresent();
        assertThat(actual.get().equation())
                .isEqualTo(MathPhysicsEngine.equationFor("MATHEMATICS", 0))
                .contains("Hardy–Littlewood");
        assertThat(actual.get().claimStatus())
                .isEqualTo(MathPhysicsEngine.ClaimStatus.PARTIAL);
    }

    @Test
    void mathematics3_aliquot_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("windowStart", 2);
        d.put("windowEnd", 42);
        d.put("terminate", 35);
        d.put("perfect", 1);
        d.put("cycle", 2);
        d.put("escape", 2);
        d.put("terminateFrac", 35.0 / 40.0);
        d.put("anomalyFrac", 4.0 / 40.0);
        d.put("count", 40);
        d.put("firstAnomaly", 0.05);
        d.put("lastAnomaly", 0.12);   // ≥ first + 0.05 → trend increases → SUPPORTED
        d.put("sampled", 20);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeAliquotSequencesOuter(
                        MathPhysicsEngine.composeAliquotSequencesInner(
                                2, 42, 35, 1, 2, 2, 35.0 / 40.0, 4.0 / 40.0, 40),
                        0.05, 0.12, 20)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 3));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 3, d))
                .contains(expected);
    }

    @Test
    void mathematics4_happy_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("windowStart", 10);
        d.put("windowEnd", 160);
        d.put("happyCount", 22);
        d.put("count", 150);
        d.put("avgSteps", 5.3);       // == Welford happyMean
        d.put("happyM2", 42.0);
        d.put("density", 22.0 / 150.0);
        d.put("deviation", Math.abs(22.0 / 150.0 - 0.1430));
        d.put("maxDeviation", 0.025); // ≤ 0.03 → SUPPORTED
        d.put("sampled", 18);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHappyNumbersOuter(
                        MathPhysicsEngine.composeHappyNumbersInner(
                                10, 160, 22, 150, 5.3, 42.0,
                                22.0 / 150.0, Math.abs(22.0 / 150.0 - 0.1430)),
                        0.025, 18)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 4));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 4, d))
                .contains(expected);
    }

    @Test
    void mathematics9_squarefree_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedStart", 1000);
        d.put("width", 200);
        d.put("samples", 30);
        d.put("bestDev", 0.07);       // (0.05, 0.10] → PARTIAL
        d.put("bestStart", 4200);
        d.put("bestDensity", 0.6779);
        d.put("meanDev", 0.031);
        // asymptotic + sample-sweep are not carried (recomputed / unused).

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeSquarefreeDensity(
                        1000, 200, 30, 0.07, 4200, 0.6779, 0.031)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 9));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 9, d))
                .contains(expected);
    }

    @Test
    void mathematics5_mertens_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("x", 120).put("M", -3L).put("ratio", 0.274);
        sweep.addObject().put("x", 170).put("M", 2L).put("ratio", 0.153);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedStart", 100);
        d.put("width", 20);
        d.put("bestRatio", 0.41);
        d.put("bestX", 350);
        d.put("bestM", -5L);
        d.put("samples", 20);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.mertensSweepSegment(120, -3L, 0.274)
                + MathPhysicsEngine.mertensSweepSegment(170, 2L, 0.153)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeMertensPartialSums(100, 20, 0.41, 350, -5L, 20, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 5));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 5, d))
                .contains(expected);
    }

    @Test
    void mathematics6_divisorSum_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedN", 200);
        d.put("width", 150);
        d.put("primaryValue", 0.08);     // == bestDev → (0.05?] n/a; ≤0.10 SUPPORTED
        d.put("bestN", 520);
        d.put("bestAvg", 0.812);
        d.put("secondaryValue", 0.04);   // == meanDev
        d.put("samples", 18);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeDivisorSumAverage(200, 150, 0.08, 520, 0.812, 0.04, 18)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 6));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 6, d))
                .contains(expected);
    }

    @Test
    void mathematics7_riemann_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("s", 1.5).put("ratio", 0.9988);
        sweep.addObject().put("s", 1.7).put("ratio", 0.9995);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedS", 1.5);
        d.put("primaryValue", 0.012);    // == bestDev
        d.put("bestS", 1.5);
        d.put("bestRatio", 0.9988);
        d.put("meanDev", 0.006);
        d.put("samples", 15);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.riemannSweepSegment(1.5, 0.9988)
                + MathPhysicsEngine.riemannSweepSegment(1.7, 0.9995)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeRiemannEulerProduct(1.5, 0.012, 1.5, 0.9988, 0.006, 15, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 7));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 7, d))
                .contains(expected);
    }

    @Test
    void mathematics8_sternBrocot_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("p", 1).put("q", 5).put("depth", 3);
        sweep.addObject().put("p", 8).put("q", 55).put("depth", 9);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedQ", 5);
        d.put("maxDepth", 12);
        d.put("bestP", 8);
        d.put("bestQ", 55);
        d.put("meanDepth", 6.5);
        d.put("samples", 20);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.sternBrocotSweepSegment(1, 5, 3)
                + MathPhysicsEngine.sternBrocotSweepSegment(8, 55, 9)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeSternBrocotDepth(5, 12, 8, 55, 6.5, 20, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 8));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 8, d))
                .contains(expected);
    }

    // ── PHYSICS numeric-only topics (0, 5, 7, 8, 9) ──────────────────────

    @Test
    void physics0_projectile_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("v0", 30.0);
        d.put("k", 0.01);
        d.put("bestAngle", 38);
        d.put("bestRange", 72.5);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeProjectileWithDragAt(30.0, 0.01, 38, 72.5)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 0));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 0, d)).contains(expected);
    }

    @Test
    void physics5_wien_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedT", 2000.0);
        d.put("bestT", 2300.0);
        d.put("samples", 20);
        d.put("bestLambda", 1.26e-6);
        d.put("primaryValue", 0.004);   // bestDev → SUPPORTED
        d.put("meanDev", 0.002);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeWienPeak(2000.0, 2300.0, 20, 1.26e-6, 0.004, 0.002)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 5));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 5, d)).contains(expected);
    }

    @Test
    void physics7_kepler_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedAuAu", 0.5);
        d.put("bestA", 2.0);
        d.put("samples", 15);
        d.put("primaryValue", 0.05);    // bestDev → SUPPORTED
        d.put("bestT", 2.83);
        d.put("meanDev", 0.02);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeKeplerThirdLaw(0.5, 2.0, 15, 0.05, 2.83, 0.02)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 7));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 7, d)).contains(expected);
    }

    @Test
    void physics8_snell_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedN2", 1.0);
        d.put("bestN2", 1.3);
        d.put("samples", 18);
        d.put("primaryValue", 1.4);     // bestErr → SUPPORTED
        d.put("bestTheta", 60.0);
        d.put("meanErr", 0.8);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeSnellCriticalAngle(1.0, 1.3, 18, 1.4, 60.0, 0.8)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 8));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 8, d)).contains(expected);
    }

    @Test
    void physics9_stefanBoltzmann_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedT", 300.0);
        d.put("bestT", 800.0);
        d.put("samples", 12);
        d.put("bestSigma", 5.8e-8);
        d.put("primaryValue", 0.029);   // bestDev → SUPPORTED
        d.put("meanDev", 0.015);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeStefanBoltzmann(300.0, 800.0, 12, 5.8e-8, 0.029, 0.015)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 9));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 9, d)).contains(expected);
    }

    // ── PHYSICS sample-sweep topics (1, 3, 4) ────────────────────────────

    @Test
    void physics1_pendulum_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("deg", 5).put("err", 0.02);
        sweep.addObject().put("deg", 20).put("err", 1.19);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("L", 1.0);
        d.put("T0", 2.006);
        d.put("breakdownAngle", 20);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.pendulumSweepSegment(5.0, 0.02)
                + MathPhysicsEngine.pendulumSweepSegment(20.0, 1.19)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composePendulumBreakdownAt(1.0, 2.006, 20.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 1));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 1, d)).contains(expected);
    }

    @Test
    void physics3_diffraction_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("lam", 400.0).put("orders", 5);
        sweep.addObject().put("lam", 700.0).put("orders", 2);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("dMicrons", 2.0);
        d.put("lambdaNm", 550.0);
        d.put("maxOrder", 3);
        d.put("theta1", 15.9);
        d.put("orderRatio", 2.5);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.diffractionSweepSegment(400.0, 5)
                + MathPhysicsEngine.diffractionSweepSegment(700.0, 2)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeDiffractionSurveyAt(2.0, 550.0, 3, 15.9, 2.5, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 3));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 3, d)).contains(expected);
    }

    @Test
    void physics4_rlc_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("mult", 0.1).put("Q", 25.0);
        sweep.addObject().put("mult", 1.0).put("Q", 2.5);
        sweep.addObject().put("mult", 4.0).put("Q", 0.625);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("L", 0.001);
        d.put("C", 1e-6);
        d.put("f0", 5032.9);
        d.put("critR", 63.2);
        d.put("minQ", 0.625);
        d.put("maxQ", 25.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.rlcSweepSegment(0.1, 25.0)
                + MathPhysicsEngine.rlcSweepSegment(1.0, 2.5)
                + MathPhysicsEngine.rlcSweepSegment(4.0, 0.625)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeRlcDampingTransitionAt(0.001, 1e-6, 5032.9, 63.2, 0.625, 25.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 4));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 4, d)).contains(expected);
    }

    @Test
    void physics2_vanDerWaals_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("p", 1).put("dev", 0.001);
        sweep.addObject().put("p", 50).put("dev", 0.08);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("T", 300.0);
        d.put("tolerance", 0.01);
        d.put("maxDev", 0.12);
        d.put("crossoverP", 10.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.vdwSweepSegment(1, 0.001)
                + MathPhysicsEngine.vdwSweepSegment(50, 0.08)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeVanDerWaalsDeviationAt(300.0, 0.01, 0.12, 10.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 2));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 2, d)).contains(expected);
    }

    @Test
    void physics6_schrodinger_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("Lnm", 1.0).put("E1", 6.0e-20).put("ratio", 14.5);
        sweep.addObject().put("Lnm", 0.9).put("E1", 7.4e-20).put("ratio", 17.9);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedLnm", 1.0);
        d.put("T", 300.0);
        d.put("samples", 20);
        d.put("bestRatio", 18.0);
        d.put("bestL", 0.9);
        d.put("bestE1", 7.4e-20);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.schrodingerSweepSegment(1.0, 6.0e-20, 14.5)
                + MathPhysicsEngine.schrodingerSweepSegment(0.9, 7.4e-20, 17.9)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeSchrodingerInfiniteWell(1.0, 300.0, 20, 18.0, 0.9, 7.4e-20, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("PHYSICS", 6));

        assertThat(SidecarResultComposer.composeFromDetail("PHYSICS", 6, d)).contains(expected);
    }

    // ── MATERIALS inner-window topics (0, 2, 3, 4) — recompute the winner ──

    @Test
    void materials0_compositeFailure_composesByteIdenticalToInnerMethod() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("sigmaYm", 120.0);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.compositeFailureModeAt(120.0)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 0));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 0, d)).contains(expected);
    }

    @Test
    void materials2_stiffnessCrossover_composesByteIdenticalToInnerMethod() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("EfOverEm", 66.0);
        d.put("targetMultiple", 2.0);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.compositeStiffnessCrossoverAt(66.0, 2.0)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 2));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 2, d)).contains(expected);
    }

    @Test
    void materials3_arrhenius_composesByteIdenticalToInnerMethod() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("T", 300.0);
        d.put("maxEaKJ", 90.0);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.arrheniusRuleValidityAt(300.0, 90.0)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 3));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 3, d)).contains(expected);
    }

    @Test
    void materials4_thermalStress_composesByteIdenticalToInnerMethod() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("deltaT", 200.0);
        d.put("sigmaYMPa", 250.0);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.thermalStressSafetyAt(200.0, 250.0)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 4));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 4, d)).contains(expected);
    }

    // ── MATERIALS sample-sweep topics (1, 5, 6) ──────────────────────────

    @Test
    void materials1_spherePacking_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("r", 0.001).put("eta", 0.7405);
        sweep.addObject().put("r", 0.21).put("eta", 0.78);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("etaFCC", 0.7405);
        d.put("bestEta", 0.82);
        d.put("bestR", 0.41);
        d.put("improvement", 8.0);
        d.put("samples", 40);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.spherePackingSweepSegment(0.001, 0.7405)
                + MathPhysicsEngine.spherePackingSweepSegment(0.21, 0.78)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeBinarySpherePacking(0.7405, 0.82, 0.41, 8.0, 40, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 1));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 1, d)).contains(expected);
    }

    @Test
    void materials5_hallPetch_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("d", 50.0).put("sigmaY", 120.0);
        sweep.addObject().put("d", 0.5).put("sigmaY", 600.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("sigma0", 80.0);
        d.put("k", 350.0);
        d.put("maxYield", 600.0);
        d.put("bestD", 0.5);
        d.put("samples", 30);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.hallPetchSweepSegment(50.0, 120.0)
                + MathPhysicsEngine.hallPetchSweepSegment(0.5, 600.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHallPetchStrengthening(80.0, 350.0, 600.0, 0.5, 30, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 5));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 5, d)).contains(expected);
    }

    @Test
    void materials6_bragg_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("d", 0.1).put("t1", 50.3);
        sweep.addObject().put("d", 0.2).put("t1", 22.6);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("lambdaNm", 0.154);
        d.put("seedDnm", 0.05);
        d.put("samples", 25);
        d.put("bestAngle", 50.3);
        d.put("bestD", 0.1);
        d.put("bestN", 1);
        d.put("meanOrders", 1.5);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.braggSweepSegment(0.1, 50.3)
                + MathPhysicsEngine.braggSweepSegment(0.2, 22.6)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeBraggDiffraction(0.154, 0.05, 25, 50.3, 0.1, 1, 1.5, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 6));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 6, d)).contains(expected);
    }

    @Test
    void materials7_carnot_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("Tc", 290.0).put("Th", 400.0).put("eta", 0.275);
        sweep.addObject().put("Tc", 280.0).put("Th", 500.0).put("eta", 0.44);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedTc", 280.0);
        d.put("seedTh", 400.0);
        d.put("samples", 20);
        d.put("bestEta", 0.44);
        d.put("bestTc", 280.0);
        d.put("bestTh", 500.0);
        d.put("meanEta", 0.35);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.carnotSweepSegment(290.0, 400.0, 0.275)
                + MathPhysicsEngine.carnotSweepSegment(280.0, 500.0, 0.44)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeCarnotEfficiency(280.0, 400.0, 20, 0.44, 280.0, 500.0, 0.35, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 7));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 7, d)).contains(expected);
    }

    @Test
    void materials8_basquin_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("Nf", 10000L).put("sigmaA", 520.0);
        sweep.addObject().put("Nf", 60000L).put("sigmaA", 430.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("sigmaPrimeF", 800.0);
        d.put("b", -0.085);
        d.put("bestAmp", 520.0);
        d.put("bestNf", 10000L);
        d.put("countAboveUts", 0);
        d.put("samples", 25);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.basquinSweepSegment(10000L, 520.0)
                + MathPhysicsEngine.basquinSweepSegment(60000L, 430.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeBasquinFatigue(800.0, -0.085, 520.0, 10000L, 0, 25, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 8));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 8, d)).contains(expected);
    }

    @Test
    void materials9_youngLaplace_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("r", 0.1).put("h", 14.9);
        sweep.addObject().put("r", 0.05).put("h", 29.7);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedRadiusMm", 0.1);
        d.put("maxH", 29.7);
        d.put("bestR", 0.05);
        d.put("samples", 18);
        d.put("countAboveLimit", 0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.youngLaplaceSweepSegment(0.1, 14.9)
                + MathPhysicsEngine.youngLaplaceSweepSegment(0.05, 29.7)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeYoungLaplaceCapillary(0.1, 29.7, 0.05, 18, 0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("MATERIALS", 9));

        assertThat(SidecarResultComposer.composeFromDetail("MATERIALS", 9, d)).contains(expected);
    }

    // ── INTERDISCIPLINARY chained-cross-domain topics (0–3) ──────────────

    @Test
    void interdisc0_collatzRlc_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("n", 30).put("depth", 18).put("f0", 1200.0);
        sweep.addObject().put("n", 130).put("depth", 50).put("f0", 800.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 10);
        d.put("C", 1.0e-6);
        d.put("bestN", 130);
        d.put("bestDepth", 50);
        d.put("bestF0", 800.0);
        d.put("bestL", 0.005);
        d.put("critR", 141.4);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.collatzRlcSweepSegment(30, 18, 1200.0)
                + MathPhysicsEngine.collatzRlcSweepSegment(130, 50, 800.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeCollatzRlcDepthResonance(10, 1.0e-6, 130, 50, 800.0, 0.005, 141.4, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 0));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 0, d)).contains(expected);
    }

    @Test
    void interdisc1_primeGapThermal_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("start", 100).put("end", 160).put("gap", 8).put("stress", 280.0);
        sweep.addObject().put("start", 300).put("end", 360).put("gap", 14).put("stress", 240.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 10);
        d.put("threshold", 230.0);
        d.put("bestStress", 280.0);
        d.put("bestGap", 8);
        d.put("bestStart", 100);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.primeGapSweepSegment(100, 160, 8, 280.0)
                + MathPhysicsEngine.primeGapSweepSegment(300, 360, 14, 240.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composePrimeGapDefectThermal(10, 230.0, 280.0, 8, 100, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 1));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 1, d)).contains(expected);
    }

    @Test
    void interdisc2_resonantFatigue_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("L", 0.005).put("f0", 1000.0).put("failed", 7);
        sweep.addObject().put("L", 0.003).put("f0", 1300.0).put("failed", 8);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 5);
        d.put("bestFatigueFrac", 0.78);
        d.put("bestF0", 1300.0);
        d.put("bestL", 0.003);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.resonantFatigueSweepSegment(0.005, 1000.0, 7)
                + MathPhysicsEngine.resonantFatigueSweepSegment(0.003, 1300.0, 8)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeResonantThermalFatigue(5, 0.78, 1300.0, 0.003, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 2));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 2, d)).contains(expected);
    }

    @Test
    void interdisc3_happyPacking_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("start", 70).put("density", 0.15).put("r", 0.5147).put("improvement", 8.0);
        sweep.addObject().put("start", 270).put("density", 0.16).put("r", 0.5357).put("improvement", 9.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 10);
        d.put("bestImprovement", 9.0);
        d.put("bestDensity", 0.16);
        d.put("bestStart", 270);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.happyPackingSweepSegment(70, 0.15, 0.5147, 8.0)
                + MathPhysicsEngine.happyPackingSweepSegment(270, 0.16, 0.5357, 9.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHappyPackingAnomaly(10, 9.0, 0.16, 270, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 3));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 3, d)).contains(expected);
    }

    @Test
    void interdisc4_vdwComposite_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("T", 209.0).put("crossoverP", 30.0).put("stress", 3120.0);
        sweep.addObject().put("T", 250.0).put("crossoverP", 45.0).put("stress", 4500.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param2", 0.0);
        d.put("bestStress", 4500.0);
        d.put("bestCrossoverP", 45.0);
        d.put("bestT", 250.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.vdwCompositeSweepSegment(209.0, 30.0, 3120.0)
                + MathPhysicsEngine.vdwCompositeSweepSegment(250.0, 45.0, 4500.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeVanDerWaalsCompositeStress(0.0, 4500.0, 45.0, 250.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 4));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 4, d)).contains(expected);
    }

    @Test
    void interdisc5_hallEffect_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("B", 0.07).put("n", 1e24).put("V_H", 0.044);
        sweep.addObject().put("B", 0.09).put("n", 1.1e24).put("V_H", 0.051);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 5);
        d.put("param2", 0.4);
        d.put("trueSign", 1.0);
        d.put("totalSamples", 20);
        d.put("bestAbsV", 0.051);
        d.put("bestB", 0.09);
        d.put("bestN", 1.1e24);
        d.put("signRecovery", 1.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.hallSweepSegment(0.07, 1e24, 0.044)
                + MathPhysicsEngine.hallSweepSegment(0.09, 1.1e24, 0.051)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHallEffectCarrierSign(5, 0.4, 1.0, 20, 0.051, 0.09, 1.1e24, 1.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 5));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 5, d)).contains(expected);
    }

    @Test
    void interdisc6_schottky_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("T", 205.0).put("phi", 0.42).put("J", 1.5e-3);
        sweep.addObject().put("T", 230.0).put("phi", 0.44).put("J", 8.0e-3);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 3);
        d.put("param2", 0.5);
        d.put("samples", 18);
        d.put("bestJ", 8.0e-3);
        d.put("bestT", 230.0);
        d.put("bestPhi", 0.44);
        d.put("aboveLimit", 0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.schottkySweepSegment(205.0, 0.42, 1.5e-3)
                + MathPhysicsEngine.schottkySweepSegment(230.0, 0.44, 8.0e-3)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeSchottkyThermionicEmission(3, 0.5, 18, 8.0e-3, 230.0, 0.44, 0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 6));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 6, d)).contains(expected);
    }

    @Test
    void interdisc7_doppler_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("v_s", 5.0).put("v_o", 2.0).put("fp", 1014.9).put("shift", 14.9);
        sweep.addObject().put("v_s", 10.0).put("v_o", 4.0).put("fp", 1042.1).put("shift", 42.1);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("param1", 10);
        d.put("param2", 1.0);
        d.put("samples", 25);
        d.put("bestShift", 42.1);
        d.put("bestFp", 1042.1);
        d.put("bestVs", 10.0);
        d.put("bestVo", 4.0);
        d.put("violatesBound", 0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.dopplerSweepSegment(5.0, 2.0, 1014.9, 14.9)
                + MathPhysicsEngine.dopplerSweepSegment(10.0, 4.0, 1042.1, 42.1)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeAcousticDopplerShift(10, 1.0, 25, 42.1, 1042.1, 10.0, 4.0, 0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("INTERDISCIPLINARY", 7));

        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 7, d)).contains(expected);
    }

    // ── BIOLOGY topics (0–4) — single-method aggregate+sweep ─────────────

    @Test
    void biology0_verhulst_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("t", 0.1).put("Nt", 5.5);
        sweep.addObject().put("t", 2.0).put("Nt", 18.0);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("r", 0.5);
        d.put("K", 120.0);
        d.put("samples", 20);
        d.put("primaryValue", 0.85);   // == bestDeviation
        d.put("bestN", 18.0);
        d.put("bestT", 2.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.verhulstSweepSegment(0.1, 5.5, 120.0)
                + MathPhysicsEngine.verhulstSweepSegment(2.0, 18.0, 120.0)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeVerhulstLogistic(0.5, 120.0, 20, 0.85, 18.0, 2.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("BIOLOGY", 0));

        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 0, d)).contains(expected);
    }

    @Test
    void biology1_lotkaVolterra_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("a", 0.4).put("ratio", 2.5);
        sweep.addObject().put("a", 0.45).put("ratio", 3.1);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("alpha", 0.4);
        d.put("beta", 0.02);
        d.put("samples", 18);
        d.put("bestRatio", 3.1);
        d.put("bestAlpha", 0.45);
        d.put("bestPeakX", 88.0);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.lotkaSweepSegment(0.4, 2.5)
                + MathPhysicsEngine.lotkaSweepSegment(0.45, 3.1)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeLotkaVolterra(0.4, 0.02, 18, 3.1, 0.45, 88.0, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("BIOLOGY", 1));

        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 1, d)).contains(expected);
    }

    @Test
    void biology2_michaelis_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("KmEst", 0.51).put("err", 0.02);
        sweep.addObject().put("KmEst", 0.49).put("err", 0.02);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("Km", 0.5);
        d.put("Vmax", 1.2);
        d.put("samples", 15);
        d.put("bestRecoveryErr", 0.005);
        d.put("bestKmRecovered", 0.5025);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.michaelisSweepSegment(0.51, 0.02)
                + MathPhysicsEngine.michaelisSweepSegment(0.49, 0.02)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeMichaelisMenten(0.5, 1.2, 15, 0.005, 0.5025, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("BIOLOGY", 2));

        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 2, d)).contains(expected);
    }

    @Test
    void biology3_kleiber_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("M", 0.5).put("R", 2.1);
        sweep.addObject().put("M", 1.0).put("R", 3.5);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedM", 0.5);
        d.put("n", 20);
        d.put("slope", 0.748);
        d.put("deviation", 0.002);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.kleiberSweepSegment(0.5, 2.1)
                + MathPhysicsEngine.kleiberSweepSegment(1.0, 3.5)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeKleiberAllometric(0.5, 20, 0.748, 0.002, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("BIOLOGY", 3));

        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 3, d)).contains(expected);
    }

    @Test
    void biology4_hardyWeinberg_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("generations", 10).put("pn", 0.301).put("drift", 0.003);
        sweep.addObject().put("generations", 14).put("pn", 0.299).put("drift", 0.004);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("p", 0.3);
        d.put("samples", 25);
        d.put("maxDrift", 0.004);
        d.put("finalP", 0.299);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.hardyWeinbergSweepSegment(10, 0.301, 0.003)
                + MathPhysicsEngine.hardyWeinbergSweepSegment(14, 0.299, 0.004)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHardyWeinberg(0.3, 25, 0.004, 0.299, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("BIOLOGY", 4));

        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 4, d)).contains(expected);
    }

    // ── CHEMISTRY topics (0–4) — single-method aggregate+sweep ───────────

    @Test
    void chemistry0_henderson_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("ratio", 0.1).put("pH", 3.13).put("pKaEst", 4.13);
        sweep.addObject().put("ratio", 0.16).put("pH", 3.34).put("pKaEst", 4.13);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("truePKa", 4.13);
        d.put("samples", 20);
        d.put("bestErr", 0.005);
        d.put("bestRecovered", 4.135);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.hendersonSweepSegment(0.1, 3.13, 4.13)
                + MathPhysicsEngine.hendersonSweepSegment(0.16, 3.34, 4.13)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeHendersonHasselbalch(4.13, 20, 0.005, 4.135, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("CHEMISTRY", 0));

        assertThat(SidecarResultComposer.composeFromDetail("CHEMISTRY", 0, d)).contains(expected);
    }

    @Test
    void chemistry1_nernst_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("Q", 0.01).put("E", 0.62);
        sweep.addObject().put("Q", 0.025).put("E", 0.59);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("E0", 0.53);
        d.put("T", 298.0);
        d.put("samples", 18);
        d.put("bestDev", 0.12);
        d.put("bestE", 0.62);
        d.put("bestQ", 0.01);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.nernstSweepSegment(0.01, 0.62)
                + MathPhysicsEngine.nernstSweepSegment(0.025, 0.59)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeNernstPotential(0.53, 298.0, 18, 0.12, 0.62, 0.01, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("CHEMISTRY", 1));

        assertThat(SidecarResultComposer.composeFromDetail("CHEMISTRY", 1, d)).contains(expected);
    }

    @Test
    void chemistry2_beerLambert_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("c", 0.001).put("A", 0.0011).put("epsRecovered", 1.1);
        sweep.addObject().put("c", 0.0015).put("A", 0.0017).put("epsRecovered", 1.13);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("trueEps", 1.1);
        d.put("trueL", 1.0);
        d.put("samples", 15);
        d.put("bestErr", 0.003);
        d.put("bestRecoveredEps", 1.097);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.beerLambertSweepSegment(0.001, 0.0011, 1.1)
                + MathPhysicsEngine.beerLambertSweepSegment(0.0015, 0.0017, 1.13)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeBeerLambert(1.1, 1.0, 15, 0.003, 1.097, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("CHEMISTRY", 2));

        assertThat(SidecarResultComposer.composeFromDetail("CHEMISTRY", 2, d)).contains(expected);
    }

    @Test
    void chemistry3_equilibriumKc_composesByteIdenticalToSharedTemplate() {
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("T", 298.0).put("Kc", 1.5).put("drift", 0.0);
        sweep.addObject().put("T", 350.0).put("Kc", 2.4).put("drift", 0.47);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("Kc298", 1.5);
        d.put("samples", 20);
        d.put("bestDrift", 0.47);
        d.put("bestT", 350.0);
        d.put("bestKc", 2.4);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.equilibriumKcSweepSegment(298.0, 1.5, 0.0)
                + MathPhysicsEngine.equilibriumKcSweepSegment(350.0, 2.4, 0.47)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeEquilibriumKc(1.5, 20, 0.47, 350.0, 2.4, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("CHEMISTRY", 3));

        assertThat(SidecarResultComposer.composeFromDetail("CHEMISTRY", 3, d)).contains(expected);
    }

    @Test
    void chemistry4_compressibilityZ_composesByteIdenticalToSharedTemplate() {
        // clampedSamples > 0 exercises the Pitzer-Curl envelope note branch.
        ArrayNode sweep = MAPPER.createArrayNode();
        sweep.addObject().put("Tr", 0.75).put("Pr", 1.2).put("Z", 0.85);
        sweep.addObject().put("Tr", 0.8).put("Pr", 1.4).put("Z", 0.82);
        ObjectNode d = MAPPER.createObjectNode();
        d.put("seedTr", 0.7);
        d.put("seedPr", 1.0);
        d.put("samples", 18);
        d.put("clampedSamples", 3);
        d.put("bestDev", 0.18);
        d.put("bestTr", 0.8);
        d.put("bestPr", 1.4);
        d.put("bestZ", 0.82);
        d.set("sampleSweep", sweep);

        String expectedSweep = (MathPhysicsEngine.compressibilityZSweepSegment(0.75, 1.2, 0.85)
                + MathPhysicsEngine.compressibilityZSweepSegment(0.8, 1.4, 0.82)).trim();
        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeCompressibilityZ(0.7, 1.0, 18, 3, 0.18, 0.8, 1.4, 0.82, expectedSweep)
                        .withEquation(MathPhysicsEngine.equationFor("CHEMISTRY", 4));

        assertThat(SidecarResultComposer.composeFromDetail("CHEMISTRY", 4, d)).contains(expected);
    }

    @Test
    void uncoveredTopic_returnsEmptySoCallerFallsBack() {
        // Topics with no composer branch yet must signal fall-back rather than
        // throw or return a wrong result. (The ARCHITECTURE domains stay
        // uncovered through the MATHEMATICS..CHEMISTRY ports.)
        assertThat(SidecarResultComposer.composeFromDetail("ARCHITECTURE_TERRESTRIAL", 0, mathTopic0Detail()))
                .isEmpty();
        assertThat(SidecarResultComposer.composeFromDetail("ARCHITECTURE_SPACE", 0, mathTopic0Detail()))
                .isEmpty();
    }

    @Test
    void mathematics1_collatz_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("windowStart", 30);
        d.put("windowEnd", 90);
        d.put("maxSteps", 111);
        d.put("maxStepsAt", 27);
        d.put("avgSteps", 38.5);
        d.put("wM2", 1234.5);
        d.put("count", 60);
        d.put("longFraction", 0.05);
        d.put("allTerminated", true);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeCollatzStoppingTimes(
                        30, 90, 111, 27, 38.5, 1234.5, 60, 0.05, true)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 1));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 1, d))
                .contains(expected);
    }

    @Test
    void mathematics2_goldbach_composesByteIdenticalToSharedTemplate() {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("evenStart", 100);
        d.put("evenEnd", 220);
        d.put("avg", 8.4);
        d.put("wM2", 210.3);
        d.put("tested", 60);
        d.put("minParts", 3);
        d.put("minPartsAt", 128);
        d.put("singleCount", 0);
        d.put("primaryValue", 2.8);

        MathPhysicsEngine.ResearchResult expected =
                MathPhysicsEngine.composeGoldbachPartitions(
                        100, 220, 8.4, 210.3, 60, 3, 128, 0, 2.8, 8.4)
                        .withEquation(MathPhysicsEngine.equationFor("MATHEMATICS", 2));

        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 2, d))
                .contains(expected);
    }

    @Test
    void nullOrMissingDetail_returnsEmpty() {
        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 0, null)).isEmpty();
        assertThat(SidecarResultComposer.composeFromDetail("MATHEMATICS", 0, NullNode.getInstance()))
                .isEmpty();
    }

    @Test
    void topicIndexIsModuloReduced_likeComputeDispatcher() {
        // compute() floor-mods the topic index by topicCount; the composer must
        // reduce identically so an out-of-range index maps to the same topic.
        int wrapped = MathPhysicsEngine.topicCount("MATHEMATICS"); // == 0 after floorMod
        Optional<MathPhysicsEngine.ResearchResult> actual =
                SidecarResultComposer.composeFromDetail("MATHEMATICS", wrapped, mathTopic0Detail());
        assertThat(actual).isPresent();
        assertThat(actual.get())
                .isEqualTo(SidecarResultComposer.composeFromDetail("MATHEMATICS", 0, mathTopic0Detail()).get());
    }
}
