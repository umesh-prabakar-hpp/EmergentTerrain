package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recordSample-echo gap (research/a3289005 recommendation): the sandbox used to
 * surface only n/mean/std/min/max for recorded samples, so a prose value equal
 * to a <em>middle</em> sample (neither min nor max) was absent from the result
 * text and the citation validator nulled correct papers (P-a328-988's
 * 2.245e-21). A small set of distinct recorded quantities now echoes each value
 * verbatim; a large population sweep still shows aggregates only.
 */
class SandboxEngineRecordSampleEchoTest {

    @Test
    void smallSampleSet_echoesIndividualMiddleValue() {
        // P-a328-988 shape: five distinct physical quantities. E1 (2.245e-21) is
        // a MIDDLE value — neither min (1.230e-21) nor max (0.352) — so it never
        // appeared in the result text before this fix.
        String code = String.join("\n",
                "recordSample(2.245e-21);",   // E1  — the middle value the prose cites
                "recordSample(0.352);",       // ratio1 (max)
                "recordSample(1.230e-21);",   // E2  (min)
                "recordSample(0.193);",       // ratio2
                "recordSample(3.07e-9);",     // L_threshold
                "return 0.352;");
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 2, 0, 0.0);

        assertThat(r.recordedN()).isEqualTo(5);
        assertThat(r.summary()).contains("values=[");
        assertThat(r.summary()).contains("2.245");
        // The middle value is now traceable in the result text.
        assertThat(CitationValidator.valueAppearsInResult(2.245e-21, r.summary())).isTrue();
    }

    @Test
    void largeSampleSet_echoesAggregatesOnly() {
        // A population sweep (n > MAX_ECHOED_SAMPLES) is summary statistics, not
        // distinct cited quantities — it must NOT echo every value.
        String code = String.join("\n",
                "for (var i = start; i < end; i++) { recordSample(i * 1.0); }",
                "return 0;");
        SandboxEngine.SandboxResult r = SandboxEngine.run(code, 1, 200, 0, 0.0);

        assertThat(r.recordedN()).isEqualTo(199);
        assertThat(r.summary()).doesNotContain("values=[");
        assertThat(r.summary()).contains("recorded n=199");
    }

    @Test
    void noSamples_noValuesClause() {
        // Runs that never call recordSample keep the legacy aggregate-free shape.
        SandboxEngine.SandboxResult r = SandboxEngine.run("return 42;", 1, 2, 0, 0.0);
        assertThat(r.recordedN()).isZero();
        assertThat(r.summary()).doesNotContain("values=[");
    }
}
