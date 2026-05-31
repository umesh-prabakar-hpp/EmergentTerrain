package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 6 — decides the next scale to probe. The
 * knowledge frontier stays monotonic (we push to larger x), but the *step* is a
 * decision: {@link DefaultNextScalePolicy} keeps the fixed ×10;
 * {@link AdaptiveNextScalePolicy} leaps when the bound holds with room and only
 * tightens near a suspected transition. An LLM-backed policy (follow-on) plugs in
 * the same way.
 */
public interface NextScalePolicy {

    /** The next x to test (must be &gt; ctx.currentX(); the loop guards otherwise). */
    long nextScale(NextScaleContext ctx);
}
