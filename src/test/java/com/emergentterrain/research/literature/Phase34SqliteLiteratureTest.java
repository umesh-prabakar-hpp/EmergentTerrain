package com.emergentterrain.research.literature;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.service.MathToolSuite;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Phase34SqliteLiteratureTest {

    @TempDir Path indexDir;

    private Phase34SqliteLiterature indexedAdapter() throws Exception {
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
        return new Phase34SqliteLiterature(indexDir, true, 15_000);
    }

    @Test
    void theoremSearchReturnsHarnessKeyAndReadReturnsIndexedPassage() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        String search = adapter.search("prime gap bound");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        assertThat(search).contains("Prime gap bounds").contains("2401.01234");

        String read = adapter.read(key.group(1), "confirmed range");
        assertThat(read).startsWith("read_reference " + key.group(1))
                .contains("Top passages matching")
                .contains("confirmed range");
    }

    @Test
    void phase35FailsClosedWhenAnExtraClaimAnchorCannotBeCorroborated() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.search("prime gap nonexistent"))
                .contains("no theorem-index matches")
                .doesNotContain("EVIDENCE");
    }

    @Test
    void phase35FullTextFallbackIssuesAKeyWhenTheTheoremCorpusHasNoLinkedRecord() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO papers VALUES ('1508.06394', 'Error terms for a divisor problem', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO fulltext VALUES ('1508.06394', 'no-problem/1508/1508.06394.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('1508.06394', 'Error terms for a divisor problem', "
                    + "'The error term in the classical Dirichlet divisor problem is studied here.')");
        }

        String search = adapter.search("Dirichlet divisor error");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        assertThat(search).contains("EVIDENCE").contains("1508.06394");
        assertThat(adapter.read(key.group(1), "error term"))
                .startsWith("read_reference " + key.group(1))
                .contains("error term");
    }

    @Test
    void phase35NormalizesInstructionsAndKeepsAtLeastTwoAndAnchors() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "Search the literature for prime gap bounds and prior papers",
                java.util.Map.of("prime", 10, "gap", 20, "bounds", 30));

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("prime AND gap")
                .allMatch(query -> query.contains(" AND ") && !query.contains(" OR "));
    }

    @Test
    void phase35SkipsDuplicateIdentifierTokensAndKeepsDistinctSubjectPhrases() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "omega_Omega_gap density",
                java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"omega gap\"", "\"gap density\"")
                .doesNotContain("\"omega omega\"");
    }

    @Test
    void phase35SuppressesGenericConnectorPhrasesBeforeTheSubjectPhrase() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "two term empirical inequality for twin prime statistic versus prime sq converges", java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .contains("\"twin prime\"")
                .doesNotContain("\"two term\"", "\"term empirical\"", "\"inequality twin\"",
                        "\"prime statistic\"", "\"prime sq\"");
    }

    @Test
    void phase35RejectsGenericPairsCapturedInRunLogsButKeepsTechnicalPhrases() {
        LiteratureQueryPlan dirichlet = new DeterministicLiteratureQueryPlanner().plan(
                "dirichlet divisor error has order one oscillation envelope", java.util.Map.of());
        assertThat(dirichlet.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"dirichlet divisor\"", "\"divisor error\"", "\"oscillation envelope\"")
                .doesNotContain("\"error has\"", "\"has order\"", "\"order one\"", "\"one oscillation\"");

        LiteratureQueryPlan measurement = new DeterministicLiteratureQueryPlanner().plan(
                "maximum prime gap consecutive high location tracks squared", java.util.Map.of());
        assertThat(measurement.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"maximum prime\"", "\"prime gap\"")
                .doesNotContain("\"consecutive high\"", "\"high location\"", "\"tracks squared\"");

        LiteratureQueryPlan wigert = new DeterministicLiteratureQueryPlanner().plan(
                "has wigert max divisor", java.util.Map.of());
        assertThat(wigert.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .contains("\"wigert max\"", "\"max divisor\"")
                .doesNotContain("\"has wigert\"");
    }

    @Test
    void phase35RejectsGenericModelProsePairsFromTheLatestRun() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "integers satisfying omega obeys richly divisible density mean approximately 77316",
                java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"richly divisible\"", "\"divisible density\"")
                .doesNotContain("\"integers satisfying\"", "\"satisfying omega\"", "\"omega obeys\"",
                        "\"obeys richly\"", "\"density mean\"", "\"mean approximately\"");
    }

    @Test
    void phase35RejectsNumericAndRelationProseAnchorsFromThePaperReviewRun() {
        LiteratureQueryPlan cramer = new DeterministicLiteratureQueryPlanner().plan(
                "cramer gap ratio requiring density feature", java.util.Map.of());
        assertThat(cramer.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"cramer gap\"", "\"gap ratio\"")
                .doesNotContain("\"requiring density\"", "\"density feature\"");

        LiteratureQueryPlan twinPrime = new DeterministicLiteratureQueryPlanner().plan(
                "twin prime density 0000 richly divisible density 9700 minus", java.util.Map.of());
        assertThat(twinPrime.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"twin prime\"", "\"prime density\"", "\"richly divisible\"",
                        "\"divisible density\"")
                .doesNotContain("\"density 0000\"", "\"0000 richly\"", "\"density 9700\"");
    }

    @Test
    void phase35BoundsSearchToolOutputBeforeItReachesTheModelProxy() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            String longTitle = "Prime giant " + "x".repeat(20_000);
            sql.execute("INSERT INTO papers VALUES ('2501.00001', '" + longTitle + "', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO theorems VALUES (2, '2501.00001')");
            sql.execute("INSERT INTO theorem_fts VALUES (2, '2501.00001', 'Prime giant theorem', 'prime giant', 'prime giant')");
            sql.execute("INSERT INTO fulltext VALUES ('2501.00001', 'no-problem/2501/2501.00001.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2501.00001', 'Prime giant', 'prime giant')");
        }

        String output = adapter.search("prime giant");

        assertThat(output).contains("2501.00001").hasSizeLessThanOrEqualTo(12_000);
    }

    @Test
    void phase35StagingAlwaysRetainsTheEstablishedFullCandidateRange() {
        assertThat(Phase34SqliteLiterature.stagedCandidateLimits(64)).containsExactly(64, 512);
        assertThat(Phase34SqliteLiterature.stagedCandidateLimits(512)).containsExactly(512);
    }

    @Test
    void phase35FailsClosedWhenOnlyGenericClaimFramingRemains() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.search("finite scale normalized maximal coordinate"))
                .contains("no sufficiently specific literature anchors")
                .doesNotContain("EVIDENCE");
    }

    @Test
    void phase35FlagCanRestoreTheLegacyBroadFallback() throws Exception {
        Phase34SqliteLiterature legacy = new Phase34SqliteLiterature(indexDir, false, 15_000);
        indexedAdapter();

        assertThat(legacy.search("prime nonexistent"))
                .contains("Prime gap bounds")
                .doesNotContain("no theorem-index matches");
    }

    @Test
    void unknownKeyIsRejectedRatherThanResolvedAsAPaperId() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.read("HFDEADBEEF", "prime gap"))
                .contains("unknown Phase 34 key");
    }

    @Test
    void unavailableIndexFailsSoft() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(indexDir);

        assertThat(adapter.search("prime gap")).contains("not available");
    }

    @Test
    void incompleteFullTextIndexIsNotAvailableOrAdvertised() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("INSERT INTO metadata VALUES ('fulltext_fts_complete', 'false')");
        }
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(indexDir);
        MathToolSuite suite = new MathToolSuite(null, new ObjectMapper());
        suite.setPhase34SqliteLiterature(adapter);

        assertThat(adapter.available()).isFalse();
        assertThat(suite.toolsJson(false, true)).doesNotContain("search_literature")
                .doesNotContain("read_reference");
    }

    @Test
    void toolSuiteUsesTheAdapterOnlyWhenItIsExplicitlyWired() throws Exception {
        MathToolSuite suite = new MathToolSuite(null, new ObjectMapper());
        suite.setPhase34SqliteLiterature(indexedAdapter());
        PaperDraft draft = new PaperDraft(null, "bot", "MATHEMATICS", "prime gap bound", "title",
                null, null, null, null, List.of(), 0.0, 0.0, 0.0, 0L, null, 0, "P-test",
                null, null, false, null, null, List.of(), null, null, null, null);

        assertThat(suite.toolsJson(false, true))
                .contains("search_literature")
                .contains("read_reference");
        String search = suite.buildExecutor(draft, new MathToolSuite.ToolMetrics())
                .apply("{\"name\":\"search_literature\",\"arguments\":{\"claim\":\"prime gap bound\"}}");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        String read = suite.buildExecutor(draft, new MathToolSuite.ToolMetrics()).apply(
                "{\"name\":\"read_reference\",\"arguments\":{\"key\":\"" + key.group(1)
                        + "\",\"query\":\"confirmed range\"}}");
        assertThat(read).startsWith("read_reference " + key.group(1));
    }
}
