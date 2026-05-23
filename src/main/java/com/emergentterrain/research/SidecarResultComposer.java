package com.emergentterrain.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Phase 28 §28.6b — builds a {@link MathPhysicsEngine.ResearchResult} from the
 * Julia sidecar's {@code /physics/topic} numeric {@code detail} envelope,
 * without re-running the in-process numeric compute.
 *
 * <h2>Why prose composes JVM-side (approach B)</h2>
 *
 * The §28.6 wire-contract finding was that the sidecar handlers return numeric
 * scalars + ClaimStatus only — they deliberately do <em>not</em> emit the
 * findings / hypothesis / equation prose, to avoid byte-format friction between
 * Java {@code String.format} and Julia {@code @sprintf}. Rather than port the
 * prose templates into Julia (the friction-prone path), this composer keeps all
 * {@code String.format} on the JVM: the sidecar runs the expensive numeric loops
 * (5000-step Euler trajectories, sieves, ODEs) and emits the resulting numbers in
 * its {@code detail} map; this class feeds those numbers into the very same
 * {@code MathPhysicsEngine.compose*} methods the in-process path uses, so the
 * prose is byte-identical by construction.
 *
 * <h2>Coverage</h2>
 *
 * One {@code (domain, topicIndex)} branch per ported topic. A topic without a
 * branch returns {@link Optional#empty()}, signalling
 * {@link MathPhysicsEngineDelegate} to fall back to the in-process result — the
 * cutover therefore lights up topic-by-topic as composers land, never
 * all-or-nothing.
 */
final class SidecarResultComposer {

    private SidecarResultComposer() {}

    /**
     * Compose the cutover {@link MathPhysicsEngine.ResearchResult} for
     * {@code (domain, topicIndex)} from the sidecar's numeric {@code detail}
     * envelope (the full {@code /physics/topic} response JsonNode), or
     * {@link Optional#empty()} if this topic has no composer yet.
     *
     * <p>The result includes the per-topic closed-form equation
     * ({@link MathPhysicsEngine#equationFor}) so it matches what
     * {@link MathPhysicsEngine#compute} would have stamped via
     * {@code withEquation(...)}.
     */
    static Optional<MathPhysicsEngine.ResearchResult> composeFromDetail(
            String domain, int topicIndex, JsonNode d) {
        // Only an actual JSON object carries the numeric detail keys. A null /
        // missing / scalar node would silently parse every key as 0 via
        // path().asInt() and compose a garbage all-zeros result, so reject it
        // and let the caller fall back to the in-process compute.
        if (d == null || !d.isObject()) {
            return Optional.empty();
        }
        int t = Math.floorMod(topicIndex, MathPhysicsEngine.topicCount(domain));
        MathPhysicsEngine.ResearchResult r = switch (domain + ":" + t) {
            case "MATHEMATICS:0" -> MathPhysicsEngine.composePrimeConstellations(
                    d.path("windowStart").asInt(),
                    d.path("windowEnd").asInt(),
                    d.path("primeCount").asInt(),
                    d.path("twinCount").asInt(),
                    d.path("cousinCount").asInt(),
                    d.path("sexyCount").asInt(),
                    d.path("maxGap").asInt(),
                    d.path("hlPrediction").asDouble(),
                    d.path("hlDeviation").asDouble(),
                    d.path("primeDensity").asDouble(),
                    d.path("twinDensity").asDouble());
            case "MATHEMATICS:1" -> MathPhysicsEngine.composeCollatzStoppingTimes(
                    d.path("windowStart").asInt(),
                    d.path("windowEnd").asInt(),
                    d.path("maxSteps").asInt(),
                    d.path("maxStepsAt").asInt(),
                    d.path("avgSteps").asDouble(),   // Welford running mean
                    d.path("wM2").asDouble(),        // Welford M2 (for fmtMean)
                    d.path("count").asInt(),
                    d.path("longFraction").asDouble(),
                    d.path("allTerminated").asBoolean());
            case "MATHEMATICS:2" -> MathPhysicsEngine.composeGoldbachPartitions(
                    d.path("evenStart").asInt(),
                    d.path("evenEnd").asInt(),
                    d.path("avg").asDouble(),        // Welford running mean
                    d.path("wM2").asDouble(),        // Welford M2 (for fmtMean)
                    d.path("tested").asInt(),
                    d.path("minParts").asInt(),
                    d.path("minPartsAt").asInt(),
                    d.path("singleCount").asInt(),
                    d.path("primaryValue").asDouble(),  // == contrast
                    d.path("avg").asDouble());
            case "MATHEMATICS:3" -> MathPhysicsEngine.composeAliquotSequencesOuter(
                    MathPhysicsEngine.composeAliquotSequencesInner(
                            d.path("windowStart").asInt(),
                            d.path("windowEnd").asInt(),
                            d.path("terminate").asInt(),
                            d.path("perfect").asInt(),
                            d.path("cycle").asInt(),
                            d.path("escape").asInt(),
                            d.path("terminateFrac").asDouble(),
                            d.path("anomalyFrac").asDouble(),
                            d.path("count").asInt()),
                    d.path("firstAnomaly").asDouble(),
                    d.path("lastAnomaly").asDouble(),
                    d.path("sampled").asInt());
            case "MATHEMATICS:4" -> MathPhysicsEngine.composeHappyNumbersOuter(
                    MathPhysicsEngine.composeHappyNumbersInner(
                            d.path("windowStart").asInt(),
                            d.path("windowEnd").asInt(),
                            d.path("happyCount").asInt(),
                            d.path("count").asInt(),
                            d.path("avgSteps").asDouble(),   // == Welford happyMean
                            d.path("happyM2").asDouble(),     // Welford M2 (for fmtMean)
                            d.path("density").asDouble(),
                            d.path("deviation").asDouble()),
                    d.path("maxDeviation").asDouble(),
                    d.path("sampled").asInt());
            case "MATHEMATICS:9" -> MathPhysicsEngine.composeSquarefreeDensity(
                    d.path("seedStart").asInt(),
                    d.path("width").asInt(),
                    d.path("samples").asInt(),
                    d.path("bestDev").asDouble(),
                    d.path("bestStart").asInt(),
                    d.path("bestDensity").asDouble(),
                    d.path("meanDev").asDouble());
            case "MATHEMATICS:5" -> MathPhysicsEngine.composeMertensPartialSums(
                    d.path("seedStart").asInt(),
                    d.path("width").asInt(),
                    d.path("bestRatio").asDouble(),
                    d.path("bestX").asInt(),
                    d.path("bestM").asLong(),
                    d.path("samples").asInt(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.mertensSweepSegment(
                            s.path("x").asInt(), s.path("M").asLong(), s.path("ratio").asDouble())));
            case "MATHEMATICS:6" -> MathPhysicsEngine.composeDivisorSumAverage(
                    d.path("seedN").asInt(),
                    d.path("width").asInt(),
                    d.path("primaryValue").asDouble(),   // == bestDev
                    d.path("bestN").asInt(),
                    d.path("bestAvg").asDouble(),
                    d.path("secondaryValue").asDouble(), // == meanDev
                    d.path("samples").asInt());
            case "MATHEMATICS:7" -> MathPhysicsEngine.composeRiemannEulerProduct(
                    d.path("seedS").asDouble(),
                    d.path("primaryValue").asDouble(),   // == bestDev
                    d.path("bestS").asDouble(),
                    d.path("bestRatio").asDouble(),
                    d.path("meanDev").asDouble(),
                    d.path("samples").asInt(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.riemannSweepSegment(
                            s.path("s").asDouble(), s.path("ratio").asDouble())));
            case "MATHEMATICS:8" -> MathPhysicsEngine.composeSternBrocotDepth(
                    d.path("seedQ").asInt(),
                    d.path("maxDepth").asInt(),
                    d.path("bestP").asInt(),
                    d.path("bestQ").asInt(),
                    d.path("meanDepth").asDouble(),
                    d.path("samples").asInt(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.sternBrocotSweepSegment(
                            s.path("p").asInt(), s.path("q").asInt(), s.path("depth").asInt())));
            case "PHYSICS:0" -> MathPhysicsEngine.composeProjectileWithDragAt(
                    d.path("v0").asDouble(),
                    d.path("k").asDouble(),
                    d.path("bestAngle").asInt(),
                    d.path("bestRange").asDouble());
            case "PHYSICS:1" -> MathPhysicsEngine.composePendulumBreakdownAt(
                    d.path("L").asDouble(),
                    d.path("T0").asDouble(),
                    d.path("breakdownAngle").asDouble(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.pendulumSweepSegment(
                            s.path("deg").asDouble(), s.path("err").asDouble())));
            case "PHYSICS:3" -> MathPhysicsEngine.composeDiffractionSurveyAt(
                    d.path("dMicrons").asDouble(),
                    d.path("lambdaNm").asDouble(),
                    d.path("maxOrder").asInt(),
                    d.path("theta1").asDouble(),
                    d.path("orderRatio").asDouble(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.diffractionSweepSegment(
                            s.path("lam").asDouble(), s.path("orders").asInt())));
            case "PHYSICS:4" -> MathPhysicsEngine.composeRlcDampingTransitionAt(
                    d.path("L").asDouble(),
                    d.path("C").asDouble(),
                    d.path("f0").asDouble(),
                    d.path("critR").asDouble(),
                    d.path("minQ").asDouble(),
                    d.path("maxQ").asDouble(),
                    sweep(d.path("sampleSweep"), s -> MathPhysicsEngine.rlcSweepSegment(
                            s.path("mult").asDouble(), s.path("Q").asDouble())));
            case "PHYSICS:5" -> MathPhysicsEngine.composeWienPeak(
                    d.path("seedT").asDouble(),
                    d.path("bestT").asDouble(),
                    d.path("samples").asInt(),
                    d.path("bestLambda").asDouble(),
                    d.path("primaryValue").asDouble(),   // == bestDev
                    d.path("meanDev").asDouble());
            case "PHYSICS:7" -> MathPhysicsEngine.composeKeplerThirdLaw(
                    d.path("seedAuAu").asDouble(),
                    d.path("bestA").asDouble(),
                    d.path("samples").asInt(),
                    d.path("primaryValue").asDouble(),   // == bestDev
                    d.path("bestT").asDouble(),
                    d.path("meanDev").asDouble());
            case "PHYSICS:8" -> MathPhysicsEngine.composeSnellCriticalAngle(
                    d.path("seedN2").asDouble(),
                    d.path("bestN2").asDouble(),
                    d.path("samples").asInt(),
                    d.path("primaryValue").asDouble(),   // == bestErr
                    d.path("bestTheta").asDouble(),
                    d.path("meanErr").asDouble());
            case "PHYSICS:9" -> MathPhysicsEngine.composeStefanBoltzmann(
                    d.path("seedT").asDouble(),
                    d.path("bestT").asDouble(),
                    d.path("samples").asInt(),
                    d.path("bestSigma").asDouble(),
                    d.path("primaryValue").asDouble(),   // == bestDev
                    d.path("meanDev").asDouble());
            // §28.6b continuations: remaining (domain, topicIdx) composers land
            // here as each topic's detail map is confirmed complete against the
            // in-process prose. Until then the delegate falls back in-process.
            default -> null;
        };
        return r == null
                ? Optional.empty()
                : Optional.of(r.withEquation(MathPhysicsEngine.equationFor(domain, t)));
    }

    /** Formats one sample-sweep element of the {@code sampleSweep} detail array. */
    @FunctionalInterface
    private interface SegmentFmt {
        String fmt(JsonNode sample);
    }

    /**
     * Rebuild a sample-sweep string (topics 5, 7, 8) by formatting each element
     * of the {@code sampleSweep} array through the topic's segment helper, then
     * trimming the trailing space — exactly as the in-process loop does. Single-
     * sources the per-segment format via the shared {@code *SweepSegment} helpers.
     */
    private static String sweep(JsonNode arr, SegmentFmt f) {
        StringBuilder sb = new StringBuilder();
        if (arr != null && arr.isArray()) {
            for (JsonNode sample : arr) {
                sb.append(f.fmt(sample));
            }
        }
        return sb.toString().trim();
    }
}
