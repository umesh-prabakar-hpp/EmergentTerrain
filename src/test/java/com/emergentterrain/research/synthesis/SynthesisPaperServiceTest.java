package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.EvidenceLabel;
import com.emergentterrain.research.literature.Phase34SqliteLiterature;
import com.emergentterrain.research.synthesis.SynthesisComposer.SourcePaper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 31 §31.7 — the compose orchestration: outline→sections, the
 * [paperId]-citation contract, the weakest-source label cap, and the
 * NULL_SYNTHESIS path. Router-free via an injected synthesizer.
 */
class SynthesisPaperServiceTest {

    @TempDir Path indexDir;

    private static SynthesisPaperService svc() {
        return new SynthesisPaperService(
                mock(com.emergentterrain.repo.ResearchPaperRepository.class),
                mock(com.emergentterrain.research.memory.ResearchMemoryRepository.class),
                null, true, 5, 3, 6, /*reSynthesisMinNewSources*/ 0, "research");
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
    void reSynthesisDecision_composesNewRefreshesGrownSkipsUnchanged() {
        // never synthesized → always compose
        assertThat(SynthesisPaperService.shouldSkipFamily(false, 0, 5)).isFalse();
        assertThat(SynthesisPaperService.shouldSkipFamily(false, 99, 0)).isFalse();
        // synthesized, re-synthesis OFF (0) → permanent skip (legacy one-per-family)
        assertThat(SynthesisPaperService.shouldSkipFamily(true, 99, 0)).isTrue();
        // synthesized, evidence-based: skip until enough NEW sources accrue, then re-synthesize
        assertThat(SynthesisPaperService.shouldSkipFamily(true, 4, 5)).isTrue();    // 4 < 5 → skip
        assertThat(SynthesisPaperService.shouldSkipFamily(true, 5, 5)).isFalse();   // 5 ≥ 5 → re-synthesize
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

    @Test
    void parseTitles_handlesLongDescriptiveTitles_noSeparatorCapture() {
        // synthesis #2 live fixture: a 125-char title between two others. The
        // old {1,120} cap failed on it and captured the ", " separators as
        // "," titles, corrupting the parse for every title after it.
        String longTitle = "A common negative lesson: 2 feature plus constant "
                + "models fail across prime, density, Mertens, and additive-arithmetic "
                + "predictors over the tested range";  // > 120 chars
        String outline = "[\"Systematic refutation of affine bounds\",\"" + longTitle
                + "\",\"Robustness of the conclusion\"]";
        List<String> titles = SynthesisPaperService.parseTitles(outline);
        assertThat(titles).containsExactly(
                "Systematic refutation of affine bounds", longTitle,
                "Robustness of the conclusion");
        assertThat(titles).noneMatch(t -> t.equals(",") || t.isBlank());
    }

    @Test
    void leakedReasoningSections_majorityNullsTheSynthesis() {
        SynthesisPaperService s = svc();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        s.setSynthesizer(prompt -> {
            if (prompt.contains("Propose between")) return "[\"Clean\",\"Leak A\",\"Leak B\"]";
            int i = n.incrementAndGet();
            return i == 1 ? "The ceiling holds at ~0.45 [P-b72c-214]."
                    : "We need to write the section. The task: synthesize [P-b72c-232].";
        });
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), null);
        assertThat(r.isNull()).isTrue();                 // 2/3 leaked → don't publish a stub
        assertThat(r.nullReason()).contains("leaked reasoning");
    }

    @Test
    void oneLeakAmongCleanSections_dropsJustThatSection() {
        SynthesisPaperService s = svc();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        s.setSynthesizer(prompt -> {
            if (prompt.contains("Propose between")) return "[\"A\",\"B\",\"C\"]";
            int i = n.incrementAndGet();
            return i == 2 ? "We need to write the section B here [P-b72c-218]."
                    : "Clean prose for the section [P-b72c-214].";
        });
        SynthesisPaperService.SynthesisResult r =
                s.compose("MATHEMATICS", cramerWarrant(), null);
        assertThat(r.isNull()).isFalse();
        assertThat(r.bodyMarkdown()).contains("## A").contains("## C")
                .doesNotContain("## B");                 // the one leak dropped
    }

    @Test
    void leakDetector_recognizesPlanningHeads() {
        assertThat(SynthesisComposer.looksLikeLeakedReasoning(
                "We need to write the section ...")).isTrue();
        assertThat(SynthesisComposer.looksLikeLeakedReasoning(
                "The task: synthesize the corpus")).isTrue();
        assertThat(SynthesisComposer.looksLikeLeakedReasoning(
                "The window-local Cramer ratio is bounded by 0.45 [P-1].")).isFalse();
        assertThat(SynthesisComposer.looksLikeLeakedReasoning(null)).isFalse();
    }

    @Test
    void fullPaperLiterature_prefersPhase34SearchAndReadOverLegacyGrounding() throws Exception {
        Phase34SqliteLiterature phase34 = indexedPhase34Adapter();

        SynthesisPaperService.FullPaperLiterature literature =
                SynthesisPaperService.fullPaperLiterature(phase34, null, null, "prime gap bound");

        assertThat(literature.source()).isEqualTo("phase34-sqlite");
        assertThat(literature.groundingBlock()).contains("Phase 34 SQLite theorem index")
                .contains("[HF");
        assertThat(literature.reads()).contains("read_reference HF")
                .contains("confirmed range");
    }

    private Phase34SqliteLiterature indexedPhase34Adapter() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE papers (paper_id TEXT PRIMARY KEY, title TEXT, authors TEXT, primary_category TEXT)");
            sql.execute("CREATE TABLE theorems (theorem_id INTEGER PRIMARY KEY, paper_id TEXT)");
            sql.execute("CREATE TABLE fulltext (paper_id TEXT PRIMARY KEY, source_key TEXT)");
            sql.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("CREATE VIRTUAL TABLE theorem_fts USING fts5(theorem_id UNINDEXED, paper_id UNINDEXED, name, slogan, body)");
            sql.execute("CREATE VIRTUAL TABLE fulltext_fts USING fts5(paper_id UNINDEXED, title, text)");
            sql.execute("INSERT INTO papers VALUES ('2401.01234', 'Prime gap bounds', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO theorems VALUES (1, '2401.01234')");
            sql.execute("INSERT INTO theorem_fts VALUES (1, '2401.01234', 'Prime gap theorem', 'gap bound', 'The prime gap has a tested bound.')");
            sql.execute("INSERT INTO fulltext VALUES ('2401.01234', 'no-problem/2401/2401.01234.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2401.01234', 'Prime gap bounds', 'The confirmed range gives a prime gap bound with a reproducible calculation.')");
            sql.execute("INSERT INTO metadata VALUES ('fulltext_fts_complete', 'true')");
        }
        return new Phase34SqliteLiterature(indexDir);
    }
}
