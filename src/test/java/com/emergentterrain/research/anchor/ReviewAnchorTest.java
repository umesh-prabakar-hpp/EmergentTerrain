package com.emergentterrain.research.anchor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 32 Wave 3 (32.4) — pure parts: prompt contract, lenient verdict
 * parsing, and label normalization over the harvested CSV's real drift.
 */
class ReviewAnchorTest {

    // ── parse ───────────────────────────────────────────────────────────────

    @Test
    void parsesCleanJson() {
        Optional<ReviewAnchor.AnchorVerdict> v = ReviewAnchor.parse(
                "{\"verdict\":\"MINOR_REVISION\",\"rationale\":\"weak presentation\"}");
        assertThat(v).isPresent();
        assertThat(v.get().verdict()).isEqualTo(ReviewAnchor.Verdict.MINOR_REVISION);
        assertThat(v.get().rationale()).isEqualTo("weak presentation");
    }

    @Test
    void parsesJsonWrappedInProse() {
        // Lenient-recovery models wrap the JSON; the field regex still finds it.
        Optional<ReviewAnchor.AnchorVerdict> v = ReviewAnchor.parse(
                "Here is my assessment:\n{\"verdict\":\"reject\",\"rationale\":\"clone\"}\nDone.");
        assertThat(v).isPresent();
        assertThat(v.get().verdict()).isEqualTo(ReviewAnchor.Verdict.REJECT);
    }

    @Test
    void bareKeywordFallback_mostSevereWins() {
        // A rationale mentioning a lesser class while concluding REJECT.
        Optional<ReviewAnchor.AnchorVerdict> v = ReviewAnchor.parse(
                "Not merely MINOR_REVISION material - this is a REJECT: broken simulator.");
        assertThat(v).isPresent();
        assertThat(v.get().verdict()).isEqualTo(ReviewAnchor.Verdict.REJECT);
    }

    @Test
    void unparseableReturnsEmpty_neverDefaults() {
        assertThat(ReviewAnchor.parse("the paper is fine I suppose")).isEmpty();
        assertThat(ReviewAnchor.parse(null)).isEmpty();
        assertThat(ReviewAnchor.parse("")).isEmpty();
    }

    @Test
    void binaryRollUp() {
        assertThat(ReviewAnchor.Verdict.ACCEPTABLE.accept()).isTrue();
        assertThat(ReviewAnchor.Verdict.MINOR_REVISION.accept()).isTrue();
        assertThat(ReviewAnchor.Verdict.MAJOR_REVISION.accept()).isFalse();
        assertThat(ReviewAnchor.Verdict.REJECT.accept()).isFalse();
    }

    @Test
    void promptCarriesRubricAndPaper() {
        String p = ReviewAnchor.buildPrompt("# Paper body here");
        assertThat(p).contains("ACCEPTABLE — math right")
                .contains("EXACTLY one line of JSON")
                .endsWith("# Paper body here");
    }

    // ── labels (real harvest drift pinned) ─────────────────────────────────

    @TempDir
    Path tmp;

    @Test
    void loadNormalizesDriftAndSkipsGarbage() throws Exception {
        Path csv = tmp.resolve("v.csv");
        Files.writeString(csv, """
                paperId,topic,peer,status,verdict,keyIssue,reviewedAt,source
                P-b72c-211,"max_divisor",0.750,PUBLISHED,"Acceptable","None",2026-06-06,b72c-1
                P-b72c-219,"density",0.712,NULL_RESULT,"Minor rev.","quasi-FP",2026-06-06,b72c-2
                P-b72c-217,"gap",0.741,NULL_RESULT,"Major revision","wrong LHS",2026-06-06,b72c-2
                P-b72c-2,"sqrt_win",NEEDS_REVISION,—,"*(no .md — gap)*","In DB only",2026-05-29,old
                P-b72c-211,"max_divisor",0.750,PUBLISHED,"Minor revision","re-review",2026-06-07,b72c-3
                """);
        VerdictLabels.LoadResult r = VerdictLabels.load(csv);
        // Drifted-but-mappable rows normalize; the placeholder row skips;
        // the re-review of 211 supersedes (latest wins).
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.labels()).hasSize(3);
        assertThat(r.labels().stream()
                .filter(l -> l.paperId().equals("P-b72c-211")).findFirst().get().verdict())
                .isEqualTo(ReviewAnchor.Verdict.MINOR_REVISION);
        assertThat(r.labels().stream()
                .filter(l -> l.paperId().equals("P-b72c-219")).findFirst().get().verdict())
                .isEqualTo(ReviewAnchor.Verdict.MINOR_REVISION);
    }

    @Test
    void quotedCommasSurviveTheSplit() {
        String[] f = VerdictLabels.splitCsv(
                "P-x-1,\"density in [300, 750)\",0.5,NULL,\"Acceptable\",\"a, b\",d,s");
        assertThat(f).hasSize(8);
        assertThat(f[1]).isEqualTo("density in [300, 750)");
        assertThat(f[5]).isEqualTo("a, b");
    }
}
