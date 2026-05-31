package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * and the rest of the app keeps running (a feature circuit-break, never an
 * app-wide hard stop — see the design discussion).
 *
 * <p>Flag-gated by {@code app.research.discovery.preflightEnabled} (default ON, a
 * pure cost-protection; OFF restores the legacy degrade-on-403 behavior).
 */
@Component
public class DiscoveryEndpointHealth {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryEndpointHealth.class);
    private static final String PROBE_BODY = "{\"code\":\"candidate(x)=Int64(0)\",\"checkpoints\":[1]}";

    private final boolean enabled;
    private final long cooldownMs;
    private final String url;
    private final HttpClient http;

    private volatile long lastProbeMs = -1;
    private volatile boolean cached = false;

    public DiscoveryEndpointHealth(
            @Value("${app.research.discovery.preflightEnabled:true}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.research.discovery.preflightCooldownMs:60000}") long cooldownMs) {
        this.enabled = enabled;
        this.cooldownMs = cooldownMs;
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.url = b + "/discover/validate";
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        } else if (!was) {
            log.info("DISCOVERY PRE-FLIGHT — {} available; discovery/escalation runs may proceed.", url);
        }
        return avail;
    }

    /** The probe itself (overridable for tests). 200 = enabled; 403/other/error = unavailable. */
    protected boolean doProbe() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(PROBE_BODY))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
