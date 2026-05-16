package com.emergentterrain.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Host-level resource kill-switch parallel to {@link SimulationGate}.
 *
 * Samples the host's memory-available + load average and the JVM's heap
 * utilisation every {@code app.resources.sampleIntervalMs} ms. Worker
 * threads in {@code @Scheduled} entry points consult {@link #isOpen()} at
 * the top and return early when the host is under stress, exactly the way
 * they already consult {@link SimulationGate#isOpen()}. Threads that can
 * afford to block call {@link #awaitOpen(long)} to wait until the host
 * recovers, with a hard timeout fallback.
 *
 * <p>Why this exists: the 2026-05-16 hang manifested as kwin_wayland and
 * kwrite freezing for ~40 minutes while the box was thrashing — symptomatic
 * of memory pressure with no app-side throttle. This gate gives the app
 * a place to back off before the kernel does it for us.
 *
 * <p>Three pressure levels with hysteresis:
 * <ul>
 *   <li>{@code NORMAL} — open, no throttling.</li>
 *   <li>{@code ELEVATED} — open, but the gate logs and emits a warning so
 *       downstream code can choose to skip non-essential work.</li>
 *   <li>{@code CRITICAL} — closed. {@link #isOpen()} returns {@code false}
 *       and {@link #awaitOpen(long)} blocks. Two consecutive clean samples
 *       are required to drop back below {@code CRITICAL} (anti-flap).</li>
 * </ul>
 *
 * Signals (any one trips the level):
 * <ul>
 *   <li>{@code /proc/meminfo MemAvailable} below
 *       {@code memAvailableElevatedMb} / {@code memAvailableCriticalMb}.</li>
 *   <li>{@code /proc/loadavg} 1-minute average above
 *       {@code loadPerCoreElevated} / {@code loadPerCoreCritical} ×
 *       {@code availableProcessors()}.</li>
 *   <li>JVM heap used / max above {@code heapElevatedPct} /
 *       {@code heapCriticalPct}.</li>
 * </ul>
 *
 * <p>Disabled on non-Linux hosts (no {@code /proc/meminfo}) — only the JVM
 * heap check applies in that case.
 */
@Component
public class SystemResourcesGate {

    private static final Logger log = LoggerFactory.getLogger(SystemResourcesGate.class);

    public enum Level { NORMAL, ELEVATED, CRITICAL }

    private static final Path MEMINFO = Paths.get("/proc/meminfo");
    private static final Path LOADAVG = Paths.get("/proc/loadavg");

    private final boolean enabled;
    private final long memAvailableElevatedKb;
    private final long memAvailableCriticalKb;
    private final double loadPerCoreElevated;
    private final double loadPerCoreCritical;
    private final double heapElevatedPct;
    private final double heapCriticalPct;
    private final int cores;

    private final AtomicReference<Level> level = new AtomicReference<>(Level.NORMAL);
    private final AtomicReference<String> reason = new AtomicReference<>("startup");
    private final AtomicReference<Instant> lastChange = new AtomicReference<>(Instant.now());
    private final AtomicInteger criticalClearStreak = new AtomicInteger(0);

    public SystemResourcesGate(
            @Value("${app.resources.enabled:true}")              boolean enabled,
            @Value("${app.resources.memAvailableElevatedMb:1500}") long memAvailableElevatedMb,
            @Value("${app.resources.memAvailableCriticalMb:500}")  long memAvailableCriticalMb,
            @Value("${app.resources.loadPerCoreElevated:2.0}")     double loadPerCoreElevated,
            @Value("${app.resources.loadPerCoreCritical:4.0}")     double loadPerCoreCritical,
            @Value("${app.resources.heapElevatedPct:85}")          double heapElevatedPct,
            @Value("${app.resources.heapCriticalPct:95}")          double heapCriticalPct) {
        this.enabled                = enabled;
        this.memAvailableElevatedKb = memAvailableElevatedMb * 1024L;
        this.memAvailableCriticalKb = memAvailableCriticalMb * 1024L;
        this.loadPerCoreElevated    = loadPerCoreElevated;
        this.loadPerCoreCritical    = loadPerCoreCritical;
        this.heapElevatedPct        = heapElevatedPct;
        this.heapCriticalPct        = heapCriticalPct;
        this.cores                  = Math.max(1, Runtime.getRuntime().availableProcessors());
        log.info("SystemResourcesGate: enabled={} cores={} memElev={}MB memCrit={}MB "
                        + "loadElev={}/core loadCrit={}/core heapElev={}% heapCrit={}%",
                enabled, cores, memAvailableElevatedMb, memAvailableCriticalMb,
                loadPerCoreElevated, loadPerCoreCritical, heapElevatedPct, heapCriticalPct);
    }

    @Scheduled(fixedDelayString = "${app.resources.sampleIntervalMs:5000}", initialDelay = 2000L)
    public void sample() {
        if (!enabled) return;
        Sample s = currentSample();
        Level next = classify(s);
        Level prev = level.get();

        // Anti-flap: require two consecutive non-critical samples before
        // dropping out of CRITICAL.
        if (prev == Level.CRITICAL && next != Level.CRITICAL) {
            if (criticalClearStreak.incrementAndGet() < 2) {
                return; // hold critical for one more sample
            }
        }
        criticalClearStreak.set(0);

        if (next == prev) return;

        level.set(next);
        reason.set(s.summarize());
        lastChange.set(Instant.now());
        if (next == Level.CRITICAL) {
            log.error("SystemResourcesGate: CRITICAL — pausing simulation work. {}", s.summarize());
        } else if (next == Level.ELEVATED) {
            log.warn("SystemResourcesGate: ELEVATED — {}", s.summarize());
        } else {
            log.info("SystemResourcesGate: NORMAL — recovered. {}", s.summarize());
        }
    }

    /** True when host resources are not in CRITICAL. Scheduled jobs consult at top. */
    public boolean isOpen() {
        return level.get() != Level.CRITICAL;
    }

    public Level level()      { return level.get(); }
    public String reason()    { return reason.get(); }
    public Instant since()    { return lastChange.get(); }

    /**
     * Block for up to {@code maxWaitMillis} until {@link #isOpen()} returns
     * true. Returns {@code true} if the gate opened within the budget,
     * {@code false} on timeout or interrupt. Poll interval is bounded so
     * the caller always returns by the deadline.
     */
    public boolean awaitOpen(long maxWaitMillis) {
        if (isOpen()) return true;
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
        while (!isOpen()) {
            long remainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainMs <= 0) return false;
            try {
                Thread.sleep(Math.min(1000L, remainMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // --- sampling ---------------------------------------------------------

    private Level classify(Sample s) {
        boolean critical =
                (s.memAvailableKb >= 0 && s.memAvailableKb < memAvailableCriticalKb)
             || (s.load1 >= 0 && s.load1 > loadPerCoreCritical * cores)
             || (s.heapPct() > heapCriticalPct);
        if (critical) return Level.CRITICAL;
        boolean elevated =
                (s.memAvailableKb >= 0 && s.memAvailableKb < memAvailableElevatedKb)
             || (s.load1 >= 0 && s.load1 > loadPerCoreElevated * cores)
             || (s.heapPct() > heapElevatedPct);
        return elevated ? Level.ELEVATED : Level.NORMAL;
    }

    private Sample currentSample() {
        long memAvailableKb = readMemAvailableKb();
        double load1 = readLoad1();
        Runtime rt = Runtime.getRuntime();
        long heapMax  = rt.maxMemory();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        return new Sample(memAvailableKb, load1, heapUsed, heapMax, cores);
    }

    private static long readMemAvailableKb() {
        try {
            List<String> lines = Files.readAllLines(MEMINFO);
            for (String line : lines) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // proc not available (non-Linux) or parse error — return sentinel
        }
        return -1L;
    }

    private static double readLoad1() {
        try {
            String line = Files.readAllLines(LOADAVG).get(0);
            String[] parts = line.split("\\s+");
            if (parts.length >= 1) return Double.parseDouble(parts[0]);
        } catch (IOException | NumberFormatException | IndexOutOfBoundsException e) {
            // proc not available — return sentinel
        }
        return -1.0;
    }

    private record Sample(long memAvailableKb, double load1, long heapUsed, long heapMax, int cores) {
        double heapPct() { return 100.0 * heapUsed / heapMax; }
        String summarize() {
            String mem = memAvailableKb < 0 ? "n/a" : String.format("%.0fMB", memAvailableKb / 1024.0);
            String load = load1 < 0 ? "n/a" : String.format("%.2f", load1);
            return String.format("memAvail=%s load1=%s/%d-cores heap=%.0f%%(%dMB/%dMB)",
                    mem, load, cores, heapPct(), heapUsed >> 20, heapMax >> 20);
        }
    }
}
