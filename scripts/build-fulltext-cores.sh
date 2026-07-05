#!/usr/bin/env bash
# =====================================================================
# scripts/build-fulltext-cores.sh — Phase 33 lane L1: full-text cores
# =====================================================================
#
# Downloads open-access PDFs for the works nearest the live families
# (NtCorpusBuilder.SEED_QUERIES via OpenAlex best_oa_location.pdf_url),
# extracts plain text with PDFBox, and writes one cores/<workId>.txt per
# work + cores/MANIFEST.json under the corpus dir (default
# ~/.emergentterrain/corpus-nt/cores). Idempotent — re-runs only fill gaps.
#
# OpenAlex spend is search-pages only (~16 queries × 2 pages × $0.001);
# the PDFs come from publishers/arXiv (no credits, polite pacing).
#
# Usage:
#   ./scripts/build-fulltext-cores.sh                  # default: 350 cores
#   DRY_RUN=true ./scripts/build-fulltext-cores.sh     # print the plan
#   TARGET_CORES=500 PAGES_PER_QUERY=3 ./scripts/build-fulltext-cores.sh
#
# Env knobs: OPENALEX_API_KEY OPENALEX_MAILTO CORPUS_DIR TARGET_CORES
#            PAGES_PER_QUERY CREDIT_CEILING DRY_RUN
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

exec mvn -q compile exec:java \
  -Dexec.mainClass=com.emergentterrain.research.literature.FullTextCoreBuilder \
  -Dexec.cleanupDaemonThreads=false
