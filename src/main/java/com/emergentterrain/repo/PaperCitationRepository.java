package com.emergentterrain.repo;

import com.emergentterrain.research.PaperCitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaperCitationRepository extends JpaRepository<PaperCitation, UUID> {

    List<PaperCitation> findByCitingPaperId(String citingPaperId);

    List<PaperCitation> findByCitedPaperId(String citedPaperId);

    long countByCitedPaperId(String citedPaperId);

    List<PaperCitation> findByRoomId(UUID roomId);
}
