package com.emergentterrain.bench;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.GpuEngineClient;
import com.emergentterrain.gpu.GpuEngineCodec;
import com.emergentterrain.gpu.v1.BoolGrid;
import com.emergentterrain.gpu.v1.RebuildPatchRequest;
import com.emergentterrain.gpu.v1.Rect;
import com.emergentterrain.gpu.v1.Region;
import com.emergentterrain.gpu.v1.TileEdit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class GpuVsCpuPatchRebuildBenchmark {

    @State(Scope.Benchmark)
    public static class PatchState {
        @Param({"RPC", "HEAVY"})
        private String profile;

        private int width;
        private int height;
        private int patchWidth;
        private int patchHeight;
        private int patchX;
        private int patchY;
        private int[][] tiles;
        private boolean[][] highways;
        private Rect patchRect;
        private Region region;
        private com.emergentterrain.gpu.v1.TileGrid tileGrid;
        private BoolGrid highwayGrid;
        private GpuEngineClient gpuEngineClient;
        private GpuEngineAdapter gpuEngineAdapter;

        @Setup(Level.Trial)
        public void setup() {
            if ("RPC".equalsIgnoreCase(profile)) {
                width = 64;
                height = 48;
                patchWidth = 4;
                patchHeight = 4;
            } else {
                width = 256;
                height = 192;
                patchWidth = 32;
                patchHeight = 32;
            }
            patchX = Math.max(0, (width / 2) - (patchWidth / 2));
            patchY = Math.max(0, (height / 2) - (patchHeight / 2));

            Random random = new Random(17);
            tiles = new int[height][width];
            highways = new boolean[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    tiles[y][x] = random.nextInt(4);
                    highways[y][x] = (x % 11 == 0) || (y % 11 == 0);
                }
            }

            tileGrid = GpuEngineCodec.toTileGrid(tiles);
            highwayGrid = GpuEngineCodec.toBoolGrid(highways, width, height);
            patchRect = Rect.newBuilder().setX(patchX).setY(patchY).setWidth(patchWidth).setHeight(patchHeight).build();
            region = Region.newBuilder().setX(0).setY(0).setWidth(width).setHeight(height).build();

            String host = System.getProperty("gpu.host",
                System.getenv().getOrDefault("GPU_ENGINE_HOST", "127.0.0.1"));
            int port = Integer.parseInt(System.getProperty("gpu.port",
                System.getenv().getOrDefault("GPU_ENGINE_PORT", "50051")));
            gpuEngineClient = new GpuEngineClient(host, port, false);
            gpuEngineAdapter = new GpuEngineAdapter(gpuEngineClient);

            gpuEngineAdapter.rebuildPatch(RebuildPatchRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setRegion(region)
                .setPatch(patchRect)
                .setTiles(tileGrid)
                .setHighways(highwayGrid)
                .addAppliedEdits(TileEdit.newBuilder().setX(patchX).setY(patchY).setType(1))
                .build());
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (gpuEngineClient != null) {
                gpuEngineClient.shutdown();
            }
        }
    }

    @Benchmark
    public void cpu_rebuild_patch(PatchState state, Blackhole blackhole) {
        int[][] patchTiles = new int[state.patchHeight][state.patchWidth];
        boolean[][] patchHighways = new boolean[state.patchHeight][state.patchWidth];
        for (int y = 0; y < state.patchHeight; y++) {
            System.arraycopy(state.tiles[state.patchY + y], state.patchX, patchTiles[y], 0, state.patchWidth);
            System.arraycopy(state.highways[state.patchY + y], state.patchX, patchHighways[y], 0, state.patchWidth);
        }
        blackhole.consume(patchTiles);
        blackhole.consume(patchHighways);
    }

    @Benchmark
    public void gpu_rebuild_patch(PatchState state, Blackhole blackhole) {
        blackhole.consume(state.gpuEngineAdapter.rebuildPatch(RebuildPatchRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setRegion(state.region)
            .setPatch(state.patchRect)
            .setTiles(state.tileGrid)
            .setHighways(state.highwayGrid)
            .addAppliedEdits(TileEdit.newBuilder().setX(state.patchX).setY(state.patchY).setType(1))
            .build()));
    }
}
