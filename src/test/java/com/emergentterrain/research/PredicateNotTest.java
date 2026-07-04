package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-04 (P-b72c-77627762) — NOT support in the predicate-scan grammar, added so union
 * densities can be decomposed into exclusive components ("isPrime AND NOT isAbundant").
 */
class PredicateNotTest {

    @Test
    void notNegatesASinglePredicate() {
        IntPredicate notPrime = DynamicComputeEngine.parsePredicate("NOT isPrime");
        assertThat(notPrime.test(4)).isTrue();    // composite
        assertThat(notPrime.test(5)).isFalse();   // prime
    }

    @Test
    void notComposesWithAnd_unionDecomposition() {
        // The exact call P-762 tried: primes that are not abundant. Since no prime is
        // abundant (σ(p)=p+1<2p), this equals isPrime.
        IntPredicate primeOnly = DynamicComputeEngine.parsePredicate("isPrime AND NOT isAbundant");
        assertThat(primeOnly.test(7)).isTrue();
        assertThat(primeOnly.test(12)).isFalse();  // abundant composite
        assertThat(primeOnly.test(9)).isFalse();   // composite, not abundant, not prime
        // And the abundant-only component:
        IntPredicate abundantOnly =
                DynamicComputeEngine.parsePredicate("isAbundant AND NOT isPrime");
        assertThat(abundantOnly.test(12)).isTrue();
        assertThat(abundantOnly.test(7)).isFalse();
    }

    @Test
    void notOfUnknownPredicateIsNull_andPlainParsingUnchanged() {
        assertThat(DynamicComputeEngine.parsePredicate("NOT isBogus")).isNull();
        IntPredicate union = DynamicComputeEngine.parsePredicate("isAbundant OR isPrime");
        assertThat(union.test(7)).isTrue();
        assertThat(union.test(12)).isTrue();
        assertThat(union.test(9)).isFalse();
    }
}
