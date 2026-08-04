package com.emergentterrain.research;

import com.emergentterrain.government.GovernmentState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 Iter 6 (write side) — bots run experiments by proposing
 * interventions into the running political simulation. The service takes
 * a high-level {@link InterventionProposal} (a hypothesis + a policy
 * target), translates it into a {@link GovernmentState.GovernmentBill},
 * and queues it for the constitutional process that
 * {@code GovernmentSimulationService.advanceBills} runs each tick. The
 * bill may then ENACT, BLOCK (via court block / low pressure / ideology
 * mismatch), or stay IN_PROGRESS — all of which are useful experimental
 * outcomes for the science layer.
 *
 * Stateless. The caller holds the {@link InterventionHandle} returned by
 * {@link #propose(InterventionProposal, GovernmentState, long)} and
 * passes it back into
 * {@link #observe(InterventionHandle, GovernmentState, long)} to get an
 * {@link InterventionOutcome}. The handle carries the before-snapshot
 * so the service doesn't need persistent storage of its own — the bill
 * itself lives inside {@link GovernmentState#getBills()} on the room's
 * persisted state, so the only data we need to recover later is the
 * before-snapshot, and that travels with the handle.
 *
 * The phase doc deferred this until Phase 2.5 (RoomService refactor)
 * landed because the routing pathway (bot proposes → file bill →
 * constitutional process) was hard to reason about while RoomService
 * still held the proposal-vote-apply pipeline. That refactor is now done.
 *
 * Bots cannot bypass the constitutional process. Every intervention is
 * filed as a bill and subject to the same legislative pipeline humans
 * generate bills through — that's a feature, not a workaround.
 *
 * Gated externally by {@code app.research.world-intervention.enabled} at
 * the call site (the integration point — likely a new
 * {@code WORLD_INTERVENTION} strategy in {@code ExperimentPlannerService}
 * — checks the flag before invoking this service).
 */
@Service
public class WorldInterventionService {

    private final WorldDataQueryService worldData;

    public WorldInterventionService(WorldDataQueryService worldData) {
        this.worldData = worldData;
    }

    /**
     * What policy lever a bot wants to pull. Each value maps to a
     * {@code GovernmentBill.policyDomain} that
     * {@code GovernmentSimulationService.applyBudgetEffects} already
     * recognises — the mapping is intentional so a bot's "increase
     * education" hypothesis becomes a real bill the constitutional process
     * will route through committees, votes, and possibly judicial review.
     */
    public enum PolicyTarget {
        /** {@code "services"} — affects health + education budgets. */
        SERVICES("services"),
        /** {@code "housing"} — affects welfare budget. */
        HOUSING("housing"),
        /** {@code "labor"} — affects justice + welfare budgets. */
        LABOR("labor"),
        /** {@code "infrastructure"} — affects infrastructure budget. */
        INFRASTRUCTURE("infrastructure"),
        /** {@code "rights-process"} — falls into the default branch in applyBudgetEffects (justice bump). */
        RIGHTS_PROCESS("rights-process");

        private final String policyDomain;

        PolicyTarget(String policyDomain) {
            this.policyDomain = policyDomain;
        }

        public String policyDomain() {
            return policyDomain;
        }
    }

    /**
     * High-level status the bot cares about while polling for an outcome.
     * Maps from the underlying {@link GovernmentState.GovernmentBill#getLawStatus()} string.
     */
    public enum BillStatus {
        /** Bill not found — either never created or has been pruned from the rolling 12-bill window. */
        NOT_FOUND,
        /** {@code "IN_PROGRESS"} or any other non-terminal status — still working through the constitutional process. */
        IN_PROGRESS,
        /** {@code "ENACTED"} — the bill became law; budget effects + world effects applied. */
        ENACTED,
        /** {@code "BLOCKED"} — court block, override fail, or executive veto with no override. */
        BLOCKED
    }

    /**
     * What a bot wants to test. The {@code proposingBotId} is recorded as
     * the bill's {@code sponsorActorId} for provenance.
     * {@code researchProgramId} is optional and supports linking outcomes
     * back to a longitudinal program in the science layer.
     */
    public record InterventionProposal(
            UUID proposingBotId,
            UUID researchProgramId,
            String hypothesis,
            PolicyTarget policyTarget,
            long durationTicks) {
    }

    /**
     * Returned from {@link #propose}. Carries the before-snapshot so a
     * later {@link #observe} call can compute deltas without re-fetching
     * historical state. The handle is otherwise a flyweight — store it
     * in the bot's program record or as a row alongside
     * {@code PreRegistration} entries.
     */
    public record InterventionHandle(
            UUID proposalId,
            String billId,
            long submittedAtTick,
            Map<String, Double> beforeValues) {
    }

    /**
     * Result of observing an intervention. {@code deltas} is
     * {@code afterValues - beforeValues} per known variable, so a
     * surprise score can be computed without recomputing the diff.
     * {@code billStatus} reflects the bill's current law-status; bots
     * polling early may see {@link BillStatus#IN_PROGRESS} and re-observe
     * later.
     */
    public record InterventionOutcome(
            UUID proposalId,
            String billId,
            BillStatus billStatus,
            Map<String, Double> beforeValues,
            Map<String, Double> afterValues,
            Map<String, Double> deltas) {
    }

    /**
     * Files a bill into {@code govState.getBills()} representing the
     * proposed intervention, and snapshots the current world variables
     * so a later {@link #observe} can compute deltas. The bill is added
     * to the front of the list (matching the internal-bill-creation
     * pattern in {@code advanceBills}) so the constitutional process
     * picks it up on the next {@code tick()}.
     *
     * Mutates {@code govState} (adds one bill). Returns null when
     * {@code govState} or its bills list is null.
     */
    public InterventionHandle propose(InterventionProposal proposal,
                                      GovernmentState govState,
                                      long observedAtTick) {
        if (proposal == null || govState == null || govState.getBills() == null) {
            return null;
        }
        UUID proposalId = UUID.randomUUID();
        Instant now = Instant.now();

        GovernmentState.GovernmentBill bill = new GovernmentState.GovernmentBill();
        String billId = String.format(Locale.ROOT, "research-%s", proposalId);
        bill.setBillId(billId);
        bill.setScope("federal");
        bill.setStateId(null);
        bill.setTitle("Research intervention: " + truncate(proposal.hypothesis(), 80));
        bill.setSponsorPartyId(resolveSponsorParty(govState));
        bill.setSponsorActorId(proposal.proposingBotId() == null ? null : proposal.proposingBotId().toString());
        bill.setPolicyDomain(proposal.policyTarget().policyDomain());
        bill.setStage("DraftBill");
        bill.setLawStatus("IN_PROGRESS");
        bill.setConstitutionalBasis("Research authority; Article I; bot-filed intervention");
        bill.setSummary("Bot-filed intervention to test: " + truncate(proposal.hypothesis(), 200));
        bill.setCreatedAt(now);
        bill.setUpdatedAt(now);

        govState.getBills().add(0, bill);
        if (govState.getBills().size() > 12) {
            govState.getBills().remove(govState.getBills().size() - 1);
        }
        govState.setLastBillAt(now);

        Map<String, Double> beforeValues = snapshotKnownVariables(govState, observedAtTick);
        return new InterventionHandle(proposalId, billId, observedAtTick, beforeValues);
    }

    /**
     * Reads the current world variables and the bill's current status,
     * returning the deltas relative to the before-snapshot captured at
     * {@link #propose} time. Returns {@link Optional#empty()} when the
     * bill is no longer in {@code govState.getBills()} (pruned out of
     * the rolling 12-bill window).
     */
    public Optional<InterventionOutcome> observe(InterventionHandle handle,
                                                 GovernmentState govState,
                                                 long observedAtTick) {
        if (handle == null || govState == null) {
            return Optional.empty();
        }
        GovernmentState.GovernmentBill bill = findBill(govState, handle.billId());
        BillStatus status = bill == null ? BillStatus.NOT_FOUND : mapBillStatus(bill.getLawStatus());
        if (status == BillStatus.NOT_FOUND) {
            return Optional.empty();
        }
        Map<String, Double> afterValues = snapshotKnownVariables(govState, observedAtTick);
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (String name : afterValues.keySet()) {
            Double before = handle.beforeValues().get(name);
            if (before != null) {
                deltas.put(name, afterValues.get(name) - before);
            }
        }
        return Optional.of(new InterventionOutcome(
                handle.proposalId(),
                handle.billId(),
                status,
                handle.beforeValues(),
                afterValues,
                deltas));
    }

    private Map<String, Double> snapshotKnownVariables(GovernmentState govState, long observedAtTick) {
        Map<String, Double> snapshot = new LinkedHashMap<>();
        List<WorldVariable> vars = worldData.queryAll(govState, observedAtTick);
        for (WorldVariable v : vars) {
            snapshot.put(v.name(), v.value());
        }
        return snapshot;
    }

    private static GovernmentState.GovernmentBill findBill(GovernmentState govState, String billId) {
        if (billId == null || govState.getBills() == null) {
            return null;
        }
        for (GovernmentState.GovernmentBill bill : govState.getBills()) {
            if (billId.equals(bill.getBillId())) {
                return bill;
            }
        }
        return null;
    }

    private static BillStatus mapBillStatus(String lawStatus) {
        if (lawStatus == null) return BillStatus.IN_PROGRESS;
        return switch (lawStatus.toUpperCase(Locale.ROOT)) {
            case "ENACTED" -> BillStatus.ENACTED;
            case "BLOCKED" -> BillStatus.BLOCKED;
            default -> BillStatus.IN_PROGRESS;
        };
    }

    private static String resolveSponsorParty(GovernmentState govState) {
        GovernmentState.GovernmentNode federal = govState.getFederal();
        if (federal != null && federal.getLowerChamberPartyId() != null) {
            return federal.getLowerChamberPartyId();
        }
        return "research-sponsor";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
