package com.emergentterrain.research.recognise;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-06-05 review rec #1 — the near-match catalog behind conjecture-constant
 * recognition. Pins the P-b72c-177 fixture (mined 1.3128 → 2C₂), tolerance
 * edges, the no-match contract, and the ambiguity guard's honest hedging.
 */
class NamedConstantNearMatchTest {

    @Test
    void minedTwinPrimeCeilingMatchesHardyLittlewood() {
        // P-b72c-177: fitted twin_vs_prime_sq ceiling 1.3128; 2C₂ = 1.320323632.
        Optional<NamedConstantNearMatch.Match> m = NamedConstantNearMatch.match(1.3128);
        assertThat(m).isPresent();
        assertThat(m.get().constant().symbol()).isEqualTo("2C_2");
        assertThat(m.get().relErr()).isLessThan(0.007);
        assertThat(m.get().describe(1.3128))
                .contains("Hardy–Littlewood")
                .contains("numerically close to");
    }

    @Test
    void valuesFarFromEveryEntryReturnEmpty() {
        assertThat(NamedConstantNearMatch.match(17.669)).isEmpty();    // P-115's intercept
        assertThat(NamedConstantNearMatch.match(107.3739)).isEmpty();  // P-121's intercept
        assertThat(NamedConstantNearMatch.match(0.0)).isEmpty();
        assertThat(NamedConstantNearMatch.match(Double.NaN)).isEmpty();
    }

    @Test
    void toleranceEdgeIsRespected() {
        // 2C₂ × 1.014 is inside the 1.5% default; × 1.02 is outside.
        assertThat(NamedConstantNearMatch.match(1.320323632 * 1.014)).isPresent();
        assertThat(NamedConstantNearMatch.match(1.320323632 * 1.02)).isEmpty();
    }

    @Test
    void mertensThirdTheoremConstantIsCatalogued() {
        // e^γ ≈ 1.78107 — the natural constant for density-product bounds.
        Optional<NamedConstantNearMatch.Match> m = NamedConstantNearMatch.match(1.7812);
        assertThat(m).isPresent();
        assertThat(m.get().constant().symbol()).isEqualTo("e^gamma");
    }
}
