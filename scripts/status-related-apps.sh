#!/usr/bin/env bash
# Print health for the Spring app, the two local subscription proxies, Julia
# math sidecars, and Rust GPU engine. This script is read-only: it never
# starts, stops, or restarts a process. For a listening service, it resolves
# the listener PID and prints that process's exact active command line from
# `ps`.
#
# Usage:
#   ./scripts/status-related-apps.sh
#
# Optional overrides are useful when a service was deliberately moved:
#   ET_APP_URL=http://127.0.0.1:9081 \
#   ET_JULIA_URL=http://127.0.0.1:9088 \
#   ET_PY_SIDECAR_URL=http://127.0.0.1:9089 \
#   ./scripts/status-related-apps.sh
#
# Exit status: 0 when all services are healthy/listening, 1 when any is not.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSPACE_ROOT="$(cd "$REPO_ROOT/.." && pwd)"
JULIA_SIDECAR_ROOT="$WORKSPACE_ROOT/julia-math-sidecar"
GPU_ENGINE_ROOT="$WORKSPACE_ROOT/EmergentTerrainGPUEngine"

APP_URL="${ET_APP_URL:-http://127.0.0.1:8081/actuator/health}"
LEGACY_AI_GATEWAY_URL="${ET_LEGACY_AI_GATEWAY_URL:-http://127.0.0.1:8787/healthz}"
LOCAL_AI_GATEWAY_URL="${ET_LOCAL_AI_GATEWAY_URL:-http://127.0.0.1:8788/healthz}"
JULIA_URL="${ET_JULIA_URL:-http://127.0.0.1:8088/healthz}"
PY_SIDECAR_URL="${ET_PY_SIDECAR_URL:-http://127.0.0.1:8089/healthz}"
APP_PORT="${ET_APP_PORT:-8081}"
LEGACY_AI_GATEWAY_PORT="${ET_LEGACY_AI_GATEWAY_PORT:-8787}"
LOCAL_AI_GATEWAY_PORT="${ET_LOCAL_AI_GATEWAY_PORT:-8788}"
JULIA_PORT="${ET_JULIA_PORT:-8088}"
PY_SIDECAR_PORT="${ET_PY_SIDECAR_PORT:-8089}"
GPU_ENGINE_PORT="${ET_GPU_ENGINE_PORT:-50051}"
TIMEOUT_SECONDS="${ET_STATUS_TIMEOUT_SECONDS:-3}"
COMMAND_DISPLAY_CHARS="${ET_STATUS_COMMAND_CHARS:-360}"

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    GREEN=$'\033[32m'
    RED=$'\033[31m'
    CYAN=$'\033[36m'
    YELLOW=$'\033[33m'
    RESET=$'\033[0m'
else
    GREEN=""
    RED=""
    CYAN=""
    YELLOW=""
    RESET=""
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required to check service health." >&2
    exit 1
fi

overall_status=0

colored() {
    printf '%s%s%s' "$1" "$2" "$RESET"
}

command_for_listener() {
    local port="$1"
    local listener pid command head_chars tail_chars

    listener="$(ss -ltnp "sport = :$port" 2>/dev/null || true)"
    pid="$(sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' <<< "$listener" | head -n 1)"
    if [[ -z "$pid" ]]; then
        return 0
    fi
    command="$(ps -p "$pid" -o args= 2>/dev/null | sed 's/^[[:space:]]*//')"
    if [[ -n "$command" ]]; then
        if (( ${#command} > COMMAND_DISPLAY_CHARS )); then
            head_chars=$((COMMAND_DISPLAY_CHARS - 120))
            tail_chars=100
            command="${command:0:head_chars} … ${command: -tail_chars} [truncated; full: ps -p $pid -o args=]"
        fi
        printf 'PID %s: %s' "$pid" "$command"
    fi
}

check_service() {
    local name="$1"
    local url="$2"
    local start_command="$3"
    local branch="$4"
    local child_indent="$5"
    local port="$6"
    local expectation="${7:-required}"
    local start_label="${8:-start if needed}"
    local response_file header_file http_status response location active_command

    response_file="$(mktemp)"
    header_file="$(mktemp)"
    if http_status="$(curl --silent --max-time "$TIMEOUT_SECONDS" \
        --dump-header "$header_file" --output "$response_file" --write-out '%{http_code}' "$url")"; then
        response="$(tr '\n' ' ' < "$response_file" | tr -s ' ' | cut -c1-180)"
        if [[ "$http_status" == 2* ]]; then
            printf '%s%s — %s (HTTP %s): %s\n' "$branch" "$name" "$(colored "$GREEN" "READY")" "$http_status" "$response"
        elif [[ "$http_status" =~ ^30[12378]$ ]]; then
            location="$(awk 'tolower($1) == "location:" { sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); value=$0 } END { print value }' "$header_file")"
            if [[ "$location" == */login ]]; then
                printf '%s%s — %s (HTTP %s → /login; health endpoint protected)\n' "$branch" "$name" "$(colored "$GREEN" "READY")" "$http_status"
            else
                printf '%s%s — %s (HTTP %s): redirect to %s\n' "$branch" "$name" "$(colored "$RED" "ERROR")" "$http_status" "${location:-unknown}"
                overall_status=1
            fi
        else
            printf '%s%s — %s (HTTP %s): %s\n' "$branch" "$name" "$(colored "$RED" "ERROR")" "$http_status" "$response"
            overall_status=1
        fi
    else
        response="$(tr '\n' ' ' < "$response_file" | tr -s ' ' | cut -c1-180)"
        if [[ "$expectation" == "optional" ]]; then
            printf '%s%s — %s: %s%s\n' "$branch" "$name" "$(colored "$YELLOW" "DORMANT")" "$url" "${response:+ — $response}"
        else
            printf '%s%s — %s: %s%s\n' "$branch" "$name" "$(colored "$RED" "DOWN")" "$url" "${response:+ — $response}"
            overall_status=1
        fi
    fi
    rm -f "$response_file"
    rm -f "$header_file"
    active_command="$(command_for_listener "$port")"
    if [[ -n "$active_command" ]]; then
        printf '%s├── %s\n' "$child_indent" "$(colored "$CYAN" "active command: $active_command")"
    else
        printf '%s├── %s\n' "$child_indent" "$(colored "$CYAN" "active command: unavailable (no process identified on port $port)")"
    fi
    printf '%s└── %s\n' "$child_indent" "$(colored "$CYAN" "$start_label: $start_command")"
}

check_gpu_engine() {
    local listeners start_command branch child_indent active_command
    branch="$1"
    child_indent="$2"
    start_command="cd $GPU_ENGINE_ROOT && cargo run"
    listeners="$(ss -ltnH "sport = :$GPU_ENGINE_PORT" 2>/dev/null || true)"
    if [[ -n "$listeners" ]]; then
        printf '%sRust GPU engine — %s: TCP port %s is listening\n' "$branch" "$(colored "$GREEN" "READY")" "$GPU_ENGINE_PORT"
    else
        printf '%sRust GPU engine — %s: TCP port %s is not listening\n' "$branch" "$(colored "$RED" "DOWN")" "$GPU_ENGINE_PORT"
        overall_status=1
    fi
    active_command="$(command_for_listener "$GPU_ENGINE_PORT")"
    if [[ -n "$active_command" ]]; then
        printf '%s├── %s\n' "$child_indent" "$(colored "$CYAN" "active command: $active_command")"
    else
        printf '%s├── %s\n' "$child_indent" "$(colored "$CYAN" "active command: unavailable (no process identified on port $GPU_ENGINE_PORT)")"
    fi
    printf '%s└── %s\n' "$child_indent" "$(colored "$CYAN" "start if needed: $start_command")"
}

echo "EmergentTerrain related-service status (HTTP timeout: ${TIMEOUT_SECONDS}s)"
check_service "EmergentTerrain app" "$APP_URL" "cd $REPO_ROOT && ./scripts/start.sh" "├── " "│   " "$APP_PORT"
check_service "local-ai-gateway" "$LOCAL_AI_GATEWAY_URL" "cd $REPO_ROOT && ./scripts/start.sh" "├── " "│   " "$LOCAL_AI_GATEWAY_PORT" "required" "started with app"
check_service "legacy-ai-gateway" "$LEGACY_AI_GATEWAY_URL" "cd $REPO_ROOT && ET_ENABLE_LEGACY_AI_GATEWAY=1 ./scripts/start.sh" "├── " "│   " "$LEGACY_AI_GATEWAY_PORT" "optional" "started with app when enabled"
check_service "Julia math sidecar" "$JULIA_URL" "cd $JULIA_SIDECAR_ROOT && JULIA_NUM_THREADS=4 PY_SIDECAR_ENABLED=true ET_DISCOVER_EXEC=1 ./scripts/start.sh" "├── " "│   " "$JULIA_PORT"
check_service "PyCall/grounding sidecar" "$PY_SIDECAR_URL" "cd $JULIA_SIDECAR_ROOT && ET_GROUND_SUBJECTS=mathematics,msc:11 ./scripts/start-py-sidecar.sh" "├── " "│   " "$PY_SIDECAR_PORT"
check_gpu_engine "└── " "    "

exit "$overall_status"
