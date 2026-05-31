package com.emergentterrain.research.discovery;

import org.springframework.stereotype.Component;

/**
 * Phase 30 frontier-escalation Phase 3 — the B3 (proxy reformulation) proposer.
 * When the straightforward computation stalls, ask discovery for a
 * *fundamentally different, more efficient* method at the same target and let the
 * moat validate it on the overlap. If a cheaper reformulation validates, the
 * reach genuinely extends — an exact value of the (same) quantity by a tractable
 * route, tagged {@code VALIDATED_PROXY}.
 *
 * <p>Honest scope: this reuses the discovery moat, so it succeeds only when a
 * cheaper method that still reproduces the overlap actually exists and validates
 * — for Mertens, where the sublinear method is already near-optimal, it will
 * often (correctly) fail and defer to B4.
 *
 * <p>For a *genuinely different* proxy quantity (a sibling summatory function),
 * the generalized oracle now exists — {@link SummatoryProxyOracle} (trusted ground
 * truth for any linear-combination summatory over μ/ω/Ω/τ, from the sieve) +
 * {@link ProxyValidator} (the proxy moat). Wiring this proposer's *runtime* to it
 * (generate a proxy method → sandbox-execute → judge via the proxy oracle, a
 * proxy-discovery loop mirroring {@link MertensDiscoveryService}) is the remaining
 * consumption step.
 */
@Component
public class B3ProxyReformulationProposer implements BranchProposer {

    private static final int CPU_SECONDS = 25;
    private static final int WALL_SECONDS = 28;

    private final MertensDiscoveryService discovery;

    public B3ProxyReformulationProposer(MertensDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @Override
    public Branch branch() {
        return Branch.PROXY_REFORMULATION;
    }

    @Override
    public PivotOutcome attempt(PivotRequest request) {
        if (discovery == null || !discovery.isEnabled()) {
            return new PivotOutcome(Branch.PROXY_REFORMULATION, TrustLevel.VALIDATED_PROXY, false,
                    "discovery unavailable for a reformulated attempt");
        }
        String reformulated = request.spec()
                + " — NOTE: a direct/naive method has already STALLED at this scale. Use a fundamentally "
                + "more efficient reformulation (e.g. the sublinear recurrence M(x)=1-sum_{i>=2} M(floor(x/i)) "
                + "with an x^(2/3) base table and the hyperbola/blocking trick), not a linear sieve.";
        DiscoveryTask dt = new DiscoveryTask(reformulated, request.target(),
                request.checkpoints(), request.anchors(), CPU_SECONDS, WALL_SECONDS, discovery.maxAttempts());
        DiscoveryOutcome out = discovery.discover(dt);
        if (out.accepted()) {
            return new PivotOutcome(Branch.PROXY_REFORMULATION, TrustLevel.VALIDATED_PROXY, true,
                    String.format("reformulated method validated by the moat: value at x=%d is %d "
                            + "(exact, VALIDATED_PROXY trust — a tractable route reproduced the overlap).",
                            request.target(), out.targetValue()));
        }
        return new PivotOutcome(Branch.PROXY_REFORMULATION, TrustLevel.VALIDATED_PROXY, false,
                "no cheaper reformulated method validated within budget (" + out.failureReason() + ")");
    }
}
