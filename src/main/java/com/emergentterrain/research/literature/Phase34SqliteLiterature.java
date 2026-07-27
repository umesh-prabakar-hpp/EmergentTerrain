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
import java.sql.Statement;
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
    // Start cheaply on a cold index, then retain the complete Phase 35 eligibility range if needed.
    private static final int DEFAULT_INITIAL_SEARCH_CANDIDATES = 64;
    private static final int LEGACY_SEARCH_ROWS = 12;
    private static final int SEARCH_RESULTS = 3;
    // Tool results become part of the next model/proxy request. Bound this independently of FTS
    // row limits so a pathological title, author string, or snippet cannot produce E2BIG.
    private static final int MAX_SEARCH_TOOL_RESULT_CHARS = 12_000;
    private static final int MAX_SEARCH_SNIPPET_CHARS = 1_800;
    private static final int MAX_PASSAGE_CHARS = 7_500;
    private static final int MAX_ISSUED_KEYS = 4_096;
    private static final long DEFAULT_SEARCH_TIMEOUT_MS = 15_000;
    private static final int PROGRESS_HANDLER_VM_STEPS = 1_000;
    // Leave cleanup/driver scheduling room inside the externally visible budget.
    private static final long BUDGET_HEADROOM_MS = 750;

    private final Path database;
    private final boolean phase35RetrievalEnabled;
    private final long searchTimeoutMs;
    private final int initialSearchCandidates;
    private final LiteratureQueryPlanner queryPlanner;
    private final Map<String, String> paperForKey = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ISSUED_KEYS;
        }
    });
    private final Map<String, String> readOutputForKey = Collections.synchronizedMap(new LinkedHashMap<>() {
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
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, DEFAULT_INITIAL_SEARCH_CANDIDATES,
                new DeterministicLiteratureQueryPlanner());
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            LiteratureQueryPlanner queryPlanner) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, DEFAULT_INITIAL_SEARCH_CANDIDATES, queryPlanner);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            int initialSearchCandidates, LiteratureQueryPlanner queryPlanner) {
        this.database = indexDir.resolve("literature.db").toAbsolutePath();
        this.phase35RetrievalEnabled = phase35RetrievalEnabled;
        this.searchTimeoutMs = Math.max(1_000, searchTimeoutMs);
        this.initialSearchCandidates = Math.clamp(initialSearchCandidates, 1, SEARCH_CANDIDATES);
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

    /**
     * Stable-enough identity for the immutable index generation currently being
     * served.  It is deliberately metadata only: recovery workers use it to
     * notice that an operator has completed an offline backfill/import, never
     * to mutate or reopen the index on the request path.
     */
    public String indexFingerprint() {
        try {
            if (!Files.isRegularFile(database)) return "unavailable";
            return database + ":" + Files.size(database) + ":"
                    + Files.getLastModifiedTime(database).toMillis();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    public String search(String query) {
        return search(query, null);
    }

    /**
     * Searches a model-proposed semantic query only through the same bounded planner and
     * evidence gates as deterministic claim extraction. Invalid proposals fail closed.
     */
    public String search(String query, LiteratureQueryProposal proposal) {
        SearchTelemetry telemetry = new SearchTelemetry();
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
            List<SearchCandidate> theoremCandidateQueries;
            if (phase35RetrievalEnabled) {
                // Looking up each term in fts5vocab can itself scan the large on-disk vocabulary.
                // The planner therefore treats an empty map as "unknown statistics" and lets the
                // bounded phrase FTS searches establish whether a term exists within one budget.
                LiteratureQueryPlan plan = queryPlanner.plan(query, Map.of(), proposal);
                if (!plan.unavailableReason().isBlank()) {
                    if (budget != null) budget.close();
                    return "search_literature: " + plan.unavailableReason();
                }
                candidateQueries = plan.candidates().stream()
                        .map(candidate -> new SearchCandidate(candidate.expression(), candidate.queryAnchors(),
                                candidate.evidenceAnchors(), candidate.minimumEvidenceMatches(),
                                candidate.requiredTitleAnchors()))
                        .toList();
                // Ranking a broad body-term conjunction still makes FTS score a very large
                // posting list before it can emit even the first 64 rows. Search the theorem
                // statement name field first: it is a safe, bounded narrowing attempt whose
                // general theorem query remains immediately behind it for complete recall.
                theoremCandidateQueries = theoremNameFirstCandidates(candidateQueries);
                log.info("Phase 35 literature plan: expressions={} candidateEvidenceAnchors={} candidateRequiredTitleAnchors={}",
                        candidateQueries.stream().map(SearchCandidate::expression).toList(),
                        candidateQueries.stream().map(SearchCandidate::evidenceAnchors).toList(),
                        candidateQueries.stream().map(SearchCandidate::requiredTitleAnchors).toList());
            } else {
                candidateQueries = legacySearchQueries(query).stream()
                        .map(candidate -> new SearchCandidate(candidate, List.of(), List.of(), 0, List.of()))
                        .toList();
                theoremCandidateQueries = candidateQueries;
            }
            if (candidateQueries.isEmpty()) {
                if (budget != null) budget.close();
                return "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.";
            }
            Map<String, Citation> distinct = new LinkedHashMap<>();
            try {
                for (int attempt = 0; attempt < theoremCandidateQueries.size(); attempt++) {
                    SearchCandidate candidate = theoremCandidateQueries.get(attempt);
                    telemetry.candidateAttempts++;
                    for (int batchLimit : phase35RetrievalEnabled
                            ? stagedCandidateLimits(initialSearchCandidates) : List.of(LEGACY_SEARCH_ROWS)) {
                        if (budget != null) budget.apply(statement);
                        telemetry.batches++;
                        telemetry.lastExpression = candidate.expression();
                        telemetry.lastSurface = "theorem";
                        telemetry.lastBatchLimit = batchLimit;
                        long batchStarted = System.nanoTime();
                        statement.setString(1, candidate.expression());
                        statement.setInt(2, batchLimit);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next() && distinct.size() < SEARCH_RESULTS) {
                                telemetry.rowsInspected++;
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
                        telemetry.lastBatchElapsedMs = (System.nanoTime() - batchStarted) / 1_000_000L;
                        log.debug("Phase 34 literature search attempt={} batchLimit={} anchors={} rows={} results={} "
                                        + "batchElapsedMs={} elapsedMs={} query={}",
                                attempt, batchLimit, candidate.evidenceAnchors().size(), telemetry.rowsInspected,
                                distinct.size(), telemetry.lastBatchElapsedMs,
                                budget == null ? -1 : budget.elapsedMs(), candidate.expression());
                        if (!distinct.isEmpty()) break;
                    }
                    if (!distinct.isEmpty()) break;
                }
                if (distinct.isEmpty() && phase35RetrievalEnabled) {
                    searchFullTextFallback(connection, budget, candidateQueries, distinct, telemetry);
                }
            } finally {
                if (budget != null) budget.close();
            }
            if (distinct.isEmpty()) {
                logSearchTelemetry(telemetry, "no_match", 0);
                return "search_literature (Phase 34 SQLite): no theorem-index matches for the supplied query.";
            }
            StringBuilder output = new StringBuilder("search_literature (Phase 34 SQLite index): "
                    + "EVIDENCE — cite only the returned [HF…] keys.\n");
            for (Citation citation : distinct.values()) {
                StringBuilder rendered = new StringBuilder("- [").append(citation.key()).append("] ")
                        .append(blankAs(citation.title(), "Untitled record"))
                        .append(" (source: ").append(citation.paperId()).append(")");
                if (!citation.authors().isBlank()) rendered.append(" — ").append(citation.authors());
                if (!citation.category().isBlank()) rendered.append(" [category: ").append(citation.category()).append("]");
                if (!citation.snippet().isBlank()) rendered.append("\n  ")
                        .append(truncate(citation.snippet(), MAX_SEARCH_SNIPPET_CHARS));
                rendered.append('\n');
                if (output.length() + rendered.length() > MAX_SEARCH_TOOL_RESULT_CHARS) {
                    String minimal = "- [" + citation.key() + "] "
                            + truncate(blankAs(citation.title(), "Untitled record"), 320)
                            + " (source: " + citation.paperId() + ")\n";
                    if (output.length() + minimal.length() > MAX_SEARCH_TOOL_RESULT_CHARS) {
                        output.append("[Additional evidence details omitted to keep this tool result bounded.]\n");
                        break;
                    }
                    output.append(minimal);
                    output.append("[Evidence details omitted to keep this tool result bounded.]\n");
                    break;
                }
                output.append(rendered);
            }
            logSearchTelemetry(telemetry, "evidence", distinct.size());
            return truncate(output.toString().stripTrailing(), MAX_SEARCH_TOOL_RESULT_CHARS);
        } catch (Exception e) {
            if (isInterrupted(e)) {
                logSearchTelemetry(telemetry, "timeout", 0);
                return "search_literature: Phase 34 SQLite retrieval exceeded its " + searchTimeoutMs
                        + "ms Phase 35 budget; no evidence was issued.";
            }
            logSearchTelemetry(telemetry, "error", 0);
            return "search_literature: Phase 34 SQLite query unavailable (" + safeMessage(e) + ").";
        }
    }

    /**
     * Adds a statement-name-only attempt before the unchanged general theorem query. This avoids
     * scoring a corpus-wide body match for common pairs such as {@code graph isomorphism}; failure
     * falls through to the exact prior candidate, so it is a latency optimization rather than a
     * recall or evidence-policy change.
     */
    static List<SearchCandidate> theoremNameFirstCandidates(List<SearchCandidate> candidates) {
        Map<String, SearchCandidate> ordered = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            String nameExpression = candidate.queryAnchors().stream()
                    .map(anchor -> "name : " + anchor)
                    .collect(java.util.stream.Collectors.joining(" AND "));
            if (!nameExpression.isBlank()) {
                ordered.putIfAbsent(nameExpression, new SearchCandidate(nameExpression,
                        candidate.queryAnchors(), candidate.evidenceAnchors(),
                        candidate.minimumEvidenceMatches(), candidate.requiredTitleAnchors()));
            }
            ordered.putIfAbsent(candidate.expression(), candidate);
        }
        return List.copyOf(ordered.values());
    }

    /** The second stage deliberately retains the established 512-result eligibility range. */
    static List<Integer> stagedCandidateLimits(int initialCandidates) {
        int initial = Math.clamp(initialCandidates, 1, SEARCH_CANDIDATES);
        return initial == SEARCH_CANDIDATES ? List.of(SEARCH_CANDIDATES) : List.of(initial, SEARCH_CANDIDATES);
    }

    private static void logSearchTelemetry(SearchTelemetry telemetry, String outcome, int resultCount) {
        log.info("Phase 35 literature retrieval: outcome={} elapsedMs={} candidateAttempts={} batches={} "
                        + "rowsInspected={} results={} lastSurface={} lastBatchElapsedMs={} lastBatchLimit={} lastExpression={}",
                outcome, telemetry.elapsedMs(), telemetry.candidateAttempts, telemetry.batches,
                telemetry.rowsInspected, resultCount, telemetry.lastSurface, telemetry.lastBatchElapsedMs, telemetry.lastBatchLimit,
                telemetry.lastExpression);
    }

    /**
     * The theorem corpus and full-text companion do not have complete overlap. After the same
     * bounded theorem search misses, look for the validated subject/result anchors in an indexed
     * full-text source. This path never accepts raw model FTS syntax or issues an ungrounded key.
     */
    private void searchFullTextFallback(Connection connection, QueryBudget budget,
                                        List<SearchCandidate> candidateQueries,
                                        Map<String, Citation> distinct,
                                        SearchTelemetry telemetry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
            for (SearchCandidate candidate : candidateQueries) {
                telemetry.candidateAttempts++;
                String expression = fullTextExpression(candidate);
                for (int batchLimit : stagedCandidateLimits(initialSearchCandidates)) {
                    if (budget != null) budget.apply(statement);
                    telemetry.batches++;
                    telemetry.lastExpression = expression;
                    telemetry.lastSurface = "fulltext";
                    telemetry.lastBatchLimit = batchLimit;
                    long batchStarted = System.nanoTime();
                    statement.setString(1, expression);
                    statement.setInt(2, batchLimit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next() && distinct.size() < SEARCH_RESULTS) {
                            telemetry.rowsInspected++;
                            String paperId = rows.getString(1);
                            if (paperId == null || paperId.isBlank()) continue;
                            if (!hasSufficientFullTextEvidence(rows.getString(6), rows.getString(7),
                                    rows.getString(8), candidate)) {
                                continue;
                            }
                            String key = keyFor(paperId);
                            paperForKey.put(key, paperId);
                            distinct.putIfAbsent(paperId, new Citation(key, paperId, rows.getString(2),
                                    rows.getString(3), rows.getString(4), rows.getString(5)));
                        }
                    }
                    telemetry.lastBatchElapsedMs = (System.nanoTime() - batchStarted) / 1_000_000L;
                    if (!distinct.isEmpty()) return;
                }
            }
        }
    }

    private static String fullTextExpression(SearchCandidate candidate) {
        StringBuilder expression = new StringBuilder(candidate.expression());
        candidate.evidenceAnchors().stream()
                .filter(anchor -> !candidate.queryAnchors().contains(anchor))
                .forEach(anchor -> expression.append(" AND ").append(anchor));
        return expression.toString();
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
                String output = "read_reference " + key + " — " + title
                        + " [source: Phase 34 Marin ar5iv Markdown, record " + sourceKey
                        + "; paraphrase and cite the key]\nTop passages matching \""
                        + (query == null ? "" : query) + "\":\n---\n" + passage;
                readOutputForKey.put(key.strip(), output);
                return output;
            }
        } catch (Exception e) {
            return "read_reference: Phase 34 SQLite query unavailable (" + safeMessage(e) + ").";
        }
    }

    /**
     * Accepts model relevance metadata only when it points to a key issued in this process and an
     * excerpt that actually appeared in the corresponding {@link #read(String, String)} output.
     * It never changes retrieval, citation issuance, or publication status by itself.
     */
    public String assessRelevance(String key, String supportingExcerpt, String verdict) {
        String normalizedKey = key == null ? "" : key.strip();
        String readOutput = readOutputForKey.get(normalizedKey);
        if (!paperForKey.containsKey(normalizedKey) || readOutput == null) {
            return "reference_relevance_assessment: rejected — key must be issued and successfully read first.";
        }
        String normalizedExcerpt = normalizeEvidenceText(supportingExcerpt);
        if (normalizedExcerpt.length() < 16 || normalizedExcerpt.length() > 600
                || !normalizeEvidenceText(readOutput).contains(normalizedExcerpt)) {
            return "reference_relevance_assessment: rejected — supporting_excerpt must be a focused passage returned by read_reference.";
        }
        String normalizedVerdict = verdict == null ? "" : verdict.strip().toUpperCase(Locale.ROOT);
        if (!("RELEVANT".equals(normalizedVerdict) || "UNCERTAIN".equals(normalizedVerdict)
                || "NOT_RELEVANT".equals(normalizedVerdict))) {
            return "reference_relevance_assessment: rejected — verdict must be RELEVANT, UNCERTAIN, or NOT_RELEVANT.";
        }
        return "reference_relevance_assessment " + normalizedKey + " — ACCEPTED " + normalizedVerdict
                + ": tied to an issued key and returned read_reference passage; review metadata only.";
    }

    private static String normalizeEvidenceText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro");
        // These are connection-local read settings: they keep FTS ranking/sorting off the shared
        // filesystem and retain enough hot pages for the 64-then-512 bounded search without
        // mutating the immutable index.
        try (Statement settings = connection.createStatement()) {
            settings.execute("PRAGMA query_only=ON");
            settings.execute("PRAGMA temp_store=MEMORY");
            settings.execute("PRAGMA cache_size=-65536");
        }
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

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value == null ? "" : value;
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private static boolean hasSufficientRecordEvidence(String title, String name, String slogan, String body,
                                                       SearchCandidate candidate) {
        if (candidate.evidenceAnchors().isEmpty()) return true;
        List<String> titleTerms = normalizedTokenTerms(title);
        if (!titleTerms.containsAll(candidate.normalizedRequiredTitleAnchors())) {
            return false;
        }
        List<String> recordTerms = new ArrayList<>(titleTerms);
        recordTerms.addAll(normalizedTokenTerms(name));
        recordTerms.addAll(normalizedTokenTerms(slogan));
        recordTerms.addAll(normalizedTokenTerms(body));
        int corroborated = (int) candidate.normalizedEvidenceAnchors().stream().filter(recordTerms::contains).count();
        return corroborated >= candidate.minimumEvidenceMatches();
    }

    /**
     * Full-text fallback is allowed to recover sources absent from the theorem corpus, but it must
     * not turn a generic body phrase into evidence. All evidence anchors must occur in the indexed
     * record and at least one distinctive query anchor must occur in its title. Some full-text
     * companion rows have no title in either the companion or the optional metadata layer. Such
     * a row can only pass when a three-anchor exact subject phrase is fully corroborated and both
     * phrase terms are distinctive; this admits a specific title-less source without reopening
     * generic body-only matches.
     */
    private static boolean hasSufficientFullTextEvidence(String metadataTitle, String indexedTitle, String text,
                                                         SearchCandidate candidate) {
        if (candidate.evidenceAnchors().isEmpty()) return true;
        List<String> titleTerms = new ArrayList<>(normalizedTokenTerms(metadataTitle));
        titleTerms.addAll(normalizedTokenTerms(indexedTitle));
        if (candidate.requiresAllSubjectAnchorsInTitle()) {
            if (!titleTerms.containsAll(candidate.normalizedRequiredTitleAnchors())) return false;
        } else if (!candidate.titleHasDistinctiveQueryAnchor(titleTerms) && !candidate.allowsTitlelessFullText()) {
            return false;
        }
        List<String> recordTerms = new ArrayList<>(titleTerms);
        recordTerms.addAll(normalizedTokenTerms(text));
        return candidate.normalizedEvidenceAnchors().stream().allMatch(recordTerms::contains);
    }

    private static List<String> tokenTerms(String value) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value);
        while (matcher.find()) terms.add(matcher.group().toLowerCase(Locale.ROOT));
        return terms;
    }

    private static List<String> normalizedTokenTerms(String value) {
        return tokenTerms(value).stream().map(LiteratureQueryPlan::normalizeSubjectAnchor).toList();
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

    static record SearchCandidate(String expression, List<String> queryAnchors, List<String> evidenceAnchors,
                                 int minimumEvidenceMatches, List<String> requiredTitleAnchors) {
        private boolean titleHasDistinctiveQueryAnchor(List<String> titleTerms) {
            return normalizedQueryAnchors().stream()
                    .filter(anchor -> !LiteratureQueryPlan.isWeakSubjectTerm(anchor))
                    .anyMatch(titleTerms::contains);
        }

        private boolean requiresAllSubjectAnchorsInTitle() {
            return !expression.startsWith("\"")
                    && requiredTitleAnchors.size() == queryAnchors.size()
                    && !requiredTitleAnchors.isEmpty();
        }

        private List<String> normalizedQueryAnchors() {
            return queryAnchors.stream().map(LiteratureQueryPlan::normalizeSubjectAnchor).distinct().toList();
        }

        private List<String> normalizedEvidenceAnchors() {
            return evidenceAnchors.stream().map(LiteratureQueryPlan::normalizeSubjectAnchor).distinct().toList();
        }

        private List<String> normalizedRequiredTitleAnchors() {
            return requiredTitleAnchors.stream().map(LiteratureQueryPlan::normalizeSubjectAnchor).distinct().toList();
        }

        private boolean allowsTitlelessFullText() {
            return expression.startsWith("\"")
                    && evidenceAnchors.size() >= 3
                    && queryAnchors.stream().noneMatch(LiteratureQueryPlan::isWeakSubjectTerm);
        }
    }

    private static final class SearchTelemetry {
        private final long startedNanos = System.nanoTime();
        private int candidateAttempts;
        private int batches;
        private int rowsInspected;
        private long lastBatchElapsedMs;
        private int lastBatchLimit;
        private String lastSurface = "none";
        private String lastExpression = "";

        private long elapsedMs() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }
    }

}
