package com.emergentterrain.research.conjecture;

import com.emergentterrain.research.conjecture.ConjectureVerifier.VerifyResult;
import com.emergentterrain.research.conjecture.ConjectureVerifier.WindowReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-05 (run-21 Rec 1, option b) — {@link ConjectureVerifier#applyRelativeToleranceVerdict}.
 * Pure post-pass tests: in the tolerance-dominated regime (mean |lhs| ≤ 2*TOL) holds re-score
 * against max(4%·mean|lhs|, 1/window_size); outside it the result is returned untouched (parity).
 */
class RelativeToleranceVerdictTest {

    private static WindowReport w(long start, double lhs, double bound, boolean holds) {
        return new WindowReport(start, lhs, 0.5, 0.0, bound, lhs - bound, holds);
    }

    @Test
    void toleranceDominatedVacuousHold_becomesReachableRefuted() {
        // The P-779 shape: twin_prime_density ~0.0065, a bound sitting 0.005 BELOW the lhs —
        // grossly violated at the quantity's own scale, yet margin +0.005 < 0.04 so the absolute
        // verdict said HOLDS (vacuous). Relative mode: threshold = max(0.04·0.0065, 1/5000)
        // = max(0.00026, 0.0002) = 0.00026 → margin +0.005 is now a REFUTED.
        VerifyResult vacuous = new VerifyResult("twin_prime_density", "richly_divisible_density",
                2.0, -3.49, 1, true, +0.005, 1715000,
                List.of(w(1715000, 0.0065, 0.0015, true)));
        VerifyResult rescored = ConjectureVerifier.applyRelativeToleranceVerdict(vacuous, 5000);

        assertThat(rescored.relativeVerdict()).isTrue();
        assertThat(rescored.holdThreshold()).isCloseTo(0.04 * 0.0065, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rescored.held()).isFalse();                       // the vacuous HOLD flipped
        assertThat(rescored.windows().get(0).holds()).isFalse();
        assertThat(rescored.worstMargin()).isEqualTo(+0.005);        // margins are data — untouched
    }

    @Test
    void toleranceDominatedGenuineHold_staysHeld() {
        // A bound genuinely above the lhs (negative margin) keeps HELD under the relative verdict.
        VerifyResult genuine = new VerifyResult("twin_prime_density", "richly_divisible_density",
                2.0, -3.4750, 1, true, -0.001, 1715000,
                List.of(w(1715000, 0.0065, 0.0075, true)));
        VerifyResult rescored = ConjectureVerifier.applyRelativeToleranceVerdict(genuine, 5000);
        assertThat(rescored.relativeVerdict()).isTrue();
        assertThat(rescored.held()).isTrue();
    }

    @Test
    void granularityFloor_protectsSingleCountNoise() {
        // Tiny window (size 500 → floor 1/500 = 0.002) dominates the 4% term (0.00026): a margin
        // of +0.001 — one count of noise — still holds, so verdicts can't flap on granularity.
        VerifyResult noisy = new VerifyResult("twin_prime_density", "richly_divisible_density",
                2.0, -3.4750, 1, true, +0.001, 1715000,
                List.of(w(1715000, 0.0065, 0.0055, true)));
        VerifyResult rescored = ConjectureVerifier.applyRelativeToleranceVerdict(noisy, 500);
        assertThat(rescored.holdThreshold()).isEqualTo(1.0 / 500);
        assertThat(rescored.held()).isTrue();
    }

    @Test
    void o1ScaleQuantities_areUntouched_parity() {
        // mean |lhs| = 0.5 > 0.04 → the absolute verdict is meaningful; the post-pass must return
        // the input UNCHANGED (bit-identical cross-run parity outside the vacuous regime).
        VerifyResult o1 = new VerifyResult("mertens_per_sqrt_W", "log_window_start",
                2.0, -7.7621, 1, true, +0.03, 100000,
                List.of(w(100000, 0.5, 0.47, true)));
        VerifyResult out = ConjectureVerifier.applyRelativeToleranceVerdict(o1, 5000);
        assertThat(out).isSameAs(o1);
        assertThat(out.relativeVerdict()).isFalse();
        assertThat(out.holdThreshold()).isEqualTo(2 * ConjectureEngine.TOL);
    }
}
