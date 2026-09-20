package com.emergentterrain.research.literature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the small, incrementally maintained paper-review-screened literature index.
 *
 * <p>Its content is hash-validated review discovery text, not an alternate evidence policy. The
 * Phase 34 adapter applies the same query planner, title-anchor gate, issued-key capability, and
 * {@code read_reference} requirement before any result can be used in a paper.
 */
final class PaperReviewScreenedLiterature {

    private static final int MAX_PASSAGE_CHARS = 7_500;
    private final Path database;

    PaperReviewScreenedLiterature(Path indexDir) {
        this.database = indexDir.resolve("literature.db").toAbsolutePath();
    }

    boolean available() {
        if (!Files.isRegularFile(database)) return false;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM metadata WHERE key = 'fulltext_fts_complete'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "true".equalsIgnoreCase(result.getString(1));
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Metadata-only identity for the immutable screened-index generation currently served.
     * It is used to keep an in-flight request from crossing an offline index replacement.
     */
    String fingerprint() {
        try {
            if (!Files.isRegularFile(database)) return "unavailable";
            return database + ":" + Files.size(database) + ":"
                    + Files.getLastModifiedTime(database).toMillis();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    List<Match> search(List<Phase34SqliteLiterature.SearchCandidate> candidates, int maxResults) {
        if (!available() || candidates == null || candidates.isEmpty()) return List.of();
        Map<String, Match> distinct = new LinkedHashMap<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     WITH ranked_sources AS (
                         SELECT rowid, rank
                           FROM fulltext_fts
                          WHERE fulltext_fts MATCH ?
                       ORDER BY rank
                          LIMIT ?
                     )
                     SELECT f.paper_id, COALESCE(p.title, ''), COALESCE(p.authors, ''),
                            COALESCE(p.primary_category, ''),
                            snippet(fulltext_fts, 2, '', '', '…', 24),
                            COALESCE(p.title, ''), COALESCE(fulltext_fts.title, ''),
                            COALESCE(fulltext_fts.text, '')
                       FROM ranked_sources ranked
                       JOIN fulltext_fts ON fulltext_fts.rowid = ranked.rowid
                       JOIN fulltext f ON f.rowid = fulltext_fts.rowid
                  LEFT JOIN papers p ON p.paper_id = f.paper_id
                   ORDER BY ranked.rank
                     """)) {
            for (Phase34SqliteLiterature.SearchCandidate candidate : candidates) {
                statement.setString(1, Phase34SqliteLiterature.fullTextExpression(candidate));
                statement.setInt(2, maxResults);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next() && distinct.size() < maxResults) {
                        String paperId = rows.getString(1);
                        if (paperId == null || paperId.isBlank()
                                || !Phase34SqliteLiterature.hasSufficientFullTextEvidence(
                                        rows.getString(6), rows.getString(7), rows.getString(8), candidate)) {
                            continue;
                        }
                        distinct.putIfAbsent(paperId, new Match(paperId, rows.getString(2), rows.getString(3),
                                rows.getString(4), rows.getString(5)));
                    }
                }
                if (distinct.size() >= maxResults) break;
            }
        } catch (Exception ignored) {
            // The primary index remains available and a screened-index failure is fail-soft.
            return List.of();
        }
        return List.copyOf(distinct.values());
    }

    String read(String key, String paperId, String issuedTitle, String query) {
        String ftsQuery = Phase34SqliteLiterature.legacyFtsQuery(query);
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
                if (passage.length() > MAX_PASSAGE_CHARS) {
                    passage = passage.substring(0, MAX_PASSAGE_CHARS) + "…";
                }
                if (!Phase34SqliteLiterature.passageMatchesIssuedTitle(issuedTitle, title, passage)) {
                    return "read_reference: indexed passage failed the issued-record consistency check for " + key
                            + "; no source evidence was returned.";
                }
                return "read_reference " + key + " — " + title
                        + " [source: paper-review-screened literature index, record " + sourceKey
                        + "; paraphrase and cite the key]\nTop passages matching \""
                        + (query == null ? "" : query) + "\":\n---\n" + passage;
            }
        } catch (Exception error) {
            return "read_reference: paper-review-screened literature index unavailable ("
                    + safeMessage(error) + ").";
        }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro");
        try (Statement settings = connection.createStatement()) {
            settings.execute("PRAGMA query_only=ON");
            settings.execute("PRAGMA temp_store=MEMORY");
        }
        return connection;
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    record Match(String paperId, String title, String authors, String category, String snippet) {}
}
