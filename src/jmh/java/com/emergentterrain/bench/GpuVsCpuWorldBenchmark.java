package com.emergentterrain.bench;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.GpuEngineClient;
import com.emergentterrain.gpu.v1.GenerateRegionRequest;
import com.emergentterrain.gpu.v1.Region;
import com.emergentterrain.service.HiddenProperties;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.WorldService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class GpuVsCpuWorldBenchmark {

    @State(Scope.Benchmark)
    public static class WorldState {
        @Param({"RPC", "HEAVY"})
        private String profile;

        private int width;
        private int height;
        private int maxTiles;
        private double aspect;
        private double highwayRatio;

        private Payload payload;
        private long seed;
        private WorldService cpuWorldService;
        private WorldService gpuWorldService;
        private GpuEngineClient gpuEngineClient;

        @Setup(Level.Trial)
        public void setup() {
            if ("RPC".equalsIgnoreCase(profile)) {
                width = 64;
                height = 48;
                maxTiles = 40000;
            } else {
                width = 320;
                height = 240;
                maxTiles = 200000;
            }
            aspect = 1.3333;
            highwayRatio = 0.02;
            HiddenProperties hidden = new HiddenProperties(0.45, 0.55, 0.6, 0.5, 0.5, 0.4);
            payload = new Payload(
                1,
                UUID.randomUUID().toString(),
                30,
                "N",
                "Bench",
                Map.of("likes_forests", true, "likes_open_fields", true),
                hidden,
                "bench"
            );
            seed = 123456789L;
            ObjectMapper objectMapper = new ObjectMapper();

            cpuWorldService = new WorldService(
                width,
                height,
                false,
                maxTiles,
                aspect,
                highwayRatio,
                0,
                1,
                false,
                false,
                0,
                objectMapper,
                null
            );

            String host = System.getProperty("gpu.host",
                System.getenv().getOrDefault("GPU_ENGINE_HOST", "127.0.0.1"));
            int port = Integer.parseInt(System.getProperty("gpu.port",
                System.getenv().getOrDefault("GPU_ENGINE_PORT", "50051")));
            gpuEngineClient = new GpuEngineClient(host, port, false);
            GpuEngineAdapter gpuEngineAdapter = new GpuEngineAdapter(gpuEngineClient);
            gpuWorldService = new WorldService(
                width,
                height,
                false,
                maxTiles,
                aspect,
                highwayRatio,
                0,
                1,
                true,
                true,
                0,
                objectMapper,
                gpuEngineAdapter
            );

            assertGpuAvailable(gpuEngineAdapter, seed);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (gpuEngineClient != null) {
                gpuEngineClient.shutdown();
            }
        }

        private void assertGpuAvailable(GpuEngineAdapter adapter, long seed) {
            try {
                GenerateRegionRequest request = GenerateRegionRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    .setSeed(seed)
                    .setRegion(Region.newBuilder().setX(0).setY(0).setWidth(1).setHeight(1))
                    .build();
                adapter.generateRegion(request);
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
    public void cpu_generate_world(WorldState state, Blackhole blackhole) {
        blackhole.consume(state.cpuWorldService.generateWorld(state.payload, state.seed));
    }

    @Benchmark
    public void gpu_generate_world(WorldState state, Blackhole blackhole) {
        blackhole.consume(state.gpuWorldService.generateWorld(state.payload, state.seed));
    }
}
