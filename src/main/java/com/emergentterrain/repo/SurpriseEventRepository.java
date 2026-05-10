package com.emergentterrain.repo;

import com.emergentterrain.research.SurpriseEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SurpriseEventRepository extends JpaRepository<SurpriseEvent, UUID> {

    List<SurpriseEvent> findByPaperId(String paperId);

    List<SurpriseEvent> findByBotIdOrderByCreatedAtDesc(UUID botId);

    /**
     * High-surprise events for the room, most surprising first. Used by the
     * cross-bot broadcast and by Phase 2 Iter 5's contradiction-targeted
     * experiment planner.
     */
    List<SurpriseEvent> findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
            UUID roomId, double minScore);
}
