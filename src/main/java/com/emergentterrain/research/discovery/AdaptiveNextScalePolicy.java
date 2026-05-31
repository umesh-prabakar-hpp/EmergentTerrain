package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 6 — margin-driven adaptive step, tuned
 * **aggressive** by design: when the bound holds with room, *leap* (×100–×1000)
 * instead of crawling ×10, and even the tightest step near the bound is a healthy
 * ×3 — never a timid ×1.x. This deliberately biases toward reaching the frontier
 * fast and avoiding "death by small steps"; the stall predictor + pivot handle the
 * compute ceiling, so the policy need not be cautious about overshooting x_c.
 *
 * <p>Step ladder by {@code margin} (1 = far below the bound, 0 = at it):
 * <pre>
 *   margin ≥ 0.6 → ×1000     (tons of headroom — big leap)
 *   margin ≥ 0.3 → ×100
 *   margin ≥ 0.1 → ×10
 *   else         → ×3        (near the bound — refine, but still a real step)
 * </pre>
 * For Mertens the ratio |M(x)|/√x stays small for a long way, so the margin is
 * usually high and the policy leaps — exactly the intent.
 */
public class AdaptiveNextScalePolicy implements NextScalePolicy {

    @Override
    public long nextScale(NextScaleContext ctx) {
        double m = ctx.margin();
        long mult;
        if (m >= 0.6)      mult = 1000;
        else if (m >= 0.3) mult = 100;
        else if (m >= 0.1) mult = 10;
        else               mult = 3;     // floor — aggressive even near the bound, never < ×3
        long next = ctx.currentX() * mult;
        if (next <= ctx.currentX()) {    // overflow → fall back to the default multiplier
            next = ctx.currentX() * Math.max(2, ctx.defaultScaleFactor());
        }
        return next <= ctx.currentX() ? ctx.currentX() + 1 : next;
    }
}
