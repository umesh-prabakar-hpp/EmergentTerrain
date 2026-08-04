package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.domain.RoomProposal;
import com.emergentterrain.domain.RoomProposalVote;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomParticipantRepository;
import com.emergentterrain.repo.RoomProposalRepository;
import com.emergentterrain.repo.RoomProposalVoteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Phase 3 (vote/evaluate decisions) surface of
 * {@link RoomProposalService} so the second-wave Phase 2.5 carve out of
 * {@link RoomService} cannot regress. Companion to
 * {@link RoomProposalServiceTest} (Phase 1 codec + tile-math) and
 * {@link RoomProposalServicePhase2Test} (bot-vote machinery).
 *
 * The {@code applyProposal} side stays in RoomService and is exercised via
 * an {@link BiConsumer} callback in these tests — we verify it's invoked /
 * not invoked at the right times rather than reimplementing the cache write.
 */
class RoomProposalServicePhase3Test {

    private static final int MIN_VOTES_TO_RESOLVE = 3;
    private static final int MIN_PROPOSAL_LIVE_SECONDS = 3;
    private static final int NO_PARTICIPATION_LOCK_SECONDS = 300;
    private static final int NEIGHBOR_RADIUS = 8;
    private static final int QUORUM_CAP_OFF = 0;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RoomProposalRepository proposalRepo = mock(RoomProposalRepository.class);
    private final RoomBotRepository botRepo = mock(RoomBotRepository.class);
    private final RoomProposalVoteRepository voteRepo = mock(RoomProposalVoteRepository.class);
    private final RoomParticipantRepository participantRepo = mock(RoomParticipantRepository.class);
    private final BotArchetypeBehaviorService archetypes = new BotArchetypeBehaviorService();
    private final OccupancyService occupancy = new OccupancyService(null, null, null);
    private final TileValidatorService validator = new TileValidatorService(occupancy);
    private final BotMovementService movement = new BotMovementService(3, 2, 0.6, 0.18);

    private RoomProposalService make(boolean approveOnTie, int quorumCap) {
        return new RoomProposalService(
                objectMapper, proposalRepo, botRepo, voteRepo, participantRepo,
                archetypes, validator, movement, occupancy,
                /* botRuleChecksEnabled */ false, /* botApproveBias */ 0.0,
                MIN_VOTES_TO_RESOLVE, MIN_PROPOSAL_LIVE_SECONDS,
                approveOnTie, NO_PARTICIPATION_LOCK_SECONDS,
                NEIGHBOR_RADIUS, quorumCap);
    }

    private final RoomProposalService svc = make(false, QUORUM_CAP_OFF);

    // ---------- capQuorum ----------

    @Test
    void capQuorumClampsToOneFloor() {
        assertThat(svc.capQuorum(0)).isEqualTo(1);
        assertThat(svc.capQuorum(-5)).isEqualTo(1);
        assertThat(svc.capQuorum(7)).isEqualTo(7);
    }

    @Test
    void capQuorumHonorsCap() {
        RoomProposalService capped = make(false, /* quorumCap */ 5);
        assertThat(capped.capQuorum(3)).isEqualTo(3);
        assertThat(capped.capQuorum(7)).isEqualTo(5);
    }

    // ---------- canVoteNow ----------

    @Test
    void canVoteNowFalseOnMissingCoordinates() {
        int[][] tiles = new int[10][10];

        RoomProposal noAnchor = new RoomProposal();
        RoomProposal anchored = anchoredAt(5, 5);

        RoomParticipant near = participantAt(5, 5);
        RoomParticipant noCoord = mock(RoomParticipant.class);
        when(noCoord.getX()).thenReturn(null);
        when(noCoord.getY()).thenReturn(null);

        assertThat(svc.canVoteNow(tiles, noAnchor, near)).isFalse();
        assertThat(svc.canVoteNow(tiles, anchored, noCoord)).isFalse();
    }

    @Test
    void canVoteNowTrueWhenParticipantInsideAnchorViewport() {
        // 60x60 world, anchor at (30, 30) → 48x36 viewport centred there.
        int[][] tiles = new int[60][60];
        RoomProposal proposal = anchoredAt(30, 30);

        assertThat(svc.canVoteNow(tiles, proposal, participantAt(30, 30))).isTrue();
        assertThat(svc.canVoteNow(tiles, proposal, participantAt(10, 30))).isTrue();   // still in viewport
        assertThat(svc.canVoteNow(tiles, proposal, participantAt(0, 0))).isFalse();    // off viewport
    }

    // ---------- evaluateProposal — quorum branch ----------

    @Test
    void evaluateProposalSkipsNonPendingProposals() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal applied = new RoomProposal();
        applied.setStatus("APPLIED");
        applied.setApprovals(10);

        svc.evaluateProposal(new Room(), applied, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(0);
    }

    @Test
    void evaluateProposalAppliesWhenQuorumApproves() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = pendingProposal(/* requiredVotes */ 3);
        proposal.setApprovals(2);
        proposal.setRejections(1);   // total=3 → meets quorum, approvals>rejections

        svc.evaluateProposal(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(1);
        verify(proposalRepo, never()).save(proposal);   // applyProposal owns the save
    }

    @Test
    void evaluateProposalRejectsWhenQuorumRejects() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = pendingProposal(3);
        proposal.setApprovals(1);
        proposal.setRejections(2);

        svc.evaluateProposal(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(0);
        assertThat(proposal.getStatus()).isEqualTo("REJECTED");
        verify(proposalRepo).save(proposal);
    }

    @Test
    void evaluateProposalNoOpWhenTotalBelowQuorum() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = pendingProposal(3);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        // RoomProposal.createdAt is initialised to Instant.now() at construction,
        // so the proposal is "fresh" and the min-live-seconds gate blocks the
        // expiry branch.
        proposal.setExpiresAt(Instant.now().plusSeconds(60));

        svc.evaluateProposal(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(0);
        assertThat(proposal.getStatus()).isEqualTo("PENDING");
    }

    // ---------- evaluateProposal — expiry / no-participation ----------

    @Test
    void evaluateProposalRoutesToApplyNoParticipationOnExpiry() {
        // Proposal: 1 approval, 0 rejections, below quorum (required=3),
        // created > min-live ago, expired. Should walk into
        // applyNoParticipation. With no neighbour list, applyNoParticipation
        // computes the boolean (1 > 0 → approve) and fires the callback.
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = pendingProposal(3);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal.setAnchorX(5);
        proposal.setAnchorY(5);
        // RoomProposal.createdAt has no setter — backfill the private field via
        // reflection so the proposal looks older than min-live-seconds.
        setProposalCreatedAt(proposal, Instant.now().minusSeconds(MIN_PROPOSAL_LIVE_SECONDS + 1));
        proposal.setExpiresAt(Instant.now().minusSeconds(1));
        proposal.setNeighborIdsJson("[]");
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        svc.evaluateProposal(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(1);
    }

    private static void setProposalCreatedAt(RoomProposal proposal, Instant when) {
        try {
            java.lang.reflect.Field f = RoomProposal.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(proposal, when);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---------- applyNoParticipation ----------

    @Test
    void applyNoParticipationRejectsWhenRejectionsExceedApprovals() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(0);
        proposal.setRejections(2);
        proposal.setNeighborIdsJson("[]");
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        svc.applyNoParticipation(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(0);
        assertThat(proposal.getStatus()).isEqualTo("REJECTED");
        // Pre-carve quirk preserved verbatim: applyNoParticipation calls
        // proposalRepository.save twice on the reject path (once inside the
        // else, once at the bottom of the method).
        verify(proposalRepo, org.mockito.Mockito.times(2)).save(proposal);
    }

    @Test
    void applyNoParticipationApprovesOnTieWhenFlagOn() {
        RoomProposalService tieApprove = make(/* approveOnTie */ true, QUORUM_CAP_OFF);
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(2);
        proposal.setRejections(2);
        proposal.setNeighborIdsJson("[]");
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        tieApprove.applyNoParticipation(new Room(), proposal, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(1);
    }

    @Test
    void applyNoParticipationDoesNotApproveTieWhenBothCountsAreZero() {
        RoomProposalService tieApprove = make(/* approveOnTie */ true, QUORUM_CAP_OFF);
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(0);
        proposal.setRejections(0);
        proposal.setNeighborIdsJson("[]");
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        tieApprove.applyNoParticipation(new Room(), proposal, applyProposal);

        // 0==0 but approvals must be > 0 for the tie-approve toggle to fire.
        assertThat(applyCalls.get()).isEqualTo(0);
        assertThat(proposal.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void applyNoParticipationLocksMissedNeighbourAtSecondMiss() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        UUID participantId = UUID.randomUUID();
        // Real entity (no setId() exists, but we don't need it — only the
        // lookup-by-id needs to return this object). The implementation
        // mutates missedVotes in place, so a real entity tracks state across
        // the two getMissedVotes calls naturally — a plain Mockito mock with
        // a constant stub would silently mis-model the increment.
        RoomParticipant participant = new RoomParticipant();
        participant.setX(5);
        participant.setY(5);
        participant.setMissedVotes(1);   // already had 1 prior miss

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal.setNeighborIdsJson("[\"P:" + participantId + "\"]");
        when(participantRepo.findById(participantId)).thenReturn(java.util.Optional.of(participant));
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        svc.applyNoParticipation(new Room(), proposal, applyProposal);

        // After: missedVotes was bumped to 2, threshold tripped, lockedUntil set,
        // and missedVotes reset to 0.
        assertThat(participant.getMissedVotes()).isEqualTo(0);
        assertThat(participant.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void applyNoParticipationDoesNotLockOnFirstMiss() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        UUID participantId = UUID.randomUUID();
        RoomParticipant participant = new RoomParticipant();
        participant.setX(5);
        participant.setY(5);
        participant.setMissedVotes(0);   // fresh — first miss

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal.setNeighborIdsJson("[\"P:" + participantId + "\"]");
        when(participantRepo.findById(participantId)).thenReturn(java.util.Optional.of(participant));
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        svc.applyNoParticipation(new Room(), proposal, applyProposal);

        // missedVotes went from 0 → 1; threshold (2) not yet tripped → no lock.
        assertThat(participant.getMissedVotes()).isEqualTo(1);
        assertThat(participant.getLockedUntil()).isNull();
    }

    @Test
    void applyNoParticipationSkipsLockingForNeighbourOutsideRadius() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        UUID participantId = UUID.randomUUID();
        // participant at (50, 50); anchor at (5, 5). distance² = 45² + 45² = 4050.
        // neighborRadius² = 8² = 64 → well outside.
        RoomParticipant participant = new RoomParticipant();
        participant.setX(50);
        participant.setY(50);
        participant.setMissedVotes(5);   // sentinel: should not be touched

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal.setNeighborIdsJson("[\"P:" + participantId + "\"]");
        when(participantRepo.findById(participantId)).thenReturn(java.util.Optional.of(participant));
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of());

        svc.applyNoParticipation(new Room(), proposal, applyProposal);

        // Outside-radius participant is exempt — no missed-vote bump.
        assertThat(participant.getMissedVotes()).isEqualTo(5);
        assertThat(participant.getLockedUntil()).isNull();
    }

    @Test
    void applyNoParticipationSkipsParticipantsThatAlreadyVoted() {
        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        UUID voterId = UUID.randomUUID();
        RoomProposalVote vote = mock(RoomProposalVote.class);
        RoomParticipant voterRef = mock(RoomParticipant.class);
        when(voterRef.getId()).thenReturn(voterId);
        when(vote.getParticipant()).thenReturn(voterRef);

        RoomProposal proposal = expiredProposalAt(5, 5);
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal.setNeighborIdsJson("[\"P:" + voterId + "\"]");
        when(voteRepo.findByProposal(proposal)).thenReturn(List.of(vote));

        svc.applyNoParticipation(new Room(), proposal, applyProposal);

        // The voter participated → they should never be looked up to penalise.
        verify(participantRepo, never()).findById(voterId);
    }

    // ---------- evaluatePendingProposals ----------

    @Test
    void evaluatePendingProposalsCallsEvaluateForEachPending() {
        Room room = new Room();
        RoomProposal a = pendingProposal(3);
        RoomProposal b = pendingProposal(3);
        b.setApprovals(2);
        b.setRejections(1);   // meets quorum, will trigger applyProposal

        when(proposalRepo.findTop200ByRoomAndStatusOrderByCreatedAtDesc(room, "PENDING"))
                .thenReturn(List.of(a, b));

        AtomicInteger applyCalls = new AtomicInteger();
        BiConsumer<Room, RoomProposal> applyProposal = (r, p) -> applyCalls.incrementAndGet();

        svc.evaluatePendingProposals(room, applyProposal);

        assertThat(applyCalls.get()).isEqualTo(1);   // b applied, a stayed pending
    }

    // ---------- helpers ----------

    private static int anyIntValue() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static RoomParticipant participantAt(int x, int y) {
        RoomParticipant p = mock(RoomParticipant.class);
        when(p.getX()).thenReturn(x);
        when(p.getY()).thenReturn(y);
        when(p.getMissedVotes()).thenReturn(0);
        return p;
    }

    private static RoomProposal anchoredAt(int x, int y) {
        RoomProposal p = new RoomProposal();
        p.setAnchorX(x);
        p.setAnchorY(y);
        return p;
    }

    private static RoomProposal pendingProposal(int requiredVotes) {
        RoomProposal p = new RoomProposal();
        p.setStatus("PENDING");
        p.setRequiredVotes(requiredVotes);
        return p;
    }

    private static RoomProposal expiredProposalAt(int anchorX, int anchorY) {
        RoomProposal p = anchoredAt(anchorX, anchorY);
        p.setStatus("PENDING");
        p.setExpiresAt(Instant.now().minusSeconds(1));
        return p;
    }
}
