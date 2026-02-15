package com.emergentterrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = EmergentTerrainApplication.class)
@ActiveProfiles("sample")
class RoomStateSamplingIntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoomStateSamplingIntegrationTest.class);

    private static final String QUERY_LATEST_ROOM_STATE = """
        SELECT id, room_id, updated_at, state_hash, previous_state_hash
        FROM room_state
        ORDER BY updated_at DESC
        LIMIT 10
        """;
    private static final String QUERY_SNAPSHOT_HISTORY = """
        SELECT room_id, snapshot_at, total_change_count, changes_since_flush,
               tile_clear_count, tile_forest_count, tile_water_count, tile_industry_count, tile_highway_count
        FROM room_state_history
        ORDER BY snapshot_at DESC
        LIMIT 20
        """;
    private static final String QUERY_AVG_CHANGES_PER_MINUTE = """
        SELECT
          room_id,
          ROUND(AVG(changes_since_flush)::numeric, 2) AS avg_changes_per_flush,
          ROUND(
            SUM(changes_since_flush)::numeric
            / NULLIF(EXTRACT(EPOCH FROM (MAX(snapshot_at) - MIN(snapshot_at))), 0) * 60,
            2
          ) AS avg_changes_per_minute
        FROM room_state_history
        WHERE snapshot_at >= NOW() - INTERVAL '60 minutes'
        GROUP BY room_id
        ORDER BY avg_changes_per_minute DESC
        """;
    private static final String QUERY_TILE_MIX_OVER_TIME = """
        SELECT room_id, snapshot_at,
               tile_clear_count, tile_forest_count, tile_water_count, tile_industry_count, tile_highway_count
        FROM room_state_history
        ORDER BY snapshot_at DESC
        LIMIT 20
        """;
    private static final String QUERY_LATEST_ROOM_STATE_COUNTS = """
        SELECT
          room_id,
          updated_at,
          total_change_count,
          changes_since_flush,
          tile_clear_count,
          tile_forest_count,
          tile_water_count,
          tile_industry_count,
          tile_highway_count
        FROM room_state
        ORDER BY updated_at DESC
        LIMIT 5
        """;
    private static final String QUERY_CHANGE_RATE_SINCE_FLUSH = """
        SELECT
          room_id,
          changes_since_flush,
          GREATEST(EXTRACT(EPOCH FROM (NOW() - updated_at)), 1) AS seconds_since_update,
          ROUND(changes_since_flush / (GREATEST(EXTRACT(EPOCH FROM (NOW() - updated_at)), 1) / 60.0), 2) AS changes_per_minute
        FROM room_state
        ORDER BY updated_at DESC
        LIMIT 5
        """;
    private static final String QUERY_SNAPSHOT_COUNTS = """
        SELECT snapshot_at, total_change_count, changes_since_flush
        FROM room_state_history
        ORDER BY snapshot_at DESC
        LIMIT 20
        """;
    private static final String QUERY_GAP_STATS = """
        WITH ordered AS (
          SELECT snapshot_at,
                 EXTRACT(EPOCH FROM (snapshot_at - LAG(snapshot_at) OVER (ORDER BY snapshot_at))) AS gap_s,
                 changes_since_flush
          FROM room_state_history
          WHERE room_id = CAST(? AS uuid)
            AND snapshot_at >= NOW() - INTERVAL '10 minutes'
        )
        SELECT
          ROUND(AVG(gap_s)::numeric,2) AS avg_gap_seconds,
          ROUND(MAX(gap_s)::numeric,2) AS max_gap_seconds,
          SUM(changes_since_flush) AS total_changes
        FROM ordered
        WHERE gap_s IS NOT NULL
        """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sampleRoomStateQueries() throws Exception {
        int sampleCount = parseIntProperty("sample.count", 3);
        long intervalMs = parseLongProperty("sample.intervalMs", 60_000L);
        boolean failOnError = Boolean.parseBoolean(System.getProperty("sample.failOnError", "false"));
        String roomIdOverride = System.getProperty("sample.roomId", "").trim();
        String outputDirRaw = System.getProperty("sample.outputDir", "").trim();
        SampleOutput output = outputDirRaw.isEmpty() ? null : new SampleOutput(outputDirRaw);

        if (sampleCount < 1) {
            sampleCount = 1;
        }
        if (intervalMs < 0) {
            intervalMs = 0;
        }

        for (int i = 1; i <= sampleCount; i++) {
            LOGGER.info("sampleStart index={} timestamp={}", i, Instant.now());

            boolean ok = true;
            ok &= logQuery(i, "latestRoomState", QUERY_LATEST_ROOM_STATE, output);
            ok &= logQuery(i, "snapshotHistory", QUERY_SNAPSHOT_HISTORY, output);
            ok &= logQuery(i, "avgChangesPerMinute", QUERY_AVG_CHANGES_PER_MINUTE, output);
            ok &= logQuery(i, "tileMixOverTime", QUERY_TILE_MIX_OVER_TIME, output);
            ok &= logQuery(i, "latestRoomStateCounts", QUERY_LATEST_ROOM_STATE_COUNTS, output);
            ok &= logQuery(i, "changeRateSinceFlush", QUERY_CHANGE_RATE_SINCE_FLUSH, output);
            ok &= logQuery(i, "snapshotCounts", QUERY_SNAPSHOT_COUNTS, output);

            String roomId = roomIdOverride.isEmpty() ? resolveLatestRoomId() : roomIdOverride;
            if (roomId == null || roomId.isBlank()) {
                LOGGER.info("sampleQuery index={} label=gapStats skipped (no room_id found)", i);
            } else {
                ok &= logQuery(i, "gapStats", QUERY_GAP_STATS, output, roomId);
            }

            if (!ok && failOnError) {
                throw new IllegalStateException("One or more sample queries failed. See logs for details.");
            }

            if (output != null) {
                output.flushSummary();
            }
            if (i < sampleCount && intervalMs > 0) {
                Thread.sleep(intervalMs);
            }
        }
        if (output != null) {
            output.close();
        }
    }

    private boolean logQuery(int sampleIndex, String label, String sql, SampleOutput output, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
            LOGGER.info("sampleQuery index={} label={} rows={}", sampleIndex, label, rows.size());
            for (Map<String, Object> row : rows) {
                LOGGER.info("sampleRow index={} label={} data={}", sampleIndex, label, row);
            }
            if (output != null) {
                output.writeRows(sampleIndex, label, rows);
            }
            return true;
        } catch (DataAccessException ex) {
            LOGGER.warn("sampleQuery index={} label={} failed error={}", sampleIndex, label, ex.getMessage());
            if (output != null) {
                output.writeError(sampleIndex, label, ex.getMessage());
            }
            return false;
        }
    }

    private String resolveLatestRoomId() {
        try {
            return jdbcTemplate.query(
                "SELECT room_id FROM room_state_history ORDER BY snapshot_at DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("room_id") : null
            );
        } catch (DataAccessException ex) {
            LOGGER.warn("sampleQuery label=latestRoomId failed error={}", ex.getMessage());
            return null;
        }
    }

    private int parseIntProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid integer for {}: {}", key, raw);
            return fallback;
        }
    }

    private long parseLongProperty(String key, long fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid long for {}: {}", key, raw);
            return fallback;
        }
    }

    private static final class SampleOutput implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Path outputDir;
        private final Path ndjsonPath;
        private final Path summaryPath;
        private BufferedWriter ndjsonWriter;
        private BufferedWriter summaryWriter;

        private SampleOutput(String outputDirRaw) throws IOException {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "");
            Path baseDir = Path.of(outputDirRaw);
            this.outputDir = baseDir.resolve("sample-" + timestamp);
            Files.createDirectories(outputDir);
            this.ndjsonPath = outputDir.resolve("samples.ndjson");
            this.summaryPath = outputDir.resolve("summary.csv");
            this.ndjsonWriter = Files.newBufferedWriter(ndjsonPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.summaryWriter = Files.newBufferedWriter(summaryPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(summaryPath) == 0) {
                summaryWriter.write("sample,label,rows,avg_gap_seconds,max_gap_seconds,total_changes,avg_changes_per_flush,avg_changes_per_minute");
                summaryWriter.newLine();
                summaryWriter.flush();
            }
            LOGGER.info("sampleOutput dir={} ndjson={} summary={}", outputDir, ndjsonPath, summaryPath);
        }

        private void writeRows(int sampleIndex, String label, List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                writeNdjson(sampleIndex, label, row, null);
            }
            writeSummaryRow(sampleIndex, label, rows);
        }

        private void writeError(int sampleIndex, String label, String error) {
            writeNdjson(sampleIndex, label, null, error);
        }

        private void writeNdjson(int sampleIndex, String label, Map<String, Object> row, String error) {
            try {
                Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("sample", sampleIndex);
                payload.put("label", label);
                payload.put("timestamp", Instant.now().toString());
                payload.put("row", row);
                payload.put("error", error);
                ndjsonWriter.write(mapper.writeValueAsString(payload));
                ndjsonWriter.newLine();
            } catch (IOException ex) {
                LOGGER.warn("sampleOutput write failed label={} error={}", label, ex.getMessage());
            }
        }

        private void writeSummaryRow(int sampleIndex, String label, List<Map<String, Object>> rows) {
            if (!(label.equals("gapStats") || label.equals("avgChangesPerMinute"))) {
                return;
            }
            Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
            String avgGap = valueToString(first.get("avg_gap_seconds"));
            String maxGap = valueToString(first.get("max_gap_seconds"));
            String totalChanges = valueToString(first.get("total_changes"));
            String avgChangesPerFlush = valueToString(first.get("avg_changes_per_flush"));
            String avgChangesPerMinute = valueToString(first.get("avg_changes_per_minute"));
            try {
                summaryWriter.write(String.join(
                    ",",
                    Integer.toString(sampleIndex),
                    label,
                    Integer.toString(rows.size()),
                    avgGap,
                    maxGap,
                    totalChanges,
                    avgChangesPerFlush,
                    avgChangesPerMinute
                ));
                summaryWriter.newLine();
            } catch (IOException ex) {
                LOGGER.warn("sampleOutput summary failed label={} error={}", label, ex.getMessage());
            }
        }

        private String valueToString(Object value) {
            return value == null ? "" : value.toString();
        }

        private void flushSummary() {
            try {
                summaryWriter.flush();
                ndjsonWriter.flush();
            } catch (IOException ex) {
                LOGGER.warn("sampleOutput flush failed error={}", ex.getMessage());
            }
        }

        @Override
        public void close() throws IOException {
            if (ndjsonWriter != null) {
                ndjsonWriter.flush();
                ndjsonWriter.close();
            }
            if (summaryWriter != null) {
                summaryWriter.flush();
                summaryWriter.close();
            }
            LOGGER.info("sampleOutput done dir={}", outputDir);
        }
    }
}
