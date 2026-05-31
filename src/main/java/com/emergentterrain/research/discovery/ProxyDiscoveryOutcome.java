package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — the result of a proxy-discovery run (the generalized analog of
 * {@link DiscoveryOutcome} for a proxy quantity). On accept it carries the proxy's
 * validated value at the target; the trust is {@code VALIDATED_PROXY} — exact, but
 * a *nearby* quantity (whether it constrains the target is the open question).
 */
public record ProxyDiscoveryOutcome(
        boolean accepted,
        String proxyName,
        String code,
        Long targetValue,
        int attempts,
        String failureReason) {

    public static ProxyDiscoveryOutcome disabled() {
        return new ProxyDiscoveryOutcome(false, null, null, null, 0, "proxy discovery disabled");
    }

    public static ProxyDiscoveryOutcome accepted(String proxyName, String code, Long targetValue, int attempts) {
        return new ProxyDiscoveryOutcome(true, proxyName, code, targetValue, attempts, null);
    }

    public static ProxyDiscoveryOutcome exhausted(String proxyName, int attempts, String lastReason) {
        return new ProxyDiscoveryOutcome(false, proxyName, null, null, attempts,
                "no proxy method validated within " + attempts + " attempt(s); last: " + lastReason);
    }
}
