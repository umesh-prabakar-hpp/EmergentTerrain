package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Phase 1 Iter 3 PaperGraphService — read-side queries over
 * the typed paper citation graph. The repository is mocked so we control
 * exactly which edges the service sees.
 */
class PaperGraphServiceTest {

    private PaperCitationRepository repo;
    private PaperGraphService       service;
    private UUID                    room;

    @BeforeEach
    void setUp() {
        repo    = mock(PaperCitationRepository.class);
        service = new PaperGraphService(repo);
        room    = UUID.randomUUID();
    }

    @Test
    void citationsOfReturnsCitedPaperIdsForOutgoingEdges() {
        when(repo.findByCitingPaperId("P-1")).thenReturn(List.of(
                edge("P-1", "P-2", RelationshipType.EXTENDS),
                edge("P-1", "P-3", RelationshipType.SUPPORTS)));

        assertThat(service.citationsOf("P-1")).containsExactly("P-2", "P-3");
    }

    @Test
    void contradictedByFiltersToContradictsRelationships() {
        when(repo.findByCitedPaperId("P-target")).thenReturn(List.of(
                edge("P-a", "P-target", RelationshipType.EXTENDS),
                edge("P-b", "P-target", RelationshipType.CONTRADICTS),
                edge("P-c", "P-target", RelationshipType.SUPPORTS),
                edge("P-d", "P-target", RelationshipType.CONTRADICTS)));

        assertThat(service.contradictedBy("P-target"))
                .containsExactlyInAnyOrder("P-b", "P-d");
    }

    @Test
    void topCitedReturnsMostCitedPapersFirst() {
        // P-a has 3 incoming, P-b has 1, P-c has 2.
        when(repo.findByRoomId(room)).thenReturn(List.of(
                edge("P-x", "P-a", RelationshipType.EXTENDS),
                edge("P-y", "P-a", RelationshipType.EXTENDS),
                edge("P-z", "P-a", RelationshipType.EXTENDS),
                edge("P-x", "P-c", RelationshipType.EXTENDS),
                edge("P-y", "P-c", RelationshipType.EXTENDS),
                edge("P-x", "P-b", RelationshipType.EXTENDS)));

        assertThat(service.topCited(room, 5)).containsExactly("P-a", "P-c", "P-b");
        assertThat(service.topCited(room, 2)).containsExactly("P-a", "P-c");
    }

    @Test
    void contradictionClusterUnionsTransitivelyConnectedPapers() {
        // P-a ↔ P-b ↔ P-c (chain) plus a separate P-d ↔ P-e pair.
        when(repo.findByRoomIdAndRelationshipType(eq(room), eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of(
                        edge("P-a", "P-b", RelationshipType.CONTRADICTS),
                        edge("P-b", "P-c", RelationshipType.CONTRADICTS),
                        edge("P-d", "P-e", RelationshipType.CONTRADICTS)));

        List<PaperGraphService.ContradictionCluster> clusters =
                service.findContradictionClusters(room);

        assertThat(clusters).hasSize(2);
        // The chain cluster has 3 papers and 2 edges; the pair has 2 papers and 1 edge.
        // Larger cluster sorts first by edge count.
        assertThat(clusters.get(0).paperIds()).containsExactlyInAnyOrder("P-a", "P-b", "P-c");
        assertThat(clusters.get(0).edgeCount()).isEqualTo(2);
        assertThat(clusters.get(1).paperIds()).containsExactlyInAnyOrder("P-d", "P-e");
        assertThat(clusters.get(1).edgeCount()).isEqualTo(1);
    }

    @Test
    void contradictionClusterEmptyWhenNoContradicts() {
        when(repo.findByRoomIdAndRelationshipType(eq(room), eq(RelationshipType.CONTRADICTS)))
                .thenReturn(List.of());

        assertThat(service.findContradictionClusters(room)).isEmpty();
    }

    @Test
    void newPaperCitationDefaultsToExtendsType() {
        PaperCitation c = new PaperCitation("P-1", "P-2", room, Instant.now());
        assertThat(c.getRelationshipType()).isEqualTo(RelationshipType.EXTENDS);
        assertThat(c.getClassifiedByBotId()).isNull();
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void confidenceIsClampedToValidRange() {
        PaperCitation c = new PaperCitation("P-1", "P-2", room, Instant.now(),
                RelationshipType.SUPPORTS, UUID.randomUUID(), 1.5);
        assertThat(c.getConfidence()).isEqualTo(1.0);

        c.setConfidence(-0.5);
        assertThat(c.getConfidence()).isEqualTo(0.0);
    }

    private PaperCitation edge(String from, String to, RelationshipType type) {
        return new PaperCitation(from, to, room, Instant.now(), type, null, 1.0);
    }
}
