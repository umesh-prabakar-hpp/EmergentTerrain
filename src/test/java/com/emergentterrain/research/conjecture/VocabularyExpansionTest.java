package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Wave 5 — unit tests for {@link VocabularyExpansion} (30.5 P3a admission orchestration).
 */
class VocabularyExpansionTest {

    private static final MdlGate.Params P = new MdlGate.Params(21, 999, 0.05, 99L);

    /** lhs = 2*rhs + 3*z → residual-after-rhs ≈ 3*z; "noise" unrelated. z and noise are sample scalars. */
    private static List<Map<String, Double>> planted() {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            double rhs = i;
            double z = (i % 4) - 1.5;
            double noise = ((i * 7) % 5) - 2;
            out.add(Map.of("lhs", 2 * rhs + 3 * z, "rhs", rhs, "z", z, "noise", noise));
        }
        return out;
    }

    private static final List<VocabularyExpansion.Target> TARGET =
            List.of(new VocabularyExpansion.Target("lhs", "rhs"));

    @Test
    void admitsTheCandidateThatExplainsTheResidual() {
        List<OperatorGrammar.Expr> cands = List.of(
                OperatorGrammar.Expr.leaf("z"), OperatorGrammar.Expr.leaf("noise"));
        List<DerivedScalar> admitted = VocabularyExpansion.admit(
                cands, planted(), TARGET, List.of("noise"), /*budget*/ 8, P);
        assertThat(admitted).extracting(DerivedScalar::name).containsExactly("z");
        assertThat(admitted.get(0).gainBits()).isGreaterThan(0.0);
    }

    @Test
    void budgetCapsAdmissions() {
        List<OperatorGrammar.Expr> cands = List.of(
                OperatorGrammar.Expr.leaf("z"), OperatorGrammar.Expr.leaf("noise"));
        // budget 0 admits nothing even though z would qualify.
        assertThat(VocabularyExpansion.admit(cands, planted(), TARGET, List.of("noise"), 0, P)).isEmpty();
    }

    @Test
    void admitsNothingWhenNoCandidateBeatsTheLedger() {
        List<OperatorGrammar.Expr> cands = List.of(OperatorGrammar.Expr.leaf("noise"));
        assertThat(VocabularyExpansion.admit(cands, planted(), TARGET, List.of("z"), 8, P)).isEmpty();
    }

    @Test
    void deterministicForSameInputs() {
        List<OperatorGrammar.Expr> cands = List.of(
                OperatorGrammar.Expr.leaf("z"), OperatorGrammar.Expr.leaf("noise"));
        assertThat(VocabularyExpansion.admit(cands, planted(), TARGET, List.of("noise"), 8, P))
                .isEqualTo(VocabularyExpansion.admit(cands, planted(), TARGET, List.of("noise"), 8, P));
    }

    @Test
    void emptyInputsSafe() {
        assertThat(VocabularyExpansion.admit(List.of(), planted(), TARGET, List.of(), 8, P)).isEmpty();
        assertThat(VocabularyExpansion.admit(
                List.of(OperatorGrammar.Expr.leaf("z")), planted(), List.of(), List.of(), 8, P)).isEmpty();
    }

    // ---- Stage A: admitWithReport rejection histogram (novelty-ceiling-and-vocab-activation.md) ----

    @Test
    void reportAdmittedMatchesAdmit() {
        List<OperatorGrammar.Expr> cands = List.of(
                OperatorGrammar.Expr.leaf("z"), OperatorGrammar.Expr.leaf("noise"));
        VocabularyExpansion.AdmissionReport report = VocabularyExpansion.admitWithReport(
                cands, planted(), TARGET, List.of("noise"), 8, P);
        assertThat(report.admitted())
                .isEqualTo(VocabularyExpansion.admit(cands, planted(), TARGET, List.of("noise"), 8, P));
    }

    @Test
    void histogramCountsEveryCandidateAndFlagsAdmit() {
        List<OperatorGrammar.Expr> cands = List.of(
                OperatorGrammar.Expr.leaf("z"), OperatorGrammar.Expr.leaf("noise"));
        VocabularyExpansion.AdmissionReport report = VocabularyExpansion.admitWithReport(
                cands, planted(), TARGET, List.of("noise"), 8, P);
        int total = report.reasonCounts().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(2);                      // one outcome per probed candidate
        assertThat(report.reasonCounts()).containsEntry("admit", 1);   // z explains the residual
    }

    @Test
    void histogramFlagsExplainedByExisting() {
        // The Fork-A signal: candidate z would compress the residual, but z is already in existing,
        // so the gate correctly admits nothing and the reason is explained_by_existing.
        List<OperatorGrammar.Expr> cands = List.of(OperatorGrammar.Expr.leaf("z"));
        VocabularyExpansion.AdmissionReport report = VocabularyExpansion.admitWithReport(
                cands, planted(), TARGET, List.of("z"), 8, P);
        assertThat(report.admitted()).isEmpty();
        assertThat(report.reasonCounts()).containsEntry("explained_by_existing", 1);
    }
}
