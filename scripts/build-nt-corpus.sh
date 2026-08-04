#!/usr/bin/env bash
# =====================================================================
# scripts/build-nt-corpus.sh — Phase 32 §32.5 step 2: offline NT corpus build
# =====================================================================
#
# Pulls number-theory work metadata + abstracts from OpenAlex into a local
# corpus directory (default ~/.emergentterrain/corpus-nt) for the §32.5
# grounding sidecar to index. Resumable + budget-capped: the JSONL response
# cache makes completed pages free, and the daily credit ledger refuses to
# spend past CREDIT_CEILING (default $0.50; the anonymous keyless tier gives
# ~$0.10/day, a free key from openalex.org/settings/api gives $1/day via
# OPENALEX_API_KEY in ~/.emergentterrain/secrets.enc or the environment).
#
# Usage:
#   ./scripts/build-nt-corpus.sh                 # default trickle build
#   DRY_RUN=true ./scripts/build-nt-corpus.sh    # print the plan, no network
#   PAGES_PER_QUERY=5 TOPIC_PAGES=20 CREDIT_CEILING=1.0 ./scripts/build-nt-corpus.sh
#
# Env knobs: OPENALEX_API_KEY OPENALEX_MAILTO CORPUS_DIR PAGES_PER_QUERY
#            TOPIC_PAGES CREDIT_CEILING DRY_RUN
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# mvn compile rewrites target/classes under a live JVM (NoClassDefFoundError —
# see project_app_runs_from_target_classes); refuse to run while the app is up.
if pgrep -f 'java.*EmergentTerrain.*target/classes' > /dev/null 2>&1; then
  echo "ERROR: the ET app appears to be running — compiling would corrupt the live JVM." >&2
  echo "Stop the app first (or wait for the run-budget stop), then re-run." >&2
  exit 1
fi

# This dev shell's ~/.bashrc JAVA_HOME points at a removed JDK 21; use JDK 25.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
fi

exec mvn -f "$REPO_ROOT/apps/game-service/pom.xml" -q compile exec:java \
  -Dexec.mainClass=com.emergentterrain.research.literature.NtCorpusBuilder \
  -Dexec.cleanupDaemonThreads=false
