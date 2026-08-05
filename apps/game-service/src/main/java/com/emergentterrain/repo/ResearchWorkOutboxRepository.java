package com.emergentterrain.repo;

import com.emergentterrain.domain.ResearchWorkOutboxEntry;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResearchWorkOutboxRepository extends JpaRepository<ResearchWorkOutboxEntry, UUID> {

    List<ResearchWorkOutboxEntry> findTop50ByStateOrderByCreatedAtAsc(String state);

    @Modifying
    @Query(value = """
            INSERT INTO research_work_outbox
                (request_id, work_type, payload_json, state, delivery_attempts, created_at)
            VALUES (:requestId, :workType, :payloadJson, 'PENDING', 0, :createdAt)
            ON CONFLICT (request_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("requestId") UUID requestId,
                       @Param("workType") String workType,
                       @Param("payloadJson") String payloadJson,
                       @Param("createdAt") Instant createdAt);
}
