#!/usr/bin/env bash
set -euo pipefail

JAVA_REPO="${JAVA_REPO:-/home/umesh/workspace/EmergentTerrain}"
GPU_REPO="${GPU_REPO:-/home/umesh/workspace/EmergentTerrainGPUEngine}"
APP_PORT="${APP_PORT:-8080}"
GPU_PORT="${GPU_PORT:-50051}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-180}"
BOT_POLL_SECONDS="${BOT_POLL_SECONDS:-20}"
MAINTENANCE_INTERVAL_MS="${MAINTENANCE_INTERVAL_MS:-500}"

timestamp="$(date +%Y%m%d-%H%M%S)"
output_dir="$JAVA_REPO/sample-output/gpu-e2e-wsl-$timestamp"
gpu_log="$output_dir/gpu-engine.log"
gpu_err_log="$output_dir/gpu-engine.err.log"
app_log="$output_dir/java-app.log"
app_err_log="$output_dir/java-app.err.log"
mkdir -p "$output_dir"

gpu_pid=""
app_pid=""

now_ms() {
  date +%s%3N
}

kill_tree() {
  local pid="$1"
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child"
  done
  kill -TERM "$pid" 2>/dev/null || true
}

cleanup() {
  local exit_code="$?"
  if [[ -n "${app_pid}" ]]; then
    kill_tree "$app_pid"
    wait "$app_pid" 2>/dev/null || true
  fi
  if [[ -n "${gpu_pid}" ]]; then
    kill_tree "$gpu_pid"
    wait "$gpu_pid" 2>/dev/null || true
  fi
  exit "$exit_code"
}
trap cleanup EXIT

port_ready() {
  python3 - "$1" "$2" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.settimeout(0.5)
    try:
        sock.connect((host, port))
    except OSError:
        raise SystemExit(1)
raise SystemExit(0)
PY
}

wait_for_port() {
  local host="$1"
  local port="$2"
  local timeout="$3"
  local label="$4"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if port_ready "$host" "$port"; then
      return 0
    fi
    sleep 0.5
  done
  echo "$label did not open $host:$port within $timeout seconds." >&2
  return 1
}

wait_for_token() {
  local url="$1"
  local timeout="$2"
  local deadline=$((SECONDS + timeout))
  local html_file="$output_dir/game-test.html"
  while (( SECONDS < deadline )); do
    if curl -fsS "$url" -o "$html_file"; then
      if token="$(python3 - "$html_file" <<'PY'
import re
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    content = fh.read()
match = re.search(r'window\.APP_TOKEN = "([^"]+)"', content)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
)"; then
        printf '%s\n' "$token"
        return 0
      fi
    fi
    sleep 0.5
  done
  echo "Could not obtain a token from $url within $timeout seconds." >&2
  return 1
}

assert_world_stub_signal() {
  python3 - "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)

tiles = data["tiles"]
highways = data["highways"]
width = len(tiles[0]) if tiles else 0
height = len(tiles)
non_zero_tiles = sum(1 for row in tiles for cell in row if int(cell) != 0)
highway_count = sum(1 for row in highways for cell in row if bool(cell))

if width <= 0 or height <= 0:
    raise SystemExit("World state did not include a valid tile grid.")
if non_zero_tiles != 0 or highway_count != 0:
    raise SystemExit(
        f"Expected current Rust stub signal, got non_zero_tiles={non_zero_tiles} highway_count={highway_count}"
    )

print(width)
print(height)
print(non_zero_tiles)
print(highway_count)
PY
}

extract_room_summary() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)

bots = data.get("bots") or []
positions = {bot["id"]: [bot["x"], bot["y"]] for bot in bots}
with open(sys.argv[2], "w", encoding="utf-8") as out:
    json.dump(positions, out, sort_keys=True)

room_id = data.get("roomId") or ""
if not room_id:
    raise SystemExit("Room creation did not return a roomId.")
if not positions:
    raise SystemExit("Room creation did not return any bots.")

print(room_id)
print(len(positions))
PY
}

bots_moved() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    initial = json.load(fh)
with open(sys.argv[2], encoding="utf-8") as fh:
    state = json.load(fh)

current = {bot["id"]: [bot["x"], bot["y"]] for bot in state.get("bots") or []}
for bot_id, start in initial.items():
    if bot_id in current and current[bot_id] != start:
        raise SystemExit(0)
raise SystemExit(1)
PY
}

if port_ready "127.0.0.1" "$GPU_PORT"; then
  echo "Port 127.0.0.1:$GPU_PORT is already in use." >&2
  exit 1
fi
if port_ready "127.0.0.1" "$APP_PORT"; then
  echo "Port 127.0.0.1:$APP_PORT is already in use." >&2
  exit 1
fi

overall_start_ms="$(now_ms)"
gpu_start_ms="$(now_ms)"
(
  cd "$GPU_REPO"
  source "$HOME/.cargo/env" 2>/dev/null || true
  export PATH="$HOME/.cargo/bin:$PATH"
  export GPU_ENGINE_ADDR="127.0.0.1:$GPU_PORT"
  cargo run >"$gpu_log" 2>"$gpu_err_log"
) &
gpu_pid="$!"

wait_for_port "127.0.0.1" "$GPU_PORT" "$STARTUP_TIMEOUT_SECONDS" "GPU engine"
gpu_ready_ms="$(now_ms)"

app_start_ms="$(now_ms)"
(
  cd "$JAVA_REPO"
  export APP_E2E_ENABLED=true
  export APP_GPU_ENABLED=true
  export APP_GPU_WORLD_ENABLED=true
  export APP_GPU_BOTS_ENABLED=true
  export APP_GPU_BOTS_APPLYEDITS=true
  export APP_GPU_BOTS_CACHEWORLD=false
  export APP_GPU_BOTS_STEPS=1
  export APP_GPU_HOST=127.0.0.1
  export APP_GPU_PORT="$GPU_PORT"
  export APP_CONSENSUS_MAINTENANCEINTERVALMS="$MAINTENANCE_INTERVAL_MS"
  mvn spring-boot:run >"$app_log" 2>"$app_err_log"
) &
app_pid="$!"

wait_for_port "127.0.0.1" "$APP_PORT" "$STARTUP_TIMEOUT_SECONDS" "Java app"
app_ready_ms="$(now_ms)"

base_url="http://127.0.0.1:$APP_PORT"
token_start_ms="$(now_ms)"
token="$(wait_for_token "$base_url/game/test" "$STARTUP_TIMEOUT_SECONDS")"
token_ready_ms="$(now_ms)"

world_state_json="$output_dir/world-state.json"
world_request_start_ms="$(now_ms)"
curl -fsS "$base_url/game/state" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"$token\"}" \
  -o "$world_state_json"
world_request_end_ms="$(now_ms)"

mapfile -t world_summary < <(assert_world_stub_signal "$world_state_json")
world_width="${world_summary[0]}"
world_height="${world_summary[1]}"
non_zero_tiles="${world_summary[2]}"
highway_count="${world_summary[3]}"

room_create_json="$output_dir/room-create.json"
room_create_start_ms="$(now_ms)"
curl -fsS "$base_url/room/create" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"$token\",\"name\":\"WSL GPU E2E Room $timestamp\"}" \
  -o "$room_create_json"
room_create_end_ms="$(now_ms)"

initial_positions_json="$output_dir/initial-bot-positions.json"
mapfile -t room_summary < <(extract_room_summary "$room_create_json" "$initial_positions_json")
room_id="${room_summary[0]}"
initial_bot_count="${room_summary[1]}"

room_state_json="$output_dir/room-state.json"
bot_move_detected=false
bot_poll_start_ms="$(now_ms)"
bot_poll_count=0
deadline=$((SECONDS + BOT_POLL_SECONDS))
while (( SECONDS < deadline )); do
  sleep 1
  bot_poll_count=$((bot_poll_count + 1))
  curl -fsS "$base_url/room/$room_id/state" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$token\",\"roomId\":\"$room_id\"}" \
    -o "$room_state_json"
  if bots_moved "$initial_positions_json" "$room_state_json"; then
    bot_move_detected=true
    break
  fi
done

if [[ "$bot_move_detected" != "true" ]]; then
  echo "No bot positions changed within $BOT_POLL_SECONDS seconds." >&2
  exit 1
fi
bot_move_ready_ms="$(now_ms)"

if grep -qE 'gpu bot simulation failed|gpu world generation failed' "$app_log" "$app_err_log"; then
  echo "GPU fallback warnings were found in app logs under $output_dir" >&2
  exit 1
fi

gpu_start_time_ms=$((gpu_ready_ms - gpu_start_ms))
app_start_time_ms=$((app_ready_ms - app_start_ms))
token_fetch_time_ms=$((token_ready_ms - token_start_ms))
world_request_time_ms=$((world_request_end_ms - world_request_start_ms))
room_create_time_ms=$((room_create_end_ms - room_create_start_ms))
bot_move_detect_time_ms=$((bot_move_ready_ms - bot_poll_start_ms))
total_time_ms=$((bot_move_ready_ms - overall_start_ms))

printf 'world_width=%s\n' "$world_width"
printf 'world_height=%s\n' "$world_height"
printf 'non_zero_tiles=%s\n' "$non_zero_tiles"
printf 'highway_count=%s\n' "$highway_count"
printf 'room_id=%s\n' "$room_id"
printf 'initial_bot_count=%s\n' "$initial_bot_count"
printf 'bot_move_detected=%s\n' "$bot_move_detected"
printf 'gpu_start_time_ms=%s\n' "$gpu_start_time_ms"
printf 'app_start_time_ms=%s\n' "$app_start_time_ms"
printf 'token_fetch_time_ms=%s\n' "$token_fetch_time_ms"
printf 'world_request_time_ms=%s\n' "$world_request_time_ms"
printf 'room_create_time_ms=%s\n' "$room_create_time_ms"
printf 'bot_move_detect_time_ms=%s\n' "$bot_move_detect_time_ms"
printf 'bot_state_poll_count=%s\n' "$bot_poll_count"
printf 'total_time_ms=%s\n' "$total_time_ms"
printf 'gpu_log=%s\n' "$gpu_log"
printf 'gpu_err_log=%s\n' "$gpu_err_log"
printf 'app_log=%s\n' "$app_log"
printf 'app_err_log=%s\n' "$app_err_log"
