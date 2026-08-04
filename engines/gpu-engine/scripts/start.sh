#!/usr/bin/env bash
# =====================================================================
# scripts/start.sh — launch the EmergentTerrain GPU engine
# =====================================================================
#
# Usage:
#   ./scripts/start.sh            # backgrounded; returns immediately
#   ./scripts/start.sh --foreground   # run in current shell
#   ./scripts/start.sh --debug    # cargo run (debug build) instead of --release
#
# Default mode is `cargo run --release` because the engine's hot
# paths are CPU/GPU-bound and the debug build is ~10× slower. Use
# --debug only when iterating on the engine itself.
#
# Idempotent — refuses to start if the engine binary is already
# running (detected via the package name from Cargo.toml).
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# The cargo package name from Cargo.toml. cargo run produces a binary
# at target/{release,debug}/<name>, so this is what pgrep matches.
PATTERN="emergent_terrain_gpu_engine"
FOREGROUND=0
PROFILE="--release"

for arg in "$@"; do
    case "$arg" in
        --foreground|-f) FOREGROUND=1 ;;
        --debug|-d) PROFILE="" ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

if pgrep -f "$PATTERN" >/dev/null 2>&1; then
    existing=$(pgrep -f "$PATTERN")
    echo "GPU engine: already running as PID(s) $existing — refusing to start a second instance."
    echo "Run ./scripts/stop.sh first if you want to restart."
    exit 1
fi

mkdir -p "$REPO_ROOT/logs"
STDOUT_LOG="$REPO_ROOT/logs/gpu-engine.log"

if [ "$FOREGROUND" = "1" ]; then
    echo "GPU engine: starting in foreground (Ctrl-C to stop)"
    # shellcheck disable=SC2086
    exec cargo run $PROFILE
fi

echo "GPU engine: starting in background (profile: ${PROFILE:-debug}), stdout → $STDOUT_LOG"
# nohup so the engine survives shell exit. cargo compile time on
# first run is ~30s-2min — the tail log captures progress.
# shellcheck disable=SC2086
nohup cargo run $PROFILE > "$STDOUT_LOG" 2>&1 &
LAUNCHER_PID=$!
echo "GPU engine: cargo launcher PID $LAUNCHER_PID"
echo
echo "Tail log:"
echo "  tail -f logs/gpu-engine.log"
echo
echo "Wait for ready (gRPC port 50051):"
echo "  until ss -tln | grep -q ':50051 '; do sleep 1; done"
