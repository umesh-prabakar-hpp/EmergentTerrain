# EmergentTerrain research service

This is the independently deployable research runtime. It serves the v1 paper and aggregate-stat
read API and runs the university research cycle from PostgreSQL on port `8082` by default. It does
not start the game UI, WebSocket stack, world generation, GPU adapter, or `RoomService`.

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -pl apps/research-service -am install -DskipTests
mvn -f apps/research-service/pom.xml spring-boot:run
```

Then start the game process with:

```bash
RESEARCH_API_BASE_URL=http://127.0.0.1:8082 \
  mvn -f apps/game-service/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--app.research-api.mode=remote --app.university.enabled=false"
```

Set the game process to `app.research-api.mode=remote` when it should display this service's
results. In that mode it does not create `ResearchTickGateway`, so only this process runs research
ticks. The service reads persisted room bot and government state during this transitional stage;
the next extraction replaces that shared-database read with a versioned game-state snapshot API.
