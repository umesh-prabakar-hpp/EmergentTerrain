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
        writeReport(indexDir, cacheState, observations);
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

    private static void writeReport(Path indexDir, String cacheState, List<Observation> observations) throws Exception {
        String configuredOutput = System.getenv("PHASE45_BENCHMARK_OUTPUT_DIR");
        Path output = configuredOutput == null || configuredOutput.isBlank()
                ? Files.createTempDirectory("phase45-literature-benchmark-")
                : Path.of(configuredOutput);
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("recorded_at", Instant.now().toString());
        report.put("cache_state", cacheState);
        report.put("index_generation", indexDir.getFileName().toString());
        report.put("observations", observations);
        Path reportPath = output.resolve("phase45-literature-retrieval-benchmark.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.println("Phase 45 literature benchmark report: " + reportPath);
    }

    private record Fixture(String id, String claim) {}

    record Observation(String scenario, String fixture, long elapsedMs, String outcome) {}
}
