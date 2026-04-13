package com.emergentterrain.government;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
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

    public GovernmentSimulationService(ObjectMapper objectMapper,
                                       SimulationHistoryWriter historyWriter,
                                       @Value("${app.government.enabled:true}") boolean enabled,
                                       @Value("${app.government.maintenanceIntervalMs:2500}") long maintenanceIntervalMs,
                                       @Value("${app.government.electionIntervalMs:60000}") long electionIntervalMs,
                                       @Value("${app.government.billIntervalMs:20000}") long billIntervalMs,
                                       @Value("${app.government.stateCount:4}") int configuredStateCount,
                                       @Value("classpath:government/constitution-rules.json") Resource constitutionRulesResource) {
        this.objectMapper = objectMapper;
        this.historyWriter = historyWriter;
        this.enabled = enabled;
        this.maintenanceIntervalMs = Math.max(250L, maintenanceIntervalMs);
        this.electionIntervalMs = Math.max(this.maintenanceIntervalMs * 4, electionIntervalMs);
        this.billIntervalMs = Math.max(this.maintenanceIntervalMs * 2, billIntervalMs);
        this.configuredStateCount = Math.max(1, configuredStateCount);
        this.baseRules = loadRules(constitutionRulesResource);
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
        boolean billChanged = advanceBills(governmentState, bots, now);
        boolean orderChanged = maybeIssueExecutiveOrders(governmentState, bots, now);
        boolean courtChanged = maybeReviewByCourts(governmentState, now);
        boolean electionChanged = maybeRunElection(governmentState, bots, now);
        assignOfficeRoles(governmentState, bots);
        changed = changed || billChanged || orderChanged || courtChanged || electionChanged;
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
        summary.setStateCount(governmentState.getStates().size());
        List<String> activeBills = new ArrayList<>();
        for (GovernmentState.GovernmentBill bill : governmentState.getBills()) {
            if (!"ENACTED".equalsIgnoreCase(bill.getLawStatus()) && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus())) {
                activeBills.add(String.format(Locale.ROOT, "%s: %s (%s)", bill.getBillId(), bill.getTitle(), bill.getStage()));
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
            }
        }
        summary.setExecutiveOrders(executiveOrders);
        summary.setActiveExecutiveOrderCount(executiveOrders.size());
        List<String> courtCases = new ArrayList<>();
        for (GovernmentState.CourtCase courtCase : governmentState.getCourtCases()) {
            courtCases.add(String.format(Locale.ROOT, "%s: %s", courtCase.getCaseId(), courtCase.getDecision()));
        }
        summary.setCourtCases(courtCases);
        summary.setActiveCourtCaseCount(courtCases.size());
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
        state.setConstitutionalRules(copyRules());
        state.setParties(defaultParties());
        state.setFederal(defaultFederalNode());
        state.setStates(defaultStates(tiles));
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
    }

    private void refreshGovernmentAggregates(GovernmentState governmentState, List<RoomBot> bots) {
        Map<String, Integer> memberCount = new HashMap<>();
        double trustSum = 0d;
        double serviceSum = 0d;
        long revenue = 0L;
        int active = 0;
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
        }
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
        switch (activeBill.getStage()) {
            case "DraftBill" -> activeBill.setStage("SponsorAssignment");
            case "SponsorAssignment" -> activeBill.setStage("CommitteeReview");
            case "CommitteeReview" -> activeBill.setStage("DebateWindow");
            case "DebateWindow" -> {
                activeBill.setStage("ChamberVoteLower");
                activeBill.setLowerVotes(projectVotes(chamberPartyId(governmentState, activeBill, true), governmentState.getParties(), bots));
            }
            case "ChamberVoteLower" -> {
                activeBill.setStage("ChamberVoteUpper");
                activeBill.setUpperVotes(projectVotes(chamberPartyId(governmentState, activeBill, false), governmentState.getParties(), bots));
            }
            case "ChamberVoteUpper" -> {
                activeBill.setStage("ExecutiveDecision");
                if (!Objects.equals(activeBill.getSponsorPartyId(), executivePartyId(governmentState, activeBill))
                    && activeBill.getPolicyDomain().startsWith("rights")) {
                    activeBill.setVetoed(true);
                }
            }
            case "ExecutiveDecision" -> {
                if (activeBill.isVetoed()) {
                    activeBill.setStage("OverrideVote");
                } else {
                    activeBill.setStage("JudicialReviewOptional");
                }
            }
            case "OverrideVote" -> {
                if (activeBill.getLowerVotes() + activeBill.getUpperVotes() > Math.max(4, bots.size() / 8)) {
                    activeBill.setVetoed(false);
                    activeBill.setStage("JudicialReviewOptional");
                } else {
                    activeBill.setStage("LawActivation");
                    activeBill.setLawStatus("BLOCKED");
                }
            }
            case "JudicialReviewOptional" -> activeBill.setStage("LawActivation");
            case "LawActivation" -> {
                activeBill.setStage("BudgetAllocation");
                activeBill.setLawStatus("ENACTED");
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
        if (avgService >= 0.44d && avgTrust >= 0.40d) {
            return false;
        }
        GovernmentState.ExecutiveOrder existing = governmentState.getExecutiveOrders().stream()
            .filter(order -> "ACTIVE".equalsIgnoreCase(order.getStatus()))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            return false;
        }
        GovernmentState.GovernmentNode targetNode = avgService < 0.38d && !governmentState.getStates().isEmpty()
            ? weakestStateNode(governmentState)
            : governmentState.getFederal();
        String policyDomain = avgService < 0.38d ? "services" : "rights-process";
        GovernmentState.ExecutiveOrder order = new GovernmentState.ExecutiveOrder();
        order.setOrderId(String.format(Locale.ROOT, "order-%05d", governmentState.getNextOrderSequence()));
        governmentState.setNextOrderSequence(governmentState.getNextOrderSequence() + 1);
        order.setScope("federal".equals(targetNode.getLevel()) ? "federal" : "state");
        order.setJurisdictionId(targetNode.getNodeId());
        order.setIssuedByPartyId(targetNode.getExecutivePartyId());
        order.setPolicyDomain(policyDomain);
        order.setSummary(policyDomain.equals("services")
            ? "Emergency services continuity order"
            : "Rights process safeguard order");
        order.setStatus("ACTIVE");
        order.setIssuedAt(now);
        order.setAffectedSystems(defaultWorldEffectsFor(policyDomain));
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
        GovernmentState.GovernmentBill billToReview = governmentState.getBills().stream()
            .filter(bill -> ("JudicialReviewOptional".equalsIgnoreCase(bill.getStage())
                || "LawActivation".equalsIgnoreCase(bill.getStage()))
                && !"BLOCKED".equalsIgnoreCase(bill.getLawStatus()))
            .findFirst()
            .orElse(null);
        GovernmentState.ExecutiveOrder orderToReview = governmentState.getExecutiveOrders().stream()
            .filter(order -> "ACTIVE".equalsIgnoreCase(order.getStatus()) && "rights-process".equalsIgnoreCase(order.getPolicyDomain()))
            .findFirst()
            .orElse(null);
        if (billToReview == null && orderToReview == null) {
            return false;
        }
        GovernmentState.CourtCase courtCase = new GovernmentState.CourtCase();
        courtCase.setCaseId(String.format(Locale.ROOT, "case-%05d", governmentState.getNextCourtSequence()));
        governmentState.setNextCourtSequence(governmentState.getNextCourtSequence() + 1);
        courtCase.setCourtLevel(orderToReview != null || (billToReview != null && "federal".equalsIgnoreCase(billToReview.getScope()))
            ? "SUPREME"
            : "STATE_SUPREME");
        courtCase.setPlaintiff("civic-population");
        courtCase.setDefendant(billToReview != null ? billToReview.getBillId() : orderToReview.getOrderId());
        courtCase.setLegalBasis("rights review");
        courtCase.setConstitutionalBasis("Amendments I, V, XIV");
        courtCase.setDecidedAt(now);
        boolean blocked = false;
        if (billToReview != null && "state".equalsIgnoreCase(billToReview.getScope())
            && "rights-process".equalsIgnoreCase(billToReview.getPolicyDomain())) {
            billToReview.setLawStatus("BLOCKED");
            billToReview.setStage("WorldEffectApplication");
            blocked = true;
        }
        if (orderToReview != null) {
            orderToReview.setStatus("BLOCKED");
            blocked = true;
        }
        courtCase.setBlockedAction(blocked);
        courtCase.setDecision(blocked ? "State action blocked under constitutional review" : "Action upheld");
        List<String> affected = new ArrayList<>();
        if (billToReview != null) {
            affected.add(billToReview.getBillId());
        }
        if (orderToReview != null) {
            affected.add(orderToReview.getOrderId());
        }
        courtCase.setAffectedLaws(affected);
        governmentState.getCourtCases().add(0, courtCase);
        if (governmentState.getCourtCases().size() > 12) {
            governmentState.getCourtCases().remove(governmentState.getCourtCases().size() - 1);
        }
        historyWriter.writeImmutableJson(
            String.format(Locale.ROOT, "courts/%s.json", courtCase.getCaseId()),
            Map.of(
                "case_id", courtCase.getCaseId(),
                "court_level", courtCase.getCourtLevel(),
                "plaintiff", courtCase.getPlaintiff(),
                "defendant", courtCase.getDefendant(),
                "legal_basis", courtCase.getLegalBasis(),
                "decision", courtCase.getDecision(),
                "constitutional_basis", courtCase.getConstitutionalBasis(),
                "affected_laws", courtCase.getAffectedLaws()
            )
        );
        return true;
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
        } else if ("services".equalsIgnoreCase(bill.getPolicyDomain())) {
            federalBudget.setHealth(federalBudget.getHealth() + 18);
            federalBudget.setEducation(federalBudget.getEducation() + 18);
        } else {
            federalBudget.setJustice(federalBudget.getJustice() + 14);
        }
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
        }
        if (count == 0) {
            return "infrastructure";
        }
        double avgService = service / count;
        double avgTrust = trust / count;
        double avgBurden = burden / count;
        if (avgService < 0.42d) {
            return "services";
        }
        if (avgBurden > 2.2d) {
            return "housing";
        }
        if (avgTrust < 0.45d) {
            return "rights-process";
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

    private void applyExecutiveOrderEffects(GovernmentState governmentState, GovernmentState.ExecutiveOrder order) {
        GovernmentState.GovernmentNode target = "state".equalsIgnoreCase(order.getScope())
            ? resolveStateNode(governmentState, order.getJurisdictionId())
            : governmentState.getFederal();
        GovernmentState.BudgetState budget = target.getBudget();
        if ("services".equalsIgnoreCase(order.getPolicyDomain())) {
            budget.setHealth(budget.getHealth() + 12);
            budget.setEducation(budget.getEducation() + 12);
        } else {
            budget.setJustice(budget.getJustice() + 8);
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

    private int projectVotes(String partyId, List<GovernmentState.PoliticalParty> parties, List<RoomBot> bots) {
        int members = parties.stream()
            .filter(party -> Objects.equals(party.getPartyId(), partyId))
            .mapToInt(GovernmentState.PoliticalParty::getMemberCount)
            .findFirst()
            .orElse(0);
        return Math.max(1, Math.min(Math.max(1, bots.size() / 3), members / 8 + 1));
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
