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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private static final ThreadLocal<String> routedShardTelemetry = new ThreadLocal<>();
    private static final Pattern TOKEN = Pattern.compile("[\\p{Alnum}]{2,}");
    private static final Pattern ISSUED_KEY_LINE = Pattern.compile("(?m)^- \\[(HF[0-9A-F]+)] .*$", Pattern.CASE_INSENSITIVE);
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
    private static final int DEFAULT_MAX_CONCURRENT_PRIMARY_SEARCHES = 2;
    private static final String PHASE45_ANCHOR_STATISTICS_VERSION = "phase45-anchor-statistics-v1";
    private static final String PHASE45_TITLE_ANCHOR_DOCUMENTS_VERSION = "phase45-title-anchor-documents-v1";

    private final Path database;
    private final boolean phase35RetrievalEnabled;
    private final long searchTimeoutMs;
    private final int initialSearchCandidates;
    private final LiteratureQueryPlanner queryPlanner;
    private final PaperReviewScreenedLiterature paperReviewScreenedLiterature;
    private final ShardedPhase34Literature shardedPrimary;
    private final int maxConcurrentPrimarySearches;
    private volatile boolean candidateFirstMembershipObserverEnabled;
    // This is deliberately only an in-flight map, never a result cache. It prevents concurrent
    // retries of the same semantic lookup from competing for the immutable SQLite/FTS pages.
    private final Map<SearchFlightKey, CompletableFuture<String>> inFlightSearches = new ConcurrentHashMap<>();
    // Separate immutable index generations never share permits.  A fair semaphore keeps distinct
    // semantic searches FIFO within one generation while single-flight still coalesces duplicates.
    private final Map<String, Semaphore> primarySearchPermits = new ConcurrentHashMap<>();
    private final Map<String, IssuedSource> sourceForKey = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IssuedSource> eldest) {
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
                new DeterministicLiteratureQueryPlanner(), null, null);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            LiteratureQueryPlanner queryPlanner) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, DEFAULT_INITIAL_SEARCH_CANDIDATES,
                queryPlanner, null, null);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            int initialSearchCandidates, LiteratureQueryPlanner queryPlanner) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, initialSearchCandidates, queryPlanner, null);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            int initialSearchCandidates, LiteratureQueryPlanner queryPlanner,
                            PaperReviewScreenedLiterature paperReviewScreenedLiterature) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, initialSearchCandidates, queryPlanner,
                paperReviewScreenedLiterature, null);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            int initialSearchCandidates, LiteratureQueryPlanner queryPlanner,
                            PaperReviewScreenedLiterature paperReviewScreenedLiterature,
                            ShardedPhase34Literature shardedPrimary) {
        this(indexDir, phase35RetrievalEnabled, searchTimeoutMs, initialSearchCandidates, queryPlanner,
                paperReviewScreenedLiterature, shardedPrimary, DEFAULT_MAX_CONCURRENT_PRIMARY_SEARCHES);
    }

    Phase34SqliteLiterature(Path indexDir, boolean phase35RetrievalEnabled, long searchTimeoutMs,
                            int initialSearchCandidates, LiteratureQueryPlanner queryPlanner,
                            PaperReviewScreenedLiterature paperReviewScreenedLiterature,
                            ShardedPhase34Literature shardedPrimary, int maxConcurrentPrimarySearches) {
        this.database = indexDir.resolve("literature.db").toAbsolutePath();
        this.phase35RetrievalEnabled = phase35RetrievalEnabled;
        this.searchTimeoutMs = Math.max(1_000, searchTimeoutMs);
        this.initialSearchCandidates = Math.clamp(initialSearchCandidates, 1, SEARCH_CANDIDATES);
        this.queryPlanner = queryPlanner == null ? new DeterministicLiteratureQueryPlanner() : queryPlanner;
        this.paperReviewScreenedLiterature = paperReviewScreenedLiterature;
        // Treat an unavailable or obsolete routed catalog exactly like no optional routed index.
        // This keeps the complete monolith authoritative rather than allowing a direct caller to
        // convert a safe shard rejection into an unavailable literature service.
        this.shardedPrimary = shardedPrimary != null && shardedPrimary.available() ? shardedPrimary : null;
        this.maxConcurrentPrimarySearches = Math.clamp(maxConcurrentPrimarySearches, 1, 4);
    }

    public boolean available() {
        if (shardedPrimary != null) return shardedPrimary.available();
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

    /** Controlled Phase 45 audit switch; never changes returned evidence or retrieval policy. */
    void setCandidateFirstMembershipObserverEnabled(boolean enabled) {
        this.candidateFirstMembershipObserverEnabled = enabled;
    }

    /**
     * Stable-enough identity for the immutable index generation currently being
     * served.  It is deliberately metadata only: recovery workers use it to
     * notice that an operator has completed an offline backfill/import, never
     * to mutate or reopen the index on the request path.
     */
    public String indexFingerprint() {
        if (shardedPrimary != null) return shardedPrimary.fingerprint();
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
        if (!phase35RetrievalEnabled) return searchOnce(query, proposal);
        return searchSingleFlight(query, proposal);
    }

    /** Adds a coarse routed-shard label to existing source-safe Phase 35 telemetry for one task. */
    String searchAsRoutedShard(String query, LiteratureQueryProposal proposal, String shardName) {
        String prior = routedShardTelemetry.get();
        routedShardTelemetry.set(shardName == null || shardName.isBlank() ? "unknown" : shardName);
        try {
            return search(query, proposal);
        } finally {
            if (prior == null) routedShardTelemetry.remove();
            else routedShardTelemetry.set(prior);
        }
    }

    static String routedShardForTelemetry() {
        String shard = routedShardTelemetry.get();
        return shard == null ? "direct" : shard;
    }

    /**
     * Coalesces only simultaneously active Phase 35 lookups with the same canonical semantic
     * request and immutable-index generation. A completed result is removed immediately, so this
     * is not a cache and cannot carry evidence or failure state into a later request.
     */
    private String searchSingleFlight(String query, LiteratureQueryProposal proposal) {
        SearchFlightKey key = searchFlightKey(query, proposal);
        CompletableFuture<String> leader = new CompletableFuture<>();
        CompletableFuture<String> active = inFlightSearches.putIfAbsent(key, leader);
        if (active == null) {
            long startedNanos = System.nanoTime();
            SearchDispatchLease dispatch = acquirePrimarySearchLease(key, startedNanos);
            if (!dispatch.acquired()) {
                String output = queueBudgetExpiredOutput(dispatch.queueWaitMs());
                leader.complete(output);
                logPrimarySearchDispatch("queue_expired", output, dispatch.queueWaitMs(), 0, key);
                logSingleFlightTelemetry("leader", output, elapsedMsSince(startedNanos), key);
                inFlightSearches.remove(key, leader);
                return output;
            }
            try {
                long remainingMs = remainingSearchBudgetMs(startedNanos);
                if (remainingMs < 1_000) {
                    String output = queueBudgetExpiredOutput(dispatch.queueWaitMs());
                    leader.complete(output);
                    logPrimarySearchDispatch("queue_expired", output, dispatch.queueWaitMs(), 0, key);
                    logSingleFlightTelemetry("leader", output, elapsedMsSince(startedNanos), key);
                    return output;
                }
                String output = searchOnce(query, proposal, remainingMs);
                leader.complete(output);
                logPrimarySearchDispatch("completed", output, dispatch.queueWaitMs(),
                        elapsedMsSince(dispatch.executionStartedNanos()), key);
                logSingleFlightTelemetry("leader", output, elapsedMsSince(startedNanos), key);
                return output;
            } catch (RuntimeException | Error error) {
                leader.completeExceptionally(error);
                logPrimarySearchDispatch("error", "error", dispatch.queueWaitMs(),
                        elapsedMsSince(dispatch.executionStartedNanos()), key);
                logSingleFlightTelemetry("leader_error", "error", elapsedMsSince(startedNanos), key);
                throw error;
            } finally {
                dispatch.release();
                inFlightSearches.remove(key, leader);
            }
        }

        long waitStartedNanos = System.nanoTime();
        try {
            String output = active.join();
            logSingleFlightTelemetry("follower", output, elapsedMsSince(waitStartedNanos), key);
            return output;
        } catch (CompletionException error) {
            logSingleFlightTelemetry("follower_error", "error", elapsedMsSince(waitStartedNanos), key);
            return "search_literature: Phase 34 SQLite query unavailable (shared retrieval failed).";
        }
    }

    private String searchOnce(String query, LiteratureQueryProposal proposal) {
        return searchOnce(query, proposal, searchTimeoutMs);
    }

    private String searchOnce(String query, LiteratureQueryProposal proposal, long requestBudgetMs) {
        if (shardedPrimary != null) return mergeRoutedPrimaryWithScreened(query, proposal,
                shardedPrimary.search(query, proposal, requestBudgetMs));
        SearchTelemetry telemetry = new SearchTelemetry();
        long availabilityStarted = System.nanoTime();
        boolean indexAvailable = available();
        telemetry.availabilityCheckMs = elapsedMsSince(availabilityStarted);
        if (!indexAvailable) {
            logSearchTelemetry(telemetry, "unavailable", 0);
            return "search_literature: Phase 34 SQLite index is not available.";
        }
        long connectionStarted = System.nanoTime();
        try (Connection connection = open()) {
            telemetry.connectionOpenMs = elapsedMsSince(connectionStarted);
            boolean titleAnchorNarrowingAvailable = phase35RetrievalEnabled
                    && hasOfflineTitleAnchorDocuments(connection);
            long statementStarted = System.nanoTime();
            try (PreparedStatement statement = connection.prepareStatement("""
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
                     """);
                 PreparedStatement titleAnchorStatement = titleAnchorNarrowingAvailable
                         ? connection.prepareStatement("""
                     WITH title_candidates AS (
                         SELECT paper_id
                           FROM literature_title_anchor_documents
                          WHERE anchor IN (?, ?)
                       GROUP BY paper_id
                         HAVING COUNT(DISTINCT anchor) = 2
                     ), ranked_theorems AS (
                         SELECT rowid, rank
                           FROM theorem_fts
                          WHERE theorem_fts MATCH ?
                            AND rowid IN (
                                SELECT candidate_fts.rowid
                                  FROM theorem_fts candidate_fts
                                  JOIN theorems t ON candidate_fts.theorem_id = t.theorem_id
                                  JOIN title_candidates titles ON titles.paper_id = t.paper_id
                            )
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
                     """) : null) {
            telemetry.statementPrepareMs = elapsedMsSince(statementStarted);
            QueryBudget budget = phase35RetrievalEnabled ? new QueryBudget(connection, requestBudgetMs) : null;
            List<SearchCandidate> candidateQueries;
            List<SearchCandidate> theoremCandidateQueries;
            if (phase35RetrievalEnabled) {
                // Phase 45 reads only an offline-built B-tree table for the few validated input
                // anchors. It never queries fts5vocab or scans corpus text to estimate selectivity
                // while a paper request is active. Missing metadata preserves the established order.
                long anchorStatisticsStarted = System.nanoTime();
                Map<String, Integer> anchorDocumentCounts = loadOfflineAnchorDocumentCounts(connection, query, proposal);
                telemetry.anchorStatisticsMs = elapsedMsSince(anchorStatisticsStarted);
                telemetry.anchorStatisticsAvailable = !anchorDocumentCounts.isEmpty();
                telemetry.anchorStatisticsTerms = anchorDocumentCounts.size();
                telemetry.titleAnchorNarrowingAvailable = titleAnchorNarrowingAvailable;
                long planStarted = System.nanoTime();
                LiteratureQueryPlan plan = queryPlanner.plan(query, anchorDocumentCounts, proposal);
                telemetry.planMs = elapsedMsSince(planStarted);
                if (!plan.unavailableReason().isBlank()) {
                    if (budget != null) budget.close();
                    logSearchTelemetry(telemetry, "invalid_query", 0);
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
                observeCandidateFirstMembership(connection, theoremCandidateQueries);
                log.info("Phase 35 literature plan: routedShard={} expressions={} candidateEvidenceAnchors={} candidateRequiredTitleAnchors={}",
                        routedShardForTelemetry(), candidateQueries.stream().map(SearchCandidate::expression).toList(),
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
                logSearchTelemetry(telemetry, "invalid_query", 0);
                return "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.";
            }
            Map<String, Citation> distinct = new LinkedHashMap<>();
            try {
                int theoremPasses = phase35RetrievalEnabled && telemetry.titleAnchorNarrowingAvailable ? 2 : 1;
                for (int pass = 0; pass < theoremPasses && distinct.isEmpty(); pass++) {
                    boolean useTitleNarrowing = pass == 0 && telemetry.titleAnchorNarrowingAvailable;
                    if (pass > 0) telemetry.titleAnchorNarrowingFallbacks++;
                    for (int attempt = 0; attempt < theoremCandidateQueries.size(); attempt++) {
                        SearchCandidate candidate = theoremCandidateQueries.get(attempt);
                        telemetry.candidateAttempts++;
                        for (int batchLimit : phase35RetrievalEnabled
                                ? stagedCandidateLimits(initialSearchCandidates) : List.of(LEGACY_SEARCH_ROWS)) {
                            boolean titleNarrowed = useTitleNarrowing && candidate.requiredTitleAnchors().size() == 2;
                            PreparedStatement activeStatement = titleNarrowed ? titleAnchorStatement : statement;
                            if (budget != null) budget.apply(activeStatement);
                            telemetry.batches++;
                            telemetry.lastExpression = candidate.expression();
                            telemetry.lastSurface = titleNarrowed ? "theorem_title_anchor_narrowed" : "theorem";
                            telemetry.lastBatchLimit = batchLimit;
                            long batchStarted = System.nanoTime();
                            try {
                                if (titleNarrowed) {
                                    titleAnchorStatement.setString(1, candidate.normalizedRequiredTitleAnchors().get(0));
                                    titleAnchorStatement.setString(2, candidate.normalizedRequiredTitleAnchors().get(1));
                                    titleAnchorStatement.setString(3, candidate.expression());
                                    titleAnchorStatement.setInt(4, batchLimit);
                                    telemetry.titleAnchorNarrowedCandidates++;
                                } else {
                                    statement.setString(1, candidate.expression());
                                    statement.setInt(2, batchLimit);
                                }
                                try (ResultSet rows = activeStatement.executeQuery()) {
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
                                    distinct.putIfAbsent(paperId, new Citation(key, paperId, title,
                                            rows.getString(3), rows.getString(4), rows.getString(5), SourceIndex.PRIMARY));
                                }
                            }
                        } finally {
                            telemetry.recordBatch("theorem", attempt, batchLimit, elapsedMsSince(batchStarted));
                        }
                        log.debug("Phase 34 literature search attempt={} batchLimit={} anchors={} rows={} results={} "
                                        + "batchElapsedMs={} elapsedMs={} query={}",
                                attempt, batchLimit, candidate.evidenceAnchors().size(), telemetry.rowsInspected,
                                distinct.size(), telemetry.lastBatchElapsedMs,
                                budget == null ? -1 : budget.elapsedMs(), candidate.expression());
                            if (!distinct.isEmpty()) break;
                        }
                        if (!distinct.isEmpty()) break;
                    }
                }
                if (distinct.isEmpty() && phase35RetrievalEnabled) {
                    searchFullTextFallback(connection, budget, candidateQueries, distinct, telemetry);
                }
            } finally {
                if (budget != null) budget.close();
            }
            // The screened index is a peer retrieval surface, not a fallback. Query both and
            // merge their separately ranked result lists without comparing raw FTS/BM25 ranks
            // across databases.
            long screenedMergeStarted = System.nanoTime();
            mergePaperReviewScreenedResults(candidateQueries, distinct);
            telemetry.screenedMergeMs = elapsedMsSince(screenedMergeStarted);
            List<Citation> merged = mergeIndexResults(distinct.values());
            if (merged.isEmpty()) {
                logSearchTelemetry(telemetry, "no_match", 0);
                return "search_literature (Phase 34 SQLite): no theorem-index matches for the supplied query.";
            }
            StringBuilder output = new StringBuilder("search_literature (Phase 34 SQLite + paper-review-screened indexes): "
                    + "EVIDENCE — cite only the returned [HF…] keys.\n");
            for (Citation citation : merged) {
                sourceForKey.put(citation.key(), new IssuedSource(citation.paperId(), citation.sourceIndex(),
                        citation.title()));
                StringBuilder rendered = new StringBuilder("- [").append(citation.key()).append("] ")
                        .append(blankAs(citation.title(), "Untitled record"))
                        .append(" (source: ").append(citation.paperId()).append(", index: ")
                        .append(citation.sourceIndex().label).append(")");
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
            logSearchTelemetry(telemetry, "evidence", merged.size());
            return truncate(output.toString().stripTrailing(), MAX_SEARCH_TOOL_RESULT_CHARS);
        }
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

    private void observeCandidateFirstMembership(Connection connection, List<SearchCandidate> candidates) {
        if (!candidateFirstMembershipObserverEnabled || !hasOfflineTitleAnchorDocuments(connection)
                || !hasOfflineTheoremNameTokenDocuments(connection)) return;
        candidates.stream().filter(candidate -> candidate.requiredTitleAnchors().size() == 2
                        && candidate.expression().equals(candidate.nameAnchorExpression()))
                .findFirst().ifPresent(candidate -> {
                    try {
                        CandidateFirstTheoremParity.Observation observation = CandidateFirstTheoremParity.observe(
                                connection, candidate.normalizedRequiredTitleAnchors(), candidate.queryAnchors());
                        log.info("Phase 45 candidate-first membership audit: routedShard={} parity={} "
                                        + "candidateRows={} ftsRows={}", routedShardForTelemetry(),
                                observation.membershipParity(), observation.candidateFirstRows().size(),
                                observation.ftsRows().size());
                    } catch (SQLException failure) {
                        log.info("Phase 45 candidate-first membership audit: routedShard={} unavailable", routedShardForTelemetry());
                    }
                });
    }

    /** Preserve the screened incremental index when the large primary surface is routed by shard. */
    private String mergeRoutedPrimaryWithScreened(String query, LiteratureQueryProposal proposal, String primaryOutput) {
        if (!phase35RetrievalEnabled || paperReviewScreenedLiterature == null
                || !paperReviewScreenedLiterature.available()) return primaryOutput;
        LiteratureQueryPlan plan = queryPlanner.plan(query, Map.of(), proposal);
        if (!plan.unavailableReason().isBlank() || plan.candidates().isEmpty()) return primaryOutput;
        List<SearchCandidate> candidates = plan.candidates().stream().map(candidate -> new SearchCandidate(
                candidate.expression(), candidate.queryAnchors(), candidate.evidenceAnchors(),
                candidate.minimumEvidenceMatches(), candidate.requiredTitleAnchors())).toList();
        List<PaperReviewScreenedLiterature.Match> screened = paperReviewScreenedLiterature.search(candidates, SEARCH_RESULTS);
        if (screened.isEmpty()) return primaryOutput;
        LinkedHashMap<String, String> rendered = new LinkedHashMap<>();
        Matcher primaryLines = ISSUED_KEY_LINE.matcher(primaryOutput == null ? "" : primaryOutput);
        while (primaryLines.find()) rendered.putIfAbsent(primaryLines.group(1).toUpperCase(Locale.ROOT), primaryLines.group());
        for (PaperReviewScreenedLiterature.Match match : screened) {
            String identity = "paper-review-screened:" + match.paperId();
            String key = keyFor(identity);
            sourceForKey.put(key, new IssuedSource(match.paperId(), SourceIndex.PAPER_REVIEW_SCREENED,
                    match.title()));
            rendered.putIfAbsent(key, "- [" + key + "] " + blankAs(match.title(), "Untitled record")
                    + " (source: " + match.paperId() + ", index: paper-review-screened)");
        }
        if (rendered.isEmpty()) return primaryOutput;
        StringBuilder output = new StringBuilder("search_literature (Phase 34 routed primary + paper-review-screened indexes): "
                + "EVIDENCE — cite only the returned [HF…] keys.\n");
        rendered.values().stream().limit(SEARCH_RESULTS).forEach(line -> output.append(line).append('\n'));
        return output.toString().stripTrailing();
    }

    private SearchFlightKey searchFlightKey(String query, LiteratureQueryProposal proposal) {
        List<String> anchors = proposal == null ? List.of() : proposal.subjectAnchors().stream()
                .map(Phase34SqliteLiterature::normalizeSemanticRequestText).toList();
        return new SearchFlightKey(indexFingerprint(), indexGeneration(), normalizeSemanticRequestText(query), anchors,
                proposal == null ? "" : normalizeSemanticRequestText(proposal.claimedRelation()),
                proposal == null ? "" : normalizeSemanticRequestText(proposal.relevanceRationale()));
    }

    /**
     * A generation contains every local evidence surface this adapter may merge. The values are
     * used only as an in-memory flight key; telemetry exposes a digest rather than filesystem
     * paths or query text.
     */
    private String indexGeneration() {
        String screened = paperReviewScreenedLiterature == null ? "not-configured"
                : paperReviewScreenedLiterature.fingerprint();
        return indexFingerprint() + "|paper-review-screened=" + screened;
    }

    private static String normalizeSemanticRequestText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Acquires a fair permit only for a distinct single-flight leader.  Waiting consumes the same
     * request budget as SQLite execution, so queue expiry remains an unresolved retrieval and
     * can enter the existing timeout-recovery lane.
     */
    private SearchDispatchLease acquirePrimarySearchLease(SearchFlightKey key, long requestStartedNanos) {
        Semaphore permits = primarySearchPermits.computeIfAbsent(key.primaryGeneration(), ignored ->
                new Semaphore(maxConcurrentPrimarySearches, true));
        long queueStartedNanos = System.nanoTime();
        long remainingMs = remainingSearchBudgetMs(requestStartedNanos);
        try {
            if (remainingMs < 1 || !permits.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)) {
                return SearchDispatchLease.notAcquired(elapsedMsSince(queueStartedNanos));
            }
            return SearchDispatchLease.acquired(permits, elapsedMsSince(queueStartedNanos), System.nanoTime());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return SearchDispatchLease.notAcquired(elapsedMsSince(queueStartedNanos));
        }
    }

    private long remainingSearchBudgetMs(long requestStartedNanos) {
        return Math.max(0, searchTimeoutMs - elapsedMsSince(requestStartedNanos));
    }

    private String queueBudgetExpiredOutput(long queueWaitMs) {
        return "search_literature: Phase 34 SQLite retrieval exceeded its " + searchTimeoutMs
                + "ms Phase 45 queue budget after " + queueWaitMs + "ms waiting; no evidence was issued.";
    }

    private static void logPrimarySearchDispatch(String state, String output, long queueWaitMs,
                                                 long executionMs, SearchFlightKey key) {
        log.info("Phase 45 literature dispatch: routedShard={} state={} outcome={} queueWaitMs={} executionMs={} generation={}",
                routedShardForTelemetry(), state, safeSearchOutcome(output), queueWaitMs, executionMs,
                key.generationDigest());
    }

    private static void logSingleFlightTelemetry(String role, String output, long elapsedMs, SearchFlightKey key) {
        log.info("Phase 35 literature single-flight: routedShard={} role={} outcome={} elapsedMs={} generation={}",
                routedShardForTelemetry(), role, safeSearchOutcome(output), elapsedMs, key.generationDigest());
    }

    private static String safeSearchOutcome(String output) {
        if (output == null || output.isBlank()) return "error";
        if ("error".equals(output)) return "error";
        if (output.contains("EVIDENCE")) return "evidence";
        if (LiteratureTimeoutRecoveryQueue.isPhase35TimeoutResult(output)) return "timeout";
        if (output.contains("not available") || output.contains("unavailable")) return "unavailable";
        if (output.contains("no sufficiently specific") || output.contains("no valid")) return "invalid_query";
        return "no_match";
    }

    private static String stableDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder rendered = new StringBuilder();
            for (int index = 0; index < 8; index++) rendered.append(String.format("%02X", digest[index]));
            return rendered.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
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

    /**
     * Reads selectivity data only when an offline index build has committed the matching schema
     * version. This is intentionally a small lookup by known safe anchors, not an FTS vocabulary
     * inspection. Any old, partial, or unavailable index falls back to historical planner order.
     */
    private static Map<String, Integer> loadOfflineAnchorDocumentCounts(Connection connection, String query,
                                                                          LiteratureQueryProposal proposal) {
        LinkedHashMap<String, Boolean> requested = new LinkedHashMap<>();
        for (String anchor : DeterministicLiteratureQueryPlanner.extractAnchors(query)) {
            requested.put(LiteratureQueryPlan.normalizeSubjectAnchor(anchor), Boolean.TRUE);
        }
        if (proposal != null && proposal.subjectAnchors() != null) {
            for (String anchor : proposal.subjectAnchors()) {
                String normalized = LiteratureQueryPlan.normalizeSubjectAnchor(anchor);
                if (normalized.matches("[\\p{L}]{2,}")) requested.put(normalized, Boolean.TRUE);
            }
        }
        if (requested.isEmpty()) return Map.of();
        try (PreparedStatement version = connection.prepareStatement(
                "SELECT value FROM metadata WHERE key = 'phase45_anchor_statistics_version'")) {
            try (ResultSet row = version.executeQuery()) {
                if (!row.next() || !PHASE45_ANCHOR_STATISTICS_VERSION.equals(row.getString(1))) return Map.of();
            }
        } catch (SQLException ignored) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(requested.size(), "?"));
        String sql = "SELECT anchor, title_document_count + theorem_name_document_count "
                + "FROM literature_anchor_document_counts WHERE anchor IN (" + placeholders + ")";
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int position = 1;
            for (String anchor : requested.keySet()) statement.setString(position++, anchor);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) counts.put(rows.getString(1), rows.getInt(2));
            }
            return Map.copyOf(counts);
        } catch (SQLException ignored) {
            return Map.of();
        }
    }

    /** A title-anchor index only narrows candidates already required by the Phase 35 title gate. */
    private static boolean hasOfflineTitleAnchorDocuments(Connection connection) {
        try (PreparedStatement version = connection.prepareStatement(
                "SELECT value FROM metadata WHERE key = 'phase45_title_anchor_documents_version'")) {
            try (ResultSet row = version.executeQuery()) {
                return row.next() && PHASE45_TITLE_ANCHOR_DOCUMENTS_VERSION.equals(row.getString(1));
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static boolean hasOfflineTheoremNameTokenDocuments(Connection connection) {
        try (PreparedStatement version = connection.prepareStatement(
                "SELECT value FROM metadata WHERE key = 'phase45_theorem_name_token_documents_version'")) {
            try (ResultSet row = version.executeQuery()) {
                return row.next() && "phase45-theorem-name-token-documents-v1".equals(row.getString(1));
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static void logSearchTelemetry(SearchTelemetry telemetry, String outcome, int resultCount) {
        log.info("Phase 35 literature retrieval: routedShard={} outcome={} elapsedMs={} candidateAttempts={} batches={} "
                        + "rowsInspected={} results={} availabilityCheckMs={} connectionOpenMs={} statementPrepareMs={} "
                        + "anchorStatisticsMs={} anchorStatisticsAvailable={} anchorStatisticsTerms={} "
                        + "titleAnchorNarrowingAvailable={} titleAnchorNarrowedCandidates={} titleAnchorNarrowingFallbacks={} "
                        + "planMs={} screenedMergeMs={} batchTimings={} lastSurface={} lastBatchElapsedMs={} "
                        + "lastBatchLimit={} lastExpression={}",
                routedShardForTelemetry(), outcome, telemetry.elapsedMs(), telemetry.candidateAttempts, telemetry.batches,
                telemetry.rowsInspected, resultCount, telemetry.availabilityCheckMs, telemetry.connectionOpenMs,
                telemetry.statementPrepareMs, telemetry.anchorStatisticsMs, telemetry.anchorStatisticsAvailable,
                telemetry.anchorStatisticsTerms, telemetry.titleAnchorNarrowingAvailable,
                telemetry.titleAnchorNarrowedCandidates, telemetry.titleAnchorNarrowingFallbacks,
                telemetry.planMs, telemetry.screenedMergeMs, telemetry.batchTimings,
                telemetry.lastSurface, telemetry.lastBatchElapsedMs, telemetry.lastBatchLimit, telemetry.lastExpression);
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
                    try {
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
                                distinct.putIfAbsent(paperId, new Citation(key, paperId, rows.getString(2),
                                        rows.getString(3), rows.getString(4), rows.getString(5), SourceIndex.PRIMARY));
                            }
                        }
                    } finally {
                        telemetry.recordBatch("fulltext", telemetry.candidateAttempts - 1, batchLimit,
                                elapsedMsSince(batchStarted));
                    }
                    if (!distinct.isEmpty()) return;
                }
            }
        }
    }

    static String fullTextExpression(SearchCandidate candidate) {
        StringBuilder expression = new StringBuilder(candidate.expression());
        candidate.evidenceAnchors().stream()
                .filter(anchor -> !candidate.queryAnchors().contains(anchor))
                .forEach(anchor -> expression.append(" AND ").append(anchor));
        return expression.toString();
    }

    private void mergePaperReviewScreenedResults(List<SearchCandidate> candidateQueries,
                                                 Map<String, Citation> distinct) {
        if (!phase35RetrievalEnabled || paperReviewScreenedLiterature == null
                || !paperReviewScreenedLiterature.available()) {
            return;
        }
        for (PaperReviewScreenedLiterature.Match match
                : paperReviewScreenedLiterature.search(candidateQueries, SEARCH_RESULTS)) {
            String identity = "paper-review-screened:" + match.paperId();
            distinct.putIfAbsent(identity, new Citation(keyFor(identity), match.paperId(), match.title(),
                    match.authors(), match.category(), match.snippet(), SourceIndex.PAPER_REVIEW_SCREENED));
        }
    }

    /**
     * SQLite rank values are corpus-specific and must not be compared across the primary and
     * paper-review-screened databases. This stable interleave preserves each source's local rank
     * and gives the primary index deterministic precedence only on a tied ordinal.
     */
    private static List<Citation> mergeIndexResults(java.util.Collection<Citation> citations) {
        List<Citation> primary = citations.stream().filter(citation -> citation.sourceIndex() == SourceIndex.PRIMARY)
                .toList();
        List<Citation> screened = citations.stream()
                .filter(citation -> citation.sourceIndex() == SourceIndex.PAPER_REVIEW_SCREENED).toList();
        List<Citation> merged = new ArrayList<>(SEARCH_RESULTS);
        for (int ordinal = 0; merged.size() < SEARCH_RESULTS
                && (ordinal < primary.size() || ordinal < screened.size()); ordinal++) {
            if (ordinal < primary.size()) merged.add(primary.get(ordinal));
            if (merged.size() < SEARCH_RESULTS && ordinal < screened.size()) merged.add(screened.get(ordinal));
        }
        Map<String, Citation> deduplicatedByPaperId = new LinkedHashMap<>();
        for (Citation citation : merged) deduplicatedByPaperId.putIfAbsent(citation.paperId(), citation);
        return List.copyOf(deduplicatedByPaperId.values());
    }

    public String read(String key, String query) {
        if (shardedPrimary != null) {
            String routed = shardedPrimary.read(key, query);
            if (routed != null) return routed;
        }
        IssuedSource issued = key == null ? null : sourceForKey.get(key.strip());
        if (issued == null) {
            return "read_reference: unknown Phase 34 key — call search_literature first and pass its [HF…] key exactly.";
        }
        if (issued.sourceIndex() == SourceIndex.PAPER_REVIEW_SCREENED) {
            if (paperReviewScreenedLiterature == null || !paperReviewScreenedLiterature.available()) {
                return "read_reference: paper-review-screened literature index is not available.";
            }
            String output = paperReviewScreenedLiterature.read(key.strip(), issued.paperId(), issued.title(), query);
            if (output.startsWith("read_reference " + key.strip())) readOutputForKey.put(key.strip(), output);
            return output;
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
            statement.setString(1, issued.paperId());
            statement.setString(2, ftsQuery);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return "read_reference: no matching full-text passage is currently indexed for " + key + ".";
                }
                String title = blankAs(row.getString(1), issued.paperId());
                String sourceKey = blankAs(row.getString(2), issued.paperId());
                String passage = blankAs(row.getString(3), "No passage text was returned.");
                if (passage.length() > MAX_PASSAGE_CHARS) passage = passage.substring(0, MAX_PASSAGE_CHARS) + "…";
                if (!passageMatchesIssuedTitle(issued.title(), title, passage)) {
                    return "read_reference: indexed passage failed the issued-record consistency check for " + key
                            + "; no source evidence was returned.";
                }
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
        if (shardedPrimary != null) {
            String routed = shardedPrimary.assess(key, supportingExcerpt, verdict);
            if (routed != null) return routed;
        }
        String normalizedKey = key == null ? "" : key.strip();
        String readOutput = readOutputForKey.get(normalizedKey);
        if (!sourceForKey.containsKey(normalizedKey) || readOutput == null) {
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

    /**
     * A key is a capability for one issued record, not merely a paper-id-shaped handle.  Require a
     * returned passage to retain a substantive word from the issued title; this catches
     * broken full-text joins (for example a twin-prime title with D-brane passage text) before a
     * model can assess or cite it.  A malformed TeX title also fails closed instead of becoming
     * apparently grounded evidence.
     */
    static boolean passageMatchesIssuedTitle(String issuedTitle, String returnedTitle, String passage) {
        if (!normalizeEvidenceText(issuedTitle).equals(normalizeEvidenceText(returnedTitle))) {
            return false;
        }
        List<String> titleTerms = normalizedTokenTerms(issuedTitle).stream()
                .filter(term -> term.length() >= 4 && !Set.of("with", "from", "some", "that", "this",
                        "into", "over", "under", "about", "results", "problem", "function").contains(term))
                .distinct().toList();
        if (titleTerms.size() < 2) return false;
        List<String> passageTerms = normalizedTokenTerms(passage);
        return titleTerms.stream().anyMatch(passageTerms::contains);
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

    static String legacyFtsQuery(String input) {
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

    private static long elapsedMsSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
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
    static boolean hasSufficientFullTextEvidence(String metadataTitle, String indexedTitle, String text,
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
                    return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadlineNanos ? 0 : 1;
                }
            });
        }

        private void apply(PreparedStatement statement) throws SQLException {
            ensureTimeRemaining();
            statement.setQueryTimeout((int) Math.max(1, Math.ceil(timeoutMs / 1_000.0)));
        }

        private void ensureTimeRemaining() throws SQLException {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
                throw new SQLException("Phase 35 SQLite retrieval timeout");
            }
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
                            String category, String snippet, SourceIndex sourceIndex) {}

    private record SearchFlightKey(String primaryGeneration, String indexGeneration, String query, List<String> subjectAnchors,
                                   String claimedRelation, String relevanceRationale) {
        private SearchFlightKey {
            subjectAnchors = List.copyOf(subjectAnchors);
        }

        private String generationDigest() {
            return stableDigest(indexGeneration);
        }
    }

    private record SearchDispatchLease(Semaphore permits, boolean acquired, long queueWaitMs,
                                       long executionStartedNanos) {
        private static SearchDispatchLease acquired(Semaphore permits, long queueWaitMs, long executionStartedNanos) {
            return new SearchDispatchLease(permits, true, queueWaitMs, executionStartedNanos);
        }

        private static SearchDispatchLease notAcquired(long queueWaitMs) {
            return new SearchDispatchLease(null, false, queueWaitMs, 0);
        }

        private void release() {
            if (acquired) permits.release();
        }
    }

    private record IssuedSource(String paperId, SourceIndex sourceIndex, String title) {}

    private enum SourceIndex {
        PRIMARY("primary"),
        PAPER_REVIEW_SCREENED("paper-review-screened");

        private final String label;

        SourceIndex(String label) {
            this.label = label;
        }
    }

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

        private String nameAnchorExpression() {
            return queryAnchors.stream().map(anchor -> "name : " + anchor)
                    .collect(java.util.stream.Collectors.joining(" AND "));
        }
    }

    private static final class SearchTelemetry {
        private final long startedNanos = System.nanoTime();
        private int candidateAttempts;
        private int batches;
        private int rowsInspected;
        private long availabilityCheckMs = -1;
        private long connectionOpenMs = -1;
        private long statementPrepareMs = -1;
        private long anchorStatisticsMs = -1;
        private boolean anchorStatisticsAvailable;
        private int anchorStatisticsTerms;
        private boolean titleAnchorNarrowingAvailable;
        private int titleAnchorNarrowedCandidates;
        private int titleAnchorNarrowingFallbacks;
        private long planMs = -1;
        private long screenedMergeMs = -1;
        private final List<String> batchTimings = new ArrayList<>();
        private long lastBatchElapsedMs;
        private int lastBatchLimit;
        private String lastSurface = "none";
        private String lastExpression = "";

        private void recordBatch(String surface, int attempt, int batchLimit, long elapsedMs) {
            lastBatchElapsedMs = elapsedMs;
            batchTimings.add(surface + ":attempt=" + attempt + ":limit=" + batchLimit + ":elapsedMs=" + elapsedMs);
        }

        private long elapsedMs() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }
    }

}
