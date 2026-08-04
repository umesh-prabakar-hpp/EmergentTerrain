#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-sample-queries.sh [options]

Options:
  --count N              Number of samples to capture. Default: 3
  --interval-ms MS       Interval between samples. Default: 60000
  --room-id ID           Target room id
  --fail-on-error        Set sample.failOnError=true
  --output-dir PATH      Output directory for sample artifacts
  --maven-cmd CMD        Maven command. Defaults to $MAVEN_CMD or mvn
  -h, --help             Show this help
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

count=3
interval_ms=60000
room_id=""
fail_on_error=0
output_dir=""
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
maven_cmd="${MAVEN_CMD:-mvn}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count)
      [[ $# -ge 2 ]] || fail "--count requires a value"
      count="$2"
      shift 2
      ;;
    --interval-ms)
      [[ $# -ge 2 ]] || fail "--interval-ms requires a value"
      interval_ms="$2"
      shift 2
      ;;
    --room-id)
      [[ $# -ge 2 ]] || fail "--room-id requires a value"
      room_id="$2"
      shift 2
      ;;
    --fail-on-error)
      fail_on_error=1
      shift
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || fail "--output-dir requires a value"
      output_dir="$2"
      shift 2
      ;;
    --maven-cmd)
      [[ $# -ge 2 ]] || fail "--maven-cmd requires a value"
      maven_cmd="$2"
      shift 2
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

read -r -a maven_cmd_parts <<<"$maven_cmd"
((${#maven_cmd_parts[@]} > 0)) || fail "Maven command cannot be empty"

args=(
  "-Dtest=RoomStateSamplingIntegrationTest"
  "-Dspring.profiles.active=sample"
  "-Dsample.count=$count"
  "-Dsample.intervalMs=$interval_ms"
)

if [[ -n "$room_id" ]]; then
  args+=("-Dsample.roomId=$room_id")
fi
if [[ $fail_on_error -eq 1 ]]; then
  args+=("-Dsample.failOnError=true")
fi
if [[ -n "$output_dir" ]]; then
  args+=("-Dsample.outputDir=$output_dir")
fi

"${maven_cmd_parts[@]}" -f "$repo_root/apps/game-service/pom.xml" "${args[@]}" test
