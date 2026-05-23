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
            // §28.6b continuations: remaining 62 (domain, topicIdx) composers land
            // here as each topic's detail map is confirmed complete against the
            // in-process prose. Until then the delegate falls back in-process.
            default -> null;
        };
        return r == null
                ? Optional.empty()
                : Optional.of(r.withEquation(MathPhysicsEngine.equationFor(domain, t)));
    }
}
