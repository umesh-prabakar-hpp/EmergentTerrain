package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyConjectureConsumptionAuditServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void appendsOneRestartIdempotentEventAfterAnObservedLegacyConsumption() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LegacyConjectureConsumptionAuditService service = new LegacyConjectureConsumptionAuditService(mapper, true,
                temporaryDirectory);
        UUID roomId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Researcher author = author();
        Conjecture conjecture = conjecture(sourceId);

        service.record(roomId, 44L, author, conjecture, "ORDINARY_QUEUE");
        service.record(roomId, 44L, author, conjecture, "ORDINARY_QUEUE");

        Path event;
        try (var events = Files.list(temporaryDirectory.resolve(roomId.toString()))) {
            var eventFiles = events.toList();
            assertThat(eventFiles).hasSize(1);
            event = eventFiles.getFirst();
        }
        var json = mapper.readTree(Files.readString(event));
        assertThat(json.path("roomId").asText()).isEqualTo(roomId.toString());
        assertThat(json.path("gameTick").asLong()).isEqualTo(44L);
        assertThat(json.path("sourceId").asText()).isEqualTo(sourceId.toString());
        assertThat(json.path("selectionKind").asText()).isEqualTo("ORDINARY_QUEUE");
        assertThat(json.path("sequence").asLong()).isEqualTo(1L);
        var window = service.windowForRoom(roomId);
        assertThat(window.epoch()).isNotBlank();
        assertThat(window.firstSequence()).isEqualTo(1L);
        assertThat(window.latestSequence()).isEqualTo(1L);
        assertThat(window.events()).hasSize(1);
    }

    @Test
    void remainsInertWhenDisabled() {
        LegacyConjectureConsumptionAuditService service = new LegacyConjectureConsumptionAuditService(
                new ObjectMapper(), false, temporaryDirectory);

        service.record(UUID.randomUUID(), 44L, author(), conjecture(UUID.randomUUID()), "PROMOTED_ANOMALY");

        assertThat(Files.exists(temporaryDirectory)).isTrue();
        assertThat(service.windowForRoom(UUID.randomUUID())).isNull();
        try (var entries = Files.list(temporaryDirectory)) {
            assertThat(entries.toList()).isEmpty();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void boundsTheContextWindowAndMakesARestartRequireANewAuditBaseline() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LegacyConjectureConsumptionAuditService service = new LegacyConjectureConsumptionAuditService(mapper, true,
                temporaryDirectory);
        UUID roomId = UUID.randomUUID();
        Researcher author = author();
        Conjecture conjecture = conjecture(UUID.randomUUID());

        for (long gameTick = 1L; gameTick <= 129L; gameTick++) {
            service.record(roomId, gameTick, author, conjecture, "ORDINARY_QUEUE");
        }

        var window = service.windowForRoom(roomId);
        assertThat(window.events()).hasSize(128);
        assertThat(window.firstSequence()).isEqualTo(2L);
        assertThat(window.latestSequence()).isEqualTo(129L);
        assertThat(window.events()).extracting(event -> event.sequence()).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(2L, 129L).boxed().toList());

        LegacyConjectureConsumptionAuditService restarted = new LegacyConjectureConsumptionAuditService(mapper, true,
                temporaryDirectory);
        var afterRestart = restarted.windowForRoom(roomId);
        assertThat(afterRestart.epoch()).isNotEqualTo(window.epoch());
        assertThat(afterRestart.events()).isEmpty();
        restarted.record(roomId, 129L, author, conjecture, "ORDINARY_QUEUE");
        assertThat(restarted.windowForRoom(roomId).events()).isEmpty();
    }

    private static Researcher author() {
        BotLifecycleState state = new BotLifecycleState();
        state.setGenome(new BotGenome("MATHEMATICS", "COMPUTATIONAL", new double[] {1, 1, 1, 1, 1}, 0.2, 1));
        return new Researcher(UUID.randomUUID().toString(), state);
    }

    private static Conjecture conjecture(UUID id) {
        Conjecture conjecture = new Conjecture("math", "lhs", "rhs", 1.0, 0.0);
        ReflectionTestUtils.setField(conjecture, "id", id);
        return conjecture;
    }
}
