#!/usr/bin/env bash
# Apply idempotent, game-owned PostgreSQL migrations required by either runtime.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/apps/game-service/src/main/resources/db/migration"
POSTGRES_URL="${PG_URL:-jdbc:postgresql://localhost:5432/emergentterrain}"
POSTGRES_USER="${PG_USER:-postgres}"
POSTGRES_PASSWORD="${PG_PASSWORD:-NewStrongPasswordHere}"
PSQL_URL="${POSTGRES_URL#jdbc:}"

[[ -d "$MIGRATION_DIR" ]] || { echo "Game migration directory not found: $MIGRATION_DIR" >&2; exit 1; }
command -v psql >/dev/null 2>&1 || { echo "ERROR: psql is required to apply game schema migrations." >&2; exit 127; }

shopt -s nullglob
migrations=("$MIGRATION_DIR"/*.sql)
if (( ${#migrations[@]} == 0 )); then
    echo "No game schema migrations found."
    exit 0
fi

for migration in "${migrations[@]}"; do
    PGPASSWORD="$POSTGRES_PASSWORD" psql --no-psqlrc --no-password --quiet \
        --set=ON_ERROR_STOP=1 --single-transaction --username="$POSTGRES_USER" \
        --dbname="$PSQL_URL" --file="$migration"
    echo "Applied game schema migration: $(basename "$migration")"
done
