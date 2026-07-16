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
        return new Phase34SqliteLiterature(indexDir);
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
