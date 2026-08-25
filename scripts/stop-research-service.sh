#!/usr/bin/env bash
# Stop only the standalone research-service JVM and its scoped Maven launcher.
set -euo pipefail

GRACE_SECONDS=15

stop_matching() {
  local label="$1"
  local pattern="$2"
  local pids
  pids="$(pgrep -f "$pattern" 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    echo "$label: not running"
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

  pids="$(pgrep -f "$pattern" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "$label: $GRACE_SECONDS s elapsed, sending SIGKILL to PID(s): $pids"
    # shellcheck disable=SC2086
    kill -KILL $pids 2>/dev/null || true
  fi
}

stop_matching "Research-service JVM" "ResearchServiceApplication"
stop_matching "Research-service Maven launcher" "apps/research-service/pom.xml.*spring-boot:run"
