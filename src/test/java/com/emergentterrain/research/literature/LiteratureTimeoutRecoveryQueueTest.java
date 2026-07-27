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

    private LiteratureTimeoutRecoveryQueue queue() {
        LiteratureTimeoutRecoveryQueue queue = new LiteratureTimeoutRecoveryQueue(
                new ObjectMapper(), true, stateDir.toString(), 3, 10, null, null);
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
        return new PaperDraft(UUID.randomUUID(), "bot", "MATHEMATICS", "topic", "title",
                "findings", "hypothesis", "conclusion", "method", List.of(),
                0.0, 0.0, 0.0, 0L, null, 0, "P-timeout", null, null, false,
                null, null, List.of(), "research hypothesis", null,
                ResultClassification.SUPPORTED, null, PaperKind.DOMAIN, null);
    }
}
