#!/usr/bin/env bash
# =====================================================================
# scripts/stop.sh — graceful stop for julia-math-sidecar
# =====================================================================
#
# Usage:
#   ./scripts/stop.sh
#
# Finds the sidecar process by its command-line signature (the
# `JuliaMathSidecar` module name inside the julia -e expression),
# sends SIGTERM, waits up to 5s for clean shutdown, then escalates
# to SIGKILL if necessary.
#
# Idempotent — exits 0 if no process is running. The matching pattern
# is precise enough that it won't catch unrelated julia processes
# (each julia invocation embeds its -e script in argv).
# =====================================================================
set -euo pipefail

PATTERN="julia.*JuliaMathSidecar"
GRACE_SECONDS=5

pids=$(pgrep -f "$PATTERN" 2>/dev/null || true)
if [ -z "$pids" ]; then
    echo "julia-math-sidecar: no running process matched '$PATTERN' — nothing to stop"
    exit 0
fi

echo "julia-math-sidecar: sending SIGTERM to PID(s): $pids"
# shellcheck disable=SC2086
kill -TERM $pids 2>/dev/null || true

# Wait up to GRACE_SECONDS for clean shutdown.
for _ in $(seq 1 $((GRACE_SECONDS * 2))); do
    if ! pgrep -f "$PATTERN" >/dev/null 2>&1; then
        echo "julia-math-sidecar: stopped cleanly"
        exit 0
    fi
    sleep 0.5
done

# Still alive — escalate.
remaining=$(pgrep -f "$PATTERN" 2>/dev/null || true)
if [ -n "$remaining" ]; then
    echo "julia-math-sidecar: $GRACE_SECONDS s elapsed, escalating to SIGKILL on: $remaining"
    # shellcheck disable=SC2086
    kill -KILL $remaining 2>/dev/null || true
    sleep 0.5
fi

if pgrep -f "$PATTERN" >/dev/null 2>&1; then
    echo "julia-math-sidecar: ERROR — process survived SIGKILL; manual intervention needed" >&2
    exit 1
fi

echo "julia-math-sidecar: stopped (after SIGKILL)"
