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

    /**
     * The Dirichlet divisor error term: {@code |D(x) − x·(ln x + 2γ − 1)| < √x},
     * where {@code D(x) = Σ_{n≤x} d(n)} and {@code γ} is the Euler–Mascheroni
     * constant. The classical error term is only {@code O(x^{1/4+ε})}, so — like
     * {@link #MERTENS} — this keeps HOLDing across every reachable scale; the loop
     * stalls before the wall rather than refuting it. Its {@link #margin} is
     * {@code 1 − |Δ|/√x}, so the adaptive policy leaps while the error ratio is small.
     */
    BoundPredicate DIVISOR_ERROR = new BoundPredicate() {
        /** 2γ − 1, the constant in the Dirichlet divisor asymptotic D(x) ~ x·ln x + (2γ−1)·x. */
        double TWO_GAMMA_MINUS_1 = 2 * 0.5772156649015329 - 1;

        private double error(long x, long value) {
            return Math.abs(value - (x * (Math.log((double) x) + TWO_GAMMA_MINUS_1)));
        }

        @Override
        public EscalationVerdict classify(long x, long value) {
            return error(x, value) < Math.sqrt((double) x)
                    ? EscalationVerdict.HOLDS : EscalationVerdict.COUNTEREXAMPLE;
        }

        @Override
        public double margin(long x, long value) {
            double ratio = error(x, value) / Math.sqrt((double) x);
            return Math.max(0.0, Math.min(1.0, 1.0 - ratio));
        }
    };
}
