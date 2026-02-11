-- EmergentTerrain Postgres test queries
-- Connection: postgres@localhost:5432/emergentterrain

-- Counts
SELECT COUNT(*) FROM profiles;
SELECT COUNT(*) FROM rooms;
SELECT COUNT(*) FROM room_bots;
SELECT COUNT(*) FROM room_participants;
SELECT COUNT(*) FROM room_state;
SELECT COUNT(*) FROM users;

-- Latest room_state snapshots (flushes)
SELECT id, room_id, updated_at, state_hash, previous_state_hash
FROM room_state
ORDER BY updated_at DESC
LIMIT 10;

-- Snapshot history (latest 20)
SELECT room_id, snapshot_at, total_change_count, changes_since_flush,
       tile_clear_count, tile_forest_count, tile_water_count, tile_industry_count, tile_highway_count
FROM room_state_history
ORDER BY snapshot_at DESC
LIMIT 20;

-- Average changes per minute (last 60 minutes)
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
ORDER BY avg_changes_per_minute DESC;

-- Tile mix over time (last 20 snapshots)
SELECT room_id, snapshot_at,
       tile_clear_count, tile_forest_count, tile_water_count, tile_industry_count, tile_highway_count
FROM room_state_history
ORDER BY snapshot_at DESC
LIMIT 20;

-- Tile counts + change counters
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
LIMIT 5;

-- Approximate change rate (changes per minute since last flush)
SELECT
  room_id,
  changes_since_flush,
  GREATEST(EXTRACT(EPOCH FROM (NOW() - updated_at)), 1) AS seconds_since_update,
  ROUND(changes_since_flush / (GREATEST(EXTRACT(EPOCH FROM (NOW() - updated_at)), 1) / 60.0), 2) AS changes_per_minute
FROM room_state
ORDER BY updated_at DESC
LIMIT 5;

-- Snapshot age (seconds) for each room
SELECT room_id, EXTRACT(EPOCH FROM (NOW() - updated_at))::int AS seconds_since_update
FROM room_state
ORDER BY seconds_since_update DESC;

-- Last 10 state hashes (per room)
SELECT room_id, updated_at, state_hash
FROM room_state
ORDER BY updated_at DESC
LIMIT 10;

-- Get width and height from tiles_json
SELECT
  jsonb_array_length(tiles_json::jsonb) AS height,
  jsonb_array_length((tiles_json::jsonb)->0) AS width
FROM room_state
LIMIT 1;

-- How many bots are in the room?
SELECT room_id, COUNT(*) AS bots
FROM room_bots
GROUP BY room_id
ORDER BY bots DESC;


-- Database size (MB and GB)
SELECT
  pg_database.datname AS database_name,
  pg_size_pretty(pg_database_size(pg_database.datname)) AS size_pretty,
  ROUND(pg_database_size(pg_database.datname) / 1024.0 / 1024.0, 2) AS size_mb,
  ROUND(pg_database_size(pg_database.datname) / 1024.0 / 1024.0 / 1024.0, 2) AS size_gb
FROM pg_database
WHERE pg_database.datname = current_database();




-- Reset schema (destructive)
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO postgres;
GRANT ALL ON SCHEMA public TO public;
