#!/usr/bin/env bash
# =====================================================================
# scripts/backfill-evidence-cores.sh — Phase 33 L2 supply side
# =====================================================================
# Scans the research-service artifact tree (papers + llm-traces) for the [W...] keys that
# search_literature actually returned, and fetches ar5iv HTML cores for
# exactly those works into cores-html/. Converts the ~2% read_reference
# hit rate into near-certainty for recurring EVIDENCE matches.
#
# Run POST-RUN or nightly. NEVER while the ET app is live: this runs
# `mvn compile`, which rewrites target/classes under the running JVM.
#
# Usage: OPENALEX_MAILTO=you@x ./scripts/backfill-evidence-cores.sh
# Env: CORPUS_DIR (default ~/.emergentterrain/corpus-nt)
#      RESEARCH_DIR (default apps/research-service/research), CREDIT_CEILING (default 0.50)
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
export RESEARCH_DIR="${RESEARCH_DIR:-$REPO_ROOT/apps/research-service/research}"

if pgrep -f 'spring-boot:run' >/dev/null 2>&1; then
  echo "ET app is running — refusing to mvn compile under a live JVM." >&2
  exit 1
fi

# This dev shell's ~/.bashrc JAVA_HOME points at a removed JDK 21; use JDK 25.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
fi

exec mvn -f "$REPO_ROOT/apps/game-service/pom.xml" -q compile exec:java \
  -Dexec.mainClass=com.emergentterrain.research.literature.EvidenceCoreBackfill \
  -Dexec.cleanupDaemonThreads=false
