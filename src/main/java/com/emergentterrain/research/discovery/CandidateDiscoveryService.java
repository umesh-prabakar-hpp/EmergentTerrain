package com.emergentterrain.research.discovery;

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
public class CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscoveryService.class);

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

    /**
     * Gate for the author-facing {@code discover_divisor_value} tool — the divisor-domain
     * sibling of {@code discover_mertens_value}. Independent of {@link #isEnabled()} (the
     * runtime-exec gate) so the divisor tool can be advertised or held back on its own,
     * while both still require the sidecar's {@code ET_DISCOVER_EXEC=1}. Field-injected
     * (default ON) so the back-compat 4-arg constructor stays unchanged.
     */
    @Value("${app.research.discovery.divisorDiscoverToolEnabled:true}")
    private boolean divisorToolEnabled = true;

    /** Whether the divisor-domain discovery tool should be advertised. */
    public boolean isDivisorToolEnabled() {
        return divisorToolEnabled;
    }

    /** Test seam for the divisor-tool gate (public: exercised from the service-package tool test). */
    public void setDivisorToolEnabled(boolean b) {
        this.divisorToolEnabled = b;
    }

    public CandidateDiscoveryService(
            @Value("${app.research.discovery.runtimeExecEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.maxRepairAttempts:3}") int maxRepairAttempts,
            CandidateGenerator generator,
            CandidateValidator validator) {
        this.enabled = enabled;
        this.maxRepairAttempts = Math.max(1, maxRepairAttempts);
        this.generator = generator;
        this.validator = validator;
        log.info("CandidateDiscoveryService ready — runtimeExec={} maxRepairAttempts={}",
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
        // §10 — delegate to the SHARED generic loop (logging, the recipe-free resource hints,
        // and early-exit on repeated timeout/oom all live there, domain-neutral). The caller
        // supplies only its 1-stage moat validation + the per-failure text; the loop does the
        // rest. The label and failure text are derived from task.quantity() so a divisor run
        // reports D(x)/divisor, not Mertens M(x) (this class drives both via the frontier path).
        String symbol = quantitySymbol(task.quantity());
        DiscoveryRepairLoop.Result<ValidationResult> r = DiscoveryRepairLoop.run(
                task.spec(), task.quantity() + " " + symbol + "(" + task.target() + ")",
                task.maxAttempts(), repairEarlyExitThreshold,
                generator,
                code -> validator.validate(code, task),
                ValidationResult::accepted,
                vr -> vr == null ? "error" : vr.status(),
                vr -> failureText(symbol, vr));
        if (r.accepted()) {
            ValidationResult v = r.winning();
            return DiscoveryOutcome.accepted(r.code(), v.targetValue(), r.attempts(),
                    provenanceOf(r.code(), task), v.elapsedMs());
        }
        return DiscoveryOutcome.exhausted(r.attempts(),
                r.lastDiscrepancy() == null ? "no validated candidate" : r.lastDiscrepancy(),
                r.lastFailedCode(), r.lastStatus());
    }

    /** The quantity's one-letter summatory symbol for human-facing labels/feedback. */
    static String quantitySymbol(String quantity) {
        return "divisor".equals(quantity) ? "D" : "M";
    }

    /**
     * The domain "what was wrong" text for a rejected candidate (the shared loop appends the
     * neutral resource signal). Names the summatory value (M(k) for Mertens, D(k) for divisor)
     * via {@code symbol}; no algorithm hint.
     */
    static String failureText(String symbol, ValidationResult r) {
        if (r == null) {
            return "no validation result";
        }
        if ("na".equals(r.verdict())) {
            return "candidate did not run (" + r.status() + ")"
                    + (r.detail() == null || r.detail().isBlank() ? "" : ": " + r.detail());
        }
        if (r.firstFail() != null) {
            ValidationResult.FirstFail f = r.firstFail();
            return symbol + "(" + f.k() + ") = " + f.got() + " but the correct value is " + f.want();
        }
        return "rejected (" + r.status() + ")";
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
