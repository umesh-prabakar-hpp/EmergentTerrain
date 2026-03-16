package com.emergentterrain.bench;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.government.GovernmentSimulationService;
import com.emergentterrain.government.GovernmentSimulationService.GovernmentMutation;
import com.emergentterrain.government.SimulationHistoryWriter;
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
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class GovernmentSimulationBenchmark {

    @State(Scope.Benchmark)
    public static class GovernmentStateData {
        @Param({"256", "2048"})
        private int botCount;

        private GovernmentSimulationService service;
        private SimulationHistoryWriter historyWriter;
        private Room room;
        private RoomState roomState;
        private List<RoomBot> bots;
        private int[][] tiles;
        private Instant baseTime;
        private long tickCounter;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            Path historyRoot = Files.createTempDirectory("gov-jmh-history");
            historyWriter = new SimulationHistoryWriter(objectMapper, historyRoot.toString());
            service = new GovernmentSimulationService(
                objectMapper,
                historyWriter,
                true,
                0,
                1_000_000,
                1_000_000,
                4,
                new ClassPathResource("government/constitution-rules.json")
            );
            room = new Room();
            setField(room, "id", UUID.randomUUID());
            room.setName("bench-room");
            roomState = new RoomState();
            bots = new ArrayList<>();
            int width = 80;
            int height = 60;
            tiles = new int[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    tiles[y][x] = (x + y) % 4;
                }
            }
            for (int i = 0; i < botCount; i++) {
                RoomBot bot = new RoomBot();
                setField(bot, "id", UUID.randomUUID());
                bot.setType("AGENT-" + i);
                bot.setX(i % width);
                bot.setY((i / width) % height);
                bots.add(bot);
            }
            baseTime = Instant.parse("2026-03-16T13:00:00Z");
            GovernmentMutation init = service.ensureInitialized(room, roomState, bots, tiles, this::profileFor, baseTime);
            roomState.setGovernmentStateJson(service.serializeGovernmentState(init.governmentState()));
            tickCounter = 0L;
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (historyWriter != null) {
                historyWriter.shutdown();
            }
        }

        private Map<String, Double> profileFor(RoomBot bot) {
            int hash = Math.floorMod(bot.getType().hashCode(), 100);
            return Map.of(
                "clear", 0.42 + (hash % 10) * 0.01,
                "industry", 0.18 + (hash % 7) * 0.01,
                "consensus", 0.33 + (hash % 9) * 0.01,
                "mobility", 0.39 + (hash % 11) * 0.01
            );
        }

        private void setField(Object target, String fieldName, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }
    }

    @Benchmark
    public void government_tick(GovernmentStateData state, Blackhole blackhole) {
        state.tickCounter++;
        GovernmentMutation mutation = state.service.tick(
            state.room,
            state.roomState,
            state.bots,
            state.tiles,
            state::profileFor,
            state.baseTime.plusMillis(state.tickCounter)
        );
        state.roomState.setGovernmentStateJson(state.service.serializeGovernmentState(mutation.governmentState()));
        blackhole.consume(mutation.summary());
    }
}
