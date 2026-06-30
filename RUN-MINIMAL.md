# Run — minimal (commands only)

```bash
# 1. GPU engine
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && ./scripts/start.sh

# 2. Julia math sidecar
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ET_DISCOVER_EXEC=1 ./scripts/start.sh &

# 2b. Py-sidecar (PyCall routes)
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ET_DISCOVER_EXEC=1 ./scripts/start-py-sidecar.sh &

# 3. Spring Boot app
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain && ./scripts/start.sh

# 4. Tail logs (per service)
tail -f /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine/logs/gpu-engine.log
tail -f /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar/logs/sidecar.log
tail -f /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar/logs/py-sidecar.log
tail -f /opt/apps/ide/eclipse/workspace-web/EmergentTerrain/logs/et-app.log
tail -f /opt/apps/ide/eclipse/workspace-web/EmergentTerrain/logs/maven-stdout.log

# 4b. Tail all logs at once
tail -F \
  /opt/apps/ide/eclipse/workspace-web/EmergentTerrain/logs/et-app.log \
  /opt/apps/ide/eclipse/workspace-web/EmergentTerrain/logs/maven-stdout.log \
  /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar/logs/sidecar.log \
  /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar/logs/py-sidecar.log \
  /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine/logs/gpu-engine.log
```

Stop each: `./scripts/stop.sh` in its dir. App: http://localhost:8081.
