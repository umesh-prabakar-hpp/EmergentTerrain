package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — classifies a computed value at scale
 * {@code x} as the bound holding, failing (counterexample), or — never returned
 * here — not computed. Passed to {@link FrontierEscalationService#escalate} so
 * the loop is bound-agnostic and unit-testable.
 */
@FunctionalInterface
public interface BoundPredicate {

    EscalationVerdict classify(long x, long value);

    /**
     * The Mertens conjecture itself: {@code |M(x)| < sqrt(x)}. Escalating this is
     * literally testing the conjecture at growing scales — it will keep HOLDing up
     * to the ~10^14 regime, which is exactly why the loop stalls before the wall
     * rather than refuting it (see frontier-escalation-loop.md §6).
     */
    BoundPredicate MERTENS = (x, value) ->
            Math.abs((double) value) < Math.sqrt((double) x)
                    ? EscalationVerdict.HOLDS
                    : EscalationVerdict.COUNTEREXAMPLE;
}
