package com.emergentterrain.research;

import com.emergentterrain.government.GovernmentState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3 Iter 6 (read-only) — exposes the running simulation as queryable
 * "experimental variables" so a bot can study the world the way it would
 * study a math/physics topic.
 *
 * The phase doc envisioned both a read side ({@code WorldDataQueryService})
 * and a write side ({@code WorldInterventionService}). This file is the read
 * side only — bots can name a variable, get its current value, and pre-register
 * predictions about how it will move. Interventions (writes) are deferred
 * until after Phase 2.5's RoomService refactor, when the routing pathway
 * (bot proposes change → file as GovernmentBill → constitutional process)
 * is cleaner to wire.
 *
 * Stateless. Callers pass in the {@link GovernmentState} they already hold —
 * we don't take a repository because GovernmentState lives as JSON inside
 * RoomState and the orchestrators that need this view (UniversityService,
 * GovernmentSimulationService) already have the parsed object on hand.
 *
 * Variable names use a dotted convention so future REGION / DISTRICT scopes
 * can extend the namespace without colliding (e.g. {@code federal.public_trust}
 * vs {@code state.NY.public_trust} when state-level surfacing lands).
 */
@Service
public class WorldDataQueryService {

    /** All federal-level variables this service knows about. */
    public static final String NAME_FEDERAL_PUBLIC_TRUST   = "federal.public_trust";
    public static final String NAME_FEDERAL_SERVICE_ACCESS = "federal.service_access";
    public static final String NAME_BUDGET_REVENUE         = "federal.budget.revenue";
    public static final String NAME_BUDGET_EDUCATION       = "federal.budget.education";
    public static final String NAME_BUDGET_HEALTH          = "federal.budget.health";
    public static final String NAME_BUDGET_INFRASTRUCTURE  = "federal.budget.infrastructure";
    public static final String NAME_BUDGET_WELFARE         = "federal.budget.welfare";
    public static final String NAME_BUDGET_JUSTICE         = "federal.budget.justice";
    public static final String NAME_BUDGET_PAYROLL         = "federal.budget.payroll";
    public static final String NAME_STATE_COUNT            = "states.count";
    public static final String NAME_STATE_MEAN_TRUST       = "states.mean_public_trust";
    public static final String NAME_STATE_MEAN_SERVICE     = "states.mean_service_access";

    /**
     * All variables this service knows about. Order is stable so prompt context
     * built from this list is deterministic.
     */
    public List<WorldVariable> queryAll(GovernmentState govState, long observedAtTick) {
        if (govState == null) return List.of();
        List<WorldVariable> out = new ArrayList<>();

        GovernmentState.GovernmentNode federal = govState.getFederal();
        if (federal != null) {
            out.add(WorldVariable.global(NAME_FEDERAL_PUBLIC_TRUST,   federal.getPublicTrust(),   observedAtTick));
            out.add(WorldVariable.global(NAME_FEDERAL_SERVICE_ACCESS, federal.getServiceAccess(), observedAtTick));

            GovernmentState.BudgetState b = federal.getBudget();
            if (b != null) {
                out.add(WorldVariable.global(NAME_BUDGET_REVENUE,        b.getRevenue(),        observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_EDUCATION,      b.getEducation(),      observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_HEALTH,         b.getHealth(),         observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_INFRASTRUCTURE, b.getInfrastructure(), observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_WELFARE,        b.getWelfare(),        observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_JUSTICE,        b.getJustice(),        observedAtTick));
                out.add(WorldVariable.global(NAME_BUDGET_PAYROLL,        b.getPayroll(),        observedAtTick));
            }
        }

        List<GovernmentState.GovernmentNode> states = govState.getStates() != null
                ? govState.getStates() : List.of();
        out.add(WorldVariable.global(NAME_STATE_COUNT, states.size(), observedAtTick));
        if (!states.isEmpty()) {
            double meanTrust   = states.stream().mapToDouble(
                    GovernmentState.GovernmentNode::getPublicTrust).average().orElse(0.0);
            double meanService = states.stream().mapToDouble(
                    GovernmentState.GovernmentNode::getServiceAccess).average().orElse(0.0);
            out.add(WorldVariable.global(NAME_STATE_MEAN_TRUST,   meanTrust,   observedAtTick));
            out.add(WorldVariable.global(NAME_STATE_MEAN_SERVICE, meanService, observedAtTick));
        }
        return out;
    }

    /** Look up a single variable by name. Returns empty when the variable is unknown or unset. */
    public Optional<WorldVariable> query(GovernmentState govState, String name, long observedAtTick) {
        if (govState == null || name == null) return Optional.empty();
        return queryAll(govState, observedAtTick).stream()
                .filter(v -> name.equals(v.name()))
                .findFirst();
    }

    /** Names of all variables this service can produce. Useful for prompt prefaces. */
    public List<String> knownVariableNames() {
        return List.of(
                NAME_FEDERAL_PUBLIC_TRUST,   NAME_FEDERAL_SERVICE_ACCESS,
                NAME_BUDGET_REVENUE,         NAME_BUDGET_EDUCATION,
                NAME_BUDGET_HEALTH,          NAME_BUDGET_INFRASTRUCTURE,
                NAME_BUDGET_WELFARE,         NAME_BUDGET_JUSTICE,
                NAME_BUDGET_PAYROLL,         NAME_STATE_COUNT,
                NAME_STATE_MEAN_TRUST,       NAME_STATE_MEAN_SERVICE);
    }
}
