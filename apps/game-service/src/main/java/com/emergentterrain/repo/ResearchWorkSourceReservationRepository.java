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

    /**
     * Claims exactly one game-owned reservation for source consumption.  Matching replays see
     * zero rows after the first claim, so they can revalidate the durable request without
     * stamping a live source again.
     */
    @Modifying
    @Query(value = """
            UPDATE research_work_source_reservations
               SET consumed_at = :consumedAt
             WHERE request_id = :requestId
               AND reservation_type = :reservationType
               AND source_id = :sourceId
               AND consumed_at IS NULL
            """, nativeQuery = true)
    int claimForConsumption(@Param("requestId") UUID requestId,
                            @Param("reservationType") String reservationType,
                            @Param("sourceId") String sourceId,
                            @Param("consumedAt") Instant consumedAt);
}
