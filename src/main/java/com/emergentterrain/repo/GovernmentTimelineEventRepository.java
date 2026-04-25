package com.emergentterrain.repo;

import com.emergentterrain.domain.GovernmentTimelineEvent;
import com.emergentterrain.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GovernmentTimelineEventRepository extends JpaRepository<GovernmentTimelineEvent, UUID> {
    List<GovernmentTimelineEvent> findByRoomAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(Room room, Instant occurredAt);
    List<GovernmentTimelineEvent> findByRoomAndKindAndReferenceIdIn(Room room, String kind, Collection<String> referenceIds);
    boolean existsByRoomAndKindAndReferenceIdAndPhase(Room room, String kind, String referenceId, String phase);
}
