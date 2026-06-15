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
 * <p>Phase 28 §28.6 — window scalars come from the sidecar via
 * {@link MathConjectureSubstrateDelegate} (the in-process
 * {@code MathConjectureSubstrate} was removed). The sidecar's sieve covers
 * the full {@link #MAX_N} range; there is no in-process fallback.
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

    /** Maximum integer the sidecar's sieve covers (NT_MAX_N). Held-out windows further out are rejected. */
    public static final int MAX_N = 2_000_000;
    /** Default number of disjoint windows evaluated per call. */
    public static final int DEFAULT_NUM_WINDOWS = 5;
    /** Cap on total integers scanned per verify call (numWindows × windowSize). */
    public static final int MAX_WORK = 100_000;

    /**
     * Config-driven verify caps (the static constants above are the defaults).
     * <ul>
     *   <li>{@code maxWorkCfg} — per-call total-integers budget; a free tuning knob (the
     *       per-integer number-theory eval is in-process, so this bounds one call's cost).</li>
     *   <li>{@code maxNCfg} — the verify window ceiling. This is NOT a free knob: it is the
     *       trusted-oracle / dense-sieve range, coupled to the sidecar's {@code NT_MAX_N}.
     *       {@link #maxN()} clamps it to {@link #MAX_N} so it can be lowered for testing but
     *       never raised past the actual sieve (which would have no ground truth). Raising the
     *       real ceiling requires extending the sidecar sieve and {@link #MAX_N} in lockstep.
     *       It is distinct from the discovery <i>frontier</i> (how far validated computation has
     *       reached <i>beyond</i> the sieve) — see frontier-escalation-loop.md.</li>
     * </ul>
     */
    @org.springframework.beans.factory.annotation.Value("${app.julia.conjecture.maxWork:100000}")
    private int maxWorkCfg = MAX_WORK;
    @org.springframework.beans.factory.annotation.Value("${app.julia.conjecture.maxN:2000000}")
    private int maxNCfg = MAX_N;

    /** Effective per-call work cap (numWindows × windowSize), config-driven. */
    public int maxWork() {
        return Math.max(1, maxWorkCfg);
    }

    /** Effective verify window ceiling — clamped to the sidecar's sieve coverage; never exceeds it. */
    public int maxN() {
        return Math.max(1, Math.min(maxNCfg, MAX_N));
    }

    /**
     * Phase 28 §28.6 — the sidecar-only delegate is the sole source of window
     * scalars and the wrapped JuliaMathClient for the batched verify path.
     * Autowired (kept field-injected to preserve this class's zero-arg
     * @Component construction); tests wire one via {@link #setSubstrateDelegate}.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private MathConjectureSubstrateDelegate substrateDelegate;

    /**
     * Binding-regime auto-probe (room b72c4e10 fix). When enabled, a
     * start-monotone bound (see {@link BindingRegimeProbe}) whose requested
     * windows all HELD gets one extra window evaluated at
     * {@link #boundaryProbeStart} — the smallest-ceiling regime where the
     * bound actually binds. If that window refutes, the overall result flips
     * to REFUTED, so a confirmatory-only trace can no longer be assembled and
     * the professor's own tool result reports the violation. Flag-gated OFF;
     * the field default (false) also keeps non-Spring {@code new
     * ConjectureVerifier()} instances dormant.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.julia.conjecture.boundary-probe-enabled:false}")
    private boolean boundaryProbeEnabled;

    /**
     * Window start used for the binding-regime probe. Defaults to 2,000 — the
     * regime where sibling papers P-b72c-13/14 found genuine refutations,
     * above the degenerate sub-100 noise floor where number-theoretic scalars
     * are too erratic to be representative.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.julia.conjecture.boundary-probe-start:2000}")
    private long boundaryProbeStart = 2000;

    /** Test seam: enable/disable the binding-regime auto-probe without Spring. */
    public void setBoundaryProbeEnabled(boolean enabled) {
        this.boundaryProbeEnabled = enabled;
    }

    /** Test seam: set the binding-regime probe start without Spring. */
    public void setBoundaryProbeStart(long start) {
        this.boundaryProbeStart = start;
    }

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
        return verifyInternal(lhs, rhs, k, c, windowStart, windowSize, numWindows,
                boundaryProbeEnabled);
    }

    /**
     * Phase 30 G2 Stage 2 — two-term verify: {@code lhs <= k*rhs + k2*rhs2 + c} on a held-out slice.
     * Java-side per-window only (the sidecar's batched {@code /conjecture/verify} doesn't know rhs2), so
     * no sidecar round-trip and no boundary probe. Validates all three scalars + the same WORK/RANGE
     * caps as the one-term path. The {@link VerifyResult} carries the primary {@code (rhs, k)}; the
     * {@code k2*rhs2} contribution is folded into each window's margin — the load-bearing verdict number.
     */
    public VerifyResult verify(
            String lhs, String rhs, double k, String rhs2, double k2, double c,
            long windowStart, int windowSize, int numWindows) {
        Objects.requireNonNull(lhs, "lhs");
        Objects.requireNonNull(rhs, "rhs");
        Objects.requireNonNull(rhs2, "rhs2");
        List<String> vocab = MathInvariants.vocabulary();
        if (!vocab.contains(lhs)) {
            throw new IllegalArgumentException("unknown lhs scalar: " + lhs + " (expected one of " + vocab + ")");
        }
        if (!vocab.contains(rhs)) {
            throw new IllegalArgumentException("unknown rhs scalar: " + rhs + " (expected one of " + vocab + ")");
        }
        if (!vocab.contains(rhs2)) {
            throw new IllegalArgumentException("unknown rhs2 scalar: " + rhs2 + " (expected one of " + vocab + ")");
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
        if (totalWork > maxWork()) {
            throw new IllegalArgumentException(
                    "num_windows × window_size = " + totalWork + " exceeds cap of " + maxWork());
        }
        if (windowStart + totalWork > maxN()) {
            throw new IllegalArgumentException(
                    "window extends to n=" + (windowStart + totalWork) + " which exceeds MAX_N=" + maxN());
        }

        MathConjectureSubstrateDelegate sub = substrate();
        List<WindowReport> reports = new ArrayList<>(numWindows);
        double worstMargin = Double.NEGATIVE_INFINITY;
        long worstWindowStart = windowStart;
        boolean held = true;
        for (int i = 0; i < numWindows; i++) {
            long start = windowStart + (long) i * windowSize;
            Map<String, Double> flat = MathInvariants.extract(
                    sub.evaluateWindow((int) start, windowSize), start, windowSize);
            double lhsVal = flat.getOrDefault(lhs, 0.0);
            double rhsVal = flat.getOrDefault(rhs, 0.0);
            double rhs2Val = flat.getOrDefault(rhs2, 0.0);
            double margin = lhsVal - (k * rhsVal + k2 * rhs2Val + c);
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
     * Core verify with an explicit boundary-probe switch so the probe itself
     * (which recurses for a single extra window) never re-triggers a probe.
     */
    private VerifyResult verifyInternal(
            String lhs, String rhs,
            double k, double c,
            long windowStart, int windowSize, int numWindows,
            boolean allowBoundaryProbe) {

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
        if (totalWork > maxWork()) {
            throw new IllegalArgumentException(
                    "num_windows × window_size = " + totalWork + " exceeds cap of " + maxWork());
        }
        long maxEvaluated = windowStart + totalWork;
        if (maxEvaluated > maxN()) {
            throw new IllegalArgumentException(
                    "window extends to n=" + maxEvaluated + " which exceeds MAX_N=" + maxN());
        }

        VerifyResult requested;
        // Phase 28 §28.4 — try the sidecar batched verify path first.
        // One round trip instead of `numWindows` calls to
        // /numbertheory/window. Any failure (transport, bad_request,
        // eval_error) falls back to the per-window path below (also
        // sidecar-backed via the delegate after the §28.6 legacy removal),
        // logged at WARN. There is no in-process fallback: if the sidecar is
        // unavailable the per-window path throws and verify aborts.
        com.emergentterrain.llm.JuliaMathClient sidecarClient = juliaMathClientOrNull();
        if (sidecarClient != null && sidecarClient.isConjectureAvailable()) {
            VerifyResult viaSidecar = null;
            try {
                List<long[]> windows = new ArrayList<>(numWindows);
                for (int i = 0; i < numWindows; i++) {
                    long start = windowStart + (long) i * windowSize;
                    windows.add(new long[]{start, windowSize});
                }
                viaSidecar = sidecarClient.conjectureVerify(lhs, rhs, k, c, windows);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ConjectureVerifier.class).warn(
                        "ConjectureVerifier: sidecar /conjecture/verify failed " +
                        "(lhs={} rhs={} numWindows={}): {} — falling back to " +
                        "per-window /numbertheory/window path",
                        lhs, rhs, numWindows, e.getMessage());
                // Fall through to the per-window path below.
            }
            requested = (viaSidecar != null)
                    ? viaSidecar
                    : evaluatePerWindow(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        } else {
            requested = evaluatePerWindow(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        }

        // Binding-regime auto-probe (room b72c4e10): a start-monotone bound that
        // "held" only because every requested window sat in the comfortable
        // large-start regime gets one extra window at the smallest-ceiling
        // start. A refutation there flips the overall verdict, so the
        // professor's tool result reports REFUTED instead of a false HELD.
        if (allowBoundaryProbe && requested.held()
                && BindingRegimeProbe.shouldProbe(rhs, k, windowStart, boundaryProbeStart)) {
            try {
                VerifyResult probe = verifyInternal(
                        lhs, rhs, k, c, boundaryProbeStart, windowSize, 1, /*allowBoundaryProbe*/ false);
                requested = mergeBoundaryProbe(requested, probe);
            } catch (RuntimeException e) {
                // Fail-safe: the probe is an enhancement, not a correctness-critical
                // path. A probe-window error (e.g. sidecar transport failure, which
                // has no in-process fallback) must not turn a successful verify into a
                // hard failure — keep the requested result unprobed and log.
                org.slf4j.LoggerFactory.getLogger(ConjectureVerifier.class).warn(
                        "ConjectureVerifier: binding-regime probe at start={} failed " +
                        "(lhs={} rhs={}): {} — returning requested result unprobed",
                        boundaryProbeStart, lhs, rhs, e.getMessage());
            }
        }

        return requested;
    }

    /**
     * Phase 30 30.7 P3 — materialise extracted windows (base/derived scalar maps) for mechanism
     * evaluation, via the same sidecar substrate as {@link #verify}. Windows that would extend past
     * {@link #maxN()} are skipped (stay in the trusted-oracle range). Deterministic.
     */
    public List<Map<String, Double>> sampleWindows(long windowStart, int windowSize, int numWindows) {
        MathConjectureSubstrateDelegate sub = substrate();
        List<Map<String, Double>> out = new ArrayList<>(Math.max(0, numWindows));
        for (int i = 0; i < numWindows; i++) {
            long start = windowStart + (long) i * windowSize;
            if (start < 1 || start + (long) windowSize > maxN()) {
                break;
            }
            Map<String, Double> raw = sub.evaluateWindow((int) start, windowSize);
            out.add(MathInvariants.extract(raw, start, windowSize));
        }
        return out;
    }

    /** Per-window evaluation via the sidecar-backed substrate delegate. */
    private VerifyResult evaluatePerWindow(
            String lhs, String rhs, double k, double c,
            long windowStart, int windowSize, int numWindows) {

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
     * Merge a binding-regime probe result into the requested result. The probe
     * window(s) — at a strictly smaller start — are prepended so the per-window
     * detail reads low-to-high start; {@code held} is the conjunction (a probe
     * refutation flips it), and the worst margin is the larger of the two.
     * Package-private + static so it is unit-testable without the sidecar.
     */
    static VerifyResult mergeBoundaryProbe(VerifyResult requested, VerifyResult probe) {
        List<WindowReport> windows = new ArrayList<>(
                probe.windows().size() + requested.windows().size());
        windows.addAll(probe.windows());        // smaller start first
        windows.addAll(requested.windows());

        boolean held = requested.held() && probe.held();
        double worstMargin;
        long worstWindowStart;
        if (probe.worstMargin() > requested.worstMargin()) {
            worstMargin = probe.worstMargin();
            worstWindowStart = probe.worstWindowStart();
        } else {
            worstMargin = requested.worstMargin();
            worstWindowStart = requested.worstWindowStart();
        }
        return new VerifyResult(
                requested.lhs(), requested.rhs(), requested.k(), requested.c(),
                requested.windowsEvaluated() + probe.windowsEvaluated(),
                held, worstMargin, worstWindowStart, windows);
    }

    /**
     * Returns the sidecar client used by the §28.4 batched-verify dispatch.
     * Resolved through the delegate so a single Spring-wired bean powers both
     * the per-window scalars (§28.2) and the batched verify (§28.4). Returns
     * null only when no delegate/client is wired (test slices).
     */
    private com.emergentterrain.llm.JuliaMathClient juliaMathClientOrNull() {
        if (substrateDelegate == null) return null;
        return substrateDelegate.juliaMathClient();
    }

    /**
     * Returns the sidecar-only delegate that serves per-window scalars.
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
