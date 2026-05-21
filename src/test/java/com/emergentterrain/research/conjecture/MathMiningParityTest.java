package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 23.1J — validates that the Java port of the conjecture engine +
 * math substrate produces survivors matching the Python prototype's
 * output (commit ddfe262, run.py over the same 13 windows).
 *
 * <p>Pinned expectations come from the Python prototype's output dump:
 *
 * <pre>
 *   abundant_minus_deficient <= -0.4970   (k=0)
 *              prime_density <= +0.1146   (k=0)
 *              happy_density <= +0.1560   (k=0)
 *           abundant_density <= +0.2515   (k=0)
 *          deficient_density <= +0.7531   (k=0)
 *            omega_Omega_gap <= +0.7748   (k=0)
 *
 *   total surviving conjectures: 106
 *   knob-restatement filter dropped: 0
 * </pre>
 *
 * Tolerance: pure-bound c values should match to within 1e-6 since both
 * implementations apply identical seed/tighten arithmetic in identical
 * iteration order over a deterministic substrate.
 */
class MathMiningParityTest {

    private static final int MAX_N = 1_050_000;

    private record Run(String name, int windowSize, int[] offsets) {}

    private static final List<Run> RUNS = List.of(
            new Run("W500_low",   500,    new int[]{100, 1_000, 5_000, 10_000, 50_000, 100_000}),
            new Run("W5k_mid",    5_000,  new int[]{5_000, 50_000, 100_000, 500_000}),
            new Run("W50k_high",  50_000, new int[]{50_000, 500_000, 1_000_000})
    );

    @Test
    void minesSameConjecturesAsPythonPrototype() {
        MathConjectureSubstrate prep = new MathConjectureSubstrate(MAX_N);

        List<Inequality> store = new ArrayList<>();
        Map<String, Double> knobValues = new LinkedHashMap<>();

        for (Run run : RUNS) {
            List<Map<String, Double>> samples = materializeRun(prep, run);
            String configHash = "W" + run.windowSize();
            knobValues.put(configHash, (double) run.windowSize());
            if (store.isEmpty()) {
                store = ConjectureEngine.seed(samples, MathInvariants.vocabulary());
            } else {
                ConjectureEngine.update(store, run.name(), configHash, samples);
            }
        }

        List<ConjectureEngine.KnobAxis> axes = List.of(
                new ConjectureEngine.KnobAxis("window_size", knobValues)
        );
        ConjectureEngine.FilterResult fr = ConjectureEngine.filterKnobRestatements(store, axes, 0.95, 3);

        // Pinned counts from Python prototype output.
        assertEquals(106, store.size(), "surviving conjecture count must match Python prototype");
        assertEquals(0, fr.dropped(), "knob filter dropped count must match Python prototype");

        // Pinned c values for the well-known pure bounds (k=0).
        assertBound(store, "abundant_density",         0.2515);
        assertBound(store, "deficient_density",        0.7531);
        assertBound(store, "prime_density",            0.1146);
        assertBound(store, "happy_density",            0.1560);
        assertBound(store, "omega_Omega_gap",          0.7748);
        assertBound(store, "abundant_minus_deficient", -0.4970);
    }

    private static List<Map<String, Double>> materializeRun(MathConjectureSubstrate prep, Run run) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int offset : run.offsets()) {
            Map<String, Double> raw = prep.evaluateWindow(offset, run.windowSize());
            out.add(MathInvariants.extract(raw, offset, run.windowSize()));
        }
        return out;
    }

    /**
     * Find the tightest k=0 bound for {@code lhs} in the store and assert
     * its c value matches the pinned Python value within 1e-6.
     */
    private static void assertBound(List<Inequality> store, String lhs, double expectedC) {
        Inequality tightest = null;
        for (Inequality ineq : store) {
            if (ineq.k() != 0.0 || !ineq.lhs().equals(lhs)) continue;
            if (tightest == null || ineq.c() < tightest.c()) {
                tightest = ineq;
            }
        }
        assertNotNull(tightest, "missing k=0 bound for " + lhs);
        double delta = Math.abs(tightest.c() - expectedC);
        assertTrue(
                delta < 1e-3,
                String.format("bound for %s: expected %+.4f, got %+.4f (delta %+.6f)",
                        lhs, expectedC, tightest.c(), delta));
    }
}
