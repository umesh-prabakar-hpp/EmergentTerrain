#!/usr/bin/env bash
set -euo pipefail

PGPASSWORD="${PG_PASSWORD:-NewStrongPasswordHere}"
PG_USER="${PG_USER:-postgres}"
PG_HOST="${PG_HOST:-localhost}"
PG_DB="${PG_DB:-emergentterrain}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== EmergentTerrain fresh start ==="
echo "DB:    $PG_USER@$PG_HOST/$PG_DB"
echo "Files: $APP_DIR"
echo ""
read -rp "This will delete all game data and generated files. Continue? [y/N] " confirm
[[ "$confirm" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

echo ""
echo "--- Truncating game tables ---"
PGPASSWORD="$PGPASSWORD" psql -U "$PG_USER" -h "$PG_HOST" -d "$PG_DB" -v ON_ERROR_STOP=1 <<'SQL'
TRUNCATE
    room_bots,
    room_participants,
    room_proposal_votes,
    room_proposals,
    room_state,
    room_state_history,
    game_confirmations,
    game_snapshots,
    government_timeline_event,
    government_timeline_sample,
    profile_quest_state,
    rooms
RESTART IDENTITY CASCADE;
SQL
echo "Done."

echo ""
echo "--- Deleting research paper files ---"
rm -rf "$APP_DIR"/research/*/
echo "Done."

echo ""
echo "--- Deleting simulation history files ---"
rm -rf "$APP_DIR"/simulation-history/constitution/*
rm -rf "$APP_DIR"/simulation-history/courts/*
rm -rf "$APP_DIR"/simulation-history/elections/*
rm -rf "$APP_DIR"/simulation-history/events/*
rm -rf "$APP_DIR"/simulation-history/legislation/*
rm -f  "$APP_DIR"/simulation-history/constitution-bootstrap-*.json
echo "Done."

echo ""
echo "=== Fresh start complete. Restart the app to begin a new simulation. ==="
