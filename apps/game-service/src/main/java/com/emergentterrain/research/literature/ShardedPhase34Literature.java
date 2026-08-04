package com.emergentterrain.research.literature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final Pattern KEY_LINE = Pattern.compile("(?m)^- \\[?(HF[0-9A-F]+)\\]?.*$", Pattern.CASE_INSENSITIVE);
    private static final List<String> DEFAULT_ORDER = List.of(
            "number-theory", "analysis", "algebra", "combinatorics", "geometry", "probability", "cross-list");

    private final Map<String, Phase34SqliteLiterature> shards;
    private final int parallelism;
    private final long aggregateTimeoutMs;
    private final Map<String, Phase34SqliteLiterature> readerForKey = new java.util.concurrent.ConcurrentHashMap<>();

    ShardedPhase34Literature(Path shardsDir, boolean phase35, long timeoutMs, int initialCandidates,
                             LiteratureQueryPlanner planner, int parallelism) {
        this.parallelism = Math.clamp(parallelism, 1, 4);
        this.aggregateTimeoutMs = Math.max(1_000, timeoutMs);
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
        List<Map.Entry<String, Phase34SqliteLiterature>> selected = select(query);
        if (selected.isEmpty()) return "search_literature: no routed primary literature shard is available.";
        List<Callable<ShardResult>> tasks = selected.stream()
                .map(entry -> (Callable<ShardResult>) () -> new ShardResult(entry.getKey(), entry.getValue(),
                        entry.getValue().search(query, proposal))).toList();
        List<ShardResult> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()))) {
            List<Future<ShardResult>> futures = executor.invokeAll(tasks, aggregateTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            for (Future<ShardResult> future : futures) {
                if (!future.isCancelled()) results.add(future.get());
            }
        } catch (Exception ignored) {
            // Completed results below remain usable; a wholly unresolved fan-out is rendered as a timeout.
        }
        boolean timedOut = results.stream().anyMatch(result ->
                LiteratureTimeoutRecoveryQueue.isPhase35TimeoutResult(result.output()));
        List<ShardResult> evidence = results.stream().filter(result -> result.output().contains("EVIDENCE"))
                .sorted(Comparator.comparingInt(result -> DEFAULT_ORDER.indexOf(result.name()))).toList();
        if (evidence.isEmpty()) {
            return timedOut
                    ? "search_literature: Phase 34 SQLite retrieval exceeded its " + aggregateTimeoutMs
                    + "ms Phase 35 budget; no evidence was issued."
                    : "search_literature (Phase 34 sharded primary): no theorem-index matches for the supplied query.";
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
        return output.toString().stripTrailing();
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

    private record ShardResult(String name, Phase34SqliteLiterature adapter, String output) {}
}
