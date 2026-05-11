package com.emergentterrain.service;

import com.emergentterrain.domain.RoomProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the behaviour of {@link RoomProposalService} so the Phase 2.5 carve
 * (Phase 1 — codec + tile-math helpers) out of {@link RoomService} cannot
 * regress. Expected values mirror the inline implementation that lived in
 * RoomService before this extraction.
 */
class RoomProposalServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // Phase 1 tests only exercise the codec + tile-math surface, which has no
    // dependencies on the injected services / repos. Null is safe for them here;
    // Phase 2 + Phase 3 tests in sibling classes wire the dependencies properly.
    private final RoomProposalService svc = new RoomProposalService(
            objectMapper, null, null, null, null, null, null, null, null,
            false, 0.0, 3, 3, false, 300, 8, 0);

    // ---------- proposalDeltaType ----------

    @Test
    void proposalDeltaTypeDecodesNumericType() {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson("{\"x\":1,\"y\":2,\"type\":3}");
        assertThat(svc.proposalDeltaType(p)).isEqualTo(3);
    }

    @Test
    void proposalDeltaTypeDecodesAllCanonicalStringNames() {
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"forest\"}"))).isEqualTo(1);
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"water\"}"))).isEqualTo(2);
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"industry\"}"))).isEqualTo(3);
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"highway\"}"))).isEqualTo(4);
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"clear\"}"))).isEqualTo(0);
    }

    @Test
    void proposalDeltaTypeReturnsNullForUnknownStringMissingFieldAndBadJson() {
        assertThat(svc.proposalDeltaType(deltaProposal("{\"type\":\"hovercraft\"}"))).isNull();
        assertThat(svc.proposalDeltaType(deltaProposal("{}"))).isNull();
        assertThat(svc.proposalDeltaType(deltaProposal("not-json"))).isNull();
        assertThat(svc.proposalDeltaType(deltaProposal(null))).isNull();
        assertThat(svc.proposalDeltaType(deltaProposal(""))).isNull();
    }

    // ---------- readBotVoteSummary ----------

    @Test
    void readBotVoteSummaryRoundTrips() {
        RoomProposal p = new RoomProposal();
        p.setBotVoteSummaryJson("{\"APPROVE\":3,\"REJECT\":1}");
        Map<String, Integer> summary = svc.readBotVoteSummary(p);
        assertThat(summary).containsEntry("APPROVE", 3).containsEntry("REJECT", 1);
    }

    @Test
    void readBotVoteSummaryReturnsNullForMissingBlankOrBadJson() {
        RoomProposal p = new RoomProposal();
        assertThat(svc.readBotVoteSummary(p)).isNull();
        p.setBotVoteSummaryJson("");
        assertThat(svc.readBotVoteSummary(p)).isNull();
        p.setBotVoteSummaryJson("not-json");
        assertThat(svc.readBotVoteSummary(p)).isNull();
    }

    // ---------- readNeighborIds ----------

    @Test
    void readNeighborIdsRoundTrips() {
        RoomProposal p = new RoomProposal();
        p.setNeighborIdsJson("[\"P:1\",\"B:2\",\"P:3\"]");
        List<String> ids = svc.readNeighborIds(p);
        assertThat(ids).containsExactly("P:1", "B:2", "P:3");
    }

    @Test
    void readNeighborIdsReturnsEmptyListForMissingBlankOrBadJson() {
        RoomProposal p = new RoomProposal();
        assertThat(svc.readNeighborIds(p)).isEmpty();
        p.setNeighborIdsJson("");
        assertThat(svc.readNeighborIds(p)).isEmpty();
        p.setNeighborIdsJson("not-json");
        assertThat(svc.readNeighborIds(p)).isEmpty();
    }

    // ---------- proposalAnchor + extractAnchor ----------

    @Test
    void proposalAnchorPrefersPersistedFields() {
        RoomProposal p = new RoomProposal();
        p.setAnchorX(7);
        p.setAnchorY(9);
        p.setDeltaJson("{\"x\":0,\"y\":0,\"type\":1}");  // would be ignored
        assertThat(svc.proposalAnchor(p)).containsExactly(7, 9);
    }

    @Test
    void proposalAnchorFallsBackToDeltaJsonWhenPersistedNull() {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson("{\"x\":4,\"y\":5,\"type\":2}");
        assertThat(svc.proposalAnchor(p)).containsExactly(4, 5);
    }

    @Test
    void proposalAnchorReturnsNullWhenNoUsableCoordsAnywhere() {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson("{\"type\":2}");
        assertThat(svc.proposalAnchor(p)).isNull();
    }

    @Test
    void extractAnchorAcceptsRawMapAndJsonString() {
        // Map form — used by the propose() HTTP entry point on the raw request.
        Map<String, Object> map = Map.of("x", 3, "y", 4, "type", "forest");
        assertThat(svc.extractAnchor(map)).containsExactly(3, 4);
        // JSON string form — used by proposalAnchor() when persisted fields absent.
        assertThat(svc.extractAnchor("{\"x\":11,\"y\":12,\"type\":2}")).containsExactly(11, 12);
        // Null / blank / no coords → null.
        assertThat(svc.extractAnchor(null)).isNull();
        assertThat(svc.extractAnchor("")).isNull();
        assertThat(svc.extractAnchor(Map.of("type", "forest"))).isNull();
    }

    // ---------- extractDeltaType ----------

    @Test
    void extractDeltaTypeReturnsMinusOneForMissingAndUnknown() {
        assertThat(svc.extractDeltaType(null)).isEqualTo(-1);
        assertThat(svc.extractDeltaType(Map.of())).isEqualTo(-1);
        assertThat(svc.extractDeltaType(Map.of("type", "spaceship"))).isEqualTo(-1);
        assertThat(svc.extractDeltaType("not-a-map")).isEqualTo(-1);
    }

    @Test
    void extractDeltaTypeDecodesNumericAndCanonicalStrings() {
        assertThat(svc.extractDeltaType(Map.of("type", 3))).isEqualTo(3);
        assertThat(svc.extractDeltaType(Map.of("type", "water"))).isEqualTo(2);
        assertThat(svc.extractDeltaType(Map.of("type", "highway"))).isEqualTo(4);
        assertThat(svc.extractDeltaType(Map.of("type", "clear"))).isEqualTo(0);
    }

    @Test
    void extractDeltaTypeFromProposalParsesDeltaJson() {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson("{\"x\":1,\"y\":2,\"type\":3}");
        assertThat(svc.extractDeltaTypeFromProposal(p)).isEqualTo(3);

        RoomProposal blank = new RoomProposal();
        assertThat(svc.extractDeltaTypeFromProposal(blank)).isEqualTo(-1);

        RoomProposal bad = new RoomProposal();
        bad.setDeltaJson("not-json");
        assertThat(svc.extractDeltaTypeFromProposal(bad)).isEqualTo(-1);
    }

    // ---------- applyBotChange + applyDeltaToTiles ----------

    @Test
    void applyBotChangeCopiesAndWritesSingleTile() {
        int[][] base = { {0, 1}, {2, 3} };
        int[][] out = svc.applyBotChange(base, 1, 0, 4);

        // Returned array is a fresh copy mutated only at the target tile.
        assertThat(out).isNotSameAs(base);
        assertThat(out[0]).containsExactly(0, 4);
        assertThat(out[1]).containsExactly(2, 3);
        assertThat(base[0]).containsExactly(0, 1);   // input untouched
    }

    @Test
    void applyDeltaToTilesAppliesTheAnchorOrCopiesIfInvalid() {
        int[][] base = { {0, 0}, {0, 0} };
        RoomProposal p = new RoomProposal();
        p.setAnchorX(1);
        p.setAnchorY(1);
        p.setDeltaJson("{\"type\":2}");

        int[][] out = svc.applyDeltaToTiles(base, p);

        assertThat(out[1]).containsExactly(0, 2);
        assertThat(out[0]).containsExactly(0, 0);

        // Invalid delta → verbatim copy (no anchor or no type known).
        RoomProposal bad = new RoomProposal();
        int[][] copy = svc.applyDeltaToTiles(base, bad);
        assertThat(copy).isNotSameAs(base);
        assertThat(copy[0]).containsExactly(0, 0);
        assertThat(copy[1]).containsExactly(0, 0);
    }

    // ---------- findChangeType ----------

    @Test
    void findChangeTypeReturnsNullForIdenticalGrids() {
        int[][] a = { {0, 0}, {0, 0} };
        int[][] b = { {0, 0}, {0, 0} };
        assertThat(svc.findChangeType(a, b)).isNull();
    }

    @Test
    void findChangeTypeReturnsFirstDifferenceInRowMajorOrder() {
        int[][] a = { {0, 0}, {0, 0} };
        int[][] b = { {0, 2}, {3, 0} };
        // Row-major scan finds (1, 0) before (0, 1).
        assertThat(svc.findChangeType(a, b)).containsExactly(1, 0, 2);
    }

    // ---------- validateProposalChange ----------

    @Test
    void validateProposalChangeAcceptsExactlyOneTileSameType() {
        int[][] a = { {0, 0}, {0, 0} };
        int[][] b = { {0, 1}, {0, 0} };
        svc.validateProposalChange(a, b);   // no throw
    }

    @Test
    void validateProposalChangeRejectsShapeMismatch() {
        int[][] a = { {0, 0}, {0, 0} };
        int[][] b = { {0, 0, 0}, {0, 0, 0} };
        assertThatThrownBy(() -> svc.validateProposalChange(a, b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid change shape");
    }

    @Test
    void validateProposalChangeRejectsZeroOrMultipleChanges() {
        int[][] a = { {0, 0}, {0, 0} };

        assertThatThrownBy(() -> svc.validateProposalChange(a, a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1x1 tile");

        int[][] two = { {1, 0}, {0, 1} };
        assertThatThrownBy(() -> svc.validateProposalChange(a, two))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1x1 tile");
    }

    // ---------- validateProposalDelta ----------

    @Test
    void validateProposalDeltaAcceptsInboundsKnownType() {
        int[][] base = { {0, 0}, {0, 0} };
        svc.validateProposalDelta(base, new int[] { 1, 1 }, Map.of("type", 3));
    }

    @Test
    void validateProposalDeltaRejectsNullsAndOutOfBoundsAndBadType() {
        int[][] base = { {0, 0}, {0, 0} };

        assertThatThrownBy(() -> svc.validateProposalDelta(base, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tiles");

        assertThatThrownBy(() -> svc.validateProposalDelta(base, new int[] { -1, 0 }, Map.of("type", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of bounds");

        assertThatThrownBy(() -> svc.validateProposalDelta(base, new int[] { 0, 99 }, Map.of("type", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of bounds");

        assertThatThrownBy(() -> svc.validateProposalDelta(base, new int[] { 0, 0 }, Map.of("type", 99)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid tile type");
    }

    // ---------- helpers ----------

    private static RoomProposal deltaProposal(String deltaJson) {
        RoomProposal p = new RoomProposal();
        p.setDeltaJson(deltaJson);
        return p;
    }
}
