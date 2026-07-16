package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in acceptance checks against the operator's completed Phase 34 index.
 *
 * <p>Run with {@code PHASE34_REAL_INDEX_DIR=~/.emergentterrain/indexes/math-literature}.
 * These fixtures deliberately name known source papers and expected passage terms instead of
 * asking an LLM to judge relevance. Add curated entries only after inspecting both source layers.
 */
@EnabledIfEnvironmentVariable(named = "PHASE34_REAL_INDEX_DIR", matches = ".+")
class Phase34RealIndexAcceptanceTest {

    private static final Pattern KEY = Pattern.compile("\\[(HF[0-9A-F]+)]");

    // Curated against the completed local corpus. Keep this list fixed during an acceptance run.
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("multiple zeta values", "0802.4331v1", "non-negative integers"),
            new Fixture("graph isomorphism", "2203.03476v1", "oriented"),
            new Fixture("partition Young", "2111.12031v1", "partition"),
            new Fixture("Cohen Macaulay", "1712.03663v2", "tensor products"),
            new Fixture("reproducing kernel", "2003.10015v2", "cyclic")
    );

    @Test
    void fixedClaimsReturnExpectedFullTextBackedSourcesAndPassages() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")));
        assertThat(adapter.available()).isTrue();

        for (Fixture fixture : FIXTURES) {
            String search = adapter.search(fixture.claim());
            assertThat(search).contains("(source: " + fixture.expectedPaperId() + ")");
            String key = keyForExpectedSource(search, fixture.expectedPaperId());

            String read = adapter.read(key, fixture.passageQuery());
            assertThat(read).startsWith("read_reference " + key)
                    .containsIgnoringCase(fixture.expectedPassageTerm());
        }
    }

    @Test
    void fabricatedKeyIsRejectedByTheRealAdapter() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")));

        assertThat(adapter.read("HFDEADBEEF", "multiple zeta values"))
                .contains("unknown Phase 34 key");
    }

    private record Fixture(String claim, String expectedPaperId, String expectedPassageTerm) {
        String passageQuery() {
            return expectedPassageTerm;
        }
    }

    private static String keyForExpectedSource(String search, String expectedPaperId) {
        return search.lines()
                .filter(line -> line.contains("(source: " + expectedPaperId + ")"))
                .map(KEY::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No issued key for " + expectedPaperId));
    }
}
