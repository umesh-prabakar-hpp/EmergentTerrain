#!/usr/bin/env bash
# Start the research-only runtime. This deliberately does not start the game app, its WebSocket
# stack, GPU engine, or subscription proxies.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GAME_POM="$REPO_ROOT/apps/game-service/pom.xml"
RESEARCH_POM="$REPO_ROOT/apps/research-service/pom.xml"
RESEARCH_PORT="${RESEARCH_SERVICE_PORT:-8082}"
GAME_CONFIG_PATH="${GAME_CONFIG_PATH:-$REPO_ROOT/apps/game-service/src/main/resources/application.yml}"

[[ -f "$GAME_POM" ]] || { echo "Game-service POM not found: $GAME_POM" >&2; exit 1; }
[[ -f "$RESEARCH_POM" ]] || { echo "Research-service POM not found: $RESEARCH_POM" >&2; exit 1; }

cd "$REPO_ROOT"
export GAME_CONFIG_PATH
# The research runtime reuses the transition-period research engine from game-service. Install that
# artifact first; this compiles it but never launches the game application.
mvn -pl apps/game-service -am install -DskipTests
# application.yml imports the transition-period game configuration, which also declares the
# game port and permits Hibernate schema updates. Command-line arguments have the necessary
# precedence to keep the standalone worker on its dedicated port and prevent it from competing
# with the game service for shared-schema DDL locks.
exec mvn -f "$RESEARCH_POM" spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=$RESEARCH_PORT --spring.jpa.hibernate.ddl-auto=none"
