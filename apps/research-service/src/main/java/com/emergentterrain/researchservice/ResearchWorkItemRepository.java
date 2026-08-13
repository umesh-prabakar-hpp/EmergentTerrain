package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ResearchWorkItemRepository extends JpaRepository<ResearchWorkItem, UUID> {

    List<ResearchWorkItem> findTop20ByStateOrderByAcceptedAtAsc(String state);

    List<ResearchWorkItem> findTop20ByStateAndResultPublishedAtIsNullAndResultDeliveryRejectedAtIsNullOrderByCompletedAtAsc(String state);

    @Modifying
    @Query(value = """
            INSERT INTO research_work_items
                (request_id, workspace_id, work_type, source_revision, request_json, state, accepted_at)
            VALUES (:requestId, :workspaceId, :workType, :sourceRevision, :requestJson, 'QUEUED', :acceptedAt)
            ON CONFLICT (request_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("requestId") UUID requestId,
                       @Param("workspaceId") UUID workspaceId,
                       @Param("workType") String workType,
                       @Param("sourceRevision") long sourceRevision,
                       @Param("requestJson") String requestJson,
                       @Param("acceptedAt") Instant acceptedAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ResearchWorkItem item
            SET item.state = 'RUNNING', item.attempts = item.attempts + 1, item.claimedAt = :claimedAt
            WHERE item.requestId = :requestId AND item.state = 'QUEUED'
            """)
    int claimQueued(@Param("requestId") UUID requestId, @Param("claimedAt") Instant claimedAt);
}
