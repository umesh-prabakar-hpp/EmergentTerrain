package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomParticipant;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomParticipantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of {@link OccupancyService} so the Phase 2.5 carve out
 * of {@link RoomService} cannot regress. Expected values mirror the inline
 * implementation that lived in RoomService before this extraction.
 */
class OccupancyServiceTest {

    private final RoomParticipantRepository participantRepo = mock(RoomParticipantRepository.class);
    private final RoomBotRepository botRepo = mock(RoomBotRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OccupancyService svc = new OccupancyService(participantRepo, botRepo, objectMapper);

    // ---------- viewport math ----------

    @Test
    void visibleConstantsExposeKnownDimensions() {
        // VISIBLE_WIDTH=48, VISIBLE_HEIGHT=36 carried over from the pre-carve
        // RoomService inline implementation. Pinning so future tuning is
        // visible at PR time.
        assertThat(svc.visibleWidth()).isEqualTo(48);
        assertThat(svc.visibleHeight()).isEqualTo(36);
    }

    @Test
    void computeViewBoundsShrinksWindowWhenWorldSmaller() {
        int[][] tiles = new int[10][20];   // 20×10 world
        int[] view = svc.computeViewBounds(5, 5, tiles);
        // World smaller than the 48×36 window in both axes → window collapses
        // to world dimensions with origin at (0,0).
        assertThat(view).containsExactly(0, 0, 20, 10);
    }

    @Test
    void computeViewBoundsCentresWindowAndClampsToWorld() {
        int[][] tiles = new int[100][100];
        // Centre well inside the world: window centres on (cx, cy).
        int[] center = svc.computeViewBounds(60, 50, tiles);
        assertThat(center).containsExactly(60 - 48 / 2, 50 - 36 / 2, 48, 36);
        // Top-left corner: window clamps to origin.
        int[] tl = svc.computeViewBounds(0, 0, tiles);
        assertThat(tl).containsExactly(0, 0, 48, 36);
        // Bottom-right corner: window clamps to the world edge.
        int[] br = svc.computeViewBounds(99, 99, tiles);
        assertThat(br).containsExactly(100 - 48, 100 - 36, 48, 36);
    }

    @Test
    void inViewIsHalfOpenOnTheRightAndBottom() {
        assertThat(svc.inView(5, 5, 10, 10, 5, 5)).isTrue();    // top-left corner included
        assertThat(svc.inView(5, 5, 10, 10, 14, 14)).isTrue();  // last cell included
        assertThat(svc.inView(5, 5, 10, 10, 15, 14)).isFalse(); // right edge excluded
        assertThat(svc.inView(5, 5, 10, 10, 14, 15)).isFalse(); // bottom edge excluded
        assertThat(svc.inView(5, 5, 10, 10, 4, 7)).isFalse();   // left of viewport
        assertThat(svc.inView(5, 5, 10, 10, 7, 4)).isFalse();   // above viewport
    }

    @Test
    void withinRadiusIsEuclideanAndInclusive() {
        assertThat(svc.withinRadius(0, 0, 0, 0, 0)).isTrue();   // self
        assertThat(svc.withinRadius(0, 0, 3, 4, 5)).isTrue();   // 3² + 4² = 25 = 5²
        assertThat(svc.withinRadius(0, 0, 3, 4, 4)).isFalse();  // 25 > 16
        assertThat(svc.withinRadius(10, 10, 13, 14, 5)).isTrue();
    }

    // ---------- canOccupy ----------

    @Test
    void canOccupyAllowsWhenAreaIsEmpty() {
        assertThat(svc.canOccupy(5, 5, "", Collections.emptyList(), 4, 2)).isTrue();
    }

    @Test
    void canOccupyBlocksWhenDensityReachesMax() {
        // densityAreaSize=4, half=2 → box spans [3..4]×[3..4] at anchor (5,5).
        // maxOccupants=2 → 2 entries inside is the cut-off (count < max).
        List<OccupantSnapshot> occs = Arrays.asList(
                new OccupantSnapshot("B:other-1", 4, 4),
                new OccupantSnapshot("B:other-2", 3, 3)
        );
        assertThat(svc.canOccupy(5, 5, "", occs, 4, 2)).isFalse();  // count == max
        assertThat(svc.canOccupy(5, 5, "", occs, 4, 3)).isTrue();   // count < max
    }

    @Test
    void canOccupyIgnoresOccupantsOutsideTheBox() {
        // Box at anchor (5,5) with densityAreaSize=4 spans [3..4]×[3..4].
        // (10, 10) is well outside and must not count.
        List<OccupantSnapshot> occs = Arrays.asList(
                new OccupantSnapshot("B:far", 10, 10),
                new OccupantSnapshot("B:near", 4, 4)
        );
        assertThat(svc.canOccupy(5, 5, "", occs, 4, 2)).isTrue();   // only 1 in box
    }

    @Test
    void canOccupySkipsSelfByExactIdMatch() {
        List<OccupantSnapshot> occs = Arrays.asList(
                new OccupantSnapshot("B:self-id", 4, 4),
                new OccupantSnapshot("B:other", 4, 4)
        );
        // With ignoreId matching the self entry, only the "B:other" counts.
        assertThat(svc.canOccupy(5, 5, "B:self-id", occs, 4, 2)).isTrue();
    }

    @Test
    void canOccupySkipsSelfByEndsWithMatch() {
        // RoomService callers pass bare UUIDs as ignoreId while the snapshot
        // entries carry the "B:" or "P:" prefix, so endsWith is the actual
        // match path for self-skipping. Pinning this branch keeps the
        // self-blocker-immunity rule for bots and participants.
        List<OccupantSnapshot> occs = Arrays.asList(
                new OccupantSnapshot("B:abc-123-def", 4, 4)
        );
        assertThat(svc.canOccupy(5, 5, "abc-123-def", occs, 4, 1)).isTrue();
    }

    @Test
    void canOccupyToleratesNullOccupants() {
        List<OccupantSnapshot> occs = Arrays.asList(null, new OccupantSnapshot("B:x", 4, 4));
        assertThat(svc.canOccupy(5, 5, "", occs, 4, 2)).isTrue();
    }

    @Test
    void canOccupyIgnoresBlankIgnoreIdAndCountsAllInBox() {
        List<OccupantSnapshot> occs = Arrays.asList(
                new OccupantSnapshot("B:a", 4, 4),
                new OccupantSnapshot("B:b", 3, 3)
        );
        assertThat(svc.canOccupy(5, 5, "", occs, 4, 2)).isFalse();   // both count
        assertThat(svc.canOccupy(5, 5, null, occs, 4, 2)).isFalse(); // null ignored same as blank
    }

    // ---------- buildOccupancySnapshot ----------

    @Test
    void buildOccupancySnapshotEmitsParticipantsBeforeBots() {
        Room room = new Room();
        RoomParticipant p1 = participant(UUID.randomUUID(), 1, 2);
        RoomBot b1 = bot(UUID.randomUUID(), 3, 4);
        when(participantRepo.findByRoomAndActiveTrueOrderByJoinedAt(any())).thenReturn(List.of(p1));
        when(botRepo.findByRoom(any())).thenReturn(List.of(b1));

        List<OccupantSnapshot> result = svc.buildOccupancySnapshot(room);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).startsWith("P:");
        assertThat(result.get(0).getX()).isEqualTo(1);
        assertThat(result.get(0).getY()).isEqualTo(2);
        assertThat(result.get(1).getId()).startsWith("B:");
        assertThat(result.get(1).getX()).isEqualTo(3);
        assertThat(result.get(1).getY()).isEqualTo(4);
    }

    @Test
    void buildOccupancySnapshotSkipsEntriesWithMissingOrNegativeCoords() {
        Room room = new Room();
        RoomParticipant pNullCoord = participant(UUID.randomUUID(), null, 5);
        RoomParticipant pNegative = participant(UUID.randomUUID(), -1, 5);
        RoomParticipant pOk = participant(UUID.randomUUID(), 0, 0);
        RoomBot bNullId = bot(null, 1, 1);
        when(participantRepo.findByRoomAndActiveTrueOrderByJoinedAt(any()))
                .thenReturn(List.of(pNullCoord, pNegative, pOk));
        when(botRepo.findByRoom(any())).thenReturn(List.of(bNullId));

        List<OccupantSnapshot> result = svc.buildOccupancySnapshot(room);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).startsWith("P:");
        assertThat(result.get(0).getX()).isEqualTo(0);
        assertThat(result.get(0).getY()).isEqualTo(0);
    }

    // ---------- readOccupancyJson ----------

    @Test
    void readOccupancyJsonReturnsEmptyListOnNullBlankOrBadJson() {
        assertThat(svc.readOccupancyJson(null)).isEmpty();
        assertThat(svc.readOccupancyJson("")).isEmpty();
        assertThat(svc.readOccupancyJson("   ")).isEmpty();
        assertThat(svc.readOccupancyJson("{not-an-array")).isEmpty();
    }

    @Test
    void readOccupancyJsonRoundTripsOccupants() {
        String json = "[{\"id\":\"P:abc\",\"x\":7,\"y\":9},{\"id\":\"B:def\",\"x\":12,\"y\":3}]";

        List<OccupantSnapshot> parsed = svc.readOccupancyJson(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getId()).isEqualTo("P:abc");
        assertThat(parsed.get(0).getX()).isEqualTo(7);
        assertThat(parsed.get(0).getY()).isEqualTo(9);
        assertThat(parsed.get(1).getId()).isEqualTo("B:def");
        assertThat(parsed.get(1).getX()).isEqualTo(12);
        assertThat(parsed.get(1).getY()).isEqualTo(3);
    }

    // ---------- helpers ----------

    private static RoomParticipant participant(UUID id, Integer x, Integer y) {
        RoomParticipant p = mock(RoomParticipant.class);
        when(p.getId()).thenReturn(id);
        when(p.getX()).thenReturn(x);
        when(p.getY()).thenReturn(y);
        return p;
    }

    private static RoomBot bot(UUID id, Integer x, Integer y) {
        RoomBot b = mock(RoomBot.class);
        when(b.getId()).thenReturn(id);
        when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(y);
        return b;
    }
}
