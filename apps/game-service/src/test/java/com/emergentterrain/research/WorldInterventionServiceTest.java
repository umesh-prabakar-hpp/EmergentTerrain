package com.emergentterrain.research;

import com.emergentterrain.government.GovernmentState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the behaviour of {@link WorldInterventionService} — the Phase 3
 * Iter 6 write side that lets bots propose interventions into the running
 * political simulation. The service is stateless, so these tests stand
 * up a small {@link GovernmentState} by hand and exercise the
 * {@code propose} → {@code observe} cycle directly.
 */
class WorldInterventionServiceTest {

    private final WorldDataQueryService worldData = new WorldDataQueryService();
    private final WorldInterventionService svc = new WorldInterventionService(worldData);

    // ---------- propose ----------

    @Test
    void proposeAddsBillToGovernmentStateWithCorrectShape() {
        GovernmentState govState = freshGovState();
        WorldInterventionService.InterventionProposal proposal = sampleProposal();

        WorldInterventionService.InterventionHandle handle = svc.propose(proposal, govState, 100L);

        assertThat(handle).isNotNull();
        assertThat(handle.billId()).isNotNull().startsWith("research-");
        assertThat(handle.submittedAtTick()).isEqualTo(100L);
        assertThat(handle.beforeValues()).isNotEmpty();

        // Bill landed in govState.bills at index 0.
        assertThat(govState.getBills()).isNotEmpty();
        GovernmentState.GovernmentBill bill = govState.getBills().get(0);
        assertThat(bill.getBillId()).isEqualTo(handle.billId());
        assertThat(bill.getPolicyDomain()).isEqualTo("services");        // SERVICES → "services"
        assertThat(bill.getLawStatus()).isEqualTo("IN_PROGRESS");
        assertThat(bill.getStage()).isEqualTo("DraftBill");
        assertThat(bill.getSponsorActorId()).isEqualTo(proposal.proposingBotId().toString());
    }

    @Test
    void proposeMapsAllPolicyTargetsToTheirBillDomains() {
        // Each PolicyTarget enum value should land in a bill with a
        // policyDomain string that GovernmentSimulationService.applyBudgetEffects
        // already recognises.
        for (WorldInterventionService.PolicyTarget target : WorldInterventionService.PolicyTarget.values()) {
            GovernmentState gs = freshGovState();
            WorldInterventionService.InterventionProposal p = new WorldInterventionService.InterventionProposal(
                    UUID.randomUUID(), null, "test " + target, target, 50L);

            WorldInterventionService.InterventionHandle handle = svc.propose(p, gs, 1L);

            assertThat(handle).as(target.name() + " handle").isNotNull();
            assertThat(gs.getBills().get(0).getPolicyDomain())
                    .as(target.name() + " policy domain")
                    .isEqualTo(target.policyDomain());
        }
    }

    @Test
    void proposeCapsBillsListAtTwelve() {
        // GovernmentSimulationService.advanceBills caps bills at 12 by
        // dropping the oldest. WorldInterventionService follows the same
        // pattern; verify the cap is honoured when filing past the limit.
        GovernmentState gs = freshGovState();
        // Pre-seed 12 bills.
        for (int i = 0; i < 12; i++) {
            GovernmentState.GovernmentBill old = new GovernmentState.GovernmentBill();
            old.setBillId("pre-" + i);
            gs.getBills().add(old);
        }

        WorldInterventionService.InterventionHandle handle =
                svc.propose(sampleProposal(), gs, 1L);

        assertThat(gs.getBills()).hasSize(12);
        // The new bill is at index 0; the oldest pre-seeded bill should be gone.
        assertThat(gs.getBills().get(0).getBillId()).isEqualTo(handle.billId());
        assertThat(gs.getBills()).noneMatch(b -> "pre-11".equals(b.getBillId()));
    }

    @Test
    void proposeReturnsNullForMissingInputs() {
        assertThat(svc.propose(null, freshGovState(), 0L)).isNull();
        assertThat(svc.propose(sampleProposal(), null, 0L)).isNull();
    }

    @Test
    void proposeSnapshotsKnownWorldVariablesAtSubmissionTime() {
        GovernmentState gs = freshGovState();
        // Hand-set values we'll later mutate to verify the before-snapshot
        // is captured at propose-time (not re-read at observe-time).
        gs.getFederal().getBudget().setEducation(123L);

        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);

        assertThat(handle.beforeValues())
                .containsEntry(WorldDataQueryService.NAME_BUDGET_EDUCATION, 123.0);
        // The "before" map is independent of later state mutation.
        gs.getFederal().getBudget().setEducation(999L);
        assertThat(handle.beforeValues().get(WorldDataQueryService.NAME_BUDGET_EDUCATION))
                .isEqualTo(123.0);
    }

    // ---------- observe ----------

    @Test
    void observeReturnsEmptyWhenBillNotFound() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle stale = new WorldInterventionService.InterventionHandle(
                UUID.randomUUID(), "research-orphan", 0L, java.util.Map.of());

        Optional<WorldInterventionService.InterventionOutcome> out = svc.observe(stale, gs, 100L);

        assertThat(out).isEmpty();
    }

    @Test
    void observeReturnsInProgressStatusWhileBillIsPending() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);

        Optional<WorldInterventionService.InterventionOutcome> out = svc.observe(handle, gs, 1L);

        assertThat(out).isPresent();
        assertThat(out.get().billStatus()).isEqualTo(WorldInterventionService.BillStatus.IN_PROGRESS);
        assertThat(out.get().billId()).isEqualTo(handle.billId());
        assertThat(out.get().proposalId()).isEqualTo(handle.proposalId());
    }

    @Test
    void observeReturnsEnactedStatusWhenBillReachedEnactedLawStatus() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);
        // Simulate the constitutional process completing.
        gs.getBills().get(0).setLawStatus("ENACTED");

        WorldInterventionService.InterventionOutcome out = svc.observe(handle, gs, 1L).orElseThrow();

        assertThat(out.billStatus()).isEqualTo(WorldInterventionService.BillStatus.ENACTED);
    }

    @Test
    void observeReturnsBlockedStatusWhenBillWasBlocked() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);
        gs.getBills().get(0).setLawStatus("BLOCKED");

        WorldInterventionService.InterventionOutcome out = svc.observe(handle, gs, 1L).orElseThrow();

        assertThat(out.billStatus()).isEqualTo(WorldInterventionService.BillStatus.BLOCKED);
    }

    @Test
    void observeComputesDeltasFromBeforeSnapshot() {
        GovernmentState gs = freshGovState();
        gs.getFederal().getBudget().setEducation(100L);
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);

        // Simulate the bill landing and budget effects applying.
        gs.getBills().get(0).setLawStatus("ENACTED");
        gs.getFederal().getBudget().setEducation(150L);

        WorldInterventionService.InterventionOutcome out = svc.observe(handle, gs, 1L).orElseThrow();

        assertThat(out.deltas())
                .containsEntry(WorldDataQueryService.NAME_BUDGET_EDUCATION, 50.0);
        assertThat(out.beforeValues().get(WorldDataQueryService.NAME_BUDGET_EDUCATION))
                .isEqualTo(100.0);
        assertThat(out.afterValues().get(WorldDataQueryService.NAME_BUDGET_EDUCATION))
                .isEqualTo(150.0);
    }

    @Test
    void observeReturnsZeroDeltaWhenNothingChanged() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);

        WorldInterventionService.InterventionOutcome out = svc.observe(handle, gs, 1L).orElseThrow();

        // Every delta should be 0.0 since we haven't touched state.
        assertThat(out.deltas().values()).allMatch(d -> d == 0.0);
    }

    @Test
    void observeReturnsEmptyForNullInputs() {
        GovernmentState gs = freshGovState();
        WorldInterventionService.InterventionHandle handle = svc.propose(sampleProposal(), gs, 0L);

        assertThat(svc.observe(null, gs, 0L)).isEmpty();
        assertThat(svc.observe(handle, null, 0L)).isEmpty();
    }

    // ---------- end-to-end: propose then observe an enacted intervention ----------

    @Test
    void proposeToObserveCycleSurfacesTheBillEnactedAndDelta() {
        // The user-facing flow: bot files an education-budget intervention,
        // constitutional process enacts the bill, applyBudgetEffects bumps
        // education by 18, bot observes a positive delta.
        GovernmentState gs = freshGovState();
        gs.getFederal().getBudget().setEducation(200L);

        WorldInterventionService.InterventionProposal proposal = new WorldInterventionService.InterventionProposal(
                UUID.randomUUID(), UUID.randomUUID(),
                "Increased education budget will lift federal public trust",
                WorldInterventionService.PolicyTarget.SERVICES,
                30L);

        WorldInterventionService.InterventionHandle handle = svc.propose(proposal, gs, 0L);

        // Simulate the constitutional process: bill enacts, education +18
        // (per GovernmentSimulationService.applyBudgetEffects for "services").
        gs.getBills().get(0).setLawStatus("ENACTED");
        gs.getFederal().getBudget().setEducation(218L);

        WorldInterventionService.InterventionOutcome out = svc.observe(handle, gs, 30L).orElseThrow();

        assertThat(out.billStatus()).isEqualTo(WorldInterventionService.BillStatus.ENACTED);
        assertThat(out.deltas().get(WorldDataQueryService.NAME_BUDGET_EDUCATION)).isEqualTo(18.0);
    }

    // ---------- helpers ----------

    private static WorldInterventionService.InterventionProposal sampleProposal() {
        return new WorldInterventionService.InterventionProposal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Education budget changes drive civic trust",
                WorldInterventionService.PolicyTarget.SERVICES,
                100L);
    }

    private static GovernmentState freshGovState() {
        GovernmentState gs = new GovernmentState();
        gs.setBills(new ArrayList<>());

        GovernmentState.GovernmentNode federal = new GovernmentState.GovernmentNode();
        federal.setLowerChamberPartyId("party-alpha");
        federal.setPublicTrust(0.5);
        federal.setServiceAccess(0.4);

        GovernmentState.BudgetState budget = new GovernmentState.BudgetState();
        budget.setEducation(100L);
        budget.setHealth(100L);
        budget.setInfrastructure(100L);
        budget.setWelfare(100L);
        budget.setJustice(100L);
        budget.setPayroll(100L);
        federal.setBudget(budget);

        gs.setFederal(federal);
        gs.setStates(new ArrayList<>());
        return gs;
    }
}
