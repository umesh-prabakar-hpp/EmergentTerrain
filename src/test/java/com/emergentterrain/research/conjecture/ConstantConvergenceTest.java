package com.emergentterrain.research.conjecture;

import com.emergentterrain.research.conjecture.ConstantConvergence.Assessment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-06-10 (lever D, frame 1) — the two-sided constant-convergence assessment for ratio/limit
 * quantities (e.g. twin_vs_prime_sq → 2C₂) the one-sided bound frame can't express.
 */
class ConstantConvergenceTest {

    @Test
    void convergesToTarget_withinTolerance() {
        // twin_vs_prime_sq readings hugging the Hardy–Littlewood constant 2C₂ ≈ 1.32032.
        double[] v = {1.3180, 1.3215, 1.3198, 1.3221};
        long[] s = {1_000_000, 1_200_000, 1_500_000, 1_800_000};
        Assessment a = ConstantConvergence.assess(v, s, 1.32032, 0.01);
        assertThat(a.estimate()).isEqualTo(1.32032);
        assertThat(a.converged()).isTrue();              // worst dev ≈ 0.0023 / 1.32 ≈ 0.0017 ≤ 0.01
        assertThat(a.relativeSpread()).isLessThan(0.01);
    }

    @Test
    void convergesByMean_despiteNoisyWindows() {
        // The twin_vs_prime_sq case observed live: twin primes are sparse, so per-window values swing
        // wildly, but the MEAN sits on 2C₂. Convergence must be judged by the mean, not the worst window.
        double[] v = {1.05, 1.60, 1.20, 1.55, 1.30};   // mean = 1.34
        long[] s = {1_000_000, 1_200_000, 1_400_000, 1_600_000, 1_800_000};
        Assessment a = ConstantConvergence.assess(v, s, 1.34, 0.05);
        assertThat(a.relativeSpread()).isGreaterThan(0.15);   // noisy per window
        assertThat(a.meanRelDeviation()).isLessThan(0.01);    // but the mean is on target
        assertThat(a.converged()).isTrue();                   // judged by the mean, not the worst window
    }

    @Test
    void divergesFromTarget_whenOff() {
        double[] v = {1.10, 1.15, 1.20, 1.25};           // trending, not at 1.32
        long[] s = {1_000_000, 1_200_000, 1_500_000, 1_800_000};
        Assessment a = ConstantConvergence.assess(v, s, 1.32032, 0.01);
        assertThat(a.converged()).isFalse();
        assertThat(a.worstWindowStart()).isEqualTo(1_000_000);   // 1.10 is farthest from 1.32
    }

    @Test
    void noTarget_estimatesLimitAsMeanAndReportsSpread() {
        double[] v = {1.30, 1.32, 1.34};
        long[] s = {1_000_000, 1_500_000, 2_000_000};
        Assessment a = ConstantConvergence.assess(v, s, null, 0.05);
        assertThat(a.estimate()).isEqualTo(1.32, org.assertj.core.data.Offset.offset(1e-9)); // mean
        assertThat(a.mean()).isEqualTo(1.32, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(a.converged()).isTrue();              // spread 0.02/1.32 ≈ 0.015 ≤ 0.05
    }

    @Test
    void rejectsEmptyOrMismatchedInput() {
        assertThatThrownBy(() -> ConstantConvergence.assess(new double[0], new long[0], null, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConstantConvergence.assess(new double[]{1.0}, new long[]{1, 2}, null, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
