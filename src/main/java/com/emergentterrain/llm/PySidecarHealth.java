package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reachability authority for the single-threaded PyCall <em>py-sidecar</em>
 * ({@code julia-math-sidecar/scripts/start-py-sidecar.sh}, default
 * {@code 127.0.0.1:8089}).
 *
 * <h2>Why this is a separate bean from {@link JuliaMathClient}</h2>
 * The PyCall-backed endpoints — {@code /prove_identity},
 * {@code /recognise/constant}, {@code /recognise/minpoly} — run <strong>only</strong>
 * in the py-sidecar. The main 4-thread sidecar is PyCall-free by construction
 * (it never loads {@code pycall_sidecar.jl}, so libpython is never resident),
 * which is what removes the off-thread {@code pydecref} SIGSEGV that struck
 * {@code /pysr}. Consequently the py-sidecar's liveness is independent of the
 * main sidecar's {@code /healthz}: probing the main server (8088) would report
 * "up" even when the PyCall endpoints aren't being served, which is exactly the
 * false-positive availability that {@code SympyIdentityClient} /
 * {@code PslqRecogniserClient} / {@code LllRecogniserClient} previously suffered
 * by delegating to {@code JuliaMathClient.isAvailable()}.
 *
 * <p>This bean probes the py-sidecar's own {@code /healthz} so those clients'
 * {@code isAvailable()} is truthful. One shared probe loop serves all three
 * (the same rationale that had them share {@code JuliaMathClient} before).
 *
 * <p>Probe discipline mirrors {@link JuliaMathClient}: a sticky
 * {@link AtomicBoolean} set by a {@link PostConstruct} probe and recovered by a
 * 60s scheduled re-probe that only fires while unreachable.
 */
@Component
public class PySidecarHealth {

    private static final Logger log = LoggerFactory.getLogger(PySidecarHealth.class);

    private final String baseUrl;
    private final long healthProbeTimeoutMs;
    private final HttpClient http;

    /** Sticky reachability; false until a /healthz probe succeeds. */
    private final AtomicBoolean reachable = new AtomicBoolean(false);

    @Autowired
    public PySidecarHealth(
            @Value("${app.julia.pySidecarUrl:http://127.0.0.1:8089}") String baseUrl,
            @Value("${app.julia.pySidecarHealthProbeTimeoutMs:3000}") long healthProbeTimeoutMs) {
        // Strip trailing slash so callers can append "/prove_identity" etc.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.healthProbeTimeoutMs = healthProbeTimeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** The py-sidecar base URL the PyCall clients should connect to (no trailing slash). */
    public String baseUrl() {
        return baseUrl;
    }

    /** Last-seen reachability. Updated by {@link #probeHealth()}. */
    public boolean isReachable() {
        return reachable.get();
    }

    @PostConstruct
    public void onInit() {
        boolean ok = probeHealth();
        log.info("PySidecarHealth: baseUrl={}, reachable={} (PyCall endpoints: /prove_identity, /recognise/constant, /recognise/minpoly)",
                baseUrl, ok);
    }

    /**
     * Periodic re-probe to recover from a transient first-probe miss (the
     * py-sidecar cold-JITs sympy/mpmath on boot and may not answer the
     * {@link PostConstruct} probe in time). Only probes while unreachable so a
     * healthy bean doesn't burn a /healthz call every minute, and a
     * permanently-absent py-sidecar doesn't spam WARN.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduledReprobe() {
        if (reachable.get()) return;
        boolean now = probeHealth();
        if (now) {
            log.info("PySidecarHealth: py-sidecar recovered — reachable=true via scheduled re-probe");
        }
    }

    /**
     * Synchronous {@code GET /healthz} against the py-sidecar; updates and
     * returns {@link #reachable}. Idempotent; safe to call from tests.
     */
    public boolean probeHealth() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/healthz"))
                    .timeout(Duration.ofMillis(healthProbeTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200;
            reachable.set(ok);
            if (!ok) {
                log.warn("PySidecarHealth: /healthz returned status={} — py-sidecar marked unreachable",
                        resp.statusCode());
            }
            return ok;
        } catch (Exception e) {
            reachable.set(false);
            // Single-line WARN, no stack trace — "py-sidecar isn't running" is a
            // normal-and-expected condition (start-py-sidecar.sh is opt-in).
            log.warn("PySidecarHealth: /healthz probe failed (baseUrl={}): {} — py-sidecar marked unreachable",
                    baseUrl, e.getMessage());
            return false;
        }
    }
}
