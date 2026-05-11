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
}
