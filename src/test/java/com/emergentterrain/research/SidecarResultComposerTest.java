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

    @Test
    void uncoveredTopic_returnsEmptySoCallerFallsBack() {
        // Topics with no composer branch yet must signal fall-back rather than
        // throw or return a wrong result. (INTERDISCIPLINARY / BIOLOGY stay
        // uncovered through the MATHEMATICS + PHYSICS + MATERIALS ports.)
        assertThat(SidecarResultComposer.composeFromDetail("INTERDISCIPLINARY", 0, mathTopic0Detail()))
                .isEmpty();
        assertThat(SidecarResultComposer.composeFromDetail("BIOLOGY", 0, mathTopic0Detail()))
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
