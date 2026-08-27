# Repo-local memory — EmergentTerrain review state

Last updated: 2026-07-11.

Purpose: durable local handoff for future Codex/agent sessions when product memory is unavailable.
Treat `src/main/resources/application.yml` as authoritative for live flags; several phase docs are
known stale.

## Memory / skill status

- Product memory write is not available in this session. The local Codex SQLite memory file had no
  retrievable EmergentTerrain review rows, and no session-level memory-write tool was exposed.
- Workspace-Agent memory is separate from this Codex session. The only exposed memory-write command
  toggles memory on an editable Workspace Agent, but no editable agents were found.
- Installed session skills found: `imagegen`, `openai-docs`, `plugin-creator`, `skill-creator`,
  `skill-installer`.
- No installed or curated `phase-review` skill was found. The phase review below was manual.
- Portable repo copies of the paper-review skill now live under `skills/paper-review/`:
  `claude/SKILL.md` for Claude Code and `codex/SKILL.md` for Codex-style loading.

## Repos reviewed

- Java/Spring app: repository root
- Rust GPU engine: `engines/gpu-engine/`
- Julia math sidecar: `services/julia-math-sidecar/`

Related workspace repos discovered: `bills-tracker`, `docs`, `health-dashboard`,
`EmergentSomaUdal`, `EmergentInvestFlowOdai`, and `bills-tracker-android`.

Notable repo facts from the focused review:

- Main app uses Java 21, Spring Boot 3.3, Maven, PostgreSQL, gRPC/protobuf, Spring AI, Symja, PDFBox,
  and JMH.
- `application.yml` is the live flag source. It currently has the web app port at `8081`, which
  conflicts with some older docs that still mention `8080`.
- GPU integration is live through `GpuEngineClient` / `GpuEngineAdapter`; the Rust engine is a
  tonic/wgpu service on `127.0.0.1:50051` by default. The adapter has blocking stub calls without
  explicit per-call deadlines.
- Julia is the sole source for physics-topic compute through `MathPhysicsEngineDelegate`; there is no
  in-process fallback when the sidecar is unavailable.
- Julia sidecar routes live on port `8088`; the single-threaded PyCall sidecar is on `8089`.
  `/discover/validate` remains execution-gated by `ET_DISCOVER_EXEC=1`.

## Final-phase review truth

### Phase 33

Phase 33 is closed and archived. The authoritative closeout is
`docs/phases/archive/phase-33-human-paper-gap.md`.

Closing state:

- Closing condition met by run 39.
- Run 41 produced the first full paper:
  `PAPER-conjecture_twin_prime_density-5e2a9b12.fullpaper.md`.
- Full-paper path had `reads=yes` and `grounding=yes`.
- D3 full-paper promotion and the shutdown persist guard were live-validated.
- Successor work should be a new phase: derivation-grade treatment of the ln 2 conjectures plus
  external-facing polish.

### Phase 32

Phase 32 docs are internally inconsistent. `phase-32-manuscript-grounding/README.md` and
`STATUS.md` still say "proposal / nothing built", but code and YAML show major pieces live.

Live flags and state verified from `application.yml`:

- `app.research.literature.grounding.enabled=true`
- `app.research.literature.panel-novelty-grounding.enabled=true`
- `app.research.literature.read-reference.enabled=true`
- `app.research.manifest.capture.enabled=true`
- `app.research.manifest.validator-mode=enforce`
- `app.research.pivot.enabled=true`
- `app.research.review-anchor.enabled=false`

Current Phase 32 constraints:

- Review-anchor remains blocked. The deploy bar requires binary accuracy >= 0.80 and Reject-recall
  >= 0.90, but the latest phase tracker says the label set still has zero Reject rows.
- 32.5 grounding is no longer merely a proposal; it is the binding novelty lever and has passed the
  core probe/live acceptance path.
- 32.5c subject gating and 32.5d/32.5e corpus expansion are follow-ons. The AWS full-text runbook says
  scripts are written and validated, but the transient bulk build has not executed yet.
- `PENDING.md` has a broken archived Phase 33 link pointing at `phase-33-human-gap/...`; the real path
  is `archive/phase-33-human-paper-gap.md`.

### Phase 31

Phase 31 core loop is live:

- 31.1 capture is ON.
- 31.2 consolidation is ON.
- 31.3 recall is ON.
- 31.7 synthesis/re-synthesis is live.

Open Phase 31 work:

- `memory_audit` append-only log is still not built.
- 31.4-31.6 and 31.8-31.10 were not fully audited in the latest tracker pass.
- `review-verdicts.csv` / review-anchor calibration needs either real Reject labels or a revised deploy
  bar.

### Phase 30

Phase 30 is mostly built and live through later phases, but one W6 follow-on remains:

- 30.7 P3b: persist accepted runnable mechanisms onto published papers. The mechanism tool is shipped
  and enabled, but accepted mechanisms are not yet persisted as paper artifacts.

## Highest-value next actions

1. Refresh stale docs: Phase 32 README, `STATUS.md`, and the broken Phase 33 link in `PENDING.md`.
2. Decide the review-anchor calibration strategy: revise the zero-Reject deploy bar, seed/planted
   Reject cases, or keep waiting.
3. Add the Phase 31 `memory_audit` log on the next 31.x touch.
4. Persist accepted 30.7 mechanisms onto papers.
5. Execute or explicitly defer the 32.5e AWS bulk full-text build under the literature-data
   policy; do not add raw corpus-access notes or downloaded corpus text to the repository.
6. Scope the successor phase around derivation-grade ln 2 conjecture treatment and external polish.
