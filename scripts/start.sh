#!/usr/bin/env bash
# =====================================================================
# scripts/start.sh — launch the EmergentTerrain Spring Boot app
# =====================================================================
#
# Usage:
#   ./scripts/start.sh            # backgrounded; returns immediately
#   ./scripts/start.sh --foreground   # run in current shell (Ctrl-C to stop)
#
# By default this script:
#   - exports JAVA_HOME to the dnf-installed JDK 25 path
#   - launches `mvn spring-boot:run` in the background via nohup
#   - redirects Maven/JVM stdout to logs/maven-stdout.log
#     (the Spring app's own log lives in logs/et-app.log via logback;
#      maven-stdout.log only captures startup banner + compile errors)
#   - prints the parent PID and the tail commands so you can verify
#     the app booted
#
# Idempotent — refuses to start if another instance is already running
# (detected via the EmergentTerrainApplication class name on the JVM
# command line). Use `./scripts/stop.sh` first if you want to restart.
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PATTERN="EmergentTerrainApplication"
FOREGROUND=0
for arg in "$@"; do
    case "$arg" in
        --foreground|-f) FOREGROUND=1 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# Bail if already running. pgrep -f matches against full command line
# including the main class — selective enough that it won't false-
# positive on unrelated java processes.
if pgrep -f "$PATTERN" >/dev/null 2>&1; then
    existing=$(pgrep -f "$PATTERN")
    echo "EmergentTerrain: already running as PID(s) $existing — refusing to start a second instance."
    echo "Run ./scripts/stop.sh first if you want to restart."
    exit 1
fi

# Fedora 44 dropped JDK 21; JDK 25 is the only `dnf`-installable JDK
# on this dev shell. The pom targets `release 21` so cross-compile is
# fine, but Maven needs JAVA_HOME exported (env-only `java -version`
# works because /usr/bin/java symlinks to JDK 25, but Maven reads
# JAVA_HOME directly).
# Trust a pre-set JAVA_HOME only if it actually contains a runnable java.
# A stale ~/.bashrc here exports java-21-openjdk, which Fedora 44 reduced to
# an empty husk (no bin/java), and Maven rejects it with "JAVA_HOME is not
# defined correctly" — the plain ${JAVA_HOME:-default} can't catch that
# because the var IS set, just broken. Fall back to JDK 25 (the only
# dnf-installable JDK on this shell) when JAVA_HOME is unset or invalid.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk
fi
export JAVA_HOME

mkdir -p "$REPO_ROOT/logs"
STDOUT_LOG="$REPO_ROOT/logs/maven-stdout.log"

if [ "$FOREGROUND" = "1" ]; then
    echo "EmergentTerrain: starting in foreground (Ctrl-C to stop)"
    exec mvn spring-boot:run
fi

# Background launch. nohup so the process survives shell exit; & to
# detach. stdout merges with stderr into the log.
echo "EmergentTerrain: starting in background, stdout → $STDOUT_LOG"
nohup mvn spring-boot:run > "$STDOUT_LOG" 2>&1 &
LAUNCHER_PID=$!
echo "EmergentTerrain: launcher PID $LAUNCHER_PID (the actual JVM spawns ~30s later)"
echo
echo "Tail logs:"
echo "  tail -f logs/et-app.log         # Spring's logback file"
echo "  tail -f logs/maven-stdout.log   # Maven + JVM startup banner"
echo
echo "Wait for ready:"
echo "  until curl -fsS http://localhost:8081/actuator/health >/dev/null; do sleep 1; done"
