package com.emergentterrain.service;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PaperKind;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.research.literature.GroundingClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 32 §32.5 step 4 — the widened caller policy ({@code MathToolSuite.groundingApplies}) and
 * the grounded tool-result rendering ({@code MathToolSuite.formatGrounding}). The HTTP client is
 * failure-soft by contract and exercised live; these tests pin the pure logic.
 */
class LiteratureGroundingCallerTest {

    private static PaperDraft draft(String topic, PaperKind kind) {
        return new PaperDraft(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                "MATHEMATICS", topic,
                "Title", "raw", "hyp", "conc",
                "EMPIRICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                (PreRegistration) null, ResultClassification.SUPPORTED,
                null, kind);
    }

    @Test
    void policy_coversPromotionTrackAnomalyAndOpenConjectures_notOrdinaryCorpus() {
        // promotion-track (the original §32.5 policy)
        assertThat(MathToolSuite.groundingApplies(
                draft("anomaly/mertens_delta", PaperKind.ANOMALY_PROMOTION))).isTrue();
        // widened (2026-07-04): priority-target conjecture paper — the flagship class
        assertThat(MathToolSuite.groundingApplies(
                draft("conjecture/dirichlet_error", PaperKind.CONJECTURE_INVESTIGATION))).isTrue();
        // widened: measurement-frame object (GROWTH spec, not necessarily rank 0)
        assertThat(MathToolSuite.groundingApplies(
                draft("conjecture/mean_omega", PaperKind.CONJECTURE_INVESTIGATION))).isTrue();
        // ordinary corpus stays on the flat-cost curated path
        assertThat(MathToolSuite.groundingApplies(
                draft("Pendulum Period Deviation", PaperKind.DOMAIN))).isFalse();
        assertThat(MathToolSuite.groundingApplies(
                draft("conjecture/happy_density", PaperKind.CONJECTURE_INVESTIGATION))).isFalse();
        assertThat(MathToolSuite.groundingApplies(null)).isFalse();
    }

    @Test
    void formatEvidence_citeOnlyKeys_supportOrContradictInstruction() {
        var result = new GroundingClient.GroundingResult("EVIDENCE", 0.42, 105855, List.of(
                new GroundingClient.Evidence("W2007557094",
                        "Analytic properties of Dirichlet series obtained from the error term",
                        2010, "Pacific Journal of Mathematics", "Jun Furuya, Yoshio Tanigawa",
                        "https://doi.org/10.2140/pjm.2010.245.239", 31.9,
                        "…the error term in the Dirichlet divisor problem…")));
        String text = MathToolSuite.formatGrounding(result, "the divisor error term oscillates");
        assertThat(text).contains("EVIDENCE").contains("Cite ONLY the keys")
                .contains("SUPPORTS or CONTRADICTS").contains("REDISCOVERY")
                .contains("[W2007557094]").contains("Furuya")
                .contains("Pacific Journal of Mathematics").contains("105855");
    }

    @Test
    void formatSilent_noveltySignal_nearMissesAreContextOnly() {
        var result = new GroundingClient.GroundingResult("SILENT", 0.28, 105855, List.of(
                new GroundingClient.Evidence("W1199646596",
                        "The mean square of the error term", 1996, "Acta Arithmetica",
                        "Tom Meurman", "", 29.4, "…")));
        String text = MathToolSuite.formatGrounding(result, "a novel claim");
        assertThat(text).contains("SILENT").contains("genuine-novelty signal")
                .contains("NEAR MISSES").contains("CONTEXT")
                .contains("[W1199646596]").contains("Meurman")
                .doesNotContain("REDISCOVERY");
    }
}
