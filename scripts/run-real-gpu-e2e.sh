#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-real-gpu-e2e.sh [options]

Options:
  --java-home PATH                 JDK home. Defaults to $JAVA_HOME or /usr/lib/jvm/java-21-openjdk
  --maven-cmd CMD                  Maven command. Defaults to $MAVEN_CMD or mvn
  --gpu-repo PATH                  Rust GPU repo path. Defaults to $GPU_REPO or engines/gpu-engine in this monorepo
  --app-port PORT                  Java app port. Default: 8080
  --gpu-port PORT                  GPU engine port. Default: 50051
  --startup-timeout-seconds SEC    Startup timeout. Default: 180
  --bot-poll-seconds SEC           Bot movement poll timeout. Default: 30
  --maintenance-interval-ms MS     Consensus maintenance interval. Default: 500
  --require-gpu-patch              Fail unless rebuild_patch is observed in the GPU log
  --reuse-existing-gpu-engine      Reuse an already-running GPU engine
  --reuse-existing-app             Reuse an already-running Java app
  -h, --help                       Show this help
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

java_home="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
maven_cmd="${MAVEN_CMD:-mvn}"
gpu_repo="${GPU_REPO:-$repo_root/engines/gpu-engine}"
app_port=8080
gpu_port=50051
startup_timeout_seconds=180
bot_poll_seconds=30
maintenance_interval_ms=500
require_gpu_patch=0
reuse_existing_gpu_engine=0
reuse_existing_app=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java-home)
      [[ $# -ge 2 ]] || fail "--java-home requires a value"
      java_home="$2"
      shift 2
      ;;
    --maven-cmd)
      [[ $# -ge 2 ]] || fail "--maven-cmd requires a value"
      maven_cmd="$2"
      shift 2
      ;;
    --gpu-repo)
      [[ $# -ge 2 ]] || fail "--gpu-repo requires a value"
      gpu_repo="$2"
      shift 2
      ;;
    --app-port)
      [[ $# -ge 2 ]] || fail "--app-port requires a value"
      app_port="$2"
      shift 2
      ;;
    --gpu-port)
      [[ $# -ge 2 ]] || fail "--gpu-port requires a value"
      gpu_port="$2"
      shift 2
      ;;
    --startup-timeout-seconds)
      [[ $# -ge 2 ]] || fail "--startup-timeout-seconds requires a value"
      startup_timeout_seconds="$2"
      shift 2
      ;;
    --bot-poll-seconds)
      [[ $# -ge 2 ]] || fail "--bot-poll-seconds requires a value"
      bot_poll_seconds="$2"
      shift 2
      ;;
    --maintenance-interval-ms)
      [[ $# -ge 2 ]] || fail "--maintenance-interval-ms requires a value"
      maintenance_interval_ms="$2"
      shift 2
      ;;
    --require-gpu-patch)
      require_gpu_patch=1
      shift
      ;;
    --reuse-existing-gpu-engine)
      reuse_existing_gpu_engine=1
      shift
      ;;
    --reuse-existing-app)
      reuse_existing_app=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

require_command curl
require_command jq
require_command python3
require_command ps
require_command kill
require_command cargo

timestamp="$(date +%Y%m%d-%H%M%S)"
output_dir="$repo_root/sample-output/gpu-e2e-$timestamp"
mkdir -p "$output_dir"

gpu_log="$output_dir/gpu-engine.log"
gpu_err_log="$output_dir/gpu-engine.err.log"
app_log="$output_dir/java-app.log"
app_err_log="$output_dir/java-app.err.log"
proposal_attempt_log="$output_dir/proposal-attempts.jsonl"

gpu_pid=""
app_pid=""
started_gpu=0
started_app=0

collect_child_pids() {
  local parent_id="$1"
  local child_id
  while IFS= read -r child_id; do
    child_id="${child_id#"${child_id%%[![:space:]]*}"}"
    child_id="${child_id%"${child_id##*[![:space:]]}"}"
    [[ -n "$child_id" ]] || continue
    printf '%s\n' "$child_id"
    collect_child_pids "$child_id"
  done < <(ps -o pid= --ppid "$parent_id" 2>/dev/null || true)
}

stop_process_tree() {
  local root_pid="${1:-}"
  local pid
  local -a ids=()

  [[ -n "$root_pid" ]] || return 0

  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    ids+=("$pid")
  done < <(collect_child_pids "$root_pid")
  ids+=("$root_pid")

  if ((${#ids[@]} > 0)); then
    kill -KILL "${ids[@]}" 2>/dev/null || true
  fi
  wait "$root_pid" 2>/dev/null || true
}

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ $started_app -eq 1 ]]; then
    stop_process_tree "$app_pid"
  fi
  if [[ $started_gpu -eq 1 ]]; then
    stop_process_tree "$gpu_pid"
  fi
  exit "$status"
}

trap cleanup EXIT

test_tcp_port() {
  python3 - "$1" "$2" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
sock = socket.socket()
sock.settimeout(0.5)
try:
    sock.connect((host, port))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
}

wait_tcp_port() {
  local address="$1"
  local port="$2"
  local timeout_seconds="$3"
  local label="$4"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    if test_tcp_port "$address" "$port"; then
      return 0
    fi
    sleep 0.5
  done

  fail "$label did not open $address:$port within $timeout_seconds seconds."
}

wait_http_token() {
  local url="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  local response token

  while ((SECONDS < deadline)); do
    response="$(curl -fsS "$url" 2>/dev/null || true)"
    if [[ -n "$response" ]]; then
      token="$(
        python3 -c 'import re, sys; match = re.search(r'"'"'window\.APP_TOKEN = "([^"]+)"'"'"', sys.stdin.read()); print(match.group(1) if match else "")' <<<"$response"
      )"
      if [[ -n "$token" ]]; then
        printf '%s\n' "$token"
        return 0
      fi
    fi
    sleep 0.5
  done

  fail "Could not obtain a token from $url within $timeout_seconds seconds."
}

api_post() {
  local url="$1"
  local body="$2"
  curl -fsS -H 'Content-Type: application/json' -d "$body" "$url"
}

bot_positions_json() {
  jq -c 'reduce (.bots[]?) as $bot ({}; .[$bot.id] = "\($bot.x),\($bot.y)")'
}

any_bot_moved() {
  local initial_json="$1"
  local current_json="$2"

  jq -e -n \
    --argjson initial "$initial_json" \
    --argjson current "$current_json" \
    'reduce ($current | to_entries[]) as $entry (false; . or (($initial[$entry.key] // "") != $entry.value))' \
    >/dev/null
}

proposal_effect_applied() {
  local response_json="$1"
  local candidate_x="$2"
  local candidate_y="$3"
  local candidate_type="$4"

  jq -e \
    --argjson x "$candidate_x" \
    --argjson y "$candidate_y" \
    --argjson type "$candidate_type" \
    '([.proposals[]? | select(.status == "APPLIED" and .anchorX == $x and .anchorY == $y and .tileType == $type)] | length) > 0
      or (.tiles[$y][$x] == $type)
      or ($type == 4 and (.highways[$y][$x] == true))' \
    <<<"$response_json" \
    >/dev/null
}

assert_no_gpu_fallback_warnings() {
  local log_path
  for log_path in "$@"; do
    [[ -f "$log_path" ]] || continue
    if grep -Eq 'gpu bot simulation failed|gpu world generation failed' "$log_path"; then
      fail "GPU fallback warnings were found in app logs under $output_dir"
    fi
  done
}

assert_log_contains() {
  local log_path="$1"
  shift
  local pattern

  [[ -f "$log_path" ]] || fail "Expected log file was not found: $log_path"

  for pattern in "$@"; do
    grep -Fq -- "$pattern" "$log_path" || fail "Expected pattern '$pattern' was not found in $log_path"
  done
}

if [[ $reuse_existing_gpu_engine -ne 1 ]] && test_tcp_port 127.0.0.1 "$gpu_port"; then
  fail "Port 127.0.0.1:$gpu_port is already in use. Use --reuse-existing-gpu-engine if that is intentional."
fi
if [[ $reuse_existing_app -ne 1 ]] && test_tcp_port 127.0.0.1 "$app_port"; then
  fail "Port 127.0.0.1:$app_port is already in use. Use --reuse-existing-app if that is intentional."
fi

if [[ $reuse_existing_gpu_engine -ne 1 ]]; then
  [[ -d "$gpu_repo" ]] || fail "GPU repo path not found: $gpu_repo"
  (
    export PATH="$HOME/.cargo/bin:$PATH"
    export GPU_ENGINE_ADDR="127.0.0.1:$gpu_port"
    cd "$gpu_repo"
    cargo run
  ) >"$gpu_log" 2>"$gpu_err_log" &
  gpu_pid=$!
  started_gpu=1
fi

wait_tcp_port 127.0.0.1 "$gpu_port" "$startup_timeout_seconds" "GPU engine"

if [[ $reuse_existing_app -ne 1 ]]; then
  [[ -d "$java_home" ]] || fail "Java home path not found: $java_home"
  read -r -a maven_cmd_parts <<<"$maven_cmd"
  ((${#maven_cmd_parts[@]} > 0)) || fail "Maven command cannot be empty"

  (
    export JAVA_HOME="$java_home"
    export PATH="$java_home/bin:$PATH"
    export APP_E2E_ENABLED=true
    export APP_GPU_ENABLED=true
    export APP_GPU_WORLD_ENABLED=true
    export APP_GPU_BOTS_ENABLED=true
    export APP_GPU_BOTS_APPLYEDITS=true
    export APP_GPU_BOTS_CACHEWORLD=true
    export APP_GPU_BOTS_STEPS=10
    export APP_GPU_BOTS_MINBOTCOUNT=1
    export APP_GPU_BOTS_MINWORKITEMS=1
    export APP_GPU_PATCH_MINAREA=1
    export APP_BOTS_INITIALROOMCREATETARGET=1000
    export APP_GPU_HOST=127.0.0.1
    export APP_GPU_PORT="$gpu_port"
    export APP_CONSENSUS_MAINTENANCEINTERVALMS="$maintenance_interval_ms"
    export APP_CONSENSUS_MINVOTESTORESOLVE=1
    export APP_CONSENSUS_MINNEIGHBORS=1
    export APP_CONSENSUS_QUORUMCAP=1
    cd "$repo_root"
    "${maven_cmd_parts[@]}" spring-boot:run
  ) >"$app_log" 2>"$app_err_log" &
  app_pid=$!
  started_app=1
fi

wait_tcp_port 127.0.0.1 "$app_port" "$startup_timeout_seconds" "Java app"

base_url="http://127.0.0.1:$app_port"
token="$(wait_http_token "$base_url/game/test" "$startup_timeout_seconds")"

world_state="$(api_post "$base_url/game/state" "$(jq -cn --arg token "$token" '{token:$token}')")"
world_width="$(jq -r 'if (.tiles | length) > 0 then (.tiles[0] | length) else 0 end' <<<"$world_state")"
world_height="$(jq -r '.tiles | length' <<<"$world_state")"
non_zero_tiles="$(jq -r '[.tiles[][] | select(. != 0)] | length' <<<"$world_state")"
highway_count="$(jq -r '[.highways[][] | select(. == true)] | length' <<<"$world_state")"

[[ "$world_width" -gt 0 && "$world_height" -gt 0 ]] || fail "World state did not include a valid tile grid."
[[ "$non_zero_tiles" -gt 0 && "$highway_count" -gt 0 ]] || fail "Expected the GPU engine to yield a non-stub world with non-zero tiles and highways, got nonZeroTiles=$non_zero_tiles highwayCount=$highway_count."

room_create="$(api_post "$base_url/room/create" "$(jq -cn --arg token "$token" --arg name "Real GPU E2E Room $timestamp" '{token:$token,name:$name}')")"
room_id="$(jq -r '.roomId // empty' <<<"$room_create")"
[[ -n "$room_id" ]] || fail "Room creation did not return a roomId."

initial_bots="$(bot_positions_json <<<"$room_create")"
initial_bot_count="$(jq -r 'length' <<<"$initial_bots")"
[[ "$initial_bot_count" -gt 0 ]] || fail "Room creation did not return any bots."

moved=0
last_state=""
bot_deadline=$((SECONDS + bot_poll_seconds))
while ((SECONDS < bot_deadline)); do
  sleep 1
  last_state="$(api_post "$base_url/room/$room_id/state" "$(jq -cn --arg token "$token" --arg roomId "$room_id" '{token:$token,roomId:$roomId}')")"
  current_bots="$(bot_positions_json <<<"$last_state")"
  if any_bot_moved "$initial_bots" "$current_bots"; then
    moved=1
    break
  fi
done

[[ $moved -eq 1 ]] || fail "No bot positions changed within $bot_poll_seconds seconds."

proposal_state="$(api_post "$base_url/room/$room_id/state" "$(jq -cn --arg token "$token" --arg roomId "$room_id" '{token:$token,roomId:$roomId}')")"
participant_json="$(jq -c --arg participantId "$(jq -r '.participantId // empty' <<<"$room_create")" '([.participants[]? | select(.id == $participantId)][0] // .participants[0] // empty)' <<<"$proposal_state")"
[[ -n "$participant_json" && "$participant_json" != "null" ]] || fail "Could not locate a participant for proposal testing."

participant_x="$(jq -r '.x' <<<"$participant_json")"
participant_y="$(jq -r '.y' <<<"$participant_json")"

proposal_applied=0
proposal_x=""
proposal_y=""
proposal_type=""

declare -a offsets=("0:0" "1:0" "0:1" "-1:0" "0:-1")
for offset in "${offsets[@]}"; do
  IFS=: read -r dx dy <<<"$offset"
  candidate_x=$((participant_x + dx))
  candidate_y=$((participant_y + dy))

  if ((candidate_x < 0)); then candidate_x=0; fi
  if ((candidate_y < 0)); then candidate_y=0; fi
  if ((candidate_x >= world_width)); then candidate_x=$((world_width - 1)); fi
  if ((candidate_y >= world_height)); then candidate_y=$((world_height - 1)); fi

  current_tile="$(jq -r --argjson x "$candidate_x" --argjson y "$candidate_y" '.tiles[$y][$x]' <<<"$proposal_state")"
  current_highway="$(jq -r --argjson x "$candidate_x" --argjson y "$candidate_y" '.highways[$y][$x]' <<<"$proposal_state")"

  declare -A seen_types=()
  declare -a preferred_types=()
  if [[ "$current_highway" != "true" ]]; then
    preferred_types+=(4)
    seen_types[4]=1
  fi
  if [[ "$current_tile" -eq 1 ]]; then
    candidate_type=0
  else
    candidate_type=1
  fi
  if [[ -z "${seen_types[$candidate_type]+x}" ]]; then
    preferred_types+=("$candidate_type")
    seen_types[$candidate_type]=1
  fi
  for candidate_type in 2 3; do
    if [[ -z "${seen_types[$candidate_type]+x}" ]]; then
      preferred_types+=("$candidate_type")
      seen_types[$candidate_type]=1
    fi
  done
  unset seen_types

  for candidate_type in "${preferred_types[@]}"; do
    if [[ "$candidate_type" -eq 4 ]]; then
      expected_tile=0
      expected_highway=true
    else
      expected_tile="$candidate_type"
      expected_highway=false
    fi

    if [[ "$current_tile" -eq "$expected_tile" && "$current_highway" == "$expected_highway" ]]; then
      continue
    fi

    proposal_note="gpu-e2e-proposal-$timestamp-$candidate_x-$candidate_y-$candidate_type"
    proposal_response="$(
      api_post \
        "$base_url/room/$room_id/propose" \
        "$(jq -c \
          --arg token "$token" \
          --arg note "$proposal_note" \
          --argjson x "$candidate_x" \
          --argjson y "$candidate_y" \
          --argjson type "$candidate_type" \
          '{token:$token,tiles:(.tiles | .[$y][$x] = $type),delta:{x:$x,y:$y,type:$type},note:$note}' <<<"$proposal_state")"
    )"

    jq -c \
      --argjson x "$candidate_x" \
      --argjson y "$candidate_y" \
      --argjson type "$candidate_type" \
      --argjson expectedTile "$expected_tile" \
      --argjson expectedHighway "$([[ "$expected_highway" == true ]] && echo true || echo false)" \
      '{
        candidate_x: $x,
        candidate_y: $y,
        candidate_type: $type,
        expected_tile: $expectedTile,
        expected_highway: $expectedHighway,
        response_tile: .tiles[$y][$x],
        response_highway: .highways[$y][$x],
        response_proposals: [.proposals[]? | {
          id,
          status,
          tileType,
          anchorX,
          anchorY,
          approvals,
          rejections,
          requiredVotes
        }]
      }' \
      <<<"$proposal_response" >>"$proposal_attempt_log"

    if proposal_effect_applied "$proposal_response" "$candidate_x" "$candidate_y" "$candidate_type"; then
      proposal_applied=1
      proposal_x="$candidate_x"
      proposal_y="$candidate_y"
      proposal_type="$candidate_type"
      break
    fi

    proposal_deadline=$((SECONDS + 10))
    while ((SECONDS < proposal_deadline)); do
      sleep 1
      proposal_state="$(api_post "$base_url/room/$room_id/state" "$(jq -cn --arg token "$token" --arg roomId "$room_id" '{token:$token,roomId:$roomId}')")"
      if proposal_effect_applied "$proposal_state" "$candidate_x" "$candidate_y" "$candidate_type"; then
        proposal_applied=1
        proposal_x="$candidate_x"
        proposal_y="$candidate_y"
        proposal_type="$candidate_type"
        break
      fi
    done

    if [[ $proposal_applied -eq 1 ]]; then
      break
    fi
  done

  if [[ $proposal_applied -eq 1 ]]; then
    break
  fi
done

[[ $proposal_applied -eq 1 ]] || fail "No proposal candidate was applied within the allotted time."

if [[ $started_app -eq 1 ]]; then
  stop_process_tree "$app_pid"
  started_app=0
  sleep 1
  assert_no_gpu_fallback_warnings "$app_log" "$app_err_log"
fi

if [[ $started_gpu -eq 1 ]]; then
  stop_process_tree "$gpu_pid"
  started_gpu=0
fi

gpu_patch_observed=0
if [[ -f "$gpu_log" ]] && grep -Fq 'rebuild_patch request_id=' "$gpu_log" && grep -Fq '"backend":"wgpu-patch"' "$gpu_log"; then
  gpu_patch_observed=1
fi
if [[ $require_gpu_patch -eq 1 && $gpu_patch_observed -ne 1 ]]; then
  fail "Expected rebuild_patch proof in $gpu_log but it was not found."
fi

if [[ $started_gpu -eq 0 && -f "$gpu_log" ]]; then
  required_patterns=(
    'generate_region request_id='
    'simulate_bots request_id='
    '"backend":"wgpu-world"'
    '"backend":"wgpu-bot-sim"'
  )
  if [[ $require_gpu_patch -eq 1 ]]; then
    required_patterns+=('rebuild_patch request_id=' '"backend":"wgpu-patch"')
  fi
  assert_log_contains "$gpu_log" "${required_patterns[@]}"
fi

jq -n \
  --argjson world_width "$world_width" \
  --argjson world_height "$world_height" \
  --argjson non_zero_tiles "$non_zero_tiles" \
  --argjson highway_count "$highway_count" \
  --arg room_id "$room_id" \
  --argjson initial_bot_count "$initial_bot_count" \
  --argjson bot_move_detected true \
  --argjson proposal_applied true \
  --argjson proposal_x "$proposal_x" \
  --argjson proposal_y "$proposal_y" \
  --argjson proposal_type "$proposal_type" \
  --argjson gpu_patch_observed "$gpu_patch_observed" \
  --arg gpu_log "$gpu_log" \
  --arg gpu_err_log "$gpu_err_log" \
  --arg app_log "$app_log" \
  --arg app_err_log "$app_err_log" \
  '{
    world_width: $world_width,
    world_height: $world_height,
    non_zero_tiles: $non_zero_tiles,
    highway_count: $highway_count,
    room_id: $room_id,
    initial_bot_count: $initial_bot_count,
    bot_move_detected: $bot_move_detected,
    proposal_applied: $proposal_applied,
    proposal_x: $proposal_x,
    proposal_y: $proposal_y,
    proposal_type: $proposal_type,
    gpu_patch_observed: ($gpu_patch_observed == 1),
    gpu_log: $gpu_log,
    gpu_err_log: $gpu_err_log,
    app_log: $app_log,
    app_err_log: $app_err_log
  }'
