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
python3 scripts/build-oeis.py        # §32.5d OEIS domain ref (~40 MB, no key) -> domain-refs/oeis.jsonl
python3 scripts/build-dlmf.py        # §32.5d DLMF special-functions ref (free scrape) -> domain-refs/dlmf.jsonl
# broad full-text (jedibear/S2ORC + ar5iv bulk) needs a transient AWS box — see §32.5e / scripts/aws/provision-build-box.sh

# 0c. Optional Phase 34 local literature index (public, tokenless; does not enable the app adapter)
python3 -m pip install --user -U huggingface_hub pyarrow
./scripts/bootstrap-hf-math-literature.sh
HF_DATASET=marin-community/ar5iv-no-problem-markdown ./scripts/bootstrap-hf-math-literature.sh
python3 scripts/prepare-hf-math-index.py --stage metadata
python3 scripts/prepare-hf-math-index.py --stage theorems
systemd-run --user --unit emergentterrain-phase34-marin-index --collect --same-dir /usr/bin/python3 "$PWD/scripts/prepare-hf-math-index.py" --stage fulltext
# One fulltext writer only. The final FTS build is automatic; see phase-34-computational-publication/README.md.

# 1. GPU engine
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && ./scripts/start.sh

# 2. Julia math sidecar. Discovery is served on 8088, so its execution gate
#    belongs on this process. It also proxies the isolated PyCall routes.
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && JULIA_NUM_THREADS=4 PY_SIDECAR_ENABLED=true ET_DISCOVER_EXEC=1 ./scripts/start.sh &

# 2b. Py-sidecar (PyCall routes + literature grounding). The recommended combined
#     subject gate drops cross-domain near-misses and applies the anchored Number
#     Theory MSC prefix (msc:11 matches 11N37, not topic-id "11"). Omit it to
#     leave the gate dormant.
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ET_GROUND_SUBJECTS=mathematics,msc:11 ./scripts/start-py-sidecar.sh &

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

Note (2026-07-08): `ET_GROUND_SUBJECTS=mathematics,msc:11` on the (2b) line
activates the grounding **subject gate** — `mathematics` drops the ~5k non-math
contaminants (Physics/Neuroscience/Environmental/…) that BM25 otherwise surfaces
on generic words, while `msc:11` is the anchored Number-Theory refinement. Read
at request time but it must be in the py-sidecar environment at launch; unset ⇒
dormant. It needs a subject-tagged corpus plus the MSC map applied. See RUN.md
for detail; validate with `run-ground-probes.py` (probes #21–#22).

```bash
# optional, post-run, app must be stopped
OPENALEX_MAILTO=umesh.prabakar@gmail.com ./scripts/backfill-evidence-cores.sh
```
