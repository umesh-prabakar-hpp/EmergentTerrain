package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path A — {@link BoundPredicate#PRIME_PI} and its {@link BoundPredicate#li(double)} helper.
 * Anchors li(x) against published values and checks the Schoenfeld bound classifies a
 * true π(x) as HOLDS and a far-off value as a COUNTEREXAMPLE.
 */
class BoundPredicatePrimeTest {

    @Test
    void liMatchesPublishedValues() {
        // li(10^7) ≈ 664918.4 (vs π(10^7)=664579); li(10^9) ≈ 50849234.9 (vs π=50847534).
        assertThat(BoundPredicate.li(1e7)).isCloseTo(664918.4, org.assertj.core.data.Offset.offset(2.0));
        assertThat(BoundPredicate.li(1e9)).isCloseTo(50849234.9, org.assertj.core.data.Offset.offset(50.0));
    }

    @Test
    void trueCountHoldsWithinTheSchoenfeldBand() {
        // π(10^7)=664579 is ~339 below li; the bound at 10^7 is √x·ln x/(8π) ≈ 2028 → HOLDS.
        assertThat(BoundPredicate.PRIME_PI.classify(10_000_000L, 664_579L))
                .isEqualTo(EscalationVerdict.HOLDS);
        assertThat(BoundPredicate.PRIME_PI.margin(10_000_000L, 664_579L)).isBetween(0.0, 1.0);
    }

    @Test
    void wildlyWrongCountIsACounterexample() {
        // π(10^7) forced far outside the ~2028-wide band → COUNTEREXAMPLE.
        assertThat(BoundPredicate.PRIME_PI.classify(10_000_000L, 700_000L))
                .isEqualTo(EscalationVerdict.COUNTEREXAMPLE);
    }

    @Test
    void belowSchoenfeldThresholdAlwaysHolds() {
        // The bound is only claimed for x ≥ 2657; below it we never manufacture a counterexample.
        assertThat(BoundPredicate.PRIME_PI.classify(100L, 999_999L))
                .isEqualTo(EscalationVerdict.HOLDS);
    }
}
