package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 24 §24.4 — pins the SR-evidence validator's three cases:
 * <ol>
 *   <li>Prose with no SR claim and no SR call → clean.</li>
 *   <li>Prose with SR claim AND a successful symbolic_regress call → clean.</li>
 *   <li>Prose with SR claim and no (or failed) symbolic_regress call → flagged.</li>
 * </ol>
 * Plus the strict-mode v1.5 entry point that catches malformed result
 * shapes from successful calls.
 */
class SymbolicRegressionEvidenceValidatorTest {

    private static ToolInvocation successfulSr() {
        String result = """
                symbolic_regress: Pareto front over 16 samples, 2 vars (x, y) → f. \
                Evaluated 42 members in 1500 ms.

                | Complexity | MSE | Expression |
                |---|---|---|
                | 1 | 4.000 | `x` |
                | 3 | 1.2e-9 | `x * y` |
                """;
        return new ToolInvocation("symbolic_regress",
                "{\"samples\":[[1,2,2]],\"var_names\":[\"x\",\"y\"]}",
                result, 1, "call_1");
    }

    private static ToolInvocation failedSr() {
        return new ToolInvocation("symbolic_regress",
                "{}",
                "symbolic_regress failed (eval_error): something broke",
                1, "call_1");
    }

    private static ToolInvocation busySr() {
        return new ToolInvocation("symbolic_regress",
                "{}",
                "symbolic_regress busy: another /pysr call is in progress. Retry...",
                1, "call_1");
    }

    private static ToolInvocation otherTool() {
        return new ToolInvocation("run_sandboxed_code",
                "{}",
                "Sandboxed computation — n=100, mean=0.42",
                1, "call_2");
    }

    // ── No-claim cases ────────────────────────────────────────────────

    @Test
    void noClaim_noCall_noFlags() {
        String prose = "The paper reports n=100 with mean 0.42 across the sweep.";
        assertThat(SymbolicRegressionEvidenceValidator.validate(prose, List.of()))
                .isEmpty();
    }

    @Test
    void noClaim_withSrCall_noFlags() {
        // SR call ran but prose makes no claim — validator stays out
        // of "should-have-cited" judgments by design.
        String prose = "The paper reports n=100 with mean 0.42.";
        assertThat(SymbolicRegressionEvidenceValidator.validate(prose, List.of(successfulSr())))
                .isEmpty();
    }

    @Test
    void emptyProse_returnsEmpty() {
        assertThat(SymbolicRegressionEvidenceValidator.validate(null, List.of(failedSr())))
                .isEmpty();
        assertThat(SymbolicRegressionEvidenceValidator.validate("   ", List.of(failedSr())))
                .isEmpty();
    }

    // ── Claim + matching call ─────────────────────────────────────────

    @Test
    void claim_symbolicRegression_withSuccessfulCall_clean() {
        String prose = "We applied symbolic regression to the sweep data and recovered "
                + "the law `x * y` (mse 1.2e-9).";
        assertThat(SymbolicRegressionEvidenceValidator.validate(prose, List.of(successfulSr())))
                .isEmpty();
    }

    @Test
    void claim_paretoFrontier_withSuccessfulCall_clean() {
        String prose = "The Pareto front shows a simple-vs-fit trade-off.";
        assertThat(SymbolicRegressionEvidenceValidator.validate(prose, List.of(successfulSr())))
                .isEmpty();
    }

    @Test
    void claim_pysrLowercase_withSuccessfulCall_clean() {
        String prose = "Running PySR on the data yielded a clean recovery.";
        assertThat(SymbolicRegressionEvidenceValidator.validate(prose, List.of(successfulSr())))
                .isEmpty();
    }

    // ── Claim + missing/failed call ───────────────────────────────────

    @Test
    void claim_recoveredClosedForm_noCall_flagged() {
        String prose = "We recovered a closed-form expression for the scaling law.";
        List<String> failures = SymbolicRegressionEvidenceValidator.validate(prose, List.of());
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
                .contains("recovered a closed-form")
                .contains("no successful symbolic_regress call");
    }

    @Test
    void claim_symbolicRegression_failedCallOnly_flagged() {
        String prose = "Symbolic regression confirmed the form F = m * a.";
        // Trace has a symbolic_regress entry but its result is the
        // failure prefix, not the success prefix — should still flag.
        List<String> failures = SymbolicRegressionEvidenceValidator.validate(
                prose, List.of(failedSr()));
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("symbolic regression");
    }

    @Test
    void claim_paretoFront_busyCallOnly_flagged() {
        // Busy is not a success — the prose claim was made without
        // evidence even though a call was attempted.
        String prose = "The Pareto front of candidate laws places `x * y` as the simplest.";
        List<String> failures = SymbolicRegressionEvidenceValidator.validate(
                prose, List.of(busySr()));
        assertThat(failures).hasSize(1);
    }

    @Test
    void claim_pysr_onlyOtherToolsCalled_flagged() {
        String prose = "PySR identified the underlying law as quadratic.";
        List<String> failures = SymbolicRegressionEvidenceValidator.validate(
                prose, List.of(otherTool()));
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("pysr");
    }

    // ── Strict mode (v1.5) ────────────────────────────────────────────

    @Test
    void strict_successWithoutParetoTable_flagged() {
        // Success prefix present but the Pareto-table header is missing
        // — could happen if MathToolSuite's format ever changes. Strict
        // mode catches that drift.
        ToolInvocation malformed = new ToolInvocation("symbolic_regress",
                "{}",
                "symbolic_regress: search complete (but no Pareto rows for some reason).",
                1, "call_1");
        String prose = "Symbolic regression confirmed it.";
        List<String> strict = SymbolicRegressionEvidenceValidator.validateStrict(
                prose, List.of(malformed));
        // Two failures: the malformed-shape AND the missing-evidence
        // (the malformed result also doesn't satisfy the matching call).
        assertThat(strict).anyMatch(s -> s.contains("no Pareto-table header"));
    }
}
