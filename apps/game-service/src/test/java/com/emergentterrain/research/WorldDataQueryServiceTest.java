package com.emergentterrain.research;

import com.emergentterrain.government.GovernmentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Phase 3 Iter 6 (read-only) WorldDataQueryService. The service
 * is stateless and pure — we build GovernmentState fixtures and assert that
 * the right variables come out with the right scope and value.
 */
class WorldDataQueryServiceTest {

    private WorldDataQueryService service;

    @BeforeEach
    void setUp() { service = new WorldDataQueryService(); }

    @Test
    void queryAllReturnsEmptyWhenStateIsNull() {
        assertThat(service.queryAll(null, 100L)).isEmpty();
    }

    @Test
    void queryAllExposesFederalTrustAndService() {
        GovernmentState s = stateWithFederal(0.7, 0.6, null);

        List<WorldVariable> vars = service.queryAll(s, 42L);

        assertThat(vars).extracting(WorldVariable::name).contains(
                WorldDataQueryService.NAME_FEDERAL_PUBLIC_TRUST,
                WorldDataQueryService.NAME_FEDERAL_SERVICE_ACCESS);
        assertThat(named(vars, WorldDataQueryService.NAME_FEDERAL_PUBLIC_TRUST).value())
                .isEqualTo(0.7);
        assertThat(named(vars, WorldDataQueryService.NAME_FEDERAL_SERVICE_ACCESS).value())
                .isEqualTo(0.6);
        assertThat(vars.get(0).observedAtTick()).isEqualTo(42L);
        assertThat(vars.get(0).scope().kind()).isEqualTo(WorldVariable.Scope.Kind.GLOBAL);
    }

    @Test
    void queryAllSurfacesBudgetCategories() {
        GovernmentState.BudgetState b = new GovernmentState.BudgetState();
        b.setEducation(50_000L);
        b.setHealth(30_000L);
        b.setInfrastructure(20_000L);
        b.setWelfare(10_000L);
        GovernmentState s = stateWithFederal(0.5, 0.5, b);

        List<WorldVariable> vars = service.queryAll(s, 1L);

        assertThat(named(vars, WorldDataQueryService.NAME_BUDGET_EDUCATION).value()).isEqualTo(50_000.0);
        assertThat(named(vars, WorldDataQueryService.NAME_BUDGET_HEALTH).value()).isEqualTo(30_000.0);
        assertThat(named(vars, WorldDataQueryService.NAME_BUDGET_INFRASTRUCTURE).value()).isEqualTo(20_000.0);
        assertThat(named(vars, WorldDataQueryService.NAME_BUDGET_WELFARE).value()).isEqualTo(10_000.0);
    }

    @Test
    void stateMeanTrustAveragesAcrossStateNodes() {
        GovernmentState s = stateWithFederal(0.5, 0.5, null);
        s.setStates(List.of(
                stateNode(0.4, 0.6),
                stateNode(0.6, 0.4),
                stateNode(0.5, 0.5)));

        List<WorldVariable> vars = service.queryAll(s, 1L);

        assertThat(named(vars, WorldDataQueryService.NAME_STATE_COUNT).value()).isEqualTo(3.0);
        assertThat(named(vars, WorldDataQueryService.NAME_STATE_MEAN_TRUST).value()).isCloseTo(0.5, within(1e-9));
        assertThat(named(vars, WorldDataQueryService.NAME_STATE_MEAN_SERVICE).value()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void emptyStatesListProducesZeroCountWithNoMeans() {
        GovernmentState s = stateWithFederal(0.5, 0.5, null);
        s.setStates(new ArrayList<>());

        List<WorldVariable> vars = service.queryAll(s, 1L);

        assertThat(named(vars, WorldDataQueryService.NAME_STATE_COUNT).value()).isZero();
        assertThat(vars).extracting(WorldVariable::name)
                .doesNotContain(WorldDataQueryService.NAME_STATE_MEAN_TRUST);
    }

    @Test
    void queryFindsVariableByName() {
        GovernmentState s = stateWithFederal(0.7, 0.6, null);
        var v = service.query(s, WorldDataQueryService.NAME_FEDERAL_PUBLIC_TRUST, 1L);
        assertThat(v).isPresent();
        assertThat(v.get().value()).isEqualTo(0.7);
    }

    @Test
    void queryReturnsEmptyForUnknownName() {
        GovernmentState s = stateWithFederal(0.7, 0.6, null);
        assertThat(service.query(s, "totally.fake", 1L)).isEmpty();
        assertThat(service.query(s, null, 1L)).isEmpty();
        assertThat(service.query(null, "x", 1L)).isEmpty();
    }

    @Test
    void knownVariableNamesIsStableAndNonEmpty() {
        List<String> names = service.knownVariableNames();
        assertThat(names).isNotEmpty();
        // Stable order: same call twice returns the same sequence.
        assertThat(service.knownVariableNames()).containsExactlyElementsOf(names);
    }

    // ---- helpers ----

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static WorldVariable named(List<WorldVariable> vars, String name) {
        return vars.stream().filter(v -> name.equals(v.name())).findFirst().orElseThrow();
    }

    private static GovernmentState stateWithFederal(double trust, double service,
                                                    GovernmentState.BudgetState budget) {
        GovernmentState s = new GovernmentState();
        GovernmentState.GovernmentNode federal = stateNode(trust, service);
        federal.setBudget(budget);
        s.setFederal(federal);
        return s;
    }

    private static GovernmentState.GovernmentNode stateNode(double trust, double service) {
        GovernmentState.GovernmentNode n = new GovernmentState.GovernmentNode();
        n.setPublicTrust(trust);
        n.setServiceAccess(service);
        return n;
    }
}
