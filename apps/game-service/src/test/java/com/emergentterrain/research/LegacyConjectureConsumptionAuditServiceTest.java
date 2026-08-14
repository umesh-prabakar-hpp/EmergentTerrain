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
    }

    @Test
    void remainsInertWhenDisabled() {
        LegacyConjectureConsumptionAuditService service = new LegacyConjectureConsumptionAuditService(
                new ObjectMapper(), false, temporaryDirectory);

        service.record(UUID.randomUUID(), 44L, author(), conjecture(UUID.randomUUID()), "PROMOTED_ANOMALY");

        assertThat(Files.exists(temporaryDirectory)).isTrue();
        try (var entries = Files.list(temporaryDirectory)) {
            assertThat(entries.toList()).isEmpty();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
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
