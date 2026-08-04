package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-paper LLM request/response trace. Each provider pool calls
 * {@link #record} at its HTTP-send site; the writer appends one JSON line
 * per call to {@code research/llm-traces/{paperId}.jsonl}. PaperId is
 * read from {@link LlmCallContext#currentPaperId()} (set by the narration
 * entry point); calls with no paper in scope land in {@code _no-paper.jsonl}.
 *
 * <p>On startup, any pre-existing {@code *.jsonl} in the trace dir is
 * moved to {@code archive/{yyyyMMdd-HHmmss}/} so the current run starts
 * with a clean slate. The archive scheme mirrors {@code UniversityService.archivePreviousRunDirs}.
 *
 * <p>Disable with {@code app.llm.trace.enabled=false}.
 */
@Component
public class LlmTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(LlmTraceWriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path    traceDir;
    private final boolean enabled;
    private final ConcurrentMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    public LlmTraceWriter(
            @Value("${app.university.researchOutputDir:research}") String researchOutputDir,
            @Value("${app.llm.trace.enabled:true}") boolean enabled) {
        this.traceDir = Paths.get(researchOutputDir, "llm-traces");
        this.enabled  = enabled;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("LlmTraceWriter: disabled (app.llm.trace.enabled=false)");
            return;
        }
        try {
            Files.createDirectories(traceDir);
            Path archiveDir = traceDir.resolve("archive");
            Files.createDirectories(archiveDir);
            archivePreviousRunFiles(archiveDir);
            log.info("LlmTraceWriter: enabled, traceDir={}", traceDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("LlmTraceWriter: failed to initialise {}: {}", traceDir, e.getMessage());
        }
    }

    private void archivePreviousRunFiles(Path archiveDir) throws IOException {
        List<Path> files;
        try (var entries = Files.list(traceDir)) {
            files = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        }
        if (files.isEmpty()) return;
        String stamp = STAMP.format(Instant.now());
        Path stampedDir = archiveDir.resolve(stamp);
        int n = 2;
        while (Files.exists(stampedDir)) {
            stampedDir = archiveDir.resolve(stamp + "-" + n++);
        }
        Files.createDirectories(stampedDir);
        for (Path src : files) {
            Path dest = stampedDir.resolve(src.getFileName());
            try {
                Files.move(src, dest);
            } catch (IOException e) {
                log.warn("LlmTraceWriter: could not archive {} -> {}: {}",
                        src, dest, e.getMessage());
            }
        }
        log.info("LlmTraceWriter: archived {} previous trace file(s) -> {}",
                files.size(), stampedDir);
    }

    /**
     * Append one JSONL line capturing a single LLM HTTP call. Request and
     * response are stored as strings (the raw provider payload/body) so
     * non-JSON error responses are preserved verbatim; downstream readers
     * can {@code jq -r '.response | fromjson | …'} when needed.
     */
    public void record(String paperId, String provider, String model, String mode,
                       int status, long latencyMs, String request, String response) {
        if (!enabled) return;
        String fileKey = (paperId == null || paperId.isBlank())
                ? "_no-paper"
                : sanitize(paperId);
        Path file = traceDir.resolve(fileKey + ".jsonl");
        try {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("ts", Instant.now().toString());
            entry.put("paperId", paperId == null ? "" : paperId);
            entry.put("provider", provider);
            entry.put("model", model);
            entry.put("mode", mode);
            entry.put("status", status);
            entry.put("latencyMs", latencyMs);
            entry.put("request", request == null ? "" : request);
            entry.put("response", response == null ? "" : response);
            String line = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Object lock = fileLocks.computeIfAbsent(fileKey, k -> new Object());
            synchronized (lock) {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("LlmTraceWriter: failed to write trace paperId={} provider={} model={}: {}",
                    paperId, provider, model, e.getMessage());
        }
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
