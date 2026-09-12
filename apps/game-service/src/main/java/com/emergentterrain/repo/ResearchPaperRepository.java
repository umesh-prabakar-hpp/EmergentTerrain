package com.emergentterrain.repo;

import com.emergentterrain.research.ResearchPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchPaperRepository extends JpaRepository<ResearchPaper, String> {

    /** All papers for a room, oldest first. Used to seed the in-memory cache on first access. */
    List<ResearchPaper> findByRoomIdOrderByTickAsc(UUID roomId);

    /** Complete topic history for a quality-aware exact-protocol reuse decision. */
    List<ResearchPaper> findByRoomIdAndTopic(UUID roomId, String topic);

    /** Bounded Phase 43 legacy backfill selection; no quality-history row means UNASSESSED. */
    @Query("""
            SELECT p FROM ResearchPaper p
            WHERE NOT EXISTS (
                SELECT 1 FROM ResearchPaperQualityAssessment a WHERE a.paperId = p.paperId
            )
            ORDER BY p.publishedAt ASC, p.paperId ASC
            """)
    List<ResearchPaper> findUnassessedForQualityBackfill(org.springframework.data.domain.Pageable pageable);

    /** Most-recent-first bounded history used by game-owned research selection. */
    List<ResearchPaper> findByRoomIdOrderByTickDesc(UUID roomId, org.springframework.data.domain.Pageable pageable);

    /** Phase 31 §31.6 — id-only projection for the startup provenance reconciler. */
    @Query("select p.paperId from ResearchPaper p")
    List<String> findAllPaperIds();

    long countByRoomId(UUID roomId);

    /**
     * Highest paperId numeric suffix for a room's "P-{prefix}-{N}" sequence. Used by
     * UniversityService.nextPaperId to avoid ID collisions across restarts without
     * scanning markdown files.
     */
    @Query(value = """
            SELECT COALESCE(
                MAX(CAST(SUBSTRING(p.paper_id FROM '[0-9]+$') AS BIGINT)),
                0
            )
            FROM research_papers p
            WHERE p.room_id = :roomId
            """, nativeQuery = true)
    long maxPaperNumberForRoom(@Param("roomId") UUID roomId);

    Optional<ResearchPaper> findByPaperId(String paperId);

    /** Phase 4 Iter 10 — all papers a bot has authored. */
    List<ResearchPaper> findByAuthorBotId(String authorBotId);

    /** Phase 4 Iter 10 — all papers narrated through a given LLM call path. */
    List<ResearchPaper> findByLlmCallPath(String llmCallPath);

    /** Phase 4 Iter 10 — all papers using a given methodology (THEORETICAL/COMPUTATIONAL/EMPIRICAL). */
    List<ResearchPaper> findByMethodology(String methodology);

    /** Phase 4b — all papers attached to a journal (impact-factor accumulation). */
    List<ResearchPaper> findByJournalId(UUID journalId);

    /**
     * 2026-05-29 — INCOMPLETE re-queue candidates: papers that published as
     * {@code NEEDS_REVISION} with {@code reviewState=INCOMPLETE} (sound-but-
     * unfinished), still under the attempt cap, and carrying a persisted draft
     * snapshot to re-narrate from. Oldest first so the backlog drains in order;
     * {@code maxAttempts} caps the number of automated finish attempts per slot.
     */
    @Query("""
            SELECT p FROM ResearchPaper p
            WHERE p.status = 'NEEDS_REVISION'
              AND p.reviewState = 'INCOMPLETE'
              AND p.reworkDraftJson IS NOT NULL
              AND p.reworkAttempts < :maxAttempts
            ORDER BY p.tick ASC
            """)
    List<ResearchPaper> findReworkCandidates(@Param("maxAttempts") int maxAttempts,
                                             org.springframework.data.domain.Pageable pageable);
}
