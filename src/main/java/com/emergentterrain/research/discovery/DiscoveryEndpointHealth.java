package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Phase 30 fork 2b — pre-flight + sticky circuit-breaker for the runtime-discovery
 * endpoint. {@link #available()} probes {@code /discover/validate} once with a
 * trivial candidate (no LLM, sub-second), caches the result for a cooldown, and is
 * consulted by every token-spending discovery/escalation path *before* it
 * generates anything. A 403 (sidecar gate off) or unreachable → cached
 * unavailable + a LOUD warn; the doomed run is skipped with **zero tokens spent**,
 * and the rest of the app keeps running (a feature circuit-break — the default, and
 * the right blast radius: a 403 is the sidecar gate working as designed, not a reason
 * to take down the whole app).
 *
 * <p>Flag-gated by {@code app.research.discovery.preflightEnabled} (default ON, a
 * pure cost-protection; OFF restores the legacy degrade-on-403 behavior).
 *
 * <p><b>Optional hard-stop (default OFF).</b> {@code hardStopOn403Enabled} layers an
 * app-wide hard stop on top of the probe: when a probe goes unavailable AND the flag
 * is ON, the whole ET app is shut down (graceful {@link SpringApplication#exit} then
 * {@code System.exit}). This is the heavy blast radius — provided as an explicit
 * opt-in for environments that would rather fail loud than run dormant. It rides the
 * same probe, so it requires {@code preflightEnabled=true}. Shipped OFF.
 */
@Component
public class DiscoveryEndpointHealth {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryEndpointHealth.class);
    private static final String PROBE_BODY = "{\"code\":\"candidate(x)=Int64(0)\",\"checkpoints\":[1]}";

    private final boolean enabled;
    private final long cooldownMs;
    private final String url;
    private final HttpClient http;
    private final boolean hardStopEnabled;
    private final int hardStopExitCode;
    private final long steadyTimeoutMs;
    private final long coldTimeoutMs;
    private final int coldRetries;

    private volatile long lastProbeMs = -1;
    private volatile boolean cached = false;
    private volatile boolean hardStopTriggered = false;
    /** Flips true on the first 200; until then probes are treated as cold (patient). */
    private volatile boolean warmedUp = false;

    private volatile ConfigurableApplicationContext context;

    /** Outcome of a single probe attempt — distinguishes cold-slow from definitively-down. */
    protected enum ProbeResult { OK, GATE_OFF, TIMEOUT, DOWN }

    @Autowired
    public DiscoveryEndpointHealth(
            @Value("${app.research.discovery.preflightEnabled:true}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.research.discovery.preflightCooldownMs:60000}") long cooldownMs,
            @Value("${app.research.discovery.hardStopOn403Enabled:false}") boolean hardStopEnabled,
            @Value("${app.research.discovery.hardStopExitCode:70}") int hardStopExitCode,
            @Value("${app.research.discovery.preflightProbeTimeoutMs:5000}") long steadyTimeoutMs,
            @Value("${app.research.discovery.preflightColdTimeoutMs:20000}") long coldTimeoutMs,
            @Value("${app.research.discovery.preflightColdRetries:1}") int coldRetries) {
        this.enabled = enabled;
        this.cooldownMs = cooldownMs;
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.url = b + "/discover/validate";
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.hardStopEnabled = hardStopEnabled;
        this.hardStopExitCode = hardStopExitCode;
        this.steadyTimeoutMs = steadyTimeoutMs;
        this.coldTimeoutMs = Math.max(steadyTimeoutMs, coldTimeoutMs);
        this.coldRetries = Math.max(0, coldRetries);
    }

    /** Back-compatible constructor (hard-stop OFF, default cold-start tuning) for tests/call sites. */
    public DiscoveryEndpointHealth(boolean enabled, String baseUrl, long cooldownMs) {
        this(enabled, baseUrl, cooldownMs, false, 70, 5000, 20000, 1);
    }

    /** Back-compatible constructor (default cold-start tuning) for tests/call sites. */
    public DiscoveryEndpointHealth(boolean enabled, String baseUrl, long cooldownMs,
                                   boolean hardStopEnabled, int hardStopExitCode) {
        this(enabled, baseUrl, cooldownMs, hardStopEnabled, hardStopExitCode, 5000, 20000, 1);
    }

    /** Optional — the context to shut down on hard-stop. Absent in unit tests. */
    @Autowired(required = false)
    public void setApplicationContext(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * True if discovery is usable (or the breaker is off). Sticky: re-probes only
     * after the cooldown, so a run's many rungs share one probe; a LOUD warn fires
     * the first time it goes unavailable.
     */
    public boolean available() {
        if (!enabled) {
            return true;   // breaker off — legacy behavior (callers proceed and degrade on 403)
        }
        long now = System.currentTimeMillis();
        if (lastProbeMs >= 0 && now - lastProbeMs < cooldownMs) {
            return cached;
        }
        boolean was = cached;
        boolean avail = doProbe();
        cached = avail;
        lastProbeMs = now;
        if (!avail) {
            log.warn("DISCOVERY PRE-FLIGHT — {} is UNAVAILABLE (403/unreachable). Set ET_DISCOVER_EXEC=1 "
                    + "on the sidecar. Skipping discovery/escalation runs (NO tokens spent) until it recovers.", url);
            if (hardStopEnabled && !hardStopTriggered) {
                hardStopTriggered = true;
                log.error("DISCOVERY HARD-STOP — hardStopOn403Enabled is ON and {} is UNAVAILABLE. "
                        + "Shutting down the ET app (exit code {}). Disable with "
                        + "app.research.discovery.hardStopOn403Enabled=false to fall back to the per-run "
                        + "circuit-break.", url, hardStopExitCode);
                triggerHardStop();
            }
        } else if (!was) {
            log.info("DISCOVERY PRE-FLIGHT — {} available; discovery/escalation runs may proceed.", url);
        }
        return avail;
    }

    /**
     * Perform the app-wide hard stop (overridable so tests can assert it fired without
     * killing the JVM). Default: graceful {@link SpringApplication#exit} to run shutdown
     * hooks, then {@code System.exit} so the process actually terminates.
     */
    protected void triggerHardStop() {
        int code = hardStopExitCode;
        if (context != null) {
            code = SpringApplication.exit(context, () -> hardStopExitCode);
        }
        System.exit(code);
    }

    /**
     * Cold-start-tolerant probe (overridable for tests). The first call to
     * {@code /discover/validate} JIT-compiles the parse + sandbox + sieve path on a
     * single-thread Julia sidecar and can blow past the steady-state timeout, so until
     * the first 200 ({@code warmedUp}) this uses the longer {@code coldTimeoutMs} and
     * retries {@code coldRetries} times. A definitive {@code GATE_OFF} (403 — sidecar
     * started without {@code ET_DISCOVER_EXEC=1}) or {@code DOWN} (connection refused)
     * short-circuits without burning retries; only a {@code TIMEOUT} (likely cold) is
     * retried. A 200 latches {@code warmedUp} so steady-state probes stay fast.
     */
    protected boolean doProbe() {
        long timeoutMs = warmedUp ? steadyTimeoutMs : coldTimeoutMs;
        int attempts = warmedUp ? 1 : 1 + coldRetries;
        for (int i = 0; i < attempts; i++) {
            ProbeResult r = probeOnce(Duration.ofMillis(timeoutMs));
            switch (r) {
                case OK -> {
                    warmedUp = true;
                    return true;
                }
                case GATE_OFF -> {
                    return false;   // 403 — config (ET_DISCOVER_EXEC unset); retries won't help
                }
                case DOWN -> {
                    warmedUp = false;   // connection refused — treat next probe as cold
                    return false;
                }
                case TIMEOUT -> {
                    warmedUp = false;
                    if (i + 1 < attempts) {
                        log.info("DISCOVERY PRE-FLIGHT — probe timed out after {} ms (attempt {}/{}); "
                                + "sidecar may be cold-JITing, retrying.", timeoutMs, i + 1, attempts);
                    }
                }
            }
        }
        return false;
    }

    /** A single probe attempt against the endpoint (overridable for tests). */
    protected ProbeResult probeOnce(Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(PROBE_BODY))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
                    ? ProbeResult.OK : ProbeResult.GATE_OFF;
        } catch (java.net.http.HttpTimeoutException te) {
            return ProbeResult.TIMEOUT;      // slow — likely cold JIT
        } catch (java.net.ConnectException ce) {
            return ProbeResult.DOWN;         // refused — sidecar not listening
        } catch (Exception e) {
            return ProbeResult.TIMEOUT;      // other transient IO — retry like a timeout
        }
    }
}
