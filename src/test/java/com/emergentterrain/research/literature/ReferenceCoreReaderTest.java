package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 L2 — reader tiers, chunk retrieval, caveats, failure-softness. */
class ReferenceCoreReaderTest {

    @TempDir
    Path corpus;

    private ReferenceCoreReader readerWith(String tier, String id, String text) throws Exception {
        Files.createDirectories(corpus.resolve(tier));
        Files.writeString(corpus.resolve(tier).resolve(id + ".txt"), text);
        return new ReferenceCoreReader(corpus.toString());
    }

    @Test
    void htmlTierPreferred_andQuotable() throws Exception {
        ReferenceCoreReader r = readerWith("cores-html", "W1",
                "Intro paragraph about primes.\n\nThe divisor exponent satisfies "
                        + "$\\theta\\leq 131/416$ as proved via exponential sums.\n\nUnrelated coda.");
        Files.createDirectories(corpus.resolve("cores"));
        Files.writeString(corpus.resolve("cores").resolve("W1.txt"), "pdf junk version");

        String out = r.read("W1", "divisor exponent 131/416");
        assertThat(out).contains("[source: ar5iv HTML")
                .contains("131/416")
                .doesNotContain("pdf junk")
                .doesNotContain("PARAPHRASE");
        assertThat(r.available()).isTrue();
    }

    @Test
    void pdfTierCarriesParaphraseCaveat() throws Exception {
        ReferenceCoreReader r = readerWith("cores", "W2",
                "The error term satisfies theta <= 131/416 by Huxley's method.\n\nMore prose here.");
        assertThat(r.read("W2", "error term exponent"))
                .contains("[source: PDF extraction")
                .contains("PARAPHRASE")
                .contains("Huxley");
    }

    @Test
    void fulltextTier_middlePreference_paraphraseSafe() throws Exception {
        // fulltext beats PDF cores but loses to ar5iv HTML; carries the paraphrase caveat.
        ReferenceCoreReader r = readerWith("fulltext", "W5",
                "The prime gap grows like log squared p per Cramer.\n\nMore body text here.");
        Files.createDirectories(corpus.resolve("cores"));
        Files.writeString(corpus.resolve("cores").resolve("W5.txt"), "pdf junk version");
        assertThat(r.read("W5", "prime gap Cramer"))
                .contains("[source: bulk full text").contains("PARAPHRASE")
                .contains("Cramer").doesNotContain("pdf junk");
        assertThat(r.hasCore("W5")).isTrue();
        assertThat(r.available()).isTrue();

        // ar5iv HTML still wins when both present
        Files.createDirectories(corpus.resolve("cores-html"));
        Files.writeString(corpus.resolve("cores-html").resolve("W5.txt"),
                "ar5iv body with $\\log^2 p$ formula.\n\ntail");
        assertThat(r.read("W5", "prime gap")).contains("[source: ar5iv HTML");
    }

    @Test
    void failureSoft_unknownKeyAndBadKey() throws Exception {
        ReferenceCoreReader r = readerWith("cores", "W3", "text long enough to exist here");
        assertThat(r.read("W999999", "anything")).contains("no full-text core");
        assertThat(r.read("../etc/passwd", "x")).contains("invalid key");
        assertThat(r.read(null, "x")).contains("invalid key");
    }

    @Test
    void topChunks_prefersQueryCoverage_andRespectsBudget() {
        List<String> chunks = List.of(
                "alpha beta gamma filler ".repeat(20),
                "the twin prime constant two C two appears with the singular series",
                "twin twin twin twin twin");   // repetition should NOT beat coverage
        List<String> top = ReferenceCoreReader.topChunks(chunks,
                "twin prime constant singular series");
        assertThat(top.get(0)).contains("singular series");
        String huge = "x".repeat(ReferenceCoreReader.MAX_CHUNK_CHARS * 4);
        assertThat(ReferenceCoreReader.chunk(huge)).allMatch(
                c -> c.length() <= ReferenceCoreReader.MAX_CHUNK_CHARS);
    }

    @org.junit.jupiter.api.Test
    void hasCore_perKeyLookup_run38() throws Exception {
        ReferenceCoreReader r = readerWith("cores-html", "W2950091262", "some long enough core text here for the test");
        org.assertj.core.api.Assertions.assertThat(r.hasCore("W2950091262")).isTrue();
        org.assertj.core.api.Assertions.assertThat(r.hasCore("W999")).isFalse();
        org.assertj.core.api.Assertions.assertThat(r.hasCore("../etc/passwd")).isFalse();
        org.assertj.core.api.Assertions.assertThat(r.hasCore(null)).isFalse();
    }
}
