package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 25 §25.2 — HTTP client for the {@code julia-math-sidecar}'s
 * {@code /prove_identity} endpoint. Peer of {@link JuliaMathClient}'s
 * pysr() — same sidecar, different route, separate Spring bean so
 * the failure surface and config knobs stay decoupled.
 *
 * <p>Per the 2026-05-22 Julia-consolidation decision in
 * {@code reference-conjecture-engines.md}, SymPy is hosted *inside*
 * the sidecar (via PyCall.jl), not as a JVM-spawned Python subprocess.
 * This client is the HTTP wire to that path — same shape as the
 * other sidecar clients.
 *
 * <h2>Lifecycle</h2>
 *
 * Bean always exists; behaviour gated by:
 * <ol>
 *   <li>{@code app.julia.sympy.enabled} (default false) — operator
 *       opt-in. When false, every call short-circuits with
 *       {@code "not_enabled"}.</li>
 *   <li>py-sidecar reachability — delegated to {@link PySidecarHealth}'s
 *       probe (the PyCall endpoints live only in the single-threaded
 *       py-sidecar, not the PyCall-free main server). If it reports the
 *       py-sidecar up, we can call /prove_identity on it.</li>
 * </ol>
 *
 * <h2>Safety</h2>
 *
 * Caller-supplied strings ({@code summand}, {@code lower},
 * {@code upper}, optional {@code target}) pass through
 * {@link SympyInputFilter} before serialisation. The filter mirrors
 * the sidecar's {@code SYMPY_FORBIDDEN} so a malicious input is
 * rejected at the JVM boundary, not after a wasted round-trip.
 *
 * <h2>Thread safety</h2>
 *
 * Stateless except for counters and the shared HttpClient; safe under
 * concurrent calls. Server-side {@code PROVE_IDENTITY_LOCK} (5s queue)
 * serialises overlapping calls and surfaces {@code "busy"} on
 * contention — caller decides whether to retry.
 */
@Service
public class SympyIdentityClient {

    private static final Logger log = LoggerFactory.getLogger(SympyIdentityClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final long timeoutMs;

    /**
     * Reachability authority for the py-sidecar that actually serves
     * {@code /prove_identity} (the main server is PyCall-free). One shared
     * probe loop across the PyCall clients; also the source of the base URL
     * we POST to.
     */
    private final PySidecarHealth pySidecarHealth;

    private final ObjectMapper mapper;
    private final HttpClient http;

    // Counters surfaced through a future SympyIdentityClient-aware
    // JuliaSidecarReporter update; for now operators can read them
    // via the Spring actuator or the stats() accessor.
    private final AtomicLong totalCalls          = new AtomicLong();
    private final AtomicLong provedCalls         = new AtomicLong();
    private final AtomicLong noClosedFormCalls   = new AtomicLong();
    private final AtomicLong safetyRejectCalls   = new AtomicLong();
    private final AtomicLong transportFailCalls  = new AtomicLong();
    private final AtomicLong evalErrorCalls      = new AtomicLong();
    private final AtomicLong latencyMsTotal      = new AtomicLong();
    private final AtomicLong latencyMsMax        = new AtomicLong();

    /**
     * Canonical constructor. Spring uses it via the {@code @Value}
     * annotations; tests construct it directly with literal args
     * (the annotations are erased at the call site).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SympyIdentityClient(
            @Value("${app.julia.sympy.enabled:false}") boolean enabled,
            @Value("${app.julia.sympy.timeoutMs:10000}") long timeoutMs,
            PySidecarHealth pySidecarHealth,
            ObjectMapper mapper) {
        this.enabled = enabled;
        // The py-sidecar URL is owned by PySidecarHealth (already slash-stripped),
        // so the URL we POST to and the URL we health-probe can't diverge.
        this.baseUrl = pySidecarHealth.baseUrl();
        this.timeoutMs = timeoutMs;
        this.pySidecarHealth = pySidecarHealth;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Whether the configured flag is on. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether prove_identity can be called: flag on AND the py-sidecar
     * (per PySidecarHealth's probe) is up.
     */
    public boolean isAvailable() {
        return enabled && pySidecarHealth.isReachable();
    }

    /**
     * Phase 25 §25.2 — POST /prove_identity.
     *
     * <p>Inputs match the sidecar's contract one-to-one. {@code algorithm}
     * is one of {@code "gosper"}, {@code "zeilberger_wz"},
     * {@code "sister_celine"}, or {@code "auto"} (tries Gosper first,
     * falls back to WZ). {@code target} is optional — when provided
     * the sidecar can compare a found closed form against the
     * caller's expected answer; for the v1 slice it's accepted but
     * not exercised by the dispatcher.
     *
     * @throws SympyIdentityException with {@code errorCode()} reflecting
     *         the sidecar's response envelope.
     * @throws IllegalArgumentException for obviously-bad inputs that
     *         would be caught by the sidecar's {@code bad_request}
     *         anyway (null/empty required fields, bad var identifier).
     */
    public IdentityProof prove(String algorithm, String summand, String var,
                                String lower, String upper,
                                Optional<String> target) {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm is required");
        }
        if (summand == null || summand.isBlank()
                || var == null || var.isBlank()
                || lower == null || lower.isBlank()
                || upper == null || upper.isBlank()) {
            throw new IllegalArgumentException(
                    "summand, var, lower, upper are all required");
        }
        if (!var.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "var '" + var + "' must match [A-Za-z_][A-Za-z0-9_]*");
        }

        totalCalls.incrementAndGet();

        if (!enabled) {
            throw new SympyIdentityException("not_enabled",
                    "SympyIdentityClient is disabled (app.julia.sympy.enabled=false)");
        }

        // Pre-flight safety — short-circuits before any HTTP cost.
        for (String[] field : new String[][]{
                {"summand", summand}, {"lower", lower}, {"upper", upper},
                {"target", target.orElse(null)}}) {
            String fieldVal = field[1];
            if (fieldVal == null) continue;
            String violation = SympyInputFilter.firstViolation(fieldVal);
            if (violation != null) {
                safetyRejectCalls.incrementAndGet();
                throw new SympyIdentityException("safety_reject",
                        "Client-side filter rejected " + field[0] + ": " + violation);
            }
        }

        // Build the request body. LinkedHashMap so field order in the
        // wire JSON matches the sidecar's logs side-by-side.
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("algorithm", algorithm);
        body.put("summand",   summand);
        body.put("var",       var);
        body.put("lower",     lower);
        body.put("upper",     upper);
        target.ifPresent(t -> body.put("target", t));
        body.put("timeoutMs", timeoutMs);

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new SympyIdentityException("request_serialise",
                    "Failed to serialise /prove_identity request body", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/prove_identity"))
                .timeout(Duration.ofMillis(timeoutMs + 5_000))  // HTTP > sidecar advisory cap
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        long t0 = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            transportFailCalls.incrementAndGet();
            throw new SympyIdentityException("transport",
                    "Sidecar unreachable at " + baseUrl + "/prove_identity: "
                            + e.getMessage(), e);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        accumulateLatency(elapsedMs);

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            evalErrorCalls.incrementAndGet();
            throw new SympyIdentityException("response_parse",
                    "Sidecar returned non-JSON body (status=" + resp.statusCode()
                            + "): " + truncate(resp.body(), 200), e);
        }

        // Envelope check.
        boolean ok = root.path("ok").asBoolean(false);
        if (!ok) {
            String code = root.path("error").asText("unknown");
            String explanation = root.path("explanation").asText("(no explanation)");
            evalErrorCalls.incrementAndGet();
            throw new SympyIdentityException(code,
                    "/prove_identity returned ok=false: " + code + ": " + explanation);
        }

        String status = root.path("status").asText("");
        String result = root.has("result") && !root.get("result").isNull()
                ? root.path("result").asText("") : null;
        String certificate = root.has("certificate") && !root.get("certificate").isNull()
                ? root.path("certificate").asText("") : null;
        String algorithmUsed = root.path("algorithm_used").asText(algorithm);
        long sidecarElapsedMs = root.path("elapsed_ms").asLong(elapsedMs);

        Status parsedStatus = switch (status) {
            case "proved"           -> { provedCalls.incrementAndGet(); yield Status.PROVED; }
            case "recurrence"       -> Status.RECURRENCE;
            case "no_closed_form"   -> { noClosedFormCalls.incrementAndGet(); yield Status.NO_CLOSED_FORM; }
            default                 -> Status.UNKNOWN;
        };

        return new IdentityProof(parsedStatus, result, certificate,
                algorithmUsed, sidecarElapsedMs);
    }

    private void accumulateLatency(long elapsedMs) {
        latencyMsTotal.addAndGet(elapsedMs);
        long prev;
        do {
            prev = latencyMsMax.get();
            if (elapsedMs <= prev) return;
        } while (!latencyMsMax.compareAndSet(prev, elapsedMs));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── Records ─────────────────────────────────────────────────────

    /** Outcome of a /prove_identity call. */
    public enum Status {
        /** SymPy returned a closed form + verified certificate. */
        PROVED,
        /** SymPy returned a polynomial recurrence (Zeilberger output). */
        RECURRENCE,
        /** SymPy couldn't find a closed form; the result is honestly absent. */
        NO_CLOSED_FORM,
        /** Sidecar returned an unrecognised status string. */
        UNKNOWN
    }

    /**
     * Result of a successful (envelope ok=true) /prove_identity call.
     *
     * @param status         classification of the outcome
     * @param result         closed form string when status is PROVED,
     *                       recurrence string when RECURRENCE, null otherwise
     * @param certificate    the WZ rational / Gosper antidifference /
     *                       reduction-chain string; non-null when status
     *                       is PROVED or RECURRENCE
     * @param algorithmUsed  which dispatch arm produced the result
     *                       ("gosper" / "zeilberger_wz" / "sister_celine")
     * @param wallMs         sidecar-reported elapsed time
     */
    public record IdentityProof(
            Status status,
            String result,
            String certificate,
            String algorithmUsed,
            long wallMs) {}

    // ── Stats accessor (for the future reporter update) ──────────────

    /** Snapshot of counters at call time. Read-only. */
    public ProverStats stats() {
        long n = totalCalls.get();
        long total = latencyMsTotal.get();
        double avg = n == 0 ? 0.0 : (double) total / n;
        return new ProverStats(
                n,
                provedCalls.get(),
                noClosedFormCalls.get(),
                safetyRejectCalls.get(),
                transportFailCalls.get(),
                evalErrorCalls.get(),
                total,
                latencyMsMax.get(),
                avg);
    }

    public record ProverStats(
            long totalCalls,
            long provedCalls,
            long noClosedFormCalls,
            long safetyRejectCalls,
            long transportFailCalls,
            long evalErrorCalls,
            long latencyMsTotal,
            long latencyMsMax,
            double latencyMsAvg) {}
}
