package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30.7 — unit tests for the mechanism-as-runnable-model verifier. Pure;
 * the probabilistic hook is seeded so the Monte-Carlo is deterministic.
 */
class MechanismVerifierTest {

    private static final Inequality MERTENS =
            new Inequality("mertens_per_sqrt_W", "log_window_start", 2.0, -7.7621);

    // ---- tag parsing ----

    @Test
    void parsesMechanismKindTags() {
        assertThat(MechanismKind.fromTag("asymptotic_intuition")).isEqualTo(MechanismKind.ASYMPTOTIC_INTUITION);
        assertThat(MechanismKind.fromTag("Mechanism kind: analogy_to_known_theory"))
                .isEqualTo(MechanismKind.ANALOGY_TO_KNOWN_THEORY);
        assertThat(MechanismKind.fromTag("Probabilistic-Model")).isEqualTo(MechanismKind.PROBABILISTIC_MODEL);
        assertThat(MechanismKind.fromTag("gibberish")).isEqualTo(MechanismKind.NONE);
        assertThat(MechanismKind.fromTag(null)).isEqualTo(MechanismKind.NONE);
    }

    // ---- heuristic_derivation: constant self-consistency ----

    @Test
    void heuristicVerifiedWhenPredictedConstantsMatch() {
        MechanismSpec spec = new MechanismSpec(MechanismKind.HEURISTIC_DERIVATION,
                Map.of("predictedK", 2.0, "predictedC", -7.7621));
        assertThat(MechanismVerifier.verify(spec, MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.VERIFIED);
    }

    @Test
    void heuristicRefutedWhenPredictedConstantsDiffer() {
        MechanismSpec spec = new MechanismSpec(MechanismKind.HEURISTIC_DERIVATION,
                Map.of("predictedK", 4.6, "predictedC", -7.7621));   // 4.6 != mined 2.0
        MechanismVerifier.Verdict v = MechanismVerifier.verify(spec, MERTENS);
        assertThat(v.status()).isEqualTo(MechanismVerifier.Status.REFUTED);
        assertThat(v.detail()).contains("4.6");
    }

    @Test
    void heuristicUnverifiableWithoutPredictions() {
        MechanismSpec spec = new MechanismSpec(MechanismKind.HEURISTIC_DERIVATION, Map.of());
        assertThat(MechanismVerifier.verify(spec, MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.UNVERIFIABLE);
    }

    // ---- probabilistic_model: seeded Monte-Carlo regenerates the bound's coverage ----

    @Test
    void probabilisticVerifiedAtLargeStartWhereBoundHolds() {
        // rhs = log10(200000) ~ 5.301 -> bound = 2*5.301 - 7.7621 ~ 2.84;
        // mertens_per_sqrt_W ~ N(0,1) -> P(<=2.84) ~ 0.998 >> claimed 0.99 -> VERIFIED
        MechanismSpec spec = new MechanismSpec(MechanismKind.PROBABILISTIC_MODEL,
                Map.of("mu", 0.0, "sigma", 1.0, "rhsValue", 5.301, "coverage", 0.99,
                        "samples", 50_000.0, "seed", 42.0));
        assertThat(MechanismVerifier.verify(spec, MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.VERIFIED);
    }

    @Test
    void probabilisticRefutedAtSmallStartWhereBoundFails() {
        // rhs = log10(2000) ~ 3.301 -> bound = 2*3.301 - 7.7621 ~ -1.16;
        // P(N(0,1) <= -1.16) ~ 0.12 << claimed 0.99 -> REFUTED (matches "fails at small start")
        MechanismSpec spec = new MechanismSpec(MechanismKind.PROBABILISTIC_MODEL,
                Map.of("mu", 0.0, "sigma", 1.0, "rhsValue", 3.301, "coverage", 0.99,
                        "samples", 50_000.0, "seed", 42.0));
        MechanismVerifier.Verdict v = MechanismVerifier.verify(spec, MERTENS);
        assertThat(v.status()).isEqualTo(MechanismVerifier.Status.REFUTED);
        assertThat(v.detail()).contains("violated more often");
    }

    @Test
    void probabilisticUnverifiableWithoutParams() {
        MechanismSpec spec = new MechanismSpec(MechanismKind.PROBABILISTIC_MODEL,
                Map.of("mu", 0.0, "sigma", 1.0));   // missing rhsValue, coverage
        assertThat(MechanismVerifier.verify(spec, MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.UNVERIFIABLE);
    }

    // ---- deferred kinds + none + promotion gate ----

    @Test
    void heavyKindsAreUnverifiableUntilHooksShip() {
        for (MechanismKind kind : new MechanismKind[]{
                MechanismKind.ASYMPTOTIC_INTUITION,
                MechanismKind.EXTREMAL_CONSTRUCTION,
                MechanismKind.ANALOGY_TO_KNOWN_THEORY}) {
            assertThat(MechanismVerifier.verify(new MechanismSpec(kind, Map.of()), MERTENS).status())
                    .isEqualTo(MechanismVerifier.Status.UNVERIFIABLE);
        }
    }

    @Test
    void noneIsNotApplicableAndOnlyVerifiedPromotes() {
        assertThat(MechanismVerifier.verify(new MechanismSpec(MechanismKind.NONE, Map.of()), MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.NOT_APPLICABLE);

        assertThat(MechanismVerifier.justifiesPromotion(
                new MechanismVerifier.Verdict(MechanismVerifier.Status.VERIFIED, ""))).isTrue();
        assertThat(MechanismVerifier.justifiesPromotion(
                new MechanismVerifier.Verdict(MechanismVerifier.Status.UNVERIFIABLE, ""))).isFalse();
        assertThat(MechanismVerifier.justifiesPromotion(null)).isFalse();
    }

    @Test
    void nullSpecOrClaimIsUnverifiable() {
        assertThat(MechanismVerifier.verify(null, MERTENS).status())
                .isEqualTo(MechanismVerifier.Status.UNVERIFIABLE);
        assertThat(MechanismVerifier.verify(
                new MechanismSpec(MechanismKind.HEURISTIC_DERIVATION, Map.of()), null).status())
                .isEqualTo(MechanismVerifier.Status.UNVERIFIABLE);
    }
}
