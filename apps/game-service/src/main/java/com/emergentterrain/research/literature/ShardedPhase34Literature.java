package com.emergentterrain.research.literature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded routed search across coarse, immutable primary-index shards.
 *
 * <p>This intentionally routes to a few likely subject shards, rather than fanning every query
 * out to every database. SQLite FTS ranks are corpus-local, so results are interleaved by
 * deterministic shard order and local ordinal; the existing per-shard evidence/title gates still
 * decide whether an {@code HF...} key exists at all.
 */
final class ShardedPhase34Literature {
    private static final Logger log = LoggerFactory.getLogger(ShardedPhase34Literature.class);
    private static final Pattern KEY_LINE = Pattern.compile("(?m)^- \\[?(HF[0-9A-F]+)\\]?.*$", Pattern.CASE_INSENSITIVE);
    private static final List<String> DEFAULT_ORDER = List.of(
            "number-theory", "analysis", "algebra", "combinatorics", "geometry", "probability", "cross-list");

    private final Map<String, Phase34SqliteLiterature> shards;
    private final int parallelism;
    private final long aggregateTimeoutMs;
    private final boolean earlySuccessCancellationEnabled;
    private final Map<String, Phase34SqliteLiterature> readerForKey = new java.util.concurrent.ConcurrentHashMap<>();

    ShardedPhase34Literature(Path shardsDir, boolean phase35, long timeoutMs, int initialCandidates,
                             LiteratureQueryPlanner planner, int parallelism) {
        this(shardsDir, phase35, timeoutMs, initialCandidates, planner, parallelism, false);
    }

    ShardedPhase34Literature(Path shardsDir, boolean phase35, long timeoutMs, int initialCandidates,
                             LiteratureQueryPlanner planner, int parallelism,
                             boolean earlySuccessCancellationEnabled) {
        this.parallelism = Math.clamp(parallelism, 1, 4);
        this.aggregateTimeoutMs = Math.max(1_000, timeoutMs);
        this.earlySuccessCancellationEnabled = earlySuccessCancellationEnabled;
        Map<String, Phase34SqliteLiterature> loaded = new LinkedHashMap<>();
        // The offline builder writes catalog.json only after every shard has committed. Refuse
        // partial directories so an app restart during an import remains on the complete monolith.
        if (!Files.isRegularFile(shardsDir.resolve("catalog.json"))) {
            this.shards = Map.of();
            return;
        }
        for (String name : DEFAULT_ORDER) {
            Path directory = shardsDir.resolve(name);
            if (!Files.isRegularFile(directory.resolve("literature.db"))) {
                this.shards = Map.of();
                return;
            }
            Phase34SqliteLiterature shard = new Phase34SqliteLiterature(directory, phase35, timeoutMs,
                    initialCandidates, planner, null);
            if (!shard.available()) {
                this.shards = Map.of();
                return;
            }
            loaded.put(name, shard);
        }
        this.shards = Map.copyOf(loaded);
    }

    boolean available() { return !shards.isEmpty(); }

    String fingerprint() {
        return shards.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue().indexFingerprint())
                .collect(java.util.stream.Collectors.joining("|", "sharded:", ""));
    }

    String search(String query, LiteratureQueryProposal proposal) {
        return search(query, proposal, aggregateTimeoutMs);
    }

    String search(String query, LiteratureQueryProposal proposal, long requestBudgetMs) {
        long startedNanos = System.nanoTime();
        long effectiveTimeoutMs = Math.max(250, Math.min(aggregateTimeoutMs, requestBudgetMs));
        List<Map.Entry<String, Phase34SqliteLiterature>> selected = select(query);
        if (selected.isEmpty()) return "search_literature: no routed primary literature shard is available.";
        long submittedNanos = System.nanoTime();
        List<ShardTask> tasks = selected.stream()
                .map(entry -> new ShardTask(entry.getKey(), entry.getValue(), submittedNanos)).toList();
        List<ShardResult> results = new ArrayList<>();
        Map<String, String> cancellationReasons = new LinkedHashMap<>();
        boolean deadlineExpired = collectShardResults(query, proposal, selected, tasks, results, cancellationReasons,
                effectiveTimeoutMs);
        boolean timedOut = deadlineExpired || hasUnresolvedCancellation(cancellationReasons) || results.stream().anyMatch(result ->
                LiteratureTimeoutRecoveryQueue.isPhase35TimeoutResult(result.output()));
        List<ShardResult> evidence = results.stream().filter(result -> result.output().contains("EVIDENCE"))
                .sorted(Comparator.comparingInt(result -> DEFAULT_ORDER.indexOf(result.name()))).toList();
        if (evidence.isEmpty()) {
            String output = timedOut
                    ? "search_literature: Phase 34 SQLite retrieval exceeded its " + effectiveTimeoutMs
                    + "ms Phase 35 budget; no evidence was issued."
                    : "search_literature (Phase 34 sharded primary): no theorem-index matches for the supplied query.";
            logSearchTelemetry(selected, results, cancellationReasons, timedOut ? "timeout" : "no_match", startedNanos,
                    effectiveTimeoutMs);
            return output;
        }
        LinkedHashMap<String, String> lines = new LinkedHashMap<>();
        for (ShardResult result : evidence) {
            Matcher matcher = KEY_LINE.matcher(result.output());
            while (matcher.find()) {
                String line = matcher.group();
                String key = matcher.group(1).toUpperCase(Locale.ROOT);
                lines.putIfAbsent(key, line);
                readerForKey.putIfAbsent(key, result.adapter());
            }
        }
        StringBuilder output = new StringBuilder("search_literature (Phase 34 routed sharded primary): "
                + "EVIDENCE — cite only the returned [HF…] keys.\n");
        lines.values().stream().limit(3).forEach(line -> output.append(line).append('\n'));
        logSearchTelemetry(selected, results, cancellationReasons, "evidence", startedNanos, effectiveTimeoutMs);
        return output.toString().stripTrailing();
    }

    private boolean collectShardResults(String query, LiteratureQueryProposal proposal,
                                        List<Map.Entry<String, Phase34SqliteLiterature>> selected,
                                        List<ShardTask> tasks, List<ShardResult> results,
                                        Map<String, String> cancellationReasons, long effectiveTimeoutMs) {
        var executor = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()));
        Map<Future<ShardResult>, String> namesByFuture = new LinkedHashMap<>();
        try {
            ExecutorCompletionService<ShardResult> completed = new ExecutorCompletionService<>(executor);
            for (ShardTask task : tasks) {
                namesByFuture.put(completed.submit((Callable<ShardResult>) () -> task.execute(query, proposal)), task.name());
            }
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMs);
            int received = 0;
            while (received < tasks.size()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    cancelIncomplete(namesByFuture, cancellationReasons, "deadline");
                    return true;
                }
                Future<ShardResult> future = completed.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (future == null) {
                    cancelIncomplete(namesByFuture, cancellationReasons, "deadline");
                    return true;
                }
                received++;
                try {
                    results.add(future.get());
                } catch (Exception ignored) {
                    // An interrupted or failed shard is unresolved; do not manufacture no-match.
                    cancellationReasons.putIfAbsent(namesByFuture.get(future), "failed");
                }
                if (earlySuccessCancellationEnabled && completedPrefixFillsAnswer(selected, results)) {
                    cancelIncomplete(namesByFuture, cancellationReasons, "early_sufficient_evidence");
                    return false;
                }
            }
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancelIncomplete(namesByFuture, cancellationReasons, "interrupted");
            return true;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean completedPrefixFillsAnswer(List<Map.Entry<String, Phase34SqliteLiterature>> selected,
                                                       List<ShardResult> results) {
        Map<String, String> outputs = results.stream().collect(java.util.stream.Collectors.toMap(
                ShardResult::name, ShardResult::output, (left, right) -> left, LinkedHashMap::new));
        return RoutedShardCancellationPolicy.completedPrefixFillsAnswer(
                selected.stream().map(Map.Entry::getKey).toList(), outputs);
    }

    private static void cancelIncomplete(Map<Future<ShardResult>, String> namesByFuture,
                                         Map<String, String> cancellationReasons, String reason) {
        for (Map.Entry<Future<ShardResult>, String> entry : namesByFuture.entrySet()) {
            if (!entry.getKey().isDone() && entry.getKey().cancel(true)) {
                cancellationReasons.putIfAbsent(entry.getValue(), reason);
            }
        }
    }

    private static boolean hasUnresolvedCancellation(Map<String, String> cancellationReasons) {
        return cancellationReasons.values().stream().anyMatch(reason -> !"early_sufficient_evidence".equals(reason));
    }

    private void logSearchTelemetry(List<Map.Entry<String, Phase34SqliteLiterature>> selected,
                                    List<ShardResult> results, Map<String, String> cancellationReasons,
                                    String outcome, long startedNanos, long effectiveTimeoutMs) {
        Map<String, ShardResult> resultByName = results.stream().collect(java.util.stream.Collectors.toMap(
                ShardResult::name, result -> result, (left, right) -> left, LinkedHashMap::new));
        List<String> shardTimings = selected.stream().map(entry -> {
            ShardResult result = resultByName.get(entry.getKey());
            if (result != null) return entry.getKey() + "(queueWaitMs=" + result.queueWaitMs()
                    + ",executionMs=" + result.executionMs() + ",outcome=" + shardOutcome(result.output()) + ")";
            String reason = cancellationReasons.get(entry.getKey());
            return entry.getKey() + (reason == null ? "(unresolved)" : "(cancelled:" + reason + ")");
        }).toList();
        log.info("Phase 35 routed shard retrieval: outcome={} elapsedMs={} effectiveTimeoutMs={} parallelism={} "
                        + "routedShards={} shardTimings={}",
                outcome, elapsedMsSince(startedNanos), effectiveTimeoutMs, parallelism,
                selected.stream().map(Map.Entry::getKey).toList(), shardTimings);
    }

    private static String shardOutcome(String output) {
        if (output != null && output.contains("EVIDENCE")) return "evidence";
        if (LiteratureTimeoutRecoveryQueue.isPhase35TimeoutResult(output)) return "timeout";
        if (output != null && output.contains("not available")) return "unavailable";
        return "no_match";
    }

    private static long elapsedMsSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    String read(String key, String query) {
        Phase34SqliteLiterature reader = readerForKey.get(key == null ? "" : key.strip());
        if (reader == null) return null;
        return reader.read(key, query);
    }

    String assess(String key, String excerpt, String verdict) {
        Phase34SqliteLiterature reader = readerForKey.get(key == null ? "" : key.strip());
        return reader == null ? null : reader.assessRelevance(key, excerpt, verdict);
    }

    private List<Map.Entry<String, Phase34SqliteLiterature>> select(String query) {
        String lower = (query == null ? "" : query).toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (containsAny(lower, "prime", "divisor", "mertens", "mobius", "omega", "zeta", "dirichlet")) {
            names.add("number-theory");
        }
        if (containsAny(lower, "integral", "fourier", "measure", "asymptotic", "analytic")) names.add("analysis");
        if (containsAny(lower, "group", "ring", "field", "module", "representation")) names.add("algebra");
        if (containsAny(lower, "graph", "hypergraph", "matroid", "combinator")) names.add("combinatorics");
        if (containsAny(lower, "manifold", "geometry", "topology", "polygon")) names.add("geometry");
        if (containsAny(lower, "random", "probability", "stochastic", "distribution")) names.add("probability");
        names.add("cross-list"); // bounded recall lane for category-missing records.
        List<Map.Entry<String, Phase34SqliteLiterature>> selected = new ArrayList<>();
        for (String name : names.stream().distinct().toList()) {
            Phase34SqliteLiterature shard = shards.get(name);
            if (shard != null) selected.add(new java.util.AbstractMap.SimpleImmutableEntry<>(name, shard));
            if (selected.size() == 8) break;
        }
        return List.copyOf(selected);
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private record ShardTask(String name, Phase34SqliteLiterature adapter, long submittedNanos) {
        private ShardResult execute(String query, LiteratureQueryProposal proposal) {
            long startedNanos = System.nanoTime();
            String output = adapter.search(query, proposal);
            return new ShardResult(name, adapter, output, elapsedMsSince(submittedNanos),
                    elapsedMsSince(startedNanos));
        }
    }

    private record ShardResult(String name, Phase34SqliteLiterature adapter, String output,
                               long queueWaitMs, long executionMs) {}
}
