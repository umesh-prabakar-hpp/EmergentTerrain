package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.question.VerifiedResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 C2 — ledger-aware synthesis prompts. */
class SynthesisLedgerBlockTest {

    private static final List<SynthesisComposer.SourcePaper> SOURCES = List.of(
            new SynthesisComposer.SourcePaper("P-1", "conjecture/max_prime_gap",
                    "CONJECTURE", 0.7, "a finding"));

    @Test
    void ledgerBlock_rendersEntriesAndFitRequirement() {
        String block = SynthesisComposer.renderLedgerBlock(List.of(
                new VerifiedResult("P-a", "max_prime_gap", "TIGHT_BOUND",
                        "max_prime_gap <= +0.6500 * log_sq_window_start HELD, relative slack 0.0018"),
                new VerifiedResult("P-b", "max_prime_gap", "TIGHT_BOUND",
                        "max_prime_gap <= +0.3080 * log_sq_window_start HELD, relative slack 0.0016")));
        assertThat(block).contains("CROSS-RUN VERIFIED RESULTS LEDGER")
                .contains("[P-a | TIGHT_BOUND]").contains("+0.3080");

        String outline = SynthesisComposer.buildOutlinePrompt(
                "MATHEMATICS", "conjecture/max_prime_gap", null, SOURCES, 3, 5, block);
        assertThat(outline).contains("QUANTITATIVE FIT REQUIREMENT")
                .contains("ONE section MUST be the quantitative fit")
                .contains("+0.6500");

        String section = SynthesisComposer.buildSectionPrompt(
                "The fit", "conjecture/max_prime_gap", "", SOURCES, block);
        assertThat(section).contains("the ledger (the tool's own verdict) wins")
                .contains("[P-b | TIGHT_BOUND]");
    }

    @Test
    void withoutLedger_promptsAreUnchangedFromLegacyOverloads() {
        String legacy = SynthesisComposer.buildOutlinePrompt(
                "MATHEMATICS", "f", null, SOURCES, 3, 5);
        String nullBlock = SynthesisComposer.buildOutlinePrompt(
                "MATHEMATICS", "f", null, SOURCES, 3, 5, null);
        String emptyBlock = SynthesisComposer.buildOutlinePrompt(
                "MATHEMATICS", "f", null, SOURCES, 3, 5, "");
        assertThat(nullBlock).isEqualTo(legacy);
        assertThat(emptyBlock).isEqualTo(legacy);
        assertThat(legacy).doesNotContain("QUANTITATIVE FIT REQUIREMENT");
        assertThat(SynthesisComposer.renderLedgerBlock(null)).isEmpty();
        assertThat(SynthesisComposer.renderLedgerBlock(List.of())).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void parseTitles_dedupesCaseVariants_syn59962f10() {
        // The outline emitted every title twice in case variants -> 10-section synthesis.
        String outline = """
                ["The Wigert anchor: quantitative fit across scales",
                 "The Wigert Anchor: Quantitative Fit Across Scales",
                 "What the corpus leaves open"]""";
        java.util.List<String> titles = SynthesisPaperService.parseTitles(outline);
        org.assertj.core.api.Assertions.assertThat(titles).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(titles.get(0))
                .isEqualTo("The Wigert anchor: quantitative fit across scales");
    }
}
