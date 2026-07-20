package com.emergentterrain.research.literature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.ProgressHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 34 read-only access to the locally prepared SQLite/FTS5 literature index.
 *
 * <p>This adapter is deliberately independent of the active importer: every operation opens the
 * database in read-only mode, and an unavailable or incomplete index returns a descriptive tool
 * result rather than throwing. Search produces short, harness-issued {@code HF...} keys. A key is
 * usable only after this adapter returned it, preventing an LLM from manufacturing a paper id for
 * {@link #read(String, String)}.
 */
public final class Phase34SqliteLiterature {

    private static final Logger log = LoggerFactory.getLogger(Phase34SqliteLiterature.class);
    private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]{2,}");
    // The full-text companion is sparser than the theorem corpus. Oversample FTS hits before
    // the full-text-presence join so a good eligible source is not hidden behind theorem-only rows.
    private static final int SEARCH_CANDIDATES = 512;
    private static final int LEGACY_SEARCH_ROWS = 12;
    private static final int SEARCH_RESULTS = 3;
    private static final int MAX_PASSAGE_CHARS = 7_500;
    private static final int MAX_ISSUED_KEYS = 4_096;
    private static final long DEFAULT_SEARCH_TIMEOUT_MS = 15_000;
    private static final int PROGRESS_HANDLER_VM_STEPS = 1_000;
    // Leave cleanup/driver scheduling room inside the externally visible budget.
    private static final long BUDGET_HEADROOM_MS = 750;

    private final Path database;
    private final boolean phase35RetrievalEnabled;
    private final long searchTimeoutMs;
    private final LiteratureQueryPlanner queryPlanner;
    private final Map<String, String> paperForKey = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ISSUED_KEYS;
        }
    });

    public Phase34SqliteLiterature(Path indexDir) {
        this(indexDir, false, DEFAULT_SEARCH_TIMEOUT_MS);
    }

    /**
     * @param phase35RetrievalEnabled enables the bounded Phase 35 query path. {@code false}
     *                                preserves the Phase 34 query construction for rollback.
     */
    public Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, new DeterministicLiteratureQueryPlanner());
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            LiteratureQueryPlanner queryPlanner) {
        this.database = indexDir.resolve("literature.db").toAbsolutePath();
        this.phase35RetrievalEnabled = phase35RetrievalEnabled;
        this.searchTimeoutMs = Math.max(1_000, searchTimeoutMs);
        this.queryPlanner = queryPlanner == null ? new DeterministicLiteratureQueryPlanner() : queryPlanner;
    }

    public boolean available() {
        if (!Files.isRegularFile(database)) return false;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM metadata WHERE key = 'fulltext_fts_complete'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "true".equalsIgnoreCase(result.getString(1));
            }
        } catch (Exception ignored) {
            // Treat a partial, old, or unreadable staging database as unavailable.
            return false;
        }
    }

    public String search(String query) {
        if (!available()) {
            return "search_literature: Phase 34 SQLite index is not available.";
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     WITH ranked_theorems AS (
                         SELECT rowid, rank
                           FROM theorem_fts
                          WHERE theorem_fts MATCH ?
                       ORDER BY rank
                          LIMIT ?
                     )
                     SELECT t.paper_id, COALESCE(p.title, ''), COALESCE(p.authors, ''),
                            COALESCE(p.primary_category, ''),
                            snippet(theorem_fts, 4, '', '', '…', 24),
                            COALESCE(theorem_fts.name, ''), COALESCE(theorem_fts.slogan, ''),
                            COALESCE(theorem_fts.body, '')
                       FROM ranked_theorems ranked
                       JOIN theorem_fts ON theorem_fts.rowid = ranked.rowid
                       JOIN theorems t ON theorem_fts.theorem_id = t.theorem_id
                     JOIN fulltext f ON f.paper_id = t.paper_id
                  LEFT JOIN papers p ON p.paper_id = t.paper_id
                   ORDER BY ranked.rank
                     """)) {
            QueryBudget budget = phase35RetrievalEnabled ? new QueryBudget(connection, searchTimeoutMs) : null;
            List<SearchCandidate> candidateQueries;
            if (phase35RetrievalEnabled) {
                // Looking up each term in fts5vocab can itself scan the large on-disk vocabulary.
                // The planner therefore treats an empty map as "unknown statistics" and lets the
                // bounded phrase FTS searches establish whether a term exists within one budget.
                LiteratureQueryPlan plan = queryPlanner.plan(query, Map.of());
                if (!plan.unavailableReason().isBlank()) {
                    if (budget != null) budget.close();
                    return "search_literature: " + plan.unavailableReason();
                }
                candidateQueries = plan.candidates().stream()
                        .map(candidate -> new SearchCandidate(candidate.expression(), candidate.evidenceAnchors(),
                                candidate.minimumEvidenceMatches(), candidate.requiredTitleAnchors()))
                        .toList();
                log.info("Phase 35 literature plan: expressions={} evidenceAnchors={} requiredTitleAnchors={}",
                        candidateQueries.stream().map(SearchCandidate::expression).toList(),
                        candidateQueries.isEmpty() ? List.of() : candidateQueries.getFirst().evidenceAnchors(),
                        candidateQueries.isEmpty() ? List.of() : candidateQueries.getFirst().requiredTitleAnchors());
            } else {
                candidateQueries = legacySearchQueries(query).stream()
                        .map(candidate -> new SearchCandidate(candidate, List.of(), 0, List.of()))
                        .toList();
            }
            if (candidateQueries.isEmpty()) {
                if (budget != null) budget.close();
                return "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.";
            }
            Map<String, Citation> distinct = new LinkedHashMap<>();
            try {
                for (int attempt = 0; attempt < candidateQueries.size(); attempt++) {
                    if (budget != null) budget.apply(statement);
                    SearchCandidate candidate = candidateQueries.get(attempt);
                    statement.setString(1, candidate.expression());
                    statement.setInt(2, phase35RetrievalEnabled ? SEARCH_CANDIDATES : LEGACY_SEARCH_ROWS);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next() && distinct.size() < SEARCH_RESULTS) {
                            String paperId = rows.getString(1);
                            if (paperId == null || paperId.isBlank()) continue;
                            String title = rows.getString(2);
                            // A matching pair alone is too weak: generic mathematical wording can
                            // match an unrelated theorem. Phase 35 needs corroboration across the
                            // returned record before it grants an HF citation key.
                            if (phase35RetrievalEnabled && !hasSufficientRecordEvidence(title, rows.getString(6),
                                    rows.getString(7), rows.getString(8), candidate)) continue;
                            String key = keyFor(paperId);
                            paperForKey.put(key, paperId);
                            distinct.putIfAbsent(paperId, new Citation(key, paperId, title,
                                    rows.getString(3), rows.getString(4), rows.getString(5)));
                        }
                    }
                    log.debug("Phase 34 literature search attempt={} anchors={} results={} elapsedMs={} query={}",
                            attempt, candidate.evidenceAnchors().size(), distinct.size(),
                            budget == null ? -1 : budget.elapsedMs(), candidate.expression());
                    if (!distinct.isEmpty()) break;
                }
            } finally {
                if (budget != null) budget.close();
            }
            if (distinct.isEmpty()) {
                return "search_literature (Phase 34 SQLite): no theorem-index matches for the supplied query.";
            }
            StringBuilder output = new StringBuilder("search_literature (Phase 34 SQLite theorem index): "
                    + "EVIDENCE — cite only the returned [HF…] keys.\n");
            for (Citation citation : distinct.values()) {
                output.append("- [").append(citation.key()).append("] ")
                        .append(blankAs(citation.title(), "Untitled record"))
                        .append(" (source: ").append(citation.paperId()).append(")");
                if (!citation.authors().isBlank()) output.append(" — ").append(citation.authors());
                if (!citation.category().isBlank()) output.append(" [category: ").append(citation.category()).append("]");
                if (!citation.snippet().isBlank()) output.append("\n  ").append(citation.snippet());
                output.append('\n');
            }
            return output.toString().stripTrailing();
        } catch (Exception e) {
            if (isInterrupted(e)) {
                return "search_literature: Phase 34 SQLite retrieval exceeded its " + searchTimeoutMs
                        + "ms Phase 35 budget; no evidence was issued.";
            }
            return "search_literature: Phase 34 SQLite query unavailable (" + safeMessage(e) + ").";
        }
    }

    public String read(String key, String query) {
        String paperId = key == null ? null : paperForKey.get(key.strip());
        if (paperId == null) {
            return "read_reference: unknown Phase 34 key — call search_literature first and pass its [HF…] key exactly.";
        }
        if (!available()) {
            return "read_reference: Phase 34 SQLite index is not available.";
        }
        // This is constrained to the one already-issued paper row, so its former OR expression
        // cannot fan out across the corpus as search_literature can.
        String ftsQuery = legacyFtsQuery(query);
        if (ftsQuery.isBlank()) {
            return "read_reference: provide a focused claim or quantity to locate in the source.";
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(p.title, ''), COALESCE(f.source_key, ''),
                            snippet(fulltext_fts, 2, '', '', '…', 180)
                       FROM fulltext_fts
                       JOIN fulltext f ON f.rowid = fulltext_fts.rowid
                  LEFT JOIN papers p ON p.paper_id = f.paper_id
                      WHERE fulltext_fts.rowid = (
                                SELECT rowid FROM fulltext WHERE paper_id = ?
                            )
                        AND fulltext_fts MATCH ?
                      LIMIT 1
                     """)) {
            statement.setString(1, paperId);
            statement.setString(2, ftsQuery);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return "read_reference: no matching full-text passage is currently indexed for " + key + ".";
                }
                String title = blankAs(row.getString(1), paperId);
                String sourceKey = blankAs(row.getString(2), paperId);
                String passage = blankAs(row.getString(3), "No passage text was returned.");
                if (passage.length() > MAX_PASSAGE_CHARS) passage = passage.substring(0, MAX_PASSAGE_CHARS) + "…";
                return "read_reference " + key + " — " + title
                        + " [source: Phase 34 Marin ar5iv Markdown, record " + sourceKey
                        + "; paraphrase and cite the key]\nTop passages matching \""
                        + (query == null ? "" : query) + "\":\n---\n" + passage;
            }
        } catch (Exception e) {
            return "read_reference: Phase 34 SQLite query unavailable (" + safeMessage(e) + ").";
        }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro");
        connection.createStatement().execute("PRAGMA query_only=ON");
        return connection;
    }

    private static List<String> legacySearchQueries(String input) {
        String ftsQuery = legacyFtsQuery(input);
        if (ftsQuery.isBlank()) return List.of();
        String focusedQuery = ftsQuery.replace(" OR ", " AND ");
        return focusedQuery.equals(ftsQuery) ? List.of(ftsQuery) : List.of(focusedQuery, ftsQuery);
    }

    private static String legacyFtsQuery(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input == null ? "" : input);
        while (matcher.find() && tokens.size() < 8) tokens.add(matcher.group());
        return String.join(" OR ", tokens);
    }

    private static String keyFor(String paperId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(paperId.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("HF");
            for (int i = 0; i < 10; i++) key.append(String.format("%02X", digest[i]));
            return key.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean hasSufficientRecordEvidence(String title, String name, String slogan, String body,
                                                       SearchCandidate candidate) {
        if (candidate.evidenceAnchors().isEmpty()) return true;
        List<String> titleTerms = tokenTerms(title);
        if (!titleTerms.containsAll(candidate.requiredTitleAnchors())) {
            return false;
        }
        List<String> recordTerms = new ArrayList<>(titleTerms);
        recordTerms.addAll(tokenTerms(name));
        recordTerms.addAll(tokenTerms(slogan));
        recordTerms.addAll(tokenTerms(body));
        int corroborated = (int) candidate.evidenceAnchors().stream().filter(recordTerms::contains).count();
        return corroborated >= candidate.minimumEvidenceMatches();
    }

    private static List<String> tokenTerms(String value) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value);
        while (matcher.find()) terms.add(matcher.group().toLowerCase(Locale.ROOT));
        return terms;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static boolean isInterrupted(Exception error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("interrupted")
                    || message.toLowerCase(Locale.ROOT).contains("timeout"))) return true;
            current = current.getCause();
        }
        return false;
    }

    /** SQLite's progress handler stops CPU-bound FTS work; JDBC timeout covers the statement API. */
    private static final class QueryBudget implements AutoCloseable {
        private final Connection connection;
        private final long startedNanos = System.nanoTime();
        private final long deadlineNanos;
        private final long timeoutMs;

        private QueryBudget(Connection connection, long timeoutMs) throws SQLException {
            this.connection = connection;
            this.timeoutMs = timeoutMs;
            long executionBudgetMs = Math.max(250, timeoutMs - BUDGET_HEADROOM_MS);
            this.deadlineNanos = startedNanos + executionBudgetMs * 1_000_000L;
            ProgressHandler.setHandler(connection, PROGRESS_HANDLER_VM_STEPS, new ProgressHandler() {
                @Override
                protected int progress() {
                    if (System.nanoTime() < deadlineNanos) return 0;
                    return 1;
                }
            });
        }

        private void apply(PreparedStatement statement) throws SQLException {
            ensureTimeRemaining();
            statement.setQueryTimeout((int) Math.max(1, Math.ceil(timeoutMs / 1_000.0)));
        }

        private void ensureTimeRemaining() throws SQLException {
            if (System.nanoTime() >= deadlineNanos) throw new SQLException("Phase 35 SQLite retrieval timeout");
        }

        private long elapsedMs() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }

        @Override
        public void close() throws SQLException {
            ProgressHandler.clearHandler(connection);
        }
    }

    private record Citation(String key, String paperId, String title, String authors,
                            String category, String snippet) {}

    private record SearchCandidate(String expression, List<String> evidenceAnchors, int minimumEvidenceMatches,
                                   List<String> requiredTitleAnchors) {}

}
