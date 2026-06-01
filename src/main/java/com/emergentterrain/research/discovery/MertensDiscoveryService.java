package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.CandidateGenerator.GenerationRequest;
import com.emergentterrain.research.discovery.DiscoveryOutcome.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Phase 30 fork 2b Phase 3 — the generate→validate→repair orchestration loop,
 * proven at dev time in fork 2a and now productionised in Java. A candidate
 * numeric method is generated, validated by the moat (via the sidecar), and on
 * failure the discrepancy is fed back for repair, up to {@code maxAttempts}.
 *
 * <p>Flag-gated OFF by {@code app.research.discovery.runtimeExecEnabled}: when
 * off, {@link #discover} short-circuits to {@link DiscoveryOutcome#disabled()}
 * and neither the model nor the sandbox is touched. This is the Java gate; the
 * sidecar's {@code ET_DISCOVER_EXEC} env gate is the second. The per-invariant
 * (MERTENS_CLASS) gate is applied by the Phase-4 tool that calls this — kept out
 * of here so the orchestration stays domain-neutral.
 *
 * <p>Generation and validation are injected ({@link CandidateGenerator},
 * {@link CandidateValidator}) so the loop is unit-testable without a live model
 * or sidecar.
 */
@Service
public class MertensDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MertensDiscoveryService.class);

    private final boolean enabled;
    private final int maxRepairAttempts;
    private final CandidateGenerator generator;
    private final CandidateValidator validator;

    /**
     * §10 mitigation — bail out of the repair loop after this many CONSECUTIVE resource
     * failures (timeout/oom) at the same scale. Repeated timeouts mean the SCALE is too big,
     * not a fixable bug, so grinding all {@code maxRepairAttempts} just burns ~wall_s each;
     * bailing early lets the escalation loop feed back a smaller x sooner. A non-resource
     * failure (wrong number — repairable) resets the streak. 0 disables (old behavior).
     * Field-injected (default 2) so the back-compat constructor stays 4-arg.
     */
    @Value("${app.research.discovery.repairEarlyExitThreshold:2}")
    private int repairEarlyExitThreshold = 2;

    /** Test seam for the early-exit threshold. */
    void setRepairEarlyExitThreshold(int t) {
        this.repairEarlyExitThreshold = t;
    }

    public MertensDiscoveryService(
            @Value("${app.research.discovery.runtimeExecEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.maxRepairAttempts:3}") int maxRepairAttempts,
            CandidateGenerator generator,
            CandidateValidator validator) {
        this.enabled = enabled;
        this.maxRepairAttempts = Math.max(1, maxRepairAttempts);
        this.generator = generator;
        this.validator = validator;
        log.info("MertensDiscoveryService ready — runtimeExec={} maxRepairAttempts={}",
                enabled ? "ON" : "OFF", this.maxRepairAttempts);
    }

    /** Whether the Java gate is on (the Phase-4 tool advertises itself only when this is true). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Configured generate→validate→repair cap; callers stamp it onto the {@link DiscoveryTask}. */
    public int maxAttempts() {
        return maxRepairAttempts;
    }

    /**
     * Run the loop. Returns an accepted outcome (with the validated value and
     * provenance) as soon as a candidate passes the moat, an exhausted outcome
     * if none does within {@code task.maxAttempts()}, or a disabled outcome when
     * the flag is off.
     */
    private DiscoveryEndpointHealth endpointHealth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEndpointHealth(DiscoveryEndpointHealth endpointHealth) {
        this.endpointHealth = endpointHealth;
    }

    public DiscoveryOutcome discover(DiscoveryTask task) {
        if (!enabled) {
            return DiscoveryOutcome.disabled();
        }
        // Pre-flight — skip BEFORE generating (no tokens) if the sidecar endpoint is gated/down.
        if (endpointHealth != null && !endpointHealth.available()) {
            log.warn("discovery skipped — endpoint unavailable; no candidate generated (no tokens spent)");
            return DiscoveryOutcome.unavailable();
        }
        String previousCode = null;
        String discrepancy = null;
        String lastCode = null;
        String lastStatus = null;
        int consecutiveResourceFails = 0;   // §10 — repeated timeout/oom ⇒ scale too big
        for (int attempt = 1; attempt <= task.maxAttempts(); attempt++) {
            String code = generator.generate(new GenerationRequest(task.spec(), previousCode, discrepancy));
            if (code == null || code.isBlank()) {
                discrepancy = "generator returned no code";
                log.info("discovery attempt {}/{} — {}", attempt, task.maxAttempts(), discrepancy);
                continue;
            }
            // Log the LLM-generated candidate verbatim (+sha) — the actual computation
            // that will (or won't) produce a frontier value, so it is auditable in the log.
            log.info("discovery attempt {}/{} — candidate (sha256={}) for M({}):\n{}",
                    attempt, task.maxAttempts(), sha256(code), task.target(), clip(code));
            ValidationResult result = validator.validate(code, task);
            if (result.accepted()) {
                log.info("discovery accepted on attempt {}/{} — target M({}) = {} | method sha256={}",
                        attempt, task.maxAttempts(), task.target(), result.targetValue(), sha256(code));
                return DiscoveryOutcome.accepted(code, result.targetValue(), attempt,
                        provenanceOf(code, task), result.elapsedMs());
            }
            previousCode = code;
            lastCode = code;
            lastStatus = result == null ? "error" : result.status();
            discrepancy = describe(result);
            log.info("discovery attempt {}/{} rejected — {}", attempt, task.maxAttempts(), discrepancy);

            // §10 mitigation — early-exit on REPEATED resource failure. A timeout/oom means the
            // scale is too big; the LLM can't fix that with a better method, so grinding the
            // remaining attempts just burns ~wall_s each. Bail and let the escalation feed back a
            // smaller x. A wrong-number failure (repairable) resets the streak.
            consecutiveResourceFails = isResourceFailure(lastStatus) ? consecutiveResourceFails + 1 : 0;
            if (repairEarlyExitThreshold > 0 && consecutiveResourceFails >= repairEarlyExitThreshold
                    && attempt < task.maxAttempts()) {
                log.info("discovery early-exit — {} consecutive {} at M({}); scale too big, stopping repair "
                        + "after {}/{} (feeding back for a smaller x)",
                        consecutiveResourceFails, lastStatus, task.target(), attempt, task.maxAttempts());
                return DiscoveryOutcome.exhausted(attempt, discrepancy, lastCode, lastStatus);
            }
        }
        return DiscoveryOutcome.exhausted(task.maxAttempts(), discrepancy, lastCode, lastStatus);
    }

    /** Trim a candidate for the log so a pathological generation can't flood it. */
    private static String clip(String code) {
        return code.length() <= 4000 ? code : code.substring(0, 4000) + "\n… [" + (code.length() - 4000) + " more chars]";
    }

    /**
     * The repair signal fed back to the generator — exactly what fork 2a showed sufficient.
     * The <b>Mertens-specific</b> realization of a resource (OOM/timeout) failure is added
     * here (this is the mertens branch); the <i>domain-neutral</i> "use bounded memory /
     * segment the range" lesson is added by the shared {@link LlmCandidateGenerator} so it
     * also serves proxy discovery (see frontier-escalation-loop.md §10.4).
     */
    static String describe(ValidationResult r) {
        if (r == null) {
            return "no validation result";
        }
        if ("na".equals(r.verdict())) {
            String base = "candidate did not run (" + r.status() + ")"
                    + (r.detail() == null || r.detail().isBlank() ? "" : ": " + r.detail());
            if ("timeout".equals(r.status())) {
                // a correct-but-O(x) sieve (even segmented) is too SLOW at large x — push sublinear
                base += " — TOO SLOW: an O(x) pass over [1,x] cannot finish in the budget at this "
                        + "scale. Switch to the SUBLINEAR O(x^(2/3)) method: sieve μ and its prefix "
                        + "sums M(n) up to ~x^(2/3), then M(n) = 1 - Σ_{d=2}^{n} M(⌊n/d⌋), grouping "
                        + "equal ⌊n/d⌋ blocks and memoizing M at the ⌊x/i⌋ points.";
            } else if (isResourceFailure(r.status())) {   // oom
                base += " — TOO MUCH MEMORY: do not allocate ~x entries for M(x) = Σ_{n≤x} μ(n). "
                        + "Sieve μ only up to ~x^(2/3) (Int8) and use the M(n) = 1 - Σ M(⌊n/d⌋) "
                        + "recurrence with memoization for the rest.";
            }
            return base;
        }
        if (r.firstFail() != null) {
            ValidationResult.FirstFail f = r.firstFail();
            return "M(" + f.k() + ") = " + f.got() + " but the correct value is " + f.want();
        }
        return "rejected (" + r.status() + ")";
    }

    /** Sandbox statuses where the fix is memory/time-bound, not a numeric bug. Domain-neutral tokens. */
    static boolean isResourceFailure(String status) {
        return "oom".equals(status) || "timeout".equals(status);
    }

    private static Provenance provenanceOf(String code, DiscoveryTask task) {
        return new Provenance(sha256(code), task.checkpoints().size(), task.anchors().size(), task.target());
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 is always available on a JRE; fall back to a non-crypto tag rather than fail discovery.
            return "nohash-" + Integer.toHexString(s.hashCode());
        }
    }
}
