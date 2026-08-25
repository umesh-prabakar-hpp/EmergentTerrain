#!/usr/bin/env bash
# Stop only the standalone local AI gateway; never stop the game or research worker.
set -euo pipefail

PATTERN="local-ai-gateway/server.mjs"
GRACE_SECONDS=15

pids="$(pgrep -f "$PATTERN" 2>/dev/null || true)"
if [[ -z "$pids" ]]; then
  echo "Local AI gateway: not running"
  exit 0
fi

echo "Local AI gateway: sending SIGTERM to PID(s): $pids"
# shellcheck disable=SC2086
kill -TERM $pids 2>/dev/null || true

for _ in $(seq 1 $((GRACE_SECONDS * 2))); do
  if ! pgrep -f "$PATTERN" >/dev/null 2>&1; then
    echo "Local AI gateway: stopped cleanly"
    exit 0
  fi
  sleep 0.5
done

pids="$(pgrep -f "$PATTERN" 2>/dev/null || true)"
if [[ -n "$pids" ]]; then
  echo "Local AI gateway: $GRACE_SECONDS s elapsed, sending SIGKILL to PID(s): $pids"
  # shellcheck disable=SC2086
  kill -KILL $pids 2>/dev/null || true
fi
