package com.emergentterrain;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.domain.User;
import com.emergentterrain.repo.ProfileRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
@Profile("rapid-runner")
public class RapidModeTestRunner implements ApplicationRunner {

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
    private ConfigurableApplicationContext context;

    public static void main(String[] args) {
        SpringApplication.run(RapidModeTestRunner.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Payload payload = createPayload();
        RoomCreateResponse created = roomService.createRoom(payload, "Rapid Test Room");
        UUID roomId = UUID.fromString(created.getRoomId());
        Room room = roomRepository.findById(roomId).orElseThrow();
        RoomState initialState = roomStateRepository.findByRoom(room).orElseThrow();
        long initialTotalChanges = initialState.getTotalChangeCount() == null ? 0 : initialState.getTotalChangeCount();
        long initialSnapshotCount = snapshotCountForRoom(roomId);

        for (int i = 0; i < 10; i++) {
            RoomStateResponse state = roomService.getRoomState(payload, roomId);
            int[][] tiles = state.getTiles();
            int x = Math.floorMod(3 + i * 7, tiles[0].length);
            int y = Math.floorMod(5 + i * 11, tiles.length);
            int nextType = tiles[y][x] == 1 ? 0 : 1;

            RoomProposalRequest req = new RoomProposalRequest();
            req.setDelta(Map.of("x", x, "y", y, "type", nextType));
            req.setTiles(copyWithChange(tiles, x, y, nextType));
            req.setNote("rapid-load-" + i);
            roomService.propose(payload, roomId, req);
        }

        for (int i = 0; i < 24; i++) {
            roomService.scheduledRoomMaintenance();
            Thread.sleep(220);
        }
        roomService.flushRoomStateCache();

        RoomState latestState = roomStateRepository.findByRoom(room).orElseThrow();
        long finalTotalChanges = latestState.getTotalChangeCount() == null ? 0 : latestState.getTotalChangeCount();
        long finalSnapshotCount = snapshotCountForRoom(roomId);

        if (finalTotalChanges <= initialTotalChanges) {
            throw new IllegalStateException("Expected totalChangeCount to increase, got " + finalTotalChanges);
        }
        if (finalSnapshotCount <= initialSnapshotCount) {
            throw new IllegalStateException("Expected snapshots to increase, got " + finalSnapshotCount);
        }

        SpringApplication.exit(context, () -> 0);
    }

    private Payload createPayload() {
        User user = new User();
        user.setEmail("rapid-test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user = userRepository.save(user);

        com.emergentterrain.domain.Profile profile = new com.emergentterrain.domain.Profile();
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
}
