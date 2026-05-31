package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 frontier-escalation Phase 6 — the step policies. The adaptive one is
 * tuned aggressive: leaps with margin, never crawls.
 */
class NextScalePolicyTest {

    private static NextScaleContext ctx(double margin) {
        return new NextScaleContext(1_000_000L, margin, 0, 10);
    }

    @Test
    void adaptiveLeapsByMargin() {
        AdaptiveNextScalePolicy p = new AdaptiveNextScalePolicy();
        assertThat(p.nextScale(ctx(0.90))).isEqualTo(1_000_000_000L);   // ×1000
        assertThat(p.nextScale(ctx(0.60))).isEqualTo(1_000_000_000L);   // ×1000 (boundary)
        assertThat(p.nextScale(ctx(0.45))).isEqualTo(100_000_000L);     // ×100
        assertThat(p.nextScale(ctx(0.20))).isEqualTo(10_000_000L);      // ×10
        assertThat(p.nextScale(ctx(0.05))).isEqualTo(3_000_000L);       // ×3 floor
    }

    @Test
    void adaptiveNeverCrawlsEvenAtTheBound() {
        // at/over the bound (margin 0) the step is still a healthy >= x3, never x1.x
        assertThat(new AdaptiveNextScalePolicy().nextScale(ctx(0.0)))
                .isGreaterThanOrEqualTo(3_000_000L);
    }

    @Test
    void defaultIgnoresMarginAndKeepsFixedMultiplier() {
        DefaultNextScalePolicy p = new DefaultNextScalePolicy();
        assertThat(p.nextScale(ctx(0.90))).isEqualTo(10_000_000L);      // ×10 regardless of margin
        assertThat(p.nextScale(ctx(0.05))).isEqualTo(10_000_000L);
    }

    @Test
    void mertensMarginLeapsWhileRatioSmall() {
        // |M(10^9)|=1037 -> ratio ~0.033 -> margin ~0.97 -> adaptive leaps x1000
        double margin = BoundPredicate.MERTENS.margin(1_000_000_000L, 1037L);
        assertThat(margin).isGreaterThan(0.6);
        assertThat(new AdaptiveNextScalePolicy()
                .nextScale(new NextScaleContext(1_000_000_000L, margin, 0, 10)))
                .isEqualTo(1_000_000_000_000L);
    }
}
