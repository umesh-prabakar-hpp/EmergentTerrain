package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 L2 supply side — key scanning for the EVIDENCE-driven backfill. */
class EvidenceCoreBackfillTest {

    @TempDir
    Path research;

    @Test
    void extractKeys_matchesWorkKeysOnly() {
        Set<String> keys = EvidenceCoreBackfill.extractKeys(
                "Result: `search_literature (grounded): EVIDENCE — [W2909857905] snippet; "
                        + "also [W4230159862]. window W123 is not a key; W12345 too short.`");
        assertThat(keys).containsExactly("W2909857905", "W4230159862");
    }

    @Test
    void scanKeys_onlyReadsFilesMentioningSearchLiterature() throws Exception {
        Files.writeString(research.resolve("paper.md"),
                "search_literature returned [W1111111111] for this claim");
        Files.writeString(research.resolve("other.md"),
                "no lit tool here, W2222222222 is just a stray token");
        Path traces = research.resolve("llm-traces");
        Files.createDirectories(traces);
        Files.writeString(traces.resolve("P-x.jsonl"),
                "{\"tool\":\"search_literature\",\"result\":\"EVIDENCE [W3333333333]\"}");

        Set<String> keys = EvidenceCoreBackfill.scanKeys(research);
        assertThat(keys).containsExactlyInAnyOrder("W1111111111", "W3333333333");
        assertThat(EvidenceCoreBackfill.scanKeys(research.resolve("nope"))).isEmpty();
    }

    @Test
    void scanRecoveryCandidates_usesOnlyDiscoveredBackfillJobs() throws Exception {
        Files.writeString(research.resolve("P-timeout.json"), """
                {"state":"AWAITING_INDEX_BACKFILL","candidates":[
                  {"workId":"W2909857905"},{"workId":"not-a-work"}]}
                """);
        Files.writeString(research.resolve("P-waiting.json"), """
                {"state":"AWAITING_DISCOVERY","candidates":[{"workId":"W4230159862"}]}
                """);

        assertThat(EvidenceCoreBackfill.scanRecoveryCandidates(research))
                .containsExactly("W2909857905");
    }
}
