package com.emergentterrain.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22 §22.6 — pins the snapshot + delta arithmetic of
 * {@link JuliaSidecarReporter}. The {@code @Scheduled report()} is a
 * thin wrapper around {@code computeDelta()} + a single log line;
 * we test the pure-function piece without needing Spring or a real
 * client.
 */
class JuliaSidecarReporterTest {

    private static JuliaMathClient.SidecarStats snap(long total, long ok,
            long safety, long evalErr, long transport,
            long latencyTotal, long latencyMax, double latencyAvg) {
        // Phase 24 §24.2 added pysr-specific counters. The existing
        // tests don't care about pysr; default the new fields to zero.
        return new JuliaMathClient.SidecarStats(
                total, ok, safety, evalErr, transport,
                latencyTotal, latencyMax, latencyAvg,
                0L, 0L, 0L, 0.0);
    }

    private static JuliaMathClient.SidecarStats snapWithPysr(long total, long ok,
            long latencyTotal, long latencyMax,
            long pysrCalls, long pysrLatencyTotal, long pysrLatencyMax) {
        double avg = ok == 0 ? 0.0 : (double) latencyTotal / ok;
        double pysrAvg = pysrCalls == 0 ? 0.0 : (double) pysrLatencyTotal / pysrCalls;
        return new JuliaMathClient.SidecarStats(
                total, ok, 0, 0, 0,
                latencyTotal, latencyMax, avg,
                pysrCalls, pysrLatencyTotal, pysrLatencyMax, pysrAvg);
    }

    @Test
    void computeDelta_zeroToFirstWindow_equalsFullCurrent() {
        // First heartbeat — previous is zero, so delta == current.
        var prev = snap(0, 0, 0, 0, 0, 0, 0, 0.0);
        var cur  = snap(10, 8, 1, 1, 0, 200, 60, 25.0);

        var d = JuliaSidecarReporter.computeDelta(prev, cur);
        assertThat(d.totalCalls()).isEqualTo(10);
        assertThat(d.okCalls()).isEqualTo(8);
        assertThat(d.safetyRejectCalls()).isEqualTo(1);
        assertThat(d.evalErrorCalls()).isEqualTo(1);
        assertThat(d.transportFailCalls()).isEqualTo(0);
        assertThat(d.latencyMsTotal()).isEqualTo(200);
    }

    @Test
    void computeDelta_subtractsPrevFromCurrent() {
        var prev = snap(10, 8, 1, 1, 0, 200, 60, 25.0);
        var cur  = snap(25, 20, 3, 2, 0, 700, 90, 35.0);

        var d = JuliaSidecarReporter.computeDelta(prev, cur);
        // 25 - 10 = 15, 20 - 8 = 12, etc.
        assertThat(d.totalCalls()).isEqualTo(15);
        assertThat(d.okCalls()).isEqualTo(12);
        assertThat(d.safetyRejectCalls()).isEqualTo(2);
        assertThat(d.evalErrorCalls()).isEqualTo(1);
        assertThat(d.latencyMsTotal()).isEqualTo(500);
    }

    @Test
    void computeDelta_latencyMaxIsRunningPeak_notWindowed() {
        // The current implementation uses the current snapshot's
        // max directly — running peak, not per-window peak. Pin
        // that behaviour so a future tightening (per-window peak)
        // is an intentional change.
        var prev = snap(10, 8, 0, 2, 0, 200, 4900, 25.0);
        var cur  = snap(15, 13, 0, 2, 0, 300, 4900, 23.0);

        var d = JuliaSidecarReporter.computeDelta(prev, cur);
        assertThat(d.latencyMsMax()).isEqualTo(4900);
    }

    @Test
    void computeDelta_separatesPysrFromSharedCounters() {
        // Phase 24 §24.2 — /pysr counters track separately so a
        // 20-second SR call doesn't poison the avg-latency reading
        // for /eval and /solve. Pin that the delta carries pysr
        // fields independently.
        var prev = snapWithPysr(10, 8, 200, 50,  0,    0,    0);
        var cur  = snapWithPysr(13, 11, 250, 50, 2, 32_000, 18_000);

        var d = JuliaSidecarReporter.computeDelta(prev, cur);
        // Shared latency only saw 50ms of new work — the pysr time
        // didn't leak into it.
        assertThat(d.latencyMsTotal()).isEqualTo(50);
        assertThat(d.pysrCalls()).isEqualTo(2);
        assertThat(d.pysrLatencyMsTotal()).isEqualTo(32_000);
        assertThat(d.pysrLatencyMsMax()).isEqualTo(18_000);
    }
}
