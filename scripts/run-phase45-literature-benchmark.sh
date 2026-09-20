#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
index_dir="${PHASE34_REAL_INDEX_DIR:-${HOME}/.emergentterrain/indexes/math-literature}"
cache_state="${PHASE45_BENCHMARK_CACHE_STATE:-normal-cache}"
output_dir="${PHASE45_BENCHMARK_OUTPUT_DIR:-${repo_root}/.runtime/test-artifacts/phase45-literature-retrieval/$(date -u +%Y%m%dT%H%M%SZ)}"

if [[ ! -f "${index_dir}/literature.db" ]]; then
  echo "ERROR: completed literature index is not available at ${index_dir}" >&2
  echo "Set PHASE34_REAL_INDEX_DIR to the local math-literature index." >&2
  exit 2
fi

mkdir -p "$output_dir"
export PHASE34_REAL_INDEX_DIR="$index_dir"
export PHASE45_BENCHMARK_ENABLED=true
export PHASE45_BENCHMARK_CACHE_STATE="$cache_state"
export PHASE45_BENCHMARK_OUTPUT_DIR="$output_dir"

cd "$repo_root"
mvn -f apps/game-service/pom.xml test -Dtest=Phase45RealIndexBenchmarkTest -DtrimStackTrace=false 2>&1 | tee "$output_dir/maven.log"

echo "Phase 45 benchmark artifacts: $output_dir"
