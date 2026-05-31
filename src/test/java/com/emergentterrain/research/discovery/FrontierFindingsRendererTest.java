package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 4 — the trust-framed Findings renderer.
 */
class FrontierFindingsRendererTest {

    private static EscalationCheckpoint cp(long x, Long v, EscalationVerdict verdict, String src) {
        return new EscalationCheckpoint(x, v, verdict, src, 1);
    }

    private static final List<EscalationCheckpoint> CORPUS = List.of(
            cp(1_000_000L, 212L, EscalationVerdict.HOLDS, "sieve"),
            cp(10_000_000L, 1037L, EscalationVerdict.HOLDS, "discovery"));

    @Test
    void rendersHeldFrontierWithTrustAndCaveat() {
        String md = FrontierFindingsRenderer.render(
                EscalationOutcome.exhausted(CORPUS, 10_000_000L, null), "M(x)", "|M(x)| < sqrt(x)");
        assertThat(md)
                .contains("## Findings")
                .contains("FULL trust")
                .contains("| x |")               // table
                .contains("HELD at every escalated scale")
                .contains("Regime caveat")
                .contains("not a proof");
    }

    @Test
    void rendersCounterexampleAsTheRealResult() {
        String md = FrontierFindingsRenderer.render(
                EscalationOutcome.counterexample(CORPUS, 1_000_000L), "M(x)", "|M(x)| < sqrt(x)");
        assertThat(md).contains("counterexample_found").contains("FAILS").contains("genuine result");
    }

    @Test
    void rendersModelPivotWithExtrapolationGloss() {
        PivotOutcome pv = new PivotOutcome(Branch.PREDICTIVE_MODEL, TrustLevel.MODEL_FIT, true,
                "model |M(x)|/sqrt(x) ~= 0.2 (R^2=0.9); bound predicted to HOLD");
        String md = FrontierFindingsRenderer.render(
                EscalationOutcome.pivoted(CORPUS, 10_000_000L, null, pv), "M(x)", "|M(x)| < sqrt(x)");
        assertThat(md)
                .contains("Pivot — branch PREDICTIVE_MODEL (trust: MODEL_FIT)")
                .contains("EXTRAPOLATION")        // honest gloss
                .contains("not a proof");
    }

    @Test
    void rendersBoundaryAsLiteratureOnly() {
        PivotOutcome pv = PivotOutcome.boundary("no verifiable branch; requires non-constructive methods");
        String md = FrontierFindingsRenderer.render(
                EscalationOutcome.pivoted(CORPUS, 10_000_000L, null, pv), "M(x)", "|M(x)| < sqrt(x)");
        assertThat(md)
                .contains("pivot_boundary")
                .contains("ANALYTIC")
                .contains("LITERATURE_ONLY")
                .contains("cited");
    }

    @Test
    void rendersLocalizedCounterexampleBracket() {
        String md = FrontierFindingsRenderer.render(
                EscalationOutcome.counterexampleLocalized(CORPUS, 487_000_000L, 523_000_000L),
                "M(x)", "|M(x)| < sqrt(x)");
        assertThat(md)
                .contains("counterexample_localized")
                .contains("localized by bisection")
                .contains("487000000").contains("523000000");
    }

    @Test
    void disabledOutcomeRendersNoFindings() {
        assertThat(FrontierFindingsRenderer.render(EscalationOutcome.disabled(), "M(x)", "b"))
                .contains("did not run");
    }
}
