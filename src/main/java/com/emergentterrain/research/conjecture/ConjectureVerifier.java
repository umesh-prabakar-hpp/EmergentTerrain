package com.emergentterrain.research.conjecture;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 23.5 — held-out verification of a conjecture. Given an inequality
 * spec ({@code lhs}, {@code rhs}, {@code k}, {@code c}) and a window
 * region, evaluates one or more disjoint windows, reports per-window
 * margins, and returns held/refuted overall.
 *
 * <p>Reuses {@link MathConjectureSubstrate} from 23.1J — verification is
 * cheaper than mining (a single window per call, no engine state), and
 * the SPF sieve is lazy-built on first use so deployments without the
 * conjecture miner enabled don't pay the startup cost.
 *
 * <p>"Held out" is an LLM-prompt-level discipline (it should pick a
 * starting window the miner didn't see, e.g. an offset outside
 * {100, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000});
 * the verifier itself doesn't track miner history. The default
 * {@code numWindows = 5} gives enough signal to refute or confirm
 * confidently while bounding worst-case work.
 */
@Component
public class ConjectureVerifier {

    /** Maximum integer the lazy-built sieve covers. Held-out windows further out are rejected. */
    public static final int MAX_N = 2_000_000;
    /** Default number of disjoint windows evaluated per call. */
    public static final int DEFAULT_NUM_WINDOWS = 5;
    /** Cap on total integers scanned per verify call (numWindows × windowSize). */
    public static final int MAX_WORK = 100_000;

    /**
     * Phase 28 §28.2 — substrate calls go through the delegate so the
     * sidecar's {@code /numbertheory/window} path serves them when
     * the conjecture-sidecar flag is on. Lazy-fetched via the
     * Spring application context to keep this class's existing
     * @Component construction zero-arg (legacy verifier wiring).
     */
    @org.springframework.beans.factory.annotation.Autowired
    private MathConjectureSubstrateDelegate substrateDelegate;

    /** One window's evaluation: the scalar values + the resulting margin. */
    public record WindowReport(
            long windowStart,
            double lhsValue,
            double rhsValue,
            double margin,
            boolean holds) {}

    /** Outcome of a {@link #verify} call. */
    public record VerifyResult(
            String lhs,
            String rhs,
            double k,
            double c,
            int windowsEvaluated,
            boolean held,
            double worstMargin,
            long worstWindowStart,
            List<WindowReport> windows) {}

    public VerifyResult verify(
            String lhs, String rhs,
            double k, double c,
            long windowStart, int windowSize) {
        return verify(lhs, rhs, k, c, windowStart, windowSize, DEFAULT_NUM_WINDOWS);
    }

    public VerifyResult verify(
            String lhs, String rhs,
            double k, double c,
            long windowStart, int windowSize, int numWindows) {

        Objects.requireNonNull(lhs, "lhs");
        Objects.requireNonNull(rhs, "rhs");

        List<String> vocab = MathInvariants.vocabulary();
        if (!vocab.contains(lhs)) {
            throw new IllegalArgumentException("unknown lhs scalar: " + lhs + " (expected one of " + vocab + ")");
        }
        if (!vocab.contains(rhs)) {
            throw new IllegalArgumentException("unknown rhs scalar: " + rhs + " (expected one of " + vocab + ")");
        }
        if (windowStart < 0) {
            throw new IllegalArgumentException("window_start must be >= 0; got " + windowStart);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("window_size must be > 0; got " + windowSize);
        }
        if (numWindows <= 0) {
            throw new IllegalArgumentException("num_windows must be > 0; got " + numWindows);
        }
        long totalWork = (long) numWindows * windowSize;
        if (totalWork > MAX_WORK) {
            throw new IllegalArgumentException(
                    "num_windows × window_size = " + totalWork + " exceeds cap of " + MAX_WORK);
        }
        long maxEvaluated = windowStart + totalWork;
        if (maxEvaluated > MAX_N) {
            throw new IllegalArgumentException(
                    "window extends to n=" + maxEvaluated + " which exceeds MAX_N=" + MAX_N);
        }

        // Phase 28 §28.4 — try the sidecar batched verify path first.
        // One round trip instead of `numWindows` calls to
        // /numbertheory/window. Any failure (transport, bad_request,
        // eval_error) falls back to the legacy per-window path below,
        // logged at WARN. Recognition / verification never blocks on
        // a sidecar outage.
        com.emergentterrain.llm.JuliaMathClient sidecarClient = juliaMathClientOrNull();
        if (sidecarClient != null && sidecarClient.isConjectureAvailable()) {
            try {
                List<long[]> windows = new ArrayList<>(numWindows);
                for (int i = 0; i < numWindows; i++) {
                    long start = windowStart + (long) i * windowSize;
                    windows.add(new long[]{start, windowSize});
                }
                return sidecarClient.conjectureVerify(lhs, rhs, k, c, windows);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ConjectureVerifier.class).warn(
                        "ConjectureVerifier: sidecar /conjecture/verify failed " +
                        "(lhs={} rhs={} numWindows={}): {} — falling back to " +
                        "per-window legacy path",
                        lhs, rhs, numWindows, e.getMessage());
                // Fall through to the legacy path below.
            }
        }

        MathConjectureSubstrateDelegate sub = substrate();

        List<WindowReport> reports = new ArrayList<>(numWindows);
        double worstMargin = Double.NEGATIVE_INFINITY;
        long worstWindowStart = windowStart;
        boolean held = true;

        for (int i = 0; i < numWindows; i++) {
            long start = windowStart + (long) i * windowSize;
            Map<String, Double> raw = sub.evaluateWindow((int) start, windowSize);
            Map<String, Double> flat = MathInvariants.extract(raw, start, windowSize);
            double lhsVal = flat.getOrDefault(lhs, 0.0);
            double rhsVal = flat.getOrDefault(rhs, 0.0);
            double margin = lhsVal - (k * rhsVal + c);
            boolean windowHolds = margin <= 2 * ConjectureEngine.TOL;
            reports.add(new WindowReport(start, lhsVal, rhsVal, margin, windowHolds));
            if (margin > worstMargin) {
                worstMargin = margin;
                worstWindowStart = start;
            }
            if (!windowHolds) {
                held = false;
            }
        }

        return new VerifyResult(lhs, rhs, k, c, numWindows, held, worstMargin, worstWindowStart, reports);
    }

    /**
     * Returns the optional sidecar client used by the §28.4 dispatch.
     * Resolved through the delegate so a single Spring-wired bean
     * powers both 28.2 (substrate) and 28.4 (verify). Returns null
     * when no sidecar client is configured — production keeps the
     * legacy path live until the conjecture flag flips.
     */
    private com.emergentterrain.llm.JuliaMathClient juliaMathClientOrNull() {
        if (substrateDelegate == null) return null;
        return substrateDelegate.juliaMathClient();
    }

    /**
     * Phase 28 §28.2: returns the delegate. Used to be a lazy
     * {@code MathConjectureSubstrate} factory; the delegate now
     * owns the legacy substrate eagerly + the sidecar dispatch.
     *
     * <p>Package-private + non-final so tests that don't wire the
     * Spring context can inject a delegate directly:
     * {@code verifier.setSubstrateDelegate(testDelegate)}.
     */
    MathConjectureSubstrateDelegate substrate() {
        return substrateDelegate;
    }

    /**
     * Test-only injection seam. Spring's @Autowired handles production.
     * Public (rather than package-private) so cross-package tests like
     * {@code com.emergentterrain.service.MathToolSuiteVerifyConjectureTest}
     * can wire a delegate when constructing a verifier outside Spring.
     */
    public void setSubstrateDelegate(MathConjectureSubstrateDelegate delegate) {
        this.substrateDelegate = delegate;
    }
}
