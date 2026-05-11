package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomProposal;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Phase 2 (bot-vote machinery) surface of
 * {@link RoomProposalService} so the second-wave Phase 2.5 carve out of
 * {@link RoomService} cannot regress. Companion to
 * {@link RoomProposalServiceTest} which covers the Phase 1 codec / tile-math
 * helpers — kept in a sibling class because the Phase 2 surface needs the
 * full DI graph wired (repositories + extracted services).
 *
 * The expected values mirror the inline implementation that lived in
 * RoomService before this extraction.
 */
class RoomProposalServicePhase2Test {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RoomProposalRepository proposalRepo = mock(RoomProposalRepository.class);
    private final RoomBotRepository botRepo = mock(RoomBotRepository.class);
    private final BotArchetypeBehaviorService archetypes = new BotArchetypeBehaviorService();
    private final OccupancyService occupancy = new OccupancyService(null, null, null);
    private final TileValidatorService validator = new TileValidatorService(occupancy);
    private final BotMovementService movement = new BotMovementService(
            // BotMovementService uses 4 @Value tunables (planLength, reverseCooldownTicks,
            // reversePenalty, headingBias). Production defaults from the @Value annotations.
            3, 2, 0.6, 0.18);

    private final RoomProposalService svc = new RoomProposalService(
            objectMapper, proposalRepo, botRepo,
            /* voteRepo */ null, /* participantRepo */ null,
            archetypes, validator, movement, occupancy,
            /* botRuleChecksEnabled */ false,
            /* botApproveBias */ 0.0,
            /* minVotesToResolve */ 3, /* minProposalLiveSeconds */ 3,
            /* approveOnTieAfterExpiry */ false, /* noParticipationLockSeconds */ 300,
            /* neighborRadius */ 8, /* quorumCap */ 0);

    // ---------- localStats ----------

    @Test
    void localStatsCountsTilesInViewportAndComputesShares() {
        // 4×4 grid: row 0 all forest, row 1 all water, row 2 all clear,
        // row 3 all industry. Anchor in the middle → world < viewport so
        // viewport collapses to the whole world.
        int[][] tiles = new int[4][4];
        for (int x = 0; x < 4; x++) {
            tiles[0][x] = 1;   // forest
            tiles[1][x] = 2;   // water
            tiles[2][x] = 0;   // clear
            tiles[3][x] = 3;   // industry
        }
        boolean[][] highways = new boolean[0][0];

        LocalStats stats = svc.localStats(tiles, highways, 2, 2);

        assertThat(stats.total()).isEqualTo(16);
        assertThat(stats.forest()).isEqualTo(0.25);
        assertThat(stats.water()).isEqualTo(0.25);
        assertThat(stats.clear()).isEqualTo(0.25);
        assertThat(stats.industry()).isEqualTo(0.25);
        assertThat(stats.road()).isEqualTo(0.0);
    }

    @Test
    void localStatsCountsHighwayCellsToward_road() {
        // 2×2 all-clear with one highway tile.
        int[][] tiles = { {0, 0}, {0, 0} };
        boolean[][] highways = { {false, true}, {false, false} };

        LocalStats stats = svc.localStats(tiles, highways, 0, 0);

        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.road()).isEqualTo(0.25);
    }

    // ---------- recentProposalDeltaTypes ----------

    @Test
    void recentProposalDeltaTypesPreservesOrderAndPreservesNulls() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                proposal("{\"type\":1}"),
                proposal(null),                  // null delta → null type
                proposal("{\"type\":\"water\"}")
        ));

        List<Integer> types = svc.recentProposalDeltaTypes(room);

        assertThat(types).containsExactly(1, null, 2);
    }

    // ---------- isTrendMajority ----------

    @Test
    void isTrendMajorityFalseOnEmptyHistory() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of());

        assertThat(svc.isTrendMajority(room, 1)).isFalse();
    }

    @Test
    void isTrendMajorityTrueWhenTwoOfFirstThreeTypedProposalsMatch() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                proposal("{\"type\":1}"),
                proposal("{\"type\":1}"),
                proposal("{\"type\":2}"),
                proposal("{\"type\":1}")  // ignored — 3-sample cap reached
        ));

        assertThat(svc.isTrendMajority(room, 1)).isTrue();
        assertThat(svc.isTrendMajority(room, 2)).isFalse();
    }

    @Test
    void isTrendMajoritySkipsProposalsWithNullDeltaType() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                proposal(null),
                proposal("{\"type\":1}"),
                proposal(null),
                proposal("{\"type\":1}")
        ));
        assertThat(svc.isTrendMajority(room, 1)).isTrue();
    }

    // ---------- lastHumanProposalType ----------

    @Test
    void lastHumanProposalTypeSkipsBotProposalsAndReturnsFirstHumanType() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                botProposal("{\"type\":3}"),
                botProposal("{\"type\":4}"),
                proposal("{\"type\":2}"),
                proposal("{\"type\":1}")
        ));

        assertThat(svc.lastHumanProposalType(room)).isEqualTo(2);
    }

    @Test
    void lastHumanProposalTypeReturnsNullWhenAllRecentAreBotGenerated() {
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                botProposal("{\"type\":1}"),
                botProposal("{\"type\":2}")
        ));

        assertThat(svc.lastHumanProposalType(room)).isNull();
    }

    // ---------- botVoteDecision ----------

    @Test
    void botVoteDecisionReturnsAbstainWhenNoTileChange() {
        Room room = new Room();
        RoomBot bot = bot(UUID.randomUUID());
        int[][] tiles = { {0, 0}, {0, 0} };
        boolean[][] highways = new boolean[0][0];

        BotVote vote = svc.botVoteDecision(room, bot, profileOf(0.5, 0.0, 0.0, 0.5, 0.5),
                tiles, tiles, highways, false, null);

        assertThat(vote).isEqualTo(BotVote.ABSTAIN);
    }

    @Test
    void botVoteDecisionMirrorsLastHumanWhenArchetypeIsNeighborMirror() {
        // Force NEIGHBOR_MIRROR archetype by picking a bot whose hash falls on
        // index 6 in the archetype list. ARCHETYPES = STEWARD(0), BUILDER(1),
        // FLOW_KEEPER(2), PRESERVATIONIST(3), PRAGMATIST(4), CONTRARIAN(5),
        // NEIGHBOR_MIRROR(6). Search for a bot id that hashes to 6.
        RoomBot bot = botWithArchetype("NEIGHBOR_MIRROR");
        Room room = new Room();
        // Pretend the latest human proposal placed a forest tile (type=1).
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                proposal("{\"type\":1}")
        ));

        int[][] base = { {0, 0}, {0, 0} };
        int[][] toForest = { {1, 0}, {0, 0} };           // matches last human → APPROVE
        int[][] toWater = { {2, 0}, {0, 0} };            // differs → REJECT

        boolean[][] highways = new boolean[0][0];
        Map<String, Double> profile = profileOf(0.5, 0.0, 0.0, 0.5, 0.5);

        assertThat(svc.botVoteDecision(room, bot, profile, base, toForest, highways, false, null))
                .isEqualTo(BotVote.APPROVE);
        assertThat(svc.botVoteDecision(room, bot, profile, base, toWater, highways, false, null))
                .isEqualTo(BotVote.REJECT);
    }

    @Test
    void botVoteDecisionContrarianRejectsWhenHerdMatches() {
        RoomBot bot = botWithArchetype("CONTRARIAN");
        Room room = new Room();
        // Build a herd: two of the three recent typed proposals match type=2.
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of(
                proposal("{\"type\":2}"),
                proposal("{\"type\":2}"),
                proposal("{\"type\":1}")
        ));

        int[][] base = { {0, 0}, {0, 0} };
        int[][] toWater = { {2, 0}, {0, 0} };
        boolean[][] highways = new boolean[0][0];

        BotVote vote = svc.botVoteDecision(room, bot, profileOf(0.5, 0.0, 0.0, 0.5, 0.5),
                base, toWater, highways, false, null);

        assertThat(vote).isEqualTo(BotVote.REJECT);
    }

    @Test
    void botVoteDecisionInvokesDriftCallbackOnFallThroughPath() {
        // The drift callback fires only on the preference-score fall-through
        // path, after all archetype-specific short-circuits and after the
        // PRAGMATIST improvement branch. STEWARD + a tile-change-to-clear
        // hits this path: prefers(STEWARD, 0) is false (so no early APPROVE),
        // dislikes(STEWARD, 0, before) is false (the dislike rule only
        // applies to type=3), and the archetype isn't PRAGMATIST, so the
        // method must walk all the way to tilePreferenceScore where the
        // callback fires unconditionally after the random flips.
        RoomBot bot = botWithArchetype("STEWARD");
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of());

        // base = all forest; proposed = one tile cleared. STEWARD has
        // industry rule (type==3 && forest<0.25): irrelevant for type=0.
        int[][] base = { {1, 1}, {1, 1} };
        int[][] cleared = { {0, 1}, {1, 1} };
        boolean[][] highways = new boolean[0][0];

        AtomicInteger callbackCount = new AtomicInteger();
        BiConsumer<RoomBot, LocalStats> driftCallback = (b, s) -> callbackCount.incrementAndGet();

        // proposalBotGenerated=true keeps the community-rules gate off (since
        // botRuleChecksEnabled=false on this svc), so the only short-circuits
        // before the fall-through are the abstain-probability flip
        // (|improvement|<0.01 ∧ random<0.08) — but our improvement is large.
        for (int i = 0; i < 50; i++) {
            svc.botVoteDecision(room, bot, profileOf(0.5, 0.0, 0.0, 0.5, 0.5),
                    base, cleared, highways, true, driftCallback);
        }
        assertThat(callbackCount.get()).isEqualTo(50);   // every call reaches drift
    }

    @Test
    void botVoteDecisionDriftCallbackNullDoesNotCrash() {
        // Same fall-through path as above; null callback must be tolerated.
        RoomBot bot = botWithArchetype("STEWARD");
        Room room = new Room();
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(room)).thenReturn(List.of());

        int[][] base = { {1, 1}, {1, 1} };
        int[][] cleared = { {0, 1}, {1, 1} };

        for (int i = 0; i < 50; i++) {
            BotVote vote = svc.botVoteDecision(room, bot, profileOf(0.5, 0.0, 0.0, 0.5, 0.5),
                    base, cleared, new boolean[0][0], true, null);
            assertThat(vote).isIn(BotVote.APPROVE, BotVote.REJECT, BotVote.ABSTAIN);
        }
    }

    // ---------- applyBotVotes ----------

    @Test
    void applyBotVotesEarlyReturnsOnEmptyNeighborList() {
        Room room = new Room();
        RoomProposal proposal = new RoomProposal();
        proposal.setNeighborIdsJson("[]");

        svc.applyBotVotes(room, proposal, new int[][] { {0} }, new int[][] { {1} }, new boolean[0][0],
                b -> { throw new IllegalStateException("profile reader must not be called"); },
                (b, s) -> { throw new IllegalStateException("drift must not be called"); });

        assertThat(proposal.getApprovals()).isEqualTo(0);
        assertThat(proposal.getRejections()).isEqualTo(0);
        assertThat(proposal.getBotVoteSummaryJson()).isNull();
    }

    @Test
    void applyBotVotesOnlyVotesForBotsInNeighborSet() {
        Room room = new Room();
        UUID neighborId = UUID.randomUUID();
        RoomBot neighbor = bot(neighborId);
        RoomBot outsider = bot(UUID.randomUUID());

        when(botRepo.findByRoom(room)).thenReturn(List.of(neighbor, outsider));
        when(proposalRepo.findTop10ByRoomOrderByCreatedAtDesc(any())).thenReturn(List.of());

        RoomProposal proposal = new RoomProposal();
        proposal.setNeighborIdsJson("[\"B:" + neighborId + "\"]");

        @SuppressWarnings("unchecked")
        Function<RoomBot, Map<String, Double>> profileReader = mock(Function.class);
        when(profileReader.apply(any())).thenReturn(profileOf(0.5, 0.0, 0.0, 0.5, 0.5));

        int[][] base = { {0, 0}, {0, 0} };
        int[][] toForest = { {1, 0}, {0, 0} };

        svc.applyBotVotes(room, proposal, base, toForest, new boolean[0][0], profileReader, null);

        // The outsider bot must never have its profile read — applyBotVotes
        // filters by neighbor membership before any per-bot work.
        verify(profileReader, never()).apply(outsider);
        // The vote either landed as approve, reject, or abstain — at least one
        // counter or the summary should reflect the neighbor's participation,
        // unless the random gates produced an abstain (probabilistic).
        int totalVotes = proposal.getApprovals() + proposal.getRejections();
        assertThat(totalVotes).isLessThanOrEqualTo(1);
    }

    // ---------- helpers ----------

    private static Map<String, Double> profileOf(double risk, double abstainProb, double conflictProb,
                                                  double consensus, double mobility) {
        Map<String, Double> p = new HashMap<>();
        p.put("risk", risk);
        p.put("abstain", abstainProb);
        p.put("conflict", conflictProb);
        p.put("consensus", consensus);
        p.put("mobility", mobility);
        return p;
    }

    private static RoomProposal proposal(String deltaJson) {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson(deltaJson);
        p.setBotGenerated(false);
        return p;
    }

    private static RoomProposal botProposal(String deltaJson) {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson(deltaJson);
        p.setBotGenerated(true);
        return p;
    }

    private static RoomBot bot(UUID id) {
        RoomBot b = mock(RoomBot.class);
        when(b.getId()).thenReturn(id);
        when(b.getType()).thenReturn("researcher");
        return b;
    }

    /**
     * Finds a {@code RoomBot} whose stable archetype hash lands on the
     * desired archetype name. BotArchetypeBehaviorService keys on
     * {@code Objects.hash(bot.getId(), bot.getType())} mod 7 — we search up
     * to 1000 random ids until we hit the target.
     */
    private RoomBot botWithArchetype(String archetypeName) {
        for (int i = 0; i < 1000; i++) {
            RoomBot b = bot(UUID.randomUUID());
            if (archetypeName.equals(archetypes.archetypeFor(b))) {
                return b;
            }
        }
        throw new IllegalStateException("no bot id mapped to " + archetypeName + " in 1000 tries");
    }
}
