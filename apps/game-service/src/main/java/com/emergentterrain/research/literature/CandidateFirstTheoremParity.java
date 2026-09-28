package com.emergentterrain.research.literature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only Phase 45 membership comparison; it never issues a source key or changes retrieval. */
final class CandidateFirstTheoremParity {
    private CandidateFirstTheoremParity() {}

    static Observation observe(Connection connection, List<String> titleAnchors, List<String> nameAnchors)
            throws SQLException {
        if (titleAnchors.size() != 2 || nameAnchors.size() != 2) {
            throw new IllegalArgumentException("candidate-first parity requires exactly two title and name anchors");
        }
        Set<Long> candidateFirst = rows(connection, """
                WITH title_candidates AS (
                    SELECT paper_id FROM literature_title_anchor_documents
                     WHERE anchor IN (?, ?) GROUP BY paper_id HAVING COUNT(DISTINCT anchor) = 2
                )
                SELECT tokens.theorem_rowid
                  FROM literature_theorem_name_token_documents tokens
                  JOIN theorem_fts fts ON fts.rowid = tokens.theorem_rowid
                  JOIN theorems theorem ON theorem.theorem_id = fts.theorem_id
                  JOIN title_candidates title ON title.paper_id = theorem.paper_id
                 WHERE tokens.token IN (?, ?)
                 GROUP BY tokens.theorem_rowid HAVING COUNT(DISTINCT tokens.token) = 2
                """, titleAnchors, nameAnchors, null);
        String expression = "name : " + nameAnchors.get(0) + " AND name : " + nameAnchors.get(1);
        Set<Long> fts = rows(connection, """
                WITH title_candidates AS (
                    SELECT paper_id FROM literature_title_anchor_documents
                     WHERE anchor IN (?, ?) GROUP BY paper_id HAVING COUNT(DISTINCT anchor) = 2
                )
                SELECT fts.rowid FROM theorem_fts fts
                  JOIN theorems theorem ON theorem.theorem_id = fts.theorem_id
                  JOIN title_candidates title ON title.paper_id = theorem.paper_id
                 WHERE theorem_fts MATCH ?
                """, titleAnchors, List.of(), expression);
        return new Observation(candidateFirst, fts);
    }

    private static Set<Long> rows(Connection connection, String sql, List<String> titleAnchors,
                                  List<String> nameAnchors, String expression) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, titleAnchors.get(0)); statement.setString(2, titleAnchors.get(1));
            if (expression == null) {
                statement.setString(3, nameAnchors.get(0)); statement.setString(4, nameAnchors.get(1));
            } else statement.setString(3, expression);
            Set<Long> rows = new LinkedHashSet<>();
            try (ResultSet result = statement.executeQuery()) { while (result.next()) rows.add(result.getLong(1)); }
            return Set.copyOf(rows);
        }
    }

    record Observation(Set<Long> candidateFirstRows, Set<Long> ftsRows) {
        boolean membershipParity() { return candidateFirstRows.equals(ftsRows); }
    }
}
