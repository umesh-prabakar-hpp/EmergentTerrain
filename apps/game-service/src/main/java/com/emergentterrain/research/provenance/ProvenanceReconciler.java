package com.emergentterrain.research.provenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 31 §31.6 (gap #21) — startup reconciliation across ET's three
 * provenance stores, which share only an implicit paper-ID convention:
 *
 * <ol>
 *   <li>markdown — {@code research/<room8>/<date>_<domain>_<topic>_<paperId>.md}</li>
 *   <li>DB — {@code ResearchPaper.paperId} rows</li>
 *   <li>LLM traces — {@code research/llm-traces/<paperId>.jsonl}</li>
 * </ol>
 *
 * <p>Read-only walker; the caller supplies the DB id set and logs the result.
 * Orphan classes are NOT symmetric in severity:
 *
 * <ul>
 *   <li><b>md-without-DB</b> — genuinely wrong (a published artifact whose row
 *       vanished, or a row that failed to persist while the file wrote): the
 *       per-id WARN class.</li>
 *   <li><b>trace-without-DB</b> — expected in bulk: every D3-suppressed draft
 *       leaves a trace and no paper (paper-ID gaps are by design). Count only.</li>
 *   <li><b>DB-without-md</b> — expected under {@code researchOutputRetention=
 *       archive} (prior runs' markdown moved to {@code archive/}). Count only;
 *       under {@code retain} a nonzero count is worth a look.</li>
 * </ul>
 */
public final class ProvenanceReconciler {

    private ProvenanceReconciler() {}

    /** 8-hex room-dir name (the live-room convention; archive/ is skipped). */
    private static final Pattern ROOM_DIR = Pattern.compile("^[0-9a-f]{8}$");

    /** paperId inside a markdown filename or trace filename. */
    private static final Pattern PAPER_ID = Pattern.compile("(P-[0-9a-f]{4}-[0-9]+)");

    public record Result(
            Set<String> markdownIds,
            Set<String> traceIds,
            int dbCount,
            List<String> mdWithoutDb,
            List<String> traceWithoutDb,
            List<String> dbWithoutMd) {

        public String summaryLine() {
            return String.format(
                    "provenance-reconcile: md=%d db=%d traces=%d "
                            + "orphans{md_without_db=%d trace_without_db=%d db_without_md=%d}",
                    markdownIds.size(), dbCount, traceIds.size(),
                    mdWithoutDb.size(), traceWithoutDb.size(), dbWithoutMd.size());
        }
    }

    /**
     * Walk the live room dirs and the traces dir, reconcile against the DB id
     * set. IO errors on either walk degrade to empty sets (the caller's
     * summary still prints; a broken walk is visible as md=0/traces=0).
     */
    public static Result reconcile(Path researchRoot, Path tracesDir, Set<String> dbIds) {
        Set<String> mdIds = scanMarkdown(researchRoot);
        Set<String> traceIds = scanTraces(tracesDir);

        List<String> mdWithoutDb = new ArrayList<>();
        for (String id : mdIds) {
            if (!dbIds.contains(id)) mdWithoutDb.add(id);
        }
        List<String> traceWithoutDb = new ArrayList<>();
        for (String id : traceIds) {
            if (!dbIds.contains(id)) traceWithoutDb.add(id);
        }
        List<String> dbWithoutMd = new ArrayList<>();
        for (String id : dbIds) {
            if (!mdIds.contains(id)) dbWithoutMd.add(id);
        }
        return new Result(mdIds, traceIds, dbIds.size(),
                List.copyOf(mdWithoutDb), List.copyOf(traceWithoutDb),
                List.copyOf(dbWithoutMd));
    }

    private static Set<String> scanMarkdown(Path researchRoot) {
        Set<String> ids = new TreeSet<>();
        if (researchRoot == null || !Files.isDirectory(researchRoot)) return ids;
        try (var rooms = Files.list(researchRoot)) {
            rooms.filter(Files::isDirectory)
                    .filter(d -> ROOM_DIR.matcher(d.getFileName().toString()).matches())
                    .forEach(room -> {
                        try (var files = Files.list(room)) {
                            files.map(f -> f.getFileName().toString())
                                    .filter(n -> n.endsWith(".md"))
                                    .forEach(n -> {
                                        Matcher m = PAPER_ID.matcher(n);
                                        if (m.find()) ids.add(m.group(1));
                                    });
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return ids;
    }

    private static Set<String> scanTraces(Path tracesDir) {
        Set<String> ids = new TreeSet<>();
        if (tracesDir == null || !Files.isDirectory(tracesDir)) return ids;
        try (var files = Files.list(tracesDir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .forEach(n -> {
                        Matcher m = PAPER_ID.matcher(n);
                        if (m.find()) ids.add(m.group(1));
                    });
        } catch (IOException ignored) {
        }
        return ids;
    }
}
