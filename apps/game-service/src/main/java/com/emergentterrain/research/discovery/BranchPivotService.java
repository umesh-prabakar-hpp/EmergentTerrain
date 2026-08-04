package com.emergentterrain.research.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 3 — the branch pivot
 * (frontier-escalation-loop.md §4). When computation is predicted to stall,
 * "scarcity drives innovation": try the verifiable innovative branches in trust
 * order (B3 proxy reformulation, then B4 predictive model), take the first that
 * validates, and — only if none does — return the **honest B5 boundary** rather
 * than inventing a value.
 *
 * <p>Proposers are injected ({@link BranchProposer}); Spring supplies whatever
 * exists (currently none — so the prod default is the boundary, which is the
 * correct honest behavior until B3/B4 proposers are wired). Flag-gated OFF
 * ({@code app.research.discovery.branchPivotEnabled}); when off, {@link #pivot}
 * returns {@link PivotOutcome#disabled()}.
 */
@Service
public class BranchPivotService {

    private static final Logger log = LoggerFactory.getLogger(BranchPivotService.class);

    private final boolean enabled;
    private final List<BranchProposer> proposers;   // sorted: lower branch ordinal (higher trust) first

    public BranchPivotService(
            @Value("${app.research.discovery.branchPivotEnabled:false}") boolean enabled,
            List<BranchProposer> proposers) {
        this.enabled = enabled;
        this.proposers = (proposers == null ? List.<BranchProposer>of() : proposers).stream()
                .sorted(Comparator.comparingInt(p -> p.branch().ordinal()))
                .toList();
        log.info("BranchPivotService ready — branchPivot={} proposers={}",
                enabled ? "ON" : "OFF",
                this.proposers.stream().map(p -> p.branch().name()).toList());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PivotOutcome pivot(PivotRequest request) {
        if (!enabled) {
            return PivotOutcome.disabled();
        }
        for (BranchProposer p : proposers) {
            PivotOutcome o = p.attempt(request);
            log.info("branch pivot @ x={} — tried {} -> validated={} ({})",
                    request.target(), p.branch(), o != null && o.validated(),
                    o == null ? "null" : o.finding());
            if (o != null && o.validated()) {
                return o;
            }
        }
        double xc = request.prediction() == null ? 0 : request.prediction().computeFrontierX();
        return PivotOutcome.boundary(String.format(
                "computation stalled near x_c~=%.3g and no verifiable branch (B3 proxy / B4 model) "
                + "validated for \"%s\" at x=%d. The science frontier requires non-constructive methods "
                + "outside this harness's verification regime — ground any claim in literature; do not "
                + "report a derived value here.", xc, request.spec(), request.target()));
    }
}
