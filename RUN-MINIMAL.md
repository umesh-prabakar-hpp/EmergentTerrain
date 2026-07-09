# Run — minimal (commands only)

```bash
# 0. fresh checkout only (exec bits aren't git-tracked)
chmod +x scripts/*.sh scripts/*.py

# 0b. one-time corpus assets (idempotent; APP MUST BE DOWN — scripts compile)
OPENALEX_MAILTO=you@example.com ./scripts/build-nt-corpus.sh && python3 scripts/run-ground-probes.py
OPENALEX_API_KEY=... OPENALEX_MAILTO=you@example.com ./scripts/build-fulltext-cores.sh
OPENALEX_API_KEY=... OPENALEX_MAILTO=you@example.com ./scripts/build-html-cores.sh
FIELDS=math python3 scripts/build-fulltext-unarxive.py --zenodo   # §32.5d full text (unarXive open subset, 4.8 GB, no key)
python3 scripts/msc-map.py --apply   # §32.5d MSC codes from committed map (no harvest); enables ET_GROUND_SUBJECTS=msc:11

# 1. GPU engine
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && ./scripts/start.sh

# 2. Julia math sidecar
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ET_DISCOVER_EXEC=1 ./scripts/start.sh &

# 2b. Py-sidecar (PyCall routes + literature grounding). ET_GROUND_SUBJECTS=mathematics
#     activates the subject gate (drops cross-domain near-misses); omit ⇒ gate dormant.
#     Recommended: mathematics (full-corpus, validated). msc:11 is an anchored MSC prefix
#     (matches 11N37, not topic-id "11") — a finer OPTIONAL add-on (~40k rows have MSC after
#     step 0e); combine if wanted: ET_GROUND_SUBJECTS=mathematics,msc:11.
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ET_GROUND_SUBJECTS=mathematics ET_DISCOVER_EXEC=1 ./scripts/start-py-sidecar.sh &

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

Note (2026-07-08): `ET_GROUND_SUBJECTS=mathematics` on the (2b) line activates the
grounding **subject gate** — it drops the ~5k non-math contaminants
(Physics/Neuroscience/Environmental/…) that BM25 otherwise surfaces on generic
words. Read at request time but must be in the py-sidecar env at launch; unset ⇒
dormant. Needs a subject-tagged corpus (rebuild via `build-nt-corpus.sh`). See
RUN.md for detail; validate with `run-ground-probes.py` (probes #21–#22).

```bash
# optional, post-run, app must be stopped
OPENALEX_MAILTO=umesh.prabakar@gmail.com ./scripts/backfill-evidence-cores.sh
```

