#!/usr/bin/env bash
# Start the research-only runtime. This deliberately does not start the game app, its WebSocket
# stack, GPU engine, or subscription proxies.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GAME_POM="$REPO_ROOT/apps/game-service/pom.xml"
RESEARCH_POM="$REPO_ROOT/apps/research-service/pom.xml"

[[ -f "$GAME_POM" ]] || { echo "Game-service POM not found: $GAME_POM" >&2; exit 1; }
[[ -f "$RESEARCH_POM" ]] || { echo "Research-service POM not found: $RESEARCH_POM" >&2; exit 1; }

cd "$REPO_ROOT"
# The research runtime reuses the transition-period research engine from game-service. Install that
# artifact first; this compiles it but never launches the game application.
mvn -pl apps/game-service -am install -DskipTests
exec mvn -f "$RESEARCH_POM" spring-boot:run
