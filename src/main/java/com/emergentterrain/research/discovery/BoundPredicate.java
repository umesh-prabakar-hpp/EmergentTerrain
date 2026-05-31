package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — classifies a computed value at scale
 * {@code x} as the bound holding, failing (counterexample), or — never returned
 * here — not computed. Passed to {@link FrontierEscalationService#escalate} so
 * the loop is bound-agnostic and unit-testable.
 */
public interface BoundPredicate {

    EscalationVerdict classify(long x, long value);

    /**
     * Headroom in [0,1]: 1 = far below the bound (lots of room), 0 = at/over it.
     * Feeds {@link AdaptiveNextScalePolicy}'s step decision. Default is a neutral
     * 0.5 when holding (no margin info), 0 on a counterexample; bounds that can
     * measure their slack should override.
     */
    default double margin(long x, long value) {
        return classify(x, value) == EscalationVerdict.HOLDS ? 0.5 : 0.0;
    }

    /**
     * The Mertens conjecture itself: {@code |M(x)| < sqrt(x)}. Escalating this is
     * literally testing the conjecture at growing scales — it will keep HOLDing up
     * to the ~10^14 regime, which is exactly why the loop stalls before the wall
     * rather than refuting it (see frontier-escalation-loop.md §6). Its
     * {@link #margin} is {@code 1 − |M(x)|/√x}, so the adaptive policy leaps while
     * the ratio is small.
     */
    BoundPredicate MERTENS = new BoundPredicate() {
        @Override
        public EscalationVerdict classify(long x, long value) {
            return Math.abs((double) value) < Math.sqrt((double) x)
                    ? EscalationVerdict.HOLDS : EscalationVerdict.COUNTEREXAMPLE;
        }

        @Override
        public double margin(long x, long value) {
            double ratio = Math.abs((double) value) / Math.sqrt((double) x);
            return Math.max(0.0, Math.min(1.0, 1.0 - ratio));
        }
    };
}
