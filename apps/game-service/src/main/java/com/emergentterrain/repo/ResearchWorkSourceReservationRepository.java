package com.emergentterrain.repo;

import com.emergentterrain.domain.ResearchWorkSourceReservationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ResearchWorkSourceReservationRepository
        extends JpaRepository<ResearchWorkSourceReservationEntry, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO research_work_source_reservations
                (request_id, reservation_type, source_id, created_at)
            VALUES (:requestId, :reservationType, :sourceId, :createdAt)
            ON CONFLICT (request_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("requestId") UUID requestId,
                       @Param("reservationType") String reservationType,
                       @Param("sourceId") String sourceId,
                       @Param("createdAt") Instant createdAt);
}
