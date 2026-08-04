package com.emergentterrain.researchservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only adapter over the pre-extraction database schema.
 *
 * <p>Only the research service reads research-paper tables. The game process uses the v1 HTTP
 * contract rather than this schema when {@code app.research-api.mode=remote}.</p>
 */
@Service
public class ResearchReadService {

    private static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ResearchReadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ResearchPaperSummary> papers(UUID roomId, int limit) {
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        List<ResearchPaperSummary> papers = jdbc.query("""
                SELECT paper_id, room_id, author_bot_id, domain, topic, title, hypothesis,
                       methodology, findings, conclusion, peer_score, status, tick, generation
                FROM research_papers
                WHERE room_id = ?
                ORDER BY tick DESC, paper_id DESC
                LIMIT ?
                """, (rs, rowNum) -> paperSummary(rs), roomId, boundedLimit);
        return papers;
    }

    public Map<String, Object> stats(UUID roomId) {
        long totalPublished = scalarLong("SELECT COUNT(*) FROM research_papers WHERE room_id = ?", roomId);
        Map<String, Long> byStatus = groupedLong("""
                SELECT status, COUNT(*)
                FROM research_papers WHERE room_id = ? GROUP BY status
                """, roomId);
        Map<String, Long> byDomain = groupedLong("""
                SELECT domain, COUNT(*)
                FROM research_papers WHERE room_id = ? GROUP BY domain
                """, roomId);
        Double avgPeerScore = jdbc.queryForObject("""
                SELECT AVG(peer_score) FROM research_papers
                WHERE room_id = ? AND status IN ('PUBLISHED', 'PUBLISHED_NO_PROFESSOR')
                """, Double.class, roomId);

        BotStats botStats = botStats(roomId);
        long nullResults = byStatus.getOrDefault("NULL_RESULT", 0L);
        long disputed = byStatus.getOrDefault("DISPUTED", 0L);
        long needsRevision = byStatus.getOrDefault("NEEDS_REVISION", 0L);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalPublished", totalPublished);
        result.put("positiveResults", totalPublished - nullResults - disputed - needsRevision);
        result.put("nullResults", nullResults);
        result.put("disputed", disputed);
        result.put("needsRevision", needsRevision);
        // Rejections are presently an in-process ResearchCycleService counter. It becomes durable
        // with the tick-worker extraction; returning zero is explicit rather than inventing data.
        result.put("totalRejected", 0L);
        result.put("researcherCount", botStats.researcherCount());
        result.put("qualifiedCount", botStats.qualifiedCount());
        result.put("maxGeneration", botStats.maxGeneration());
        result.put("avgReputation", botStats.avgReputation());
        result.put("avgPeerScore", rounded(avgPeerScore == null ? 0.0 : avgPeerScore));
        result.put("byDomain", byDomain);
        result.put("replicationPending", 0L);
        return result;
    }

    private ResearchPaperSummary paperSummary(ResultSet rs) throws SQLException {
        String paperId = rs.getString("paper_id");
        List<String> reviewers = jdbc.query("""
                SELECT reviewer_bot_id FROM research_paper_reviewers
                WHERE paper_id = ? ORDER BY reviewer_bot_id
                """, (reviewer, rowNum) -> reviewer.getString(1), paperId);
        return new ResearchPaperSummary(
                paperId,
                rs.getObject("room_id", UUID.class),
                rs.getString("author_bot_id"),
                rs.getString("domain"),
                rs.getString("topic"),
                rs.getString("title"),
                rs.getString("hypothesis"),
                rs.getString("methodology"),
                rs.getString("findings"),
                rs.getString("conclusion"),
                reviewers,
                rs.getDouble("peer_score"),
                rs.getString("status"),
                rs.getLong("tick"),
                rs.getInt("generation"));
    }

    private BotStats botStats(UUID roomId) {
        List<String> lifecycleJson = jdbc.query("""
                SELECT lifecycle_json FROM room_bots
                WHERE room_id = ? AND status = 'ACTIVE'
                """, (rs, rowNum) -> rs.getString(1), roomId);
        int maxGeneration = 0;
        long qualified = 0;
        double reputationTotal = 0.0;
        for (String json : lifecycleJson) {
            try {
                JsonNode state = objectMapper.readTree(json == null ? "{}" : json);
                maxGeneration = Math.max(maxGeneration, state.path("genome").path("generation").asInt(0));
                reputationTotal += state.path("reputation").asDouble(0.0);
                if ("QUALIFIED".equals(state.path("researchStatus").asText())) {
                    qualified++;
                }
            } catch (Exception ignored) {
                // A malformed legacy state has the same neutral contribution as the game service's
                // loadStatesQuiet path; it must not make the read endpoint unavailable.
            }
        }
        double average = lifecycleJson.isEmpty() ? 0.0 : reputationTotal / lifecycleJson.size();
        return new BotStats(lifecycleJson.size(), qualified, maxGeneration, rounded(average));
    }

    private long scalarLong(String sql, UUID roomId) {
        Long value = jdbc.queryForObject(sql, Long.class, roomId);
        return value == null ? 0L : value;
    }

    private Map<String, Long> groupedLong(String sql, UUID roomId) {
        Map<String, Long> values = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> values.put(rs.getString(1), rs.getLong(2)), roomId);
        return values;
    }

    private static double rounded(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record BotStats(long researcherCount, long qualifiedCount, int maxGeneration,
                            double avgReputation) { }
}
