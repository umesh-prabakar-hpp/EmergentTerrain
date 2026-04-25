package com.emergentterrain.government;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.web.GovernmentActionSummary;
import com.emergentterrain.web.GovernmentBudgetSummary;
import com.emergentterrain.web.GovernmentElectionSummary;
import com.emergentterrain.web.GovernmentStateMiniSummary;
import com.emergentterrain.web.GovernmentSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Service
public class GovernmentSimulationService {
    private static final String CONSTITUTION_VERSION = "us-constitutional-phase1";

    private final ObjectMapper objectMapper;
    private final SimulationHistoryWriter historyWriter;
    private final boolean enabled;
    private final long maintenanceIntervalMs;
    private final long electionIntervalMs;
    private final long billIntervalMs;
    private final int configuredStateCount;
    private final List<GovernmentState.ConstitutionRule> baseRules;
    private final double implementationSuccessTrustDelta;
    private final double implementationSuccessServiceDelta;
    private final double implementationStallTrustDelta;
    private final double implementationBackfireTrustDelta;
    private final double implementationBackfireServiceDelta;
    private final double conflictActiveTrustDelta;
    private final double conflictActiveServiceDelta;
    private final double conflictSettledTrustDelta;
    private final double pressureTrustPenaltyScale;
    private final double pressureTrustPenaltyCap;
    private final double pressureHighThreshold;
    private final double pressureAgendaShockThreshold;
    private final double executivePressureTriggerThreshold;
    private final double implementationSucceedThreshold;
    private final double implementationStallThreshold;
    private final double courtBlockThreshold;

    public GovernmentSimulationService(ObjectMapper objectMapper,
                                       SimulationHistoryWriter historyWriter,
                                       @Value("${app.government.enabled:true}") boolean enabled,
                                       @Value("${app.government.maintenanceIntervalMs:2500}") long maintenanceIntervalMs,
                                       @Value("${app.government.electionIntervalMs:60000}") long electionIntervalMs,
                                       @Value("${app.government.billIntervalMs:20000}") long billIntervalMs,
                                       @Value("${app.government.stateCount:4}") int configuredStateCount,
                                       @Value("classpath:government/constitution-rules.json") Resource constitutionRulesResource,
                                       @Value("${app.government.feedback.implementation.successTrustDelta:0.025}") double implementationSuccessTrustDelta,
                                       @Value("${app.government.feedback.implementation.successServiceDelta:0.03}") double implementationSuccessServiceDelta,
                                       @Value("${app.government.feedback.implementation.stallTrustDelta:-0.015}") double implementationStallTrustDelta,
                                       @Value("${app.government.feedback.implementation.backfireTrustDelta:-0.045}") double implementationBackfireTrustDelta,
                                       @Value("${app.government.feedback.implementation.backfireServiceDelta:-0.03}") double implementationBackfireServiceDelta,
                                       @Value("${app.government.feedback.conflict.activeTrustDelta:-0.02}") double conflictActiveTrustDelta,
                                       @Value("${app.government.feedback.conflict.activeServiceDelta:-0.015}") double conflictActiveServiceDelta,
                                       @Value("${app.government.feedback.conflict.settledTrustDelta:0.01}") double conflictSettledTrustDelta,
                                       @Value("${app.government.feedback.pressure.trustPenaltyScale:0.025}") double pressureTrustPenaltyScale,
                                       @Value("${app.government.feedback.pressure.trustPenaltyCap:0.08}") double pressureTrustPenaltyCap,
                                       @Value("${app.government.thresholds.pressure.high:0.65}") double pressureHighThreshold,
                                       @Value("${app.government.thresholds.pressure.agendaShock:0.68}") double pressureAgendaShockThreshold,
                                       @Value("${app.government.thresholds.pressure.executiveTrigger:0.55}") double executivePressureTriggerThreshold,
                                       @Value("${app.government.thresholds.implementation.succeed:0.72}") double implementationSucceedThreshold,
                                       @Value("${app.government.thresholds.implementation.stall:0.46}") double implementationStallThreshold,
                                       @Value("${app.government.thresholds.court.blockScore:0.45}") double courtBlockThreshold) {
        this.objectMapper = objectMapper;
        this.historyWriter = historyWriter;
        this.enabled = enabled;
        this.maintenanceIntervalMs = Math.max(250L, maintenanceIntervalMs);
        this.electionIntervalMs = Math.max(this.maintenanceIntervalMs * 4, electionIntervalMs);
        this.billIntervalMs = Math.max(this.maintenanceIntervalMs * 2, billIntervalMs);
        this.configuredStateCount = Math.max(1, configuredStateCount);
        this.baseRules = loadRules(constitutionRulesResource);
        this.implementationSuccessTrustDelta = implementationSuccessTrustDelta;
        this.implementationSuccessServiceDelta = implementationSuccessServiceDelta;
        this.implementationStallTrustDelta = implementationStallTrustDelta;
        this.implementationBackfireTrustDelta = implementationBackfireTrustDelta;
        this.implementationBackfireServiceDelta = implementationBackfireServiceDelta;
        this.conflictActiveTrustDelta = conflictActiveTrustDelta;
        this.conflictActiveServiceDelta = conflictActiveServiceDelta;
        this.conflictSettledTrustDelta = conflictSettledTrustDelta;
        this.pressureTrustPenaltyScale = pressureTrustPenaltyScale;
        this.pressureTrustPenaltyCap = pressureTrustPenaltyCap;
        this.pressureHighThreshold = pressureHighThreshold;
        this.pressureAgendaShockThreshold = pressureAgendaShockThreshold;
        this.executivePressureTriggerThreshold = executivePressureTriggerThreshold;
        this.implementationSucceedThreshold = implementationSucceedThreshold;
        this.implementationStallThreshold = implementationStallThreshold;
        this.courtBlockThreshold = courtBlockThreshold;
    }

    public GovernmentSimulationService(ObjectMapper objectMapper,
                                       SimulationHistoryWriter historyWriter,
                                       boolean enabled,
                                       long maintenanceIntervalMs,
                                       long electionIntervalMs,
                                       long billIntervalMs,
                                       int configuredStateCount,
                                       Resource constitutionRulesResource) {
        this(
            objectMapper,
            historyWriter,
            enabled,
            maintenanceIntervalMs,
            electionIntervalMs,
            billIntervalMs,
            configuredStateCount,
            constitutionRulesResource,
            0.025d,
            0.03d,
            -0.015d,
            -0.045d,
            -0.03d,
            -0.02d,
            -0.015d,
            0.01d,
            0.025d,
            0.08d,
            0.65d,
            0.68d,
            0.55d,
            0.72d,
            0.46d,
            0.45d
        );
    }

    public record GovernmentMutation(
        GovernmentState governmentState,
        GovernmentSummary summary,
        boolean governmentChanged,
        List<RoomBot> dirtyBots
    ) {
    }

    public GovernmentMutation ensureInitialized(Room room,
                                                RoomState roomState,
                                                List<RoomBot> bots,
                                                int[][] tiles,
                                                Function<RoomBot, Map<String, Double>> profileProvider,
                                                Instant now) {
        GovernmentState governmentState = readGovernmentState(roomState == null ? null : roomState.getGovernmentStateJson());
        boolean governmentChanged = false;
        if (governmentState == null) {
            governmentState = bootstrapGovernmentState(room, tiles, now);
            governmentChanged = true;
        }
        ensureGovernmentShape(governmentState, tiles, now);
        List<RoomBot> dirtyBots = ensureBotCivics(room, governmentState, bots, tiles, profileProvider, now);
        refreshGovernmentAggregates(governmentState, bots);
        GovernmentSummary summary = buildSummary(governmentState);
        return new GovernmentMutation(governmentState, summary, governmentChanged || !dirtyBots.isEmpty(), dirtyBots);
    }

    public GovernmentMutation tick(Room room,
                                   RoomState roomState,
                                   List<RoomBot> bots,
                                   int[][] tiles,
                                   Function<RoomBot, Map<String, Double>> profileProvider,
                                   Instant now) {
        GovernmentMutation initialized = ensureInitialized(room, roomState, bots, tiles, profileProvider, now);
        GovernmentState governmentState = initialized.governmentState();
        List<RoomBot> dirtyBots = new ArrayList<>(initialized.dirtyBots());
        boolean changed = initialized.governmentChanged();
        if (!enabled) {
            return new GovernmentMutation(governmentState, initialized.summary(), changed, dirtyBots);
        }
        if (governmentState.getLastTickAt() != null
            && now.toEpochMilli() - governmentState.getLastTickAt().toEpochMilli() < maintenanceIntervalMs) {
            return new GovernmentMutation(governmentState, initialized.summary(), changed, dirtyBots);
        }
        governmentState.setTick(governmentState.getTick() + 1);
        governmentState.setLastTickAt(now);
        changed = true;
        refreshGovernmentAggregates(governmentState, bots);
        refreshInstitutionProfiles(governmentState);
        refreshActorInfluence(governmentState);

        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            GovernmentState.GovernmentNode stateNode = resolveStateNode(governmentState, civicState.getStateId());
            double serviceDelta = terrainServiceSignal(bot, tiles)
                + budgetServiceBonus(governmentState.getFederal().getBudget())
                + budgetServiceBonus(stateNode.getBudget());
            double pressureDelta = economicPressure(civicState);
            updateEconomicState(civicState, governmentState, stateNode, serviceDelta, pressureDelta);
            civicState.setRightsSatisfaction(clamp01(civicState.getRightsSatisfaction()
                - pressureDelta * 0.03d
                + 0.012d
                + (stateNode.getPublicTrust() - 0.5d) * 0.02d));
            civicState.setInstitutionalTrust(clamp01(civicState.getInstitutionalTrust()
                + (civicState.getServiceAccess() - 0.5d) * 0.05d
                + (civicState.getRightsSatisfaction() - 0.5d) * 0.04d));
            civicState.setCivicParticipation(clamp01(civicState.getCivicParticipation()
                + (civicState.getInstitutionalTrust() - 0.5d) * 0.06d));
            String previousParty = civicState.getPartyId();
            civicState.setPartyId(closestPartyId(governmentState.getParties(), civicState));
            if (!Objects.equals(previousParty, civicState.getPartyId())) {
                civicState.setInstitutionalTrust(clamp01(civicState.getInstitutionalTrust() - 0.02d));
            }
            bot.setCivicStateJson(writeJson(civicState));
            dirtyBots.add(bot);
        }

        refreshGovernmentAggregates(governmentState, bots);
        refreshInstitutionProfiles(governmentState);
        boolean billChanged = advanceBills(governmentState, bots, now);
        boolean orderChanged = maybeIssueExecutiveOrders(governmentState, bots, now);
        boolean electionChanged = maybeRunElection(governmentState, bots, now);
        refreshInstitutionProfiles(governmentState);
        boolean courtChanged = maybeReviewByCourts(governmentState, now);
        boolean adminChanged = advanceAdministrativeState(governmentState, now);
        boolean conflictChanged = updateFederalStateConflicts(governmentState, now);
        boolean implementationChanged = updateImplementationOutcomes(governmentState, now);
        boolean historyChanged = refreshHistoricalMemory(governmentState, now);
        boolean botLegalChanged = applyBotGovernmentEnforcement(governmentState, bots, now);
        if (botLegalChanged) {
            refreshGovernmentAggregates(governmentState, bots);
            refreshInstitutionProfiles(governmentState);
            refreshActorInfluence(governmentState);
            dirtyBots = uniqueDirtyBots(dirtyBots, bots);
        }
        boolean pressureChanged = maybeGeneratePoliticalPressure(governmentState, bots, now);
        assignOfficeRoles(governmentState, bots);
        refreshInstitutionProfiles(governmentState);
        changed = changed || billChanged || orderChanged || courtChanged || electionChanged
            || pressureChanged || adminChanged || conflictChanged || implementationChanged || historyChanged || botLegalChanged;
        GovernmentSummary summary = buildSummary(governmentState);
        return new GovernmentMutation(governmentState, summary, changed, dirtyBots);
    }

    public GovernmentSummary buildSummary(GovernmentState governmentState) {
        GovernmentSummary summary = new GovernmentSummary();
        if (governmentState == null) {
            return summary;
        }
        summary.setConstitutionVersion(governmentState.getConstitutionVersion());
        summary.setTick(governmentState.getTick());
        summary.setNextElectionAt(governmentState.getNextElectionAt());
        GovernmentState.GovernmentNode federal = governmentState.getFederal();
        summary.setFederalExecutiveParty(resolvePartyName(governmentState, federal.getExecutivePartyId()));
        summary.setFederalLowerParty(resolvePartyName(governmentState, federal.getLowerChamberPartyId()));
        summary.setFederalUpperParty(resolvePartyName(governmentState, federal.getUpperChamberPartyId()));
        summary.setFederalChiefJusticeParty(resolvePartyName(governmentState, federal.getChiefJusticePartyId()));
        summary.setFederalTrust(federal.getPublicTrust());
        summary.setFederalRevenue(federal.getBudget().getRevenue());
        summary.setFederalInfrastructureBudget(federal.getBudget().getInfrastructure());
        summary.setFederalBudget(buildBudgetSummary(federal));
        summary.setStateCount(governmentState.getStates().size());
        summary.setActiveBotCount(governmentState.getActiveBotCount());
        summary.setPetitioningBotCount(governmentState.getPetitioningBotCount());
        summary.setReviewBotCount(governmentState.getReviewBotCount());
        summary.setSanctionedBotCount(governmentState.getSanctionedBotCount());
        summary.setPetitioningShare(governmentState.getPetitioningShare());
        summary.setSanctionedShare(governmentState.getSanctionedShare());
        summary.setStateSummaries(buildStateSummaries(governmentState));
        summary.setElectionSummary(buildElectionSummary(governmentState));
        List<GovernmentActionSummary> actionItems = new ArrayList<>();
        List<String> activeBills = new ArrayList<>();
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            if (!"ENACTED".equalsIgnoreCase(bill.getLawStatus()) && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus())) {
                activeBills.add(String.format(Locale.ROOT, "%s: %s (%s)", bill.getBillId(), bill.getTitle(), bill.getStage()));
                actionItems.add(buildBillSummary(governmentState, bill));
            }
        }
        summary.setActiveBills(activeBills);
        summary.setActiveBillCount(activeBills.size());
        List<String> executiveOrders = new ArrayList<>();
        for (GovernmentState.ExecutiveOrder order : governmentState.getExecutiveOrders()) {
            if (!"SUPERSEDED".equalsIgnoreCase(order.getStatus()) && !"BLOCKED".equalsIgnoreCase(order.getStatus())) {
                executiveOrders.add(String.format(Locale.ROOT, "%s: %s (%s)",
                    order.getOrderId(),
                    order.getSummary(),
                    order.getScope()));
                actionItems.add(buildExecutiveOrderSummary(governmentState, order));
            }
        }
        summary.setExecutiveOrders(executiveOrders);
        summary.setActiveExecutiveOrderCount(executiveOrders.size());
        List<String> courtCases = new ArrayList<>();
        for (GovernmentState.CourtCase courtCase : governmentState.getCourtCases()) {
            courtCases.add(String.format(Locale.ROOT, "%s: %s", courtCase.getCaseId(), courtCase.getDecision()));
            actionItems.add(buildCourtCaseSummary(governmentState, courtCase));
        }
        summary.setCourtCases(courtCases);
        summary.setActiveCourtCaseCount(courtCases.size());
        summary.setActionItems(actionItems);
        summary.setInstitutionItems(buildInstitutionItems(governmentState));
        summary.setPrecedentItems(buildPrecedentItems(governmentState));
        summary.setAdministrativeItems(buildAdministrativeItems(governmentState));
        summary.setImplementationItems(buildImplementationItems(governmentState));
        summary.setPressureItems(buildPressureItems(governmentState));
        summary.setConflictItems(buildConflictItems(governmentState));
        summary.setActorItems(buildActorItems(governmentState));
        summary.setHistoryItems(buildHistoryItems(governmentState));
        summary.setCivicLegalItems(buildCivicLegalItems(governmentState));
        List<String> partySummaries = new ArrayList<>();
        for (GovernmentState.PoliticalParty party : governmentState.getParties()) {
            partySummaries.add(String.format(Locale.ROOT,
                "%s %d members / influence %.2f",
                party.getName(),
                party.getMemberCount(),
                party.getInfluence()));
        }
        summary.setPartySummaries(partySummaries);
        return summary;
    }

    private GovernmentBudgetSummary buildBudgetSummary(GovernmentState.GovernmentNode node) {
        if (node == null || node.getBudget() == null) {
            return null;
        }
        GovernmentBudgetSummary budget = new GovernmentBudgetSummary();
        budget.setRevenue(node.getBudget().getRevenue());
        budget.setEducation(node.getBudget().getEducation());
        budget.setHealth(node.getBudget().getHealth());
        budget.setInfrastructure(node.getBudget().getInfrastructure());
        budget.setWelfare(node.getBudget().getWelfare());
        budget.setJustice(node.getBudget().getJustice());
        budget.setPayroll(node.getBudget().getPayroll());
        budget.setServiceAccess(node.getServiceAccess());
        return budget;
    }

    private List<GovernmentStateMiniSummary> buildStateSummaries(GovernmentState governmentState) {
        List<GovernmentStateMiniSummary> items = new ArrayList<>();
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            GovernmentStateMiniSummary item = new GovernmentStateMiniSummary();
            item.setName(stateNode.getDisplayName());
            item.setDistrictingMode(stateNode.getDistrictingMode());
            item.setDistrictCount(stateNode.getDistrictCount());
            item.setExecutiveParty(resolvePartyName(governmentState, stateNode.getExecutivePartyId()));
            item.setLowerParty(resolvePartyName(governmentState, stateNode.getLowerChamberPartyId()));
            item.setUpperParty(resolvePartyName(governmentState, stateNode.getUpperChamberPartyId()));
            item.setChiefJusticeParty(resolvePartyName(governmentState, stateNode.getChiefJusticePartyId()));
            item.setTrust(stateNode.getPublicTrust());
            item.setServiceAccess(stateNode.getServiceAccess());
            if (stateNode.getBudget() != null) {
                item.setRevenue(stateNode.getBudget().getRevenue());
                item.setInfrastructure(stateNode.getBudget().getInfrastructure());
            }
            items.add(item);
        }
        return items;
    }

    private GovernmentElectionSummary buildElectionSummary(GovernmentState governmentState) {
        GovernmentElectionSummary item = new GovernmentElectionSummary();
        item.setNextScope("Federal");
        item.setNextElectionAt(governmentState.getNextElectionAt());
        GovernmentState.ElectionRecord latest = governmentState.getElections().isEmpty()
            ? null
            : governmentState.getElections().get(0);
        if (latest != null) {
            item.setNextScope(humanizeToken(latest.getScope()));
            item.setLastHeldAt(latest.getHeldAt());
            item.setLastWinnerParty(resolvePartyName(governmentState, latest.getWinningPartyId()));
            item.setLastTurnout(latest.getTurnout());
            item.setLastSummary(latest.getSummary());
        }
        return item;
    }

    private GovernmentActionSummary buildBillSummary(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("bill");
        item.setTitle(String.format(Locale.ROOT, "%s: %s", bill.getBillId(), bill.getTitle()));
        item.setMeta(joinSummaryParts(
            "Bill",
            humanizeToken(bill.getScope()),
            humanizeToken(bill.getStage()),
            humanizeToken(bill.getLawStatus())
        ));
        String summary = bill.getSummary();
        String effects = summarizeList("Changes", bill.getWorldEffects());
        item.setSummary(joinSentences(summary, effects));
        item.setDetails(summaryDetails(
            "Policy domain: " + humanizeToken(bill.getPolicyDomain()),
            bill.getStateId() == null || bill.getStateId().isBlank()
                ? "Jurisdiction: " + humanizeToken(bill.getScope())
                : "Jurisdiction: " + humanizeToken(bill.getScope()) + " / " + bill.getStateId(),
            bill.getSponsorPartyId() == null || bill.getSponsorPartyId().isBlank()
                ? null
                : "Sponsor: " + resolvePartyName(governmentState, bill.getSponsorPartyId()),
            bill.getSponsorActorId() == null || bill.getSponsorActorId().isBlank()
                ? null
                : "Sponsor coalition actor: " + resolveActorName(governmentState, bill.getSponsorActorId()),
            bill.getCommitteeName() == null || bill.getCommitteeName().isBlank()
                ? null
                : "Committee: " + bill.getCommitteeName(),
            bill.getConstitutionalBasis() == null || bill.getConstitutionalBasis().isBlank()
                ? null
                : "Constitutional basis: " + bill.getConstitutionalBasis(),
            bill.getLawStatus() == null || bill.getLawStatus().isBlank()
                ? null
                : "Law status: " + humanizeToken(bill.getLawStatus()),
            bill.getChamberTextStatus() == null || bill.getChamberTextStatus().isBlank()
                ? null
                : "Chamber text: " + humanizeToken(bill.getChamberTextStatus()),
            bill.isConferenceRequired() ? "Conference committee required." : null,
            bill.getWhipCount() <= 0 ? null : "Whip count: " + bill.getWhipCount(),
            bill.getEffectiveAt() == null ? null : "Effective: " + bill.getEffectiveAt().toString(),
            summarizeList("Sections", bill.getSections()),
            summarizeList("Amendments", bill.getAmendmentSummaries()),
            summarizeList("Coalition", bill.getCoalitionPartyIds().stream().map(partyId -> resolvePartyName(governmentState, partyId)).toList()),
            summarizeList("Affected rights", bill.getAffectedRights()),
            summarizeList("Enforcement", bill.getEnforcementMechanisms()),
            bill.getPreemptionScope() == null || bill.getPreemptionScope().isBlank()
                ? null
                : "Preemption scope: " + humanizeToken(bill.getPreemptionScope()),
            bill.getImplementationStatus() == null || bill.getImplementationStatus().isBlank()
                ? null
                : "Implementation: " + humanizeToken(bill.getImplementationStatus()),
            summarizeFundingChanges("Funding", bill.getFundingChanges())
        ));
        return item;
    }

    private GovernmentActionSummary buildExecutiveOrderSummary(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("order");
        item.setTitle(String.format(Locale.ROOT, "Order %s", order.getOrderId()));
        item.setMeta(joinSummaryParts(
            "Executive Order",
            humanizeToken(order.getScope()),
            humanizeToken(order.getPolicyDomain()),
            humanizeToken(order.getStatus())
        ));
        String summary = order.getSummary();
        String effects = summarizeList("Touches", order.getAffectedSystems());
        item.setSummary(joinSentences(summary, effects));
        item.setDetails(summaryDetails(
            "Jurisdiction: " + joinSummaryParts(humanizeToken(order.getScope()), order.getJurisdictionId()),
            order.getIssuedByPartyId() == null || order.getIssuedByPartyId().isBlank()
                ? null
                : "Issued by: " + resolvePartyName(governmentState, order.getIssuedByPartyId()),
            order.getAgencyName() == null || order.getAgencyName().isBlank()
                ? null
                : "Lead agency: " + order.getAgencyName(),
            order.getPolicyDomain() == null || order.getPolicyDomain().isBlank()
                ? null
                : "Policy domain: " + humanizeToken(order.getPolicyDomain()),
            order.getIssuedAt() == null ? null : "Issued: " + order.getIssuedAt().toString(),
            order.getEffectiveAt() == null ? null : "Effective: " + order.getEffectiveAt().toString(),
            order.getEmergencyBasis() == null || order.getEmergencyBasis().isBlank()
                ? null
                : "Emergency basis: " + humanizeToken(order.getEmergencyBasis()),
            order.isChallengedImmediately() ? "Immediate challenge risk detected." : null,
            summarizeList("Directives", order.getDirectives()),
            summarizeList("Affected rights", order.getAffectedRights()),
            summarizeList("Enforcement", order.getEnforcementMechanisms()),
            order.getPreemptionScope() == null || order.getPreemptionScope().isBlank()
                ? null
                : "Preemption scope: " + humanizeToken(order.getPreemptionScope()),
            summarizeFundingChanges("Funding", order.getFundingChanges())
        ));
        return item;
    }

    private GovernmentActionSummary buildCourtCaseSummary(GovernmentState governmentState, GovernmentState.CourtCase courtCase) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("court");
        item.setTitle(courtCase.getChallengedTitle() == null || courtCase.getChallengedTitle().isBlank()
            ? String.format(Locale.ROOT, "Court: %s", courtCase.getCaseId())
            : String.format(Locale.ROOT, "Court: %s · %s", courtCase.getCaseId(), courtCase.getChallengedTitle()));
        item.setMeta(joinSummaryParts(
            humanizeToken(courtCase.getCourtLevel()),
            humanizeToken(courtCase.getStatus()),
            humanizeToken(courtCase.getStage()),
            "DECIDED".equalsIgnoreCase(courtCase.getStatus())
                ? (courtCase.isBlockedAction() ? "Blocked action" : "Action upheld")
                : null
        ));
        String summary = joinSentences(
            courtCase.getDecision(),
            courtCase.getIssueSummary(),
            courtCase.getDefendant() == null || courtCase.getDefendant().isBlank()
                ? null
                : "Reviewed action against " + courtCase.getDefendant() + ".",
            summarizeList("Affected", courtCase.getAffectedLaws())
        );
        item.setSummary(summary);
        item.setDetails(summaryDetails(
            courtCase.getPlaintiff() == null || courtCase.getPlaintiff().isBlank()
                ? null
                : "Plaintiff: " + humanizeToken(courtCase.getPlaintiff()),
            courtCase.getDefendant() == null || courtCase.getDefendant().isBlank()
                ? null
                : "Defendant: " + courtCase.getDefendant(),
            courtCase.getChallengedTitle() == null || courtCase.getChallengedTitle().isBlank()
                ? null
                : "Challenged action: " + courtCase.getChallengedTitle(),
            courtCase.getDoctrine() == null || courtCase.getDoctrine().isBlank()
                ? null
                : "Doctrine: " + humanizeToken(courtCase.getDoctrine()),
            courtCase.getScrutinyLevel() == null || courtCase.getScrutinyLevel().isBlank()
                ? null
                : "Scrutiny: " + humanizeToken(courtCase.getScrutinyLevel()),
            courtCase.getStandingBasis() == null || courtCase.getStandingBasis().isBlank()
                ? null
                : "Standing: " + humanizeToken(courtCase.getStandingBasis()),
            courtCase.getLegalBasis() == null || courtCase.getLegalBasis().isBlank()
                ? null
                : "Legal basis: " + humanizeToken(courtCase.getLegalBasis()),
            courtCase.getConstitutionalBasis() == null || courtCase.getConstitutionalBasis().isBlank()
                ? null
                : "Constitutional basis: " + courtCase.getConstitutionalBasis(),
            courtCase.getLowerCourtDisposition() == null || courtCase.getLowerCourtDisposition().isBlank()
                ? null
                : "Lower court: " + courtCase.getLowerCourtDisposition(),
            courtCase.getAppellatePath() == null || courtCase.getAppellatePath().isBlank()
                ? null
                : "Appellate path: " + courtCase.getAppellatePath(),
            courtCase.isInjunctionGranted() ? "Emergency injunction granted." : null,
            courtCase.getIssueSummary() == null || courtCase.getIssueSummary().isBlank()
                ? null
                : "Issue before court: " + courtCase.getIssueSummary(),
            courtCase.getRemedy() == null || courtCase.getRemedy().isBlank()
                ? null
                : "Remedy: " + humanizeToken(courtCase.getRemedy()),
            courtCase.isRemanded() ? "Case remanded for compliance or further proceedings." : null,
            courtCase.getDissentSummary() == null || courtCase.getDissentSummary().isBlank()
                ? null
                : "Dissent: " + courtCase.getDissentSummary(),
            courtCase.getMajorityAlignment() == null || courtCase.getMajorityAlignment().isBlank()
                ? null
                : "Majority alignment: " + humanizeToken(courtCase.getMajorityAlignment()),
            courtCase.getFiledAt() == null ? null : "Filed: " + courtCase.getFiledAt().toString(),
            courtCase.getArguedAt() == null ? null : "Argued: " + courtCase.getArguedAt().toString(),
            courtCase.getDecidedAt() == null ? null : "Decided: " + courtCase.getDecidedAt().toString(),
            summarizeList("Laws under review", courtCase.getAffectedLaws()),
            summarizeList("Precedent references", courtCase.getPrecedentIds())
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildInstitutionItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getInstitutions() == null) {
            return List.of();
        }
        return governmentState.getInstitutions().stream()
            .sorted(Comparator
                .comparing((GovernmentState.InstitutionProfile item) -> !"federal".equalsIgnoreCase(item.getLevel()))
                .thenComparingInt(item -> item.getCapacityPerTick() - item.getWorkload())
                .thenComparing(GovernmentState.InstitutionProfile::getBranch, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .map(institution -> buildInstitutionSummary(governmentState, institution))
            .toList();
    }

    private GovernmentActionSummary buildInstitutionSummary(GovernmentState governmentState,
                                                            GovernmentState.InstitutionProfile institution) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("institution");
        item.setTitle(institution.getDisplayName() == null || institution.getDisplayName().isBlank()
            ? humanizeToken(institution.getBranch())
            : institution.getDisplayName());
        item.setMeta(joinSummaryParts(
            humanizeToken(institution.getLevel()),
            humanizeToken(institution.getBranch()),
            String.format(Locale.ROOT, "capacity %d/tick", institution.getCapacityPerTick())
        ));
        int backlog = institution.getWorkload() - institution.getCapacityPerTick();
        item.setSummary(String.format(Locale.ROOT,
            "Legitimacy %.2f · workload %d%s",
            institution.getLegitimacy(),
            institution.getWorkload(),
            backlog > 0 ? String.format(Locale.ROOT, " · backlog +%d", backlog) : ""
        ));
        item.setDetails(summaryDetails(
            "Jurisdiction: " + ("state".equalsIgnoreCase(institution.getLevel())
                ? resolveStateName(governmentState, institution.getJurisdictionId())
                : "Federal"),
            institution.getProceduralRule() == null || institution.getProceduralRule().isBlank()
                ? null
                : "Procedural rule: " + humanizeToken(institution.getProceduralRule()),
            String.format(Locale.ROOT,
                "Leans: liberty %.2f · order %.2f · welfare %.2f · growth %.2f",
                institution.getLibertyLean(),
                institution.getOrderLean(),
                institution.getWelfareLean(),
                institution.getGrowthLean())
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildPrecedentItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getPrecedents() == null) {
            return List.of();
        }
        return governmentState.getPrecedents().stream()
            .limit(8)
            .map(this::buildPrecedentSummary)
            .toList();
    }

    private GovernmentActionSummary buildPrecedentSummary(GovernmentState.LegalPrecedent precedent) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("precedent");
        item.setTitle(String.format(Locale.ROOT, "Precedent: %s", precedent.getPrecedentId()));
        item.setMeta(joinSummaryParts(
            humanizeToken(precedent.getScope()),
            humanizeToken(precedent.getDoctrine()),
            humanizeToken(precedent.getOutcome())
        ));
        item.setSummary(joinSentences(precedent.getHolding(), precedent.getSummary()));
        item.setDetails(summaryDetails(
            precedent.getCaseId() == null || precedent.getCaseId().isBlank()
                ? null
                : "Origin case: " + precedent.getCaseId(),
            precedent.getPolicyDomain() == null || precedent.getPolicyDomain().isBlank()
                ? null
                : "Policy domain: " + humanizeToken(precedent.getPolicyDomain()),
            precedent.getConstitutionalBasis() == null || precedent.getConstitutionalBasis().isBlank()
                ? null
                : "Constitutional basis: " + precedent.getConstitutionalBasis(),
            precedent.getCreatedAt() == null ? null : "Created: " + precedent.getCreatedAt().toString(),
            String.format(Locale.ROOT, "Weight: %.2f", precedent.getWeight())
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildAdministrativeItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getAdministrativeActions() == null) {
            return List.of();
        }
        return governmentState.getAdministrativeActions().stream()
            .limit(8)
            .map(this::buildAdministrativeSummary)
            .toList();
    }

    private GovernmentActionSummary buildAdministrativeSummary(GovernmentState.AdministrativeAction action) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("administrative");
        item.setTitle(action.getTitle());
        item.setMeta(joinSummaryParts(
            humanizeToken(action.getActionType()),
            humanizeToken(action.getScope()),
            humanizeToken(action.getStage())
        ));
        item.setSummary(action.getSummary());
        item.setDetails(summaryDetails(
            action.getAgencyName() == null ? null : "Agency: " + action.getAgencyName(),
            action.getStatus() == null ? null : "Status: " + humanizeToken(action.getStatus()),
            action.getSourceBillId() == null ? null : "Source bill: " + action.getSourceBillId(),
            action.getSourceOrderId() == null ? null : "Source order: " + action.getSourceOrderId(),
            action.isAppealed() ? "Administrative appeal filed." : null,
            action.isStayed() ? "Enforcement stayed pending review." : null,
            action.getCreatedAt() == null ? null : "Opened: " + action.getCreatedAt().toString(),
            action.getUpdatedAt() == null ? null : "Updated: " + action.getUpdatedAt().toString()
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildImplementationItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getImplementationOutcomes() == null) {
            return List.of();
        }
        return governmentState.getImplementationOutcomes().stream()
            .limit(8)
            .map(this::buildImplementationSummary)
            .toList();
    }

    private GovernmentActionSummary buildImplementationSummary(GovernmentState.ImplementationOutcome outcome) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("implementation");
        item.setTitle("Implementation: " + outcome.getTitle());
        item.setMeta(joinSummaryParts(
            humanizeToken(outcome.getSourceType()),
            humanizeToken(outcome.getStatus()),
            outcome.getJurisdictionId()
        ));
        item.setSummary(outcome.getSummary());
        item.setDetails(summaryDetails(
            String.format(Locale.ROOT, "Capacity %.2f · outcome %.2f", outcome.getCapacityScore(), outcome.getOutcomeScore()),
            outcome.getSourceId() == null ? null : "Source: " + outcome.getSourceId(),
            outcome.getMeasuredAt() == null ? null : "Measured: " + outcome.getMeasuredAt().toString(),
            summarizeList("Adverse effects", outcome.getAdverseEffects())
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildPressureItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getPressureEvents() == null) {
            return List.of();
        }
        return governmentState.getPressureEvents().stream()
            .limit(8)
            .map(event -> buildPressureSummary(governmentState, event))
            .toList();
    }

    private GovernmentActionSummary buildPressureSummary(GovernmentState governmentState, GovernmentState.PoliticalPressure event) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("pressure");
        item.setTitle("Pressure: " + humanizeToken(event.getPressureType()));
        item.setMeta(joinSummaryParts(
            resolveActorName(governmentState, event.getSourceActorId()),
            humanizeToken(event.getEffect()),
            String.format(Locale.ROOT, "intensity %.2f", event.getIntensity())
        ));
        item.setSummary(event.getSummary());
        item.setDetails(summaryDetails(
            event.getTargetInstitutionId() == null ? null : "Target institution: " + event.getTargetInstitutionId(),
            event.getCreatedAt() == null ? null : "Raised: " + event.getCreatedAt().toString()
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildConflictItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getConflicts() == null) {
            return List.of();
        }
        return governmentState.getConflicts().stream()
            .limit(8)
            .map(conflict -> buildConflictSummary(governmentState, conflict))
            .toList();
    }

    private GovernmentActionSummary buildConflictSummary(GovernmentState governmentState, GovernmentState.FederalStateConflict conflict) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("conflict");
        item.setTitle("Federal-state conflict: " + resolveStateName(governmentState, conflict.getStateId()));
        item.setMeta(joinSummaryParts(
            humanizeToken(conflict.getConflictType()),
            humanizeToken(conflict.getStatus())
        ));
        item.setSummary(conflict.getSummary());
        item.setDetails(summaryDetails(
            "Federal party: " + resolvePartyName(governmentState, conflict.getFederalPartyId()),
            "State party: " + resolvePartyName(governmentState, conflict.getStatePartyId()),
            conflict.getRelatedBillId() == null ? null : "Related bill: " + conflict.getRelatedBillId(),
            conflict.getRelatedOrderId() == null ? null : "Related order: " + conflict.getRelatedOrderId(),
            conflict.getCreatedAt() == null ? null : "Opened: " + conflict.getCreatedAt().toString(),
            conflict.getResolvedAt() == null ? null : "Resolved: " + conflict.getResolvedAt().toString()
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildActorItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getActors() == null) {
            return List.of();
        }
        return governmentState.getActors().stream()
            .sorted(Comparator.comparingDouble(GovernmentState.CivicActor::getInfluence).reversed())
            .limit(8)
            .map(actor -> buildActorSummary(governmentState, actor))
            .toList();
    }

    private GovernmentActionSummary buildActorSummary(GovernmentState governmentState, GovernmentState.CivicActor actor) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("actor");
        item.setTitle(actor.getName());
        item.setMeta(joinSummaryParts(
            humanizeToken(actor.getActorType()),
            humanizeToken(actor.getScope()),
            String.format(Locale.ROOT, "influence %.2f", actor.getInfluence())
        ));
        item.setSummary(joinSentences(
            actor.getAlignedPartyId() == null ? null : "Aligned with " + resolvePartyName(governmentState, actor.getAlignedPartyId()) + ".",
            summarizeList("Agenda", actor.getAgendas())
        ));
        item.setDetails(summaryDetails(
            actor.getJurisdictionId() == null ? null : "Jurisdiction: " + actor.getJurisdictionId(),
            "Actor id: " + actor.getActorId()
        ));
        return item;
    }

    private List<GovernmentActionSummary> buildHistoryItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getHistoricalMarkers() == null) {
            return List.of();
        }
        return governmentState.getHistoricalMarkers().stream()
            .limit(8)
            .map(this::buildHistorySummary)
            .toList();
    }

    private List<GovernmentActionSummary> buildCivicLegalItems(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getActiveBotCount() <= 0) {
            return List.of();
        }
        List<GovernmentActionSummary> items = new ArrayList<>();

        GovernmentActionSummary climate = new GovernmentActionSummary();
        climate.setKind("civic-legal");
        climate.setTitle("Civic legal climate");
        climate.setMeta(joinSummaryParts(
            String.format(Locale.ROOT, "%d active bots", governmentState.getActiveBotCount()),
            String.format(Locale.ROOT, "%d petitioning", governmentState.getPetitioningBotCount()),
            String.format(Locale.ROOT, "%d sanctioned", governmentState.getSanctionedBotCount())
        ));
        climate.setSummary(String.format(Locale.ROOT,
            "%s civic legal climate with petitioning share %.2f and sanction share %.2f.",
            describeCivicLegalClimate(governmentState),
            governmentState.getPetitioningShare(),
            governmentState.getSanctionedShare()));
        climate.setDetails(summaryDetails(
            String.format(Locale.ROOT, "Reviewing bots: %d", governmentState.getReviewBotCount()),
            governmentState.getPetitioningBotCount() > 0
                ? "Petitioning bots are now feeding political-pressure generation."
                : "No active petition wave.",
            governmentState.getSanctionedBotCount() > 0
                ? "Some bots are under notice or sanction-linked legal exposure."
                : "No current sanction spike."
        ));
        items.add(climate);

        if (governmentState.getPetitioningBotCount() > 0 || governmentState.getReviewBotCount() > 0) {
            GovernmentActionSummary response = new GovernmentActionSummary();
            response.setKind("civic-legal");
            response.setTitle("Procedural response");
            response.setMeta(joinSummaryParts(
                String.format(Locale.ROOT, "%d petitioning", governmentState.getPetitioningBotCount()),
                String.format(Locale.ROOT, "%d in review", governmentState.getReviewBotCount())
            ));
            response.setSummary(governmentState.getPetitioningBotCount() > governmentState.getReviewBotCount()
                ? "More bots are escalating into petitioning than remaining in passive compliance review."
                : "Review channels are absorbing most of the current legal exposure.");
            response.setDetails(summaryDetails(
                governmentState.getPetitioningBotCount() > 0
                    ? "Petitioning tends to rise when rights-sensitive enforcement outruns perceived due-process protection."
                    : null,
                governmentState.getReviewBotCount() > 0
                    ? "Review state reflects bots responding to notices, stayed actions, or legal compliance checks."
                    : null
            ));
            items.add(response);
        }
        return items;
    }

    private GovernmentActionSummary buildHistorySummary(GovernmentState.HistoricalMarker marker) {
        GovernmentActionSummary item = new GovernmentActionSummary();
        item.setKind("history");
        item.setTitle(marker.getTitle());
        item.setMeta(joinSummaryParts(
            humanizeToken(marker.getMarkerType()),
            "Tick " + marker.getTick()
        ));
        item.setSummary(marker.getSummary());
        item.setDetails(summaryDetails(
            marker.getPartyAlignment() == null ? null : "Party alignment: " + marker.getPartyAlignment(),
            marker.getDoctrine() == null ? null : "Doctrine: " + humanizeToken(marker.getDoctrine()),
            marker.getCreatedAt() == null ? null : "Recorded: " + marker.getCreatedAt().toString()
        ));
        return item;
    }

    private String describeCivicLegalClimate(GovernmentState governmentState) {
        if (governmentState.getSanctionedShare() >= 0.2d) {
            return "High-pressure";
        }
        if (governmentState.getPetitioningShare() >= 0.18d) {
            return "Mobilized";
        }
        if (governmentState.getReviewBotCount() > 0) {
            return "Procedurally active";
        }
        return "Calm";
    }

    private String summarizeList(String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return label + ": " + String.join(", ", items) + ".";
    }

    private String summarizeFundingChanges(String label, Map<String, Long> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> formatted = new ArrayList<>();
        for (Map.Entry<String, Long> entry : items.entrySet()) {
            formatted.add(humanizeToken(entry.getKey()) + " " + entry.getValue());
        }
        return label + ": " + String.join(", ", formatted) + ".";
    }

    private String joinSentences(String... parts) {
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            cleaned.add(value);
        }
        return String.join(" ", cleaned);
    }

    private String joinSummaryParts(String... parts) {
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            cleaned.add(value);
        }
        return String.join(" · ", cleaned);
    }

    private List<String> summaryDetails(String... parts) {
        List<String> details = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            details.add(value);
        }
        return details;
    }

    private String humanizeToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('_', ' ').replace('-', ' ');
        normalized = normalized.replaceAll("([a-z])([A-Z])", "$1 $2");
        normalized = normalized.toLowerCase(Locale.ROOT).trim();
        String[] words = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result.toString();
    }

    public BotCivicState readBotCivicState(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, BotCivicState.class);
        } catch (IOException e) {
            return null;
        }
    }

    public GovernmentState readGovernmentState(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GovernmentState.class);
        } catch (IOException e) {
            return null;
        }
    }

    public String serializeGovernmentState(GovernmentState governmentState) {
        return writeJson(governmentState);
    }

    private List<GovernmentState.ConstitutionRule> loadRules(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<GovernmentState.ConstitutionRule>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private GovernmentState bootstrapGovernmentState(Room room, int[][] tiles, Instant now) {
        GovernmentState state = new GovernmentState();
        state.setConstitutionVersion(CONSTITUTION_VERSION);
        state.setTick(0);
        state.setLastTickAt(now);
        state.setLastBillAt(now);
        state.setNextElectionAt(now.plusMillis(electionIntervalMs));
        state.setNextBillSequence(1);
        state.setNextElectionSequence(1);
        state.setNextEventSequence(1);
        state.setNextOrderSequence(1);
        state.setNextCourtSequence(1);
        state.setNextPrecedentSequence(1);
        state.setNextActorSequence(1);
        state.setNextAgencySequence(1);
        state.setNextPressureSequence(1);
        state.setNextConflictSequence(1);
        state.setNextImplementationSequence(1);
        state.setNextHistoricalSequence(1);
        state.setConstitutionalRules(copyRules());
        state.setParties(defaultParties());
        state.setFederal(defaultFederalNode());
        state.setStates(defaultStates(tiles));
        state.setInstitutions(new ArrayList<>());
        state.setPrecedents(new ArrayList<>());
        state.setActors(defaultActors(state));
        state.setAdministrativeActions(new ArrayList<>());
        state.setPressureEvents(new ArrayList<>());
        state.setConflicts(new ArrayList<>());
        state.setImplementationOutcomes(new ArrayList<>());
        state.setHistoricalMarkers(new ArrayList<>());
        refreshInstitutionProfiles(state);
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "constitution/constitution-bootstrap-%s.json", room.getId()),
            Map.of(
                "revision_id", 0,
                "timestamp", now,
                "level", "federal",
                "origin_event", "bootstrap",
                "amendment_identifier", CONSTITUTION_VERSION,
                "summary", "Phase 1 constitutional rule set initialized",
                "affected_systems", List.of("government", "elections", "rights", "budgets"),
                "previous_rule_hash", "none",
                "new_rule_hash", Integer.toHexString(Objects.hash(room.getId(), CONSTITUTION_VERSION, baseRules.size()))
            )
        );
        return state;
    }

    private List<RoomBot> ensureBotCivics(Room room,
                                          GovernmentState governmentState,
                                          List<RoomBot> bots,
                                          int[][] tiles,
                                          Function<RoomBot, Map<String, Double>> profileProvider,
                                          Instant now) {
        List<RoomBot> dirtyBots = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            boolean changed = false;
            if (civicState == null) {
                civicState = bootstrapBotCivicState(room, bot, governmentState, tiles, profileProvider.apply(bot));
                changed = true;
            } else {
                changed |= repairBotCivicState(room, bot, civicState, governmentState, tiles, profileProvider.apply(bot));
            }
            if (changed) {
                bot.setCivicStateJson(writeJson(civicState));
                dirtyBots.add(bot);
            }
        }
        return dirtyBots;
    }

    private boolean repairBotCivicState(Room room,
                                        RoomBot bot,
                                        BotCivicState civicState,
                                        GovernmentState governmentState,
                                        int[][] tiles,
                                        Map<String, Double> profile) {
        boolean changed = false;
        String stateId = deriveStateId(bot, tiles);
        String districtId = deriveDistrictId(bot, tiles);
        if (!Objects.equals(civicState.getStateId(), stateId)) {
            civicState.setStateId(stateId);
            changed = true;
        }
        if (!Objects.equals(civicState.getDistrictId(), districtId)) {
            civicState.setDistrictId(districtId);
            changed = true;
        }
        if (civicState.getPartyId() == null || civicState.getPartyId().isBlank()) {
            civicState.setPartyId(closestPartyId(governmentState.getParties(), civicState));
            changed = true;
        }
        if (civicState.getHouseholdId() == null || civicState.getHouseholdId().isBlank()) {
            civicState.setHouseholdId(householdId(room, bot));
            changed = true;
        }
        if (civicState.getEmploymentStatus() == null || civicState.getEmploymentStatus().isBlank()) {
            applyProfileIdeology(civicState, profile);
            hydrateEconomicState(civicState, bot, tiles);
            changed = true;
        }
        return changed;
    }

    private BotCivicState bootstrapBotCivicState(Room room,
                                                 RoomBot bot,
                                                 GovernmentState governmentState,
                                                 int[][] tiles,
                                                 Map<String, Double> profile) {
        BotCivicState civicState = new BotCivicState();
        civicState.setHouseholdId(householdId(room, bot));
        civicState.setDistrictId(deriveDistrictId(bot, tiles));
        civicState.setStateId(deriveStateId(bot, tiles));
        civicState.setLegalStatus("CITIZEN");
        civicState.setAgeTier(ageTier(room, bot));
        applyProfileIdeology(civicState, profile);
        hydrateEconomicState(civicState, bot, tiles);
        civicState.setPartyId(closestPartyId(governmentState.getParties(), civicState));
        civicState.setRightsSatisfaction(clamp01(0.52d + profile.getOrDefault("consensus", 0.5d) * 0.18d));
        civicState.setInstitutionalTrust(clamp01(0.45d + profile.getOrDefault("clear", 0.25d) * 0.2d));
        civicState.setSafetyPerception(clamp01(0.40d + terrainServiceSignal(bot, tiles) * 0.4d));
        civicState.setServiceAccess(clamp01(terrainServiceSignal(bot, tiles)));
        civicState.setCivicParticipation(clamp01(0.32d + profile.getOrDefault("mobility", 0.3d) * 0.2d));
        civicState.setVoterEligible(!"YOUTH".equals(civicState.getAgeTier()) && "CITIZEN".equals(civicState.getLegalStatus()));
        civicState.setOfficeRole("CITIZEN");
        civicState.setTermState("NONE");
        civicState.setProceduralState("CLEAR");
        civicState.setSanctionState("NONE");
        return civicState;
    }

    private void ensureGovernmentShape(GovernmentState governmentState, int[][] tiles, Instant now) {
        if (governmentState.getConstitutionVersion() == null || governmentState.getConstitutionVersion().isBlank()) {
            governmentState.setConstitutionVersion(CONSTITUTION_VERSION);
        }
        if (governmentState.getConstitutionalRules() == null || governmentState.getConstitutionalRules().isEmpty()) {
            governmentState.setConstitutionalRules(copyRules());
        }
        if (governmentState.getParties() == null || governmentState.getParties().isEmpty()) {
            governmentState.setParties(defaultParties());
        }
        if (governmentState.getFederal() == null) {
            governmentState.setFederal(defaultFederalNode());
        }
        if (governmentState.getStates() == null || governmentState.getStates().isEmpty()) {
            governmentState.setStates(defaultStates(tiles));
        }
        if (governmentState.getNextElectionAt() == null) {
            governmentState.setNextElectionAt(now.plusMillis(electionIntervalMs));
        }
        if (governmentState.getExecutiveOrders() == null) {
            governmentState.setExecutiveOrders(new ArrayList<>());
        }
        if (governmentState.getCourtCases() == null) {
            governmentState.setCourtCases(new ArrayList<>());
        }
        if (governmentState.getInstitutions() == null) {
            governmentState.setInstitutions(new ArrayList<>());
        }
        if (governmentState.getPrecedents() == null) {
            governmentState.setPrecedents(new ArrayList<>());
        }
        if (governmentState.getActors() == null) {
            governmentState.setActors(new ArrayList<>());
        }
        if (governmentState.getAdministrativeActions() == null) {
            governmentState.setAdministrativeActions(new ArrayList<>());
        }
        if (governmentState.getPressureEvents() == null) {
            governmentState.setPressureEvents(new ArrayList<>());
        }
        if (governmentState.getConflicts() == null) {
            governmentState.setConflicts(new ArrayList<>());
        }
        if (governmentState.getImplementationOutcomes() == null) {
            governmentState.setImplementationOutcomes(new ArrayList<>());
        }
        if (governmentState.getHistoricalMarkers() == null) {
            governmentState.setHistoricalMarkers(new ArrayList<>());
        }
        if (governmentState.getNextPrecedentSequence() <= 0) {
            governmentState.setNextPrecedentSequence(1);
        }
        if (governmentState.getNextActorSequence() <= 0) {
            governmentState.setNextActorSequence(1);
        }
        if (governmentState.getNextAgencySequence() <= 0) {
            governmentState.setNextAgencySequence(1);
        }
        if (governmentState.getNextPressureSequence() <= 0) {
            governmentState.setNextPressureSequence(1);
        }
        if (governmentState.getNextConflictSequence() <= 0) {
            governmentState.setNextConflictSequence(1);
        }
        if (governmentState.getNextImplementationSequence() <= 0) {
            governmentState.setNextImplementationSequence(1);
        }
        if (governmentState.getNextHistoricalSequence() <= 0) {
            governmentState.setNextHistoricalSequence(1);
        }
        if (governmentState.getActors().isEmpty()) {
            governmentState.setActors(defaultActors(governmentState));
        }
        refreshInstitutionProfiles(governmentState);
    }

    private void refreshGovernmentAggregates(GovernmentState governmentState, List<RoomBot> bots) {
        Map<String, Integer> memberCount = new HashMap<>();
        double trustSum = 0d;
        double serviceSum = 0d;
        long revenue = 0L;
        int active = 0;
        int petitioning = 0;
        int reviewing = 0;
        int sanctioned = 0;
        Map<String, List<BotCivicState>> civicsByState = new HashMap<>();
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            active++;
            trustSum += civicState.getInstitutionalTrust();
            serviceSum += civicState.getServiceAccess();
            revenue += Math.max(0, civicState.getWageBand()) * 12L + 8L;
            memberCount.merge(civicState.getPartyId(), 1, Integer::sum);
            civicsByState.computeIfAbsent(civicState.getStateId(), key -> new ArrayList<>()).add(civicState);
            if ("PETITIONING".equalsIgnoreCase(civicState.getProceduralState())) {
                petitioning++;
            }
            if ("REVIEW".equalsIgnoreCase(civicState.getProceduralState())
                || "COMPLIANCE_REVIEW".equalsIgnoreCase(civicState.getSanctionState())
                || "STAYED_REVIEW".equalsIgnoreCase(civicState.getSanctionState())) {
                reviewing++;
            }
            if (!"NONE".equalsIgnoreCase(civicState.getSanctionState())
                && !"COMPLIANCE_REVIEW".equalsIgnoreCase(civicState.getSanctionState())
                && !"STAYED_REVIEW".equalsIgnoreCase(civicState.getSanctionState())) {
                sanctioned++;
            }
        }
        governmentState.setActiveBotCount(active);
        governmentState.setPetitioningBotCount(petitioning);
        governmentState.setReviewBotCount(reviewing);
        governmentState.setSanctionedBotCount(sanctioned);
        governmentState.setPetitioningShare(active == 0 ? 0.0d : petitioning / (double) active);
        governmentState.setSanctionedShare(active == 0 ? 0.0d : sanctioned / (double) active);
        for (GovernmentState.PoliticalParty party : governmentState.getParties()) {
            int members = memberCount.getOrDefault(party.getPartyId(), 0);
            party.setMemberCount(members);
            party.setInfluence(active == 0 ? 0d : members / (double) active);
        }
        GovernmentState.GovernmentNode federal = governmentState.getFederal();
        federal.setPublicTrust(active == 0 ? 0.5d : trustSum / active);
        federal.setServiceAccess(active == 0 ? 0.5d : serviceSum / active);
        updateBudget(federal.getBudget(), "federal", "federal", revenue, active);
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            List<BotCivicState> civics = civicsByState.getOrDefault(stateNode.getNodeId(), List.of());
            double stateTrust = 0d;
            double stateService = 0d;
            long stateRevenue = 0L;
            for (BotCivicState civicState : civics) {
                stateTrust += civicState.getInstitutionalTrust();
                stateService += civicState.getServiceAccess();
                stateRevenue += Math.max(0, civicState.getWageBand()) * 5L + 4L;
            }
            int size = civics.size();
            stateNode.setPublicTrust(size == 0 ? federal.getPublicTrust() : stateTrust / size);
            stateNode.setServiceAccess(size == 0 ? federal.getServiceAccess() : stateService / size);
            updateBudget(stateNode.getBudget(), "state", stateNode.getNodeId(), stateRevenue, size);
        }
        applyGovernmentLayerFeedback(governmentState, federal);
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            applyGovernmentLayerFeedback(governmentState, stateNode);
        }
    }

    private void applyGovernmentLayerFeedback(GovernmentState governmentState, GovernmentState.GovernmentNode node) {
        if (governmentState == null || node == null) {
            return;
        }
        String jurisdictionId = node.getNodeId();
        double trust = node.getPublicTrust();
        double service = node.getServiceAccess();
        for (GovernmentState.ImplementationOutcome outcome : governmentState.getImplementationOutcomes()) {
            if (!jurisdictionId.equalsIgnoreCase(outcome.getJurisdictionId())) {
                continue;
            }
            if ("SUCCEEDING".equalsIgnoreCase(outcome.getStatus())) {
                trust += implementationSuccessTrustDelta;
                service += implementationSuccessServiceDelta;
            } else if ("STALLING".equalsIgnoreCase(outcome.getStatus())) {
                trust += implementationStallTrustDelta;
            } else if ("BACKFIRE".equalsIgnoreCase(outcome.getStatus())) {
                trust += implementationBackfireTrustDelta;
                service += implementationBackfireServiceDelta;
            }
        }
        for (GovernmentState.FederalStateConflict conflict : governmentState.getConflicts()) {
            if (!"federal".equalsIgnoreCase(node.getLevel()) && !jurisdictionId.equalsIgnoreCase(conflict.getStateId())) {
                continue;
            }
            if ("SETTLED".equalsIgnoreCase(conflict.getStatus())) {
                trust += conflictSettledTrustDelta;
            } else {
                trust += conflictActiveTrustDelta;
                service += conflictActiveServiceDelta;
            }
        }
        double pressurePenalty = governmentState.getPressureEvents().stream()
            .filter(event -> {
                GovernmentState.InstitutionProfile institution = findInstitutionById(governmentState, event.getTargetInstitutionId());
                return institution != null && jurisdictionId.equalsIgnoreCase(institution.getJurisdictionId());
            })
            .mapToDouble(GovernmentState.PoliticalPressure::getIntensity)
            .sum();
        trust -= Math.min(pressureTrustPenaltyCap, pressurePenalty * pressureTrustPenaltyScale);
        node.setPublicTrust(clamp01(trust));
        node.setServiceAccess(clamp01(service));
    }

    private void refreshInstitutionProfiles(GovernmentState governmentState) {
        if (governmentState == null) {
            return;
        }
        List<GovernmentState.InstitutionProfile> profiles = new ArrayList<>();
        GovernmentState.GovernmentNode federal = governmentState.getFederal();
        profiles.add(buildInstitutionProfile(governmentState, federal, "EXECUTIVE", federal.getExecutivePartyId(), "expedited-order-review"));
        profiles.add(buildInstitutionProfile(governmentState, federal, "LOWER_CHAMBER", federal.getLowerChamberPartyId(), "committee-gatekeeping"));
        profiles.add(buildInstitutionProfile(governmentState, federal, "UPPER_CHAMBER", federal.getUpperChamberPartyId(), "filibuster-risk"));
        profiles.add(buildInstitutionProfile(governmentState, federal, "COURT", federal.getChiefJusticePartyId(), "docket-cap"));
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            profiles.add(buildInstitutionProfile(governmentState, stateNode, "EXECUTIVE", stateNode.getExecutivePartyId(), "emergency-order-review"));
            profiles.add(buildInstitutionProfile(governmentState, stateNode, "LOWER_CHAMBER", stateNode.getLowerChamberPartyId(), "district-bargaining"));
            profiles.add(buildInstitutionProfile(governmentState, stateNode, "UPPER_CHAMBER", stateNode.getUpperChamberPartyId(), "district-bargaining"));
            profiles.add(buildInstitutionProfile(governmentState, stateNode, "COURT", stateNode.getChiefJusticePartyId(), "state-docket-cap"));
        }
        governmentState.setInstitutions(profiles);
    }

    private GovernmentState.InstitutionProfile buildInstitutionProfile(GovernmentState governmentState,
                                                                       GovernmentState.GovernmentNode node,
                                                                       String branch,
                                                                       String partyId,
                                                                       String proceduralRule) {
        GovernmentState.InstitutionProfile profile = new GovernmentState.InstitutionProfile();
        profile.setInstitutionId(node.getNodeId() + "-" + branch.toLowerCase(Locale.ROOT));
        profile.setBranch(branch);
        profile.setLevel(node.getLevel());
        profile.setJurisdictionId(node.getNodeId());
        profile.setDisplayName(node.getDisplayName() + " " + humanizeToken(branch));
        GovernmentState.PoliticalParty party = resolveParty(governmentState, partyId);
        profile.setLibertyLean(party == null ? 0.5d : party.getLiberty());
        profile.setOrderLean(party == null ? 0.5d : party.getOrder());
        profile.setWelfareLean(party == null ? 0.5d : party.getWelfare());
        profile.setGrowthLean(party == null ? 0.5d : party.getGrowth());
        profile.setLegitimacy(clamp01(node.getPublicTrust() * 0.7d + node.getServiceAccess() * 0.3d));
        profile.setWorkload(calculateInstitutionWorkload(governmentState, node, branch));
        profile.setCapacityPerTick(calculateInstitutionCapacity(profile));
        profile.setProceduralRule(proceduralRule);
        return profile;
    }

    private int calculateInstitutionWorkload(GovernmentState governmentState,
                                             GovernmentState.GovernmentNode node,
                                             String branch) {
        String scope = node.getLevel();
        String jurisdictionId = node.getNodeId();
        int bills = (int) governmentState.getBills().stream()
            .filter(bill -> matchesJurisdiction(bill.getScope(), bill.getStateId(), scope, jurisdictionId))
            .filter(bill -> !"ENACTED".equalsIgnoreCase(bill.getLawStatus()) && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus()))
            .count();
        int orders = (int) governmentState.getExecutiveOrders().stream()
            .filter(order -> matchesJurisdiction(order.getScope(), order.getJurisdictionId(), scope, jurisdictionId))
            .filter(order -> !"SUPERSEDED".equalsIgnoreCase(order.getStatus()) && !"BLOCKED".equalsIgnoreCase(order.getStatus()))
            .count();
        int cases = (int) governmentState.getCourtCases().stream()
            .filter(courtCase -> matchesCourtJurisdiction(courtCase, scope))
            .filter(courtCase -> !"DECIDED".equalsIgnoreCase(courtCase.getStatus()))
            .count();
        int adminActions = (int) governmentState.getAdministrativeActions().stream()
            .filter(action -> matchesJurisdiction(action.getScope(), action.getJurisdictionId(), scope, jurisdictionId))
            .filter(action -> !"CLOSED".equalsIgnoreCase(action.getStatus()))
            .count();
        return switch (branch) {
            case "EXECUTIVE" -> orders + adminActions + Math.max(0, bills / 2);
            case "LOWER_CHAMBER", "UPPER_CHAMBER" -> bills;
            case "COURT" -> cases + (int) governmentState.getAdministrativeActions().stream()
                .filter(GovernmentState.AdministrativeAction::isAppealed)
                .filter(action -> matchesJurisdiction(action.getScope(), action.getJurisdictionId(), scope, jurisdictionId))
                .count();
            default -> bills + orders + cases + adminActions;
        };
    }

    private int calculateInstitutionCapacity(GovernmentState.InstitutionProfile profile) {
        double legitimacy = profile.getLegitimacy();
        int workload = profile.getWorkload();
        int base = switch (profile.getBranch()) {
            case "COURT" -> 1;
            case "EXECUTIVE" -> 2;
            default -> 2;
        };
        int capacity = base + (int) Math.round(legitimacy * 2.5d) - (workload >= 4 ? 1 : 0);
        return Math.max(1, capacity);
    }

    private List<GovernmentState.CivicActor> defaultActors(GovernmentState governmentState) {
        List<GovernmentState.CivicActor> actors = new ArrayList<>();
        actors.add(actor(governmentState, "Federal Transit Board", "AGENCY", "federal", "federal", "party-order-build", List.of("infrastructure", "growth")));
        actors.add(actor(governmentState, "Civil Liberties Union", "ADVOCACY", "federal", "federal", "party-liberty-green", List.of("rights-process", "judicial review")));
        actors.add(actor(governmentState, "Public Services Federation", "UNION", "federal", "federal", "party-civic-labor", List.of("services", "labor")));
        actors.add(actor(governmentState, "National Manufacturers Council", "INDUSTRY", "federal", "federal", "party-order-build", List.of("infrastructure", "administrative deference")));
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            actors.add(actor(
                governmentState,
                stateNode.getDisplayName() + " Attorney General",
                "STATE_ATTORNEY_GENERAL",
                "state",
                stateNode.getNodeId(),
                stateNode.getExecutivePartyId(),
                List.of("federalism", "rights-process")
            ));
        }
        return actors;
    }

    private GovernmentState.CivicActor actor(GovernmentState governmentState,
                                             String name,
                                             String actorType,
                                             String scope,
                                             String jurisdictionId,
                                             String alignedPartyId,
                                             List<String> agendas) {
        GovernmentState.CivicActor actor = new GovernmentState.CivicActor();
        actor.setActorId(String.format(Locale.ROOT, "actor-%05d", governmentState.getNextActorSequence()));
        governmentState.setNextActorSequence(governmentState.getNextActorSequence() + 1);
        actor.setName(name);
        actor.setActorType(actorType);
        actor.setScope(scope);
        actor.setJurisdictionId(jurisdictionId);
        actor.setAlignedPartyId(alignedPartyId);
        actor.setAgendas(new ArrayList<>(agendas));
        actor.setInfluence(0.45d);
        return actor;
    }

    private void refreshActorInfluence(GovernmentState governmentState) {
        if (governmentState == null || governmentState.getActors() == null) {
            return;
        }
        for (GovernmentState.CivicActor actor : governmentState.getActors()) {
            if ("STATE_ATTORNEY_GENERAL".equalsIgnoreCase(actor.getActorType())) {
                GovernmentState.GovernmentNode stateNode = resolveStateNode(governmentState, actor.getJurisdictionId());
                actor.setAlignedPartyId(stateNode.getExecutivePartyId());
                actor.setInfluence(clamp01(0.35d + stateNode.getPublicTrust() * 0.25d + stateNode.getServiceAccess() * 0.2d));
                continue;
            }
            GovernmentState.PoliticalParty party = resolveParty(governmentState, actor.getAlignedPartyId());
            double baseInfluence = party == null ? 0.35d : 0.25d + party.getInfluence() * 0.8d;
            actor.setInfluence(clamp01(baseInfluence + governmentState.getFederal().getPublicTrust() * 0.08d));
        }
    }

    private boolean maybeGeneratePoliticalPressure(GovernmentState governmentState, List<RoomBot> bots, Instant now) {
        double avgTrust = averageTrust(bots);
        double avgService = averageServiceAccess(bots);
        double petitioningShare = petitioningShare(bots);
        GovernmentState.GovernmentBill activeBill = governmentState.getBills().stream()
            .filter(bill -> !"ENACTED".equalsIgnoreCase(bill.getLawStatus()) && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus()))
            .findFirst()
            .orElse(null);
        if (avgTrust >= 0.50d && avgService >= 0.48d && activeBill == null && petitioningShare < 0.12d) {
            return false;
        }
        GovernmentState.InstitutionProfile targetInstitution = activeBill != null
            ? findInstitution(
                governmentState,
                "state".equalsIgnoreCase(activeBill.getScope()) ? activeBill.getStateId() : governmentState.getFederal().getNodeId(),
                "LOWER_CHAMBER")
            : findInstitution(governmentState, governmentState.getFederal().getNodeId(), avgService < avgTrust ? "EXECUTIVE" : "COURT");
        GovernmentState.CivicActor sourceActor = selectPressureActor(governmentState, activeBill == null ? null : activeBill.getPolicyDomain(), targetInstitution);
        if (sourceActor == null || targetInstitution == null) {
            return false;
        }
        GovernmentState.PoliticalPressure pressure = new GovernmentState.PoliticalPressure();
        pressure.setEventId(String.format(Locale.ROOT, "pressure-%05d", governmentState.getNextPressureSequence()));
        governmentState.setNextPressureSequence(governmentState.getNextPressureSequence() + 1);
        pressure.setPressureType(avgTrust < 0.42d || petitioningShare > 0.18d ? "PROTEST" : "LOBBYING");
        pressure.setSourceActorId(sourceActor.getActorId());
        pressure.setTargetInstitutionId(targetInstitution.getInstitutionId());
        double intensity = clamp01((1.0d - Math.min(avgTrust, avgService)) * 0.8d
            + sourceActor.getInfluence() * 0.35d
            + petitioningShare * 0.55d);
        pressure.setIntensity(intensity);
        pressure.setEffect(intensity > pressureAgendaShockThreshold ? "agenda shock" : "committee pressure");
        pressure.setSummary(String.format(Locale.ROOT,
            "%s is pressing %s over %s conditions%s.",
            sourceActor.getName(),
            targetInstitution.getDisplayName(),
            avgService < avgTrust ? "service delivery" : "institutional legitimacy",
            petitioningShare > 0.15d ? " as petitioning bots raise procedural grievances" : ""));
        pressure.setCreatedAt(now);
        governmentState.getPressureEvents().add(0, pressure);
        if (governmentState.getPressureEvents().size() > 14) {
            governmentState.setPressureEvents(new ArrayList<>(governmentState.getPressureEvents().subList(0, 14)));
        }
        if (activeBill != null
            && List.of("CommitteeReview", "AmendmentDrafting", "CoalitionWhip", "DebateWindow").contains(activeBill.getStage())) {
            String amendment = intensity > pressureHighThreshold
                ? "Emergency amendment added in response to " + sourceActor.getName()
                : "Committee concession offered after " + sourceActor.getName() + " outreach";
            if (!activeBill.getAmendmentSummaries().contains(amendment)) {
                activeBill.getAmendmentSummaries().add(amendment);
            }
        }
        return true;
    }

    private boolean applyBotGovernmentEnforcement(GovernmentState governmentState, List<RoomBot> bots, Instant now) {
        boolean changed = false;
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            if (applyBotLegalProfile(governmentState, civicState, now)) {
                bot.setCivicStateJson(writeJson(civicState));
                changed = true;
            }
        }
        return changed;
    }

    private boolean applyBotLegalProfile(GovernmentState governmentState, BotCivicState civicState, Instant now) {
        String jurisdictionId = civicState.getStateId() == null || civicState.getStateId().isBlank()
            ? "state-1"
            : civicState.getStateId();
        List<GovernmentState.GovernmentBill> applicableBills = governmentState.getBills().stream()
            .filter(bill -> "ENACTED".equalsIgnoreCase(bill.getLawStatus()))
            .filter(bill -> bill.getEffectiveAt() == null || !bill.getEffectiveAt().isAfter(now))
            .filter(bill -> appliesToBotJurisdiction(bill.getScope(), bill.getStateId(), jurisdictionId))
            .toList();
        List<GovernmentState.ExecutiveOrder> applicableOrders = governmentState.getExecutiveOrders().stream()
            .filter(order -> List.of("ACTIVE", "REVIEWED", "UNDER_REVIEW").contains(String.valueOf(order.getStatus()).toUpperCase(Locale.ROOT)))
            .filter(order -> order.getEffectiveAt() == null || !order.getEffectiveAt().isAfter(now))
            .filter(order -> appliesToBotJurisdiction(order.getScope(), order.getJurisdictionId(), jurisdictionId))
            .toList();
        List<GovernmentState.AdministrativeAction> applicableAdministrativeActions = governmentState.getAdministrativeActions().stream()
            .filter(action -> !"CLOSED".equalsIgnoreCase(action.getStatus()))
            .filter(action -> appliesToBotJurisdiction(action.getScope(), action.getJurisdictionId(), jurisdictionId))
            .toList();
        if (applicableBills.isEmpty() && applicableOrders.isEmpty() && applicableAdministrativeActions.isEmpty()) {
            return resetBotLegalProfile(civicState);
        }

        long rightsMeasures = countPolicyMeasures(applicableBills, applicableOrders, "rights-process");
        long laborMeasures = countPolicyMeasures(applicableBills, applicableOrders, "labor");
        long housingMeasures = countPolicyMeasures(applicableBills, applicableOrders, "housing");
        long serviceMeasures = countPolicyMeasures(applicableBills, applicableOrders, "services");
        long activeEnforcement = applicableAdministrativeActions.stream()
            .filter(action -> List.of("ENFORCEMENT", "APPEAL_REVIEW").contains(String.valueOf(action.getStage()).toUpperCase(Locale.ROOT)))
            .count();
        long reviewOnlyActions = applicableAdministrativeActions.stream()
            .filter(action -> List.of("NOTICE_COMMENT", "FINAL_RULE", "GUIDANCE").contains(String.valueOf(action.getStage()).toUpperCase(Locale.ROOT)))
            .count();
        long stayedActions = applicableAdministrativeActions.stream()
            .filter(action -> action.isAppealed() || action.isStayed())
            .count();
        boolean activeConflict = governmentState.getConflicts().stream()
            .anyMatch(conflict -> jurisdictionId.equalsIgnoreCase(conflict.getStateId())
                && !"SETTLED".equalsIgnoreCase(conflict.getStatus()));
        boolean dueProcessFloor = hasConstitutionProtection(governmentState, "due-process")
            || hasConstitutionProtection(governmentState, "equal-protection");

        double enforcementPressure = activeEnforcement * 0.18d + reviewOnlyActions * 0.07d;
        enforcementPressure += laborMeasures * laborExposure(civicState);
        enforcementPressure += housingMeasures * housingExposure(civicState);
        enforcementPressure += serviceMeasures * serviceExposure(civicState);
        if (activeConflict) {
            enforcementPressure += 0.10d;
        }
        enforcementPressure -= rightsMeasures * 0.15d;
        enforcementPressure -= stayedActions * 0.10d;
        enforcementPressure = clamp01(enforcementPressure);

        String previousSanction = civicState.getSanctionState();
        String previousProcedure = civicState.getProceduralState();
        double previousRights = civicState.getRightsSatisfaction();
        double previousTrust = civicState.getInstitutionalTrust();
        double previousParticipation = civicState.getCivicParticipation();
        double previousService = civicState.getServiceAccess();

        String sanctionState;
        if (stayedActions > 0 && enforcementPressure > 0.20d) {
            sanctionState = "STAYED_REVIEW";
        } else if (enforcementPressure > 0.64d) {
            sanctionState = "ACTIVE_SANCTION";
        } else if (enforcementPressure > 0.38d) {
            sanctionState = "UNDER_NOTICE";
        } else if (reviewOnlyActions > 0 || rightsMeasures > 0) {
            sanctionState = "COMPLIANCE_REVIEW";
        } else {
            sanctionState = "NONE";
        }

        String proceduralState;
        if ("ACTIVE_SANCTION".equals(sanctionState) || "UNDER_NOTICE".equals(sanctionState)) {
            proceduralState = dueProcessFloor ? "REVIEW" : "PETITIONING";
        } else if (rightsMeasures > 0 && civicState.getRightsSatisfaction() < 0.58d) {
            proceduralState = "PETITIONING";
        } else if ("COMPLIANCE_REVIEW".equals(sanctionState) || stayedActions > 0) {
            proceduralState = "REVIEW";
        } else {
            proceduralState = "CLEAR";
        }

        double rightsDelta = rightsMeasures * 0.03d - enforcementPressure * 0.07d;
        rightsDelta += stayedActions * 0.02d + (dueProcessFloor ? 0.01d : -0.01d);
        if (activeConflict) {
            rightsDelta -= 0.02d;
        }
        double trustDelta = rightsMeasures * 0.012d - enforcementPressure * 0.05d;
        trustDelta += stayedActions * 0.015d;
        if ("PETITIONING".equals(proceduralState)) {
            trustDelta -= 0.01d;
        }
        double participationDelta = ("PETITIONING".equals(proceduralState) ? 0.05d : 0.0d)
            + ("REVIEW".equals(proceduralState) ? 0.03d : 0.0d)
            - ("CLEAR".equals(proceduralState) ? 0.005d : 0.0d);
        double serviceDelta = serviceMeasures * 0.015d + housingMeasures * 0.01d;
        if ("ACTIVE_SANCTION".equals(sanctionState)) {
            serviceDelta -= 0.03d;
        } else if ("UNDER_NOTICE".equals(sanctionState)) {
            serviceDelta -= 0.015d;
        }

        civicState.setSanctionState(sanctionState);
        civicState.setProceduralState(proceduralState);
        civicState.setRightsSatisfaction(clamp01(civicState.getRightsSatisfaction() + rightsDelta));
        civicState.setInstitutionalTrust(clamp01(civicState.getInstitutionalTrust() + trustDelta));
        civicState.setCivicParticipation(clamp01(civicState.getCivicParticipation() + participationDelta));
        civicState.setServiceAccess(clamp01(civicState.getServiceAccess() + serviceDelta));
        if ("ACTIVE_SANCTION".equals(sanctionState) && civicState.getWageBand() <= 1) {
            civicState.setEmploymentStatus("SEEKING");
        }
        if ("SERVICE".equalsIgnoreCase(civicState.getOccupationSector()) && serviceMeasures > 0) {
            civicState.setSavingsPressureBand(Math.max(0, civicState.getSavingsPressureBand() - 1));
        }
        if (housingMeasures > 0 && civicState.getCostBurdenBand() > 0) {
            civicState.setCostBurdenBand(Math.max(0, civicState.getCostBurdenBand() - 1));
        }

        return !Objects.equals(previousSanction, civicState.getSanctionState())
            || !Objects.equals(previousProcedure, civicState.getProceduralState())
            || Math.abs(previousRights - civicState.getRightsSatisfaction()) > 0.0001d
            || Math.abs(previousTrust - civicState.getInstitutionalTrust()) > 0.0001d
            || Math.abs(previousParticipation - civicState.getCivicParticipation()) > 0.0001d
            || Math.abs(previousService - civicState.getServiceAccess()) > 0.0001d;
    }

    private boolean resetBotLegalProfile(BotCivicState civicState) {
        boolean changed = !"NONE".equals(civicState.getSanctionState()) || !"CLEAR".equals(civicState.getProceduralState());
        civicState.setSanctionState("NONE");
        civicState.setProceduralState("CLEAR");
        return changed;
    }

    private boolean advanceAdministrativeState(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            if (!"ENACTED".equalsIgnoreCase(bill.getLawStatus())) {
                continue;
            }
            if (findAdministrativeActionForSource(governmentState, bill.getBillId(), null) != null) {
                continue;
            }
            GovernmentState.AdministrativeAction action = new GovernmentState.AdministrativeAction();
            action.setActionId(String.format(Locale.ROOT, "admin-%05d", governmentState.getNextAgencySequence()));
            governmentState.setNextAgencySequence(governmentState.getNextAgencySequence() + 1);
            action.setAgencyName(agencyNameForPolicyDomain(bill.getPolicyDomain(), bill.getScope()));
            action.setActionType("RULEMAKING");
            action.setScope(bill.getScope());
            action.setJurisdictionId("state".equalsIgnoreCase(bill.getScope()) ? bill.getStateId() : governmentState.getFederal().getNodeId());
            action.setStatus("OPEN");
            action.setSourceBillId(bill.getBillId());
            action.setTitle("Implement " + bill.getTitle());
            action.setSummary("Agency rulemaking opened to operationalize enacted legislation.");
            action.setStage("NOTICE_COMMENT");
            action.setCreatedAt(now);
            action.setUpdatedAt(now);
            governmentState.getAdministrativeActions().add(0, action);
            changed = true;
        }
        for (GovernmentState.ExecutiveOrder order : governmentState.getExecutiveOrders()) {
            if (!List.of("ACTIVE", "REVIEWED").contains(String.valueOf(order.getStatus()).toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (findAdministrativeActionForSource(governmentState, null, order.getOrderId()) != null) {
                continue;
            }
            GovernmentState.AdministrativeAction action = new GovernmentState.AdministrativeAction();
            action.setActionId(String.format(Locale.ROOT, "admin-%05d", governmentState.getNextAgencySequence()));
            governmentState.setNextAgencySequence(governmentState.getNextAgencySequence() + 1);
            action.setAgencyName(agencyNameForPolicyDomain(order.getPolicyDomain(), order.getScope()));
            action.setActionType("ENFORCEMENT_GUIDANCE");
            action.setScope(order.getScope());
            action.setJurisdictionId(order.getJurisdictionId());
            action.setStatus("OPEN");
            action.setSourceOrderId(order.getOrderId());
            action.setTitle("Carry out " + order.getSummary());
            action.setSummary("Executive implementation guidance is being translated into administrative action.");
            action.setStage("GUIDANCE");
            action.setCreatedAt(now);
            action.setUpdatedAt(now);
            governmentState.getAdministrativeActions().add(0, action);
            changed = true;
        }
        for (GovernmentState.AdministrativeAction action : governmentState.getAdministrativeActions()) {
            if ("CLOSED".equalsIgnoreCase(action.getStatus())) {
                continue;
            }
            action.setUpdatedAt(now);
            boolean appealed = governmentState.getPressureEvents().stream()
                .anyMatch(event -> event.getCreatedAt() != null
                    && !event.getCreatedAt().isBefore(now.minusMillis(maintenanceIntervalMs * 3))
                    && event.getIntensity() > pressureHighThreshold);
            if (appealed) {
                action.setAppealed(true);
                action.setStayed("NOTICE_COMMENT".equalsIgnoreCase(action.getStage()) || "FINAL_RULE".equalsIgnoreCase(action.getStage()));
            }
            switch (String.valueOf(action.getStage()).toUpperCase(Locale.ROOT)) {
                case "NOTICE_COMMENT" -> action.setStage("FINAL_RULE");
                case "FINAL_RULE", "GUIDANCE" -> action.setStage("ENFORCEMENT");
                case "ENFORCEMENT" -> action.setStage(action.isAppealed() ? "APPEAL_REVIEW" : "CLOSED");
                case "APPEAL_REVIEW" -> action.setStage("CLOSED");
                default -> {
                }
            }
            action.setStatus("CLOSED".equalsIgnoreCase(action.getStage()) ? "CLOSED" : "OPEN");
            changed = true;
        }
        if (governmentState.getAdministrativeActions().size() > 16) {
            governmentState.setAdministrativeActions(new ArrayList<>(governmentState.getAdministrativeActions().subList(0, 16)));
        }
        return changed;
    }

    private boolean updateFederalStateConflicts(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        GovernmentState.GovernmentBill federalBill = governmentState.getBills().stream()
            .filter(bill -> "federal".equalsIgnoreCase(bill.getScope()))
            .filter(bill -> "ENACTED".equalsIgnoreCase(bill.getLawStatus()))
            .filter(bill -> bill.getPreemptionScope() != null && !bill.getPreemptionScope().isBlank())
            .findFirst()
            .orElse(null);
        GovernmentState.ExecutiveOrder federalOrder = governmentState.getExecutiveOrders().stream()
            .filter(order -> "federal".equalsIgnoreCase(order.getScope()))
            .filter(order -> List.of("ACTIVE", "REVIEWED").contains(String.valueOf(order.getStatus()).toUpperCase(Locale.ROOT)))
            .findFirst()
            .orElse(null);
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            boolean mismatched = !Objects.equals(stateNode.getExecutivePartyId(), governmentState.getFederal().getExecutivePartyId());
            GovernmentState.FederalStateConflict conflict = findOpenConflict(governmentState, stateNode.getNodeId());
            if ((federalBill != null || federalOrder != null) && mismatched) {
                if (conflict == null) {
                    conflict = new GovernmentState.FederalStateConflict();
                    conflict.setConflictId(String.format(Locale.ROOT, "conflict-%05d", governmentState.getNextConflictSequence()));
                    governmentState.setNextConflictSequence(governmentState.getNextConflictSequence() + 1);
                    conflict.setStateId(stateNode.getNodeId());
                    conflict.setCreatedAt(now);
                    governmentState.getConflicts().add(0, conflict);
                }
                conflict.setFederalPartyId(governmentState.getFederal().getExecutivePartyId());
                conflict.setStatePartyId(stateNode.getExecutivePartyId());
                conflict.setConflictType(federalBill != null ? "PREEMPTION_DISPUTE" : "ORDER_RESISTANCE");
                conflict.setRelatedBillId(federalBill == null ? null : federalBill.getBillId());
                conflict.setRelatedOrderId(federalOrder == null ? null : federalOrder.getOrderId());
                conflict.setSummary(String.format(Locale.ROOT,
                    "%s is resisting %s implementation pressure from the federal executive.",
                    stateNode.getDisplayName(),
                    federalBill != null ? federalBill.getTitle() : federalOrder.getSummary()));
                conflict.setStatus(governmentState.getCourtCases().stream().anyMatch(caseItem -> !"DECIDED".equalsIgnoreCase(caseItem.getStatus()))
                    ? "IN_LITIGATION"
                    : "NEGOTIATION");
                changed = true;
            } else if (conflict != null && !"SETTLED".equalsIgnoreCase(conflict.getStatus())) {
                conflict.setStatus("SETTLED");
                conflict.setResolvedAt(now);
                changed = true;
            }
        }
        if (governmentState.getConflicts().size() > 12) {
            governmentState.setConflicts(new ArrayList<>(governmentState.getConflicts().subList(0, 12)));
        }
        return changed;
    }

    private boolean updateImplementationOutcomes(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            if (!"ENACTED".equalsIgnoreCase(bill.getLawStatus()) || bill.getEffectiveAt() == null || now.isBefore(bill.getEffectiveAt())) {
                continue;
            }
            changed |= upsertImplementationOutcome(governmentState, now, bill.getBillId(), "BILL", bill.getTitle(),
                "state".equalsIgnoreCase(bill.getScope()) ? bill.getStateId() : governmentState.getFederal().getNodeId(),
                bill.getPolicyDomain(), bill.getFundingChanges(), bill.getPreemptionScope());
        }
        for (GovernmentState.ExecutiveOrder order : governmentState.getExecutiveOrders()) {
            if (!List.of("ACTIVE", "REVIEWED").contains(String.valueOf(order.getStatus()).toUpperCase(Locale.ROOT))
                || order.getEffectiveAt() == null
                || now.isBefore(order.getEffectiveAt())) {
                continue;
            }
            changed |= upsertImplementationOutcome(governmentState, now, order.getOrderId(), "ORDER", order.getSummary(),
                order.getJurisdictionId(), order.getPolicyDomain(), order.getFundingChanges(), order.getPreemptionScope());
        }
        if (governmentState.getImplementationOutcomes().size() > 16) {
            governmentState.setImplementationOutcomes(new ArrayList<>(governmentState.getImplementationOutcomes().subList(0, 16)));
        }
        return changed;
    }

    private boolean upsertImplementationOutcome(GovernmentState governmentState,
                                                Instant now,
                                                String sourceId,
                                                String sourceType,
                                                String title,
                                                String jurisdictionId,
                                                String policyDomain,
                                                Map<String, Long> fundingChanges,
                                                String preemptionScope) {
        GovernmentState.ImplementationOutcome outcome = findImplementationOutcome(governmentState, sourceId);
        GovernmentState.GovernmentNode node = "state".equalsIgnoreCase(jurisdictionId) || jurisdictionId == null || "federal".equalsIgnoreCase(jurisdictionId)
            ? resolveStateNode(governmentState, jurisdictionId)
            : resolveStateNode(governmentState, jurisdictionId);
        if ("federal".equalsIgnoreCase(jurisdictionId)) {
            node = governmentState.getFederal();
        }
        String targetNodeId = node.getNodeId();
        GovernmentState.InstitutionProfile executive = findInstitution(governmentState, node.getNodeId(), "EXECUTIVE");
        double capacity = clamp01(node.getPublicTrust() * 0.35d
            + node.getServiceAccess() * 0.35d
            + (executive == null ? 0.5d : executive.getLegitimacy()) * 0.3d);
        long fundingTotal = fundingChanges == null ? 0L : fundingChanges.values().stream().mapToLong(Long::longValue).sum();
        double score = capacity + Math.min(0.25d, fundingTotal / 200d);
        if (preemptionScope != null && preemptionScope.toLowerCase(Locale.ROOT).contains("override")) {
            score -= 0.08d;
        }
        if (governmentState.getConflicts().stream().anyMatch(conflict -> targetNodeId.equalsIgnoreCase(conflict.getStateId())
            && !"SETTLED".equalsIgnoreCase(conflict.getStatus()))) {
            score -= 0.18d;
        }
        if (governmentState.getPressureEvents().stream().anyMatch(event -> event.getIntensity() > pressureHighThreshold)) {
            score -= 0.08d;
        }
        score = clamp01(score);
        if (outcome == null) {
            outcome = new GovernmentState.ImplementationOutcome();
            outcome.setOutcomeId(String.format(Locale.ROOT, "implementation-%05d", governmentState.getNextImplementationSequence()));
            governmentState.setNextImplementationSequence(governmentState.getNextImplementationSequence() + 1);
            outcome.setSourceId(sourceId);
            outcome.setSourceType(sourceType);
            outcome.setTitle(title);
            outcome.setJurisdictionId(node.getNodeId());
            governmentState.getImplementationOutcomes().add(0, outcome);
        }
        outcome.setMeasuredAt(now);
        outcome.setCapacityScore(capacity);
        outcome.setOutcomeScore(score);
        if (score > implementationSucceedThreshold) {
            outcome.setStatus("SUCCEEDING");
            outcome.setSummary(title + " is being implemented smoothly.");
            outcome.setAdverseEffects(List.of());
        } else if (score > implementationStallThreshold) {
            outcome.setStatus("STALLING");
            outcome.setSummary(title + " is moving unevenly through local implementation.");
            outcome.setAdverseEffects(List.of("delayed guidance", "partial coverage"));
        } else {
            outcome.setStatus("BACKFIRE");
            outcome.setSummary(title + " is producing backlash and uneven uptake.");
            outcome.setAdverseEffects(List.of("compliance fatigue", "litigation risk"));
        }
        GovernmentState.GovernmentBill bill = resolveBill(governmentState, sourceId);
        if (bill != null) {
            bill.setImplementationStatus(outcome.getStatus());
        }
        return true;
    }

    private boolean refreshHistoricalMemory(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        GovernmentState.ElectionRecord election = governmentState.getElections().isEmpty() ? null : governmentState.getElections().get(0);
        if (election != null) {
            changed |= addHistoricalMarkerIfMissing(governmentState, now, "ELECTION_CYCLE", election.getElectionId(),
                "Election " + election.getElectionId(),
                election.getSummary(),
                resolvePartyName(governmentState, election.getWinningPartyId()),
                null);
        }
        GovernmentState.LegalPrecedent precedent = governmentState.getPrecedents().isEmpty() ? null : governmentState.getPrecedents().get(0);
        if (precedent != null) {
            changed |= addHistoricalMarkerIfMissing(governmentState, now, "PRECEDENT_SHIFT", precedent.getPrecedentId(),
                "Precedent " + precedent.getPrecedentId(),
                precedent.getHolding(),
                null,
                precedent.getDoctrine());
        }
        GovernmentState.FederalStateConflict conflict = governmentState.getConflicts().stream()
            .filter(item -> !"SETTLED".equalsIgnoreCase(item.getStatus()))
            .findFirst()
            .orElse(null);
        if (conflict != null) {
            changed |= addHistoricalMarkerIfMissing(governmentState, now, "FEDERAL_STATE_CONFLICT", conflict.getConflictId(),
                "Conflict " + conflict.getConflictId(),
                conflict.getSummary(),
                joinSummaryParts(resolvePartyName(governmentState, conflict.getFederalPartyId()), resolvePartyName(governmentState, conflict.getStatePartyId())),
                null);
        }
        GovernmentState.ImplementationOutcome outcome = governmentState.getImplementationOutcomes().stream()
            .filter(item -> "BACKFIRE".equalsIgnoreCase(item.getStatus()))
            .findFirst()
            .orElse(null);
        if (outcome != null) {
            changed |= addHistoricalMarkerIfMissing(governmentState, now, "POLICY_BACKFIRE", outcome.getOutcomeId(),
                "Implementation shock " + outcome.getOutcomeId(),
                outcome.getSummary(),
                null,
                null);
        }
        if (governmentState.getHistoricalMarkers().size() > 18) {
            governmentState.setHistoricalMarkers(new ArrayList<>(governmentState.getHistoricalMarkers().subList(0, 18)));
        }
        return changed;
    }

    private boolean addHistoricalMarkerIfMissing(GovernmentState governmentState,
                                                 Instant now,
                                                 String markerType,
                                                 String key,
                                                 String title,
                                                 String summary,
                                                 String partyAlignment,
                                                 String doctrine) {
        if (key == null || governmentState.getHistoricalMarkers().stream()
            .anyMatch(marker -> title.equalsIgnoreCase(marker.getTitle()))) {
            return false;
        }
        GovernmentState.HistoricalMarker marker = new GovernmentState.HistoricalMarker();
        marker.setMarkerId(String.format(Locale.ROOT, "history-%05d", governmentState.getNextHistoricalSequence()));
        governmentState.setNextHistoricalSequence(governmentState.getNextHistoricalSequence() + 1);
        marker.setMarkerType(markerType);
        marker.setTitle(title);
        marker.setSummary(summary);
        marker.setTick(governmentState.getTick());
        marker.setPartyAlignment(partyAlignment);
        marker.setDoctrine(doctrine);
        marker.setCreatedAt(now);
        governmentState.getHistoricalMarkers().add(0, marker);
        return true;
    }

    private GovernmentState.CivicActor selectPressureActor(GovernmentState governmentState,
                                                           String policyDomain,
                                                           GovernmentState.InstitutionProfile targetInstitution) {
        if (governmentState == null || governmentState.getActors() == null) {
            return null;
        }
        return governmentState.getActors().stream()
            .filter(actor -> policyDomain == null
                || actor.getAgendas().stream().anyMatch(agenda -> policyDomain.equalsIgnoreCase(agenda)))
            .filter(actor -> targetInstitution == null
                || "federal".equalsIgnoreCase(targetInstitution.getLevel())
                || Objects.equals(actor.getJurisdictionId(), targetInstitution.getJurisdictionId())
                || "federal".equalsIgnoreCase(actor.getScope()))
            .max(Comparator.comparingDouble(GovernmentState.CivicActor::getInfluence))
            .orElse(governmentState.getActors().isEmpty() ? null : governmentState.getActors().get(0));
    }

    private String agencyNameForPolicyDomain(String policyDomain, String scope) {
        String level = "state".equalsIgnoreCase(scope) ? "State" : "Federal";
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> level + " Services Administration";
            case "housing" -> level + " Housing Board";
            case "labor" -> level + " Labor Standards Agency";
            case "rights-process" -> level + " Civil Rights Office";
            default -> level + " Infrastructure Authority";
        };
    }

    private GovernmentState.AdministrativeAction findAdministrativeActionForSource(GovernmentState governmentState,
                                                                                   String billId,
                                                                                   String orderId) {
        return governmentState.getAdministrativeActions().stream()
            .filter(item -> billId == null || billId.equalsIgnoreCase(item.getSourceBillId()))
            .filter(item -> orderId == null || orderId.equalsIgnoreCase(item.getSourceOrderId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.FederalStateConflict findOpenConflict(GovernmentState governmentState, String stateId) {
        return governmentState.getConflicts().stream()
            .filter(item -> stateId.equalsIgnoreCase(item.getStateId()))
            .filter(item -> !"SETTLED".equalsIgnoreCase(item.getStatus()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.ImplementationOutcome findImplementationOutcome(GovernmentState governmentState, String sourceId) {
        return governmentState.getImplementationOutcomes().stream()
            .filter(item -> sourceId.equalsIgnoreCase(item.getSourceId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.AdministrativeAction resolveAdministrativeAction(GovernmentState governmentState, String sourceId) {
        return governmentState.getAdministrativeActions().stream()
            .filter(item -> sourceId.equalsIgnoreCase(item.getSourceBillId()) || sourceId.equalsIgnoreCase(item.getSourceOrderId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.InstitutionProfile findInstitutionById(GovernmentState governmentState, String institutionId) {
        if (governmentState == null || institutionId == null || institutionId.isBlank() || governmentState.getInstitutions() == null) {
            return null;
        }
        return governmentState.getInstitutions().stream()
            .filter(item -> institutionId.equalsIgnoreCase(item.getInstitutionId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.PoliticalPressure recentPressureForBranch(GovernmentState governmentState, String branch) {
        if (governmentState == null || governmentState.getPressureEvents() == null) {
            return null;
        }
        return governmentState.getPressureEvents().stream()
            .filter(event -> {
                GovernmentState.InstitutionProfile institution = findInstitutionById(governmentState, event.getTargetInstitutionId());
                return institution != null && branch.equalsIgnoreCase(institution.getBranch());
            })
            .findFirst()
            .orElse(null);
    }

    private double recentPressureIntensityForInstitution(GovernmentState governmentState, String institutionId) {
        if (governmentState == null || institutionId == null || institutionId.isBlank() || governmentState.getPressureEvents() == null) {
            return 0d;
        }
        return governmentState.getPressureEvents().stream()
            .filter(event -> institutionId.equalsIgnoreCase(event.getTargetInstitutionId()))
            .limit(3)
            .mapToDouble(GovernmentState.PoliticalPressure::getIntensity)
            .sum();
    }

    private String dominantAgendaForActor(GovernmentState governmentState, String actorId, String fallback) {
        if (governmentState == null || actorId == null || actorId.isBlank() || governmentState.getActors() == null) {
            return fallback;
        }
        return governmentState.getActors().stream()
            .filter(actor -> actorId.equalsIgnoreCase(actor.getActorId()))
            .findFirst()
            .map(actor -> actor.getAgendas().isEmpty() ? fallback : actor.getAgendas().get(0))
            .orElse(fallback);
    }

    private String executiveOrderSummaryForDomain(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> "Emergency services continuity order";
            case "housing" -> "Emergency housing stabilization order";
            case "labor" -> "Emergency labor standards order";
            default -> "Rights process safeguard order";
        };
    }

    private boolean advanceBills(GovernmentState governmentState, List<RoomBot> bots, Instant now) {
        boolean changed = false;
        GovernmentState.GovernmentBill activeBill = governmentState.getBills().stream()
            .filter(bill -> !"ENACTED".equalsIgnoreCase(bill.getLawStatus()) && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus()))
            .findFirst()
            .orElse(null);
        if (activeBill == null && shouldCreateBill(governmentState, now)) {
            GovernmentState.GovernmentBill bill = new GovernmentState.GovernmentBill();
            bill.setBillId(String.format(Locale.ROOT, "bill-%05d", governmentState.getNextBillSequence()));
            governmentState.setNextBillSequence(governmentState.getNextBillSequence() + 1);
            boolean stateScoped = governmentState.getNextBillSequence() % 2 == 0 && !governmentState.getStates().isEmpty();
            GovernmentState.GovernmentNode sponsorNode = stateScoped
                ? weakestStateNode(governmentState)
                : governmentState.getFederal();
            bill.setScope(stateScoped ? "state" : "federal");
            bill.setStateId(stateScoped ? sponsorNode.getNodeId() : null);
            bill.setTitle(nextBillTitle(governmentState, bots, bill.getScope(), bill.getStateId()));
            bill.setSponsorPartyId(sponsorNode.getLowerChamberPartyId());
            bill.setPolicyDomain(nextPolicyDomain(governmentState, bots, bill.getScope(), bill.getStateId()));
            bill.setStage("DraftBill");
            bill.setLawStatus("IN_PROGRESS");
            bill.setConstitutionalBasis("Article I; Amendments 1, 5, 14");
            bill.setSummary("Deterministic policy response to current civic pressure signals.");
            bill.setCreatedAt(now);
            bill.setUpdatedAt(now);
            bill.setWorldEffects(defaultWorldEffectsFor(bill.getPolicyDomain()));
            populateBillSubstance(governmentState, bill, now);
            governmentState.getBills().add(0, bill);
            if (governmentState.getBills().size() > 12) {
                governmentState.getBills().remove(governmentState.getBills().size() - 1);
            }
            governmentState.setLastBillAt(now);
            historyWriter.writeImmutableJson(
                String.format(Locale.ROOT, "legislation/%s.json", bill.getBillId()),
                Map.of(
                    "bill_id", bill.getBillId(),
                    "title", bill.getTitle(),
                    "sponsor", bill.getSponsorPartyId(),
                    "party_affiliation", bill.getSponsorPartyId(),
                    "vote_results", Map.of("lower", 0, "upper", 0),
                    "executive_action", "pending",
                    "law_status", bill.getLawStatus()
                )
            );
            changed = true;
            activeBill = bill;
        }
        if (activeBill == null) {
            return changed;
        }
        activeBill.setUpdatedAt(now);
        if (shouldHoldBillForInstitutionLoad(governmentState, activeBill)) {
            return changed;
        }
        switch (activeBill.getStage()) {
            case "DraftBill" -> activeBill.setStage("SponsorAssignment");
            case "SponsorAssignment" -> activeBill.setStage("CommitteeReview");
            case "CommitteeReview" -> activeBill.setStage("AmendmentDrafting");
            case "AmendmentDrafting" -> {
                if (activeBill.getAmendmentSummaries().isEmpty()) {
                    activeBill.getAmendmentSummaries().add("Manager's amendment narrows " + humanizeToken(activeBill.getPolicyDomain()) + " enforcement language.");
                }
                activeBill.setStage("CoalitionWhip");
            }
            case "CoalitionWhip" -> {
                activeBill.setWhipCount(projectVotes(
                    governmentState,
                    activeBill.getSponsorPartyId(),
                    governmentState.getParties(),
                    bots,
                    "state".equalsIgnoreCase(activeBill.getScope()) ? activeBill.getStateId() : governmentState.getFederal().getNodeId(),
                    "LOWER_CHAMBER"
                ));
                activeBill.setStage("DebateWindow");
            }
            case "DebateWindow" -> {
                activeBill.setChamberTextStatus(activeBill.isConferenceRequired() ? "DIVERGED" : "ALIGNED");
                activeBill.setStage("ChamberVoteLower");
                activeBill.setLowerVotes(projectVotes(
                    governmentState,
                    chamberPartyId(governmentState, activeBill, true),
                    governmentState.getParties(),
                    bots,
                    "state".equalsIgnoreCase(activeBill.getScope()) ? activeBill.getStateId() : governmentState.getFederal().getNodeId(),
                    "LOWER_CHAMBER"
                ));
            }
            case "ChamberVoteLower" -> {
                activeBill.setStage("ChamberVoteUpper");
                activeBill.setUpperVotes(projectVotes(
                    governmentState,
                    chamberPartyId(governmentState, activeBill, false),
                    governmentState.getParties(),
                    bots,
                    "state".equalsIgnoreCase(activeBill.getScope()) ? activeBill.getStateId() : governmentState.getFederal().getNodeId(),
                    "UPPER_CHAMBER"
                ));
                if (Math.abs(activeBill.getUpperVotes() - activeBill.getLowerVotes()) > 1 || activeBill.isConferenceRequired()) {
                    activeBill.setConferenceRequired(true);
                    activeBill.setChamberTextStatus("DIVERGED");
                }
            }
            case "ChamberVoteUpper" -> {
                activeBill.setStage(activeBill.isConferenceRequired() ? "ConferenceCommittee" : "ExecutiveDecision");
                if (!Objects.equals(activeBill.getSponsorPartyId(), executivePartyId(governmentState, activeBill))
                    && activeBill.getPolicyDomain().startsWith("rights")) {
                    activeBill.setVetoed(true);
                }
            }
            case "ConferenceCommittee" -> {
                activeBill.setConferenceRequired(false);
                activeBill.setChamberTextStatus("ALIGNED");
                activeBill.setStage("ExecutiveDecision");
            }
            case "ExecutiveDecision" -> {
                if (activeBill.isVetoed()) {
                    activeBill.setStage("OverrideVote");
                } else {
                    activeBill.setStage(requiresJudicialReview(governmentState, activeBill)
                        ? "JudicialReviewPending"
                        : "LawActivation");
                }
            }
            case "OverrideVote" -> {
                if (activeBill.getLowerVotes() + activeBill.getUpperVotes() > Math.max(4, bots.size() / 8)) {
                    activeBill.setVetoed(false);
                    activeBill.setStage(requiresJudicialReview(governmentState, activeBill)
                        ? "JudicialReviewPending"
                        : "LawActivation");
                } else {
                    activeBill.setStage("LawActivation");
                    activeBill.setLawStatus("BLOCKED");
                }
            }
            case "JudicialReviewOptional" -> activeBill.setStage(requiresJudicialReview(governmentState, activeBill)
                ? "JudicialReviewPending"
                : "LawActivation");
            case "JudicialReviewPending" -> {
                GovernmentState.CourtCase courtCase = latestCaseForTarget(governmentState, activeBill.getBillId());
                if (courtCase != null && "DECIDED".equalsIgnoreCase(courtCase.getStatus())) {
                    if (courtCase.isBlockedAction()) {
                        activeBill.setLawStatus("BLOCKED");
                        activeBill.setStage("WorldEffectApplication");
                    } else {
                        activeBill.setStage("LawActivation");
                    }
                }
            }
            case "LawActivation" -> {
                activeBill.setStage("BudgetAllocation");
                activeBill.setLawStatus("ENACTED");
                activeBill.setImplementationStatus("PENDING");
                if (activeBill.getEffectiveAt() == null) {
                    activeBill.setEffectiveAt(now.plusMillis(maintenanceIntervalMs * 2));
                }
            }
            case "BudgetAllocation" -> {
                applyBudgetEffects(governmentState, activeBill);
                activeBill.setStage("WorldEffectApplication");
            }
            case "WorldEffectApplication" -> writeBillEvent(governmentState, activeBill, now);
            default -> {
            }
        }
        return true;
    }

    private boolean maybeRunElection(GovernmentState governmentState, List<RoomBot> bots, Instant now) {
        if (governmentState.getNextElectionAt() == null || now.isBefore(governmentState.getNextElectionAt())) {
            return false;
        }
        Map<String, Double> support = new LinkedHashMap<>();
        for (GovernmentState.PoliticalParty party : governmentState.getParties()) {
            support.put(party.getPartyId(), 0d);
        }
        int eligible = 0;
        double turnoutAccumulator = 0d;
        Map<String, Map<String, Double>> stateSupport = new HashMap<>();
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null || !civicState.isVoterEligible()) {
                continue;
            }
            eligible++;
            double turnout = clamp01(0.35d + civicState.getCivicParticipation() * 0.45d + civicState.getInstitutionalTrust() * 0.2d);
            turnoutAccumulator += turnout;
            for (GovernmentState.PoliticalParty party : governmentState.getParties()) {
                double score = turnout * partyAffinity(party, civicState);
                support.merge(party.getPartyId(), score, Double::sum);
                stateSupport.computeIfAbsent(civicState.getStateId(), key -> new HashMap<>())
                    .merge(party.getPartyId(), score, Double::sum);
            }
        }
        String winner = support.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(governmentState.getFederal().getExecutivePartyId());
        String runnerUp = support.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .skip(1)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(winner);
        GovernmentState.GovernmentNode federal = governmentState.getFederal();
        federal.setExecutivePartyId(winner);
        federal.setLowerChamberPartyId(winner);
        federal.setUpperChamberPartyId(runnerUp);
        federal.setChiefJusticePartyId(runnerUp);
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            Map<String, Double> localSupport = stateSupport.getOrDefault(stateNode.getNodeId(), support);
            String stateWinner = localSupport.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(winner);
            stateNode.setExecutivePartyId(stateWinner);
            stateNode.setLowerChamberPartyId(stateWinner);
            stateNode.setUpperChamberPartyId(runnerUp);
            stateNode.setChiefJusticePartyId(runnerUp);
        }
        GovernmentState.ElectionRecord record = new GovernmentState.ElectionRecord();
        record.setElectionId(String.format(Locale.ROOT, "election-%05d", governmentState.getNextElectionSequence()));
        governmentState.setNextElectionSequence(governmentState.getNextElectionSequence() + 1);
        record.setScope("federal");
        record.setJurisdictionId("federal");
        record.setHeldAt(now);
        record.setWinningPartyId(winner);
        record.setTurnout(eligible == 0 ? 0d : turnoutAccumulator / eligible);
        record.setSummary(String.format(Locale.ROOT, "%s won a deterministic federal cycle", winner));
        governmentState.getElections().add(0, record);
        if (governmentState.getElections().size() > 16) {
            governmentState.getElections().remove(governmentState.getElections().size() - 1);
        }
        governmentState.setNextElectionAt(now.plusMillis(electionIntervalMs));
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "elections/%s.json", record.getElectionId()),
            Map.of(
                "event_id", record.getElectionId(),
                "timestamp", record.getHeldAt(),
                "event_type", "election",
                "location_scope", record.getScope(),
                "summary", record.getSummary(),
                "triggering_action", "scheduled_cycle",
                "affected_entities", List.of("federal", "parties", "representation"),
                "resulting_world_changes", List.of("office_rotation", "budget_priority_shift")
            )
        );
        return true;
    }

    private boolean maybeIssueExecutiveOrders(GovernmentState governmentState, List<RoomBot> bots, Instant now) {
        double avgService = averageServiceAccess(bots);
        double avgTrust = averageTrust(bots);
        GovernmentState.PoliticalPressure executivePressure = recentPressureForBranch(governmentState, "EXECUTIVE");
        if (avgService >= 0.44d && avgTrust >= 0.40d
            && (executivePressure == null || executivePressure.getIntensity() < executivePressureTriggerThreshold)) {
            return false;
        }
        GovernmentState.InstitutionProfile executive = findInstitution(
            governmentState,
            avgService < 0.38d && !governmentState.getStates().isEmpty() ? weakestStateNode(governmentState).getNodeId() : governmentState.getFederal().getNodeId(),
            "EXECUTIVE"
        );
        if (executive != null && executive.getWorkload() > executive.getCapacityPerTick() + 1 && executive.getLegitimacy() < 0.55d) {
            return false;
        }
        GovernmentState.ExecutiveOrder existing = governmentState.getExecutiveOrders().stream()
            .filter(order -> "ACTIVE".equalsIgnoreCase(order.getStatus()) || "UNDER_REVIEW".equalsIgnoreCase(order.getStatus()))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            return false;
        }
        GovernmentState.GovernmentNode targetNode = avgService < 0.38d && !governmentState.getStates().isEmpty()
            ? weakestStateNode(governmentState)
            : governmentState.getFederal();
        String policyDomain = executivePressure != null
            ? dominantAgendaForActor(governmentState, executivePressure.getSourceActorId(), avgService < 0.38d ? "services" : "rights-process")
            : (avgService < 0.38d ? "services" : "rights-process");
        GovernmentState.ExecutiveOrder order = new GovernmentState.ExecutiveOrder();
        order.setOrderId(String.format(Locale.ROOT, "order-%05d", governmentState.getNextOrderSequence()));
        governmentState.setNextOrderSequence(governmentState.getNextOrderSequence() + 1);
        order.setScope("federal".equals(targetNode.getLevel()) ? "federal" : "state");
        order.setJurisdictionId(targetNode.getNodeId());
        order.setIssuedByPartyId(targetNode.getExecutivePartyId());
        order.setPolicyDomain(policyDomain);
        order.setSummary(executiveOrderSummaryForDomain(policyDomain));
        order.setStatus("ACTIVE");
        order.setIssuedAt(now);
        order.setAffectedSystems(defaultWorldEffectsFor(policyDomain));
        populateExecutiveOrderSubstance(governmentState, order, now);
        governmentState.getExecutiveOrders().add(0, order);
        if (governmentState.getExecutiveOrders().size() > 8) {
            governmentState.getExecutiveOrders().remove(governmentState.getExecutiveOrders().size() - 1);
        }
        applyExecutiveOrderEffects(governmentState, order);
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "events/event-%05d.json", governmentState.getNextEventSequence()),
            Map.of(
                "event_id", governmentState.getNextEventSequence(),
                "timestamp", now,
                "event_type", "executive_order",
                "location_scope", order.getScope(),
                "summary", order.getSummary(),
                "triggering_action", order.getOrderId(),
                "affected_entities", order.getAffectedSystems(),
                "resulting_world_changes", order.getAffectedSystems()
            )
        );
        governmentState.setNextEventSequence(governmentState.getNextEventSequence() + 1);
        return true;
    }

    private boolean maybeReviewByCourts(GovernmentState governmentState, Instant now) {
        boolean filed = fileEligibleCourtCases(governmentState, now);
        boolean advanced = advanceOpenCourtCases(governmentState, now);
        return filed || advanced;
    }

    private boolean hasRecentCourtReview(GovernmentState governmentState, String defendant, String reviewPosture) {
        if (defendant == null || defendant.isBlank() || reviewPosture == null || reviewPosture.isBlank()) {
            return false;
        }
        return governmentState.getCourtCases().stream()
            .anyMatch(courtCase -> defendant.equalsIgnoreCase(courtCase.getDefendant())
                && reviewPosture.equalsIgnoreCase(courtCase.getReviewPosture()));
    }

    private boolean fileEligibleCourtCases(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            if (!requiresJudicialReview(governmentState, bill)) {
                continue;
            }
            if (!"JudicialReviewPending".equalsIgnoreCase(bill.getStage())
                && !"JudicialReviewOptional".equalsIgnoreCase(bill.getStage())) {
                continue;
            }
            LegalReviewPlan plan = buildLegalReviewPlanForBill(governmentState, bill);
            if (plan == null || hasRecentCourtReview(governmentState, bill.getBillId(), plan.reviewPosture())) {
                continue;
            }
            if (courtBacklogFull(governmentState, courtLevelForBill(bill))) {
                continue;
            }
            GovernmentState.CourtCase courtCase = initializeCourtCase(governmentState, now, courtLevelForBill(bill), plan);
            courtCase.setPlaintiff(resolveCourtPlaintiff(governmentState, bill, null));
            courtCase.setDefendant(bill.getBillId());
            courtCase.setChallengedTitle(bill.getTitle());
            courtCase.setAffectedLaws(List.of(bill.getBillId()));
            governmentState.getCourtCases().add(0, courtCase);
            bill.setStage("JudicialReviewPending");
            changed = true;
        }
        for (GovernmentState.ExecutiveOrder order : governmentState.getExecutiveOrders()) {
            if (!requiresJudicialReview(governmentState, order)) {
                continue;
            }
            if (!"ACTIVE".equalsIgnoreCase(order.getStatus()) && !"UNDER_REVIEW".equalsIgnoreCase(order.getStatus())) {
                continue;
            }
            LegalReviewPlan plan = buildLegalReviewPlanForOrder(governmentState, order);
            if (plan == null || hasRecentCourtReview(governmentState, order.getOrderId(), plan.reviewPosture())) {
                continue;
            }
            if (courtBacklogFull(governmentState, courtLevelForOrder(order))) {
                continue;
            }
            GovernmentState.CourtCase courtCase = initializeCourtCase(governmentState, now, courtLevelForOrder(order), plan);
            courtCase.setPlaintiff(resolveCourtPlaintiff(governmentState, null, order));
            courtCase.setDefendant(order.getOrderId());
            courtCase.setChallengedTitle(order.getSummary());
            courtCase.setAffectedLaws(List.of(order.getOrderId()));
            governmentState.getCourtCases().add(0, courtCase);
            order.setStatus("UNDER_REVIEW");
            changed = true;
        }
        if (changed && governmentState.getCourtCases().size() > 24) {
            governmentState.setCourtCases(new ArrayList<>(governmentState.getCourtCases().subList(0, 24)));
        }
        return changed;
    }

    private GovernmentState.CourtCase initializeCourtCase(GovernmentState governmentState,
                                                          Instant now,
                                                          String courtLevel,
                                                          LegalReviewPlan plan) {
        GovernmentState.CourtCase courtCase = new GovernmentState.CourtCase();
        courtCase.setCaseId(String.format(Locale.ROOT, "case-%05d", governmentState.getNextCourtSequence()));
        governmentState.setNextCourtSequence(governmentState.getNextCourtSequence() + 1);
        courtCase.setCourtLevel(courtLevel);
        courtCase.setIssueSummary(plan.issueSummary());
        courtCase.setReviewPosture(plan.reviewPosture());
        courtCase.setStatus("OPEN");
        courtCase.setStage("FILED");
        courtCase.setFiledAt(now);
        courtCase.setScrutinyLevel(plan.scrutinyLevel());
        courtCase.setStandingBasis(plan.standingBasis());
        courtCase.setRemedy(plan.remedy());
        courtCase.setDoctrine(plan.doctrine());
        courtCase.setSourcePolicyDomain(plan.policyDomain());
        courtCase.setLegalBasis(plan.legalBasis());
        courtCase.setConstitutionalBasis(plan.constitutionalBasis());
        courtCase.setLowerCourtDisposition("Trial record assembled for constitutional review.");
        courtCase.setAppellatePath("STATE_SUPREME".equalsIgnoreCase(courtLevel)
            ? "State trial -> state intermediate appeal -> state supreme"
            : "District court -> circuit review -> supreme");
        courtCase.setInjunctionGranted("STRICT_SCRUTINY".equalsIgnoreCase(plan.scrutinyLevel()));
        courtCase.setMajorityAlignment(humanizeToken(plan.doctrine()) + " majority");
        courtCase.setPrecedentIds(new ArrayList<>(plan.precedentIds()));
        return courtCase;
    }

    private boolean advanceOpenCourtCases(GovernmentState governmentState, Instant now) {
        boolean changed = false;
        Map<String, Integer> progressedByCourt = new HashMap<>();
        List<GovernmentState.CourtCase> openCases = governmentState.getCourtCases().stream()
            .filter(courtCase -> !"DECIDED".equalsIgnoreCase(courtCase.getStatus()))
            .sorted(Comparator.comparing(GovernmentState.CourtCase::getFiledAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        for (GovernmentState.CourtCase courtCase : openCases) {
            GovernmentState.InstitutionProfile courtInstitution =
                "STATE_SUPREME".equalsIgnoreCase(courtCase.getCourtLevel())
                    ? weakestCourtInstitution(governmentState, "state")
                    : findInstitution(governmentState, "federal", "COURT");
            if (courtInstitution == null) {
                continue;
            }
            int progressed = progressedByCourt.getOrDefault(courtInstitution.getInstitutionId(), 0);
            if (progressed >= courtInstitution.getCapacityPerTick()) {
                continue;
            }
            if (advanceCourtCaseStage(governmentState, courtCase, now)) {
                progressedByCourt.put(courtInstitution.getInstitutionId(), progressed + 1);
                changed = true;
            }
        }
        return changed;
    }

    private boolean advanceCourtCaseStage(GovernmentState governmentState,
                                          GovernmentState.CourtCase courtCase,
                                          Instant now) {
        String stage = courtCase.getStage() == null ? "FILED" : courtCase.getStage();
        switch (stage) {
            case "FILED" -> {
                courtCase.setStage("BRIEFING");
                courtCase.setBriefedAt(now);
                return true;
            }
            case "BRIEFING" -> {
                courtCase.setStage("ARGUMENT");
                courtCase.setArguedAt(now);
                return true;
            }
            case "ARGUMENT" -> {
                courtCase.setStage("CONFERENCE");
                courtCase.setConferenceAt(now);
                return true;
            }
            case "CONFERENCE" -> {
                courtCase.setStage("OPINION_DRAFT");
                courtCase.setOpinionDraftAt(now);
                return true;
            }
            case "OPINION_DRAFT" -> {
                return resolveCourtDecision(governmentState, courtCase, now);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean resolveCourtDecision(GovernmentState governmentState,
                                         GovernmentState.CourtCase courtCase,
                                         Instant now) {
        boolean blocked = determineBlockedOutcome(governmentState, courtCase);
        courtCase.setBlockedAction(blocked);
        courtCase.setStatus("DECIDED");
        courtCase.setStage("DECIDED");
        courtCase.setDecidedAt(now);
        if (blocked) {
            courtCase.setDecision("Court invalidated the challenged action after constitutional review.");
        } else {
            courtCase.setDecision("Court upheld the challenged action under the applicable constitutional doctrine.");
        }
        courtCase.setRemanded(blocked || "FEDERALISM_PREEMPTION".equalsIgnoreCase(courtCase.getDoctrine()));
        if (Math.abs(precedentTilt(governmentState, courtCase)) < 0.06d) {
            courtCase.setDissentSummary("A narrow dissent warned that the majority underweighted institutional consequences.");
        }
        if (courtCase.getRemedy() == null || courtCase.getRemedy().isBlank()) {
            courtCase.setRemedy(blocked ? "injunction and remand" : "declaratory affirmance");
        }
        GovernmentState.GovernmentBill bill = resolveBill(governmentState, courtCase.getDefendant());
        if (bill != null) {
            if (blocked) {
                bill.setLawStatus("BLOCKED");
                bill.setStage("WorldEffectApplication");
            } else if ("JudicialReviewPending".equalsIgnoreCase(bill.getStage())
                || "JudicialReviewOptional".equalsIgnoreCase(bill.getStage())) {
                bill.setStage("LawActivation");
            }
        }
        GovernmentState.ExecutiveOrder order = resolveOrder(governmentState, courtCase.getDefendant());
        if (order != null) {
            order.setStatus(blocked ? "BLOCKED" : "REVIEWED");
        }
        createPrecedent(governmentState, courtCase, now);
        writeCourtHistory(courtCase);
        return true;
    }

    private boolean determineBlockedOutcome(GovernmentState governmentState, GovernmentState.CourtCase courtCase) {
        double blockScore = 0.0d;
        if ("STRICT_SCRUTINY".equalsIgnoreCase(courtCase.getScrutinyLevel())) {
            blockScore += 0.42d;
        } else if ("INTERMEDIATE_SCRUTINY".equalsIgnoreCase(courtCase.getScrutinyLevel())) {
            blockScore += 0.22d;
        } else {
            blockScore += 0.08d;
        }
        if ("rights-process".equalsIgnoreCase(courtCase.getSourcePolicyDomain())) {
            blockScore += 0.18d;
        }
        if (courtCase.getDoctrine() != null && courtCase.getDoctrine().toLowerCase(Locale.ROOT).contains("federalism")) {
            blockScore += 0.14d;
        }
        if (courtCase.getDoctrine() != null && courtCase.getDoctrine().toLowerCase(Locale.ROOT).contains("administrative")) {
            blockScore -= 0.05d;
        }
        GovernmentState.AdministrativeAction administrativeAction = resolveAdministrativeAction(governmentState, courtCase.getDefendant());
        if (administrativeAction != null && administrativeAction.isAppealed()) {
            blockScore += 0.08d;
        }
        if (administrativeAction != null && administrativeAction.isStayed()) {
            blockScore += 0.05d;
        }
        if (courtCase.isInjunctionGranted()) {
            blockScore += 0.06d;
        }
        if (governmentState.getConflicts().stream().anyMatch(conflict ->
            "IN_LITIGATION".equalsIgnoreCase(conflict.getStatus())
                && (Objects.equals(conflict.getRelatedBillId(), courtCase.getDefendant())
                || Objects.equals(conflict.getRelatedOrderId(), courtCase.getDefendant())))) {
            blockScore += 0.09d;
        }
        double precedentTilt = precedentTilt(governmentState, courtCase);
        blockScore += precedentTilt;
        GovernmentState.InstitutionProfile courtInstitution =
            "STATE_SUPREME".equalsIgnoreCase(courtCase.getCourtLevel())
                ? weakestCourtInstitution(governmentState, "state")
                : findInstitution(governmentState, "federal", "COURT");
        if (courtInstitution != null) {
            blockScore += (courtInstitution.getLibertyLean() - courtInstitution.getOrderLean()) * 0.16d;
            blockScore += (0.55d - courtInstitution.getLegitimacy()) * 0.12d;
        }
        return blockScore >= courtBlockThreshold;
    }

    private double precedentTilt(GovernmentState governmentState, GovernmentState.CourtCase courtCase) {
        if (governmentState.getPrecedents() == null || governmentState.getPrecedents().isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (GovernmentState.LegalPrecedent precedent : governmentState.getPrecedents()) {
            if (precedent.getDoctrine() == null || courtCase.getDoctrine() == null) {
                continue;
            }
            if (!precedent.getDoctrine().equalsIgnoreCase(courtCase.getDoctrine())) {
                continue;
            }
            if (precedent.getPolicyDomain() != null
                && courtCase.getSourcePolicyDomain() != null
                && !precedent.getPolicyDomain().equalsIgnoreCase(courtCase.getSourcePolicyDomain())) {
                continue;
            }
            total += ("BLOCKED".equalsIgnoreCase(precedent.getOutcome()) ? 1 : -1) * precedent.getWeight() * 0.08d;
        }
        return total;
    }

    private void createPrecedent(GovernmentState governmentState, GovernmentState.CourtCase courtCase, Instant now) {
        GovernmentState.LegalPrecedent precedent = new GovernmentState.LegalPrecedent();
        precedent.setPrecedentId(String.format(Locale.ROOT, "precedent-%05d", governmentState.getNextPrecedentSequence()));
        governmentState.setNextPrecedentSequence(governmentState.getNextPrecedentSequence() + 1);
        precedent.setCaseId(courtCase.getCaseId());
        precedent.setDoctrine(courtCase.getDoctrine());
        precedent.setScope("STATE_SUPREME".equalsIgnoreCase(courtCase.getCourtLevel()) ? "state" : "federal");
        precedent.setPolicyDomain(courtCase.getSourcePolicyDomain());
        precedent.setHolding(courtCase.isBlockedAction()
            ? "Challenged action exceeded constitutional bounds under " + courtCase.getDoctrine()
            : "Challenged action survived review under " + courtCase.getDoctrine());
        precedent.setOutcome(courtCase.isBlockedAction() ? "BLOCKED" : "UPHELD");
        precedent.setConstitutionalBasis(courtCase.getConstitutionalBasis());
        precedent.setCreatedAt(now);
        precedent.setSummary(courtCase.getIssueSummary());
        precedent.setWeight("SUPREME".equalsIgnoreCase(courtCase.getCourtLevel()) ? 1.0d : 0.75d);
        governmentState.getPrecedents().add(0, precedent);
        if (governmentState.getPrecedents().size() > 48) {
            governmentState.setPrecedents(new ArrayList<>(governmentState.getPrecedents().subList(0, 48)));
        }
        courtCase.getPrecedentIds().add(precedent.getPrecedentId());
    }

    private void writeCourtHistory(GovernmentState.CourtCase courtCase) {
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "courts/%s.json", courtCase.getCaseId()),
            Map.ofEntries(
                Map.entry("case_id", courtCase.getCaseId()),
                Map.entry("court_level", courtCase.getCourtLevel()),
                Map.entry("status", courtCase.getStatus()),
                Map.entry("stage", courtCase.getStage()),
                Map.entry("plaintiff", courtCase.getPlaintiff()),
                Map.entry("defendant", courtCase.getDefendant()),
                Map.entry("challenged_title", courtCase.getChallengedTitle()),
                Map.entry("issue_summary", courtCase.getIssueSummary()),
                Map.entry("review_posture", courtCase.getReviewPosture()),
                Map.entry("doctrine", courtCase.getDoctrine()),
                Map.entry("scrutiny_level", courtCase.getScrutinyLevel()),
                Map.entry("standing_basis", courtCase.getStandingBasis()),
                Map.entry("remedy", courtCase.getRemedy()),
                Map.entry("legal_basis", courtCase.getLegalBasis()),
                Map.entry("decision", courtCase.getDecision()),
                Map.entry("constitutional_basis", courtCase.getConstitutionalBasis()),
                Map.entry("affected_laws", courtCase.getAffectedLaws()),
                Map.entry("precedent_ids", courtCase.getPrecedentIds())
            )
        );
    }

    private boolean courtBacklogFull(GovernmentState governmentState, String courtLevel) {
        GovernmentState.InstitutionProfile institution =
            "STATE_SUPREME".equalsIgnoreCase(courtLevel)
                ? weakestCourtInstitution(governmentState, "state")
                : findInstitution(governmentState, "federal", "COURT");
        return institution != null && institution.getWorkload() >= institution.getCapacityPerTick() * 2;
    }

    private String courtLevelForBill(GovernmentState.GovernmentBill bill) {
        return "federal".equalsIgnoreCase(bill.getScope()) ? "SUPREME" : "STATE_SUPREME";
    }

    private String courtLevelForOrder(GovernmentState.ExecutiveOrder order) {
        return "federal".equalsIgnoreCase(order.getScope()) ? "SUPREME" : "STATE_SUPREME";
    }

    private String describeBillCourtIssue(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        String scopeLabel = "state".equalsIgnoreCase(bill.getScope()) ? "state" : "federal";
        String title = bill.getTitle() == null || bill.getTitle().isBlank() ? bill.getBillId() : bill.getTitle();
        String domain = humanizeToken(bill.getPolicyDomain());
        if ("rights-process".equalsIgnoreCase(bill.getPolicyDomain())) {
            return String.format(Locale.ROOT,
                "The court is weighing whether %s's %s violates constitutional due-process and civil-liberty protections.",
                scopeLabel,
                title);
        }
        if (domain == null || domain.isBlank()) {
            return String.format(Locale.ROOT,
                "The court is reviewing whether %s stays within constitutional limits for %s.",
                title,
                scopeLabel + " lawmaking");
        }
        return String.format(Locale.ROOT,
            "The court is reviewing whether %s lawfully changes %s policy under the constitution.",
            title,
            domain.toLowerCase(Locale.ROOT));
    }

    private String describeExecutiveOrderCourtIssue(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        String summary = order.getSummary() == null || order.getSummary().isBlank() ? order.getOrderId() : order.getSummary();
        String domain = humanizeToken(order.getPolicyDomain()).toLowerCase(Locale.ROOT);
        return String.format(Locale.ROOT,
            "The court is reviewing whether executive order %s lawfully intervenes in %s policy.",
            summary,
            domain.isBlank() ? "government" : domain);
    }

    private String resolveCourtPlaintiff(GovernmentState governmentState,
                                         GovernmentState.GovernmentBill bill,
                                         GovernmentState.ExecutiveOrder order) {
        if (bill != null) {
            if ("state".equalsIgnoreCase(bill.getScope())) {
                String stateName = resolveStateName(governmentState, bill.getStateId());
                if (stateName != null && !stateName.isBlank()) {
                    return stateName + " residents coalition";
                }
            }
            if ("rights-process".equalsIgnoreCase(bill.getPolicyDomain())) {
                return "civil liberties coalition";
            }
            if ("housing".equalsIgnoreCase(bill.getPolicyDomain())) {
                return "municipal housing coalition";
            }
            if ("labor".equalsIgnoreCase(bill.getPolicyDomain())) {
                return "labor federation";
            }
            if ("services".equalsIgnoreCase(bill.getPolicyDomain())) {
                return "public service beneficiaries union";
            }
            return "civic-population";
        }
        if (order != null && "rights-process".equalsIgnoreCase(order.getPolicyDomain())) {
            return "civil liberties coalition";
        }
        if (order != null && "services".equalsIgnoreCase(order.getPolicyDomain())) {
            return "public service beneficiaries union";
        }
        return "civic-population";
    }

    private String resolveStateName(GovernmentState governmentState, String stateId) {
        if (governmentState == null || stateId == null || stateId.isBlank()) {
            return stateId;
        }
        return governmentState.getStates().stream()
            .filter(state -> stateId.equalsIgnoreCase(state.getNodeId()))
            .map(GovernmentState.GovernmentNode::getDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .findFirst()
            .orElse(stateId);
    }

    private boolean requiresJudicialReview(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        LegalReviewPlan plan = buildLegalReviewPlanForBill(governmentState, bill);
        return plan != null && (!"ADMINISTRATIVE_DEFERENCE".equalsIgnoreCase(plan.doctrine())
            || !plan.precedentIds().isEmpty()
            || (bill.getAffectedRights() != null && !bill.getAffectedRights().isEmpty()));
    }

    private boolean requiresJudicialReview(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        LegalReviewPlan plan = buildLegalReviewPlanForOrder(governmentState, order);
        return plan != null && (!"ADMINISTRATIVE_DEFERENCE".equalsIgnoreCase(plan.doctrine())
            || !plan.precedentIds().isEmpty()
            || (order.getAffectedRights() != null && !order.getAffectedRights().isEmpty()));
    }

    private LegalReviewPlan buildLegalReviewPlanForBill(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        if (bill == null) {
            return null;
        }
        List<GovernmentState.ConstitutionRule> rules = selectRelevantRules(governmentState, systemsForBill(bill));
        String doctrine = determineDoctrine(bill.getPolicyDomain(), bill.getAffectedRights(), bill.getPreemptionScope(), rules);
        String scrutiny = determineScrutinyLevel(doctrine, bill.getAffectedRights());
        List<String> precedentIds = matchingPrecedentIds(governmentState, doctrine, bill.getPolicyDomain());
        String issue = describeBillCourtIssue(governmentState, bill);
        if (!precedentIds.isEmpty()) {
            issue = issue + " Relevant precedent load: " + precedentIds.size() + ".";
        }
        return new LegalReviewPlan(
            doctrine,
            scrutiny,
            determineStandingBasis(bill.getScope(), bill.getPolicyDomain(), bill.getAffectedRights()),
            determineRemedy(doctrine, bill.getScope()),
            determineLegalBasis(rules, doctrine),
            determineConstitutionalBasis(rules, bill.getConstitutionalBasis()),
            issue,
            String.format(Locale.ROOT, "bill:%s:%s:%s:%s", bill.getBillId(), bill.getStage(), bill.getLawStatus(), doctrine),
            precedentIds,
            bill.getPolicyDomain()
        );
    }

    private LegalReviewPlan buildLegalReviewPlanForOrder(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        if (order == null) {
            return null;
        }
        List<GovernmentState.ConstitutionRule> rules = selectRelevantRules(governmentState, systemsForExecutiveOrder(order));
        String doctrine = determineDoctrine(order.getPolicyDomain(), order.getAffectedRights(), order.getPreemptionScope(), rules);
        String scrutiny = determineScrutinyLevel(doctrine, order.getAffectedRights());
        List<String> precedentIds = matchingPrecedentIds(governmentState, doctrine, order.getPolicyDomain());
        String issue = describeExecutiveOrderCourtIssue(governmentState, order);
        if (!precedentIds.isEmpty()) {
            issue = issue + " Relevant precedent load: " + precedentIds.size() + ".";
        }
        return new LegalReviewPlan(
            doctrine,
            scrutiny,
            determineStandingBasis(order.getScope(), order.getPolicyDomain(), order.getAffectedRights()),
            determineRemedy(doctrine, order.getScope()),
            determineLegalBasis(rules, doctrine),
            determineConstitutionalBasis(rules, "Article II; " + (order.getAffectedRights().isEmpty() ? "Amendment X" : "Amendments I, V, XIV")),
            issue,
            String.format(Locale.ROOT, "order:%s:%s:%s:%s", order.getOrderId(), order.getPolicyDomain(), order.getStatus(), doctrine),
            precedentIds,
            order.getPolicyDomain()
        );
    }

    private List<GovernmentState.ConstitutionRule> selectRelevantRules(GovernmentState governmentState, List<String> systems) {
        if (governmentState == null || governmentState.getConstitutionalRules() == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String system : systems) {
            if (system != null && !system.isBlank()) {
                normalized.add(system.toLowerCase(Locale.ROOT));
            }
        }
        return governmentState.getConstitutionalRules().stream()
            .filter(rule -> rule.getAffectedSystems() != null && rule.getAffectedSystems().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains))
            .limit(5)
            .toList();
    }

    private List<String> systemsForBill(GovernmentState.GovernmentBill bill) {
        List<String> systems = new ArrayList<>();
        if (bill.getWorldEffects() != null) {
            systems.addAll(bill.getWorldEffects());
        }
        if (bill.getAffectedRights() != null) {
            systems.addAll(bill.getAffectedRights());
        }
        if (bill.getPolicyDomain() != null) {
            systems.add(bill.getPolicyDomain());
        }
        systems.add("courts");
        systems.add("rights");
        return systems;
    }

    private List<String> systemsForExecutiveOrder(GovernmentState.ExecutiveOrder order) {
        List<String> systems = new ArrayList<>();
        if (order.getAffectedSystems() != null) {
            systems.addAll(order.getAffectedSystems());
        }
        if (order.getAffectedRights() != null) {
            systems.addAll(order.getAffectedRights());
        }
        if (order.getPolicyDomain() != null) {
            systems.add(order.getPolicyDomain());
        }
        systems.add("executive");
        systems.add("courts");
        return systems;
    }

    private String determineDoctrine(String policyDomain,
                                     List<String> affectedRights,
                                     String preemptionScope,
                                     List<GovernmentState.ConstitutionRule> rules) {
        boolean hasRights = affectedRights != null && !affectedRights.isEmpty();
        boolean hasFederalismRule = rules.stream().anyMatch(rule -> "federalism".equalsIgnoreCase(rule.getCategory()));
        if ("rights-process".equalsIgnoreCase(policyDomain) || hasRights) {
            return "SUBSTANTIVE_DUE_PROCESS";
        }
        if (hasFederalismRule || (preemptionScope != null && !preemptionScope.isBlank() && !"none".equalsIgnoreCase(preemptionScope))) {
            return "FEDERALISM_PREEMPTION";
        }
        if ("housing".equalsIgnoreCase(policyDomain) || "labor".equalsIgnoreCase(policyDomain) || "services".equalsIgnoreCase(policyDomain)) {
            return "EQUAL_PROTECTION_REVIEW";
        }
        return "ADMINISTRATIVE_DEFERENCE";
    }

    private String determineScrutinyLevel(String doctrine, List<String> affectedRights) {
        if ("SUBSTANTIVE_DUE_PROCESS".equalsIgnoreCase(doctrine)) {
            return "STRICT_SCRUTINY";
        }
        if ("FEDERALISM_PREEMPTION".equalsIgnoreCase(doctrine) || (affectedRights != null && !affectedRights.isEmpty())) {
            return "INTERMEDIATE_SCRUTINY";
        }
        return "RATIONAL_BASIS";
    }

    private String determineStandingBasis(String scope, String policyDomain, List<String> affectedRights) {
        if ("state".equalsIgnoreCase(scope)) {
            return "localized injury and state standing";
        }
        if (affectedRights != null && !affectedRights.isEmpty()) {
            return "rights-bearing individuals and associational standing";
        }
        if ("housing".equalsIgnoreCase(policyDomain)) {
            return "municipal and tenant standing";
        }
        if ("labor".equalsIgnoreCase(policyDomain)) {
            return "workforce and labor-organizational standing";
        }
        return "generalized civic injury with procedural gateway";
    }

    private String determineRemedy(String doctrine, String scope) {
        if ("FEDERALISM_PREEMPTION".equalsIgnoreCase(doctrine)) {
            return "partial preemption and remand";
        }
        if ("SUBSTANTIVE_DUE_PROCESS".equalsIgnoreCase(doctrine)) {
            return "injunction and narrow tailoring remand";
        }
        if ("state".equalsIgnoreCase(scope)) {
            return "state compliance order";
        }
        return "declaratory review";
    }

    private String determineLegalBasis(List<GovernmentState.ConstitutionRule> rules, String doctrine) {
        if (rules == null || rules.isEmpty()) {
            return humanizeToken(doctrine);
        }
        return rules.stream()
            .map(GovernmentState.ConstitutionRule::getTitle)
            .filter(Objects::nonNull)
            .limit(2)
            .collect(java.util.stream.Collectors.joining(" + "));
    }

    private String determineConstitutionalBasis(List<GovernmentState.ConstitutionRule> rules, String fallback) {
        if (rules == null || rules.isEmpty()) {
            return fallback;
        }
        return rules.stream()
            .map(GovernmentState.ConstitutionRule::getArticleOrAmendment)
            .filter(Objects::nonNull)
            .distinct()
            .limit(4)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private List<String> matchingPrecedentIds(GovernmentState governmentState, String doctrine, String policyDomain) {
        if (governmentState == null || governmentState.getPrecedents() == null) {
            return List.of();
        }
        return governmentState.getPrecedents().stream()
            .filter(precedent -> doctrine.equalsIgnoreCase(precedent.getDoctrine()))
            .filter(precedent -> policyDomain == null
                || precedent.getPolicyDomain() == null
                || policyDomain.equalsIgnoreCase(precedent.getPolicyDomain()))
            .limit(3)
            .map(GovernmentState.LegalPrecedent::getPrecedentId)
            .toList();
    }

    private GovernmentState.CourtCase latestCaseForTarget(GovernmentState governmentState, String defendant) {
        if (governmentState == null || defendant == null || defendant.isBlank()) {
            return null;
        }
        return governmentState.getCourtCases().stream()
            .filter(courtCase -> defendant.equalsIgnoreCase(courtCase.getDefendant()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.GovernmentBill resolveBill(GovernmentState governmentState, String billId) {
        if (governmentState == null || billId == null || billId.isBlank()) {
            return null;
        }
        return governmentState.getBills().stream()
            .filter(bill -> billId.equalsIgnoreCase(bill.getBillId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.ExecutiveOrder resolveOrder(GovernmentState governmentState, String orderId) {
        if (governmentState == null || orderId == null || orderId.isBlank()) {
            return null;
        }
        return governmentState.getExecutiveOrders().stream()
            .filter(order -> orderId.equalsIgnoreCase(order.getOrderId()))
            .findFirst()
            .orElse(null);
    }

    private boolean shouldHoldBillForInstitutionLoad(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        if (bill == null || bill.getStage() == null) {
            return false;
        }
        String branch = switch (bill.getStage()) {
            case "DraftBill", "SponsorAssignment", "CommitteeReview", "AmendmentDrafting", "CoalitionWhip", "DebateWindow", "ChamberVoteLower" -> "LOWER_CHAMBER";
            case "ChamberVoteUpper", "ConferenceCommittee", "OverrideVote" -> "UPPER_CHAMBER";
            case "ExecutiveDecision" -> "EXECUTIVE";
            default -> null;
        };
        if (branch == null) {
            return false;
        }
        String jurisdictionId = "state".equalsIgnoreCase(bill.getScope()) ? bill.getStateId() : governmentState.getFederal().getNodeId();
        GovernmentState.InstitutionProfile institution = findInstitution(governmentState, jurisdictionId, branch);
        return institution != null
            && institution.getWorkload() > institution.getCapacityPerTick() + 1
            && institution.getLegitimacy() < 0.6d;
    }

    private GovernmentState.InstitutionProfile findInstitution(GovernmentState governmentState, String jurisdictionId, String branch) {
        if (governmentState == null || governmentState.getInstitutions() == null) {
            return null;
        }
        return governmentState.getInstitutions().stream()
            .filter(institution -> branch.equalsIgnoreCase(institution.getBranch()))
            .filter(institution -> jurisdictionId == null || jurisdictionId.equalsIgnoreCase(institution.getJurisdictionId()))
            .findFirst()
            .orElse(null);
    }

    private GovernmentState.InstitutionProfile weakestCourtInstitution(GovernmentState governmentState, String level) {
        if (governmentState == null || governmentState.getInstitutions() == null) {
            return null;
        }
        return governmentState.getInstitutions().stream()
            .filter(institution -> "COURT".equalsIgnoreCase(institution.getBranch()))
            .filter(institution -> level == null || level.equalsIgnoreCase(institution.getLevel()))
            .min(Comparator.comparingDouble(GovernmentState.InstitutionProfile::getLegitimacy))
            .orElse(null);
    }

    private GovernmentState.PoliticalParty resolveParty(GovernmentState governmentState, String partyId) {
        if (governmentState == null || partyId == null || partyId.isBlank()) {
            return null;
        }
        return governmentState.getParties().stream()
            .filter(party -> partyId.equalsIgnoreCase(party.getPartyId()))
            .findFirst()
            .orElse(null);
    }

    private boolean matchesJurisdiction(String scope, String stateId, String level, String jurisdictionId) {
        if ("federal".equalsIgnoreCase(level)) {
            return !"state".equalsIgnoreCase(scope);
        }
        return "state".equalsIgnoreCase(scope) && jurisdictionId != null && jurisdictionId.equalsIgnoreCase(stateId);
    }

    private boolean matchesCourtJurisdiction(GovernmentState.CourtCase courtCase, String level) {
        if (courtCase == null || level == null) {
            return false;
        }
        if ("federal".equalsIgnoreCase(level)) {
            return "SUPREME".equalsIgnoreCase(courtCase.getCourtLevel());
        }
        return "STATE_SUPREME".equalsIgnoreCase(courtCase.getCourtLevel());
    }

    private void populateBillSubstance(GovernmentState governmentState, GovernmentState.GovernmentBill bill, Instant now) {
        bill.setSections(defaultBillSections(bill.getPolicyDomain()));
        bill.setAffectedRights(defaultAffectedRights(bill.getPolicyDomain()));
        bill.setEnforcementMechanisms(defaultEnforcementMechanisms(bill.getPolicyDomain()));
        bill.setImplementationNotes(defaultImplementationNotes(bill.getPolicyDomain(), bill.getScope()));
        bill.setPreemptionScope("state".equalsIgnoreCase(bill.getScope()) ? "state floor with federal backstop" : "federal floor and ceiling");
        bill.setCommitteeName(defaultCommitteeName(bill.getPolicyDomain(), bill.getScope()));
        bill.setSponsorActorId(selectSponsorActorId(governmentState, bill.getPolicyDomain(), bill.getScope(), bill.getStateId()));
        bill.setCoalitionPartyIds(defaultCoalitionPartyIds(governmentState, bill.getSponsorPartyId(), bill.getPolicyDomain()));
        bill.setConferenceRequired("housing".equalsIgnoreCase(bill.getPolicyDomain()) || "rights-process".equalsIgnoreCase(bill.getPolicyDomain()));
        bill.setChamberTextStatus("DRAFTING");
        bill.setImplementationStatus("PENDING");
        bill.setEffectiveAt(now.plusMillis(billIntervalMs));
        bill.setFundingChanges(defaultFundingChanges(bill.getPolicyDomain()));
    }

    private void populateExecutiveOrderSubstance(GovernmentState governmentState, GovernmentState.ExecutiveOrder order, Instant now) {
        order.setDirectives(defaultExecutiveDirectives(order.getPolicyDomain()));
        order.setAffectedRights(defaultAffectedRights(order.getPolicyDomain()));
        order.setEnforcementMechanisms(defaultEnforcementMechanisms(order.getPolicyDomain()));
        order.setImplementationNotes(defaultImplementationNotes(order.getPolicyDomain(), order.getScope()));
        order.setPreemptionScope("federal".equalsIgnoreCase(order.getScope()) ? "temporary federal override" : "state emergency override");
        order.setAgencyName(agencyNameForPolicyDomain(order.getPolicyDomain(), order.getScope()));
        order.setEmergencyBasis(defaultEmergencyBasis(order.getPolicyDomain()));
        order.setChallengedImmediately("rights-process".equalsIgnoreCase(order.getPolicyDomain()) || "labor".equalsIgnoreCase(order.getPolicyDomain()));
        order.setEffectiveAt(now.plusMillis(maintenanceIntervalMs));
        order.setFundingChanges(defaultFundingChanges(order.getPolicyDomain()));
    }

    private void assignOfficeRoles(GovernmentState governmentState, List<RoomBot> bots) {
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            civicState.setOfficeRole("CITIZEN");
            civicState.setTermState("NONE");
            bot.setCivicStateJson(writeJson(civicState));
        }
        assignRole(governmentState.getFederal().getExecutivePartyId(), "FEDERAL_EXECUTIVE", bots, null);
        assignRole(governmentState.getFederal().getLowerChamberPartyId(), "FEDERAL_LEGISLATOR", bots, null);
        assignRole(governmentState.getFederal().getChiefJusticePartyId(), "FEDERAL_JUDGE", bots, null);
        for (GovernmentState.GovernmentNode stateNode : governmentState.getStates()) {
            assignRole(stateNode.getExecutivePartyId(), "STATE_EXECUTIVE", bots, stateNode.getNodeId());
            assignRole(stateNode.getLowerChamberPartyId(), "STATE_LEGISLATOR", bots, stateNode.getNodeId());
            assignRole(stateNode.getChiefJusticePartyId(), "STATE_JUDGE", bots, stateNode.getNodeId());
        }
    }

    private void writeBillEvent(GovernmentState governmentState, GovernmentState.GovernmentBill bill, Instant now) {
        if (!"ENACTED".equalsIgnoreCase(bill.getLawStatus())) {
            return;
        }
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "events/event-%05d.json", governmentState.getNextEventSequence()),
            Map.of(
                "event_id", governmentState.getNextEventSequence(),
                "timestamp", now,
                "event_type", "major_legislation",
                "location_scope", bill.getScope(),
                "summary", bill.getTitle() + " enacted",
                "triggering_action", bill.getBillId(),
                "affected_entities", List.of(bill.getPolicyDomain(), "budget", "institutions"),
                "resulting_world_changes", bill.getWorldEffects()
            )
        );
        governmentState.setNextEventSequence(governmentState.getNextEventSequence() + 1);
    }

    private void updateBudget(GovernmentState.BudgetState budget, String level, String jurisdictionId, long revenue, int activeBots) {
        budget.setBudgetId(level + "-" + jurisdictionId);
        budget.setLevel(level);
        budget.setJurisdictionId(jurisdictionId);
        budget.setRevenue(revenue);
        budget.setEducation(Math.round(revenue * 0.18d));
        budget.setHealth(Math.round(revenue * 0.18d));
        budget.setInfrastructure(Math.round(revenue * 0.22d));
        budget.setWelfare(Math.round(revenue * 0.14d));
        budget.setJustice(Math.round(revenue * 0.11d));
        budget.setPayroll(Math.round(revenue * (activeBots > 100 ? 0.13d : 0.09d)));
    }

    private void applyBudgetEffects(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        GovernmentState.GovernmentNode targetNode = "state".equalsIgnoreCase(bill.getScope())
            ? resolveStateNode(governmentState, bill.getStateId())
            : governmentState.getFederal();
        GovernmentState.BudgetState federalBudget = targetNode.getBudget();
        if ("infrastructure".equalsIgnoreCase(bill.getPolicyDomain())) {
            federalBudget.setInfrastructure(federalBudget.getInfrastructure() + 35);
        } else if ("housing".equalsIgnoreCase(bill.getPolicyDomain())) {
            federalBudget.setWelfare(federalBudget.getWelfare() + 24);
        } else if ("labor".equalsIgnoreCase(bill.getPolicyDomain())) {
            federalBudget.setJustice(federalBudget.getJustice() + 10);
            federalBudget.setWelfare(federalBudget.getWelfare() + 12);
        } else if ("services".equalsIgnoreCase(bill.getPolicyDomain())) {
            federalBudget.setHealth(federalBudget.getHealth() + 18);
            federalBudget.setEducation(federalBudget.getEducation() + 18);
        } else {
            federalBudget.setJustice(federalBudget.getJustice() + 14);
        }
    }

    private List<String> defaultBillSections(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> List.of("Findings", "Access standards", "Agency coordination", "Funding and reporting");
            case "housing" -> List.of("Affordability findings", "Tenant relief", "Local planning", "Funding and oversight");
            case "rights-process" -> List.of("Procedural safeguards", "Notice requirements", "Review rights", "Remedies");
            case "labor" -> List.of("Worker protections", "Compliance audits", "Enforcement", "Appropriations");
            default -> List.of("Mobility findings", "Capital projects", "Jurisdictional coordination", "Reporting");
        };
    }

    private List<String> defaultAffectedRights(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "rights-process" -> List.of("due-process", "equal-protection", "petition");
            case "housing" -> List.of("residence", "equal-protection");
            case "labor" -> List.of("association", "labor-liberty");
            case "services" -> List.of("equal-protection", "citizenship");
            default -> List.of("travel", "petition");
        };
    }

    private List<String> defaultEnforcementMechanisms(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "rights-process" -> List.of("judicial review trigger", "agency notice", "injunctive relief");
            case "services" -> List.of("agency certification", "continuity audit", "budget clawback");
            case "housing" -> List.of("local compliance review", "grant conditions", "civil enforcement");
            case "labor" -> List.of("inspector general review", "civil penalties", "restoration order");
            default -> List.of("capital reporting", "performance audit", "appropriation controls");
        };
    }

    private List<String> defaultImplementationNotes(String policyDomain, String scope) {
        String level = "state".equalsIgnoreCase(scope) ? "state" : "federal";
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> List.of("Primary agencies have 1 cycle to publish access plans.", level + " agencies must report continuity metrics.");
            case "housing" -> List.of("Relief formulas phase in over 2 cycles.", level + " implementers must preserve local baseline rights.");
            case "rights-process" -> List.of("Administrative sanctions pause pending notice.", "Courts receive standing to review contested actions.");
            case "labor" -> List.of("Workforce audits begin next cycle.", "Compliance metrics are shared with judicial oversight.");
            default -> List.of("Capital scheduling begins next cycle.", level + " project board must publish corridor priorities.");
        };
    }

    private Map<String, Long> defaultFundingChanges(String policyDomain) {
        Map<String, Long> changes = new LinkedHashMap<>();
        switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> {
                changes.put("health", 18L);
                changes.put("education", 18L);
            }
            case "housing" -> {
                changes.put("welfare", 24L);
                changes.put("infrastructure", 10L);
            }
            case "rights-process" -> {
                changes.put("justice", 14L);
                changes.put("payroll", 8L);
            }
            case "labor" -> {
                changes.put("justice", 10L);
                changes.put("welfare", 12L);
            }
            default -> {
                changes.put("infrastructure", 35L);
                changes.put("payroll", 6L);
            }
        }
        return changes;
    }

    private List<String> defaultExecutiveDirectives(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "rights-process" -> List.of("Suspend challenged enforcement", "Issue emergency procedural guidance", "Report compliance to court");
            case "services" -> List.of("Stabilize service delivery", "Reallocate emergency personnel", "Publish continuity dashboard");
            case "housing" -> List.of("Freeze displacement actions", "Prioritize relief disbursement", "Coordinate with municipalities");
            case "labor" -> List.of("Pause retaliatory enforcement", "Deploy labor compliance teams", "Publish workplace standards bulletin");
            default -> List.of("Coordinate agencies", "Publish implementation memo", "Report operational impacts");
        };
    }

    private String defaultCommitteeName(String policyDomain, String scope) {
        String level = "state".equalsIgnoreCase(scope) ? "State" : "Federal";
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> level + " Human Services Committee";
            case "housing" -> level + " Housing and Municipal Affairs Committee";
            case "labor" -> level + " Labor and Commerce Committee";
            case "rights-process" -> level + " Judiciary Committee";
            default -> level + " Infrastructure and Works Committee";
        };
    }

    private String selectSponsorActorId(GovernmentState governmentState, String policyDomain, String scope, String stateId) {
        GovernmentState.CivicActor actor = governmentState.getActors().stream()
            .filter(item -> "federal".equalsIgnoreCase(scope)
                ? "federal".equalsIgnoreCase(item.getScope())
                : Objects.equals(item.getJurisdictionId(), stateId) || "federal".equalsIgnoreCase(item.getScope()))
            .filter(item -> item.getAgendas().stream().anyMatch(agenda -> policyDomain.equalsIgnoreCase(agenda)))
            .max(Comparator.comparingDouble(GovernmentState.CivicActor::getInfluence))
            .orElse(null);
        return actor == null ? null : actor.getActorId();
    }

    private List<String> defaultCoalitionPartyIds(GovernmentState governmentState, String sponsorPartyId, String policyDomain) {
        List<String> coalition = new ArrayList<>();
        if (sponsorPartyId != null) {
            coalition.add(sponsorPartyId);
        }
        if ("services".equalsIgnoreCase(policyDomain) || "labor".equalsIgnoreCase(policyDomain)) {
            coalition.add("party-civic-labor");
        }
        if ("rights-process".equalsIgnoreCase(policyDomain) || "housing".equalsIgnoreCase(policyDomain)) {
            coalition.add("party-liberty-green");
        }
        if ("infrastructure".equalsIgnoreCase(policyDomain)) {
            coalition.add("party-order-build");
        }
        return coalition.stream().filter(Objects::nonNull).distinct().toList();
    }

    private String defaultEmergencyBasis(String policyDomain) {
        return switch (String.valueOf(policyDomain).toLowerCase(Locale.ROOT)) {
            case "services" -> "service continuity emergency";
            case "rights-process" -> "constitutional safeguard emergency";
            case "housing" -> "displacement mitigation emergency";
            case "labor" -> "workplace compliance emergency";
            default -> "infrastructure continuity emergency";
        };
    }

    private String nextBillTitle(GovernmentState governmentState, List<RoomBot> bots, String scope, String stateId) {
        String domain = nextPolicyDomain(governmentState, bots, scope, stateId);
        return switch (domain) {
            case "infrastructure" -> "federal".equalsIgnoreCase(scope)
                ? "National Mobility and Corridor Act"
                : "State Infrastructure Acceleration Act";
            case "housing" -> "federal".equalsIgnoreCase(scope)
                ? "Housing Burden Stabilization Act"
                : "State Housing Relief Act";
            case "labor" -> "federal".equalsIgnoreCase(scope)
                ? "National Workplace Dignity Act"
                : "State Worker Protection Act";
            case "services" -> "federal".equalsIgnoreCase(scope)
                ? "Public Services Access Act"
                : "State Services Continuity Act";
            default -> "federal".equalsIgnoreCase(scope)
                ? "Rights and Procedure Integrity Act"
                : "State Due Process Safeguard Act";
        };
    }

    private String nextPolicyDomain(GovernmentState governmentState, List<RoomBot> bots, String scope, String stateId) {
        double service = 0d;
        double trust = 0d;
        double burden = 0d;
        double participation = 0d;
        double debtPressure = 0d;
        double wageBand = 0d;
        int count = 0;
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            if ("state".equalsIgnoreCase(scope) && stateId != null && !Objects.equals(stateId, civicState.getStateId())) {
                continue;
            }
            count++;
            service += civicState.getServiceAccess();
            trust += civicState.getInstitutionalTrust();
            burden += civicState.getCostBurdenBand();
            participation += civicState.getCivicParticipation();
            debtPressure += civicState.getDebtPressureBand();
            wageBand += civicState.getWageBand();
        }
        if (count == 0) {
            return "infrastructure";
        }
        double avgService = service / count;
        double avgTrust = trust / count;
        double avgBurden = burden / count;
        double avgParticipation = participation / count;
        double avgDebtPressure = debtPressure / count;
        double avgWageBand = wageBand / count;
        if (avgService < 0.42d) {
            return "services";
        }
        if (avgBurden > 2.2d) {
            return "housing";
        }
        if (avgTrust < 0.45d) {
            return "rights-process";
        }
        if (avgDebtPressure > 2.0d || (avgParticipation > 0.58d && avgWageBand < 2.4d)) {
            return "labor";
        }
        return "infrastructure";
    }

    private GovernmentState.GovernmentNode weakestStateNode(GovernmentState governmentState) {
        return governmentState.getStates().stream()
            .min(Comparator.comparingDouble(GovernmentState.GovernmentNode::getPublicTrust)
                .thenComparingDouble(GovernmentState.GovernmentNode::getServiceAccess))
            .orElse(governmentState.getFederal());
    }

    private GovernmentState.GovernmentNode resolveStateNode(GovernmentState governmentState, String stateId) {
        if (stateId == null || stateId.isBlank()) {
            return governmentState.getFederal();
        }
        return governmentState.getStates().stream()
            .filter(state -> Objects.equals(state.getNodeId(), stateId))
            .findFirst()
            .orElse(governmentState.getFederal());
    }

    private String chamberPartyId(GovernmentState governmentState, GovernmentState.GovernmentBill bill, boolean lower) {
        GovernmentState.GovernmentNode node = "state".equalsIgnoreCase(bill.getScope())
            ? resolveStateNode(governmentState, bill.getStateId())
            : governmentState.getFederal();
        return lower ? node.getLowerChamberPartyId() : node.getUpperChamberPartyId();
    }

    private String executivePartyId(GovernmentState governmentState, GovernmentState.GovernmentBill bill) {
        GovernmentState.GovernmentNode node = "state".equalsIgnoreCase(bill.getScope())
            ? resolveStateNode(governmentState, bill.getStateId())
            : governmentState.getFederal();
        return node.getExecutivePartyId();
    }

    private double budgetServiceBonus(GovernmentState.BudgetState budget) {
        double total = budget.getEducation() + budget.getHealth() + budget.getInfrastructure() + budget.getWelfare();
        return Math.min(0.18d, total / 10_000d);
    }

    private void updateEconomicState(BotCivicState civicState,
                                     GovernmentState governmentState,
                                     GovernmentState.GovernmentNode stateNode,
                                     double serviceDelta,
                                     double pressureDelta) {
        civicState.setServiceAccess(clamp01(civicState.getServiceAccess() * 0.65d + serviceDelta * 0.35d));
        if ("SEEKING".equals(civicState.getEmploymentStatus())
            && (stateNode.getBudget().getPayroll() > 12 || governmentState.getFederal().getBudget().getInfrastructure() > 18)) {
            civicState.setEmploymentStatus("PUBLIC_SERVICE");
            civicState.setOccupationSector("CIVIC");
            civicState.setWageBand(Math.min(5, civicState.getWageBand() + 1));
        } else if (pressureDelta > 0.55d && civicState.getWageBand() <= 1) {
            civicState.setEmploymentStatus("SEEKING");
        } else if (civicState.getServiceAccess() > 0.62d && "SEEKING".equals(civicState.getEmploymentStatus())) {
            civicState.setEmploymentStatus("EMPLOYED");
            civicState.setOccupationSector("SERVICE");
        }
        if (civicState.getServiceAccess() < 0.35d) {
            civicState.setCostBurdenBand(Math.min(5, civicState.getCostBurdenBand() + 1));
            civicState.setDebtPressureBand(Math.min(5, civicState.getDebtPressureBand() + 1));
        } else {
            civicState.setSavingsPressureBand(Math.max(0, civicState.getSavingsPressureBand() - 1));
            civicState.setDebtPressureBand(Math.max(0, civicState.getDebtPressureBand() - 1));
        }
        if (pressureDelta > 0.65d) {
            civicState.setProceduralState("PETITIONING");
        } else if (!"NONE".equals(civicState.getSanctionState())) {
            civicState.setProceduralState("REVIEW");
        } else {
            civicState.setProceduralState("CLEAR");
        }
    }

    private double averageServiceAccess(List<RoomBot> bots) {
        double total = 0d;
        int count = 0;
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            total += civicState.getServiceAccess();
            count++;
        }
        return count == 0 ? 0.5d : total / count;
    }

    private double averageTrust(List<RoomBot> bots) {
        double total = 0d;
        int count = 0;
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            total += civicState.getInstitutionalTrust();
            count++;
        }
        return count == 0 ? 0.5d : total / count;
    }

    private double petitioningShare(List<RoomBot> bots) {
        int total = 0;
        int petitioning = 0;
        for (RoomBot bot : bots) {
            BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
            if (civicState == null) {
                continue;
            }
            total++;
            if ("PETITIONING".equalsIgnoreCase(civicState.getProceduralState())
                || "REVIEW".equalsIgnoreCase(civicState.getProceduralState())) {
                petitioning++;
            }
        }
        return total == 0 ? 0.0d : petitioning / (double) total;
    }

    private List<RoomBot> uniqueDirtyBots(List<RoomBot> existing, List<RoomBot> additions) {
        Map<UUID, RoomBot> merged = new LinkedHashMap<>();
        for (RoomBot bot : existing) {
            merged.put(bot.getId(), bot);
        }
        for (RoomBot bot : additions) {
            merged.put(bot.getId(), bot);
        }
        return new ArrayList<>(merged.values());
    }

    private boolean appliesToBotJurisdiction(String scope, String jurisdictionTarget, String botStateId) {
        return !"state".equalsIgnoreCase(scope)
            || (botStateId != null && botStateId.equalsIgnoreCase(jurisdictionTarget));
    }

    private long countPolicyMeasures(List<GovernmentState.GovernmentBill> bills,
                                     List<GovernmentState.ExecutiveOrder> orders,
                                     String policyDomain) {
        return bills.stream().filter(bill -> policyDomain.equalsIgnoreCase(bill.getPolicyDomain())).count()
            + orders.stream().filter(order -> policyDomain.equalsIgnoreCase(order.getPolicyDomain())).count();
    }

    private boolean hasConstitutionProtection(GovernmentState governmentState, String protectionType) {
        if (governmentState == null || governmentState.getConstitutionalRules() == null) {
            return false;
        }
        return governmentState.getConstitutionalRules().stream()
            .anyMatch(rule -> protectionType.equalsIgnoreCase(rule.getProtectionType()));
    }

    private double laborExposure(BotCivicState civicState) {
        if (civicState == null) {
            return 0.0d;
        }
        if ("PUBLIC_SERVICE".equalsIgnoreCase(civicState.getEmploymentStatus())
            || "EMPLOYED".equalsIgnoreCase(civicState.getEmploymentStatus())) {
            return civicState.getWageBand() <= 1 ? 0.16d : 0.09d;
        }
        return 0.03d;
    }

    private double housingExposure(BotCivicState civicState) {
        if (civicState == null) {
            return 0.0d;
        }
        return civicState.getCostBurdenBand() >= 3 ? 0.10d : 0.04d;
    }

    private double serviceExposure(BotCivicState civicState) {
        if (civicState == null) {
            return 0.0d;
        }
        return civicState.getServiceAccess() < 0.42d ? 0.08d : 0.02d;
    }

    private void applyExecutiveOrderEffects(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        GovernmentState.GovernmentNode target = "state".equalsIgnoreCase(order.getScope())
            ? resolveStateNode(governmentState, order.getJurisdictionId())
            : governmentState.getFederal();
        GovernmentState.BudgetState budget = target.getBudget();
        if ("services".equalsIgnoreCase(order.getPolicyDomain())) {
            budget.setHealth(budget.getHealth() + 12);
            budget.setEducation(budget.getEducation() + 12);
            target.setServiceAccess(clamp01(target.getServiceAccess() + 0.03d));
        } else if ("housing".equalsIgnoreCase(order.getPolicyDomain())) {
            budget.setWelfare(budget.getWelfare() + 10);
            budget.setInfrastructure(budget.getInfrastructure() + 8);
            target.setPublicTrust(clamp01(target.getPublicTrust() + 0.015d));
        } else if ("labor".equalsIgnoreCase(order.getPolicyDomain())) {
            budget.setJustice(budget.getJustice() + 9);
            budget.setPayroll(budget.getPayroll() + 6);
            target.setPublicTrust(clamp01(target.getPublicTrust() - 0.01d));
        } else {
            budget.setJustice(budget.getJustice() + 8);
            target.setPublicTrust(clamp01(target.getPublicTrust() + 0.01d));
        }
    }

    private void assignRole(String partyId, String role, List<RoomBot> bots, String stateId) {
        RoomBot winner = bots.stream()
            .filter(bot -> {
                BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
                return civicState != null
                    && Objects.equals(civicState.getPartyId(), partyId)
                    && (stateId == null || Objects.equals(civicState.getStateId(), stateId));
            })
            .max(Comparator.comparingDouble(bot -> {
                BotCivicState civicState = readBotCivicState(bot.getCivicStateJson());
                return civicState == null ? 0d : civicState.getCivicParticipation() + civicState.getInstitutionalTrust();
            }))
            .orElse(null);
        if (winner == null) {
            return;
        }
        BotCivicState civicState = readBotCivicState(winner.getCivicStateJson());
        if (civicState == null) {
            return;
        }
        civicState.setOfficeRole(role);
        civicState.setTermState("ACTIVE");
        winner.setCivicStateJson(writeJson(civicState));
    }

    private int projectVotes(GovernmentState governmentState,
                             String partyId,
                             List<GovernmentState.PoliticalParty> parties,
                             List<RoomBot> bots,
                             String jurisdictionId,
                             String branch) {
        int members = parties.stream()
            .filter(party -> Objects.equals(party.getPartyId(), partyId))
            .mapToInt(GovernmentState.PoliticalParty::getMemberCount)
            .findFirst()
            .orElse(0);
        int projected = Math.max(1, Math.min(Math.max(1, bots.size() / 3), members / 8 + 1));
        GovernmentState.InstitutionProfile institution = findInstitution(governmentState, jurisdictionId, branch);
        if (institution != null && institution.getLegitimacy() < 0.45d) {
            projected = Math.max(1, projected - 1);
        }
        double pressure = recentPressureIntensityForInstitution(governmentState, institution == null ? null : institution.getInstitutionId());
        if (pressure > 0.65d) {
            projected = Math.max(1, projected - 1);
        }
        GovernmentState.CivicActor actor = governmentState.getActors().stream()
            .filter(item -> Objects.equals(item.getAlignedPartyId(), partyId))
            .max(Comparator.comparingDouble(GovernmentState.CivicActor::getInfluence))
            .orElse(null);
        if (actor != null && actor.getInfluence() > 0.6d) {
            projected += 1;
        }
        return projected;
    }

    private List<String> defaultWorldEffectsFor(String domain) {
        if ("infrastructure".equalsIgnoreCase(domain)) {
            return List.of("highway_expansion", "public_jobs_increase");
        }
        if ("housing".equalsIgnoreCase(domain)) {
            return List.of("residential_stability", "rent_pressure_relief");
        }
        if ("services".equalsIgnoreCase(domain)) {
            return List.of("health_access_increase", "education_access_increase");
        }
        return List.of("due_process_enforcement", "rights_safeguards");
    }

    private boolean shouldCreateBill(GovernmentState governmentState, Instant now) {
        return governmentState.getLastBillAt() == null
            || now.toEpochMilli() - governmentState.getLastBillAt().toEpochMilli() >= billIntervalMs;
    }

    private List<GovernmentState.ConstitutionRule> copyRules() {
        List<GovernmentState.ConstitutionRule> copy = new ArrayList<>();
        for (GovernmentState.ConstitutionRule rule : baseRules) {
            copy.add(objectMapper.convertValue(rule, GovernmentState.ConstitutionRule.class));
        }
        return copy;
    }

    private List<GovernmentState.PoliticalParty> defaultParties() {
        List<GovernmentState.PoliticalParty> parties = new ArrayList<>();
        parties.add(party("party-civic-labor", "Civic Labor", 0.42d, 0.44d, 0.82d, 0.56d));
        parties.add(party("party-liberty-green", "Liberty Green", 0.84d, 0.30d, 0.58d, 0.47d));
        parties.add(party("party-order-build", "Order Build", 0.36d, 0.82d, 0.34d, 0.86d));
        return parties;
    }

    private GovernmentState.PoliticalParty party(String partyId, String name, double liberty, double order, double welfare, double growth) {
        GovernmentState.PoliticalParty party = new GovernmentState.PoliticalParty();
        party.setPartyId(partyId);
        party.setName(name);
        party.setLiberty(liberty);
        party.setOrder(order);
        party.setWelfare(welfare);
        party.setGrowth(growth);
        party.setInfluence(0d);
        party.setMemberCount(0);
        return party;
    }

    private GovernmentState.GovernmentNode defaultFederalNode() {
        GovernmentState.GovernmentNode node = new GovernmentState.GovernmentNode();
        node.setNodeId("federal");
        node.setLevel("federal");
        node.setDisplayName("Federal Union");
        node.setDistrictingMode("population");
        node.setDistrictCount(Math.max(3, configuredStateCount * 3));
        node.setLowerChamberPartyId("party-civic-labor");
        node.setUpperChamberPartyId("party-order-build");
        node.setExecutivePartyId("party-civic-labor");
        node.setChiefJusticePartyId("party-liberty-green");
        node.setPublicTrust(0.52d);
        node.setServiceAccess(0.48d);
        updateBudget(node.getBudget(), "federal", "federal", 0L, 0);
        return node;
    }

    private List<GovernmentState.GovernmentNode> defaultStates(int[][] tiles) {
        int count = Math.max(1, Math.min(configuredStateCount, stateLayoutCount(tiles)));
        List<GovernmentState.GovernmentNode> states = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GovernmentState.GovernmentNode node = new GovernmentState.GovernmentNode();
            node.setNodeId("state-" + (i + 1));
            node.setLevel("state");
            node.setDisplayName("State " + (i + 1));
            node.setDistrictingMode("regional");
            node.setDistrictCount(4);
            node.setLowerChamberPartyId("party-liberty-green");
            node.setUpperChamberPartyId("party-order-build");
            node.setExecutivePartyId("party-liberty-green");
            node.setChiefJusticePartyId("party-civic-labor");
            node.setPublicTrust(0.5d);
            node.setServiceAccess(0.45d);
            updateBudget(node.getBudget(), "state", node.getNodeId(), 0L, 0);
            states.add(node);
        }
        return states;
    }

    private int stateLayoutCount(int[][] tiles) {
        if (tiles == null || tiles.length == 0 || tiles[0].length == 0) {
            return configuredStateCount;
        }
        int width = tiles[0].length;
        int height = tiles.length;
        if (width < 20 || height < 20) {
            return 1;
        }
        if (width < 48 || height < 36) {
            return Math.min(2, configuredStateCount);
        }
        return configuredStateCount;
    }

    private String householdId(Room room, RoomBot bot) {
        int bucket = Math.floorMod(Objects.hash(room.getId(), bot.getType()), 24);
        return String.format(Locale.ROOT, "household-%02d", bucket);
    }

    private String ageTier(Room room, RoomBot bot) {
        int value = Math.floorMod(Objects.hash(room.getId(), bot.getId(), bot.getType()), 100);
        if (value < 18) {
            return "YOUTH";
        }
        if (value < 70) {
            return "ADULT";
        }
        return "SENIOR";
    }

    private void applyProfileIdeology(BotCivicState civicState, Map<String, Double> profile) {
        Map<String, Double> safe = profile == null ? Map.of() : profile;
        civicState.setIdeologyLiberty(clamp01(0.25d + safe.getOrDefault("clear", 0.2d) * 0.7d));
        civicState.setIdeologyOrder(clamp01(0.2d + safe.getOrDefault("industry", 0.2d) * 0.75d));
        civicState.setIdeologyWelfare(clamp01(0.2d + safe.getOrDefault("consensus", 0.2d) * 0.8d));
        civicState.setIdeologyGrowth(clamp01(0.2d + safe.getOrDefault("mobility", 0.2d) * 0.65d));
    }

    private void hydrateEconomicState(BotCivicState civicState, RoomBot bot, int[][] tiles) {
        int tileValue = terrainValue(bot, tiles);
        if (tileValue == 3) {
            civicState.setEmploymentStatus("EMPLOYED");
            civicState.setOccupationSector("INDUSTRY");
            civicState.setWageBand(4);
            civicState.setCostBurdenBand(2);
        } else if (tileValue == 0) {
            civicState.setEmploymentStatus("PUBLIC_SERVICE");
            civicState.setOccupationSector("CIVIC");
            civicState.setWageBand(3);
            civicState.setCostBurdenBand(2);
        } else if (tileValue == 1) {
            civicState.setEmploymentStatus("EMPLOYED");
            civicState.setOccupationSector("RESOURCE");
            civicState.setWageBand(2);
            civicState.setCostBurdenBand(1);
        } else {
            civicState.setEmploymentStatus("SEEKING");
            civicState.setOccupationSector("SERVICE");
            civicState.setWageBand(1);
            civicState.setCostBurdenBand(3);
        }
        civicState.setSavingsPressureBand(Math.max(0, 4 - civicState.getWageBand()));
        civicState.setDebtPressureBand(Math.max(0, civicState.getCostBurdenBand() + 1 - civicState.getWageBand()));
    }

    private int terrainValue(RoomBot bot, int[][] tiles) {
        if (tiles == null || tiles.length == 0 || tiles[0].length == 0 || bot.getX() == null || bot.getY() == null) {
            return 0;
        }
        int x = Math.max(0, Math.min(tiles[0].length - 1, bot.getX()));
        int y = Math.max(0, Math.min(tiles.length - 1, bot.getY()));
        return tiles[y][x];
    }

    private String deriveStateId(RoomBot bot, int[][] tiles) {
        int count = Math.max(1, Math.min(configuredStateCount, stateLayoutCount(tiles)));
        if (count == 1 || tiles == null || tiles.length == 0 || tiles[0].length == 0 || bot.getX() == null || bot.getY() == null) {
            return "state-1";
        }
        int width = tiles[0].length;
        int height = tiles.length;
        int xBucket = Math.min(1, Math.max(0, bot.getX() * 2 / Math.max(1, width)));
        int yBucket = Math.min(1, Math.max(0, bot.getY() * 2 / Math.max(1, height)));
        int index = Math.min(count - 1, yBucket * 2 + xBucket);
        return "state-" + (index + 1);
    }

    private String deriveDistrictId(RoomBot bot, int[][] tiles) {
        if (tiles == null || tiles.length == 0 || tiles[0].length == 0 || bot.getX() == null || bot.getY() == null) {
            return "district-1";
        }
        int width = tiles[0].length;
        int height = tiles.length;
        int xBucket = Math.min(3, Math.max(0, bot.getX() * 4 / Math.max(1, width)));
        int yBucket = Math.min(3, Math.max(0, bot.getY() * 4 / Math.max(1, height)));
        return "district-" + (yBucket * 4 + xBucket + 1);
    }

    private double terrainServiceSignal(RoomBot bot, int[][] tiles) {
        int tileValue = terrainValue(bot, tiles);
        return switch (tileValue) {
            case 0 -> 0.56d;
            case 1 -> 0.42d;
            case 2 -> 0.28d;
            case 3 -> 0.67d;
            default -> 0.5d;
        };
    }

    private double economicPressure(BotCivicState civicState) {
        return Math.max(0d, civicState.getCostBurdenBand() * 0.18d
            + civicState.getDebtPressureBand() * 0.11d
            - civicState.getWageBand() * 0.08d);
    }

    private String closestPartyId(List<GovernmentState.PoliticalParty> parties, BotCivicState civicState) {
        return parties.stream()
            .min(Comparator.comparingDouble(party -> partyDistance(party, civicState)))
            .map(GovernmentState.PoliticalParty::getPartyId)
            .orElse("party-civic-labor");
    }

    private double partyDistance(GovernmentState.PoliticalParty party, BotCivicState civicState) {
        return Math.abs(party.getLiberty() - civicState.getIdeologyLiberty())
            + Math.abs(party.getOrder() - civicState.getIdeologyOrder())
            + Math.abs(party.getWelfare() - civicState.getIdeologyWelfare())
            + Math.abs(party.getGrowth() - civicState.getIdeologyGrowth());
    }

    private double partyAffinity(GovernmentState.PoliticalParty party, BotCivicState civicState) {
        return 1.0d / (0.35d + partyDistance(party, civicState));
    }

    private String resolvePartyName(GovernmentState governmentState, String partyId) {
        if (partyId == null) {
            return "None";
        }
        return governmentState.getParties().stream()
            .filter(party -> partyId.equals(party.getPartyId()))
            .map(GovernmentState.PoliticalParty::getName)
            .findFirst()
            .orElse(partyId);
    }

    private String resolveActorName(GovernmentState governmentState, String actorId) {
        if (governmentState == null || actorId == null || actorId.isBlank() || governmentState.getActors() == null) {
            return actorId == null ? "Unknown actor" : actorId;
        }
        return governmentState.getActors().stream()
            .filter(actor -> actorId.equalsIgnoreCase(actor.getActorId()))
            .map(GovernmentState.CivicActor::getName)
            .findFirst()
            .orElse(actorId);
    }

    private record LegalReviewPlan(String doctrine,
                                   String scrutinyLevel,
                                   String standingBasis,
                                   String remedy,
                                   String legalBasis,
                                   String constitutionalBasis,
                                   String issueSummary,
                                   String reviewPosture,
                                   List<String> precedentIds,
                                   String policyDomain) {
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }
}
