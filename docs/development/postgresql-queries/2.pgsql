WITH
params AS (
    SELECT 104::int AS x, 37::int AS y  -- ← set X and Y here
),
latest AS (
    SELECT room_id
    FROM room_state
    ORDER BY updated_at DESC
    LIMIT 1
)
SELECT
    (tiles_json::jsonb -> p.y ->> p.x)::int AS tile_type,
    CASE (tiles_json::jsonb -> p.y ->> p.x)::int
        WHEN 0 THEN 'clear'
        WHEN 1 THEN 'forest'
        WHEN 2 THEN 'water'
        WHEN 3 THEN 'industry'
        WHEN 4 THEN 'highway'
        ELSE 'unknown'
    END AS tile_label
FROM room_state r
JOIN params p ON TRUE
WHERE r.room_id = (SELECT room_id FROM latest);
