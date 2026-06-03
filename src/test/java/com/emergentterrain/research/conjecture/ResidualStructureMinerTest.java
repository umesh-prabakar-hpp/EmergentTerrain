package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 5 — unit tests for {@link ResidualStructureMiner} (30.2 residual-structure discovery).
 */
class ResidualStructureMinerTest {

    private static final int PERMS = 999;
    private static final double ALPHA = 0.05;
    private static final List<String> EXPLAINERS = List.of("rhs", "z", "w");

    /** rows of {lhs,rhs,z,w}. */
    private static List<Map<String, Double>> rows(double[][] data) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (double[] d : data) {
            out.add(Map.of("lhs", d[0], "rhs", d[1], "z", d[2], "w", d[3]));
        }
        return out;
    }

    @Test
    void findsTheScalarExplainingTheResidual() {
        // lhs = 2*rhs + 3*z, with z orthogonal to rhs → OLS-on-rhs leaves a residual ≈ 3*z, so z is
        // discovered as the residual explainer; w is unrelated noise.
        int n = 14;
        double[][] data = new double[n][4];
        for (int i = 0; i < n; i++) {
            double rhs = i + 1;
            double z = (i % 2 == 0) ? 1.0 : -1.0;     // orthogonal-ish to the rhs ramp
            double w = (i * 7 % 5);                   // unrelated
            data[i] = new double[]{2 * rhs + 3 * z, rhs, z, w};
        }
        Optional<ResidualStructureMiner.Candidate> c =
                ResidualStructureMiner.mine(rows(data), "lhs", "rhs", EXPLAINERS, PERMS, ALPHA, 42L);
        assertThat(c).isPresent();
        assertThat(c.get().explainer()).isEqualTo("z");
        assertThat(c.get().pValue()).isLessThanOrEqualTo(ALPHA);
    }

    @Test
    void noResidualStructureReturnsEmpty() {
        // lhs = 2*rhs exactly → residual ≈ 0, no scalar explains it → no candidate.
        int n = 14;
        double[][] data = new double[n][4];
        for (int i = 0; i < n; i++) {
            double rhs = i + 1;
            data[i] = new double[]{2 * rhs, rhs, (i % 3), (i % 2)};
        }
        assertThat(ResidualStructureMiner.mine(rows(data), "lhs", "rhs", EXPLAINERS, PERMS, ALPHA, 7L))
                .isEmpty();
    }

    @Test
    void lhsAndRhsAreSkippedAsExplainers() {
        // Even though "rhs" and "lhs" are in the explainer list, they're never returned.
        int n = 14;
        double[][] data = new double[n][4];
        for (int i = 0; i < n; i++) {
            double rhs = i + 1;
            double z = (i % 2 == 0) ? 1.0 : -1.0;
            data[i] = new double[]{2 * rhs + 3 * z, rhs, z, 0};
        }
        Optional<ResidualStructureMiner.Candidate> c = ResidualStructureMiner.mine(
                rows(data), "lhs", "rhs", List.of("lhs", "rhs", "z"), PERMS, ALPHA, 42L);
        assertThat(c).isPresent();
        assertThat(c.get().explainer()).isEqualTo("z");
    }

    @Test
    void tooFewSamplesReturnsEmpty() {
        double[][] data = {{2, 1, 1, 0}, {4, 2, -1, 0}, {6, 3, 1, 0}};
        assertThat(ResidualStructureMiner.mine(rows(data), "lhs", "rhs", EXPLAINERS, PERMS, ALPHA, 1L))
                .isEmpty();
    }

    @Test
    void nullInputsAreSafe() {
        assertThat(ResidualStructureMiner.mine(null, "lhs", "rhs", EXPLAINERS, PERMS, ALPHA, 0L)).isEmpty();
        assertThat(ResidualStructureMiner.mine(List.of(), "lhs", "rhs", EXPLAINERS, PERMS, ALPHA, 0L)).isEmpty();
    }
}
