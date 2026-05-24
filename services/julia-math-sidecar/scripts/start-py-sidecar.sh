#!/usr/bin/env bash
# =====================================================================
# scripts/start-py-sidecar.sh — PyCall companion process (sympy/mpmath)
# =====================================================================
#
# Serves ONLY the PyCall-backed endpoints — /prove_identity,
# /recognise/constant, /recognise/minpoly — on a separate port (default
# 8089). The main sidecar (scripts/start.sh, JULIA_NUM_THREADS=4) stays
# PyCall-free and proxies here when started with PY_SIDECAR_ENABLED=true.
#
# WHY A SEPARATE PROCESS: PyCall's pydecref finalizer segfaults when Julia's
# GC runs it off the GIL-holding thread, which happens under
# JULIA_NUM_THREADS>1. This process is pinned to ONE thread so every
# finalizer runs on it — safe. Do not raise the thread count here.
#
# Usage:
#   ./scripts/start-py-sidecar.sh             # 127.0.0.1:8089, 1 thread
#   PORT=9089 ./scripts/start-py-sidecar.sh   # override port
#   HOST=0.0.0.0 ./scripts/start-py-sidecar.sh
#
# Then start the main sidecar with isolation on:
#   PY_SIDECAR_ENABLED=true ./scripts/start.sh
#   (optionally PY_SIDECAR_URL=http://127.0.0.1:9089 to match a custom PORT)
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8089}"
# Force single-threaded — >1 thread is a PyCall segfault hazard (off-thread
# pydecref). Intentionally NOT honoring a caller-supplied JULIA_NUM_THREADS.
export JULIA_NUM_THREADS=1

LOG_FILE="${LOG_FILE:-$REPO_ROOT/logs/py-sidecar.log}"
if [ "$LOG_FILE" != "-" ]; then
    mkdir -p "$(dirname "$LOG_FILE")"
    echo "py-sidecar: tee'ing log to $LOG_FILE"
fi

if ! command -v julia >/dev/null 2>&1; then
    echo "py-sidecar: \`julia\` not found on PATH." >&2
    exit 1
fi

if [ "$LOG_FILE" = "-" ]; then
    exec julia --project=. -e "
        using JuliaMathSidecar
        start_py_server!(host=\"$HOST\", port=$PORT)
    "
else
    exec julia --project=. -e "
        using JuliaMathSidecar
        start_py_server!(host=\"$HOST\", port=$PORT)
    " 2>&1 | tee -a "$LOG_FILE"
fi
