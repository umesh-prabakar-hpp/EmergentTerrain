#!/usr/bin/env bash
# =====================================================================
# scripts/stop.sh — graceful stop for EmergentTerrainGPUEngine
# =====================================================================
#
# Usage:
#   ./scripts/stop.sh
#
# Stops the GPU engine binary launched by ./scripts/start.sh. Sends
# SIGTERM first (the engine's main loop handles Ctrl-C cleanly via
# tokio's signal handler), waits 5s, escalates to SIGKILL if needed.
#
# Also sweeps the cargo launcher process so a `cargo run` wrapper
# doesn't linger holding the build lock.
#
# Idempotent — exits 0 when nothing is running.
# =====================================================================
set -euo pipefail

ENGINE_PATTERN="emergent_terrain_gpu_engine"
CARGO_PATTERN="cargo run.*--release\|cargo run$\|cargo run "
GRACE_SECONDS=5

kill_pattern() {
    local label="$1"
    local pattern="$2"
    local pids
    pids=$(pgrep -f "$pattern" 2>/dev/null || true)
    if [ -z "$pids" ]; then
        echo "$label: no running process matched '$pattern'"
        return 0
    fi
    echo "$label: sending SIGTERM to PID(s): $pids"
    # shellcheck disable=SC2086
    kill -TERM $pids 2>/dev/null || true

    for _ in $(seq 1 $((GRACE_SECONDS * 2))); do
        if ! pgrep -f "$pattern" >/dev/null 2>&1; then
            echo "$label: stopped cleanly"
            return 0
        fi
        sleep 0.5
    done

    local remaining
    remaining=$(pgrep -f "$pattern" 2>/dev/null || true)
    if [ -n "$remaining" ]; then
        echo "$label: $GRACE_SECONDS s elapsed, escalating to SIGKILL on: $remaining"
        # shellcheck disable=SC2086
        kill -KILL $remaining 2>/dev/null || true
        sleep 1
    fi
    if pgrep -f "$pattern" >/dev/null 2>&1; then
        echo "$label: ERROR — process survived SIGKILL; manual intervention needed" >&2
        return 1
    fi
    echo "$label: stopped (after SIGKILL)"
}

# Kill the engine binary first (it's what holds the GPU + gRPC port),
# then the cargo launcher in case it lingered.
kill_pattern "GPU engine"      "$ENGINE_PATTERN"
kill_pattern "Cargo launcher"  "$CARGO_PATTERN"
