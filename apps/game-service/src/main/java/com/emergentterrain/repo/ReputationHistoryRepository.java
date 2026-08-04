package com.emergentterrain.repo;

import com.emergentterrain.research.ReputationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReputationHistoryRepository extends JpaRepository<ReputationHistory, UUID> {

    /** Bot's full reputation arc, oldest first. Used by ResearchIdentity views. */
    List<ReputationHistory> findByBotIdOrderByGameTickAsc(UUID botId);

    /** Room-wide trajectory for stats / dashboards. */
    List<ReputationHistory> findByRoomIdOrderByGameTickAsc(UUID roomId);
}
