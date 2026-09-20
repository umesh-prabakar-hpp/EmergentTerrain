package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchPaperArtifactProvenanceReader;
import com.emergentterrain.researchcontract.ResearchPaperProvenanceSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Research-owned cache of display-safe paper provenance summaries.
 *
 * <p>Artifact discovery is deliberately performed on a bounded background cadence, not on a
 * browser request. Only the derived DTO is persisted; neither paths nor raw artifacts are stored
 * in the catalog.</p>
 */
@Service
public class ResearchPaperProvenanceService {
    private static final int MAX_CATALOG_PAPERS = 2_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchPaperArtifactProvenanceReader reader;
    private final Path outputRoot;

    public ResearchPaperProvenanceService(JdbcTemplate jdbc, ObjectMapper mapper,
                                          @Value("${app.university.researchOutputDir:research}") String outputRoot) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.reader = new ResearchPaperArtifactProvenanceReader(mapper);
        this.outputRoot = Path.of(outputRoot);
    }

    /** Returns no response for an absent or cross-room paper, avoiding cross-room enumeration. */
    public Optional<ResearchPaperProvenanceSummary> provenance(UUID roomId, String paperId) {
        if (paperId == null || paperId.isBlank() || !belongsToRoom(roomId, paperId)) return Optional.empty();
        List<String> stored = jdbc.query("""
                SELECT summary_json FROM research_paper_artifact_catalog
                WHERE room_id = ? AND paper_id = ?
                """, (rs, rowNum) -> rs.getString(1), roomId, paperId);
        if (stored.isEmpty()) return Optional.of(ResearchPaperProvenanceSummary.pending(paperId));
        try {
            return Optional.of(mapper.readValue(stored.getFirst(), ResearchPaperProvenanceSummary.class));
        } catch (Exception ignored) {
            return Optional.of(ResearchPaperProvenanceSummary.pending(paperId));
        }
    }

    /** A failed artifact read is represented as MISSING rather than making the query API fail. */
    @Scheduled(initialDelayString = "${app.research-service.provenance.initial-delay-ms:10000}",
            fixedDelayString = "${app.research-service.provenance.refresh-interval-ms:60000}")
    public void refreshArtifactCatalog() {
        List<PaperKey> papers = jdbc.query("""
                SELECT room_id, paper_id FROM research_papers
                ORDER BY tick DESC, paper_id DESC
                LIMIT ?
                """, (rs, rowNum) -> new PaperKey(rs.getObject(1, UUID.class), rs.getString(2)),
                MAX_CATALOG_PAPERS);
        for (PaperKey paper : papers) {
            ResearchPaperProvenanceSummary summary = reader.read(outputRoot, paper.roomId(), paper.paperId());
            save(paper, summary);
        }
    }

    private void save(PaperKey paper, ResearchPaperProvenanceSummary summary) {
        try {
            jdbc.update("""
                    INSERT INTO research_paper_artifact_catalog
                        (paper_id, room_id, summary_json, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (paper_id) DO UPDATE SET room_id = EXCLUDED.room_id,
                        summary_json = EXCLUDED.summary_json, updated_at = EXCLUDED.updated_at
                    """, paper.paperId(), paper.roomId(), mapper.writeValueAsString(summary));
        } catch (Exception ignored) {
            // This is a read-model cache. A transient artifact or catalog failure must not affect
            // publication, research work, or game state.
        }
    }

    private boolean belongsToRoom(UUID roomId, String paperId) {
        Boolean exists = jdbc.query("""
                SELECT EXISTS(SELECT 1 FROM research_papers WHERE room_id = ? AND paper_id = ?)
                """, rs -> rs.next() && rs.getBoolean(1), roomId, paperId);
        return Boolean.TRUE.equals(exists);
    }

    private record PaperKey(UUID roomId, String paperId) { }
}
