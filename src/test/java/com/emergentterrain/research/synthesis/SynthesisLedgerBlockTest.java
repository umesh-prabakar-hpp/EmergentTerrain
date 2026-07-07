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

    @org.junit.jupiter.api.Test
    void warrantHint_fitReadyFamilyGoesFirst_stableOnTies() {
        // max_prime_gap lost two warrant races on raw source count while holding five
        // ledgered binding pairs — ledger inventory now outranks prose volume.
        var a = new SynthesisWarrant.Warrant("conjecture/max_divisor_count",
                java.util.List.of(SOURCES.get(0), SOURCES.get(0), SOURCES.get(0)));
        var b = new SynthesisWarrant.Warrant("conjecture/max_prime_gap",
                java.util.List.of(SOURCES.get(0), SOURCES.get(0)));
        var c = new SynthesisWarrant.Warrant("conjecture/cramer_gap_ratio",
                java.util.List.of(SOURCES.get(0), SOURCES.get(0)));
        java.util.Map<String, Integer> rows = java.util.Map.of(
                "conjecture/max_divisor_count", 2,
                "conjecture/max_prime_gap", 6,
                "conjecture/cramer_gap_ratio", 2);
        var sorted = SynthesisPaperService.reorderByLedger(
                java.util.List.of(a, b, c), f -> rows.getOrDefault(f, 0));
        org.assertj.core.api.Assertions.assertThat(sorted.get(0).family())
                .isEqualTo("conjecture/max_prime_gap");
        // tie between a and c keeps the incoming (source-count) order: a before c
        org.assertj.core.api.Assertions.assertThat(sorted.get(1).family())
                .isEqualTo("conjecture/max_divisor_count");
    }

    @org.junit.jupiter.api.Test
    void stripEchoedHeading_syn30ace9e8() {
        String cleaned = SynthesisPaperService.stripEchoedHeading(
                "## The Wigert anchor: empirical discovery\n\nAcross more than thirty conjectures...",
                "The Wigert anchor: empirical discovery");
        org.assertj.core.api.Assertions.assertThat(cleaned)
                .startsWith("Across more than thirty");
        // A section legitimately starting with a DIFFERENT sub-heading is untouched.
        String other = SynthesisPaperService.stripEchoedHeading(
                "## A sub-point\nprose", "The Wigert anchor");
        org.assertj.core.api.Assertions.assertThat(other).startsWith("## A sub-point");
        org.assertj.core.api.Assertions.assertThat(
                SynthesisPaperService.stripEchoedHeading("plain prose", "t")).isEqualTo("plain prose");
    }

    @org.junit.jupiter.api.Test
    void fullPaperComposer_promptsAndAssembly_d3() {
        String intro = FullPaperComposer.buildIntroRelatedWorkPrompt(
                "conjecture/max_prime_gap",
                "the Wigert coordinate descends monotonically toward ln 2",
                "verdict: SILENT\n  [W123] Some adjacent work (2014): snippet",
                "read_reference W123 [source: PDF extraction ...] passage text");
        org.assertj.core.api.Assertions.assertThat(intro)
                .contains("Introduction and Related Work")
                .contains("position the claim against")
                .contains("no prior statement of the claim")
                .contains("[W123]");

        String abs = FullPaperComposer.buildAbstractPrompt("Title", "body with 0.00095");
        org.assertj.core.api.Assertions.assertThat(abs)
                .contains("ABSTRACT").contains("every number must");

        String md = FullPaperComposer.assemble("The ln 2 law", "An abstract.",
                "Intro prose.", "\n## Fit\n\nprose", java.util.List.of("P-1", "P-2"),
                "conjecture/max_prime_gap");
        org.assertj.core.api.Assertions.assertThat(md)
                .startsWith("# The ln 2 law")
                .contains("## Abstract").contains("## Introduction and Related Work")
                .contains("## Results: the program").contains("## Provenance")
                .contains("2 internal sources")
                .contains("binding pairs carry both the");
    }

    @org.junit.jupiter.api.Test
    void headlineOf_prefersFitLikeSectionTitle() {
        var r = new SynthesisPaperService.SynthesisResult("f", "T",
                "## Failures of linear bounds\n\nprose\n\n"
                        + "## Quantitative Fit of the Constant: Ledger Evidence\n\nprose",
                com.emergentterrain.research.EvidenceLabel.CONJECTURE, java.util.List.of(), java.util.List.of(), false, null);
        org.assertj.core.api.Assertions.assertThat(SynthesisPaperService.headlineOf(r))
                .isEqualTo("Quantitative Fit of the Constant: Ledger Evidence");
    }
}
