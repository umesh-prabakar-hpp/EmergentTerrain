package com.emergentterrain.research.literature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]{2,}");
    private static final int SEARCH_ROWS = 12;
    private static final int SEARCH_RESULTS = 3;
    private static final int MAX_PASSAGE_CHARS = 7_500;
    private static final int MAX_ISSUED_KEYS = 4_096;

    private final Path database;
    private final Map<String, String> paperForKey = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ISSUED_KEYS;
        }
    });

    public Phase34SqliteLiterature(Path indexDir) {
        this.database = indexDir.resolve("literature.db").toAbsolutePath();
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
        String ftsQuery = ftsQuery(query);
        if (!available()) {
            return "search_literature: Phase 34 SQLite index is not available.";
        }
        if (ftsQuery.isBlank()) {
            return "search_literature: provide a claim or topic containing searchable terms.";
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT t.paper_id, COALESCE(p.title, ''), COALESCE(p.authors, ''),
                            COALESCE(p.primary_category, ''),
                            snippet(theorem_fts, 4, '', '', '…', 24)
                       FROM theorem_fts
                       JOIN theorems t ON theorem_fts.theorem_id = t.theorem_id
                       JOIN fulltext f ON f.paper_id = t.paper_id
                  LEFT JOIN papers p ON p.paper_id = t.paper_id
                      WHERE theorem_fts MATCH ?
                   ORDER BY bm25(theorem_fts)
                      LIMIT ?
                     """)) {
            Map<String, Citation> distinct = new LinkedHashMap<>();
            // Prefer an all-terms query: it is materially more precise and avoids ranking every
            // document that contains a generic word from a long claim. Preserve recall with the
            // former OR query only when the focused attempt finds nothing.
            for (String candidateQuery : searchQueries(ftsQuery)) {
                statement.setString(1, candidateQuery);
                statement.setInt(2, SEARCH_ROWS);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next() && distinct.size() < SEARCH_RESULTS) {
                        String paperId = rows.getString(1);
                        if (paperId == null || paperId.isBlank()) continue;
                        String key = keyFor(paperId);
                        paperForKey.put(key, paperId);
                        distinct.putIfAbsent(paperId, new Citation(key, paperId, rows.getString(2),
                                rows.getString(3), rows.getString(4), rows.getString(5)));
                    }
                }
                if (!distinct.isEmpty()) break;
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
        String ftsQuery = ftsQuery(query);
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

    private static String ftsQuery(String input) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(input == null ? "" : input);
        while (matcher.find() && tokens.size() < 8) tokens.add(matcher.group());
        return String.join(" OR ", tokens);
    }

    private static List<String> searchQueries(String broadQuery) {
        String focusedQuery = broadQuery.replace(" OR ", " AND ");
        return focusedQuery.equals(broadQuery) ? List.of(broadQuery) : List.of(focusedQuery, broadQuery);
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

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Citation(String key, String paperId, String title, String authors,
                            String category, String snippet) {}
}
