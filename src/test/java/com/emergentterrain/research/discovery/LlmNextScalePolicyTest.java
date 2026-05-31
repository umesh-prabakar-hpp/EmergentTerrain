package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 6 — the LLM step policy's pure parts: parsing
 * a model reply, the robust fallback, and the prompt. No live router.
 */
class LlmNextScalePolicyTest {

    private static NextScaleContext ctx(double margin) {
        return new NextScaleContext(1_000_000L, margin, 0, 10);
    }

    @Test
    void parsesPlainCommaAndScientific() {
        assertThat(LlmNextScalePolicy.parseScale("5000000000")).isEqualTo(5_000_000_000L);
        assertThat(LlmNextScalePolicy.parseScale("next: 1,000,000,000")).isEqualTo(1_000_000_000L);
        assertThat(LlmNextScalePolicy.parseScale("I'd probe 2e12 next")).isEqualTo(2_000_000_000_000L);
        assertThat(LlmNextScalePolicy.parseScale("go to 1e9.")).isEqualTo(1_000_000_000L);
    }

    @Test
    void takesTheLastNumberWhenSeveralPresent() {
        assertThat(LlmNextScalePolicy.parseScale("from 1000000 jump to 100000000")).isEqualTo(100_000_000L);
    }

    @Test
    void garbageOrNullParsesToNull() {
        assertThat(LlmNextScalePolicy.parseScale("no idea")).isNull();
        assertThat(LlmNextScalePolicy.parseScale(null)).isNull();
    }

    @Test
    void usesValidParsedScale() {
        assertThat(LlmNextScalePolicy.nextScaleFromReply("7000000000", ctx(0.9))).isEqualTo(7_000_000_000L);
    }

    @Test
    void allowsABackwardScale() {
        // model picks a smaller x to localize — accepted as-is, not a fallback
        assertThat(LlmNextScalePolicy.nextScaleFromReply("500000",
                new NextScaleContext(1_000_000L, 0.05, 0, 10))).isEqualTo(500_000L);
    }

    @Test
    void fallsBackToAggressiveAdaptiveOnBadReply() {
        // garbage -> adaptive: margin 0.9 -> ×1000
        assertThat(LlmNextScalePolicy.nextScaleFromReply("dunno", ctx(0.9))).isEqualTo(1_000_000_000L);
        // overflow / out-of-range -> adaptive
        assertThat(LlmNextScalePolicy.nextScaleFromReply("99999999999999999999", ctx(0.9)))
                .isEqualTo(1_000_000_000L);
    }

    @Test
    void promptCarriesHistoryAndAggression() {
        List<EscalationCheckpoint> hist =
                List.of(new EscalationCheckpoint(1_000_000L, 212L, EscalationVerdict.HOLDS, "sieve", 1));
        String p = LlmNextScalePolicy.buildPrompt(new NextScaleContext(1_000_000L, 0.8, 5e8, 10, hist));
        assertThat(p)
                .contains("AGGRESSIVELY").contains("LEAP")
                .contains("SMALLER x")            // backward allowed
                .contains("x=1000000");           // history rendered
    }
}
