package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Runtime reachability. Set true after a successful /healthz probe.
     * Set false on any /healthz failure OR on the first transport
     * failure of a real call. Stays sticky-false until probeHealth()
     * is invoked manually (no auto-retry timer in 0.1 — re-probe is
     * controlled by the caller).
     */
    private final AtomicBoolean reachable = new AtomicBoolean(false);

    private final HttpClient http;
    private final ObjectMapper mapper;

    public JuliaMathClient(
            @Value("${app.julia.enabled:false}") boolean enabled,
            @Value("${app.julia.baseUrl:http://localhost:8088}") String baseUrl,
            @Value("${app.julia.timeoutMs:5000}") long timeoutMs,
            @Value("${app.julia.healthProbeTimeoutMs:2000}") long healthProbeTimeoutMs,
            ObjectMapper mapper) {
        this.enabled = enabled;
        // Strip trailing slash so the URL-building below doesn't double-slash.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.timeoutMs = timeoutMs;
        this.healthProbeTimeoutMs = healthProbeTimeoutMs;
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
        if (!enabled) {
            throw new JuliaMathException("not_enabled",
                    "JuliaMathClient is disabled (app.julia.enabled=false); cannot call " + path);
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
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Transport failure — sidecar unreachable, timeout at HTTP
            // layer, etc. Mark sticky-unreachable so callers stop
            // retrying without a probe.
            reachable.set(false);
            throw new JuliaMathException("transport",
                    "Sidecar unreachable at " + baseUrl + path + ": " + e.getMessage(), e);
        }
        // Parse the JSON envelope. Sidecar returns JSON for both 2xx
        // and 4xx/5xx, so we parse first and inspect the `ok` field.
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new JuliaMathException("response_parse",
                    "Sidecar returned non-JSON body (status=" + resp.statusCode() + "): "
                            + truncate(resp.body(), 200), e);
        }
        boolean ok = root.path("ok").asBoolean(false);
        if (!ok) {
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            throw new JuliaMathException(code,
                    "Sidecar returned error '" + code + "': " + explanation);
        }
        return root;
    }

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
}
