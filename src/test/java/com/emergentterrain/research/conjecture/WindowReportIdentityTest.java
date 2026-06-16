package com.emergentterrain.research.conjecture;

import com.emergentterrain.research.conjecture.ConjectureVerifier.WindowReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 2026-06-15 (G2 fix 4) — the WindowReport identity gate. Every emitted tuple must satisfy
 * {@code margin == lhsValue − bound}; the compact constructor enforces it so a future construction site
 * that drops a bound term (the original two-term serialization bug) fails fast instead of emitting a
 * tuple whose printed bound and margin silently disagree.
 */
class WindowReportIdentityTest {

    @Test
    void acceptsAConsistentTuple() {
        // lhs=0.5, bound=1.37 → margin must be 0.5 − 1.37 = −0.87.
        assertThatCode(() -> new WindowReport(1000L, 0.5, 5.3, 0.0, 1.37, -0.87, true))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAConsistentTwoTermTuple() {
        // A two-term bound: bound carries k*rhs + k2*rhs2 + c; margin = lhs − bound.
        double bound = 2.0 * 5.3 + 0.5 * 3.1 + (-7.7);
        WindowReport w = new WindowReport(2000L, 4.2, 5.3, 3.1, bound, 4.2 - bound, true);
        assertThat(w.bound()).isEqualTo(bound);
        assertThat(w.rhs2Value()).isEqualTo(3.1);
    }

    @Test
    void rejectsADroppedTermTuple() {
        // The original bug shape: the emitted margin is the true two-term margin, but the bound omits the
        // k2*rhs2 contribution — so margin != lhs − bound by exactly that dropped term.
        double fullBound = 2.0 * 5.3 + 0.5 * 3.1 - 7.7;   // the bound the margin was actually computed from
        double trueMargin = 4.2 - fullBound;
        double droppedBound = 2.0 * 5.3 - 7.7;            // k2*rhs2 dropped (the serialization bug)
        assertThatThrownBy(() -> new WindowReport(3000L, 4.2, 5.3, 3.1, droppedBound, trueMargin, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity violated");
    }
}
