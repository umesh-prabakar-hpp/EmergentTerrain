package com.emergentterrain.government;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomState;
import com.emergentterrain.web.GovernmentSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernmentSimulationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureInitializedBootstrapsGovernmentStateCivicStateAndHistory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SimulationHistoryWriter historyWriter = new SimulationHistoryWriter(objectMapper, tempDir.toString());
        GovernmentSimulationService service = new GovernmentSimulationService(
            objectMapper,
            historyWriter,
            true,
            0,
            50,
            10,
            4,
            new ClassPathResource("government/constitution-rules.json")
        );

        try {
            Room room = room("bootstrap-room");
            RoomState roomState = new RoomState();
            List<RoomBot> bots = bots(12, 2, 6, 1);
            int[][] tiles = tiles(24, 16, 1);
            Instant now = Instant.parse("2026-03-16T12:00:00Z");

            GovernmentSimulationService.GovernmentMutation mutation =
                service.ensureInitialized(room, roomState, bots, tiles, this::profileFor, now);

            assertThat(mutation.governmentState()).isNotNull();
            assertThat(mutation.governmentState().getConstitutionalRules()).hasSizeGreaterThanOrEqualTo(30);
            assertThat(mutation.summary().getConstitutionVersion()).isEqualTo("us-constitutional-phase1");
            assertThat(mutation.summary().getStateCount()).isGreaterThanOrEqualTo(1);
            assertThat(mutation.dirtyBots()).hasSize(bots.size());
            assertThat(service.readBotCivicState(mutation.dirtyBots().get(0).getCivicStateJson())).isNotNull();

            historyWriter.shutdown();
            assertThat(waitForFile(tempDir.resolve("constitution"))).isTrue();
        } finally {
            historyWriter.shutdown();
        }
    }

    @Test
    void repeatedTicksCreateBillsOrdersElectionsAndOfficeRoles() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SimulationHistoryWriter historyWriter = new SimulationHistoryWriter(objectMapper, tempDir.toString());
        GovernmentSimulationService service = new GovernmentSimulationService(
            objectMapper,
            historyWriter,
            true,
            250,
            1000,
            500,
            4,
            new ClassPathResource("government/constitution-rules.json")
        );

        try {
            Room room = room("tick-room");
            RoomState roomState = new RoomState();
            List<RoomBot> bots = bots(64, 0, 8, 2);
            int[][] tiles = tiles(32, 20, 2);
            Instant base = Instant.parse("2026-03-16T12:10:00Z");

            GovernmentSimulationService.GovernmentMutation init =
                service.ensureInitialized(room, roomState, bots, tiles, this::profileFor, base);
            GovernmentState seeded = init.governmentState();
            seeded.setNextElectionAt(base.plusMillis(2));
            for (RoomBot bot : bots) {
                BotCivicState civic = service.readBotCivicState(bot.getCivicStateJson());
                civic.setServiceAccess(0.18d);
                civic.setInstitutionalTrust(0.24d);
                civic.setRightsSatisfaction(0.28d);
                civic.setCivicParticipation(0.67d);
                bot.setCivicStateJson(objectMapper.writeValueAsString(civic));
            }
            GovernmentState.GovernmentBill bill = new GovernmentState.GovernmentBill();
            bill.setBillId("bill-seeded-001");
            bill.setTitle("Seeded Services Act");
            bill.setScope("federal");
            bill.setSponsorPartyId(seeded.getFederal().getLowerChamberPartyId());
            bill.setPolicyDomain("services");
            bill.setStage("DraftBill");
            bill.setLawStatus("IN_PROGRESS");
            bill.setConstitutionalBasis("Article I");
            bill.setSummary("Seeded bill to verify stage progression.");
            bill.setCreatedAt(base);
            bill.setUpdatedAt(base);
            seeded.getBills().add(0, bill);
            roomState.setGovernmentStateJson(service.serializeGovernmentState(init.governmentState()));
            for (RoomBot bot : init.dirtyBots()) {
                bot.setCivicStateJson(bot.getCivicStateJson());
            }

            GovernmentSimulationService.GovernmentMutation latest = init;
            for (int i = 1; i <= 12; i++) {
                latest = service.tick(
                    room,
                    roomState,
                    bots,
                    tiles,
                    this::profileFor,
                    base.plusMillis(i * 600L)
                );
                roomState.setGovernmentStateJson(service.serializeGovernmentState(latest.governmentState()));
            }

            GovernmentState governmentState = latest.governmentState();
            GovernmentSummary summary = latest.summary();
            assertThat(governmentState.getBills()).isNotEmpty();
            assertThat(governmentState.getExecutiveOrders()).isNotEmpty();
            assertThat(governmentState.getElections()).isNotEmpty();
            assertThat(summary.getActiveExecutiveOrderCount()).isGreaterThanOrEqualTo(1);
            assertThat(summary.getPartySummaries()).isNotEmpty();
            assertThat(bots)
                .map(bot -> service.readBotCivicState(bot.getCivicStateJson()))
                .anyMatch(civic -> civic != null && !"CITIZEN".equals(civic.getOfficeRole()));
        } finally {
            historyWriter.shutdown();
        }
    }

    @Test
    void judicialReviewBlocksStateRightsProcessBillAndWritesCourtHistory() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SimulationHistoryWriter historyWriter = new SimulationHistoryWriter(objectMapper, tempDir.toString());
        GovernmentSimulationService service = new GovernmentSimulationService(
            objectMapper,
            historyWriter,
            true,
            0,
            1000,
            1000,
            4,
            new ClassPathResource("government/constitution-rules.json")
        );

        try {
            Room room = room("court-room");
            RoomState roomState = new RoomState();
            List<RoomBot> bots = bots(24, 0, 6, 2);
            int[][] tiles = tiles(28, 18, 0);
            Instant base = Instant.parse("2026-03-16T12:20:00Z");

            GovernmentSimulationService.GovernmentMutation init =
                service.ensureInitialized(room, roomState, bots, tiles, this::profileFor, base);
            GovernmentState governmentState = init.governmentState();
            GovernmentState.GovernmentBill bill = new GovernmentState.GovernmentBill();
            bill.setBillId("bill-court-001");
            bill.setTitle("State Rights Compression Act");
            bill.setScope("state");
            bill.setStateId("state-1");
            bill.setSponsorPartyId("party-order-build");
            bill.setPolicyDomain("rights-process");
            bill.setStage("JudicialReviewOptional");
            bill.setLawStatus("IN_PROGRESS");
            bill.setConstitutionalBasis("Amendments I, V, XIV");
            bill.setSummary("A state bill that should be blocked by review.");
            bill.setCreatedAt(base);
            bill.setUpdatedAt(base);
            governmentState.getBills().add(0, bill);
            roomState.setGovernmentStateJson(service.serializeGovernmentState(governmentState));

            GovernmentSimulationService.GovernmentMutation reviewed = service.tick(
                room,
                roomState,
                bots,
                tiles,
                this::profileFor,
                base.plusMillis(300)
            );

            assertThat(reviewed.governmentState().getCourtCases()).isNotEmpty();
            assertThat(reviewed.governmentState().getBills().get(0).getLawStatus()).isEqualTo("BLOCKED");

            historyWriter.shutdown();
            assertThat(waitForFile(tempDir.resolve("courts"))).isTrue();
        } finally {
            historyWriter.shutdown();
        }
    }

    private Room room(String name) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", UUID.randomUUID());
        room.setName(name);
        return room;
    }

    private List<RoomBot> bots(int count, int tileXOffset, int width, int rowSpacing) {
        List<RoomBot> bots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RoomBot bot = new RoomBot();
            ReflectionTestUtils.setField(bot, "id", UUID.randomUUID());
            bot.setType("AGENT-" + i);
            bot.setX(tileXOffset + (i % width));
            bot.setY((i / width) * rowSpacing);
            bots.add(bot);
        }
        return bots;
    }

    private int[][] tiles(int width, int height, int tileType) {
        int[][] tiles = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = tileType;
            }
        }
        return tiles;
    }

    private Map<String, Double> profileFor(RoomBot bot) {
        int hash = Math.floorMod(bot.getType().hashCode(), 100);
        return Map.of(
            "clear", 0.45 + (hash % 10) * 0.01,
            "industry", 0.20 + (hash % 7) * 0.01,
            "consensus", 0.35 + (hash % 9) * 0.01,
            "mobility", 0.40 + (hash % 11) * 0.01
        );
    }

    private boolean waitForFile(Path directory) throws Exception {
        for (int i = 0; i < 20; i++) {
            if (Files.exists(directory)) {
                try (var stream = Files.list(directory)) {
                    if (stream.findAny().isPresent()) {
                        return true;
                    }
                }
            }
            Thread.sleep(50);
        }
        return false;
    }
}
