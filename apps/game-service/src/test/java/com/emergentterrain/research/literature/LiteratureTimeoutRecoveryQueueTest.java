package com.emergentterrain.research.literature;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PaperKind;
import com.emergentterrain.research.ResultClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LiteratureTimeoutRecoveryQueueTest {

    @TempDir
    Path stateDir;

    @Test
    void recognizesOnlyPhase35Timeouts() {
        List<ToolInvocation> timeout = List.of(new ToolInvocation("search_literature",
                "{\"claim\":\"Dirichlet divisor estimate\",\"subject_anchors\":[\"dirichlet\",\"divisor\"],"
                        + "\"claimed_relation\":\"bounds\",\"relevance_rationale\":\"context\"}",
                "search_literature: Phase 34 SQLite retrieval exceeded its 15000ms Phase 35 budget; no evidence was issued.",
                1));
        List<ToolInvocation> noMatch = List.of(new ToolInvocation("search_literature",
                "{\"claim\":\"Dirichlet divisor estimate\"}",
                "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.", 1));

        assertThat(LiteratureTimeoutRecoveryQueue.containsTimeout(timeout)).isTrue();
        assertThat(LiteratureTimeoutRecoveryQueue.containsTimeout(noMatch)).isFalse();
    }

    @Test
    void journalsTimeoutAndRestoresItAfterRestart() throws Exception {
        LiteratureTimeoutRecoveryQueue queue = queue();
        assertThat(queue.deferIfTimedOut(draft(), timedOutNarration())).isTrue();
        assertThat(queue.queuedJobs()).isEqualTo(1);
        assertThat(queue.job("P-timeout").state())
                .isEqualTo(LiteratureTimeoutRecoveryQueue.State.AWAITING_DISCOVERY);

        assertThat(Files.list(stateDir).filter(path -> path.getFileName().toString().endsWith(".json")))
                .hasSize(1);

        LiteratureTimeoutRecoveryQueue restored = queue();
        assertThat(restored.queuedJobs()).isEqualTo(1);
        assertThat(restored.job("P-timeout").claim()).isEqualTo("Dirichlet divisor estimate");
        assertThat(restored.job("P-timeout").subjectAnchors()).containsExactly("dirichlet", "divisor");
    }

    @Test
    void laterSelectedSearchOutcomeClearsAnEarlierTimeout() {
        LiteratureTimeoutRecoveryQueue queue = queue();
        NarrationResult resolved = new NarrationResult("h", "f", "c", null, "model", 0.5, List.of(), "path")
                .withToolCallTrace(List.of(
                        new ToolInvocation("search_literature",
                                "{\"claim\":\"Dirichlet divisor estimate\",\"subject_anchors\":[\"dirichlet\",\"divisor\"]}",
                                "search_literature: Phase 34 SQLite retrieval exceeded its 15000ms Phase 35 budget; no evidence was issued.", 1),
                        new ToolInvocation("search_literature",
                                "{\"claim\":\"Dirichlet divisor estimate\",\"subject_anchors\":[\"dirichlet\",\"divisor\"]}",
                                "search_literature (Phase 34 SQLite): EVIDENCE — cite only the returned [HF123] keys.", 2)));

        assertThat(queue.deferIfTimedOut(draft(), resolved)).isFalse();
        assertThat(queue.queuedJobs()).isZero();
    }

    @Test
    void openAlexIsAdvisoryAndAnIndexRefreshStillReleasesTheHeldDraft() throws Exception {
        Path indexDir = Files.createDirectory(stateDir.resolve("index"));
        Path database = indexDir.resolve("literature.db");
        Files.writeString(database, "initial-index-generation");
        LiteratureTimeoutRecoveryQueue queue = queue(new Phase34SqliteLiterature(indexDir));
        assertThat(queue.deferIfTimedOut(draft("P-local-index"), timedOutNarration())).isTrue();

        queue.tick();
        assertThat(queue.job("P-local-index").state())
                .isEqualTo(LiteratureTimeoutRecoveryQueue.State.AWAITING_INDEX_BACKFILL);

        Files.writeString(database, "refreshed-index-generation");
        List<String> dispatched = new ArrayList<>();
        queue.wireRetryDispatch(draft -> dispatched.add(draft.paperId()));
        queue.tick();

        assertThat(dispatched).containsExactly("P-local-index");
        assertThat(queue.job("P-local-index").state())
                .isEqualTo(LiteratureTimeoutRecoveryQueue.State.RETRY_DISPATCHED);
    }

    @Test
    void serializedRecoveryDoesNotReleaseAnotherDraftWhileOneRetryIsOutstanding() throws Exception {
        Path indexDir = Files.createDirectory(stateDir.resolve("index"));
        Path database = indexDir.resolve("literature.db");
        Files.writeString(database, "initial-index-generation");
        LiteratureTimeoutRecoveryQueue queue = queue(new Phase34SqliteLiterature(indexDir));
        assertThat(queue.deferIfTimedOut(draft("P-first"), timedOutNarration())).isTrue();
        assertThat(queue.deferIfTimedOut(draft("P-second"), timedOutNarration())).isTrue();

        queue.tick();
        queue.tick();
        assertThat(queue.job("P-first").state()).isEqualTo(LiteratureTimeoutRecoveryQueue.State.AWAITING_INDEX_BACKFILL);
        assertThat(queue.job("P-second").state()).isEqualTo(LiteratureTimeoutRecoveryQueue.State.AWAITING_INDEX_BACKFILL);

        Files.writeString(database, "refreshed-index-generation");
        List<String> dispatched = new ArrayList<>();
        queue.wireRetryDispatch(draft -> dispatched.add(draft.paperId()));
        queue.tick();
        queue.tick();

        assertThat(dispatched).containsExactly("P-first");
        assertThat(queue.job("P-first").state()).isEqualTo(LiteratureTimeoutRecoveryQueue.State.RETRY_DISPATCHED);
        assertThat(queue.job("P-second").state()).isEqualTo(LiteratureTimeoutRecoveryQueue.State.AWAITING_INDEX_BACKFILL);
    }

    private LiteratureTimeoutRecoveryQueue queue() {
        return queue(null);
    }

    private LiteratureTimeoutRecoveryQueue queue(Phase34SqliteLiterature localIndex) {
        LiteratureTimeoutRecoveryQueue queue = new LiteratureTimeoutRecoveryQueue(
                new ObjectMapper(), true, stateDir.toString(), 3, 10, null, localIndex);
        queue.load();
        return queue;
    }

    private static NarrationResult timedOutNarration() {
        return new NarrationResult("h", "f", "c", null, "model", 0.5, List.of(), "path")
                .withToolCallTrace(List.of(new ToolInvocation("search_literature",
                        "{\"claim\":\"Dirichlet divisor estimate\",\"subject_anchors\":[\"dirichlet\",\"divisor\"],"
                                + "\"claimed_relation\":\"bounds\",\"relevance_rationale\":\"context\"}",
                        "search_literature: Phase 34 SQLite retrieval exceeded its 15000ms Phase 35 budget; "
                                + "no evidence was issued.", 1)));
    }

    private static PaperDraft draft() {
        return draft("P-timeout");
    }

    private static PaperDraft draft(String paperId) {
        return new PaperDraft(UUID.randomUUID(), "bot", "MATHEMATICS", "topic", "title",
                "findings", "hypothesis", "conclusion", "method", List.of(),
                0.0, 0.0, 0.0, 0L, null, 0, paperId, null, null, false,
                null, null, List.of(), "research hypothesis", null,
                ResultClassification.SUPPORTED, null, PaperKind.DOMAIN, null);
    }
}
