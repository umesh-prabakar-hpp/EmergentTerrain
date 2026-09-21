package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in, read-only Phase 45 benchmark against an operator's immutable local index.
 *
 * <p>The JSON report contains fixture identifiers, timing, and safe outcomes only. The matching
 * Phase 35 INFO telemetry in the harness log supplies candidate, SQLite-batch, and routed-shard
 * detail without storing source passages, claims, or credentials in the report.
 */
@EnabledIfEnvironmentVariable(named = "PHASE34_REAL_INDEX_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PHASE45_BENCHMARK_ENABLED", matches = "true")
class Phase45RealIndexBenchmarkTest {

    private static final Pattern ISSUED_KEY = Pattern.compile("\\[?(HF[0-9A-F]+)\\]?", Pattern.CASE_INSENSITIVE);

    private static final List<Fixture> PRIMARY_FIXTURES = List.of(
            new Fixture("maximum-prime-gap-anchors", "maximum prime gap"),
            new Fixture("divisor-omega-anchors", "divisor omega richly divisible density"));
    private static final List<Fixture> MIXED_FIXTURES = List.of(
            PRIMARY_FIXTURES.get(0),
            PRIMARY_FIXTURES.get(1),
            new Fixture("phase35-multiple-zeta", "multiple zeta values"),
            new Fixture("phase35-dirichlet-error", "dirichlet divisor error term"));

    @Test
    void recordsRepresentativeSequentialConcurrentAndMixedRetrievalScenarios() throws Exception {
        Path indexDir = Path.of(System.getenv("PHASE34_REAL_INDEX_DIR"));
        assertThat(Files.isRegularFile(indexDir.resolve("literature.db"))).isTrue();
        String cacheState = System.getenv().getOrDefault("PHASE45_BENCHMARK_CACHE_STATE", "normal-cache");
        List<Observation> observations = new ArrayList<>();
        List<RoutedParityObservation> routedParity = new ArrayList<>();

        Phase34SqliteLiterature sequential = adapter(indexDir);
        for (Fixture fixture : PRIMARY_FIXTURES) {
            observations.add(run("alone", fixture, sequential));
        }

        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            observations.addAll(runConcurrent("twice-concurrent", PRIMARY_FIXTURES, indexDir, executor));
            observations.addAll(runConcurrent("mixed-phase35", MIXED_FIXTURES, indexDir, executor));
        }

        assertThat(observations).hasSize(PRIMARY_FIXTURES.size() * 2 + MIXED_FIXTURES.size());
        assertThat(observations).allSatisfy(observation -> {
            assertThat(observation.elapsedMs()).isGreaterThanOrEqualTo(0);
            assertThat(observation.outcome()).isIn("evidence", "no_match", "timeout", "unavailable");
        });
        configuredRoutedShards().ifPresent(shardsDir -> {
            for (Fixture fixture : PRIMARY_FIXTURES) routedParity.add(runRoutedParity(fixture, indexDir, shardsDir));
        });
        writeReport(indexDir, cacheState, observations, routedParity);
    }

    private static List<Observation> runConcurrent(String scenario, List<Fixture> fixtures, Path indexDir,
                                                    ExecutorService executor) throws Exception {
        List<Future<Observation>> futures = fixtures.stream()
                .map(fixture -> executor.submit(() -> run(scenario, fixture, adapter(indexDir))))
                .toList();
        List<Observation> observations = new ArrayList<>();
        for (Future<Observation> future : futures) observations.add(future.get(45, TimeUnit.SECONDS));
        return observations;
    }

    private static Phase34SqliteLiterature adapter(Path indexDir) {
        return new Phase34SqliteLiterature(indexDir, true, 15_000);
    }

    private static java.util.Optional<Path> configuredRoutedShards() {
        String configured = System.getenv("PHASE45_ROUTED_SHARDS_DIR");
        if (configured == null || configured.isBlank()) return java.util.Optional.empty();
        Path directory = Path.of(configured);
        return Files.isRegularFile(directory.resolve("catalog.json"))
                ? java.util.Optional.of(directory) : java.util.Optional.empty();
    }

    /**
     * Observes the default-off policy against the same immutable shard generation. A mismatch is
     * recorded rather than hidden or converted into a benchmark failure: operators need the JSON
     * and source-safe shard telemetry to decide whether an enabled comparison is admissible.
     */
    private static RoutedParityObservation runRoutedParity(Fixture fixture, Path indexDir, Path shardsDir) {
        RoutedRun disabled = runRouted(fixture, indexDir, shardsDir, false);
        RoutedRun enabled = runRouted(fixture, indexDir, shardsDir, true);
        boolean outcomeParity = disabled.outcome().equals(enabled.outcome());
        boolean issuedKeyParity = issuedKeys(disabled.output()).equals(issuedKeys(enabled.output()));
        boolean readEligibilityParity = readEligibility(disabled, fixture.claim())
                == readEligibility(enabled, fixture.claim());
        String parity = parityStatus(disabled, enabled, outcomeParity, issuedKeyParity, readEligibilityParity);
        return new RoutedParityObservation(fixture.id(), disabled.elapsedMs(), enabled.elapsedMs(),
                disabled.outcome(), enabled.outcome(), outcomeParity, issuedKeyParity,
                readEligibilityParity, disabled.earlyCancellationOpportunity(), enabled.earlyCancellationOpportunity(),
                enabled.earlyCancellationApplied(), parity);
    }

    private static RoutedRun runRouted(Fixture fixture, Path indexDir, Path shardsDir,
                                       boolean earlySuccessCancellationEnabled) {
        ShardedPhase34Literature shards = new ShardedPhase34Literature(shardsDir, true, 15_000, 64,
                new DeterministicLiteratureQueryPlanner(), 3, earlySuccessCancellationEnabled);
        Phase34SqliteLiterature routed = new Phase34SqliteLiterature(indexDir, true, 15_000, 64,
                new DeterministicLiteratureQueryPlanner(), null, shards, 2);
        long startedNanos = System.nanoTime();
        ShardedPhase34Literature.RoutedShardSearchObservation observation = shards.searchWithObservation(
                fixture.claim(), null, 15_000);
        String output = observation.output();
        return new RoutedRun(routed, output, (System.nanoTime() - startedNanos) / 1_000_000L, outcome(output),
                observation.earlyCancellationOpportunity(), observation.earlyCancellationApplied());
    }

    private static String parityStatus(RoutedRun disabled, RoutedRun enabled, boolean outcomeParity,
                                       boolean issuedKeyParity, boolean readEligibilityParity) {
        if (!disabled.earlyCancellationOpportunity()) return "not_observed";
        if (!enabled.earlyCancellationApplied()) return "inconclusive_not_applied";
        return outcomeParity && issuedKeyParity && readEligibilityParity
                ? "matched_after_cancellation" : "mismatched";
    }

    private static List<String> issuedKeys(String output) {
        List<String> keys = new ArrayList<>();
        Matcher matcher = ISSUED_KEY.matcher(output == null ? "" : output);
        while (matcher.find()) keys.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return List.copyOf(keys);
    }

    private static boolean readEligibility(RoutedRun run, String query) {
        return issuedKeys(run.output()).stream().allMatch(key -> run.adapter().read(key, query)
                .startsWith("read_reference " + key));
    }

    private static Observation run(String scenario, Fixture fixture, Phase34SqliteLiterature adapter) {
        long startedNanos = System.nanoTime();
        String output = adapter.search(fixture.claim());
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new Observation(scenario, fixture.id(), elapsedMs, outcome(output));
    }

    private static String outcome(String output) {
        if (output != null && output.contains("EVIDENCE")) return "evidence";
        if (LiteratureTimeoutRecoveryQueue.isPhase35TimeoutResult(output)) return "timeout";
        if (output != null && output.contains("not available")) return "unavailable";
        return "no_match";
    }

    private static void writeReport(Path indexDir, String cacheState, List<Observation> observations,
                                    List<RoutedParityObservation> routedParity) throws Exception {
        String configuredOutput = System.getenv("PHASE45_BENCHMARK_OUTPUT_DIR");
        Path output = configuredOutput == null || configuredOutput.isBlank()
                ? Files.createTempDirectory("phase45-literature-benchmark-")
                : Path.of(configuredOutput);
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 2);
        report.put("recorded_at", Instant.now().toString());
        report.put("cache_state", cacheState);
        report.put("index_generation", indexDir.getFileName().toString());
        report.put("observations", observations);
        report.put("routed_shard_parity", routedParity);
        Path reportPath = output.resolve("phase45-literature-retrieval-benchmark.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.println("Phase 45 literature benchmark report: " + reportPath);
    }

    private record Fixture(String id, String claim) {}

    record Observation(String scenario, String fixture, long elapsedMs, String outcome) {}

    private record RoutedRun(Phase34SqliteLiterature adapter, String output, long elapsedMs, String outcome,
                             boolean earlyCancellationOpportunity, boolean earlyCancellationApplied) {}

    record RoutedParityObservation(String fixture, long disabledElapsedMs, long enabledElapsedMs,
                                   String disabledOutcome, String enabledOutcome, boolean outcomeParity,
                                   boolean issuedKeyParity, boolean readEligibilityParity,
                                   boolean disabledEarlyCancellationOpportunity,
                                   boolean enabledEarlyCancellationOpportunity,
                                   boolean enabledEarlyCancellationApplied, String parity) {}
}
