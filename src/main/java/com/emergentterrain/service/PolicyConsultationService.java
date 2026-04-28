package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Fires async policy consultations each cooldown window and applies machine-readable
 * recommendations to drive steady long-term progress across government, economy,
 * civic health, terrain, and research.
 *
 * Never blocks the maintenance thread. Result lands in pendingResults and is applied
 * on the next tick that calls drainAndApply().
 *
 * Domain hints use probabilistic acceptance (hintAcceptanceRate) so the game's own
 * bot-stats logic still influences outcomes — AI acts as advisor, not dictator.
 *
 * Extensible: research_priority and civic_goal actions are stored in GovernmentState
 * and consumed by future subsystems (university R&D engine, civic feedback loop).
 */
@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class PolicyConsultationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyConsultationService.class);

    private static final double BUDGET_CLAMP_MIN = 0;
    private static final double BUDGET_CLAMP_MAX = 200_000;

    // ---- Action schema ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PolicyAction(
            String type,    // budget_shift | suggest_domain | prefer_tile | research_priority | civic_goal
            String field,   // budget_shift: infrastructure|education|health|welfare
            Integer delta,  // budget_shift: -20..20
            String tile,    // prefer_tile: forest|water|clear|industry
            Double weight,  // prefer_tile: 0.05..0.20
            String domain,  // suggest_domain: infrastructure|housing|labor|services|rights-process
            String area,    // research_priority: medicine|engineering|materials|agriculture|social_sciences
            String target   // civic_goal: expand_services|raise_trust|reduce_sanctions|boost_participation|stabilize_economy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PolicyResponse(List<PolicyAction> actions, String outlook, String summary) {}

    private record ConsultationRecord(
            Instant at, String summary, String outlook,
            double trustAtConsultation, double serviceAtConsultation) {}

    // Wraps the response with the tick at which the snapshot was taken for staleness checks
    private record PendingResult(PolicyResponse response, long snapshotTick) {}

    // ---- State ----

    // Results are valid if current tick is within this many ticks of the snapshot tick.
    // At 2500ms/tick, 30 ticks = 75 seconds — headroom for slow Ollama responses (~15-25s).
    private static final long MAX_STALE_TICKS = 30;

    public final double hintAcceptanceRate;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    // One consultation per room at a time — next fires as soon as previous result is drained.
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConsultationRecord> lastConsultation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingResult> pendingResults = new ConcurrentHashMap<>();

    public PolicyConsultationService(LlmService llmService, ObjectMapper objectMapper,
                                     @Value("${app.ai.hintAcceptanceRate:0.6}") double hintAcceptanceRate) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.hintAcceptanceRate = Math.max(0.0, Math.min(1.0, hintAcceptanceRate));
    }

    // ---- Public API ----

    /**
     * Non-blocking. Skipped if a consultation is already in flight for this room.
     * Fires on a virtual thread; in-flight flag clears when the thread finishes so the
     * next call to triggerAsync (on the next government change) starts immediately.
     */
    public void triggerAsync(UUID roomId, GovernmentState snapshot) {
        if (Boolean.TRUE.equals(inFlight.get(roomId))) {
            return;
        }
        inFlight.put(roomId, Boolean.TRUE);

        ConsultationRecord prev = lastConsultation.get(roomId);
        double trust      = snapshot.getFederal().getPublicTrust();
        double service    = snapshot.getFederal().getServiceAccess();
        long snapshotTick = snapshot.getTick();
        String prompt     = buildConsultationPrompt(snapshot, trust, service, prev);

        executor.execute(() -> {
            try {
                String raw = llmService.consultPolicySync(prompt);
                if (raw == null || raw.isBlank()) return;
                String json = extractJson(raw);
                if (json == null) {
                    log.warn("Policy consultation: no JSON in response room={}", roomId);
                    return;
                }
                try {
                    PolicyResponse response = objectMapper.readValue(json, PolicyResponse.class);
                    pendingResults.put(roomId, new PendingResult(response, snapshotTick));
                    lastConsultation.put(roomId, new ConsultationRecord(
                            Instant.now(), response.summary(), response.outlook(),
                            trust, service));
                    log.info("Policy consultation ready room={} snapshotTick={} outlook={} summary={} actions={}",
                            roomId, snapshotTick, response.outlook(), response.summary(),
                            response.actions() == null ? 0 : response.actions().size());
                } catch (JsonProcessingException e) {
                    log.warn("Policy consultation JSON parse failed room={}: {}", roomId, e.getMessage());
                }
            } finally {
                inFlight.remove(roomId); // always clear — next triggerAsync call may now proceed
            }
        });
    }

    /**
     * Non-blocking drain. If a result has arrived since the last trigger, applies it and returns it.
     * Call once per maintenance tick.
     */
    public Optional<PolicyResponse> drainAndApply(UUID roomId, GovernmentState state) {
        PendingResult pending = pendingResults.remove(roomId);
        if (pending == null) return Optional.empty();
        long staleTicks = state.getTick() - pending.snapshotTick();
        if (staleTicks > MAX_STALE_TICKS) {
            log.info("Policy result discarded as stale room={} staleTicks={} max={}",
                    roomId, staleTicks, MAX_STALE_TICKS);
            return Optional.empty();
        }
        applyRecommendations(state, pending.response());
        return Optional.of(pending.response());
    }

    public void applyRecommendations(GovernmentState state, PolicyResponse response) {
        if (response == null || response.actions() == null) return;
        List<String> newHints   = new ArrayList<>();
        List<String> newResearch = new ArrayList<>();
        List<String> newGoals   = new ArrayList<>();

        for (PolicyAction action : response.actions()) {
            if (action.type() == null) continue;
            switch (action.type()) {
                case "budget_shift"      -> applyBudgetShift(state, action);
                case "suggest_domain"    -> {
                    if (action.domain() != null && !action.domain().isBlank())
                        newHints.add(action.domain().toLowerCase());
                }
                case "prefer_tile"       -> applyTileNudge(state, action);
                case "research_priority" -> {
                    if (action.area() != null && !action.area().isBlank())
                        newResearch.add(action.area().toLowerCase());
                }
                case "civic_goal"        -> {
                    if (action.target() != null && !action.target().isBlank())
                        newGoals.add(action.target().toLowerCase());
                }
                default -> log.debug("Policy consultation: unknown action type={}", action.type());
            }
        }
        if (!newHints.isEmpty())    { state.setPolicyHints(newHints);     log.info("Policy hints: {}",    newHints); }
        if (!newResearch.isEmpty()) { state.setResearchHints(newResearch); log.info("Research hints: {}", newResearch); }
        if (!newGoals.isEmpty())    { state.setCivicGoals(newGoals);       log.info("Civic goals: {}",    newGoals); }
    }

    // ---- Apply helpers ----

    private void applyBudgetShift(GovernmentState state, PolicyAction action) {
        if (action.field() == null || action.delta() == null) return;
        GovernmentState.BudgetState budget = state.getFederal().getBudget();
        if (budget == null) return;
        int delta = Math.max(-20, Math.min(20, action.delta()));
        switch (action.field().toLowerCase()) {
            case "infrastructure" -> budget.setInfrastructure(clampBudget(budget.getInfrastructure() + delta));
            case "education"      -> budget.setEducation(clampBudget(budget.getEducation() + delta));
            case "health"         -> budget.setHealth(clampBudget(budget.getHealth() + delta));
            case "welfare"        -> budget.setWelfare(clampBudget(budget.getWelfare() + delta));
            default -> log.debug("Policy consultation: unknown budget field={}", action.field());
        }
        log.info("Budget shift applied field={} delta={}", action.field(), delta);
    }

    private void applyTileNudge(GovernmentState state, PolicyAction action) {
        if (action.tile() == null || action.weight() == null) return;
        double weight = Math.max(0.05, Math.min(0.20, action.weight()));
        state.getTileNudges().put(action.tile().toLowerCase(), weight);
        log.info("Tile nudge applied tile={} weight={}", action.tile(), weight);
    }

    private long clampBudget(long value) {
        return (long) Math.max(BUDGET_CLAMP_MIN, Math.min(BUDGET_CLAMP_MAX, value));
    }

    // ---- Prompt ----

    private String buildConsultationPrompt(GovernmentState state, double trust, double service,
                                           ConsultationRecord prev) {
        GovernmentState.BudgetState budget = state.getFederal() != null ? state.getFederal().getBudget() : null;

        // Trend indicators derived from previous consultation
        String trustTrend   = trend(prev, trust,   prev != null ? prev.trustAtConsultation() : trust);
        String serviceTrend = trend(prev, service, prev != null ? prev.serviceAtConsultation() : service);

        String prevLine = prev == null ? "" :
                "\nPrev advice: \"%s\" | trust%s%.2f service%s%.2f"
                        .formatted(prev.summary(),
                                   trust   >= prev.trustAtConsultation()   ? "+" : "",
                                   trust   - prev.trustAtConsultation(),
                                   service >= prev.serviceAtConsultation() ? "+" : "",
                                   service - prev.serviceAtConsultation());

        String budgetLine = budget == null ? "" :
                "\nBudget edu=%d health=%d infra=%d welfare=%d"
                        .formatted(budget.getEducation(), budget.getHealth(),
                                   budget.getInfrastructure(), budget.getWelfare());

        long activeBills  = state.getBills().stream()
                .filter(b -> b.getLawStatus() == null || "ACTIVE".equalsIgnoreCase(b.getLawStatus())).count();
        long blockedBills = state.getBills().stream()
                .filter(b -> "BLOCKED".equalsIgnoreCase(b.getLawStatus())).count();

        return """
                Civic simulation long-term advisor. Goal: steady progress across all dimensions.
                Tick=%d trust=%s%.2f service=%s%.2f bots=%d petitioning=%d sanctioned=%d
                Bills: %d active %d blocked%s%s

                Actions available:
                budget_shift    field=education|health|infrastructure|welfare  delta=-20..20
                suggest_domain  domain=infrastructure|housing|labor|services|rights-process
                prefer_tile     tile=forest|water|clear|industry  weight=0.05..0.20
                research_priority  area=medicine|engineering|materials|agriculture|social_sciences
                civic_goal      target=expand_services|raise_trust|reduce_sanctions|boost_participation|stabilize_economy

                Choose 2-4 actions that move the weakest indicators toward balance. Respond ONLY with JSON:
                {"actions":[{"type":"budget_shift","field":"education","delta":10},{"type":"suggest_domain","domain":"services"},{"type":"research_priority","area":"medicine"},{"type":"civic_goal","target":"expand_services"}],"outlook":"stressed","summary":"services and trust critically low"}"""
                .formatted(
                        state.getTick(),
                        trustTrend,   trust,
                        serviceTrend, service,
                        state.getActiveBotCount(),
                        state.getPetitioningBotCount(),
                        state.getSanctionedBotCount(),
                        activeBills, blockedBills,
                        prevLine, budgetLine);
    }

    private String trend(ConsultationRecord prev, double current, double baseline) {
        if (prev == null) return "";
        return current >= baseline ? "↑" : "↓";
    }

    private String extractJson(String raw) {
        String s = raw.replaceAll("```[a-z]*\\s*", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return null;
    }
}
