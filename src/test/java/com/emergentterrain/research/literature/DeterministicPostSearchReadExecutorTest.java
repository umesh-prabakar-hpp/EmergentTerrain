package com.emergentterrain.research.literature;

import com.emergentterrain.llm.ToolInvocation;
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

class DeterministicPostSearchReadExecutorTest {

    @TempDir Path indexDir;

    @Test
    void readsTheFirstSafeIssuedKeyWhenProfessorStoppedAfterSearch() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        String search = adapter.search("prime gap bound");
        String key = issuedKey(search);
        String args = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "claim", "prime gap bound",
                "subject_anchors", List.of("prime", "gap"),
                "claimed_relation", "bound comparison",
                "relevance_rationale", "positions the finite calculation"));
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("search_literature", args, search, 0, "call_1"),
                new ToolInvocation("search_literature", "{\"claim\":\"later claim\"}",
                        "search_literature: EVIDENCE [HFDEADBEEF] later result", 1, "call_2"));

        DeterministicPostSearchReadExecutor.Outcome outcome =
                new DeterministicPostSearchReadExecutor(adapter, new ObjectMapper()).execute(trace);

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.key()).isEqualTo(key);
        assertThat(outcome.invocation().functionName()).isEqualTo("read_reference");
        assertThat(outcome.invocation().argsJson()).contains("\"key\":\"" + key + "\"")
                .contains("\"query\":\"prime gap bound\"");
        assertThat(outcome.invocation().resultJson()).startsWith("read_reference " + key)
                .contains("Top passages matching");
    }

    @Test
    void isIdempotentAndNeverRetriesATraceThatAlreadyAttemptedRead() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        String search = adapter.search("prime gap bound");
        String key = issuedKey(search);
        String searchArgs = "{\"claim\":\"prime gap bound\"}";
        String readArgs = "{\"key\":\"" + key + "\",\"query\":\"prime gap bound\"}";
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("search_literature", searchArgs, search, 0, "call_1"),
                new ToolInvocation("read_reference", readArgs, adapter.read(key, "prime gap bound"), 1, "call_2"));

        DeterministicPostSearchReadExecutor.Outcome outcome =
                new DeterministicPostSearchReadExecutor(adapter, new ObjectMapper()).execute(trace);

        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.reason()).isEqualTo("existing_read_attempt");
        assertThat(outcome.invocation()).isNull();
    }

    @Test
    void refusesToReadWhenSearchDidNotIssueSafeEvidence() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        List<ToolInvocation> trace = List.of(new ToolInvocation("search_literature",
                "{\"claim\":\"prime gap bound\"}",
                "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.",
                0, "call_1"));

        DeterministicPostSearchReadExecutor.Outcome outcome =
                new DeterministicPostSearchReadExecutor(adapter, new ObjectMapper()).execute(trace);

        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.reason()).isEqualTo("no_eligible_issued_key");
    }

    @Test
    void assessesAnExactIssuedAndReadPassageOnce() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        String search = adapter.search("prime gap bound");
        String key = issuedKey(search);
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("search_literature",
                        "{\"claim\":\"prime gap bound\",\"subject_anchors\":[\"prime\",\"gap\"]}",
                        search, 0),
                new ToolInvocation("read_reference", "{\"key\":\"" + key + "\"}",
                        adapter.read(key, "prime gap bound"), 1));

        DeterministicPostReadAssessmentExecutor.Outcome outcome =
                new DeterministicPostReadAssessmentExecutor(adapter, new ObjectMapper()).execute(trace);

        assertThat(outcome.attempted()).isTrue();
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.invocation().functionName()).isEqualTo("assess_reference_relevance");
        assertThat(outcome.invocation().resultJson()).contains("ACCEPTED RELEVANT");
    }

    @Test
    void rejectsWhenOnlyTheEchoedQueryContainsTheSecondSubjectAnchor() {
        String read = "read_reference HF123 — Twin prime correlations [source: test]\n"
                + "Top passages matching \"mean omega prime divisors\":\n---\n"
                + "The number of distinct prime factors appears in this unrelated formula.";

        assertThat(DeterministicPostReadAssessmentExecutor.evidenceContainsAllAnchors(read,
                List.of("prime", "divisors"))).isFalse();
        assertThat(DeterministicPostReadAssessmentExecutor.evidenceContainsAllAnchors(read,
                List.of("prime", "factors"))).isTrue();
    }

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

    private static String issuedKey(String search) {
        Matcher matcher = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
