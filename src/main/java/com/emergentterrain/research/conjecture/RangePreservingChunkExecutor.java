package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 36 vertical slice: executes one immutable logical interval as bounded chunks without
 * changing its scientific scope.  The journal deliberately lives outside {@code research/<room>},
 * which is cleared at application restart, so completed chunks are reusable after a restart.
 *
 * <p>This executor currently exposes only a count-weighted density reducer.  Other scalar types
 * must keep their existing one-shot path until they provide an exact reducer; averaging arbitrary
 * window statistics would turn a transport optimisation into a different experiment.
 */
@Service
public class RangePreservingChunkExecutor {

    private static final String FORMAT_VERSION = "phase36-range-chunks-v1";
    private static final Map<String, Object> JOB_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final Path journalRoot;
    private final int chunkSize;
    private final boolean enabled;

    @Autowired
    public RangePreservingChunkExecutor(
            ObjectMapper mapper,
            @Value("${app.simulationHistory.root:simulation-history}") String simulationHistoryRoot,
            @Value("${app.research.experiments.range-chunking.chunk-size:50000}") int chunkSize,
            @Value("${app.research.experiments.range-chunking.enabled:false}") boolean enabled) {
        this(mapper, Path.of(simulationHistoryRoot).resolve("research-experiments"), chunkSize, enabled);
    }

    /** Test-facing constructor; production wiring uses the constructor above. */
    RangePreservingChunkExecutor(ObjectMapper mapper, Path journalRoot, int chunkSize, boolean enabled) {
        this.mapper = mapper;
        this.journalRoot = journalRoot;
        this.chunkSize = Math.max(1, chunkSize);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Execute an exact {@code [logicalStart, logicalEnd)} density measurement.  The supplied
     * measurement is a density for precisely its chunk interval; aggregation is therefore
     * {@code sum(density * chunkLength) / totalLength}.  A failure persists a PARTIAL or
     * UNAVAILABLE job and returns normally so the caller can fail soft without claiming the full
     * range.
     */
    public DensityJobResult executeDensity(
            String metric, long logicalStart, long logicalEnd, DensityChunkEvaluator evaluator) {
        if (logicalStart < 1 || logicalEnd <= logicalStart) {
            throw new IllegalArgumentException("logical range must be a non-empty positive [start,end) interval");
        }
        String jobId = jobId(metric, logicalStart, logicalEnd, chunkSize);
        synchronized (JOB_LOCKS.computeIfAbsent(jobId, ignored -> new Object())) {
            ExperimentJob job = loadOrCreate(jobId, metric, logicalStart, logicalEnd);
            for (int i = 0; i < job.chunks().size(); i++) {
                ChunkResult chunk = job.chunks().get(i);
                if (chunk.status() == ChunkStatus.COMPLETE) {
                    continue;
                }
                long queuedAt = System.nanoTime();
                try {
                    DensityMeasurement measurement = evaluator.evaluate(chunk.start(), Math.toIntExact(chunk.end() - chunk.start()));
                    if (!Double.isFinite(measurement.density())) {
                        throw new IllegalStateException("chunk returned a non-finite density");
                    }
                    long queuedMs = elapsedMillis(queuedAt);
                    ChunkResult completed = new ChunkResult(chunk.start(), chunk.end(), ChunkStatus.COMPLETE,
                            measurement.density(), queuedMs, Math.max(0, measurement.computeMs()),
                            Math.max(0, measurement.transportMs()), chunk.attempt() + 1, null);
                    job = replaceChunk(job, i, completed, JobStatus.RUNNING);
                    persist(job);
                } catch (RuntimeException e) {
                    ChunkResult failed = new ChunkResult(chunk.start(), chunk.end(), ChunkStatus.FAILED,
                            null, elapsedMillis(queuedAt), 0, 0, chunk.attempt() + 1, conciseFailure(e));
                    JobStatus status = completedChunks(job) == 0 ? JobStatus.UNAVAILABLE : JobStatus.PARTIAL;
                    job = replaceChunk(job, i, failed, status);
                    persist(job);
                    return summarize(job);
                }
            }
            job = withStatus(job, JobStatus.COMPLETE);
            persist(job);
            return summarize(job);
        }
    }

    private ExperimentJob loadOrCreate(String jobId, String metric, long start, long end) {
        Path file = journalFile(jobId);
        if (Files.exists(file)) {
            try {
                ExperimentJob existing = mapper.readValue(file.toFile(), ExperimentJob.class);
                if (FORMAT_VERSION.equals(existing.formatVersion()) && existing.jobId().equals(jobId)
                        && existing.metric().equals(metric) && existing.logicalStart() == start
                        && existing.logicalEnd() == end && existing.chunkSize() == chunkSize) {
                    return existing;
                }
            } catch (IOException ignored) {
                // A damaged journal must not be trusted as evidence; replace it with a fresh plan.
            }
        }
        List<ChunkResult> chunks = new ArrayList<>();
        for (long cursor = start; cursor < end; cursor += Math.min((long) chunkSize, end - cursor)) {
            long chunkEnd = Math.min(end, cursor + chunkSize);
            chunks.add(new ChunkResult(cursor, chunkEnd, ChunkStatus.PENDING, null, 0, 0, 0, 0, null));
        }
        ExperimentJob created = new ExperimentJob(FORMAT_VERSION, jobId, metric, start, end, chunkSize,
                JobStatus.PENDING, chunks);
        persist(created);
        return created;
    }

    private ExperimentJob replaceChunk(ExperimentJob job, int index, ChunkResult replacement, JobStatus status) {
        List<ChunkResult> chunks = new ArrayList<>(job.chunks());
        chunks.set(index, replacement);
        return new ExperimentJob(job.formatVersion(), job.jobId(), job.metric(), job.logicalStart(), job.logicalEnd(),
                job.chunkSize(), status, List.copyOf(chunks));
    }

    private ExperimentJob withStatus(ExperimentJob job, JobStatus status) {
        return new ExperimentJob(job.formatVersion(), job.jobId(), job.metric(), job.logicalStart(), job.logicalEnd(),
                job.chunkSize(), status, job.chunks());
    }

    private DensityJobResult summarize(ExperimentJob job) {
        long completedLength = 0;
        double weightedTotal = 0;
        long queuedMs = 0;
        long computeMs = 0;
        long transportMs = 0;
        for (ChunkResult chunk : job.chunks()) {
            queuedMs += chunk.queuedMs();
            computeMs += chunk.computeMs();
            transportMs += chunk.transportMs();
            if (chunk.status() == ChunkStatus.COMPLETE && chunk.density() != null) {
                long length = chunk.end() - chunk.start();
                completedLength += length;
                weightedTotal += chunk.density() * length;
            }
        }
        Double density = job.status() == JobStatus.COMPLETE && completedLength == job.logicalEnd() - job.logicalStart()
                ? weightedTotal / completedLength : null;
        return new DensityJobResult(job, density, completedLength, queuedMs, computeMs, transportMs);
    }

    private int completedChunks(ExperimentJob job) {
        return (int) job.chunks().stream().filter(chunk -> chunk.status() == ChunkStatus.COMPLETE).count();
    }

    private void persist(ExperimentJob job) {
        try {
            Files.createDirectories(journalRoot);
            Path temp = Files.createTempFile(journalRoot, job.jobId(), ".tmp");
            mapper.writeValue(temp.toFile(), job);
            try {
                Files.move(temp, journalFile(job.jobId()), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, journalFile(job.jobId()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist range-chunk experiment journal under " + journalRoot, e);
        }
    }

    private Path journalFile(String jobId) {
        return journalRoot.resolve(jobId + ".json");
    }

    private static String conciseFailure(RuntimeException error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String jobId(String metric, long start, long end, int chunkSize) {
        String source = FORMAT_VERSION + "|" + metric + "|" + start + "|" + end + "|" + chunkSize;
        try {
            return "exp-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    public interface DensityChunkEvaluator {
        DensityMeasurement evaluate(long start, int size);
    }

    public record DensityMeasurement(double density, long computeMs, long transportMs) {}

    public enum JobStatus { PENDING, RUNNING, PARTIAL, COMPLETE, UNAVAILABLE }
    public enum ChunkStatus { PENDING, COMPLETE, FAILED }

    public record ChunkResult(long start, long end, ChunkStatus status, Double density,
                              long queuedMs, long computeMs, long transportMs,
                              int attempt, String failure) {}

    public record ExperimentJob(String formatVersion, String jobId, String metric,
                                long logicalStart, long logicalEnd, int chunkSize,
                                JobStatus status, List<ChunkResult> chunks) {}

    public record DensityJobResult(ExperimentJob job, Double density, long completedLength,
                                   long queuedMs, long computeMs, long transportMs) {
        public boolean complete() {
            return job.status() == JobStatus.COMPLETE && density != null;
        }
    }
}
