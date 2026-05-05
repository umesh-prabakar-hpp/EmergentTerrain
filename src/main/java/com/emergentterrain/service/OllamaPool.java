package com.emergentterrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Round-robin pool of Ollama backends with per-backend concurrency control and
 * automatic health tracking. Replaces the single chatClient in LlmService.
 *
 * Configuration:
 *   app.ai.backendUrls      — comma-separated list of Ollama base URLs
 *   app.ai.backendParallelism — max concurrent requests per backend (= OLLAMA_NUM_PARALLEL)
 *
 * Acquisition strategy: try each backend in round-robin order; pick the first
 * healthy one that has a free slot. If all are at capacity, block on the best
 * available candidate (fair wait, not a drop). On MAX_FAILURES consecutive
 * errors a backend is marked unhealthy and probed every 30 s until it recovers.
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class OllamaPool {

    private static final Logger log = LoggerFactory.getLogger(OllamaPool.class);

    private static final int  MAX_FAILURES   = 3;
    private static final long PROBE_DELAY_MS = 30_000;

    private record Backend(ChatClient client, Semaphore slots, String url,
                           AtomicBoolean healthy, AtomicInteger consecutiveFailures) {}

    private final List<Backend> backends;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final ScheduledExecutorService probeScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ollama-health-probe");
                t.setDaemon(true);
                return t;
            });

    public OllamaPool(
            @Value("${app.ai.backendUrls:http://localhost:11434}") String backendUrlsCsv,
            @Value("${app.ai.backendParallelism:3}") int parallelism,
            @Value("${spring.ai.ollama.chat.model:gemma3:4b}") String model,
            @Value("${spring.ai.ollama.chat.options.temperature:0.7}") double temperature,
            @Value("${spring.ai.ollama.chat.options.num-predict:200}") int numPredict) {

        OllamaOptions options = OllamaOptions.builder()
                .model(model)
                .temperature(temperature)
                .numPredict(numPredict)
                .build();

        List<Backend> built = new ArrayList<>();
        for (String raw : backendUrlsCsv.split(",")) {
            String url = raw.trim();
            if (url.isEmpty()) continue;
            OllamaApi api = new OllamaApi.Builder().baseUrl(url).build();
            OllamaChatModel chatModel = OllamaChatModel.builder()
                    .ollamaApi(api)
                    .defaultOptions(options)
                    .build();
            ChatClient client = ChatClient.builder(chatModel).build();
            built.add(new Backend(client, new Semaphore(parallelism), url,
                    new AtomicBoolean(true), new AtomicInteger(0)));
            log.info("OllamaPool: registered backend url={} parallelism={}", url, parallelism);
        }
        if (built.isEmpty()) {
            throw new IllegalStateException(
                    "OllamaPool: no backend URLs configured — set app.ai.backendUrls");
        }
        this.backends = List.copyOf(built);
    }

    /**
     * Acquire a backend slot, run fn against its ChatClient, release the slot.
     * Blocks if all backends are at capacity. Re-throws RuntimeException from fn
     * after recording a failure on that backend.
     */
    public <T> T call(Function<ChatClient, T> fn) {
        Backend backend = acquire();
        try {
            T result = fn.apply(backend.client());
            backend.consecutiveFailures().set(0);
            return result;
        } catch (RuntimeException e) {
            recordFailure(backend);
            throw e;
        } finally {
            backend.slots().release();
        }
    }

    public int backendCount()  { return backends.size(); }
    public int healthyCount()  { return (int) backends.stream().filter(b -> b.healthy().get()).count(); }

    private Backend acquire() {
        int n     = backends.size();
        int start = Math.abs(cursor.getAndIncrement() % n);

        // Fast path: first healthy backend with a free slot (round-robin order)
        for (int i = 0; i < n; i++) {
            Backend b = backends.get((start + i) % n);
            if (b.healthy().get() && b.slots().tryAcquire()) {
                return b;
            }
        }

        // Slow path: all full or unhealthy — block on the best available candidate
        Backend candidate = null;
        for (int i = 0; i < n; i++) {
            Backend b = backends.get((start + i) % n);
            if (b.healthy().get()) { candidate = b; break; }
        }
        if (candidate == null) candidate = backends.get(start % n); // last resort
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
            log.warn("OllamaPool: backend {} marked unhealthy after {} failures — probing in {}s",
                    backend.url(), count, PROBE_DELAY_MS / 1000);
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
                log.info("OllamaPool: backend {} is healthy again", backend.url());
            } else {
                log.warn("OllamaPool: backend {} probe returned {} — retrying in {}s",
                        backend.url(), resp.statusCode(), PROBE_DELAY_MS / 1000);
                probeScheduler.schedule(() -> probe(backend), PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("OllamaPool: backend {} probe failed: {} — retrying in {}s",
                    backend.url(), e.getMessage(), PROBE_DELAY_MS / 1000);
            probeScheduler.schedule(() -> probe(backend), PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() { probeScheduler.shutdownNow(); }
}
