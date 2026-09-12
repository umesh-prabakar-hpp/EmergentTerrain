#!/usr/bin/env bash
# Validate the local corpus state required before the university paper workflow starts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INDEX_DIR="${RESEARCH_LITERATURE_INDEX_DIR:-$HOME/.emergentterrain/indexes/math-literature}"
INDEX_DB="$INDEX_DIR/literature.db"

if [[ ! -f "$INDEX_DB" ]]; then
  echo "ERROR: required literature index is missing: $INDEX_DB" >&2
  echo "Build it with the literature steps in RUN-MINIMAL.md, then rerun this command." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required to verify the literature index." >&2
  exit 127
fi

python3 - "$INDEX_DB" <<'PY'
import sqlite3
import sys

database = sys.argv[1]
try:
    with sqlite3.connect(f"file:{database}?mode=ro", uri=True) as connection:
        rows = dict(connection.execute(
            "SELECT key, value FROM metadata WHERE key IN "
            "('metadata_complete', 'theorems_complete', 'fulltext_complete', 'fulltext_fts_complete')"
        ))
except sqlite3.Error as error:
    raise SystemExit(f"ERROR: cannot read required literature index: {error}")

required = ('metadata_complete', 'theorems_complete', 'fulltext_complete', 'fulltext_fts_complete')
missing = [key for key in required if rows.get(key) != 'true']
if missing:
    raise SystemExit(
        "ERROR: literature index is incomplete; required completed stages: " + ", ".join(missing)
    )
print(f"Research paper prerequisites: READY (complete literature index: {database})")
PY
