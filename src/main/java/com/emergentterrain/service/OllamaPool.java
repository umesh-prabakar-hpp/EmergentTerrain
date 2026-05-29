package com.emergentterrain.service;

import com.emergentterrain.llm.LlmCallMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Phase 9 U4 — Round-robin pool of Ollama backends with multi-model support.
 *
 * <p>Each configured model in {@code app.ai.ollama.preferredModels} (CSV)
 * gets its own list of {@link Backend}s across the configured hosts —
 * {@code N hosts × M models = N×M Backend instances}. Per-model round-robin
 * picks from the relevant list; per-host health tracking continues to gate
 * which Backend wins.
 *
 * <p>Configuration migrated to the unified {@code app.ai.ollama.*} namespace.
 * Legacy {@code spring.ai.ollama.*} keys are read as a fallback for one
 * release cycle; a {@code @PostConstruct} hook logs a deprecation warning
 * when any legacy key is present so operators can spot the migration target.
 *
 * <p>Per-model LlmPool surface (added in U1 default impls; overridden here
 * so the dispatch targets the requested model rather than the first one):
 * <ul>
 *   <li>{@link #callSyncOnModel(String, String)}</li>
 *   <li>{@link #callStreamOnModel(String, String, Consumer)}</li>
 *   <li>{@link #modelIds()}</li>
 *   <li>{@link #isModelBlocked(String)} — true iff every Backend for the
 *       given model is currently unhealthy.</li>
 * </ul>
 *
 * <p>{@link #callSync(String)} / {@link #callStream(String, Consumer)} keep
 * legacy behaviour: they forward to the first preferred model. U6's
 * {@code FastModelRegistry} will route the router's fast-tier calls through
 * a model-aware path so a slow first model doesn't drag the whole tier.
 */
@Component
// 2026-05-29 — per-provider kill switch. The annotation's name[] requires
// EVERY listed property to equal "true", so the bean loads only when the
// global AI switch is on AND ollama is explicitly enabled. app.ai.ollama.enabled
// defaults OFF (matchIfMissing=false): Ollama is dormant unless an operator
// opts it back in, at which point it rejoins FastModelRegistry automatically.
// OpenRouter is the configured fast-tier provider (app.ai.fastProvider) while
// this is off.
@ConditionalOnProperty(
        name = {"app.ai.enabled", "app.ai.ollama.enabled"},
        havingValue = "true",
        matchIfMissing = false)
public class OllamaPool implements LlmPool {

    private static final Logger log = LoggerFactory.getLogger(OllamaPool.class);

    private static final int  MAX_FAILURES   = 3;
    private static final long PROBE_DELAY_MS = 30_000;
    private static final String PROVIDER_ID = "ollama";

    @Autowired(required = false)
    private LlmCallMetrics callMetrics;

    private record Backend(String modelName, ChatClient client, Semaphore slots, String url,
                           AtomicBoolean healthy, AtomicInteger consecutiveFailures) {}

    private final List<String> modelIds;
    /** Backends grouped by model id, in CSV order. */
    private final Map<String, List<Backend>> backendsByModel;
    /** Round-robin cursor per model. */
    private final Map<String, AtomicInteger> cursorsByModel;
    private final Environment environment;
    private final ScheduledExecutorService probeScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ollama-health-probe");
                t.setDaemon(true);
                return t;
            });

    public OllamaPool(
            Environment environment,
            // Phase 9 U4 — chained defaults: new key → legacy key → constant. New
            // key wins on set; legacy is the fallback. checkLegacyKeys() WARNs
            // when the legacy form is what actually populated the value.
            @Value("${app.ai.ollama.backendHosts:${app.ai.backendHosts:localhost}}")
            String backendHostsCsv,
            @Value("${app.ai.ollama.backendPort:${app.ai.backendPort:11434}}")
            int port,
            @Value("${app.ai.ollama.backendParallelism:${app.ai.backendParallelism:3}}")
            int parallelism,
            @Value("${app.ai.ollama.preferredModels:${spring.ai.ollama.chat.model:gemma3:4b}}")
            String preferredModelsCsv,
            @Value("${app.ai.ollama.temperature:${spring.ai.ollama.chat.options.temperature:0.7}}")
            double temperature,
            @Value("${app.ai.ollama.maxTokens:${spring.ai.ollama.chat.options.num-predict:200}}")
            int maxTokens,
            @Value("${app.ai.selection-tests.enabled:true}")
            boolean selectionTestsEnabled) {
        this.environment = environment;

        List<String> candidates = Arrays.stream(preferredModelsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "OllamaPool: no models configured — set app.ai.ollama.preferredModels");
        }

        List<String> hosts = Arrays.stream(backendHostsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (hosts.isEmpty()) {
            throw new IllegalStateException(
                    "OllamaPool: no backend URLs configured — set app.ai.ollama.backendHosts");
        }

        // Build N hosts × M models Backend instances for every candidate. Each
        // Backend pins a (host, model) tuple — its ChatClient is configured with
        // that single model so the round-robin pick fully determines which model
        // serves. Models that fail Phase 9 U5b selection tests are dropped from
        // the final maps below, leaving their Backend instances unused.
        Map<String, List<Backend>> candidateByModel = new LinkedHashMap<>();
        Map<String, AtomicInteger> candidateCursors = new LinkedHashMap<>();
        for (String modelId : candidates) {
            List<Backend> built = new ArrayList<>();
            for (String host : hosts) {
                String url = "http://" + host + ":" + port;
                OllamaApi api = new OllamaApi.Builder().baseUrl(url).build();
                OllamaOptions options = OllamaOptions.builder()
                        .model(modelId)
                        .temperature(temperature)
                        .numPredict(maxTokens)
                        .build();
                OllamaChatModel chatModel = OllamaChatModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(options)
                        .build();
                ChatClient client = ChatClient.builder(chatModel).build();
                built.add(new Backend(modelId, client, new Semaphore(parallelism), url,
                        new AtomicBoolean(true), new AtomicInteger(0)));
                log.info("OllamaPool: registered backend url={} model={} parallelism={}",
                        url, modelId, parallelism);
            }
            candidateByModel.put(modelId, List.copyOf(built));
            candidateCursors.put(modelId, new AtomicInteger(0));
        }

        // Phase 9 U5b — run chat + JSON probes against each candidate. Ollama
        // doesn't support tools so the toolDispatch is null; the tester skips
        // the tool probe accordingly.
        List<String> passing;
        if (selectionTestsEnabled) {
            passing = ModelSelectionTester.filterPassing(
                    "ollama", candidates,
                    (mid, prompt) -> probeOllamaModel(
                            candidateByModel.get(mid), candidateCursors.get(mid), prompt),
                    null);
            if (passing.isEmpty()) {
                // Every model failed — most likely Ollama isn't running. Keep
                // unverified candidates so the pool doesn't become unusable; the
                // health-probe scheduler will retry connectivity on its own
                // cadence. Operator sees the WARN and can investigate.
                log.warn("OllamaPool: selection tests failed for all candidates {} — "
                                + "keeping unverified models in activeModels. "
                                + "Investigate: is Ollama running on {} ? are the models "
                                + "pulled (`ollama list`)?",
                        candidates, hosts);
                passing = candidates;
            }
        } else {
            passing = candidates;
        }

        // Final maps drop entries for failed models so isModelBlocked + acquire
        // return correct values for them (model unknown → "blocked").
        Map<String, List<Backend>> finalByModel = new LinkedHashMap<>();
        Map<String, AtomicInteger> finalCursors = new LinkedHashMap<>();
        for (String mid : passing) {
            finalByModel.put(mid, candidateByModel.get(mid));
            finalCursors.put(mid, candidateCursors.get(mid));
        }
        this.modelIds        = List.copyOf(passing);
        this.backendsByModel = Collections.unmodifiableMap(finalByModel);
        this.cursorsByModel  = Collections.unmodifiableMap(finalCursors);

        log.info("OllamaPool: models={} parallelism={} selection-tests={}",
                modelIds, parallelism, selectionTestsEnabled ? "on" : "off");
    }

    /**
     * Phase 9 U5b + Phase 20.11 fixup — single-shot sync call for selection
     * probes. Races every configured backend in parallel and returns the
     * first non-throwing result; the slower backends' calls keep running
     * but their results are discarded. Cuts gemma3:4b-on-CPU probe time
     * roughly in half on this codebase's typical two-backend setup
     * (localhost + 192.168.1.145): the 2026-05-20 08:01 startup saw a
     * single-backend chat probe at 47s + json probe at 114s = 161s total
     * for one model; with the GPU-class backend at 192.168.1.145 winning
     * the race, expected total drops to ~30-50s.
     *
     * <p>Trade-off: temporarily occupies one slot on every backend
     * simultaneously (rather than just one). Acceptable for startup-time
     * probes — production calls still go through the round-robin path
     * via {@link #acquireFrom} for proper load distribution.
     */
    private String probeOllamaModel(List<Backend> backends, AtomicInteger cursor,
                                      String prompt) {
        if (backends.size() == 1) return probeOnBackend(backends.get(0), prompt);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            // invokeAny returns the first non-throwing result; the rest are
            // cancelled (interrupting their underlying ChatClient calls) once
            // a winner emerges, which is what we want — no need to wait for
            // a slow localhost probe once the LAN backend has answered.
            return executor.invokeAny(backends.stream()
                    .<java.util.concurrent.Callable<String>>map(
                            b -> () -> probeOnBackend(b, prompt))
                    .toList());
        } catch (java.util.concurrent.ExecutionException e) {
            // Every backend threw — re-surface the last cause for the tester
            // to interpret (chat-probe / json-probe / tool-probe failure).
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("OllamaPool probe failed on all backends", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OllamaPool probe interrupted", e);
        }
    }

    /**
     * Single-backend probe. Shared between {@link #probeOllamaModel} (race
     * winner path) and the degenerate single-backend case. Acquires a slot,
     * runs the chat client call, records success/failure, releases the slot.
     */
    private String probeOnBackend(Backend b, String prompt) {
        try {
            b.slots().acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for OllamaPool probe slot", e);
        }
        try {
            String result = b.client().prompt().user(prompt).call().content();
            b.consecutiveFailures().set(0);
            return result;
        } catch (RuntimeException e) {
            recordFailure(b);
            throw e;
        } finally {
            b.slots().release();
        }
    }

    /**
     * Phase 9 U4 — deprecation warning for legacy {@code spring.ai.ollama.*}
     * keys. Runs once at startup; the constructor's chained-default
     * {@code @Value} resolution already prefers the new keys, so the warning
     * is purely advisory — operators see the message and can migrate at their
     * convenience within the deprecation window.
     */
    @PostConstruct
    void checkLegacyKeys() {
        List<String> legacy = List.of(
                "spring.ai.ollama.chat.model",
                "spring.ai.ollama.chat.options.temperature",
                "spring.ai.ollama.chat.options.num-predict",
                "app.ai.backendHosts",
                "app.ai.backendPort",
                "app.ai.backendParallelism");
        List<String> detected = legacy.stream()
                .filter(k -> environment.containsProperty(k))
                .toList();
        if (!detected.isEmpty()) {
            log.warn("OllamaPool: legacy config keys detected — please migrate to "
                            + "app.ai.ollama.*: {}", detected);
        }
    }

    @Override
    public String callSync(String prompt) {
        return callSyncOnModel(modelIds.get(0), prompt);
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        callStreamOnModel(modelIds.get(0), prompt, tokenConsumer);
    }

    @Override
    public String callSyncOnModel(String modelId, String prompt) {
        Backend backend = acquire(modelId);
        String outcome = "ok";
        try {
            String result = backend.client().prompt().user(prompt).call().content();
            backend.consecutiveFailures().set(0);
            return result;
        } catch (RuntimeException e) {
            outcome = "io_error";
            recordFailure(backend);
            throw e;
        } finally {
            backend.slots().release();
            recordCall(modelId, "sync", outcome);
        }
    }

    @Override
    public void callStreamOnModel(String modelId, String prompt, Consumer<String> tokenConsumer) {
        Backend backend = acquire(modelId);
        String outcome = "ok";
        try {
            backend.client().prompt().user(prompt).stream().content()
                    .toStream().forEach(tokenConsumer);
            backend.consecutiveFailures().set(0);
        } catch (RuntimeException e) {
            outcome = "io_error";
            recordFailure(backend);
            throw e;
        } finally {
            backend.slots().release();
            recordCall(modelId, "stream", outcome);
        }
    }

    private void recordCall(String modelId, String mode, String outcome) {
        if (callMetrics != null) callMetrics.record(PROVIDER_ID, modelId, mode, outcome);
    }

    @Override
    public List<String> modelIds() { return modelIds; }

    @Override
    public boolean isModelBlocked(String modelId) {
        List<Backend> backends = backendsByModel.get(modelId);
        if (backends == null || backends.isEmpty()) return true;
        return backends.stream().noneMatch(b -> b.healthy().get());
    }

    @Override
    public String selectedModel() { return modelIds.get(0); }

    @Override
    public List<String> selectedModels() { return modelIds; }

    @Override
    public int backendCount() {
        return backendsByModel.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public int healthyCount() {
        return (int) backendsByModel.values().stream()
                .flatMap(List::stream)
                .filter(b -> b.healthy().get())
                .count();
    }

    @Override
    public boolean allBlocked() {
        return modelIds.stream().allMatch(this::isModelBlocked);
    }

    /**
     * Pick a healthy Backend for the given model. Round-robins through the
     * model's Backend list; if none are healthy AND none have free slots,
     * waits on the first healthy candidate. Throws on unknown model id.
     */
    private Backend acquire(String modelId) {
        List<Backend> list = backendsByModel.get(modelId);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("OllamaPool: unknown model=" + modelId);
        }
        return acquireFrom(list, cursorsByModel.get(modelId));
    }

    /**
     * Phase 9 U5b — acquire factored out so selection-test probes can use it
     * against candidate maps before {@link #backendsByModel} is finalised.
     */
    private static Backend acquireFrom(List<Backend> list, AtomicInteger cursor) {
        int n     = list.size();
        int start = Math.abs(cursor.getAndIncrement() % n);

        for (int i = 0; i < n; i++) {
            Backend b = list.get((start + i) % n);
            if (b.healthy().get() && b.slots().tryAcquire()) {
                return b;
            }
        }

        Backend candidate = null;
        for (int i = 0; i < n; i++) {
            Backend b = list.get((start + i) % n);
            if (b.healthy().get()) { candidate = b; break; }
        }
        if (candidate == null) candidate = list.get(start % n);
        try {
            candidate.slots().acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for OllamaPool slot", e);
        }
        return candidate;
    }

    private void recordFailure(Backend backend) {
        int count = backend.consecutiveFailures().incrementAndGet();
        if (count >= MAX_FAILURES && backend.healthy().compareAndSet(true, false)) {
            log.warn("OllamaPool: backend {} (model={}) marked unhealthy after {} failures — "
                            + "probing in {}s",
                    backend.url(), backend.modelName(), count, PROBE_DELAY_MS / 1000);
            probeScheduler.schedule(() -> probe(backend), PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void probe(Backend backend) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(backend.url() + "/api/tags"))
                    .GET().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 200) {
                backend.consecutiveFailures().set(0);
                backend.healthy().set(true);
                log.info("OllamaPool: backend {} (model={}) is healthy again",
                        backend.url(), backend.modelName());
            } else {
                log.warn("OllamaPool: backend {} (model={}) probe returned {} — retrying in {}s",
                        backend.url(), backend.modelName(), resp.statusCode(), PROBE_DELAY_MS / 1000);
                probeScheduler.schedule(() -> probe(backend), PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("OllamaPool: backend {} (model={}) probe failed: {} — retrying in {}s",
                    backend.url(), backend.modelName(), e.getMessage(), PROBE_DELAY_MS / 1000);
            probeScheduler.schedule(() -> probe(backend), PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() { probeScheduler.shutdownNow(); }
}
