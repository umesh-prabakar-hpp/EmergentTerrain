#!/usr/bin/env bash
# =====================================================================
# scripts/stop.sh — graceful stop for EmergentTerrain
# =====================================================================
#
# Usage:
#   ./scripts/stop.sh
#
# Stops the Spring Boot app launched by ./scripts/start.sh. Sends
# SIGTERM to the JVM (Spring catches it and runs shutdown hooks for
# clean DB-pool close + flushing the corpus state). Waits 15s, then
# escalates to SIGKILL if the JVM is still alive (DB or HTTP-pool
# shutdown can hang on flaky network).
#
# Also stops the maven launcher process if it survived independently
# (the mvn wrapper sometimes lingers after the JVM exits).
#
# Idempotent — exits 0 when nothing is running. Pattern matching uses
# the EmergentTerrainApplication class name on the JVM command line,
# which is specific enough not to false-positive.
# =====================================================================
set -euo pipefail

JVM_PATTERN="EmergentTerrainApplication"
MVN_PATTERN="spring-boot:run"
GRACE_SECONDS=15

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

# Stop the JVM first (gives Spring's @PreDestroy hooks a chance).
# Then sweep the Maven launcher in case it lingered.
kill_pattern "EmergentTerrain JVM" "$JVM_PATTERN"
kill_pattern "Maven launcher"     "$MVN_PATTERN"
# Then subscription proxies started by start.sh (no-op if absent).
kill_pattern "local-ai-gateway" "local-ai-gateway/server.mjs"
