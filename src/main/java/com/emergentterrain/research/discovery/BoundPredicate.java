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

    /**
     * The von Koch / Schoenfeld RH-equivalent bound on the prime-counting function:
     * {@code |π(x) − li(x)| < (1/(8π))·√x·ln x}, where {@code π(x) = #{p ≤ x}} and
     * {@code li(x)} is the logarithmic integral. Schoenfeld (1976) proved this holds for
     * all {@code x ≥ 2657} <em>under the Riemann Hypothesis</em>; RH is verified far past
     * the reachable frontier, so — like {@link #MERTENS} and {@link #DIVISOR_ERROR} — it
     * keeps HOLDing across every scale the loop reaches and the loop stalls before the wall
     * rather than refuting it. Escalating it is, in effect, testing RH numerically. Its
     * {@link #margin} is {@code 1 − |π(x)−li(x)|/bound}, so the adaptive policy leaps while
     * the ratio is small. Below Schoenfeld's {@code x ≥ 2657} threshold the bound is not
     * claimed, so it reports HOLDS there (the frontier starts at the sieve ceiling ≫ 2657).
     */
    BoundPredicate PRIME_PI = new BoundPredicate() {
        /** 1/(8π), the constant in Schoenfeld's bound |π(x) − li(x)| < (1/8π)·√x·ln x. */
        static final double INV_8PI = 1.0 / (8.0 * Math.PI);
        /** Schoenfeld's validity threshold; below it the bound is not asserted. */
        static final long X_MIN = 2657L;

        private double bound(long x) {
            return INV_8PI * Math.sqrt((double) x) * Math.log((double) x);
        }

        private double error(long x, long value) {
            return Math.abs((double) value - li((double) x));
        }

        @Override
        public EscalationVerdict classify(long x, long value) {
            if (x < X_MIN) {
                return EscalationVerdict.HOLDS;   // bound only claimed for x ≥ 2657
            }
            return error(x, value) < bound(x)
                    ? EscalationVerdict.HOLDS : EscalationVerdict.COUNTEREXAMPLE;
        }

        @Override
        public double margin(long x, long value) {
            if (x < X_MIN) {
                return 0.5;
            }
            double ratio = error(x, value) / bound(x);
            return Math.max(0.0, Math.min(1.0, 1.0 - ratio));
        }
    };

    /**
     * The logarithmic integral {@code li(x) = ∫₀ˣ dt/ln t} (Cauchy principal value), the
     * prime-counting comparison function in {@link #PRIME_PI}. Computed as
     * {@code li(x) = Ei(ln x) = γ + ln(ln x) + Σ_{k≥1} (ln x)^k / (k·k!)} (the standard
     * Ramanujan/Ei series, convergent for all {@code x > 1}). Double precision is ample
     * here: at the reachable frontier {@code li(x) ~ 10^12} and the bound it is compared
     * against is {@code ~10^7}, far above the rounding floor. Undefined for {@code x ≤ 1};
     * the only caller guards {@code x ≥ 2657}.
     */
    static double li(double x) {
        double lnx = Math.log(x);
        final double EULER_MASCHERONI = 0.5772156649015329;
        double sum = EULER_MASCHERONI + Math.log(lnx);
        double term = 1.0;            // term = (lnx)^k / k!  (starts at k=0 → 1, updated before use)
        for (int k = 1; k <= 4000; k++) {
            term *= lnx / k;          // now (lnx)^k / k!
            double add = term / k;    // the k-th series term (lnx)^k / (k·k!)
            sum += add;
            if (Math.abs(add) <= 1e-9 * Math.abs(sum) && k > lnx) {
                break;                // converged (only after passing the peak at k≈lnx)
            }
        }
        return sum;
    }
}
