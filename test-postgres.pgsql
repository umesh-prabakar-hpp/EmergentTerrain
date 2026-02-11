-- EmergentTerrain Postgres test queries
-- Connection: postgres@localhost:5432/emergentterrain

-- Changes in last minute
SELECT COUNT(*) AS changes_last_minute
FROM room_proposals
WHERE status = 'APPLIED'
  AND created_at >= (CURRENT_TIMESTAMP - INTERVAL '60 seconds');

-- Expired but still pending
SELECT COUNT(*)
FROM room_proposals
WHERE status = 'PENDING'
  AND expires_at < CURRENT_TIMESTAMP;

-- Cleanup: mark expired pending as REJECTED
UPDATE room_proposals
SET status = 'REJECTED'
WHERE status = 'PENDING'
  AND expires_at < CURRENT_TIMESTAMP;

-- Expired pending proposals
SELECT id, created_at, expires_at, approvals, rejections, required_votes, bot_label, bot_generated
FROM room_proposals
WHERE status = 'PENDING'
  AND expires_at < CURRENT_TIMESTAMP;

-- Proposals with proposer display name
SELECT p.display_name, rp.id, rp.created_at, rp.expires_at,
       rp.approvals, rp.rejections, rp.required_votes, rp.status, p.display_name
FROM room_proposals rp
JOIN room_participants p ON p.id = rp.proposer_id;

-- Counts
SELECT COUNT(*) FROM profiles;
SELECT COUNT(*) FROM rooms;
SELECT COUNT(*) FROM room_bots;
SELECT COUNT(*) FROM room_participants;
SELECT COUNT(*) FROM room_proposals;
SELECT COUNT(*) FROM room_proposal_votes;
SELECT COUNT(*) FROM room_state;
SELECT COUNT(*) FROM users;

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
SELECT status, COUNT(*) FROM room_proposals GROUP BY status;


-- Database size (MB and GB)
SELECT
  pg_database.datname AS database_name,
  pg_size_pretty(pg_database_size(pg_database.datname)) AS size_pretty,
  ROUND(pg_database_size(pg_database.datname) / 1024.0 / 1024.0, 2) AS size_mb,
  ROUND(pg_database_size(pg_database.datname) / 1024.0 / 1024.0 / 1024.0, 2) AS size_gb
FROM pg_database
WHERE pg_database.datname = current_database();

-- Average polls created per bot (all time)
SELECT
  COUNT(*) FILTER (WHERE bot_generated) AS bot_proposals,
  (SELECT COUNT(*) FROM room_bots) AS total_bots,
  ROUND(
    COUNT(*) FILTER (WHERE bot_generated)::numeric
    / NULLIF((SELECT COUNT(*) FROM room_bots), 0),
    2
  ) AS avg_proposals_per_bot
FROM room_proposals;

-- Average polls created per bot per minute (last 1 minute)
SELECT
  COUNT(*) FILTER (WHERE bot_generated AND created_at >= NOW() - INTERVAL '1 minute') AS bot_proposals_1m,
  (SELECT COUNT(*) FROM room_bots) AS total_bots,
  ROUND(
    COUNT(*) FILTER (WHERE bot_generated AND created_at >= NOW() - INTERVAL '1 minute')::numeric
    / NULLIF((SELECT COUNT(*) FROM room_bots), 0),
    2
  ) AS avg_proposals_per_bot_1m
FROM room_proposals;

-- Min/Max/Avg votes per poll
SELECT
  MIN(approvals + rejections) AS min_votes,
  MAX(approvals + rejections) AS max_votes,
  ROUND(AVG((approvals + rejections)::numeric), 2) AS avg_votes
FROM room_proposals;




-- Reset schema (destructive)
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO postgres;
GRANT ALL ON SCHEMA public TO public;
