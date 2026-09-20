#!/usr/bin/env bash
set -euo pipefail

# Runs the deterministic, real-index Phase 35 artifact smoke test. It creates
# only a JUnit temporary directory: no Spring app, database row, LLM request,
# or research/<room>/ output is produced.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
index_dir="${PHASE34_REAL_INDEX_DIR:-${HOME}/.emergentterrain/indexes/math-literature}"

if [[ ! -d "$index_dir" ]]; then
  echo "ERROR: Phase 34 real index is not available at $index_dir" >&2
  echo "Set PHASE34_REAL_INDEX_DIR to the completed local math-literature index." >&2
  exit 2
fi

export PHASE34_REAL_INDEX_DIR="$index_dir"
cd "$repo_root"
mvn -pl apps/game-service -am test -Dtest=Phase35ArtifactBackedSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DtrimStackTrace=false
