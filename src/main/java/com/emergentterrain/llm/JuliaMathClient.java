package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 22 §22.2 — HTTP client for the {@code julia-math-sidecar}
 * external service. Lets EmergentTerrain delegate symbolic + numeric
 * math evaluation to a long-lived Julia process (Symbolics.jl,
 * Roots.jl, QuadGK.jl, DifferentialEquations.jl) instead of running
 * those calls in-process via Symja.
 *
 * <h2>Lifecycle</h2>
 *
 * Bean always exists. Behaviour gated by two conditions:
 * <ol>
 *   <li>{@code app.julia.enabled} (default {@code false}) — operator
 *       opt-in. When false, every method short-circuits and the caller
 *       falls back to Symja.</li>
 *   <li>Sidecar reachability — at {@link #probeHealth()} startup time
 *       (and on demand thereafter) we GET {@code /healthz}. Failure
 *       sets the {@code reachable} flag to false and logs a single
 *       warning. The flag is sticky-false until a manual
 *       {@link #probeHealth()} call recovers it.</li>
 * </ol>
 *
 * The class is intentionally NOT annotated with
 * {@code @ConditionalOnProperty} — we want the bean to instantiate
 * regardless of {@code enabled} so callers can ask "are you on?"
 * via {@link #isAvailable()} without dealing with optional injection.
 *
 * <h2>API surface</h2>
 *
 * One method per sidecar endpoint:
 * <ul>
 *   <li>{@link #eval(String)} — arbitrary Julia expression</li>
 *   <li>{@link #solve(String, String)} — symbolic root finding</li>
 *   <li>{@link #nsolve(String, String, double)} — numeric root from guess</li>
 *   <li>{@link #integrate(String, String, double, double)} — definite integral</li>
 *   <li>{@link #sweep(String, String, double, double, int)} — stats over a range</li>
 * </ul>
 *
 * Each method returns a typed result record on success and throws
 * {@link JuliaMathException} on any failure (transport, protocol,
 * evaluation error from the sidecar). Callers in {@code MathToolSuite}
 * should catch and fall back to the Symja path so a sidecar outage
 * never blocks a paper.
 *
 * <h2>Thread safety</h2>
 *
 * Stateless except for the {@link #reachable} flag, which is an
 * {@code AtomicBoolean}. Concurrent calls are safe.
 */
@Service
public class JuliaMathClient {

    private static final Logger log = LoggerFactory.getLogger(JuliaMathClient.class);

    /**
     * Operator opt-in. Default false — the Spring bean instantiates
     * and probes on startup, but every call returns immediately with
     * "not enabled" until this flag flips to true.
     */
    private final boolean enabled;

    /** Sidecar base URL. Default localhost:8088 matches start.sh. */
    private final String baseUrl;

    /** Per-call HTTP timeout (sent to the sidecar in the request body). */
    private final long timeoutMs;

    /**
     * HTTP probe timeout for the readiness check. Short on purpose —
     * if /healthz doesn't answer in 2 seconds the sidecar is treated
     * as down and we don't block the JVM startup waiting.
     */
    private final long healthProbeTimeoutMs;

    // ── Phase 24 §24.2 — PySR-specific config (app.julia.pysr.*)
    // Independent enable flag so the operator can flip pysr on/off
    // separately from the main Julia path. When false, isPysrAvailable()
    // returns false and MathToolSuite omits the symbolic_regress tool
    // from toolsJson().
    private final boolean pysrEnabled;
    private final long pysrTimeoutMs;
    private final int  pysrPopulations;
    private final int  pysrIterations;
    private final int  pysrComplexityCeiling;
    // ── Phase 24 §24.6 — Lever 1 + Lever 3 defaults ───────────────
    // Toggled at the JuliaMathClient level so every symbolic_regress
    // call from MathToolSuite picks up the recommended production
    // wiring (autoRetry=true, autoNormalize=false) without each
    // caller having to set it. Per-request PysrRequest overrides
    // still work for tests and the probe.
    private final boolean pysrAutoNormalize;
    private final boolean pysrAutoRetry;

    // ── Phase 28 §28.1 — conjecture-substrate consolidation flag.
    // Independent of the main `enabled` flag so the operator can keep
    // /eval and /sweep on while toggling the substrate-port endpoints
    // off (or vice versa). When false, isConjectureAvailable() returns
    // false; callers (added in §28.2+) take the legacy in-process
    // MathConjectureSubstrate path. §28.1 ships the endpoint + this
    // flag without migrating any caller — that's §28.2.
    private final boolean conjectureEnabled;
    private final long    conjectureTimeoutMs;

    /**
     * Runtime reachability. Set true after a successful /healthz probe.
     * Set false on any /healthz failure OR on the first transport
     * failure of a real call. Stays sticky-false until probeHealth()
     * is invoked manually (no auto-retry timer in 0.1 — re-probe is
     * controlled by the caller).
     */
    private final AtomicBoolean reachable = new AtomicBoolean(false);

    // ── Phase 22 §22.6 — running counters surfaced by JuliaSidecarReporter
    //
    // Updated inside post() on every call path: success bumps okCalls
    // + latency; safety reject bumps safetyRejectCalls (and skips
    // latency since no HTTP happened); transport bumps
    // transportFailCalls (also no latency); other server-side errors
    // (parse_error, eval_error, timeout) bump evalErrorCalls + latency.
    //
    // Atomic longs because handler threads run concurrently — Spring
    // doesn't guarantee single-threaded access through the bean. The
    // counters are running totals; deltas are the reporter's problem.
    private final AtomicLong totalCalls          = new AtomicLong();
    private final AtomicLong okCalls             = new AtomicLong();
    private final AtomicLong safetyRejectCalls   = new AtomicLong();
    private final AtomicLong transportFailCalls  = new AtomicLong();
    private final AtomicLong evalErrorCalls      = new AtomicLong();
    private final AtomicLong latencyMsTotal      = new AtomicLong();
    private final AtomicLong latencyMsMax        = new AtomicLong();

    // ── Phase 24 §24.2 — /pysr-specific counters
    //
    // /pysr calls have a fundamentally different latency profile from
    // /eval (20-second wall-clock vs ~30ms). Folding them into the
    // shared latency counters would average misleadingly. Track them
    // separately so the JuliaSidecarReporter can surface a dedicated
    // pysr line.
    private final AtomicLong pysrCalls           = new AtomicLong();
    private final AtomicLong pysrLatencyMsTotal  = new AtomicLong();
    private final AtomicLong pysrLatencyMsMax    = new AtomicLong();

    private final HttpClient http;
    private final ObjectMapper mapper;

    /**
     * Back-compat overload used by tests that don't care about pysr.
     * Spring will not pick this one — it lacks {@code @Value}
     * annotations — but it stays useful for direct instantiation in
     * test code that pre-dates phase-24's pysr config.
     */
    public JuliaMathClient(boolean enabled, String baseUrl, long timeoutMs,
                            long healthProbeTimeoutMs, ObjectMapper mapper) {
        this(enabled, baseUrl, timeoutMs, healthProbeTimeoutMs,
             /*pysrEnabled*/ false,
             /*pysrTimeoutMs*/ 20_000L,
             /*pysrPopulations*/ 8,
             /*pysrIterations*/ 40,
             /*pysrComplexityCeiling*/ 30,
             /*pysrAutoNormalize*/ false,
             /*pysrAutoRetry*/ true,
             /*conjectureEnabled*/ false,
             /*conjectureTimeoutMs*/ 5_000L,
             mapper);
    }

    /**
     * Back-compat overload that exposes pysr config but pre-dates the
     * phase-28 conjecture flag. Used by tests that opt in to pysr but
     * don't need conjecture wiring.
     */
    public JuliaMathClient(boolean enabled, String baseUrl, long timeoutMs,
                            long healthProbeTimeoutMs,
                            boolean pysrEnabled, long pysrTimeoutMs,
                            int pysrPopulations, int pysrIterations,
                            int pysrComplexityCeiling,
                            ObjectMapper mapper) {
        this(enabled, baseUrl, timeoutMs, healthProbeTimeoutMs,
             pysrEnabled, pysrTimeoutMs, pysrPopulations, pysrIterations,
             pysrComplexityCeiling,
             /*pysrAutoNormalize*/ false,
             /*pysrAutoRetry*/ true,
             /*conjectureEnabled*/ false,
             /*conjectureTimeoutMs*/ 5_000L,
             mapper);
    }

    /**
     * Back-compat overload pre-dating phase-24 §24.6 Lever 1+3 toggles.
     * Same as the Spring canonical sans the {@code pysrAutoNormalize}
     * and {@code pysrAutoRetry} args; fills in the recommended
     * production defaults (autoNormalize=false, autoRetry=true).
     */
    public JuliaMathClient(boolean enabled, String baseUrl, long timeoutMs,
                            long healthProbeTimeoutMs,
                            boolean pysrEnabled, long pysrTimeoutMs,
                            int pysrPopulations, int pysrIterations,
                            int pysrComplexityCeiling,
                            boolean conjectureEnabled, long conjectureTimeoutMs,
                            ObjectMapper mapper) {
        this(enabled, baseUrl, timeoutMs, healthProbeTimeoutMs,
             pysrEnabled, pysrTimeoutMs, pysrPopulations, pysrIterations,
             pysrComplexityCeiling,
             /*pysrAutoNormalize*/ false,
             /*pysrAutoRetry*/ true,
             conjectureEnabled, conjectureTimeoutMs,
             mapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JuliaMathClient(
            @Value("${app.julia.enabled:false}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.julia.timeoutMs:5000}") long timeoutMs,
            @Value("${app.julia.healthProbeTimeoutMs:3000}") long healthProbeTimeoutMs,
            @Value("${app.julia.pysr.enabled:false}") boolean pysrEnabled,
            @Value("${app.julia.pysr.timeoutMs:20000}") long pysrTimeoutMs,
            @Value("${app.julia.pysr.populations:8}") int pysrPopulations,
            @Value("${app.julia.pysr.iterations:40}") int pysrIterations,
            @Value("${app.julia.pysr.complexityCeiling:30}") int pysrComplexityCeiling,
            @Value("${app.julia.pysr.autoNormalize:false}") boolean pysrAutoNormalize,
            @Value("${app.julia.pysr.autoRetry:true}") boolean pysrAutoRetry,
            @Value("${app.julia.conjecture.enabled:false}") boolean conjectureEnabled,
            @Value("${app.julia.conjecture.timeoutMs:5000}") long conjectureTimeoutMs,
            ObjectMapper mapper) {
        this.enabled = enabled;
        // Strip trailing slash so the URL-building below doesn't double-slash.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.timeoutMs = timeoutMs;
        this.healthProbeTimeoutMs = healthProbeTimeoutMs;
        this.pysrEnabled = pysrEnabled;
        this.pysrTimeoutMs = pysrTimeoutMs;
        this.pysrPopulations = pysrPopulations;
        this.pysrIterations = pysrIterations;
        this.pysrComplexityCeiling = pysrComplexityCeiling;
        this.pysrAutoNormalize = pysrAutoNormalize;
        this.pysrAutoRetry = pysrAutoRetry;
        this.conjectureEnabled = conjectureEnabled;
        this.conjectureTimeoutMs = conjectureTimeoutMs;
        this.mapper = mapper;
        // 5-second connect timeout: if we can't even open a TCP socket
        // to the sidecar in 5s, assume it's down and let isAvailable()
        // surface that.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Startup readiness probe. Runs once after Spring wires the bean.
     * Logs the outcome at INFO so operators can grep for whether the
     * Julia path is live or dormant in this JVM run.
     *
     * <p>Why @PostConstruct vs @EventListener(ApplicationReadyEvent):
     * we want the probe to happen ASAP after bean construction so
     * {@code MathToolSuite}'s autowiring sees an accurate
     * {@code reachable} state. @PostConstruct runs before the
     * application context finishes refreshing.
     */
    @PostConstruct
    public void onInit() {
        if (!enabled) {
            log.info("JuliaMathClient: disabled (app.julia.enabled=false) — sidecar will not be used");
            return;
        }
        boolean ok = probeHealth();
        log.info("JuliaMathClient: enabled, baseUrl={}, timeoutMs={}, reachable={}",
                baseUrl, timeoutMs, ok);
    }

    /**
     * Phase 22 §22.7 fixup — periodic re-probe to recover from a
     * transient first-probe failure. The original {@link #onInit}
     * probe runs in the @PostConstruct phase and can race the
     * sidecar's JIT compilation window: if the sidecar happens to be
     * JIT-compiling a previous request when /healthz arrives, the
     * single-threaded HTTP.jl handler queues the request past our
     * probe timeout and we mark `reachable=false`. Before this
     * scheduled re-probe was added, that one timeout pinned the
     * JVM on the Symja fallback for the entire run.
     *
     * <p>Only re-probes when {@code reachable=false} so a healthy
     * client doesn't burn HTTP calls every minute. Once recovered,
     * the next first-call's transport failure would sticky-false
     * again — and this method would recover it on the next tick.
     *
     * <p>Cadence is 60 s — quick enough to recover within one paper
     * if the first probe missed, slow enough that a permanently-down
     * sidecar doesn't fill the log with WARN spam (re-probe failures
     * log at DEBUG when already false, not WARN).
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduledReprobe() {
        if (!enabled) return;
        if (reachable.get()) return;  // already up, nothing to do
        // Quiet re-probe — temporarily downgrade the failure log
        // level so a permanently-down sidecar doesn't spam WARN
        // every 60s. probeHealth() itself emits at WARN level on
        // the first failure; we already logged that during @PostConstruct,
        // and subsequent re-probe failures are noise.
        boolean before = reachable.get();
        boolean now = probeHealth();
        if (now && !before) {
            log.info("JuliaMathClient: sidecar recovered — reachable=true via scheduled re-probe");
        }
    }

    /**
     * Synchronous health probe. Calls {@code GET /healthz} with a
     * short timeout; updates the {@link #reachable} flag based on
     * the outcome. Returns the new flag value.
     *
     * <p>Safe to call repeatedly — used both at startup and ad-hoc by
     * test code or recovery probes. Idempotent.
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
                log.warn("JuliaMathClient: /healthz returned status={} — sidecar marked unreachable",
                        resp.statusCode());
            }
            return ok;
        } catch (Exception e) {
            reachable.set(false);
            // Single-line WARN — no stack trace because "sidecar isn't
            // running" is a normal-and-expected condition in dev.
            log.warn("JuliaMathClient: /healthz probe failed (baseUrl={}): {} — sidecar marked unreachable",
                    baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * True iff the sidecar is both enabled by config and last seen
     * reachable. Callers (MathToolSuite) check this before delegating;
     * a false return is the operator's signal to use the Symja
     * fallback path.
     */
    public boolean isAvailable() {
        return enabled && reachable.get();
    }

    /** True iff config opt-in is set. Decoupled from reachability. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Last-seen reachability. May be stale if {@link #probeHealth()} hasn't been re-called recently. */
    public boolean isReachable() {
        return reachable.get();
    }

    /**
     * Phase 24 §24.2 — PySR is available when (a) the main Julia path
     * is available AND (b) the pysr-specific flag is also flipped. Two
     * flags rather than one so the operator can keep the Julia sidecar
     * active while toggling SR off (e.g. during the recovery-probe
     * validation window in §24.5).
     */
    public boolean isPysrAvailable() {
        return isAvailable() && pysrEnabled;
    }

    /** Whether the pysr-specific flag is on (independent of reachability). */
    public boolean isPysrEnabled() {
        return pysrEnabled;
    }

    /**
     * Phase 28 §28.1 — true iff the main Julia path is available AND
     * the conjecture-specific flag is on. Callers in §28.2+ branch on
     * this to choose between the sidecar endpoint and the legacy
     * in-process `MathConjectureSubstrate`. As of §28.1, no caller
     * branches on it yet — the flag exists so wiring can land in the
     * next sub-phase without re-touching this file.
     */
    public boolean isConjectureAvailable() {
        return isAvailable() && conjectureEnabled;
    }

    /** Whether the conjecture-specific flag is on (independent of reachability). */
    public boolean isConjectureEnabled() {
        return conjectureEnabled;
    }

    // ── Endpoint methods ─────────────────────────────────────────────

    /**
     * POST /eval — evaluate an arbitrary Julia expression.
     *
     * @param expr Native Julia expression. The LLM should emit Julia
     *             syntax ({@code floor(x)}, {@code x^2}, {@code asin(x)})
     *             rather than Mathematica syntax ({@code Floor[x]},
     *             {@code arcsin(x)}). Translation, if desired, happens
     *             upstream — this client passes the string through.
     * @return EvalResult with the stringified value and Julia type.
     * @throws JuliaMathException on transport, protocol, or evaluation failure.
     */
    public EvalResult eval(String expr) {
        JsonNode body = post("/eval", Map.of("expr", expr, "timeoutMs", timeoutMs));
        return new EvalResult(
                body.path("result").asText(),
                body.path("type").asText(),
                body.path("elapsed_ms").asLong(0));
    }

    /**
     * POST /solve — symbolic root finding via Symbolics.jl.
     *
     * @param expr Expression that equals zero (the equation in
     *             {@code expr = 0} form). LHS-only — caller pre-moves
     *             any RHS to the left.
     * @param var  The variable to solve for, e.g. {@code "x"}.
     * @return SolveResult with the list of root expressions, or
     *         {@link SolveResult#symbolicFailed()} if Symbolics.jl
     *         couldn't crack it (caller should retry against /nsolve
     *         with an initial guess).
     */
    public SolveResult solve(String expr, String var) {
        JsonNode body = post("/solve",
                Map.of("expr", expr, "var", var, "timeoutMs", timeoutMs));
        if (body.path("symbolic_failed").asBoolean(false)) {
            return SolveResult.failed();
        }
        JsonNode result = body.path("result");
        List<String> roots = new java.util.ArrayList<>();
        if (result.isArray()) {
            result.forEach(r -> roots.add(r.asText()));
        }
        return new SolveResult(roots, false, body.path("elapsed_ms").asLong(0));
    }

    /**
     * POST /nsolve — numeric root from an initial guess via Roots.jl.
     *
     * @param expr One-arg Julia expression in {@code var}.
     * @param var  Variable name, e.g. {@code "x"}.
     * @param x0   Initial guess; Newton-class methods select automatically.
     * @return NumericRootResult with the converged root.
     */
    public NumericRootResult nsolve(String expr, String var, double x0) {
        JsonNode body = post("/nsolve",
                Map.of("expr", expr, "var", var, "x0", x0, "timeoutMs", timeoutMs));
        return new NumericRootResult(
                body.path("result").asDouble(),
                body.path("elapsed_ms").asLong(0));
    }

    /**
     * POST /integrate — definite integral via QuadGK.jl.
     *
     * @param expr One-arg Julia expression in {@code var}.
     * @param var  Variable of integration, e.g. {@code "x"}.
     * @param a    Lower limit. Numeric literal or symbolic value like
     *             {@code "π"} accepted — the sidecar parses both.
     *             Use a {@code double} for the numeric path; for
     *             symbolic limits like π, prefer {@link #integrateSymbolicLimits}.
     * @param b    Upper limit.
     * @return IntegralResult with the value and an adaptive error estimate.
     */
    public IntegralResult integrate(String expr, String var, double a, double b) {
        JsonNode body = post("/integrate",
                Map.of("expr", expr, "var", var, "a", a, "b", b, "timeoutMs", timeoutMs));
        return new IntegralResult(
                body.path("result").asDouble(),
                body.path("error_estimate").asDouble(),
                body.path("elapsed_ms").asLong(0));
    }

    /**
     * POST /integrate — variant that accepts symbolic limits (e.g.
     * {@code "π"} or {@code "exp(1)"}). The sidecar parses + evaluates
     * the limit strings as Julia expressions.
     */
    public IntegralResult integrateSymbolicLimits(String expr, String var, String a, String b) {
        JsonNode body = post("/integrate",
                Map.of("expr", expr, "var", var, "a", a, "b", b, "timeoutMs", timeoutMs));
        return new IntegralResult(
                body.path("result").asDouble(),
                body.path("error_estimate").asDouble(),
                body.path("elapsed_ms").asLong(0));
    }

    /**
     * POST /sweep — evaluate f(var) across a uniform grid, return
     * summary stats. Equivalent of EmergentTerrain's existing
     * {@code evaluate_symbolic} tool semantics, but with a Julia
     * back end and no statement/loop limits.
     *
     * @param expr    One-arg Julia expression in {@code var}.
     * @param var     Variable name.
     * @param start   Lower bound (inclusive).
     * @param end     Upper bound (inclusive).
     * @param samples Number of grid points; must be in (0, 100_000].
     */
    public SweepResult sweep(String expr, String var, double start, double end, int samples) {
        JsonNode body = post("/sweep",
                Map.of("expr", expr, "var", var,
                        "start", start, "end", end, "samples", samples,
                        "timeoutMs", timeoutMs));
        return new SweepResult(
                body.path("mean").asDouble(),
                body.path("std").asDouble(),
                body.path("min").asDouble(),
                body.path("max").asDouble(),
                body.path("n").asInt(),
                body.path("elapsed_ms").asLong(0));
    }

    /**
     * Phase 24 §24.2 — POST /pysr — symbolic regression over a small
     * data table. Returns a Pareto front of (expression, mse, complexity)
     * candidates, sorted by complexity ascending.
     *
     * <p>Per-call wall-clock is bounded by {@code request.timeoutMs()}
     * (or the {@code app.julia.pysr.timeoutMs} default if zero), capped
     * server-side at 30 s. Concurrent /pysr calls serialise on the
     * sidecar's per-process lock; a second concurrent call may return
     * a {@link JuliaMathException} with code {@code busy} — callers
     * should treat that as a retryable signal.
     *
     * <p>The {@link PysrRequest} carries client-side defaults; any zero
     * / null / empty field falls back to the sidecar's defaults rather
     * than overriding them, so callers can pass partially-populated
     * requests safely.
     *
     * @throws JuliaMathException on transport / safety / eval errors.
     *         Code {@code busy} specifically means another /pysr call
     *         is in progress; the caller may retry.
     */
    public PysrResult pysr(PysrRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PysrRequest cannot be null");
        }
        // Client-side validation — fails fast before the HTTP round-trip
        // for shape errors that the sidecar would also reject. Mirrors
        // the validation in the Julia handle_pysr handler so the error
        // text is friendly.
        if (request.samples() == null || request.samples().length == 0) {
            throw new JuliaMathException("client_safety_rejected",
                    "PysrRequest.samples must be a non-empty array of rows");
        }
        if (request.varNames() == null || request.varNames().isEmpty()) {
            throw new JuliaMathException("client_safety_rejected",
                    "PysrRequest.varNames must be a non-empty list");
        }
        for (String name : request.varNames()) {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new JuliaMathException("client_safety_rejected",
                        "Variable name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
            }
        }
        String targetName = request.targetName() == null || request.targetName().isBlank()
                ? "f" : request.targetName();
        if (!targetName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new JuliaMathException("client_safety_rejected",
                    "Target name '" + targetName + "' must match [A-Za-z_][A-Za-z0-9_]*");
        }
        int expectedCols = request.varNames().size() + 1;
        for (int i = 0; i < request.samples().length; i++) {
            if (request.samples()[i] == null || request.samples()[i].length != expectedCols) {
                throw new JuliaMathException("client_safety_rejected",
                        "samples[" + i + "] must be a numeric row of length "
                                + expectedCols + " (= varNames.size() + 1 target)");
            }
        }

        // Build the body. LinkedHashMap so field order in the wire JSON
        // matches what the sidecar logs prefer (cosmetic, but kind to
        // operators reading log lines side-by-side).
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("samples", request.samples());
        body.put("varNames", request.varNames());
        body.put("targetName", targetName);
        if (request.operators() != null && !request.operators().isEmpty()) {
            body.put("operators", request.operators());
        }
        // Zero / null → fall back to the configured pysr defaults
        // (app.julia.pysr.*). Caller can override per-call by passing
        // a positive value in the request.
        int populations = request.populations() > 0 ? request.populations() : pysrPopulations;
        int iterations  = request.iterations()  > 0 ? request.iterations()  : pysrIterations;
        int complexity  = request.complexityCeiling() > 0
                ? request.complexityCeiling() : pysrComplexityCeiling;
        long callTimeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : pysrTimeoutMs;
        body.put("populations",       populations);
        body.put("iterations",        iterations);
        body.put("complexityCeiling", complexity);
        body.put("timeoutMs",         callTimeoutMs);
        if (request.seed() != null)   body.put("seed", request.seed());
        // Phase 24 §24.6 Levers 1 + 3. Per-request override (non-null
        // PysrRequest field) wins over the client-level configured
        // default; default of defaults is autoRetry=true, autoNormalize=false
        // (recommended production wiring per phase-24 §24.6).
        boolean useAutoNormalize = request.autoNormalize() != null
                ? request.autoNormalize() : pysrAutoNormalize;
        boolean useAutoRetry = request.autoRetry() != null
                ? request.autoRetry() : pysrAutoRetry;
        body.put("autoNormalize", useAutoNormalize);
        body.put("autoRetry",     useAutoRetry);

        long t0 = System.nanoTime();
        JsonNode resp = post("/pysr", body);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        accumulatePysrLatency(elapsedMs);
        pysrCalls.incrementAndGet();

        // Parse the Pareto front.
        java.util.ArrayList<ParetoEntry> entries = new java.util.ArrayList<>();
        JsonNode pareto = resp.path("pareto");
        if (pareto.isArray()) {
            pareto.forEach(node -> entries.add(new ParetoEntry(
                    node.path("expr").asText(""),
                    node.path("mse").asDouble(Double.POSITIVE_INFINITY),
                    node.path("complexity").asInt(0))));
        }
        return new PysrResult(
                java.util.List.copyOf(entries),
                resp.path("evaluated").asInt(0),
                resp.path("elapsed_ms").asLong(0),
                resp.path("retried").asBoolean(false));
    }

    /**
     * Phase 28 §28.1 — POST /numbertheory/window — per-window scalar
     * aggregate over integers in {@code [start, start+size)}. Port of
     * {@code MathConjectureSubstrate.evaluateWindow}.
     *
     * <p>The sidecar builds an SPF sieve lazily on first call and
     * shares it across subsequent calls (one ~50ms build per JVM run,
     * sub-ms per call after).
     *
     * <p>Returns a {@link NumberTheoryWindow} carrying an ordered
     * {@link java.util.LinkedHashMap} of scalar name → value. Key
     * ordering matches the JVM legacy implementation exactly so the
     * §28.2 parity test can compare by sequence rather than by set.
     *
     * <p>This method does <strong>not</strong> consult the
     * {@link #isConjectureAvailable()} flag — that's the caller's
     * responsibility. We always honour the call when the main Julia
     * path is reachable; the flag governs whether <em>application
     * code</em> chooses to make the call.
     *
     * @param start window start (inclusive); must be ≥ 0.
     * @param size  window size; must be &gt; 0.
     * @throws JuliaMathException on transport, sidecar error
     *         (`bad_request`, `eval_error`, `timeout`), or
     *         protocol-level errors. Also thrown when the main Julia
     *         path is disabled (`not_enabled`).
     */
    public NumberTheoryWindow numberTheoryWindow(int start, int size) {
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0; got " + start);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0; got " + size);
        }
        JsonNode resp = post("/numbertheory/window",
                Map.of("start", start, "size", size, "timeoutMs", conjectureTimeoutMs));
        // Preserve the wire order. The sidecar emits each entry as a
        // single-key JSON object — `[{"abundant_density":0.24},
        // {"deficient_density":0.76}, ...]` — because Julia's JSON3
        // serializes `Vector{Pair{String,Float64}}` that way. Order
        // matches Julia insertion order, which is the §28.2 parity
        // contract. We also tolerate the legacy 2-element-array shape
        // (`[["key", val], ...]`) so a future sidecar version that
        // switches to that wire format doesn't break this client.
        java.util.LinkedHashMap<String, Double> scalars = new java.util.LinkedHashMap<>();
        JsonNode result = resp.path("result");
        if (result.isArray()) {
            for (JsonNode pair : result) {
                if (pair.isObject() && pair.size() == 1) {
                    // Current shape: {"key": value}
                    String key = pair.fieldNames().next();
                    scalars.put(key, pair.path(key).asDouble());
                } else if (pair.isArray() && pair.size() == 2) {
                    // Legacy shape: ["key", value]
                    scalars.put(pair.get(0).asText(), pair.get(1).asDouble());
                }
            }
        }
        return new NumberTheoryWindow(
                scalars,
                resp.path("start").asInt(start),
                resp.path("size").asInt(size),
                resp.path("elapsed_ms").asLong(0));
    }

    /**
     * Phase 28 §28.3 — POST /conjecture/seed. Port of the JVM-side
     * {@link com.emergentterrain.research.conjecture.ConjectureEngine#seed}.
     * Returns a list of {@link com.emergentterrain.research.conjecture.Inequality}
     * matching the same wire shape as the in-process call's output —
     * the §28.3 parity test pins byte-identical scalar comparisons.
     *
     * @param samples    list of {name -> value} maps (one per
     *                   sample/window/tick)
     * @param vocabulary the conjecture-eligible scalar names
     */
    public java.util.List<com.emergentterrain.research.conjecture.Inequality>
            conjectureSeed(java.util.List<java.util.Map<String, Double>> samples,
                            java.util.List<String> vocabulary) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("samples", samples);
        body.put("vocabulary", vocabulary);
        JsonNode resp = post("/conjecture/seed", body);
        return parseInequalities(resp.path("inequalities"));
    }

    /**
     * Phase 28 §28.3 — POST /conjecture/update. Port of the JVM-side
     * {@link com.emergentterrain.research.conjecture.ConjectureEngine#update}.
     * Returns an {@link UpdateResultWire} carrying the new surviving
     * store + the retired items + the confirmed/retired counts. Unlike
     * the in-process JVM call (which mutates {@code store} in place),
     * this returns the new store list — callers should replace their
     * local copy with {@code result.store()}.
     */
    public UpdateResultWire conjectureUpdate(
            java.util.List<com.emergentterrain.research.conjecture.Inequality> store,
            String runKey,
            String configHash,
            java.util.List<java.util.Map<String, Double>> samples) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("store",      store.stream().map(this::serializeInequality).toList());
        body.put("runKey",     runKey);
        body.put("configHash", configHash);
        body.put("samples",    samples);
        JsonNode resp = post("/conjecture/update", body);
        return new UpdateResultWire(
                resp.path("confirmed").asInt(0),
                resp.path("retired").asInt(0),
                parseInequalities(resp.path("retiredItems")),
                parseInequalities(resp.path("store")));
    }

    /**
     * Phase 28 §28.3 — JSON-array → {@code List<Inequality>}. Each
     * entry is reconstructed via the {@code Inequality.fromFields}
     * factory so the post-construction mutable state ends up
     * identical to the in-process path.
     */
    private java.util.List<com.emergentterrain.research.conjecture.Inequality>
            parseInequalities(JsonNode arr) {
        java.util.List<com.emergentterrain.research.conjecture.Inequality> out =
                new java.util.ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode node : arr) {
            String lhs = node.path("lhs").asText();
            String rhs = node.path("rhs").asText();
            double k = node.path("k").asDouble();
            double c = node.path("c").asDouble();
            int confirmedRuns = node.path("confirmedRuns").asInt(0);
            java.util.List<String> confirmedConfigs = new java.util.ArrayList<>();
            JsonNode ccArr = node.path("confirmedConfigs");
            if (ccArr.isArray()) {
                for (JsonNode v : ccArr) confirmedConfigs.add(v.asText());
            }
            java.util.List<com.emergentterrain.research.conjecture.Witness> refutedBy =
                    new java.util.ArrayList<>();
            JsonNode rArr = node.path("refutedBy");
            if (rArr.isArray()) {
                for (JsonNode w : rArr) {
                    refutedBy.add(new com.emergentterrain.research.conjecture.Witness(
                            w.path("runKey").asText(),
                            w.path("tick").asLong(),
                            w.path("margin").asDouble()));
                }
            }
            double p99Slack = node.path("p99Slack").asDouble(0.0);
            java.util.Map<String, Double> slackByConfig = new java.util.LinkedHashMap<>();
            JsonNode sbc = node.path("slackByConfig");
            if (sbc.isObject()) {
                sbc.fields().forEachRemaining(e ->
                        slackByConfig.put(e.getKey(), e.getValue().asDouble(0.0)));
            }
            String droppedByKnob = node.has("droppedByKnob") && !node.get("droppedByKnob").isNull()
                    ? node.path("droppedByKnob").asText() : null;
            out.add(com.emergentterrain.research.conjecture.Inequality.fromFields(
                    lhs, rhs, k, c, confirmedRuns, confirmedConfigs,
                    refutedBy, p99Slack, slackByConfig, droppedByKnob));
        }
        return out;
    }

    /**
     * Serialise an Inequality to the wire shape the sidecar expects.
     * Mirrors {@link #parseInequalities} in reverse — same field
     * names, same nullability semantics for {@code droppedByKnob}.
     */
    private Map<String, Object> serializeInequality(
            com.emergentterrain.research.conjecture.Inequality ineq) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lhs", ineq.lhs());
        m.put("rhs", ineq.rhs());
        m.put("k", ineq.k());
        m.put("c", ineq.c());
        m.put("confirmedRuns", ineq.confirmedRuns());
        m.put("confirmedConfigs", ineq.confirmedConfigs());
        java.util.List<Map<String, Object>> witnesses = new java.util.ArrayList<>();
        for (com.emergentterrain.research.conjecture.Witness w : ineq.refutedBy()) {
            java.util.LinkedHashMap<String, Object> wm = new java.util.LinkedHashMap<>();
            wm.put("runKey", w.runKey());
            wm.put("tick", w.tick());
            wm.put("margin", w.margin());
            witnesses.add(wm);
        }
        m.put("refutedBy", witnesses);
        m.put("p99Slack", ineq.p99Slack());
        m.put("slackByConfig", ineq.slackByConfig());
        m.put("droppedByKnob", ineq.droppedByKnob());
        return m;
    }

    /**
     * Result envelope for {@link #conjectureUpdate}. Carries the new
     * surviving store (which the caller swaps in for their local list),
     * the retired entries (with their final witness state), and the
     * confirmed / retired counts that mirror the in-process
     * {@code ConjectureEngine.UpdateResult}.
     */
    public record UpdateResultWire(
            int confirmed,
            int retired,
            java.util.List<com.emergentterrain.research.conjecture.Inequality> retiredItems,
            java.util.List<com.emergentterrain.research.conjecture.Inequality> store) {}

    // ── Internal HTTP plumbing ───────────────────────────────────────

    /**
     * Send a POST with a JSON body to {@code path} and return the
     * parsed response. Translates HTTP / parse / sidecar-error states
     * into {@link JuliaMathException} so callers don't have to switch
     * on multiple exception types.
     *
     * <p>Mutates {@link #reachable} on transport failure — sets it
     * false so subsequent {@link #isAvailable()} calls return false
     * without making another round-trip. Recovery requires a manual
     * {@link #probeHealth()} call.
     */
    private JsonNode post(String path, Map<String, Object> body) {
        totalCalls.incrementAndGet();
        if (!enabled) {
            throw new JuliaMathException("not_enabled",
                    "JuliaMathClient is disabled (app.julia.enabled=false); cannot call " + path);
        }
        // Phase 22 §22.3 — JVM-side safety filter (defense in depth).
        // Scan every string-valued field in the body that the sidecar
        // will eval (`expr`, symbolic limits `a` / `b` when string-form,
        // anything else with structural code). Numeric fields, the
        // `var` name, and `timeoutMs` aren't run through it because
        // they can't carry executable code in the contract.
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (!(val instanceof String s)) continue;
            // `var` is a Julia identifier (alphanumeric + underscore)
            // and gets used as a parameter name, not eval'd as code.
            // Skip the filter; validate identifier shape instead.
            // `targetName` (phase-24 /pysr) is the same shape.
            if ("var".equals(key) || "targetName".equals(key)) {
                if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new JuliaMathException("client_safety_rejected",
                            "Field '" + key + "' must be a Julia identifier "
                                    + "([A-Za-z_][A-Za-z0-9_]*); got: " + truncate(s, 40));
                }
                continue;
            }
            // For everything else (expr, a, b string-form), scan for
            // forbidden tokens before we serialise the request.
            String bad = JuliaSafetyFilter.firstForbiddenPattern(s);
            if (bad != null) {
                safetyRejectCalls.incrementAndGet();
                throw new JuliaMathException("client_safety_rejected",
                        "Client-side safety filter rejected " + key + " containing '"
                                + bad + "' (forbidden token). Sidecar would have rejected "
                                + "too — short-circuiting before HTTP. Truncated input: "
                                + truncate(s, 80));
            }
        }
        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new JuliaMathException("request_serialise", "Failed to serialise request body", e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs + 1_000))  // HTTP timeout > sidecar timeout
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        long t0 = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Transport failure — sidecar unreachable, timeout at HTTP
            // layer, etc. Mark sticky-unreachable so callers stop
            // retrying without a probe.
            reachable.set(false);
            transportFailCalls.incrementAndGet();
            throw new JuliaMathException("transport",
                    "Sidecar unreachable at " + baseUrl + path + ": " + e.getMessage(), e);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        // Record latency for any call that actually made it onto the
        // wire — covers ok, eval_error, parse_error, timeout, etc.
        // Safety/transport rejects are recorded above (zero-latency).
        accumulateLatency(elapsedMs);
        // Parse the JSON envelope. Sidecar returns JSON for both 2xx
        // and 4xx/5xx, so we parse first and inspect the `ok` field.
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            evalErrorCalls.incrementAndGet();
            throw new JuliaMathException("response_parse",
                    "Sidecar returned non-JSON body (status=" + resp.statusCode() + "): "
                            + truncate(resp.body(), 200), e);
        }
        boolean ok = root.path("ok").asBoolean(false);
        if (!ok) {
            evalErrorCalls.incrementAndGet();
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            throw new JuliaMathException(code,
                    "Sidecar returned error '" + code + "': " + explanation);
        }
        okCalls.incrementAndGet();
        return root;
    }

    /**
     * Bump {@code latencyMsTotal} (cumulative) and {@code latencyMsMax}
     * (running peak). Both are running totals — the
     * {@link JuliaSidecarReporter} computes deltas across successive
     * snapshots so each log line reflects the most-recent 5-minute
     * window.
     */
    private void accumulateLatency(long elapsedMs) {
        latencyMsTotal.addAndGet(elapsedMs);
        // AtomicLong has no direct max(); CAS until it sticks.
        long prev;
        do {
            prev = latencyMsMax.get();
            if (elapsedMs <= prev) return;
        } while (!latencyMsMax.compareAndSet(prev, elapsedMs));
    }

    /**
     * Mirror of {@link #accumulateLatency(long)} for /pysr-specific
     * counters. /pysr wall-clock spans seconds vs /eval's milliseconds;
     * tracking the two separately keeps avg-latency readings meaningful
     * for each endpoint family.
     */
    private void accumulatePysrLatency(long elapsedMs) {
        pysrLatencyMsTotal.addAndGet(elapsedMs);
        long prev;
        do {
            prev = pysrLatencyMsMax.get();
            if (elapsedMs <= prev) return;
        } while (!pysrLatencyMsMax.compareAndSet(prev, elapsedMs));
    }

    // ── Phase 22 §22.6 — snapshot for JuliaSidecarReporter ──────────

    /**
     * Snapshot the running counters. Values are cumulative since the
     * JVM started — callers (the reporter) subtract their last
     * snapshot to compute deltas for the current heartbeat window.
     */
    public SidecarStats snapshot() {
        long calls = okCalls.get();
        long total = latencyMsTotal.get();
        double avg = calls == 0 ? 0.0 : (double) total / calls;
        long pysrN = pysrCalls.get();
        long pysrTotal = pysrLatencyMsTotal.get();
        double pysrAvg = pysrN == 0 ? 0.0 : (double) pysrTotal / pysrN;
        return new SidecarStats(
                totalCalls.get(),
                calls,
                safetyRejectCalls.get(),
                evalErrorCalls.get(),
                transportFailCalls.get(),
                total,
                latencyMsMax.get(),
                avg,
                pysrN,
                pysrTotal,
                pysrLatencyMsMax.get(),
                pysrAvg);
    }

    /**
     * Cumulative counters surfaced to {@link JuliaSidecarReporter}.
     * All counts are since JVM start. {@code latencyMsTotal} +
     * {@code latencyMsMax} cover only calls that made it onto the
     * wire (ok / eval_error / parse_error / timeout — but not
     * safety-rejected or transport-failed calls, which short-circuit
     * before HTTP).
     *
     * <p>{@code pysrCalls} / {@code pysrLatencyMs*} (phase-24 §24.2)
     * are a parallel set for /pysr only. /pysr calls also bump
     * {@code totalCalls} / {@code okCalls} / shared latency — the
     * /pysr-specific fields exist so the reporter can break out a
     * dedicated heartbeat line without averaging /pysr's seconds-scale
     * latency against /eval's milliseconds.
     */
    public record SidecarStats(
            long totalCalls,
            long okCalls,
            long safetyRejectCalls,
            long evalErrorCalls,
            long transportFailCalls,
            long latencyMsTotal,
            long latencyMsMax,
            double latencyMsAvg,
            long pysrCalls,
            long pysrLatencyMsTotal,
            long pysrLatencyMsMax,
            double pysrLatencyMsAvg) {}

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ── Result records ───────────────────────────────────────────────

    /** Result of {@link #eval(String)}. */
    public record EvalResult(String result, String type, long elapsedMs) {}

    /**
     * Result of {@link #solve(String, String)}. When
     * {@link #symbolicFailed()} is true, {@code roots} is empty and
     * the caller should retry against {@code /nsolve} with an initial
     * guess.
     */
    public record SolveResult(List<String> roots, boolean symbolicFailed, long elapsedMs) {
        /** Factory for the "Symbolics.jl couldn't crack it" response. */
        public static SolveResult failed() {
            return new SolveResult(List.of(), true, 0);
        }
    }

    /** Result of {@link #nsolve(String, String, double)}. */
    public record NumericRootResult(double root, long elapsedMs) {}

    /**
     * Result of {@link #integrate}. {@code errorEstimate} is QuadGK's
     * adaptive error bound — caller decides whether to tighten or
     * accept based on the integrand's smoothness.
     */
    public record IntegralResult(double value, double errorEstimate, long elapsedMs) {}

    /** Result of {@link #sweep}. */
    public record SweepResult(double mean, double std, double min, double max,
                              int n, long elapsedMs) {}

    // ── Phase 24 §24.2 — /pysr request + result records ──────────────

    /**
     * Request shape for {@link #pysr(PysrRequest)}.
     *
     * <p>Zero / null / empty fields fall back to the sidecar's defaults
     * (populations=8, iterations=40, complexityCeiling=30, timeoutMs
     * from the client's {@code app.julia.timeoutMs}, seed derived from
     * the input hash). This lets callers populate only the knobs they
     * care about.
     *
     * @param samples           N rows × (varNames.size() + 1) columns.
     *                          Last column is the target value.
     * @param varNames          Variable names; baked into the printed
     *                          expressions. Each must match
     *                          {@code [A-Za-z_][A-Za-z0-9_]*}.
     * @param targetName        Target variable name; cosmetic, used in
     *                          error messages. Defaults to {@code "f"}
     *                          when null/blank.
     * @param operators         Subset of the allowlist. Null/empty →
     *                          sidecar default of arithmetic + exp/log.
     * @param populations       Multi-population GP population count.
     * @param iterations        Iterations per population.
     * @param complexityCeiling Max expression complexity; the search
     *                          will not propose anything beyond this.
     * @param timeoutMs         Per-call wall-clock cap in ms. Clamped
     *                          server-side at 30 s.
     * @param seed              Deterministic seed; null → server picks
     *                          one from hash of inputs.
     */
    public record PysrRequest(
            double[][] samples,
            java.util.List<String> varNames,
            String targetName,
            java.util.List<String> operators,
            int populations,
            int iterations,
            int complexityCeiling,
            long timeoutMs,
            Long seed,
            /**
             * Phase 24 §24.6 Lever 1 toggle. {@code null} → fall back to
             * the client's configured {@code app.julia.pysr.autoNormalize}
             * default. Non-null → per-request override.
             */
            Boolean autoNormalize,
            /**
             * Phase 24 §24.6 Lever 3 toggle. {@code null} → fall back to
             * the client's configured {@code app.julia.pysr.autoRetry}
             * default. Non-null → per-request override.
             */
            Boolean autoRetry) {
        /**
         * Back-compat constructor pre-dating phase-24 §24.6. Leaves both
         * Lever toggles at the client's configured defaults.
         */
        public PysrRequest(double[][] samples,
                           java.util.List<String> varNames,
                           String targetName,
                           java.util.List<String> operators,
                           int populations,
                           int iterations,
                           int complexityCeiling,
                           long timeoutMs,
                           Long seed) {
            this(samples, varNames, targetName, operators, populations,
                 iterations, complexityCeiling, timeoutMs, seed,
                 /*autoNormalize*/ null, /*autoRetry*/ null);
        }
    }

    /**
     * Pareto-front entry: a candidate expression with its mean-squared
     * error against the training data and a symbolic-tree complexity.
     * Lower mse + lower complexity is the trade-off frontier.
     */
    public record ParetoEntry(String expression, double mse, int complexity) {}

    /**
     * Result of {@link #pysr(PysrRequest)}. {@code pareto} is sorted by
     * complexity ascending — the first entry is the simplest law the
     * search found; later entries fit better at the cost of complexity.
     */
    public record PysrResult(
            java.util.List<ParetoEntry> pareto,
            int evaluated,
            long elapsedMs,
            /**
             * Phase 24 §24.6 Lever 3 signal: true when the sidecar's
             * autoRetry path fired (first pass looked stuck, second
             * pass ran with autoNormalize=true and merged into pareto).
             * False otherwise. Surfaced so the LLM caller can hint
             * "we recovered this via retry" in user-visible output.
             */
            boolean retried) {
        /** Back-compat constructor pre-dating phase-24 §24.6 retried field. */
        public PysrResult(java.util.List<ParetoEntry> pareto,
                          int evaluated,
                          long elapsedMs) {
            this(pareto, evaluated, elapsedMs, /*retried*/ false);
        }
    }

    // ── Phase 28 §28.1 — /numbertheory/window result ─────────────────

    /**
     * Result of {@link #numberTheoryWindow(int, int)}. {@code scalars}
     * is an ordered map (insertion order matches the wire order, which
     * in turn matches the JVM legacy
     * {@code MathConjectureSubstrate.evaluateWindow} key order) of
     * scalar name → value for the requested window. {@code start} and
     * {@code size} are echoed back so the caller doesn't need to track
     * them across the round-trip.
     */
    public record NumberTheoryWindow(
            java.util.LinkedHashMap<String, Double> scalars,
            int start,
            int size,
            long elapsedMs) {}
}
