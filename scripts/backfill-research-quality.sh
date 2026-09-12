#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAX_PAPERS="${1:-1}"
GAME_POM="$REPO_ROOT/apps/game-service/pom.xml"
RESEARCH_POM="$REPO_ROOT/apps/research-service/pom.xml"
GAME_CONFIG_PATH="${GAME_CONFIG_PATH:-$REPO_ROOT/apps/game-service/src/main/resources/application.yml}"

[[ "$MAX_PAPERS" =~ ^[1-9][0-9]*$ ]] || {
  echo "Usage: $0 [1..25]" >&2
  exit 2
}
(( MAX_PAPERS <= 25 )) || {
  echo "Maximum batch size is 25; received $MAX_PAPERS" >&2
  exit 2
}
[[ -f "$GAME_POM" ]] || { echo "Game-service POM not found: $GAME_POM" >&2; exit 1; }
[[ -f "$RESEARCH_POM" ]] || { echo "Research-service POM not found: $RESEARCH_POM" >&2; exit 1; }

cd "$REPO_ROOT"
export GAME_CONFIG_PATH
mvn -pl apps/game-service -am install -DskipTests
exec mvn -f "$RESEARCH_POM" spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.jpa.hibernate.ddl-auto=none --app.research-service.scheduling.enabled=false --app.research-service.worker.enabled=false --app.ai.selection-tests.enabled=false --app.research.quality-adjudication.enabled=true --app.research.quality-adjudication.backfill.enabled=true --app.research.quality-adjudication.backfill.max-papers=$MAX_PAPERS"
