package com.emergentterrain.research.literature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.ProgressHandler;

import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Durable, low-priority Phase 45 membership audits.
 *
 * <p>The audit deliberately has no request-path connection, deadline, evidence key, or result
 * authority. Search records a small deduplicated job only after its normal retrieval has
 * completed; this worker performs the expensive FTS comparison later on its own read-only
 * connection. Jobs survive a restart and an expired lease is reclaimable by the next worker.
 */
final class CandidateFirstMembershipAuditQueue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CandidateFirstMembershipAuditQueue.class);
    private static final int PROGRESS_HANDLER_VM_STEPS = 1_000;
    private static final int ENQUEUE_BUSY_TIMEOUT_MS = 500;
    private static final int WORKER_BUSY_TIMEOUT_MS = 2_000;

    private final Path stateDatabase;
    private final long auditTimeoutMs;
    private final long pollIntervalMs;
    private final long leaseMs;
    private final int maxAttempts;
    private final ScheduledExecutorService worker;
    private volatile boolean available;

    CandidateFirstMembershipAuditQueue(Path stateDir, long auditTimeoutMs, long pollIntervalMs,
                                       long leaseMs, int maxAttempts) {
        this(stateDir, auditTimeoutMs, pollIntervalMs, leaseMs, maxAttempts, true);
    }

    CandidateFirstMembershipAuditQueue(Path stateDir, long auditTimeoutMs, long pollIntervalMs,
                                       long leaseMs, int maxAttempts, boolean startWorker) {
        this.stateDatabase = stateDir.toAbsolutePath().resolve("candidate-first-membership-audits.db");
        this.auditTimeoutMs = Math.max(250, auditTimeoutMs);
        this.pollIntervalMs = Math.max(250, pollIntervalMs);
        this.leaseMs = Math.max(this.auditTimeoutMs + 1_000, leaseMs);
        this.maxAttempts = Math.clamp(maxAttempts, 1, 10);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "candidate-first-membership-audit");
            thread.setDaemon(true);
            return thread;
        });
        try {
            initialise();
            available = true;
            if (startWorker) {
                worker.scheduleWithFixedDelay(this::runOneSafely, this.pollIntervalMs,
                        this.pollIntervalMs, TimeUnit.MILLISECONDS);
            }
        } catch (IOException | SQLException failure) {
            log.warn("Phase 45 candidate-first audit queue is unavailable: {}", failure.toString());
        }
    }

    void enqueue(Path indexDatabase, String indexFingerprint, List<String> titleAnchors,
                 List<String> nameAnchors) {
        if (!available || !validAnchors(titleAnchors) || !validAnchors(nameAnchors)) return;
        QueuedInput input = new QueuedInput(indexDatabase.toAbsolutePath().toString(),
                indexFingerprint == null ? "unavailable" : indexFingerprint,
                List.copyOf(titleAnchors), List.copyOf(nameAnchors));
        try (Connection connection = stateConnection(ENQUEUE_BUSY_TIMEOUT_MS);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO candidate_first_membership_audits
                         (job_key, index_fingerprint, index_database, title_anchor_one, title_anchor_two,
                          name_anchor_one, name_anchor_two, state, attempts, due_at_ms, created_at_ms, updated_at_ms)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                     ON CONFLICT(job_key) DO NOTHING
                     """)) {
            long now = System.currentTimeMillis();
            insert.setString(1, input.jobKey());
            insert.setString(2, input.indexFingerprint());
            insert.setString(3, input.indexDatabase());
            insert.setString(4, input.titleAnchors().get(0));
            insert.setString(5, input.titleAnchors().get(1));
            insert.setString(6, input.nameAnchors().get(0));
            insert.setString(7, input.nameAnchors().get(1));
            insert.setLong(8, now);
            insert.setLong(9, now);
            insert.setLong(10, now);
            if (insert.executeUpdate() == 1) {
                log.info("Phase 45 candidate-first membership audit queued: generation={} job={}",
                        input.indexFingerprint(), input.jobKey());
            }
        } catch (SQLException failure) {
            // Diagnostic persistence must never alter or fail a literature search.
            log.warn("Phase 45 candidate-first membership audit was not queued: {}", failure.toString());
        }
    }

    /** One durable claim/attempt; package-visible for deterministic tests and controlled runners. */
    void runOne() {
        if (!available) return;
        Job job;
        try {
            job = claimNext();
        } catch (SQLException failure) {
            log.warn("Phase 45 candidate-first audit claim failed: {}", failure.toString());
            return;
        }
        if (job == null) return;

        try (Connection index = readOnlyConnection(Path.of(job.indexDatabase()));
             AuditDeadline deadline = new AuditDeadline(index, auditTimeoutMs)) {
            CandidateFirstTheoremParity.Observation observation = CandidateFirstTheoremParity.observe(
                    index, job.titleAnchors(), job.nameAnchors(), deadline);
            finish(job, observation.membershipParity() ? "PASSED" : "MISMATCHED",
                    observation.candidateFirstRows().size(), observation.ftsRows().size(), "");
        } catch (SQLException failure) {
            retryOrFinish(job, isTimeout(failure) ? "TIMED_OUT" : "FAILED", failure.getMessage());
        } catch (RuntimeException failure) {
            retryOrFinish(job, "FAILED", failure.getMessage());
        }
    }

    List<AuditSnapshot> snapshots() {
        if (!available) return List.of();
        try (Connection connection = stateConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT job_key, state, attempts, candidate_rows, fts_rows, last_error
                       FROM candidate_first_membership_audits ORDER BY created_at_ms, job_key
                     """ );
             ResultSet rows = query.executeQuery()) {
            List<AuditSnapshot> snapshots = new ArrayList<>();
            while (rows.next()) snapshots.add(new AuditSnapshot(rows.getString(1), rows.getString(2),
                    rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getString(6)));
            return List.copyOf(snapshots);
        } catch (SQLException failure) {
            log.warn("Phase 45 candidate-first audit status read failed: {}", failure.toString());
            return List.of();
        }
    }

    private void runOneSafely() {
        try {
            runOne();
        } catch (RuntimeException failure) {
            log.warn("Phase 45 candidate-first audit worker failed: {}", failure.toString());
        }
    }

    private Job claimNext() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = stateConnection()) {
            connection.setAutoCommit(false);
            try {
                Job candidate = null;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT job_key, index_database, title_anchor_one, title_anchor_two,
                               name_anchor_one, name_anchor_two, attempts
                          FROM candidate_first_membership_audits
                         WHERE (state = 'PENDING' AND due_at_ms <= ?)
                            OR (state = 'RUNNING' AND lease_until_ms < ?)
                         ORDER BY created_at_ms, job_key LIMIT 1
                        """)) {
                    select.setLong(1, now);
                    select.setLong(2, now);
                    try (ResultSet rows = select.executeQuery()) {
                        if (rows.next()) candidate = new Job(rows.getString(1), rows.getString(2),
                                List.of(rows.getString(3), rows.getString(4)),
                                List.of(rows.getString(5), rows.getString(6)), rows.getInt(7) + 1);
                    }
                }
                if (candidate == null) {
                    connection.commit();
                    return null;
                }
                try (PreparedStatement claim = connection.prepareStatement("""
                        UPDATE candidate_first_membership_audits
                           SET state = 'RUNNING', attempts = ?, lease_until_ms = ?, updated_at_ms = ?
                         WHERE job_key = ?
                           AND ((state = 'PENDING' AND due_at_ms <= ?)
                             OR (state = 'RUNNING' AND lease_until_ms < ?))
                        """)) {
                    claim.setInt(1, candidate.attempts());
                    claim.setLong(2, now + leaseMs);
                    claim.setLong(3, now);
                    claim.setString(4, candidate.jobKey());
                    claim.setLong(5, now);
                    claim.setLong(6, now);
                    if (claim.executeUpdate() != 1) {
                        connection.commit();
                        return null;
                    }
                }
                connection.commit();
                return candidate;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void finish(Job job, String state, int candidateRows, int ftsRows, String error) {
        try (Connection connection = stateConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE candidate_first_membership_audits
                        SET state = ?, candidate_rows = ?, fts_rows = ?, last_error = ?,
                            lease_until_ms = NULL, updated_at_ms = ?
                      WHERE job_key = ? AND state = 'RUNNING'
                     """)) {
            update.setString(1, state);
            update.setInt(2, candidateRows);
            update.setInt(3, ftsRows);
            update.setString(4, blank(error));
            update.setLong(5, System.currentTimeMillis());
            update.setString(6, job.jobKey());
            if (update.executeUpdate() == 1) {
                log.info("Phase 45 candidate-first membership audit: job={} state={} candidateRows={} ftsRows={}",
                        job.jobKey(), state, candidateRows, ftsRows);
            }
        } catch (SQLException failure) {
            log.warn("Phase 45 candidate-first audit completion write failed: {}", failure.toString());
        }
    }

    private void retryOrFinish(Job job, String failureState, String detail) {
        boolean terminal = job.attempts() >= maxAttempts;
        long now = System.currentTimeMillis();
        long delayMs = Math.min(60_000L, 1_000L << Math.min(6, Math.max(0, job.attempts() - 1)));
        try (Connection connection = stateConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE candidate_first_membership_audits
                        SET state = ?, due_at_ms = ?, lease_until_ms = NULL, last_error = ?, updated_at_ms = ?
                      WHERE job_key = ? AND state = 'RUNNING'
                     """)) {
            update.setString(1, terminal ? failureState : "PENDING");
            update.setLong(2, terminal ? now : now + delayMs);
            update.setString(3, truncate(blank(detail), 500));
            update.setLong(4, now);
            update.setString(5, job.jobKey());
            if (update.executeUpdate() == 1) {
                log.info("Phase 45 candidate-first membership audit: job={} state={} attempts={} error={}",
                        job.jobKey(), terminal ? failureState : "PENDING", job.attempts(),
                        truncate(blank(detail), 180));
            }
        } catch (SQLException persistenceFailure) {
            log.warn("Phase 45 candidate-first audit failure write failed: {}", persistenceFailure.toString());
        }
    }

    private void initialise() throws IOException, SQLException {
        Files.createDirectories(stateDatabase.getParent());
        try (Connection connection = stateConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS candidate_first_membership_audits (
                        job_key TEXT PRIMARY KEY,
                        index_fingerprint TEXT NOT NULL,
                        index_database TEXT NOT NULL,
                        title_anchor_one TEXT NOT NULL,
                        title_anchor_two TEXT NOT NULL,
                        name_anchor_one TEXT NOT NULL,
                        name_anchor_two TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        due_at_ms INTEGER NOT NULL,
                        lease_until_ms INTEGER,
                        candidate_rows INTEGER NOT NULL DEFAULT 0,
                        fts_rows INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT NOT NULL DEFAULT '',
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """);
            sql.execute("""
                    CREATE INDEX IF NOT EXISTS candidate_first_membership_audits_claim
                        ON candidate_first_membership_audits(state, due_at_ms, lease_until_ms, created_at_ms)
                    """);
        }
    }

    private Connection stateConnection() throws SQLException {
        return stateConnection(WORKER_BUSY_TIMEOUT_MS);
    }

    private Connection stateConnection(int busyTimeoutMs) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + stateDatabase);
        try (Statement settings = connection.createStatement()) {
            settings.execute("PRAGMA busy_timeout=" + Math.max(1, busyTimeoutMs));
            settings.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    private static Connection readOnlyConnection(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database.toAbsolutePath() + "?mode=ro");
        try (Statement settings = connection.createStatement()) {
            settings.execute("PRAGMA query_only=ON");
            settings.execute("PRAGMA temp_store=MEMORY");
            settings.execute("PRAGMA cache_size=-16384");
        }
        return connection;
    }

    private static boolean validAnchors(List<String> anchors) {
        return anchors != null && anchors.size() == 2 && anchors.stream().allMatch(anchor -> anchor != null && !anchor.isBlank());
    }

    private static boolean isTimeout(SQLException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("timeout")
                    || message.toLowerCase(Locale.ROOT).contains("interrupted"))) return true;
        }
        return false;
    }

    private static String blank(String value) { return value == null ? "" : value; }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    record AuditSnapshot(String jobKey, String state, int attempts, int candidateRows, int ftsRows, String lastError) {}

    record AuditInput(String indexFingerprint, List<String> titleAnchors, List<String> nameAnchors) {}

    private record QueuedInput(String indexDatabase, String indexFingerprint, List<String> titleAnchors,
                               List<String> nameAnchors) {
        private String jobKey() {
            return sha256(indexFingerprint + "\\n" + indexDatabase + "\\n" + String.join("\\n", titleAnchors)
                    + "\\n" + String.join("\\n", nameAnchors));
        }
    }

    private record Job(String jobKey, String indexDatabase, List<String> titleAnchors,
                       List<String> nameAnchors, int attempts) {}

    /** Query-local deadline; unlike request retrieval it is installed only on the audit connection. */
    static final class AuditDeadline implements AutoCloseable {
        private final Connection connection;
        private final long deadlineNanos;
        private final long timeoutMs;

        private AuditDeadline(Connection connection, long timeoutMs) throws SQLException {
            this.connection = connection;
            this.timeoutMs = timeoutMs;
            this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
            ProgressHandler.setHandler(connection, PROGRESS_HANDLER_VM_STEPS, new ProgressHandler() {
                @Override
                protected int progress() {
                    return System.nanoTime() < deadlineNanos ? 0 : 1;
                }
            });
        }

        void apply(PreparedStatement statement) throws SQLException {
            if (System.nanoTime() >= deadlineNanos) throw new SQLException("candidate-first membership audit timeout");
            statement.setQueryTimeout((int) Math.max(1, Math.ceil(timeoutMs / 1_000.0)));
        }

        @Override
        public void close() throws SQLException {
            ProgressHandler.clearHandler(connection);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte b : digest) encoded.append(String.format("%02x", b));
            return encoded.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
