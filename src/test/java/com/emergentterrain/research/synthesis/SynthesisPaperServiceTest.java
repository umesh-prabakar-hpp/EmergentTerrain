package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.EvidenceLabel;
import com.emergentterrain.research.synthesis.SynthesisComposer.SourcePaper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 31 §31.7 — the compose orchestration: outline→sections, the
 * [paperId]-citation contract, the weakest-source label cap, and the
 * NULL_SYNTHESIS path. Router-free via an injected synthesizer.
 */
class SynthesisPaperServiceTest {

    private static SynthesisPaperService svc() {
        return new SynthesisPaperService(
                mock(com.emergentterrain.repo.ResearchPaperRepository.class),
                mock(com.emergentterrain.research.memory.ResearchMemoryRepository.class),
                null, true, 5, 3, 6, "research");
    }

    private static SourcePaper src(String id, String label, double peer) {
        return new SourcePaper(id, "conjecture/cramer_gap_ratio", label, peer,
                "finding for " + id);
    }

    private static SynthesisWarrant.Warrant cramerWarrant() {
        return new SynthesisWarrant.Warrant("conjecture/cramer_gap_ratio", List.of(
                src("P-b72c-214", "CONJECTURE", 0.68),
                src("P-b72c-218", "CONJECTURE", 0.70),
                src("P-b72c-222", "CONJECTURE", 0.72),
                src("P-b72c-232", "EMPIRICAL_RESULT", 0.82),
                src("P-b72c-240", "CONJECTURE", 0.71)));
    }

    @Test
    void composesSectionsCitesSourcesAndCapsLabel() {
        SynthesisPaperService s = svc();
        // Synthesizer: outline returns 2 titles; each section cites a real source.
        s.setSynthesizer(prompt -> prompt.contains("Propose between")
                ? "[\"The shared ceiling\",\"What remains open\"]"
                : "The ceiling is ~0.45 [P-b72c-214] and holds in-range [P-b72c-232].");
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), "compiled cramer lesson");

        assertThat(r.isNull()).isFalse();
        assertThat(r.bodyMarkdown()).contains("## The shared ceiling").contains("## What remains open");
        assertThat(r.citedIds()).contains("P-b72c-214", "P-b72c-232");
        assertThat(r.uncitedViolations()).isEmpty();
        // Sources are 4×CONJECTURE + 1×EMPIRICAL → cap is CONJECTURE (weakest),
        // even though the composer proposes EMPIRICAL_RESULT.
        assertThat(r.label()).isEqualTo(EvidenceLabel.CONJECTURE);
    }

    @Test
    void citingAPaperOutsideTheCorpus_isAViolation() {
        SynthesisPaperService s = svc();
        s.setSynthesizer(prompt -> prompt.contains("Propose between")
                ? "[\"Section A\"]"
                : "A claim [P-b72c-214] and a stray cite [P-b72c-999] not in corpus.");
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), null);
        assertThat(r.citedIds()).containsExactly("P-b72c-214");
        assertThat(r.uncitedViolations()).containsExactly("P-b72c-999");
    }

    @Test
    void nullSynthesisOutline_producesNoBody() {
        SynthesisPaperService s = svc();
        s.setSynthesizer(prompt -> "NULL_SYNTHESIS: only two papers, no real disagreement");
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), null);
        assertThat(r.isNull()).isTrue();
        assertThat(r.publishable()).isFalse();
        assertThat(r.nullReason()).contains("only two papers");
    }

    @Test
    void unparseableOutline_isNullNotCrash() {
        SynthesisPaperService s = svc();
        s.setSynthesizer(prompt -> "I think the sections could be about various things");
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), null);
        assertThat(r.isNull()).isTrue();
        assertThat(r.nullReason()).contains("no parseable section titles");
    }

    @Test
    void parseTitles_extractsQuotedStrings() {
        assertThat(SynthesisPaperService.parseTitles(
                "[\"The shared ceiling\",\"Where bounds disagree\",\"Open\"]"))
                .containsExactly("The shared ceiling", "Where bounds disagree", "Open");
        assertThat(SynthesisPaperService.parseTitles("not json")).isEmpty();
    }
}
