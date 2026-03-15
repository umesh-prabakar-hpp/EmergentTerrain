package com.emergentterrain.gpu;

import com.emergentterrain.EmergentTerrainApplication;
import com.emergentterrain.domain.Profile;
import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.User;
import com.emergentterrain.gpu.v1.BoolGrid;
import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.GpuTerrainServiceGrpc;
import com.emergentterrain.gpu.v1.RebuildPatchRequest;
import com.emergentterrain.gpu.v1.RebuildPatchResponse;
import com.emergentterrain.gpu.v1.Rect;
import com.emergentterrain.gpu.v1.SimulateBotsRequest;
import com.emergentterrain.gpu.v1.SimulateBotsResponse;
import com.emergentterrain.gpu.v1.TileEncoding;
import com.emergentterrain.gpu.v1.TileGrid;
import com.emergentterrain.repo.ProfileRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.UserRepository;
import com.emergentterrain.service.HiddenProperties;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.RoomService;
import com.emergentterrain.web.RoomBotInfo;
import com.emergentterrain.web.RoomCreateResponse;
import com.emergentterrain.web.RoomParticipantInfo;
import com.emergentterrain.web.RoomProposalRequest;
import com.emergentterrain.web.RoomStateResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EmergentTerrainApplication.class)
@Import(RoomServiceGpuBotIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.gpu.enabled=true",
    "app.gpu.world.enabled=false",
    "app.gpu.bots.enabled=true",
    "app.gpu.bots.applyEdits=false",
    "app.gpu.bots.cacheWorld=false",
    "app.gpu.bots.steps=1",
    "app.consensus.botApproveBias=1.0",
    "app.consensus.minVotesToResolve=1",
    "app.consensus.minNeighbors=1",
    "app.consensus.quorumCap=1",
    "app.consensus.maintenanceIntervalMs=3600000",
    "app.consensus.maintenanceWorkers=1",
    "app.bots.maxPerRoom=5"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RoomServiceGpuBotIntegrationTest {
    @Autowired
    private RoomService roomService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomBotRepository roomBotRepository;

    @Autowired
    private RecordingGpuService gpuService;

    @Test
    void scheduledMaintenanceUsesGpuBotSimulationAndAppliesReturnedPositions() {
        Payload payload = createPayload();
        RoomCreateResponse created = roomService.createRoom(payload, "GPU Bot Integration Room");
        UUID roomId = UUID.fromString(created.getRoomId());
        Room room = roomRepository.findById(roomId).orElseThrow();

        Map<String, RoomBotInfo> initialBotsById = created.getBots().stream()
            .collect(Collectors.toMap(RoomBotInfo::getId, Function.identity()));
        assertThat(initialBotsById).hasSize(5);

        roomService.scheduledRoomMaintenance();

        SimulateBotsRequest request = gpuService.lastSimulateBotsRequest();
        assertThat(request).as("GPU bot request should be captured").isNotNull();
        assertThat(request.getBotsCount()).isEqualTo(initialBotsById.size());
        assertThat(request.getTiles().getWidth()).isGreaterThan(0);
        assertThat(request.getTiles().getHeight()).isGreaterThan(0);
        assertThat(request.getHighways().getWidth()).isEqualTo(request.getTiles().getWidth());
        assertThat(request.getHighways().getHeight()).isEqualTo(request.getTiles().getHeight());

        RoomStateResponse updated = roomService.getRoomState(payload, roomId);
        Map<String, RoomBotInfo> updatedBotsById = updated.getBots().stream()
            .collect(Collectors.toMap(RoomBotInfo::getId, Function.identity()));

        for (BotState botState : request.getBotsList()) {
            RoomBotInfo initial = initialBotsById.get(botState.getId());
            RoomBotInfo current = updatedBotsById.get(botState.getId());
            assertThat(initial).isNotNull();
            assertThat(current).isNotNull();
            assertThat(current.getX()).isEqualTo(expectedX(botState, request.getTiles().getWidth()));
            assertThat(current.getY()).isEqualTo(expectedY(botState, request.getTiles().getHeight()));
            assertThat(current.getX() != initial.getX() || current.getY() != initial.getY()).isTrue();
        }

        assertThat(roomBotRepository.findByRoom(room))
            .extracting(bot -> bot.getId().toString())
            .containsExactlyInAnyOrderElementsOf(updatedBotsById.keySet());
    }

    @Test
    void proposeUsesGpuPatchRebuildAndAppliesReturnedPatch() {
        Payload payload = createPayload();
        RoomCreateResponse created = roomService.createRoom(payload, "GPU Patch Integration Room");
        UUID roomId = UUID.fromString(created.getRoomId());

        RoomStateResponse initial = roomService.getRoomState(payload, roomId);
        RoomParticipantInfo participant = initial.getParticipants().stream()
            .filter(info -> Objects.equals(info.getId(), created.getParticipantId()))
            .findFirst()
            .orElseGet(() -> initial.getParticipants().get(0));

        int x = participant.getX();
        int y = participant.getY();
        int targetType = initial.getTiles()[y][x] == 3 ? 1 : 3;
        int[][] proposedTiles = copyTiles(initial.getTiles());
        proposedTiles[y][x] = targetType;

        RoomProposalRequest request = new RoomProposalRequest();
        request.setTiles(proposedTiles);
        request.setDelta(Map.of(
            "x", x,
            "y", y,
            "type", targetType
        ));
        request.setNote("gpu-patch-test");

        roomService.propose(payload, roomId, request);

        RebuildPatchRequest patchRequest = gpuService.lastRebuildPatchRequest();
        assertThat(patchRequest).as("GPU patch request should be captured").isNotNull();
        assertThat(patchRequest.getPatch()).isEqualTo(Rect.newBuilder().setX(x).setY(y).setWidth(1).setHeight(1).build());
        assertThat(patchRequest.getAppliedEditsCount()).isEqualTo(1);
        assertThat(patchRequest.getAppliedEdits(0).getX()).isEqualTo(x);
        assertThat(patchRequest.getAppliedEdits(0).getY()).isEqualTo(y);
        assertThat(patchRequest.getAppliedEdits(0).getType()).isEqualTo(targetType);

        RoomStateResponse updated = roomService.getRoomState(payload, roomId);
        assertThat(updated.getTiles()[y][x]).isEqualTo(targetType);
        assertThat(updated.getHighways()[y][x]).isFalse();
    }

    private int[][] copyTiles(int[][] tiles) {
        int[][] out = new int[tiles.length][];
        for (int y = 0; y < tiles.length; y++) {
            out[y] = tiles[y].clone();
        }
        return out;
    }

    private int expectedX(BotState botState, int width) {
        return Math.floorMod(botState.getX() + 1, width);
    }

    private int expectedY(BotState botState, int height) {
        return Math.floorMod(botState.getY() + 2, height);
    }

    private Payload createPayload() {
        User user = new User();
        user.setEmail("gpu-bot-test-" + UUID.randomUUID() + "@example.com");
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
        return new Payload(
            1,
            profile.getId().toString(),
            profile.getAge(),
            profile.getSex(),
            profile.getLocation(),
            Map.of(
                "likes_forests", true,
                "likes_open_fields", false,
                "likes_water", true,
                "likes_industry", false
            ),
            hidden,
            Instant.now().toString()
        );
    }

    @Configuration
    static class TestConfig {
        private static final String SERVER_NAME = "gpu-bot-room-service-test";

        @Bean
        RecordingGpuService recordingGpuService() {
            return new RecordingGpuService();
        }

        @Bean(destroyMethod = "shutdownNow")
        Server grpcServer(RecordingGpuService gpuService) throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(gpuService)
                .build()
                .start();
        }

        @Bean(destroyMethod = "shutdownNow")
        ManagedChannel grpcChannel(Server server) {
            return InProcessChannelBuilder.forName(SERVER_NAME)
                .directExecutor()
                .build();
        }

        @Bean
        @Primary
        GpuEngineClient gpuEngineClient(ManagedChannel channel) {
            return new GpuEngineClient(channel);
        }
    }

    static class RecordingGpuService extends GpuTerrainServiceGrpc.GpuTerrainServiceImplBase {
        private final AtomicReference<SimulateBotsRequest> lastSimulateBotsRequest = new AtomicReference<>();
        private final AtomicReference<RebuildPatchRequest> lastRebuildPatchRequest = new AtomicReference<>();

        SimulateBotsRequest lastSimulateBotsRequest() {
            return lastSimulateBotsRequest.get();
        }

        RebuildPatchRequest lastRebuildPatchRequest() {
            return lastRebuildPatchRequest.get();
        }

        @Override
        public void simulateBots(SimulateBotsRequest request, StreamObserver<SimulateBotsResponse> responseObserver) {
            lastSimulateBotsRequest.set(request);
            SimulateBotsResponse.Builder response = SimulateBotsResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setDebugJson("{\"stub\":true}");
            for (BotState bot : request.getBotsList()) {
                response.addBots(BotState.newBuilder()
                    .setId(bot.getId())
                    .setX(Math.floorMod(bot.getX() + 1, request.getTiles().getWidth()))
                    .setY(Math.floorMod(bot.getY() + 2, request.getTiles().getHeight()))
                    .setForest(bot.getForest())
                    .setClear(bot.getClear())
                    .setWater(bot.getWater())
                    .setIndustry(bot.getIndustry())
                    .setMobility(bot.getMobility())
                    .setState(bot.getState()));
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }

        @Override
        public void generateRegion(com.emergentterrain.gpu.v1.GenerateRegionRequest request,
                                   StreamObserver<com.emergentterrain.gpu.v1.GenerateRegionResponse> responseObserver) {
            int width = request.getRegion().getWidth();
            int height = request.getRegion().getHeight();
            int size = Math.multiplyExact(width, height);
            responseObserver.onNext(com.emergentterrain.gpu.v1.GenerateRegionResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setTiles(com.emergentterrain.gpu.v1.TileGrid.newBuilder()
                    .setWidth(width)
                    .setHeight(height)
                    .setEncoding(com.emergentterrain.gpu.v1.TileEncoding.TILE_ENCODING_U8)
                    .setTiles(ByteString.copyFrom(new byte[size]))
                    .build())
                .setHighways(BoolGrid.newBuilder()
                    .setWidth(width)
                    .setHeight(height)
                    .setValues(ByteString.copyFrom(new byte[size]))
                    .build())
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void rebuildPatch(RebuildPatchRequest request, StreamObserver<RebuildPatchResponse> responseObserver) {
            lastRebuildPatchRequest.set(request);
            int type = request.getAppliedEditsCount() > 0 ? request.getAppliedEdits(0).getType() : 0;
            byte tileValue = (byte) (type == 4 ? 0 : type);
            byte highwayValue = (byte) (type == 4 ? 1 : 0);
            Rect patch = request.getPatch();
            responseObserver.onNext(RebuildPatchResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setPatch(patch)
                .setPatchTiles(TileGrid.newBuilder()
                    .setWidth(patch.getWidth())
                    .setHeight(patch.getHeight())
                    .setEncoding(TileEncoding.TILE_ENCODING_U8)
                    .setTiles(ByteString.copyFrom(new byte[] { tileValue }))
                    .build())
                .setPatchHighways(BoolGrid.newBuilder()
                    .setWidth(patch.getWidth())
                    .setHeight(patch.getHeight())
                    .setValues(ByteString.copyFrom(new byte[] { highwayValue }))
                    .build())
                .setDebugJson("{\"stub\":true}")
                .build());
            responseObserver.onCompleted();
        }
    }
}
