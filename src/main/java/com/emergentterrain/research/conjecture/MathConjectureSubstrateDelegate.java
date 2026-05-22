package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.JuliaMathClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 28 §28.2 — dispatch wrapper around the legacy
 * {@link MathConjectureSubstrate} and the sidecar's
 * {@code /numbertheory/window} endpoint (via
 * {@link JuliaMathClient#numberTheoryWindow}).
 *
 * <h2>Dispatch rule</h2>
 *
 * Default behaviour preserves the legacy in-process path — every
 * call hits the same SPF sieve and per-integer scalars that
 * shipped with phase 23.1. When
 * {@code app.julia.conjecture.enabled=true} AND the sidecar is
 * reachable, calls route through the sidecar instead. A sidecar
 * failure (transport, bad_request, eval_error) logs WARN and
 * falls through to the legacy path — recognition / mining never
 * blocks on a sidecar outage.
 *
 * <h2>Why a delegate, not direct sidecar routing</h2>
 *
 * The phase-23.1J substrate already exists, is correct, and ships
 * with the operator-facing JVM. Removing it before the sidecar has
 * burned in would be a regression. The delegate lets the operator
 * flip the flag and observe — if the sidecar path falls back too
 * often (warn-log frequency), the flag flips back without code
 * changes.
 *
 * <h2>Substrate lifecycle</h2>
 *
 * The legacy substrate is built eagerly at constructor time with
 * the configured {@code maxN} (default 2,000,000 — matches the
 * larger of the two existing callers, {@code ConjectureVerifier}).
 * SPF sieve construction is ~1–2 s; doing it once at startup keeps
 * later {@code evaluateWindow} calls sub-millisecond. The miner +
 * verifier share this single instance.
 */
@Service
public class MathConjectureSubstrateDelegate {

    private static final Logger log = LoggerFactory.getLogger(MathConjectureSubstrateDelegate.class);

    /**
     * Max integer the legacy SPF sieve supports. Larger than
     * sidecar's {@code NT_MAX_N=1_050_000} cap so the delegate can
     * cover the full {@link ConjectureVerifier#MAX_N} range
     * (2,000,000). When a window exceeds the sidecar's cap, the
     * sidecar returns {@code bad_request} and we fall back to
     * legacy — handled transparently in {@link #evaluateWindow}.
     */
    public static final int DEFAULT_MAX_N = 2_000_000;

    private final MathConjectureSubstrate legacy;
    private final JuliaMathClient juliaMathClient;
    private final boolean conjectureSidecarEnabled;

    private final AtomicLong totalCalls       = new AtomicLong();
    private final AtomicLong sidecarCalls     = new AtomicLong();
    private final AtomicLong sidecarFallbacks = new AtomicLong();
    private final AtomicLong legacyCalls      = new AtomicLong();

    @Autowired
    public MathConjectureSubstrateDelegate(
            @Value("${app.julia.conjecture.maxN:2000000}") int maxN,
            @Autowired(required = false) JuliaMathClient juliaMathClient) {
        log.info("MathConjectureSubstrateDelegate: building legacy substrate maxN={}", maxN);
        this.legacy = new MathConjectureSubstrate(maxN);
        this.juliaMathClient = juliaMathClient;
        this.conjectureSidecarEnabled = juliaMathClient != null;
        log.info("MathConjectureSubstrateDelegate: ready, sidecar wired={} (actual " +
                "isConjectureAvailable() decided per call)", conjectureSidecarEnabled);
    }

    /**
     * Test-friendly constructor that injects a pre-built legacy
     * substrate. Avoids the SPF sieve rebuild cost when a test
     * reuses a substrate across multiple delegate fixtures.
     */
    public MathConjectureSubstrateDelegate(MathConjectureSubstrate legacy,
                                            JuliaMathClient juliaMathClient) {
        this.legacy = legacy;
        this.juliaMathClient = juliaMathClient;
        this.conjectureSidecarEnabled = juliaMathClient != null;
    }

    /**
     * Evaluate the per-window scalar map for the given window.
     * Dispatch matrix:
     * <ul>
     *   <li>JuliaMathClient null OR
     *       {@code !isConjectureAvailable()} → legacy.</li>
     *   <li>Sidecar reachable AND configured → try sidecar; on any
     *       exception, log WARN and fall back to legacy.</li>
     * </ul>
     *
     * <p>Returned map shape (key order + value semantics) is
     * byte-identical between paths — the §28.2 parity test pins
     * this. New keys / reordering must be added to both impls in
     * the same commit.
     */
    public Map<String, Double> evaluateWindow(int start, int size) {
        totalCalls.incrementAndGet();

        if (conjectureSidecarEnabled && juliaMathClient.isConjectureAvailable()) {
            try {
                sidecarCalls.incrementAndGet();
                JuliaMathClient.NumberTheoryWindow result =
                        juliaMathClient.numberTheoryWindow(start, size);
                return result.scalars();
            } catch (RuntimeException e) {
                sidecarFallbacks.incrementAndGet();
                log.warn("MathConjectureSubstrateDelegate: sidecar number-theory-window " +
                        "failed (start={} size={}): {} — falling back to legacy",
                        start, size, e.getMessage());
                // Fall through to legacy below.
            }
        }
        legacyCalls.incrementAndGet();
        return legacy.evaluateWindow(start, size);
    }

    /** Snapshot of dispatch counters. Useful for the §28.2 burn-in audit. */
    public Stats stats() {
        return new Stats(
                totalCalls.get(),
                sidecarCalls.get(),
                sidecarFallbacks.get(),
                legacyCalls.get());
    }

    public record Stats(
            long totalCalls,
            long sidecarCalls,
            long sidecarFallbacks,
            long legacyCalls) {}
}
