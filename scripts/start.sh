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
#   - gracefully stops a previous app/proxy set before starting a fresh one
#   - exports JAVA_HOME to the dnf-installed JDK 25 path
#   - launches the game-service Maven module in the background via nohup so the
#     JVM cannot inherit stale compiled classes from an earlier worktree state
#   - redirects Maven/JVM stdout to logs/maven-stdout.log
#     (the Spring app's own log lives in logs/et-app.log via logback;
#      maven-stdout.log only captures startup banner + compile errors)
#   - prints the parent PID and the tail commands so you can verify
#     the app booted
#
# Restart-safe — always delegates the initial cleanup to ./scripts/stop.sh, so
# a new start reloads the app and its managed subscription proxies instead of
# reusing an older process with stale model or code configuration.
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GAME_SERVICE_POM="$REPO_ROOT/apps/game-service/pom.xml"
REACTOR_POM="$REPO_ROOT/pom.xml"
cd "$REPO_ROOT"

[[ -f "$GAME_SERVICE_POM" ]] || { echo "Game-service POM not found: $GAME_SERVICE_POM" >&2; exit 1; }

FOREGROUND=0
for arg in "$@"; do
    case "$arg" in
        --foreground|-f) FOREGROUND=1 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# Always refresh the process set before launch. stop.sh is idempotent and uses
# SIGTERM plus a bounded grace period, so this is a safe no-op when nothing is
# running and prevents a stale proxy from silently retaining an old model.
"$SCRIPT_DIR/stop.sh"

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

# ---------------------------------------------------------------------
# local-ai-gateway (scripts/local-ai-gateway).
# OpenAI-compat shim backed by the locally logged-in ChatGPT/Codex account.
# Each request is an ephemeral read-only Codex turn in /tmp. The active
# `local-ai` provider points at port 8788 and falls back to
# OpenRouter when the local session cannot serve a request.
# ---------------------------------------------------------------------
LOCAL_AI_GATEWAY_DIR="$REPO_ROOT/scripts/local-ai-gateway"
LOCAL_AI_GATEWAY_PATTERN="local-ai-gateway/server.mjs"
LOCAL_AI_GATEWAY_LOG="$REPO_ROOT/logs/codex-proxy.log"
start_codex_proxy() {
    : > "$LOCAL_AI_GATEWAY_LOG"
    nohup node "$LOCAL_AI_GATEWAY_DIR/server.mjs" >> "$LOCAL_AI_GATEWAY_LOG" 2>&1 &
    echo "local-ai-gateway: started on http://127.0.0.1:8788 (PID $!), log → $LOCAL_AI_GATEWAY_LOG"
}
if [ "${ET_DISABLE_LOCAL_AI_GATEWAY:-0}" = "1" ]; then
    echo "local-ai-gateway: disabled (ET_DISABLE_LOCAL_AI_GATEWAY=1)"
elif pgrep -f "$LOCAL_AI_GATEWAY_PATTERN" >/dev/null 2>&1; then
    if [ "${ET_RESTART_LOCAL_AI_GATEWAY:-0}" = "1" ]; then
        echo "local-ai-gateway: restarting (ET_RESTART_LOCAL_AI_GATEWAY=1)"
        pkill -f "$LOCAL_AI_GATEWAY_PATTERN"
        for _ in 1 2 3 4 5 6 7 8; do pgrep -f "$LOCAL_AI_GATEWAY_PATTERN" >/dev/null 2>&1 || break; sleep 1; done
        if command -v node >/dev/null 2>&1 && command -v codex >/dev/null 2>&1; then
            start_codex_proxy
        else
            echo "local-ai-gateway: cannot restart — node or codex missing" >&2
        fi
    else
        echo "local-ai-gateway: already running — set ET_RESTART_LOCAL_AI_GATEWAY=1 to reload"
    fi
elif ! command -v node >/dev/null 2>&1 || ! command -v codex >/dev/null 2>&1; then
    echo "local-ai-gateway: node or codex not on PATH — OpenRouter will be used" >&2
else
    start_codex_proxy
fi

if [ "$FOREGROUND" = "1" ]; then
    echo "EmergentTerrain: starting in foreground (Ctrl-C to stop)"
    mvn -f "$REACTOR_POM" -pl apps/game-service -am install -DskipTests
    exec mvn -f "$GAME_SERVICE_POM" spring-boot:run
fi

# Background launch. nohup so the process survives shell exit; & to
# detach. stdout merges with stderr into the log.
echo "EmergentTerrain: clean-building and starting in background, stdout → $STDOUT_LOG"
nohup bash -c 'mvn -f "$1" -pl apps/game-service -am install -DskipTests && mvn -f "$2" clean spring-boot:run' \
    _ "$REACTOR_POM" "$GAME_SERVICE_POM" > "$STDOUT_LOG" 2>&1 &
LAUNCHER_PID=$!
echo "EmergentTerrain: launcher PID $LAUNCHER_PID (the actual JVM spawns ~30s later)"
echo
echo "Tail logs:"
echo "  tail -f logs/et-app.log         # Spring's logback file"
echo "  tail -f logs/maven-stdout.log   # Maven + JVM startup banner"
echo
echo "Wait for ready:"
echo "  until curl -fsS http://localhost:8081/actuator/health >/dev/null; do sleep 1; done"
