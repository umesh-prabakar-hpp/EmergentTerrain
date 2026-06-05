# Phase 31 — items 31.1–31.6: the cross-run research memory loop

All items flag-gated **OFF**. Source file refs point into the clones under
`/opt/apps/ide/vscode/ai/reference-from-github/`. ET file refs use the current `main`.

The loop, end to end:

```
capture (31.1+31.6)        consolidate (31.2)            retrieve (31.3)
─────────────────          ──────────────────            ───────────────
panel verdicts      ─┐                                   formulation-time
W4 retire reasons    ├─→  typed MemoryEntry  ─→ offline  lesson/insight
NARRATION_AUDIT      │    (FACT/LESSON/       compile:   injection, scoped
gate retirements     │     PROCEDURAL/        dedup,     by topic/domain
frontier events      │     INSIGHT)           supersede,
paper outcomes      ─┘    hash+version        contradict ─→ lint (31.5)
tool-loop traces   ──→  skills (31.4)        resolve         health metrics
```

---

## 31.1 — Typed research memory store  *(the substrate)*

- **Source:** mem0 `mem0/memory/main.py:699-971` (phased ADD-only extraction: every operation is an
  ADD with optional `linked_memory_ids` — no lossy in-place UPDATE at capture time),
  `main.py:784-822` (MD5 content-hash dedup, strict) + prompt-advisory semantic dedup;
  `storage.py:102-255` (immutable history table: every mutation appends `{old, new, event, actor}`);
  agentmemory `src/functions/remember.ts:62-87` (Jaccard-similarity supersession: a near-duplicate
  becomes `version+1` chained via `supersedes`, old marked `isLatest=false`),
  `src/types.ts:83-105` (strength 1-10 + reinforcements + lastAccessedAt on every entry).
- **Idea:** memory is an append-only, content-addressed, *versioned* store. New knowledge never
  silently overwrites old; it supersedes it with lineage, so "what did we believe on date X" stays
  answerable. Strength + reinforcement is the per-entry quality signal retrieval ranks on.
- **ET adoption shape:** new `research_memory` table (additive, `ddl-auto=update` like the frontier
  tables): `id, type (FACT|LESSON|PROCEDURAL|INSIGHT), content, contentHash, strength,
  reinforcements, version, supersedesId, sourceRefs (paper ids / gate names / log refs), topic,
  domain, roomId, createdAt, lastAccessedAt, isLatest`. Capture writers (cheap, no LLM):
  - `ReviewPanelService` — panel verdict + per-axis grades on publish → FACT;
  - `ConjectureMinerService` — every W1/W4 retirement with its reason → FACT
    ("`<conjecture>` retired by `<gate>`: `<reason>`");
  - `ResearchNarrationService` — `NARRATION_AUDIT` category hits → LESSON candidates;
  - `UniversityService.publishPaper` — outcome row (classification, status, grades) → FACT;
  - frontier events already persist (`frontier_events`) — bridge, don't duplicate.
  Scoping mirrors mem0's `user/agent/run` triple (`main.py:231-324`) as ET's
  `roomId / botId / runId` so a multi-room future needs no schema change.
- **What this is NOT:** no vector DB, no embedding service in v1 (see 31.3 — index-first retrieval
  is sufficient at ET's corpus scale; the schema reserves an `embedding` column for later).
- **Flag:** `app.research.memory.enabled` (capture only — write-path, zero read-path effect;
  the shadow mode of this phase).
- **Acceptance:** over one refresh with the flag ON, every published/retired artifact has ≥1 memory
  row with resolvable `sourceRefs`; duplicate capture of an identical fact dedups by hash (row
  count stable on replay); flag OFF ⇒ zero rows, byte-identical pipeline.

## 31.2 — Offline consolidation: observations → crystals → lessons/insights  *(the compiler)*

- **Source:** agentmemory `src/functions/crystallize.ts:13-105` (session → "crystal": narrative,
  keyOutcomes, lessons, with `sourceIds` back-pointers), `src/functions/consolidate.ts:65-160`
  (group observations by concept; ≥3 observations in a group → one LLM-synthesized memory; dedup by
  title against existing; **max 10 LLM calls per pass** to bound cost), `src/functions/reflect.ts:25-200`
  (cluster-level insights with confidence that converges under reinforcement:
  `conf = min(1, old + 0.1·(1−old))`); LightMem `src/lightmem/memory/lightmem.py:445-630`
  (the **update queue**: for each entry, top-k similar *older* entries; an LLM pass decides
  `update / delete / ignore` per pair — the contradiction-resolution step),
  `lightmem.py:484-488` (**timestamp-causal filter**: candidates restricted to
  `float_time_stamp ≤ entry.ts`, so consolidation flows causally forward — newer evidence revises
  older belief, never the reverse); claude-memory-compiler `scripts/compile.py:35-163` (LLM compiler
  reads schema + current wiki + new logs and *edits the wiki directly*; `scripts/utils.py:36-38`
  hash-state so unchanged logs are never re-compiled).
- **Idea:** raw capture is noisy and append-only; a periodic *offline* pass (cheap, bounded,
  hash-incremental) compiles it: groups related observations per topic, synthesizes one durable
  LESSON/INSIGHT per group, resolves contradictions explicitly (supersede the refuted entry, never
  delete history), and reinforces entries re-confirmed by new runs.
- **ET adoption shape:** new `MemoryConsolidationService`, `@Scheduled` (e.g. 6-hourly, off the hot
  path), gated by per-pass LLM-call cap. Pass = (1) group new FACT rows by `(topic, conjecture lhs)`;
  (2) groups with ≥3 entries → one fast-tier synthesis call → LESSON (dedup vs existing by hash,
  then by LLM-advisory); (3) update-queue check of each new entry against older same-topic entries
  (timestamp-causal) → `reinforce / supersede / ignore`; (4) run-level crystal row (one per
  refresh: what was investigated, what survived, what retired). The contradiction case ET hits
  today: a bound confirmed in run N and refuted in run N+2 currently coexists as two unrelated
  artifacts — after 31.2 the refutation supersedes, and the lineage is queryable.
- **Flag:** `app.research.memory.consolidation.enabled` (+ `…consolidation.maxLlmCallsPerPass`,
  default 10).
- **Acceptance:** on a replay corpus containing a known confirm-then-refute pair, consolidation
  produces a superseding chain (not two live contradictory entries); per-pass LLM calls ≤ cap;
  re-running a pass on unchanged input is a no-op (hash-incremental); deleting all LESSON/INSIGHT
  rows and re-consolidating regenerates equivalent content (no hidden state).

## 31.3 — Recall: index-first injection at formulation & narration  *(the read path)*

- **Source:** claude-memory-compiler `scripts/query.py:35-76` (**index-first retrieval, no RAG**:
  load the one-line-per-entry index, let the model pick 3-10 relevant entries, then read those —
  outperforms embedding search below ~500 entries and costs nothing to build); mem0
  `mem0/utils/scoring.py:16-121` (when scale demands it: hybrid semantic+BM25+entity scoring with
  adaptive sigmoid normalization and an entity-spread attenuation
  `1/(1+0.001·(n_linked−1)²)` so hub concepts don't dominate); agentmemory
  `src/functions/context.ts:33-150` (assembled context blocks ranked by
  strength × recency × access-count, trimmed to a fixed token budget, each block tagged with
  `sourceIds` for audit).
- **Idea:** retrieval quality at small scale is a *curation* problem, not an embedding problem.
  Maintain a compiled index (one line per LESSON/INSIGHT: hook + topic + strength); at
  formulation/narration time, inject the top-k scoped entries under a hard token budget, each
  carrying its source pointer so the narrator can cite *why* it believes the hint.
- **ET adoption shape:** `FormulationService` (pre-hypothesis) and the professor prompt gain an
  optional `## Prior lessons (compiled)` block: top-k `isLatest` LESSON/INSIGHT rows for the
  topic/domain, ranked `strength × e^(−age/τ) × (1+log(1+reinforcements))`, capped at a fixed token
  budget (ARC-overlay discipline: budget enforced, deleting the store reverts behavior exactly).
  Distinct from P6b (raw prior findings) — this is the compiled layer. Embedding/hybrid retrieval
  is **deferred** until the store exceeds ~500 live entries (schema reserves the column).
- **Flag:** `app.research.memory.recall.enabled` (+ `…recall.tokenBudget`, `…recall.topK`).
- **Acceptance:** A/B over ≥2 refreshes vs recall-OFF: repeat-investigation rate (same conjecture
  family re-investigated without citing the prior outcome) drops; NEEDS_REVISION rate does not
  rise; injected block never exceeds budget; with the store emptied, behavior is byte-identical to
  flag-OFF.

## 31.4 — Procedural memory: extract reusable investigation skills  *(first slice of 29.3.4)*

- **Source:** agentmemory `src/functions/skill-extract.ts:14-100` (at session end, LLM reads the
  summary + high-importance observations and either emits `<no-skill/>` or a structured procedure:
  `trigger` (when to apply), `steps[≥2]`, `expected_outcome`, `tags` — extracted **only from
  sessions with successful closure**, never from dead ends); mem0 `main.py:1618-1655` +
  `configs/prompts.py:326-402` (procedural memory = a structured execution-trace digest stored as a
  first-class memory type, distinct from facts).
- **Idea:** a successful investigation embodies a method (which tools in which order, which window
  strategy, which null was decisive). Extracting that as a *prompt-level procedure* lets the next
  investigation of a similar conjecture start from a proven playbook — the generalization of what
  the frontier track already does domain-specifically with `verified_methods`.
- **ET adoption shape:** on publish of a SUPPORTED / high-panel-grade paper, one fast-tier call
  reads the paper's tool-call trace (`research/llm-traces/P-*.jsonl`) + outcome and extracts a
  PROCEDURAL row (or abstains). Recall (31.3) surfaces matching procedures by trigger/topic into
  the professor prompt. **No code synthesis, no new execution surface** — this is the prompt-skill
  half of 29.3.4; the sandboxed tool-synthesis half stays sequenced last per phase-29.
- **Flag:** `app.research.memory.skills.enabled`.
- **Acceptance:** a procedure extracted from paper N is injected and visibly followed (tool-call
  sequence match) in a later same-domain investigation; extraction abstains on NULL_RESULT /
  low-grade papers; procedures carry `sourceRefs` to their origin paper.

## 31.5 — Memory hygiene: lint, decay, contradiction audit  *(keeps 31.1-31.4 honest)*

- **Source:** claude-memory-compiler `scripts/lint.py:35-130` (seven checks: broken source links,
  orphans, stale entries via source-hash change, sparse entries, missing backlinks, and an LLM
  **contradiction sweep** over the live store); agentmemory
  `src/functions/consolidation-pipeline.ts:21-43` (time decay `strength·0.9^periods`),
  `src/functions/auto-forget.ts:24-62` (TTL + contradiction-aware forgetting);
  benchmark methodology `benchmark/quality-eval.ts` (recall@k on labeled queries as the regression
  gate for any retrieval change).
- **ET adoption shape:** lint runs inside the consolidation pass (31.2): structural checks free;
  the LLM contradiction sweep budget-capped and only over `isLatest` entries. Decay applies at
  consolidation time; an entry decayed below a floor is marked dormant (never deleted — history is
  the audit trail). New health metrics logged per pass: live-entry count, supersession rate,
  contradiction-found rate, dormant rate — the memory-layer analog of W4's gaming-rate metric.
  A small labeled recall set (≈20 queries against known corpus facts) becomes part of the B7-style
  benchmark so retrieval changes are measurable.
- **Flag:** rolled into `…consolidation.enabled` (lint is part of the pass; no separate flag).
- **Acceptance:** a planted contradictory pair is flagged within one pass; a stale entry (source
  paper later superseded) is marked; health metrics emitted every pass; dormancy never removes
  rows.

## 31.6 — Provenance unification & restart survivability  *(prerequisite plumbing — do first)*

- **Source:** claude-memory-compiler `AGENTS.md` (append-only build log: every compile/query/lint
  operation logged with timestamp + artifacts affected) + `scripts/flush.py:43-53,206-214`
  (state-hash dedup so capture survives restarts/re-runs without double-ingest); the gap list from
  the 2026-06-04 architecture review (gaps #20/#21).
- **The ET bugs this fixes (independent value even if 31.1-31.5 never ship):**
  1. `research/<roomId>/` markdown + `REVIEW_REPORT.md` are wiped on app restart while DB rows and
     the paper-ID counter survive — so `/paper-review` loses cross-restart continuity and the
     on-disk corpus silently diverges from the DB (gap #20).
  2. Three provenance stores (markdown / DB row / `llm-traces/P-*.jsonl`) share only an implicit
     paper-ID convention; nothing verifies the linkage (gap #21).
- **Adoption shape:** (a) stop truncating `research/<roomId>/` on restart (or archive to
  `research/<roomId>/archive-<ts>/` instead of deleting — operator decision); (b) stamp one stable
  `paperId` across all three artifacts and add a startup reconciliation check that logs orphans
  (markdown without DB row, trace without markdown, …); (c) a small append-only
  `memory_audit` log of every 31.x mutation (capture/consolidate/supersede), the build-log analog.
- **Flag:** `app.research.provenance.reconcile.enabled` for the startup check; the
  retention change is a config default (`app.university.researchOutputRetention=archive|truncate`).
- **Acceptance:** after a restart mid-corpus, `/paper-review` sees the prior papers (archived or
  retained); the reconciliation check reports zero orphans on a clean run and correctly flags a
  hand-planted orphan; every memory row's `sourceRefs` resolves.

---

## Cost & risk

| Item | LLM cost | Risk |
|---|---|---|
| 31.1 capture | zero (no LLM) | low — write-only; schema additive |
| 31.2 consolidation | capped (≤10 fast-tier calls/pass) | medium — bad consolidation could compress away nuance; mitigated by append-only history + regenerate-from-scratch acceptance |
| 31.3 recall | ~1 small block per paper | medium — prompt pollution; mitigated by hard token budget + A/B bar |
| 31.4 skills | 1 fast call per SUPPORTED paper | low — abstain-by-default |
| 31.5 lint | capped, inside 31.2 | low |
| 31.6 provenance | zero | low — but the retention change needs an operator call on disk growth |

**The Goodhart note (carry from ext #3):** the memory store must never become a side-channel that
lets the generator see private-validator internals. Capture writers record gate *outcomes and
reasons* as already logged publicly — never the held-out windows, thresholds, or validator
internals that `PrivateHoldoutValidator` keeps hidden. Lint (31.5) should include a check that no
memory row quotes private-window data.
