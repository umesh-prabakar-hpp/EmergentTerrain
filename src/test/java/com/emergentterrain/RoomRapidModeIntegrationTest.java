package com.emergentterrain;

import com.emergentterrain.domain.Profile;
import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.domain.User;
import com.emergentterrain.repo.ProfileRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomParticipantRepository;
import com.emergentterrain.repo.RoomProposalRepository;
import com.emergentterrain.repo.RoomProposalVoteRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.repo.RoomStateSnapshotRepository;
import com.emergentterrain.repo.UserRepository;
import com.emergentterrain.service.HiddenProperties;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.RoomService;
import com.emergentterrain.web.RoomCreateResponse;
import com.emergentterrain.web.RoomProposalRequest;
import com.emergentterrain.web.RoomStateResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EmergentTerrainApplication.class)
@ActiveProfiles("test")
class RoomRapidModeIntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoomRapidModeIntegrationTest.class);

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomStateRepository roomStateRepository;

    @Autowired
    private RoomStateSnapshotRepository roomStateSnapshotRepository;

    @Autowired
    private RoomProposalRepository roomProposalRepository;

    @Autowired
    private RoomProposalVoteRepository roomProposalVoteRepository;

    @Autowired
    private RoomBotRepository roomBotRepository;

    @Autowired
    private RoomParticipantRepository roomParticipantRepository;

    @Test
    void rapidModeGeneratesAndPersistsRoomChanges() throws Exception {
        long durationSeconds = Long.parseLong(System.getProperty("rapid.test.durationSeconds", "60"));
        int proposalsPerBatch = Integer.parseInt(System.getProperty("rapid.test.proposalsPerBatch", "10"));
        int maintenancePerBatch = Integer.parseInt(System.getProperty("rapid.test.maintenancePerBatch", "1"));
        int sleepMs = Integer.parseInt(System.getProperty("rapid.test.sleepMs", "200"));

        Payload payload = createPayload();
        RoomCreateResponse created = roomService.createRoom(payload, "Rapid Test Room A");
        UUID roomId = UUID.fromString(created.getRoomId());
        Room room = roomRepository.findById(roomId).orElseThrow();
        RoomState initialState = roomStateRepository.findByRoom(room).orElseThrow();
        long initialTotalChanges = initialState.getTotalChangeCount() == null ? 0 : initialState.getTotalChangeCount();
        long initialSnapshotCount = snapshotCountForRoom(roomId);

        RoomCreateResponse createdTwo = roomService.createRoom(payload, "Rapid Test Room B");
        UUID roomIdTwo = UUID.fromString(createdTwo.getRoomId());
        Room roomTwo = roomRepository.findById(roomIdTwo).orElseThrow();
        RoomState initialStateTwo = roomStateRepository.findByRoom(roomTwo).orElseThrow();
        long initialTotalChangesTwo = initialStateTwo.getTotalChangeCount() == null ? 0 : initialStateTwo.getTotalChangeCount();
        long initialSnapshotCountTwo = snapshotCountForRoom(roomIdTwo);

        long startNanos = System.nanoTime();
        long endNanos = startNanos + TimeUnit.SECONDS.toNanos(durationSeconds);
        long proposalsSubmitted = 0;
        long maintenanceCalls = 0;
        int proposalIndex = 0;

        while (System.nanoTime() < endNanos) {
            boolean targetFirst = (proposalIndex % 2 == 0);
            UUID activeRoomId = targetFirst ? roomId : roomIdTwo;
            RoomStateResponse state = roomService.getRoomState(payload, activeRoomId);
            int[][] tiles = state.getTiles();

            for (int i = 0; i < proposalsPerBatch; i++) {
                int x = Math.floorMod(3 + proposalIndex * 7, tiles[0].length);
                int y = Math.floorMod(5 + proposalIndex * 11, tiles.length);
                int nextType = tiles[y][x] == 1 ? 0 : 1;

                RoomProposalRequest req = new RoomProposalRequest();
                req.setDelta(Map.of("x", x, "y", y, "type", nextType));
                req.setTiles(copyWithChange(tiles, x, y, nextType));
                req.setNote("rapid-load-" + proposalIndex);
                roomService.propose(payload, activeRoomId, req);

                proposalIndex++;
                proposalsSubmitted++;
            }

            for (int i = 0; i < maintenancePerBatch; i++) {
                roomService.scheduledRoomMaintenance();
                maintenanceCalls++;
            }

            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
        }
        roomService.flushRoomStateCache();

        RoomState latestState = roomStateRepository.findByRoom(room).orElseThrow();
        long finalTotalChanges = latestState.getTotalChangeCount() == null ? 0 : latestState.getTotalChangeCount();
        long finalSnapshotCount = snapshotCountForRoom(roomId);

        RoomState latestStateTwo = roomStateRepository.findByRoom(roomTwo).orElseThrow();
        long finalTotalChangesTwo = latestStateTwo.getTotalChangeCount() == null ? 0 : latestStateTwo.getTotalChangeCount();
        long finalSnapshotCountTwo = snapshotCountForRoom(roomIdTwo);

        long proposalCount = countProposalsForRoom(roomId);
        long voteCount = countVotesForRoom(roomId);
        long botCount = countBotsForRoom(roomId);
        long participantCount = countParticipantsForRoom(roomId);
        long proposalCountTwo = countProposalsForRoom(roomIdTwo);
        long voteCountTwo = countVotesForRoom(roomIdTwo);
        long botCountTwo = countBotsForRoom(roomIdTwo);
        long participantCountTwo = countParticipantsForRoom(roomIdTwo);
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        LOGGER.info(
            "rapidModeSummary roomId={} durationMs={} proposalsSubmitted={} maintenanceCalls={} proposals={} votes={} bots={} participants={} snapshots={} totalChangeCount={}",
            roomId,
            durationMs,
            proposalsSubmitted,
            maintenanceCalls,
            proposalCount,
            voteCount,
            botCount,
            participantCount,
            finalSnapshotCount,
            finalTotalChanges
        );
        LOGGER.info(
            "rapidModeSummary roomId={} durationMs={} proposalsSubmitted={} maintenanceCalls={} proposals={} votes={} bots={} participants={} snapshots={} totalChangeCount={}",
            roomIdTwo,
            durationMs,
            proposalsSubmitted,
            maintenanceCalls,
            proposalCountTwo,
            voteCountTwo,
            botCountTwo,
            participantCountTwo,
            finalSnapshotCountTwo,
            finalTotalChangesTwo
        );

        assertThat(finalTotalChanges).isGreaterThan(initialTotalChanges);
        assertThat(finalSnapshotCount).isGreaterThan(initialSnapshotCount);
        assertThat(finalTotalChangesTwo).isGreaterThan(initialTotalChangesTwo);
        assertThat(finalSnapshotCountTwo).isGreaterThan(initialSnapshotCountTwo);
    }

    private Payload createPayload() {
        User user = new User();
        user.setEmail("rapid-test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user = userRepository.save(user);

        Profile profile = new Profile();
        profile.setId(UUID.randomUUID());
        profile.setUser(user);
        profile.setAge(30);
        profile.setSex("N");
        profile.setLocation("US");
        profile.setAnswersJson("{}");
        profile.setHiddenPropsJson("{}");
        profile.setPayloadHash("test-payload-hash");
        profileRepository.save(profile);

        HiddenProperties hidden = new HiddenProperties(0.4, 0.5, 0.6, 0.5, 0.7, 0.3);
        Map<String, Boolean> answers = Map.of(
            "likes_forests", true,
            "likes_open_fields", false,
            "likes_water", true,
            "likes_industry", false
        );
        return new Payload(
            1,
            profile.getId().toString(),
            profile.getAge(),
            profile.getSex(),
            profile.getLocation(),
            answers,
            hidden,
            Instant.now().toString()
        );
    }

    private static int[][] copyWithChange(int[][] source, int x, int y, int type) {
        int[][] out = new int[source.length][source[0].length];
        for (int row = 0; row < source.length; row++) {
            System.arraycopy(source[row], 0, out[row], 0, source[0].length);
        }
        out[y][x] = type;
        return out;
    }

    private long snapshotCountForRoom(UUID roomId) {
        return roomStateSnapshotRepository.findAll().stream()
            .filter(s -> s.getRoom() != null && roomId.equals(s.getRoom().getId()))
            .count();
    }

    private long countProposalsForRoom(UUID roomId) {
        return roomProposalRepository.findAll().stream()
            .filter(p -> p.getRoom() != null && roomId.equals(p.getRoom().getId()))
            .count();
    }

    private long countVotesForRoom(UUID roomId) {
        return roomProposalVoteRepository.findAll().stream()
            .filter(v -> v.getProposal() != null
                && v.getProposal().getRoom() != null
                && roomId.equals(v.getProposal().getRoom().getId()))
            .count();
    }

    private long countBotsForRoom(UUID roomId) {
        return roomBotRepository.findAll().stream()
            .filter(b -> b.getRoom() != null && roomId.equals(b.getRoom().getId()))
            .count();
    }

    private long countParticipantsForRoom(UUID roomId) {
        return roomParticipantRepository.findAll().stream()
            .filter(p -> p.getRoom() != null && roomId.equals(p.getRoom().getId()))
            .count();
    }
}
