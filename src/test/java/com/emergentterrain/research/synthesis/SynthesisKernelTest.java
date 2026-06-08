package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.EvidenceLabel;
import com.emergentterrain.research.synthesis.SynthesisComposer.SourcePaper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 31 §31.7 — the pure synthesis kernel: weakest-source label cap,
 * outline/section prompt contracts, and the warrant gate.
 */
class SynthesisKernelTest {

    // ── label cap ────────────────────────────────────────────────────────────

    @Test
    void cap_isTheWeakestSourceOnTheStrengthSpine() {
        // EMPIRICAL_RESULT + CONJECTURE → CONJECTURE (a synthesis can't launder
        // the conjecture up to empirical by aggregation).
        assertThat(SynthesisLabelCap.cap(List.of(
                EvidenceLabel.EMPIRICAL_RESULT, EvidenceLabel.CONJECTURE)))
                .isEqualTo(EvidenceLabel.CONJECTURE);
        // All EMPIRICAL_RESULT → EMPIRICAL_RESULT.
        assertThat(SynthesisLabelCap.cap(List.of(
                EvidenceLabel.EMPIRICAL_RESULT, EvidenceLabel.EMPIRICAL_RESULT)))
                .isEqualTo(EvidenceLabel.EMPIRICAL_RESULT);
        // UNEXPLAINED_OBSERVATION is the weakest spine level.
        assertThat(SynthesisLabelCap.cap(List.of(
                EvidenceLabel.THEOREM, EvidenceLabel.UNEXPLAINED_OBSERVATION)))
                .isEqualTo(EvidenceLabel.UNEXPLAINED_OBSERVATION);
    }

    @Test
    void cap_ignoresKnownResultAndDefaultsToConjecture() {
        // KNOWN_RESULT is orthogonal — a synthesis citing it alongside fresh
        // empirical work is bounded by the empirical work.
        assertThat(SynthesisLabelCap.cap(List.of(
                EvidenceLabel.KNOWN_RESULT, EvidenceLabel.EMPIRICAL_RESULT)))
                .isEqualTo(EvidenceLabel.EMPIRICAL_RESULT);
        // No rankable evidence → CONJECTURE (the system default).
        assertThat(SynthesisLabelCap.cap(List.of(EvidenceLabel.KNOWN_RESULT)))
                .isEqualTo(EvidenceLabel.CONJECTURE);
        assertThat(SynthesisLabelCap.cap(List.of())).isEqualTo(EvidenceLabel.CONJECTURE);
    }

    @Test
    void applyCap_demotesAnOverclaimKeepsADefensibleClaim() {
        // Proposing EMPIRICAL_RESULT over conjecture sources → demoted.
        assertThat(SynthesisLabelCap.applyCap(EvidenceLabel.EMPIRICAL_RESULT,
                List.of(EvidenceLabel.CONJECTURE)))
                .isEqualTo(EvidenceLabel.CONJECTURE);
        // Proposing CONJECTURE over empirical sources → kept (not laundered up).
        assertThat(SynthesisLabelCap.applyCap(EvidenceLabel.CONJECTURE,
                List.of(EvidenceLabel.EMPIRICAL_RESULT)))
                .isEqualTo(EvidenceLabel.CONJECTURE);
    }

    // ── composer prompts ─────────────────────────────────────────────────────

    private static SourcePaper src(String id, String topic, String label, double peer) {
        return new SourcePaper(id, topic, label, peer, "finding for " + id);
    }

    @Test
    void outlinePrompt_groundsInCorpusAndAllowsNullSynthesis() {
        String p = SynthesisComposer.buildOutlinePrompt("MATHEMATICS",
                "conjecture/cramer_gap_ratio",
                "2-density-feature bounds repeatedly fail; only deficient/abundant survive.",
                List.of(src("P-b72c-214", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.68),
                        src("P-b72c-252", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.75)),
                3, 6);
        assertThat(p)
                .contains("[P-b72c-214]").contains("[P-b72c-252]")
                .contains("Compiled cross-run lesson")
                .contains("NULL_SYNTHESIS")
                .contains("cite ONLY these")
                .contains("invent nothing");
    }

    @Test
    void sectionPrompt_carriesAntiRepetitionDigestAndCitationRule() {
        String p = SynthesisComposer.buildSectionPrompt("Where the bounds disagree",
                "conjecture/cramer_gap_ratio",
                "Section 1 established the shared ≈0.45 ceiling.",
                List.of(src("P-b72c-232", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.82)));
        assertThat(p)
                .contains("Where the bounds disagree")
                .contains("ALREADY-WRITTEN sections")
                .contains("Section 1 established")
                .contains("[paperId]")
                .contains("[P-b72c-232]");
    }

    @Test
    void nullSynthesisSentinelDetected() {
        assertThat(SynthesisComposer.isNullSynthesis(
                "NULL_SYNTHESIS: only two papers, no real disagreement")).isTrue();
        assertThat(SynthesisComposer.isNullSynthesis(
                "[\"Section A\",\"Section B\"]")).isFalse();
        assertThat(SynthesisComposer.isNullSynthesis(null)).isFalse();
    }

    // ── warrant gate ─────────────────────────────────────────────────────────

    @Test
    void warrant_clearsFamiliesAtThresholdStrongestFirst() {
        List<SourcePaper> corpus = List.of(
                src("P-1", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.7),
                src("P-2", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.7),
                src("P-3", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.7),
                src("P-4", "conjecture/cramer_gap_ratio", "CONJECTURE", 0.7),
                src("P-5", "conjecture/mertens_delta", "CONJECTURE", 0.7),
                src("P-6", "conjecture/mertens_delta", "CONJECTURE", 0.7),
                src("P-7", "conjecture/twin_density", "CONJECTURE", 0.7));   // 1 — below
        List<SynthesisWarrant.Warrant> w = SynthesisWarrant.warranted(corpus, 2);
        // cramer (4) and mertens (2) clear minSources=2; twin (1) does not.
        assertThat(w).extracting(SynthesisWarrant.Warrant::family)
                .containsExactly("conjecture/cramer_gap_ratio", "conjecture/mertens_delta");
        assertThat(w.get(0).sources()).hasSize(4);   // strongest corpus first
    }

    @Test
    void warrant_enforcesAFloorOfTwoEvenIfMinIsOne() {
        // A "synthesis" of one paper is not a synthesis — floor at 2.
        List<SourcePaper> corpus = List.of(
                src("P-1", "conjecture/x", "CONJECTURE", 0.7));
        assertThat(SynthesisWarrant.warranted(corpus, 1)).isEmpty();
    }
}
