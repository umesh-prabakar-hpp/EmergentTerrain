#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: compare-cpu-vs-gpu-performance.sh [options]

Options:
  --iterations N                    Number of CPU and GPU runs. Default: 2
  --bot-simulation-iterations N     Iterations for /perf/room/{roomId}/bot-simulation. Default: 10
  --java-home PATH                  JDK home. Defaults to $JAVA_HOME or /usr/lib/jvm/java-21-openjdk
  --maven-cmd CMD                   Maven command. Defaults to $MAVEN_CMD or mvn
  --java-repo PATH                  Java repo path. Default: /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
  --gpu-repo PATH                   GPU repo path. Default: /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
  --build-artifacts                 Force rebuild of the Spring Boot jar and Rust release binary
  -h, --help                        Show this help
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

millis() {
  date +%s%3N
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_repo_default="$(cd "$script_dir/.." && pwd)"

iterations=2
bot_simulation_iterations=10
java_home="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
maven_cmd="${MAVEN_CMD:-mvn}"
java_repo="${JAVA_REPO:-$java_repo_default}"
gpu_repo="${GPU_REPO:-/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine}"
build_artifacts=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --iterations)
      [[ $# -ge 2 ]] || fail "--iterations requires a value"
      iterations="$2"
      shift 2
      ;;
    --bot-simulation-iterations)
      [[ $# -ge 2 ]] || fail "--bot-simulation-iterations requires a value"
      bot_simulation_iterations="$2"
      shift 2
      ;;
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
    --java-repo)
      [[ $# -ge 2 ]] || fail "--java-repo requires a value"
      java_repo="$2"
      shift 2
      ;;
    --gpu-repo)
      [[ $# -ge 2 ]] || fail "--gpu-repo requires a value"
      gpu_repo="$2"
      shift 2
      ;;
    --build-artifacts)
      build_artifacts=1
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

[[ -d "$java_repo" ]] || fail "Java repo path not found: $java_repo"
[[ -d "$gpu_repo" ]] || fail "GPU repo path not found: $gpu_repo"
[[ -d "$java_home" ]] || fail "Java home path not found: $java_home"

java_cmd="$java_home/bin/java"
jar_cmd="$java_home/bin/jar"
jar_path="$java_repo/target/emergent-terrain-0.0.1-SNAPSHOT.jar"
gpu_exe="$gpu_repo/target/release/emergent_terrain_gpu_engine"
output_root="$java_repo/sample-output"
mkdir -p "$output_root"

read -r -a maven_cmd_parts <<<"$maven_cmd"
((${#maven_cmd_parts[@]} > 0)) || fail "Maven command cannot be empty"

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
    sleep 0.25
  done

  fail "$label did not open $address:$port within $timeout_seconds seconds."
}

api_post() {
  local url="$1"
  local body="$2"
  curl -fsS -H 'Content-Type: application/json' -d "$body" "$url"
}

get_test_token() {
  local base_url="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  local response token

  while ((SECONDS < deadline)); do
    response="$(curl -fsS "$base_url/game/test-token" 2>/dev/null || true)"
    token="$(jq -r '.token // empty' <<<"$response" 2>/dev/null || true)"
    if [[ -n "$token" ]]; then
      printf '%s\n' "$token"
      return 0
    fi
    sleep 0.25
  done

  fail "Could not obtain a token from $base_url within $timeout_seconds seconds."
}

world_stats_json() {
  jq -c '{
    width: (if (.tiles | length) > 0 then (.tiles[0] | length) else 0 end),
    height: (.tiles | length),
    non_zero_tiles: ([.tiles[][] | select(. != 0)] | length),
    highway_count: ([.highways[][] | select(. == true)] | length)
  }'
}

proposal_candidates_json() {
  local room_state_json="$1"
  local participant_id="$2"

  python3 -c '
import json
import sys

participant_id = sys.argv[1]
state = json.load(sys.stdin)
participants = state.get("participants") or []
participant = next((p for p in participants if p.get("id") == participant_id), None)
if participant is None and participants:
    participant = participants[0]
if participant is None:
    raise SystemExit("Could not locate a participant for proposal testing.")

tiles = state.get("tiles") or []
highways = state.get("highways") or []
height = len(tiles)
width = len(tiles[0]) if height > 0 else 0
offsets = [(0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)]
candidates = []

for dx, dy in offsets:
    x = max(0, min(width - 1, int(participant["x"]) + dx))
    y = max(0, min(height - 1, int(participant["y"]) + dy))
    current_tile = int(tiles[y][x])
    current_highway = bool(highways[y][x])

    preferred_types = []
    if not current_highway:
        preferred_types.append(4)
    preferred_types.append(0 if current_tile == 1 else 1)
    preferred_types.extend([2, 3])

    seen = set()
    for tile_type in preferred_types:
        if tile_type in seen:
            continue
        seen.add(tile_type)
        expected_tile = 0 if tile_type == 4 else tile_type
        expected_highway = tile_type == 4
        if current_tile == expected_tile and current_highway == expected_highway:
            continue
        candidates.append({
            "x": x,
            "y": y,
            "type": tile_type,
            "expectedTile": expected_tile,
            "expectedHighway": expected_highway,
        })

print(json.dumps(candidates))
' "$participant_id" <<<"$room_state_json"
}

proposal_effect_applied() {
  local response_json="$1"
  local candidate_x="$2"
  local candidate_y="$3"
  local expected_tile="$4"
  local expected_highway="$5"

  jq -e \
    --argjson x "$candidate_x" \
    --argjson y "$candidate_y" \
    --argjson expectedTile "$expected_tile" \
    --argjson expectedHighway "$expected_highway" \
    '(.tiles[$y][$x] == $expectedTile) and (.highways[$y][$x] == $expectedHighway)' \
    <<<"$response_json" \
    >/dev/null
}

build_artifacts_if_needed() {
  local needs_boot_jar=1

  if [[ -f "$jar_path" ]]; then
    if "$jar_cmd" tf "$jar_path" 2>/dev/null | grep -q '^BOOT-INF/'; then
      needs_boot_jar=0
    fi
  fi

  if [[ $build_artifacts -eq 1 || $needs_boot_jar -eq 1 ]]; then
    (
      export JAVA_HOME="$java_home"
      export PATH="$java_home/bin:$PATH"
      cd "$java_repo"
      "${maven_cmd_parts[@]}" -DskipTests package spring-boot:repackage
    )
  fi

  if [[ $build_artifacts -eq 1 || ! -x "$gpu_exe" ]]; then
    (
      cd "$gpu_repo"
      cargo build --release
    )
  fi
}

run_scenario() {
  local mode="$1"
  local iteration="$2"
  local app_port=8080
  local gpu_port=50051
  local tag output_dir app_log app_err_log gpu_log gpu_err_log
  local gpu_pid="" app_pid=""
  local startup_started gpu_ready_ms=0 app_ready_ms start_ms end_ms
  local base_url warm_token token world_state world_stats_json_val room_create room_id proposal_state participant_id
  local proposal_candidates proposal_applied=0 proposal_ms="" proposal_type="" populate_response bot_sim_response gpu_patch_observed=false
  local room_create_ms token_ms world_ms populate_ms bot_sim_client_ms request_flow_ms total_ms world_non_zero_tiles world_highway_count initial_bot_count populated_bot_count

  if test_tcp_port 127.0.0.1 "$app_port"; then
    fail "Port 8080 already in use."
  fi
  if [[ "$mode" == "gpu" ]] && test_tcp_port 127.0.0.1 "$gpu_port"; then
    fail "Port 50051 already in use."
  fi

  tag="$mode-$iteration-$(date +%Y%m%d-%H%M%S)"
  output_dir="$output_root/perf-release-$tag"
  mkdir -p "$output_dir"
  app_log="$output_dir/java-app.log"
  app_err_log="$output_dir/java-app.err.log"
  gpu_log="$output_dir/gpu-engine.log"
  gpu_err_log="$output_dir/gpu-engine.err.log"

  cleanup_scenario() {
    set +e
    if [[ -n "$app_pid" ]]; then
      stop_process_tree "$app_pid"
    fi
    if [[ -n "$gpu_pid" ]]; then
      stop_process_tree "$gpu_pid"
    fi
    sleep 1
  }

  trap cleanup_scenario RETURN

  startup_started="$(millis)"
  if [[ "$mode" == "gpu" ]]; then
    (
      unset DEBUG
      export GPU_ENGINE_ADDR="127.0.0.1:$gpu_port"
      cd "$gpu_repo"
      exec "$gpu_exe"
    ) >"$gpu_log" 2>"$gpu_err_log" &
    gpu_pid=$!
    wait_tcp_port 127.0.0.1 "$gpu_port" 120 "GPU engine"
  fi
  gpu_ready_ms=$(( $(millis) - startup_started ))

  local -a java_args=(
    "-XX:TieredStopAtLevel=1"
    "-XX:CICompilerCount=2"
    "-Xshare:auto"
    "-Xms256m"
    "-Xmx512m"
    "-Djava.awt.headless=true"
    "-jar"
    "$jar_path"
    "--spring.profiles.active=perf"
    "--app.e2e.enabled=true"
    "--app.consensus.minVotesToResolve=1"
    "--app.consensus.minNeighbors=1"
    "--app.consensus.quorumCap=1"
  )
  if [[ "$mode" == "gpu" ]]; then
    java_args+=(
      "--app.gpu.enabled=true"
      "--app.gpu.world.enabled=true"
      "--app.gpu.bots.enabled=true"
      "--app.gpu.bots.applyEdits=true"
      "--app.gpu.bots.cacheWorld=true"
      "--app.gpu.bots.steps=1"
      "--app.gpu.host=127.0.0.1"
      "--app.gpu.port=$gpu_port"
    )
  else
    java_args+=(
      "--app.gpu.enabled=false"
      "--app.gpu.world.enabled=false"
      "--app.gpu.bots.enabled=false"
      "--app.gpu.bots.applyEdits=false"
    )
  fi

  (
    unset DEBUG
    export JAVA_HOME="$java_home"
    export PATH="$java_home/bin:$PATH"
    cd "$java_repo"
    exec "$java_cmd" "${java_args[@]}"
  ) >"$app_log" 2>"$app_err_log" &
  app_pid=$!
  wait_tcp_port 127.0.0.1 "$app_port" 180 "Java app"
  app_ready_ms=$(( $(millis) - startup_started ))

  base_url="http://127.0.0.1:$app_port"
  warm_token="$(get_test_token "$base_url" 60)"
  api_post "$base_url/game/state" "$(jq -cn --arg token "$warm_token" '{token:$token}')" >/dev/null

  start_ms="$(millis)"
  token="$(get_test_token "$base_url" 60)"
  token_ms=$(( $(millis) - start_ms ))

  start_ms="$(millis)"
  world_state="$(api_post "$base_url/game/state" "$(jq -cn --arg token "$token" '{token:$token}')")"
  world_ms=$(( $(millis) - start_ms ))
  world_stats_json_val="$(world_stats_json <<<"$world_state")"
  world_non_zero_tiles="$(jq -r '.non_zero_tiles' <<<"$world_stats_json_val")"
  world_highway_count="$(jq -r '.highway_count' <<<"$world_stats_json_val")"

  start_ms="$(millis)"
  room_create="$(api_post "$base_url/room/create" "$(jq -cn --arg token "$token" --arg name "Perf Release $tag" '{token:$token,name:$name}')")"
  room_create_ms=$(( $(millis) - start_ms ))
  room_id="$(jq -r '.roomId // empty' <<<"$room_create")"
  [[ -n "$room_id" ]] || fail "Room creation did not return a roomId in $mode mode."
  initial_bot_count="$(jq -r '[.bots[]?] | length' <<<"$room_create")"
  participant_id="$(jq -r '.participantId // empty' <<<"$room_create")"

  proposal_state="$room_create"
  proposal_candidates="$(proposal_candidates_json "$proposal_state" "$participant_id")"

  while IFS=$'\t' read -r candidate_x candidate_y candidate_type expected_tile expected_highway; do
    [[ -n "$candidate_x" ]] || continue
    start_ms="$(millis)"
    local proposal_note proposal_response
    proposal_note="perf-release-$tag-$candidate_x-$candidate_y-$candidate_type"
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
    if proposal_effect_applied "$proposal_response" "$candidate_x" "$candidate_y" "$expected_tile" "$expected_highway"; then
      proposal_applied=1
      proposal_ms=$(( $(millis) - start_ms ))
      proposal_type="$candidate_type"
      break
    fi

    local proposal_deadline=$((SECONDS + 10))
    while ((SECONDS < proposal_deadline)); do
      sleep 0.1
      proposal_state="$(api_post "$base_url/room/$room_id/state" "$(jq -cn --arg token "$token" --arg roomId "$room_id" '{token:$token,roomId:$roomId}')")"
      if proposal_effect_applied "$proposal_state" "$candidate_x" "$candidate_y" "$expected_tile" "$expected_highway"; then
        proposal_applied=1
        proposal_ms=$(( $(millis) - start_ms ))
        proposal_type="$candidate_type"
        break
      fi
    done
    if [[ $proposal_applied -eq 1 ]]; then
      break
    fi
  done < <(jq -r '.[] | [.x,.y,.type,.expectedTile,.expectedHighway] | @tsv' <<<"$proposal_candidates")

  [[ $proposal_applied -eq 1 ]] || fail "No proposal applied in $mode mode."

  start_ms="$(millis)"
  populate_response="$(api_post "$base_url/perf/room/$room_id/populate" '{}')"
  populate_ms=$(( $(millis) - start_ms ))

  start_ms="$(millis)"
  bot_sim_response="$(api_post "$base_url/perf/room/$room_id/bot-simulation" "$(jq -cn --argjson iterations "$bot_simulation_iterations" '{iterations:$iterations,clearGpuWorldCache:true}')")"
  bot_sim_client_ms=$(( $(millis) - start_ms ))

  populated_bot_count="$(jq -r '.botCount // 0' <<<"$populate_response")"
  if [[ "$mode" == "gpu" ]] && [[ -f "$gpu_log" ]] && grep -Fq 'rebuild_patch request_id=' "$gpu_log" && grep -Fq '"backend":"wgpu-patch"' "$gpu_log"; then
    gpu_patch_observed=true
  fi

  request_flow_ms=$(( token_ms + world_ms + room_create_ms + proposal_ms + populate_ms + bot_sim_client_ms ))
  total_ms=$(( app_ready_ms + token_ms + world_ms + room_create_ms + proposal_ms + populate_ms + bot_sim_client_ms ))

  jq -n \
    --arg mode "$mode" \
    --argjson iteration "$iteration" \
    --argjson gpu_start_ms "$([[ "$mode" == "gpu" ]] && echo "$gpu_ready_ms" || echo 0)" \
    --argjson app_ready_ms "$app_ready_ms" \
    --argjson token_ms "$token_ms" \
    --argjson world_ms "$world_ms" \
    --argjson room_create_ms "$room_create_ms" \
    --argjson proposal_apply_ms "$proposal_ms" \
    --argjson populate_ms "$populate_ms" \
    --argjson bot_sim_client_ms "$bot_sim_client_ms" \
    --argjson bot_sim_server_total_ms "$(jq -r '.totalMs // 0' <<<"$bot_sim_response")" \
    --argjson bot_sim_server_avg_ms "$(jq -r '.averageMs // 0' <<<"$bot_sim_response")" \
    --argjson request_flow_ms "$request_flow_ms" \
    --argjson total_ms "$total_ms" \
    --argjson gpu_patch_observed "$([[ "$gpu_patch_observed" == true ]] && echo true || echo false)" \
    --argjson world_non_zero_tiles "$world_non_zero_tiles" \
    --argjson world_highway_count "$world_highway_count" \
    --argjson initial_bot_count "$initial_bot_count" \
    --argjson populated_bot_count "$populated_bot_count" \
    --argjson proposal_type "$proposal_type" \
    --arg output_dir "$output_dir" \
    '{
      mode: $mode,
      iteration: $iteration,
      gpu_start_ms: $gpu_start_ms,
      app_ready_ms: $app_ready_ms,
      token_ms: $token_ms,
      world_ms: $world_ms,
      room_create_ms: $room_create_ms,
      proposal_apply_ms: $proposal_apply_ms,
      populate_ms: $populate_ms,
      bot_sim_client_ms: $bot_sim_client_ms,
      bot_sim_server_total_ms: $bot_sim_server_total_ms,
      bot_sim_server_avg_ms: $bot_sim_server_avg_ms,
      request_flow_ms: $request_flow_ms,
      total_ms: $total_ms,
      gpu_patch_observed: $gpu_patch_observed,
      world_non_zero_tiles: $world_non_zero_tiles,
      world_highway_count: $world_highway_count,
      initial_bot_count: $initial_bot_count,
      populated_bot_count: $populated_bot_count,
      proposal_type: $proposal_type,
      output_dir: $output_dir
    }'
}

build_artifacts_if_needed

runs_json='[]'
for mode in cpu gpu; do
  for ((iteration=1; iteration<=iterations; iteration++)); do
    run_json="$(run_scenario "$mode" "$iteration")"
    runs_json="$(jq -c --argjson run "$run_json" '. + [$run]' <<<"$runs_json")"
  done
done

summary_json="$(jq -cn --argjson runs "$runs_json" '
  ["cpu","gpu"]
  | map(
      . as $mode
      | ($runs | map(select(.mode == $mode))) as $items
      | {
          mode: $mode,
          app_ready_ms_avg: (($items | map(.app_ready_ms) | add) / ($items | length)),
          gpu_start_ms_avg: (($items | map(.gpu_start_ms) | add) / ($items | length)),
          token_ms_avg: (($items | map(.token_ms) | add) / ($items | length)),
          world_ms_avg: (($items | map(.world_ms) | add) / ($items | length)),
          room_create_ms_avg: (($items | map(.room_create_ms) | add) / ($items | length)),
          proposal_apply_ms_avg: (($items | map(.proposal_apply_ms) | add) / ($items | length)),
          populate_ms_avg: (($items | map(.populate_ms) | add) / ($items | length)),
          bot_sim_client_ms_avg: (($items | map(.bot_sim_client_ms) | add) / ($items | length)),
          bot_sim_server_total_ms_avg: (($items | map(.bot_sim_server_total_ms) | add) / ($items | length)),
          bot_sim_server_avg_ms_avg: (($items | map(.bot_sim_server_avg_ms) | add) / ($items | length)),
          request_flow_ms_avg: (($items | map(.request_flow_ms) | add) / ($items | length)),
          total_ms_avg: (($items | map(.total_ms) | add) / ($items | length)),
          gpu_patch_observed_count: ($items | map(select(.gpu_patch_observed)) | length),
          world_non_zero_tiles_avg: (($items | map(.world_non_zero_tiles) | add) / ($items | length)),
          world_highway_count_avg: (($items | map(.world_highway_count) | add) / ($items | length)),
          initial_bot_count_avg: (($items | map(.initial_bot_count) | add) / ($items | length)),
          populated_bot_count_avg: (($items | map(.populated_bot_count) | add) / ($items | length))
        }
      | with_entries(if (.value | type) == "number" then .value |= ((.*100 | round) / 100) else . end)
    )
')"

cpu_summary="$(jq -c '.[] | select(.mode == "cpu")' <<<"$summary_json")"
gpu_summary="$(jq -c '.[] | select(.mode == "gpu")' <<<"$summary_json")"

diff_json="$(jq -n \
  --argjson cpu "$cpu_summary" \
  --argjson gpu "$gpu_summary" '
  def pct($g; $c): if $c != 0 then (((($g - $c) / $c) * 100) * 100 | round) / 100 else null end;
  {
    app_ready_ms_delta: (((($gpu.app_ready_ms_avg - $cpu.app_ready_ms_avg) * 100) | round) / 100),
    world_ms_delta: (((($gpu.world_ms_avg - $cpu.world_ms_avg) * 100) | round) / 100),
    room_create_ms_delta: (((($gpu.room_create_ms_avg - $cpu.room_create_ms_avg) * 100) | round) / 100),
    proposal_apply_ms_delta: (((($gpu.proposal_apply_ms_avg - $cpu.proposal_apply_ms_avg) * 100) | round) / 100),
    populate_ms_delta: (((($gpu.populate_ms_avg - $cpu.populate_ms_avg) * 100) | round) / 100),
    bot_sim_client_ms_delta: (((($gpu.bot_sim_client_ms_avg - $cpu.bot_sim_client_ms_avg) * 100) | round) / 100),
    bot_sim_server_total_ms_delta: (((($gpu.bot_sim_server_total_ms_avg - $cpu.bot_sim_server_total_ms_avg) * 100) | round) / 100),
    request_flow_ms_delta: (((($gpu.request_flow_ms_avg - $cpu.request_flow_ms_avg) * 100) | round) / 100),
    total_ms_delta: (((($gpu.total_ms_avg - $cpu.total_ms_avg) * 100) | round) / 100),
    world_ms_pct: pct($gpu.world_ms_avg; $cpu.world_ms_avg),
    room_create_ms_pct: pct($gpu.room_create_ms_avg; $cpu.room_create_ms_avg),
    proposal_apply_ms_pct: pct($gpu.proposal_apply_ms_avg; $cpu.proposal_apply_ms_avg),
    populate_ms_pct: pct($gpu.populate_ms_avg; $cpu.populate_ms_avg),
    bot_sim_client_ms_pct: pct($gpu.bot_sim_client_ms_avg; $cpu.bot_sim_client_ms_avg),
    bot_sim_server_total_ms_pct: pct($gpu.bot_sim_server_total_ms_avg; $cpu.bot_sim_server_total_ms_avg),
    request_flow_ms_pct: pct($gpu.request_flow_ms_avg; $cpu.request_flow_ms_avg),
    total_ms_pct: pct($gpu.total_ms_avg; $cpu.total_ms_avg)
  }'
)"

jq -n \
  --argjson runs "$runs_json" \
  --argjson summary "$summary_json" \
  --argjson diff "$diff_json" \
  '{runs:$runs, summary:$summary, diff:$diff}'
