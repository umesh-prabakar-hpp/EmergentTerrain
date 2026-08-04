package com.emergentterrain.research.provenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 31 §31.6 acceptance: "the reconciliation check reports zero orphans
 * on a clean run and correctly flags a hand-planted orphan."
 */
class ProvenanceReconcilerTest {

    @TempDir
    Path root;

    private Path room(String name) throws IOException {
        Path r = root.resolve(name);
        Files.createDirectories(r);
        return r;
    }

    private Path traces() throws IOException {
        Path t = root.resolve("llm-traces");
        Files.createDirectories(t);
        return t;
    }

    @Test
    void cleanRun_zeroOrphans() throws IOException {
        Path room = room("b72c4e10");
        Files.writeString(room.resolve(
                "2026-06-06_mathematics_topic_P-b72c-227.md"), "x");
        Files.writeString(room.resolve(
                "2026-06-06_mathematics_topic_P-b72c-227.manifest.json"), "{}");  // not .md — ignored
        Path traces = traces();
        Files.writeString(traces.resolve("P-b72c-227.jsonl"), "{}");

        ProvenanceReconciler.Result r = ProvenanceReconciler.reconcile(
                root, traces, Set.of("P-b72c-227"));

        assertThat(r.mdWithoutDb()).isEmpty();
        assertThat(r.traceWithoutDb()).isEmpty();
        assertThat(r.dbWithoutMd()).isEmpty();
        assertThat(r.summaryLine()).contains(
                "md=1 db=1 traces=1 orphans{md_without_db=0 trace_without_db=0 db_without_md=0}");
    }

    @Test
    void plantedMarkdownOrphan_flagged() throws IOException {
        Path room = room("b72c4e10");
        Files.writeString(room.resolve(
                "2026-06-06_mathematics_topic_P-b72c-999.md"), "planted");

        ProvenanceReconciler.Result r = ProvenanceReconciler.reconcile(
                root, traces(), Set.of("P-b72c-227"));

        assertThat(r.mdWithoutDb()).containsExactly("P-b72c-999");
        // The planted id also makes the DB row md-less — expected-class count.
        assertThat(r.dbWithoutMd()).containsExactly("P-b72c-227");
    }

    @Test
    void suppressedDraftTraces_countedNotWrong() throws IOException {
        // D3-suppressed drafts leave traces with no DB row — the expected class.
        Path traces = traces();
        Files.writeString(traces.resolve("P-b72c-233.jsonl"), "{}");
        Files.writeString(traces.resolve("P-b72c-234.jsonl"), "{}");

        ProvenanceReconciler.Result r = ProvenanceReconciler.reconcile(
                root, traces, Set.of());
        assertThat(r.traceWithoutDb()).hasSize(2);
        assertThat(r.mdWithoutDb()).isEmpty();
    }

    @Test
    void archiveDirAndNonRoomDirsAreSkipped() throws IOException {
        Path archive = root.resolve("archive").resolve("b72c4e10-20260606");
        Files.createDirectories(archive);
        Files.writeString(archive.resolve(
                "2026-06-05_mathematics_topic_P-b72c-100.md"), "archived");
        Path notRoom = root.resolve("notable-papers");
        Files.createDirectories(notRoom);
        Files.writeString(notRoom.resolve("P-b72c-101.md"), "loose");

        ProvenanceReconciler.Result r = ProvenanceReconciler.reconcile(
                root, traces(), Set.of());
        assertThat(r.markdownIds()).isEmpty();   // neither dir matches ^[0-9a-f]{8}$
    }

    @Test
    void missingDirsDegradeToEmpty() {
        ProvenanceReconciler.Result r = ProvenanceReconciler.reconcile(
                root.resolve("nope"), root.resolve("also-nope"), Set.of("P-x-1"));
        assertThat(r.markdownIds()).isEmpty();
        assertThat(r.traceIds()).isEmpty();
        assertThat(r.dbWithoutMd()).containsExactly("P-x-1");
    }
}
