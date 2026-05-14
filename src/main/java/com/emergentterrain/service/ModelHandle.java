package com.emergentterrain.service;

/**
 * Phase 9 U2 — a single (pool, modelId) tuple managed by a {@link ModelRegistry}.
 *
 * <p>Wraps the pool reference so the registry can dispatch a per-model call
 * to its owning pool without looking the pool up by provider key. Per-model
 * state — block status, telemetry — is still owned by the pool; the handle is
 * a pointer, not a cache.
 *
 * <p>{@link #reviewerId()} is the canonical telemetry string that downstream
 * audit (panel reviews, callPath, persisted {@code panel_outcome} JSON) keys
 * on. Two handles for the same provider are distinguishable by their
 * {@code modelId} component.
 */
public record ModelHandle(LlmPool pool, String modelId) {

    /** Stable provider key from the owning pool — e.g. {@code "nvidia"}. */
    public String providerKey() {
        return pool.providerKey();
    }

    /**
     * Delegates to the owning pool's {@link LlmPool#isModelBlocked(String)}.
     * The registry filters handles through this for availability picking.
     */
    public boolean isBlocked() {
        return pool.isModelBlocked(modelId);
    }

    /**
     * Canonical reviewer id of the form {@code "provider/model"}. Stable across
     * the lifetime of the JVM — used as the PanelReview.reviewerId stamped onto
     * persisted panel outcomes.
     */
    public String reviewerId() {
        return providerKey() + "/" + modelId;
    }
}
