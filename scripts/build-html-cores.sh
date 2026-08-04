#!/usr/bin/env bash
# =====================================================================
# scripts/build-html-cores.sh — Phase 33 L1b: ar5iv HTML cores (Java)
# =====================================================================
# For every arXiv-hosted work in cores/MANIFEST.json, fetches the ar5iv
# (LaTeXML) HTML rendering and extracts text with each formula replaced by
# $<original LaTeX>$ (the <math alttext> attribute) — fixing the PDF path's
# dropped glyphs and flattened math structure. Writes cores-html/<id>.txt +
# MANIFEST.json. Idempotent; no OpenAlex spend (ar5iv is free).
#
# Usage: OPENALEX_MAILTO=you@x ./scripts/build-html-cores.sh
# Env: CORPUS_DIR (default ~/.emergentterrain/corpus-nt)
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# mvn compile rewrites target/classes under a live JVM (NoClassDefFoundError —
# see project_app_runs_from_target_classes); refuse to run while the app is up.
if pgrep -f 'java.*EmergentTerrain.*target/classes' > /dev/null 2>&1; then
  echo "ERROR: the ET app appears to be running — compiling would corrupt the live JVM." >&2
  echo "Stop the app first (or run from a worktree with its own target/), then re-run." >&2
  exit 1
fi

# This dev shell's ~/.bashrc JAVA_HOME points at a removed JDK 21; use JDK 25.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
fi

exec mvn -f "$REPO_ROOT/apps/game-service/pom.xml" -q compile exec:java \
  -Dexec.mainClass=com.emergentterrain.research.literature.ArxivHtmlCoreBuilder \
  -Dexec.cleanupDaemonThreads=false
