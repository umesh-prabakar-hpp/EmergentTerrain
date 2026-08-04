package com.emergentterrain.research;

import com.emergentterrain.llm.JuliaMathClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Phase 28 §28.6 — sidecar-backed physics-topic compute.
 *
 * <p>After the §28.6b cutover the Julia sidecar is the <strong>sole</strong>
 * source of physics-topic research compute. This delegate fires
 * {@link JuliaMathClient#physicsTopic} and composes the
 * {@link MathPhysicsEngine.ResearchResult} JVM-side from the sidecar's numeric
 * {@code detail} via {@link SidecarResultComposer} (approach B — prose composed
 * from the sidecar's numbers with the engine's own {@code String.format}
 * templates, byte-identical to the former in-process result by construction).
 *
 * <p>The in-process numeric engine ({@code MathPhysicsEngine.compute} and its
 * per-topic methods) was removed in §28.6 legacy removal, so there is no
 * fallback: if the sidecar is unreachable, errors, or returns no result, this
 * delegate throws. Callers run inside the research loop, which already treats a
 * compute failure as a recoverable per-cycle error.
 *
 * <p>The composer covers every (domain, topicIdx) the engine produces, so the
 * only way {@link SidecarResultComposer#composeFromDetail} returns empty is a
 * genuinely-unknown domain string — also surfaced as a thrown exception.
 */
@Service
public class MathPhysicsEngineDelegate {

    private static final Logger log = LoggerFactory.getLogger(MathPhysicsEngineDelegate.class);

    private final JuliaMathClient juliaMathClient;

    /**
     * {@code @Autowired(required=false)} so the delegate is still constructible
     * in test-slice contexts without a JuliaMathClient bean; {@link #compute}
     * then throws a clear error rather than NPE-ing.
     */
    @Autowired
    public MathPhysicsEngineDelegate(@Autowired(required = false) JuliaMathClient juliaMathClient) {
        this.juliaMathClient = juliaMathClient;
        log.info("MathPhysicsEngineDelegate: ready (sidecar-only; client={})",
                juliaMathClient != null);
    }

    /**
     * Compute the {@link MathPhysicsEngine.ResearchResult} for
     * {@code (domain, topicIndex)} via the sidecar, composing the prose JVM-side
     * from the returned numeric {@code detail}.
     *
     * @throws IllegalStateException if the sidecar is unavailable, errors, or
     *         returns no composable result for this topic — there is no
     *         in-process fallback after the §28.6 cutover.
     */
    public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                                     int param1, double param2,
                                                     MathPhysicsEngine.SearchParams sp) {
        if (juliaMathClient == null || !juliaMathClient.isAvailable()) {
            throw new IllegalStateException(
                    "physics-topic compute requires the Julia sidecar, which is unavailable "
                            + "(domain=" + domain + ", topic=" + topicIndex + "). "
                            + "The in-process engine was removed in phase-28 §28.6 legacy removal.");
        }
        Optional<JuliaMathClient.PhysicsTopicResultWire> wire;
        try {
            wire = juliaMathClient.physicsTopic(domain, topicIndex, param1, param2, sp);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "sidecar /physics/topic failed (domain=" + domain + ", topic=" + topicIndex
                            + "): " + e.getMessage(), e);
        }
        if (wire.isEmpty()) {
            // physicsTopic returns empty ONLY for a not_ported (domain, topic);
            // real failures (transport / sidecar-side eval_error) are rethrown
            // and surface above via the catch block with the actual reason.
            throw new IllegalStateException(
                    "(domain=" + domain + ", topic=" + topicIndex + ") is not ported by the Julia "
                            + "sidecar; no in-process fallback (removed in phase-28 §28.6).");
        }
        Optional<MathPhysicsEngine.ResearchResult> composed =
                SidecarResultComposer.composeFromDetail(domain, topicIndex, wire.get().detail());
        return composed.orElseThrow(() -> new IllegalStateException(
                "no composer for (domain=" + domain + ", topic=" + topicIndex
                        + ") — unknown domain string; cannot compose ResearchResult from sidecar detail."));
    }

    /**
     * Convenience overload for callers that haven't built a SearchParams
     * — uses the neutral genome.
     */
    public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                                     int param1, double param2) {
        return compute(domain, topicIndex, param1, param2,
                MathPhysicsEngine.SearchParams.neutral());
    }
}
