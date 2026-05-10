package com.emergentterrain.research;

import com.emergentterrain.government.BotCivicState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Phase 1 Iter 2 ResearchIdentity view: it should reflect what
 * lives on BotGenome / BotLifecycleState / BotCivicState without persisting
 * any new state itself, and the skepticism multiplier should map {@code [0,1]}
 * to a peer-tolerance multiplier of {@code [1.0, 0.5]}.
 */
class ResearchIdentityTest {

    @Test
    void buildsFromGenomeAndLifecycleWithDefaults() {
        BotLifecycleState ls = stateWith(0.5, 0.7);

        ResearchIdentity id = ResearchIdentity.from(ls, null, List.of());

        assertThat(id.favoredMethod()).isEqualTo(ls.getGenome().getMethodology());
        assertThat(id.riskTolerance()).isEqualTo(ls.getGenome().getExplorationRate());
        assertThat(id.skepticismLevel()).isEqualTo(0.5);
        assertThat(id.reputation()).isEqualTo(0.7);
        assertThat(id.topicAffinity()).hasSize(BotGenome.TOPIC_COUNT);
        assertThat(id.ideologyVector()).containsExactly(0.5, 0.5, 0.5, 0.5);
        assertThat(id.reputationHistory()).isEmpty();
    }

    @Test
    void readsIdeologyFromCivicStateWhenSupplied() {
        BotLifecycleState ls = stateWith(0.5, 0.5);
        BotCivicState civic = new BotCivicState();
        civic.setIdeologyLiberty(0.8);
        civic.setIdeologyOrder(0.3);
        civic.setIdeologyWelfare(0.6);
        civic.setIdeologyGrowth(0.4);

        ResearchIdentity id = ResearchIdentity.from(ls, civic, List.of());

        assertThat(id.ideologyVector()).containsExactly(0.8, 0.3, 0.6, 0.4);
    }

    @Test
    void skepticismOfZeroLeavesPeerToleranceUnchanged() {
        BotLifecycleState ls = stateWith(0.0, 0.5);
        ResearchIdentity id = ResearchIdentity.from(ls, null, List.of());

        assertThat(id.effectivePeerToleranceMultiplier()).isEqualTo(1.0);
    }

    @Test
    void skepticismOfOneHalvesPeerTolerance() {
        BotLifecycleState ls = stateWith(1.0, 0.5);
        ResearchIdentity id = ResearchIdentity.from(ls, null, List.of());

        assertThat(id.effectivePeerToleranceMultiplier()).isEqualTo(0.5);
    }

    @Test
    void skepticismIsClampedToValidRange() {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setSkepticismLevel(2.5);   // setter clamps
        assertThat(ls.getSkepticismLevel()).isEqualTo(1.0);

        ls.setSkepticismLevel(-0.5);
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.0);
    }

    @Test
    void copyOfReputationHistoryIsDefensive() {
        BotLifecycleState ls = stateWith(0.5, 0.5);
        java.util.ArrayList<ReputationHistory> mutable = new java.util.ArrayList<>();
        mutable.add(new ReputationHistory(
                UUID.randomUUID(), UUID.randomUUID(), 0L,
                0.2, 0, 0,
                BotLifecycleState.Phase.JUVENILE,
                BotLifecycleState.ResearchStatus.UNQUALIFIED));

        ResearchIdentity id = ResearchIdentity.from(ls, null, mutable);
        mutable.clear();

        assertThat(id.reputationHistory()).hasSize(1);
    }

    private static BotLifecycleState stateWith(double skepticism, double reputation) {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setGenome(BotGenome.initialise(UUID.randomUUID(), 0));
        ls.setSkepticismLevel(skepticism);
        ls.setReputation(reputation);
        return ls;
    }
}
