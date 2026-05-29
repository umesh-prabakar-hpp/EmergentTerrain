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
echo "--- Truncating game + research tables ---"
PGPASSWORD="$PGPASSWORD" psql -U "$PG_USER" -h "$PG_HOST" -d "$PG_DB" -v ON_ERROR_STOP=1 <<'SQL'
-- Deliberately PRESERVED (not game/run data):
--   oeis_cache                 external OEIS lookups — expensive to rebuild, pure cache
--   users, profiles            account data, survive a simulation reset
--   prompt_template_variants   accumulated A/B variant stats (cross-run learning) —
--                              wipe manually if a clean prompt-experiment is wanted
-- Child tables (research_paper_reviewers, formal_finding_independents,
-- room_state_history) are cleared automatically via FK CASCADE from their parents.
-- conjectures: truncated below — the row carries per-run investigation state
-- (confirmedRuns/promotedAt/refutedByJson); the miner repopulates the corpus from
-- the math sidecar on the next run. Comment it out to keep the mined queue across
-- a fresh start (e.g. during the conjecture-path verification phase).
TRUNCATE
    room_bots,
    room_participants,
    room_proposal_votes,
    room_proposals,
    room_state,
    game_confirmations,
    game_snapshots,
    government_timeline_event,
    government_timeline_sample,
    profile_quest_state,
    rooms,
    bot_calibrations,
    conjectures,
    epistemic_markets,
    expectation_priors,
    formal_findings,
    funding_agencies,
    grants,
    journals,
    market_stakes,
    paper_citations,
    program_failed_approaches,
    program_open_questions,
    replication_councils,
    reputation_history,
    research_papers,
    research_programs,
    surprise_events,
    universities
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
