

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


-- Gap stats (room_id must be UUID)
WITH latest AS (
  SELECT room_id FROM room_state_history ORDER BY snapshot_at DESC LIMIT 1
),
ordered AS (
  SELECT snapshot_at,
         EXTRACT(EPOCH FROM (snapshot_at - LAG(snapshot_at) OVER (ORDER BY snapshot_at))) AS gap_s,
         changes_since_flush
  FROM room_state_history
  WHERE room_id = (SELECT room_id FROM latest)
    AND snapshot_at >= NOW() - INTERVAL '10 minutes'
)
SELECT
  ROUND(AVG(gap_s)::numeric,2) AS avg_gap_seconds,
  ROUND(MAX(gap_s)::numeric,2) AS max_gap_seconds,
  SUM(changes_since_flush) AS total_changes
FROM ordered
WHERE gap_s IS NOT NULL;





