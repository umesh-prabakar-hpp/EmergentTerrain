package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 30 Wave 5 — unit tests for {@link MdlGate} (30.5 P2, MDL admission ledger).
 */
class MdlGateTest {

    private static final MdlGate.Params P = new MdlGate.Params(/*grammarSymbols*/ 21, 999, 0.05, 42L);

    /** lhs = 2*rhs + 3*z (z repeats, ~uncorrelated with the rhs ramp) → residual-after-rhs ≈ 3*z. */
    private static List<Map<String, Double>> planted() {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            double rhs = i;
            double z = (i % 4) - 1.5;
            double noise = ((i * 7) % 5) - 2;        // unrelated to z
            out.add(Map.of("lhs", 2 * rhs + 3 * z, "rhs", rhs, "z", z, "noise", noise));
        }
        return out;
    }

    private static OperatorGrammar.Expr leaf(String n) {
        return OperatorGrammar.Expr.leaf(n);
    }

    @Test
    void realExplainerIsAdmitted() {
        MdlGate.Verdict v = MdlGate.evaluate(leaf("z"), planted(), "lhs", "rhs", List.of("noise"), P);
        assertThat(v.admit()).isTrue();
        assertThat(v.reason()).isEqualTo("admit");
        assertThat(v.beatsNull()).isTrue();
        assertThat(v.compressionBits()).isGreaterThan(v.descriptionBits());
        assertThat(v.netGainBits()).isGreaterThan(0.0);
    }

    @Test
    void unrelatedCandidateRejected() {
        MdlGate.Verdict v = MdlGate.evaluate(leaf("noise"), planted(), "lhs", "rhs", List.of("z"), P);
        assertThat(v.admit()).isFalse();
        // noise neither beats the null nor compresses the residual
        assertThat(v.reason()).isIn("fails_null", "no_compression");
    }

    @Test
    void candidateAlreadyExplainedByExistingScalarIsRejected() {
        // The candidate IS an existing scalar's series → incremental compression is zero.
        MdlGate.Verdict v = MdlGate.evaluate(leaf("z"), planted(), "lhs", "rhs", List.of("z", "noise"), P);
        assertThat(v.admit()).isFalse();
        assertThat(v.reason()).isEqualTo("explained_by_existing");
        assertThat(v.bestExistingBits()).isGreaterThan(0.0);
    }

    @Test
    void descriptionCostScalesWithNodeCount() {
        double bitsPerNode = Math.log(21) / Math.log(2);
        MdlGate.Verdict leafV = MdlGate.evaluate(leaf("z"), planted(), "lhs", "rhs", List.of(), P);
        MdlGate.Verdict sqV = MdlGate.evaluate(
                OperatorGrammar.Expr.unary(OperatorGrammar.Unary.SQUARE, leaf("z")),
                planted(), "lhs", "rhs", List.of(), P);
        assertThat(leafV.descriptionBits()).isCloseTo(bitsPerNode, within(1e-9));        // 1 node
        assertThat(sqV.descriptionBits()).isCloseTo(2 * bitsPerNode, within(1e-9));      // 2 nodes
    }

    @Test
    void tooFewSamplesNotAdmitted() {
        List<Map<String, Double>> few = planted().subList(0, 5);
        MdlGate.Verdict v = MdlGate.evaluate(leaf("z"), few, "lhs", "rhs", List.of("noise"), P);
        assertThat(v.admit()).isFalse();
        assertThat(v.reason()).isEqualTo("insufficient_samples");
    }

    @Test
    void nullSafe() {
        assertThat(MdlGate.evaluate(null, planted(), "lhs", "rhs", List.of(), P).admit()).isFalse();
        assertThat(MdlGate.evaluate(leaf("z"), null, "lhs", "rhs", List.of(), P).admit()).isFalse();
    }
}
