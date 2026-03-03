package com.emergentterrain.gpu;

import com.emergentterrain.gpu.v1.BoolGrid;
import com.emergentterrain.gpu.v1.GenerateRegionRequest;
import com.emergentterrain.gpu.v1.GenerateRegionResponse;
import com.emergentterrain.gpu.v1.GpuTerrainServiceGrpc;
import com.emergentterrain.gpu.v1.RebuildPatchRequest;
import com.emergentterrain.gpu.v1.RebuildPatchResponse;
import com.emergentterrain.gpu.v1.Region;
import com.emergentterrain.gpu.v1.SimulateBotsRequest;
import com.emergentterrain.gpu.v1.SimulateBotsResponse;
import com.emergentterrain.gpu.v1.TileEncoding;
import com.emergentterrain.gpu.v1.TileGrid;
import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.TileEdit;
import com.emergentterrain.gpu.v1.Rect;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = GpuEngineAdapterIntegrationTest.TestConfig.class)
class GpuEngineAdapterIntegrationTest {
    @Autowired
    private GpuEngineAdapter adapter;

    @Test
    void adapterCallsGrpcService() {
        GenerateRegionRequest regionRequest = GenerateRegionRequest.newBuilder()
            .setRequestId("req-1")
            .setSeed(123L)
            .setRegion(Region.newBuilder().setX(0).setY(0).setWidth(4).setHeight(3))
            .build();
        GenerateRegionResponse regionResponse = adapter.generateRegion(regionRequest);
        assertThat(regionResponse.getTiles().getWidth()).isEqualTo(4);
        assertThat(regionResponse.getTiles().getHeight()).isEqualTo(3);
        assertThat(regionResponse.getTiles().getTiles().size()).isEqualTo(12);

        RebuildPatchRequest patchRequest = RebuildPatchRequest.newBuilder()
            .setRequestId("req-2")
            .setRegion(Region.newBuilder().setX(0).setY(0).setWidth(4).setHeight(3))
            .setPatch(Rect.newBuilder().setX(1).setY(1).setWidth(2).setHeight(2))
            .build();
        RebuildPatchResponse patchResponse = adapter.rebuildPatch(patchRequest);
        assertThat(patchResponse.getPatchTiles().getWidth()).isEqualTo(2);
        assertThat(patchResponse.getPatchTiles().getHeight()).isEqualTo(2);

        SimulateBotsRequest botsRequest = SimulateBotsRequest.newBuilder()
            .setRequestId("req-3")
            .addBots(BotState.newBuilder().setId("bot-1").setX(1).setY(2))
            .build();
        SimulateBotsResponse botsResponse = adapter.simulateBots(botsRequest);
        assertThat(botsResponse.getBotsCount()).isEqualTo(1);
        assertThat(botsResponse.getEditsCount()).isEqualTo(0);
    }

    @Configuration
    static class TestConfig {
        private static final String SERVER_NAME = "gpu-engine-test";

        @Bean(destroyMethod = "shutdownNow")
        Server grpcServer() throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                .directExecutor()
                .addService(new TestGpuService())
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
        GpuEngineClient gpuEngineClient(ManagedChannel channel) {
            return new GpuEngineClient(channel);
        }

        @Bean
        GpuEngineAdapter gpuEngineAdapter(GpuEngineClient client) {
            return new GpuEngineAdapter(client);
        }
    }

    static class TestGpuService extends GpuTerrainServiceGrpc.GpuTerrainServiceImplBase {
        @Override
        public void generateRegion(GenerateRegionRequest request, StreamObserver<GenerateRegionResponse> responseObserver) {
            Region region = request.getRegion();
            long sizeLong = (long) region.getWidth() * (long) region.getHeight();
            int size = Math.toIntExact(sizeLong);
            TileGrid tiles = TileGrid.newBuilder()
                .setWidth(region.getWidth())
                .setHeight(region.getHeight())
                .setEncoding(TileEncoding.TILE_ENCODING_U8)
                .setTiles(ByteString.copyFrom(new byte[size]))
                .build();
            BoolGrid highways = BoolGrid.newBuilder()
                .setWidth(region.getWidth())
                .setHeight(region.getHeight())
                .setValues(ByteString.copyFrom(new byte[size]))
                .build();
            GenerateRegionResponse response = GenerateRegionResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setTiles(tiles)
                .setHighways(highways)
                .setDebugJson("{\"stub\":true}")
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void rebuildPatch(RebuildPatchRequest request, StreamObserver<RebuildPatchResponse> responseObserver) {
            Rect patch = request.getPatch();
            long sizeLong = (long) patch.getWidth() * (long) patch.getHeight();
            int size = Math.toIntExact(sizeLong);
            TileGrid patchTiles = TileGrid.newBuilder()
                .setWidth(patch.getWidth())
                .setHeight(patch.getHeight())
                .setEncoding(TileEncoding.TILE_ENCODING_U8)
                .setTiles(ByteString.copyFrom(new byte[size]))
                .build();
            BoolGrid patchHighways = BoolGrid.newBuilder()
                .setWidth(patch.getWidth())
                .setHeight(patch.getHeight())
                .setValues(ByteString.copyFrom(new byte[size]))
                .build();
            RebuildPatchResponse response = RebuildPatchResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setPatch(patch)
                .setPatchTiles(patchTiles)
                .setPatchHighways(patchHighways)
                .setDebugJson("{\"stub\":true}")
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void simulateBots(SimulateBotsRequest request, StreamObserver<SimulateBotsResponse> responseObserver) {
            List<BotState> bots = request.getBotsList();
            SimulateBotsResponse response = SimulateBotsResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .addAllBots(bots)
                .addAllEdits(List.of())
                .setDebugJson("{\"stub\":true}")
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
