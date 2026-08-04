package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 3 — unit tests for {@link SublinearGenerationBudget}.
 */
class SublinearGenerationBudgetTest {

    @Test
    void zeroValidationsYieldsZeroBudget() {
        assertThat(SublinearGenerationBudget.budget(0, 0.5, 5)).isZero();
    }

    @Test
    void growsSublinearlyWithValidations() {
        // alpha=0.5 → floor(sqrt(n)): 1→1, 4→2, 9→3, 16→4 (each well under a linear count).
        assertThat(SublinearGenerationBudget.budget(1, 0.5, 100)).isEqualTo(1);
        assertThat(SublinearGenerationBudget.budget(4, 0.5, 100)).isEqualTo(2);
        assertThat(SublinearGenerationBudget.budget(9, 0.5, 100)).isEqualTo(3);
        assertThat(SublinearGenerationBudget.budget(16, 0.5, 100)).isEqualTo(4);
    }

    @Test
    void clampedToHardCap() {
        // sqrt(10000)=100 but hard cap 5 → 5: the configured cap stays an upper ceiling.
        assertThat(SublinearGenerationBudget.budget(10_000, 0.5, 5)).isEqualTo(5);
    }

    @Test
    void throttlesEarlyBelowTheCap() {
        // With few validations the budget is below the hard cap (the point of the gate).
        assertThat(SublinearGenerationBudget.budget(9, 0.5, 5)).isEqualTo(3);
        assertThat(SublinearGenerationBudget.budget(9, 0.5, 5)).isLessThan(5);
    }

    @Test
    void alphaOneIsLinearAndStillClamped() {
        assertThat(SublinearGenerationBudget.budget(3, 1.0, 100)).isEqualTo(3);
        assertThat(SublinearGenerationBudget.budget(50, 1.0, 5)).isEqualTo(5);
    }

    @Test
    void zeroHardCapYieldsZero() {
        assertThat(SublinearGenerationBudget.budget(100, 0.5, 0)).isZero();
    }

    @Test
    void alphaClampedIntoRange() {
        // alpha > 1 is clamped to 1 (linear), not extrapolated super-linearly.
        assertThat(SublinearGenerationBudget.budget(3, 5.0, 100)).isEqualTo(3);
    }
}
