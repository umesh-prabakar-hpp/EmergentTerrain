package com.emergentterrain.bench;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.GpuEngineClient;
import com.emergentterrain.gpu.GpuEngineCodec;
import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.SimulateBotsRequest;
import com.emergentterrain.gpu.v1.SimulateBotsResponse;
import com.emergentterrain.gpu.v1.TileGrid;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class GpuVsCpuBotSimulationBenchmark {

    @State(Scope.Benchmark)
    public static class BotStateData {
        private static final int WIDTH = 256;
        private static final int HEIGHT = 192;
        private static final int BOT_COUNT = 2000;
        private static final int STEPS = 10;

        private int[][] tiles;
        private boolean[][] highways;
        private int[] botX;
        private int[] botY;
        private double[] botForest;
        private double[] botWater;
        private double[] botIndustry;
        private double[] botClear;
        private double[] botMobility;

        private TileGrid tileGrid;
        private com.emergentterrain.gpu.v1.BoolGrid highwayGrid;
        private GpuEngineClient gpuEngineClient;
        private GpuEngineAdapter gpuEngineAdapter;
        private BotState[] gpuBots;
        private String worldId;

        @Setup(Level.Trial)
        public void setup() {
            Random rand = new Random(42);
            tiles = new int[HEIGHT][WIDTH];
            highways = new boolean[HEIGHT][WIDTH];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int v = rand.nextInt(100);
                    if (v < 55) tiles[y][x] = 1; // forest
                    else if (v < 70) tiles[y][x] = 2; // water
                    else if (v < 80) tiles[y][x] = 3; // industry
                    else tiles[y][x] = 0; // clear
                    highways[y][x] = (x % 12 == 0 || y % 12 == 0);
                }
            }

            botX = new int[BOT_COUNT];
            botY = new int[BOT_COUNT];
            botForest = new double[BOT_COUNT];
            botWater = new double[BOT_COUNT];
            botIndustry = new double[BOT_COUNT];
            botClear = new double[BOT_COUNT];
            botMobility = new double[BOT_COUNT];
            gpuBots = new BotState[BOT_COUNT];
            for (int i = 0; i < BOT_COUNT; i++) {
                botX[i] = rand.nextInt(WIDTH);
                botY[i] = rand.nextInt(HEIGHT);
                botForest[i] = 0.2 + rand.nextDouble() * 0.6;
                botWater[i] = 0.1 + rand.nextDouble() * 0.6;
                botIndustry[i] = 0.05 + rand.nextDouble() * 0.5;
                botClear[i] = 0.2 + rand.nextDouble() * 0.6;
                botMobility[i] = 0.3 + rand.nextDouble() * 0.6;
                gpuBots[i] = BotState.newBuilder()
                    .setId("bot-" + i)
                    .setX(botX[i])
                    .setY(botY[i])
                    .setForest((float) botForest[i])
                    .setWater((float) botWater[i])
                    .setIndustry((float) botIndustry[i])
                    .setClear((float) botClear[i])
                    .setMobility((float) botMobility[i])
                    .build();
            }

            tileGrid = GpuEngineCodec.toTileGrid(tiles);
            highwayGrid = GpuEngineCodec.toBoolGrid(highways, WIDTH, HEIGHT);

            String host = System.getProperty("gpu.host",
                System.getenv().getOrDefault("GPU_ENGINE_HOST", "127.0.0.1"));
            int port = Integer.parseInt(System.getProperty("gpu.port",
                System.getenv().getOrDefault("GPU_ENGINE_PORT", "50051")));
            gpuEngineClient = new GpuEngineClient(host, port, false);
            gpuEngineAdapter = new GpuEngineAdapter(gpuEngineClient);

            worldId = UUID.randomUUID().toString();
            primeGpuWorld();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (gpuEngineClient != null) {
                gpuEngineClient.shutdown();
            }
        }

        private void primeGpuWorld() {
            try {
                SimulateBotsRequest request = SimulateBotsRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setWorldId(worldId)
                    .setTiles(tileGrid)
                    .setHighways(highwayGrid)
                    .setSteps(1)
                    .addBots(BotState.newBuilder().setId("probe").setX(0).setY(0))
                    .build();
                gpuEngineAdapter.simulateBots(request);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "GPU engine is not reachable. Start the Rust GPU engine before running this benchmark. " +
                    "Host/port can be set via GPU_ENGINE_HOST/GPU_ENGINE_PORT or -Dgpu.host/-Dgpu.port.",
                    e
                );
            }
        }
    }

    @Benchmark
    public void cpu_simulate_bots(BotStateData state, Blackhole blackhole) {
        int[] currX = Arrays.copyOf(state.botX, state.botX.length);
        int[] currY = Arrays.copyOf(state.botY, state.botY.length);
        Random rand = new Random(123);
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        for (int step = 0; step < BotStateData.STEPS; step++) {
            int[] nextX = new int[state.botX.length];
            int[] nextY = new int[state.botY.length];
            for (int i = 0; i < currX.length; i++) {
                int x = currX[i];
                int y = currY[i];
                if (rand.nextDouble() > state.botMobility[i]) {
                    nextX[i] = x;
                    nextY[i] = y;
                    continue;
                }
                int bestX = x;
                int bestY = y;
                double bestScore = -Double.MAX_VALUE;
                for (int[] d : dirs) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= BotStateData.WIDTH || ny >= BotStateData.HEIGHT) {
                        continue;
                    }
                    int type = state.tiles[ny][nx];
                    double score = preferenceScore(type, state, i);
                    if (state.highways[ny][nx] || type == 4) {
                        score += 0.2;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = nx;
                        bestY = ny;
                    }
                }
                nextX[i] = bestX;
                nextY[i] = bestY;
            }
            currX = nextX;
            currY = nextY;
        }
        blackhole.consume(currX);
        blackhole.consume(currY);
    }

    @Benchmark
    public void gpu_simulate_bots(BotStateData state, Blackhole blackhole) {
        SimulateBotsRequest.Builder builder = SimulateBotsRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setWorldId(state.worldId)
            .setSteps(BotStateData.STEPS);
        for (BotState bot : state.gpuBots) {
            builder.addBots(bot);
        }
        SimulateBotsResponse response = state.gpuEngineAdapter.simulateBots(builder.build());
        blackhole.consume(response);
    }

    private static double preferenceScore(int type, BotStateData state, int idx) {
        switch (type) {
            case 1:
                return state.botForest[idx];
            case 2:
                return state.botWater[idx];
            case 3:
                return state.botIndustry[idx];
            case 4:
                return state.botClear[idx];
            default:
                return state.botClear[idx];
        }
    }
}
