package com.emergentterrain.repo;

import com.emergentterrain.research.PaperCitation;
import com.emergentterrain.research.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaperCitationRepository extends JpaRepository<PaperCitation, UUID> {

    List<PaperCitation> findByCitingPaperId(String citingPaperId);

    List<PaperCitation> findByCitedPaperId(String citedPaperId);

    long countByCitedPaperId(String citedPaperId);

    List<PaperCitation> findByRoomId(UUID roomId);

    /** Phase 1 Iter 3 — find all edges of a given type for a room. */
    List<PaperCitation> findByRoomIdAndRelationshipType(UUID roomId, RelationshipType type);

    long countByCitedPaperIdAndRelationshipType(String citedPaperId, RelationshipType type);
}
