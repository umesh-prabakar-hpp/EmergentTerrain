package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ResearchWorkItemRepository extends JpaRepository<ResearchWorkItem, UUID> {

    List<ResearchWorkItem> findTop20ByStateOrderByAcceptedAtAsc(String state);

    @Query("""
            SELECT item FROM ResearchWorkItem item
            WHERE item.state = :state
              AND item.resultPublishedAt IS NULL
              AND item.resultDeliveryRejectedAt IS NULL
              AND (item.resultDeliveryClaimedAt IS NULL OR item.resultDeliveryClaimedAt < :staleBefore)
            ORDER BY item.completedAt ASC
            """)
    List<ResearchWorkItem> findResultDeliveryCandidates(@Param("state") String state,
                                                         @Param("staleBefore") Instant staleBefore,
                                                         Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ResearchWorkItem item
            SET item.resultDeliveryClaimedAt = :claimedAt
            WHERE item.requestId = :requestId
              AND item.state = 'COMPLETED'
              AND item.resultPublishedAt IS NULL
              AND item.resultDeliveryRejectedAt IS NULL
              AND (item.resultDeliveryClaimedAt IS NULL OR item.resultDeliveryClaimedAt < :staleBefore)
            """)
    int claimResultDelivery(@Param("requestId") UUID requestId, @Param("claimedAt") Instant claimedAt,
                            @Param("staleBefore") Instant staleBefore);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ResearchWorkItem item
            SET item.resultPublishedAt = :publishedAt, item.resultDeliveryClaimedAt = NULL,
                item.resultDeliveryRejectedAt = NULL, item.resultDeliveryRejection = NULL
            WHERE item.requestId = :requestId AND item.resultDeliveryClaimedAt = :claimedAt
            """)
    int acknowledgeResultDelivery(@Param("requestId") UUID requestId, @Param("claimedAt") Instant claimedAt,
                                  @Param("publishedAt") Instant publishedAt);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ResearchWorkItem item
            SET item.resultDeliveryRejectedAt = :rejectedAt, item.resultDeliveryRejection = :rejection,
                item.resultDeliveryClaimedAt = NULL
            WHERE item.requestId = :requestId AND item.resultDeliveryClaimedAt = :claimedAt
            """)
    int rejectResultDelivery(@Param("requestId") UUID requestId, @Param("claimedAt") Instant claimedAt,
                             @Param("rejectedAt") Instant rejectedAt, @Param("rejection") String rejection);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ResearchWorkItem item
            SET item.resultDeliveryClaimedAt = NULL
            WHERE item.requestId = :requestId AND item.resultDeliveryClaimedAt = :claimedAt
            """)
    int releaseResultDeliveryClaim(@Param("requestId") UUID requestId, @Param("claimedAt") Instant claimedAt);

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
