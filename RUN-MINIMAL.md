# Run — minimal (commands only)

```bash
# 0. fresh checkout only (exec bits aren't git-tracked)
chmod +x scripts/*.sh scripts/*.py

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

Note (2026-07-04): the py-sidecar (2b) also builds the literature-grounding BM25
index at startup when `~/.emergentterrain/corpus-nt/abstracts.jsonl` exists —
adds ~20–60 s before `Listening on: 127.0.0.1:8089`; wait for it before starting
the app if you want grounding active from the first paper. No corpus = clean
fallback, nothing breaks.
