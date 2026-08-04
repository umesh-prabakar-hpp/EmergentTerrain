package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 22 §22.6 — periodic heartbeat surfacing sidecar call counts +
 * latency. Companion to {@link TokenTierReporter}, which tracks LLM
 * token spend per tier. This reporter tracks JVM→sidecar HTTP call
 * outcomes + JVM-side latency (including network round-trip), which
 * is what an operator needs to know "is the Julia path doing useful
 * work right now."
 *
 * <h2>Why JVM-side counters, not sidecar /metrics scraping</h2>
 *
 * The sidecar exposes its own {@code /metrics} endpoint with per-
 * endpoint counters. That's useful for sidecar-internal diagnostics
 * (e.g. JIT compile time vs eval time) but NOT for the EmergentTerrain
 * operational question, which is "did EmergentTerrain successfully
 * delegate to Julia?" — the answer to that includes:
 *
 * <ul>
 *   <li>Client-side safety rejects (never reach the sidecar)</li>
 *   <li>Transport failures (the sidecar wouldn't see them either)</li>
 *   <li>Round-trip latency (sidecar timing misses the network hop)</li>
 *   <li>Fall-throughs to Symja on JuliaMathException (only the JVM
 *       knows these happened)</li>
 * </ul>
 *
 * <p>So we count in {@link JuliaMathClient} where the JVM-side full
 * picture exists, and log it here.
 *
 * <h2>Heartbeat shape</h2>
 *
 * Default cadence is 5 minutes ({@code app.julia.reportIntervalMs}).
 * Matches {@link TokenTierReporter}'s rhythm so operators get one
 * coherent heartbeat per 5-minute window covering both LLM tokens and
 * Julia compute.
 *
 * <p>The reporter stores the previous snapshot so each line covers
 * only the delta — a "calls=42" reading means 42 calls in the last
 * 5 minutes, not 42 calls since JVM start. This matches what an
 * operator wants to see (rate, not lifetime totals).
 *
 * <p>One log line per fire:
 * <pre>
 *   julia-sidecar window=5m calls={ok=42, safety_reject=3, eval_error=1, transport_fail=0} \
 *       latency_ms={avg=35, max=4900, total=1470}
 * </pre>
 *
 * <p>No-op if {@link JuliaMathClient} is null (Spring context without
 * the bean — never happens in production but covers test contexts).
 */
@Component
public class JuliaSidecarReporter {

    private static final Logger log = LoggerFactory.getLogger(JuliaSidecarReporter.class);

    private final JuliaMathClient client;
    private final long intervalMs;

    /**
     * Previous snapshot used for delta calculation. Mutated by
     * {@link #report()} on each fire. Single-threaded access — only
     * the Spring scheduler thread reads/writes it — so no atomic
     * wrapper needed.
     */
    private JuliaMathClient.SidecarStats lastSnapshot;

    public JuliaSidecarReporter(JuliaMathClient client,
                                 @Value("${app.julia.reportIntervalMs:300000}") long intervalMs) {
        this.client = client;
        this.intervalMs = intervalMs;
        // Seed the previous snapshot with a zeroed value so the first
        // heartbeat reports cumulative-since-boot (otherwise the
        // first delta would be undefined). Subsequent fires compute
        // proper 5-minute windows.
        this.lastSnapshot = zero();
        log.info("JuliaSidecarReporter: initialized intervalMs={} client={}",
                intervalMs, client == null ? "absent" : "present");
    }

    @Scheduled(fixedDelayString = "${app.julia.reportIntervalMs:300000}")
    public void report() {
        if (client == null) return;
        JuliaMathClient.SidecarStats cur = client.snapshot();
        Delta d = computeDelta(lastSnapshot, cur);
        // Don't spam the log when nothing happened in this window —
        // common case during development when Julia is dormant.
        if (d.totalCalls == 0) {
            lastSnapshot = cur;
            return;
        }
        log.info("julia-sidecar window={}m calls={{ok={}, safety_reject={}, eval_error={}, transport_fail={}}} "
                        + "latency_ms={{avg={}, max={}, total={}}}",
                intervalMs / 60_000L,
                d.okCalls, d.safetyRejectCalls, d.evalErrorCalls, d.transportFailCalls,
                d.okCalls == 0 ? 0 : d.latencyMsTotal / d.okCalls,
                d.latencyMsMax, d.latencyMsTotal);
        // Phase 24 §24.2 — dedicated /pysr heartbeat. Suppress when no
        // /pysr activity happened in this window so dormant-Julia
        // periods don't double the log surface.
        if (d.pysrCalls > 0) {
            log.info("julia-sidecar window={}m pysr={{calls={}, avg_ms={}, max_ms={}, total_ms={}}}",
                    intervalMs / 60_000L,
                    d.pysrCalls,
                    d.pysrCalls == 0 ? 0 : d.pysrLatencyMsTotal / d.pysrCalls,
                    d.pysrLatencyMsMax, d.pysrLatencyMsTotal);
        }
        lastSnapshot = cur;
    }

    /**
     * Compute the per-field delta between two snapshots. Cumulative
     * counts go up monotonically (no decrement path on
     * {@link JuliaMathClient}), so subtraction yields the
     * heartbeat-window value. The {@code latencyMsMax} field is the
     * peak of the running window, not the lifetime peak — we use the
     * current snapshot's max directly (good enough; doesn't reset
     * per window in this version, follow-up if needed).
     */
    static Delta computeDelta(JuliaMathClient.SidecarStats prev,
                               JuliaMathClient.SidecarStats cur) {
        return new Delta(
                cur.totalCalls()         - prev.totalCalls(),
                cur.okCalls()            - prev.okCalls(),
                cur.safetyRejectCalls()  - prev.safetyRejectCalls(),
                cur.evalErrorCalls()     - prev.evalErrorCalls(),
                cur.transportFailCalls() - prev.transportFailCalls(),
                cur.latencyMsTotal()     - prev.latencyMsTotal(),
                cur.latencyMsMax(),                                // running peak — not zeroed per window
                cur.pysrCalls()          - prev.pysrCalls(),
                cur.pysrLatencyMsTotal() - prev.pysrLatencyMsTotal(),
                cur.pysrLatencyMsMax());
    }

    /** Per-heartbeat-window counts. Same shape as SidecarStats but is a delta. */
    record Delta(
            long totalCalls,
            long okCalls,
            long safetyRejectCalls,
            long evalErrorCalls,
            long transportFailCalls,
            long latencyMsTotal,
            long latencyMsMax,
            long pysrCalls,
            long pysrLatencyMsTotal,
            long pysrLatencyMsMax) {}

    private static JuliaMathClient.SidecarStats zero() {
        return new JuliaMathClient.SidecarStats(0, 0, 0, 0, 0, 0, 0, 0.0,
                0, 0, 0, 0.0);
    }
}
