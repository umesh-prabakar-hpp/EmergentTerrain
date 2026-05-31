package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.CandidateGenerator.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 30 fork 2b — the proxy-discovery loop: generate→validate→repair for a
 * proxy quantity, the consumption of the generalized oracle. Mirrors
 * {@link MertensDiscoveryService}, but the moat is the proxy moat: the candidate
 * is run by the sandbox **executor** (the existing {@link CandidateValidator},
 * whose own M(x) verdict is ignored — only its emitted {@code values} are used),
 * and those values are judged by {@link ProxyValidator} against the
 * {@link ProxyOracle} for the proxy. So a method for *any* linear-combination
 * summatory proxy can be validated on the overlap, then trusted out of range.
 *
 * <p>Flag-gated OFF ({@code app.research.discovery.proxyDiscoveryEnabled}); when
 * off, {@link #discover} short-circuits to a disabled outcome.
 */
@Service
public class ProxyDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProxyDiscoveryService.class);

    private final boolean enabled;
    private final int maxAttempts;
    private final CandidateGenerator generator;
    private final CandidateValidator executor;        // run candidate, take raw values (verdict ignored)
    private final ProxyOracleFactory oracleFactory;

    public ProxyDiscoveryService(
            @Value("${app.research.discovery.proxyDiscoveryEnabled:false}") boolean enabled,
            @Value("${app.research.discovery.maxRepairAttempts:3}") int maxAttempts,
            CandidateGenerator generator,
            CandidateValidator executor,
            ProxyOracleFactory oracleFactory) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.generator = generator;
        this.executor = executor;
        this.oracleFactory = oracleFactory;
        log.info("ProxyDiscoveryService ready — proxyDiscovery={}", enabled ? "ON" : "OFF");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ProxyDiscoveryOutcome discover(ProxySpec spec, DiscoveryTask task) {
        if (!enabled) {
            return ProxyDiscoveryOutcome.disabled();
        }
        ProxyOracle oracle = oracleFactory.forSpec(spec);
        String previousCode = null;
        String discrepancy = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = generator.generate(new GenerationRequest(specText(spec), previousCode, discrepancy));
            if (code == null || code.isBlank()) {
                discrepancy = "generator returned no code";
                continue;
            }
            ValidationResult res = executor.validate(code, task);   // sandbox executor — verdict ignored
            if (!"ok".equals(res.status())) {
                previousCode = code;
                discrepancy = "candidate did not run (" + res.status() + ")";
                log.info("proxy discovery [{}] attempt {}/{} — {}", spec.name(), attempt, maxAttempts, discrepancy);
                continue;
            }
            ProxyValidator.Verdict v = ProxyValidator.judge(res.values(), oracle, task.checkpoints());
            if (v.accepted()) {
                Long targetValue = res.values() == null ? null : res.values().get(task.target());
                log.info("proxy discovery [{}] accepted on attempt {}/{} — P({}) = {}",
                        spec.name(), attempt, maxAttempts, task.target(), targetValue);
                return ProxyDiscoveryOutcome.accepted(spec.name(), code, targetValue, attempt);
            }
            previousCode = code;
            discrepancy = "P(" + v.firstFailK() + ") = " + v.got() + " but the correct value is " + v.want();
            log.info("proxy discovery [{}] attempt {}/{} rejected — {}", spec.name(), attempt, maxAttempts, discrepancy);
        }
        return ProxyDiscoveryOutcome.exhausted(spec.name(), maxAttempts, discrepancy);
    }

    /** Describe the proxy summatory for the generator. */
    static String specText(ProxySpec spec) {
        return "P(x) = sum over n = 1..x of [ " + term(spec) + " ], where mu(n) = the Mobius function, "
                + "omega(n) = the number of DISTINCT prime factors of n, Omega(n) = the number of prime "
                + "factors of n WITH multiplicity, and tau(n) = the number of divisors of n. Return P(x) "
                + "as an exact Int64.";
    }

    static String term(ProxySpec s) {
        StringBuilder sb = new StringBuilder();
        appendTerm(sb, s.cMu(), "mu(n)");
        appendTerm(sb, s.cOmega(), "omega(n)");
        appendTerm(sb, s.cBigOmega(), "Omega(n)");
        appendTerm(sb, s.cTau(), "tau(n)");
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private static void appendTerm(StringBuilder sb, double c, String name) {
        if (c == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(c < 0 ? " - " : " + ");
        } else if (c < 0) {
            sb.append("-");
        }
        double a = Math.abs(c);
        if (a != 1.0) {
            sb.append(a == Math.rint(a) ? Long.toString((long) a) : Double.toString(a)).append("*");
        }
        sb.append(name);
    }
}
