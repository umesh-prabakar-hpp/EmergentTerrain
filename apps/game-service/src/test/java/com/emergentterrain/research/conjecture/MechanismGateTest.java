package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 6 — unit tests for {@link MechanismGate} (30.7 P2: regeneration + closure + conservation).
 */
class MechanismGateTest {

    private static final double TOL = ConjectureEngine.TOL;
    private static final Set<String> FOOD = Set.of("x", "prime_density", "log_window_size");

    /** windows with x = 1..n and y = x^2 - 1 (so y <= x^2, a true upper bound). */
    private static List<Map<String, Double>> samples(int n, double yOffset) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(Map.of("x", (double) i, "y", (double) i * i + yOffset));
        }
        return out;
    }

    @Test
    void faithfulMechanismIsAdmitted() {
        // predict(y) := square(x); y = x^2 - 1 <= predict; monotone increasing in x; closes; dimension ok.
        Mechanism m = Mechanism.parse("""
                predict(y) := square(x)
                invariant: monotone_increasing(predict in x)
                """);
        MechanismGate.Verdict v = MechanismGate.evaluate(m, samples(12, -1.0), FOOD, TOL);
        assertThat(v.admit()).isTrue();
        assertThat(v.reason()).isEqualTo("admit");
    }

    @Test
    void boundViolatedOutOfSampleFailsRegeneration() {
        // y = x^2 + 5 > predict (= x^2) + 2*TOL → regeneration fails on held windows.
        Mechanism m = Mechanism.parse("predict(y) := square(x)");
        MechanismGate.Verdict v = MechanismGate.evaluate(m, samples(12, 5.0), FOOD, TOL);
        assertThat(v.admit()).isFalse();
        assertThat(v.reason()).isEqualTo("fails_regeneration");
    }

    @Test
    void danglingTermDoesNotClose() {
        // operand z is neither food nor produced → not closed.
        Mechanism m = Mechanism.parse("predict(y) := square(z)");
        MechanismGate.Verdict v = MechanismGate.evaluate(m, samples(12, -1.0), FOOD, TOL);
        assertThat(v.closes()).isFalse();
        assertThat(v.reason()).isEqualTo("not_closed");
    }

    @Test
    void unconsumedIntermediateDoesNotClose() {
        // 'spare' is produced but never consumed and isn't the target → not closed.
        Mechanism m = Mechanism.parse("""
                spare := log(x)
                predict(y) := square(x)
                """);
        assertThat(MechanismGate.closes(m, FOOD)).isFalse();
    }

    @Test
    void dimensionalMixIsRejected() {
        // prime_density (bounded-NT) * log_window_size (window-magnitude) → dimensional violation.
        Mechanism m = Mechanism.parse("predict(y) := (log_window_size * prime_density)");
        MechanismGate.Verdict v = MechanismGate.evaluate(m, samples(12, -1.0), FOOD, TOL);
        assertThat(v.dimensionallyValid()).isFalse();
        assertThat(v.reason()).isEqualTo("dimensional_violation");
    }

    @Test
    void wrongMonotonicityDirectionIsRejected() {
        // predict increases with x, but the mechanism claims it decreases → invariant violation.
        Mechanism m = Mechanism.parse("""
                predict(y) := square(x)
                invariant: monotone_decreasing(predict in x)
                """);
        MechanismGate.Verdict v = MechanismGate.evaluate(m, samples(12, -1.0), FOOD, TOL);
        assertThat(v.invariantsHold()).isFalse();
        assertThat(v.reason()).isEqualTo("invariant_violation");
    }

    @Test
    void nullSafe() {
        assertThat(MechanismGate.evaluate(null, samples(12, -1.0), FOOD, TOL).admit()).isFalse();
    }
}
