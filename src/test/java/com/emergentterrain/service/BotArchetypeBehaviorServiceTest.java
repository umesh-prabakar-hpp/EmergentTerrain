package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of {@link BotArchetypeBehaviorService} so the Phase 2.5
 * carve out of {@link RoomService} cannot regress. The expected values here
 * mirror the inline implementation that lived in RoomService before this
 * extraction — if any test fails, the carve has changed observable behaviour.
 */
class BotArchetypeBehaviorServiceTest {

    private static final Set<String> KNOWN_ARCHETYPES = Set.of(
            "STEWARD", "BUILDER", "FLOW_KEEPER", "PRESERVATIONIST",
            "PRAGMATIST", "CONTRARIAN", "NEIGHBOR_MIRROR");

    private final BotArchetypeBehaviorService svc = new BotArchetypeBehaviorService();

    @Test
    void archetypeAssignmentIsDeterministicForSameBotInputs() {
        UUID id = UUID.randomUUID();
        String first  = svc.archetypeFor(botWith(id, "researcher"));
        String second = svc.archetypeFor(botWith(id, "researcher"));

        assertThat(first).isEqualTo(second);
        assertThat(KNOWN_ARCHETYPES).contains(first);
    }

    @Test
    void archetypeDistributesAcrossAllSeven() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500 && seen.size() < KNOWN_ARCHETYPES.size(); i++) {
            seen.add(svc.archetypeFor(botWith(UUID.randomUUID(), "researcher")));
        }
        assertThat(seen).isEqualTo(KNOWN_ARCHETYPES);
    }

    @Test
    void targetsForEveryArchetypeHaveFiveBucketsThatAreValidShares() {
        for (String archetype : KNOWN_ARCHETYPES) {
            double[] t = svc.targetsFor(archetype);
            assertThat(t).as(archetype + " targets").hasSize(5);
            for (double v : t) {
                assertThat(v).as(archetype + " bucket").isBetween(0.0, 1.0);
            }
            // Targets are tile-share approximations; total may exceed 1.0 because
            // road overlaps the other shares. Sanity-check the upper bound only.
            double sum = t[0] + t[1] + t[2] + t[3];
            assertThat(sum).as(archetype + " non-road sum")
                    .isBetween(0.85, 1.05);
        }
    }

    @Test
    void unknownArchetypeFallsBackToPragmatistTargets() {
        assertThat(svc.targetsFor("UNKNOWN_ARCHETYPE"))
                .containsExactly(0.30, 0.15, 0.34, 0.17, 0.04);
    }

    @Test
    void prosperityScoreIsZeroWhenStatsMatchTargets() {
        double[] t = svc.targetsFor("STEWARD");
        LocalStats matching = new LocalStats(100, t[0], t[1], t[2], t[3], t[4]);

        assertThat(svc.prosperityScore(matching, "STEWARD"))
                .isEqualTo(0.0, within(1e-9));
    }

    @Test
    void prosperityScoreIsL1Deviation() {
        // STEWARD targets: forest=0.36, water=0.16, clear=0.32, industry=0.12, road=0.04.
        // Stats off by +0.10 on each of the five buckets → L1 distance = 0.50.
        double[] t = svc.targetsFor("STEWARD");
        LocalStats off = new LocalStats(100,
                t[0] + 0.10, t[1] + 0.10, t[2] + 0.10, t[3] + 0.10, t[4] + 0.10);

        assertThat(svc.prosperityScore(off, "STEWARD"))
                .isEqualTo(0.50, within(1e-9));
    }

    @Test
    void prefersTypeMatchesArchetypeRules() {
        assertThat(svc.prefersType("STEWARD", 1)).isTrue();           // forest
        assertThat(svc.prefersType("STEWARD", 2)).isTrue();           // water
        assertThat(svc.prefersType("STEWARD", 3)).isFalse();          // industry
        assertThat(svc.prefersType("BUILDER", 3)).isTrue();
        assertThat(svc.prefersType("BUILDER", 0)).isTrue();
        assertThat(svc.prefersType("BUILDER", 1)).isFalse();
        assertThat(svc.prefersType("FLOW_KEEPER", 2)).isTrue();
        assertThat(svc.prefersType("FLOW_KEEPER", 3)).isFalse();
        assertThat(svc.prefersType("PRESERVATIONIST", 1)).isTrue();
        assertThat(svc.prefersType("PRESERVATIONIST", 0)).isFalse();
        // The three permissive archetypes accept everything.
        assertThat(svc.prefersType("PRAGMATIST", 0)).isTrue();
        assertThat(svc.prefersType("CONTRARIAN", 3)).isTrue();
        assertThat(svc.prefersType("NEIGHBOR_MIRROR", 1)).isTrue();
    }

    @Test
    void dislikesTypeFiresOnArchetypeSpecificContext() {
        // STEWARD dislikes industry when forest or water are scarce.
        LocalStats forestStarved = new LocalStats(100, 0.20, 0.30, 0.30, 0.15, 0.05);
        assertThat(svc.dislikesType("STEWARD", 3, forestStarved)).isTrue();

        LocalStats waterStarved = new LocalStats(100, 0.40, 0.05, 0.30, 0.20, 0.05);
        assertThat(svc.dislikesType("STEWARD", 3, waterStarved)).isTrue();

        LocalStats balanced = new LocalStats(100, 0.40, 0.20, 0.30, 0.05, 0.05);
        assertThat(svc.dislikesType("STEWARD", 3, balanced)).isFalse();

        // BUILDER dislikes forest/water when industry has not yet established.
        LocalStats lowIndustry = new LocalStats(100, 0.30, 0.20, 0.34, 0.10, 0.06);
        assertThat(svc.dislikesType("BUILDER", 1, lowIndustry)).isTrue();
        assertThat(svc.dislikesType("BUILDER", 2, lowIndustry)).isTrue();
        assertThat(svc.dislikesType("BUILDER", 3, lowIndustry)).isFalse();

        // PRAGMATIST/CONTRARIAN/NEIGHBOR_MIRROR never veto on this signal.
        for (String permissive : new String[] {"PRAGMATIST", "CONTRARIAN", "NEIGHBOR_MIRROR"}) {
            for (int type = 0; type < 4; type++) {
                assertThat(svc.dislikesType(permissive, type, lowIndustry))
                        .as("%s should not veto type %d", permissive, type)
                        .isFalse();
            }
        }
    }

    private static RoomBot botWith(UUID id, String type) {
        RoomBot bot = mock(RoomBot.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getType()).thenReturn(type);
        return bot;
    }
}
