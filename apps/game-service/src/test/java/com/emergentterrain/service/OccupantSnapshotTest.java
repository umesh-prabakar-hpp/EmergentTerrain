package com.emergentterrain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.5 carve: defensive coverage for {@link OccupantSnapshot} after its
 * promotion from a private nested class on {@link RoomService} to a top-level
 * package-private class. The persistence layer reads the JSON form via
 * {@code TypeReference&lt;List&lt;OccupantSnapshot&gt;&gt;} from
 * {@code RoomState.occupancyJson}, so a regression in the no-arg-constructor
 * + setter contract would silently break occupancy restore on next room load.
 */
class OccupantSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesToFlatJsonWithIdXY() throws Exception {
        OccupantSnapshot snap = new OccupantSnapshot("B:abc", 12, 34);

        String json = mapper.writeValueAsString(snap);

        assertThat(json).contains("\"id\":\"B:abc\"");
        assertThat(json).contains("\"x\":12");
        assertThat(json).contains("\"y\":34");
    }

    @Test
    void deserializesViaListTypeReferenceLikePersistencePath() throws Exception {
        String json = "[{\"id\":\"P:1\",\"x\":1,\"y\":2},{\"id\":\"B:7\",\"x\":-3,\"y\":99}]";

        List<OccupantSnapshot> parsed = mapper.readValue(json, new TypeReference<>() {});

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getId()).isEqualTo("P:1");
        assertThat(parsed.get(0).getX()).isEqualTo(1);
        assertThat(parsed.get(0).getY()).isEqualTo(2);
        assertThat(parsed.get(1).getId()).isEqualTo("B:7");
        assertThat(parsed.get(1).getX()).isEqualTo(-3);
        assertThat(parsed.get(1).getY()).isEqualTo(99);
    }

    @Test
    void noArgConstructorYieldsZeroedFields() {
        OccupantSnapshot snap = new OccupantSnapshot();

        assertThat(snap.getId()).isNull();
        assertThat(snap.getX()).isZero();
        assertThat(snap.getY()).isZero();
    }

    @Test
    void roundTripsThroughJacksonWithoutLoss() throws Exception {
        OccupantSnapshot original = new OccupantSnapshot("B:" + 0xdeadbeef, 47, -47);

        String json = mapper.writeValueAsString(original);
        OccupantSnapshot decoded = mapper.readValue(json, OccupantSnapshot.class);

        assertThat(decoded.getId()).isEqualTo(original.getId());
        assertThat(decoded.getX()).isEqualTo(original.getX());
        assertThat(decoded.getY()).isEqualTo(original.getY());
    }
}
