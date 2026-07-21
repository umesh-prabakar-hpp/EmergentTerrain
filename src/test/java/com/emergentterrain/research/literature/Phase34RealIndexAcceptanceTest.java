package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
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
    // Exact Phase 34 tool claims from P-b72c-77627868/-69, whose broad OR fallback held both
    // narration slots for about twelve minutes on 2026-07-17.
    private static final List<String> FORMERLY_SLOW_CLAIMS = List.of(
            "The maximal divisor count up to x follows the Wigert growth law log(max d(n)) log log x / log x tending to log 2, with finite-scale corrections caused by optimizing decreasing prime exponents and integrality constraints.",
            "The finite-scale coordinate log(max_divisor_count) times log log x divided by log x decreases toward log 2, while a tight affine multiple of exp(log x/log log x) bounds window maxima near x=700 million.",
            "Finite-scale maximal prime gaps normalized by (log x)^2 show a slowly decreasing trajectory toward an asymptotic growth constant, but such a finite ladder may not discriminate Cramér-type convergence from plausible alternative asymptotic laws.",
            "Finite-scale normalized maximal prime gaps decrease across a geometric scale ladder toward a log-squared growth constant near 0.6932, but the observed trajectory may not discriminate between alternative slowly converging asymptotic laws."
    );
    // Exact candidate-word sequences captured in the 2026-07-20 Phase 35 logs. They must fail
    // closed quickly rather than letting generic pairs such as "order one" consume the budget.
    private static final List<String> LOGGED_TERM_SEQUENCES = List.of(
            "dirichlet divisor error has order one oscillation envelope",
            "maximum prime gap consecutive high location tracks squared",
            "has wigert max divisor"
    );
    // P76/P77 emitted these unrelated astrophysics/physics sources merely because their bodies
    // shared generic density wording. A relevant result is still allowed; these known false positives
    // must never be issued as evidence again.
    private static final List<String> ABUNDANT_DENSITY_REVIEW_CLAIMS = List.of(
            "The abundant-number fraction in the exhaustive interval [350,500) is 0.26000, exceeding a 0.210 baseline as a local deviation rather than an asymptotic claim.",
            "The density of abundant integers in [120,420) is elevated relative to a local baseline and its finite-range deviation should be tracked across scales."
    );
    private static final List<String> KNOWN_IRRELEVANT_ABUNDANT_SOURCE_IDS = List.of(
            "hep-ph0505025", "0901.1065", "0912.1573", "1607.07094", "1508.06729");

    @Test
    void fixedClaimsReturnExpectedFullTextBackedSourcesAndPassages() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
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
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);

        assertThat(adapter.read("HFDEADBEEF", "multiple zeta values"))
                .contains("unknown Phase 34 key");
    }

    @Test
    void twoConcurrentSearchesFinishInsideTheConfiguredPhase35Budget() throws Exception {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> adapter.search("multiple zeta values"));
            Future<String> second = executor.submit(() -> adapter.search("graph isomorphism"));

            assertThat(first.get(20, TimeUnit.SECONDS)).contains("EVIDENCE");
            assertThat(second.get(20, TimeUnit.SECONDS)).contains("EVIDENCE");
        }
    }

    @Test
    void formerlySlowRunClaimsCompleteBoundedlyWithoutUngroundedFailures() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
        for (String claim : FORMERLY_SLOW_CLAIMS) {
            long started = System.nanoTime();
            String result = adapter.search(claim);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).isLessThanOrEqualTo(15_000);
            assertThat(result).doesNotContain("exceeded its")
                    .doesNotContain("not available");
            if (result.contains("EVIDENCE")) {
                assertThat(result).contains("[HF").contains("(source: ");
            } else {
                assertThat(result).contains("no theorem-index matches");
            }
        }
    }

    @Test
    void loggedTermSequencesDoNotReintroduceGenericPhraseTimeouts() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
        for (String claim : LOGGED_TERM_SEQUENCES) {
            long started = System.nanoTime();
            String result = adapter.search(claim);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).isLessThanOrEqualTo(15_000);
            assertThat(result).doesNotContain("exceeded its")
                    .doesNotContain("not available");
        }
    }

    @Test
    void abundantDensityReviewClaimsNeverIssueTheKnownUnrelatedSources() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
        for (String claim : ABUNDANT_DENSITY_REVIEW_CLAIMS) {
            String result = adapter.search(claim);
            for (String sourceId : KNOWN_IRRELEVANT_ABUNDANT_SOURCE_IDS) {
                assertThat(result).doesNotContain("(source: " + sourceId + ")");
            }
        }
    }

    @Test
    void dirichletErrorTermCanUseTheFullTextFallbackWhenNoTheoremRecordIsLinked() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);

        String search = adapter.search("The Dirichlet divisor error term");
        assertThat(search).contains("EVIDENCE").contains("(source: 1508.06394)");
        String key = keyForExpectedSource(search, "1508.06394");
        assertThat(adapter.read(key, "error term"))
                .startsWith("read_reference " + key)
                .containsIgnoringCase("error term");
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
