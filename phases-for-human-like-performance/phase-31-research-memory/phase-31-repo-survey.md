# Phase 31 — per-repo survey: adopted / already-covered / rejected

Disposition table for every notable mechanism found in the seven 2026-06-04 clones, so nothing is
re-litigated from scratch later (the `arc-feature-considerations.md` convention). File refs point
into `/opt/apps/ide/vscode/ai/reference-from-github/<repo>/`.

Legend: **→ 31.x** adopted into a phase-31 item · **covered** an ET mechanism or pending phase
item already owns it · **rejected** reviewed and deliberately not ported.

---

## A. mem0 (`mem0/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| ADD-only phased extraction (no in-place UPDATE at capture; link to priors) | `mem0/memory/main.py:699-971` | **→ 31.1** (append-only store, supersession not mutation) |
| MD5 hash dedup + advisory semantic dedup | `main.py:784-822` | **→ 31.1** |
| Immutable history/audit table per mutation | `mem0/memory/storage.py:102-255` | **→ 31.1 / 31.6** (memory_audit log) |
| Hybrid retrieval: semantic + BM25 + entity, adaptive sigmoid, hub-entity attenuation | `mem0/utils/scoring.py:16-121`, `main.py:1343-1499` | **→ 31.3** (deferred tier — only past ~500 live entries; v1 is index-first) |
| Multi-tenant scoping (user/agent/run) | `main.py:231-324` | **→ 31.1** (roomId/botId/runId columns) |
| Temporal grounding (observation-date resolution; preserve explicit dates) | `main.py:218-229`, `configs/prompts.py:520-541` | **→ 31.2** (consolidation prompt rule) |
| Procedural memory (structured execution-trace digest as first-class type) | `main.py:1618-1655`, `prompts.py:326-402` | **→ 31.4** |
| Fact-extraction integrity rules (no echo extraction, no fabrication, attribution) | `prompts.py:468-944` | **→ 31.2** (prompt design input) |
| LLM reranker post-retrieval | `mem0/reranker/base.py` | rejected — extra call per recall; index-first makes it moot at ET scale |
| Graph memory (entity/relation store) | `mem0/memory/graph_memory*` | covered — ET's `DerivationGraph` (W2) is the domain-correct graph; a second generic graph adds confusion |
| Advanced metadata filter operators | `main.py:1239-1342` | rejected — pgsql queries suffice |

## B. LightMem (`LightMem/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Offline update queue: per-entry top-k older candidates → LLM `update/delete/ignore` | `src/lightmem/memory/lightmem.py:445-630` | **→ 31.2** (contradiction resolution) |
| Timestamp-causal candidate filtering (`ts ≤ entry.ts`) | `lightmem.py:484-488` | **→ 31.2** (newer revises older, never reverse) |
| Three-stage buffers (sensory → short-term → long-term; batch before extraction) | `factory/memory_buffer/*.py` | **→ 31.2** in spirit (capture raw, compile in batches); the literal buffer cascade is conversation-stream machinery ET doesn't need |
| Consolidated flag + hot/cold tiers | `memory/utils.py:14-31,580-750` | **→ 31.5** (dormancy) |
| Cross-event supplementary retrieval for summarization | `lightmem.py:715-793`, `utils.py:401-468` | **→ 31.7** (synthesis sections pull related prior context) |
| Pre-compression (LLMlingua-2 entropy compression, ~50% token cut) | `configs/pre_compressor/*` | rejected — ET papers cost ~$0.01; token budget is not the binding constraint, and it adds a model dependency |
| Topic segmentation via embedding similarity cuts | `sensory_memory.py:43-113` | rejected — ET observations arrive pre-structured (per-paper, per-gate); nothing to segment |
| Parallel offline update with token-stats accounting | `lightmem.py:527-630` | covered — ET's `TokenAccountant` + executor patterns |

## C. agentmemory (`agentmemory/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Observation compression to typed records (facts/concepts/importance 1-10) | `src/functions/compress.ts:67-150` | **→ 31.1** (typed capture; ET's writers are deterministic, so cheaper than LLM compression) |
| Jaccard supersession + content-addressed fingerprint IDs | `src/functions/remember.ts:62-87`, `src/state/schema.ts:89-99` | **→ 31.1** |
| Crystallization (session → narrative digest + lessons) | `src/functions/crystallize.ts:13-105` | **→ 31.2** (run-level crystal) |
| Concept-grouped consolidation, ≥3 obs → 1 memory, ≤10 LLM calls/pass | `src/functions/consolidate.ts:65-160` | **→ 31.2** (the cost-bounding shape) |
| Strength + reinforcement + recency decay scoring | `src/functions/access-tracker.ts`, `consolidation-pipeline.ts:21-43` | **→ 31.1 / 31.3 / 31.5** |
| Reflection → insights with convergent confidence | `src/functions/reflect.ts:25-200` | **→ 31.2** (INSIGHT tier) |
| Skill extraction from successful sessions only | `src/functions/skill-extract.ts:14-100` | **→ 31.4** |
| Context assembly: budgeted blocks, sourceIds, pinned slots | `src/functions/context.ts:33-150` | **→ 31.3** (budgeted injection); pinned slots = operator-pinned standing guidance, nice-to-have |
| Temporal knowledge graph: versioned edges with `reasoning` + `alternatives` | `src/functions/temporal-graph.ts:14-100` | covered/partial — `DerivationGraph` owns structure; the *versioned-edge + reasoning* fields are a W2 enhancement to note, not a new graph |
| Hybrid BM25+vector+graph with RRF (recall@5 ≈ 95%) | `src/state/hybrid-search.ts:22-99` | **→ 31.3** deferred tier (with mem0's) |
| Recall benchmark methodology (labeled queries, recall@k, NDCG) | `benchmark/quality-eval.ts` | **→ 31.5** (labeled recall set inside the B7 benchmark) |
| Privacy filtering at ingestion | `src/functions/privacy.ts` | rejected — no secrets flow through ET observations; YAGNI |
| 12-hook lifecycle integration (MCP/hooks) | `src/hooks/*` | rejected — ET capture points are Spring service seams, not editor hooks |

## D. claude-memory-compiler (`claude-memory-compiler/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Capture → daily log → LLM-compiled wiki (compiler edits the wiki directly) | `scripts/compile.py:35-163` | **→ 31.2** |
| Hash-state incremental compile (never re-process unchanged input) | `scripts/flush.py:43-53`, `utils.py:36-38` | **→ 31.2 / 31.6** |
| Index-first retrieval, no RAG (index → pick 3-10 → read) | `scripts/query.py:35-76` | **→ 31.3** (the v1 read path) |
| Lint: broken links / orphans / staleness / sparse / LLM contradiction sweep | `scripts/lint.py:35-130` | **→ 31.5** |
| Append-only build log of every operation | `AGENTS.md` (log format) | **→ 31.6** (memory_audit) |
| File-back query loop (answers become durable Q&A artifacts) | `scripts/query.py:37-59` | rejected for now — ET has no interactive query surface; revisit with the deferred MCP-server idea (`arc-feature-considerations.md` §4) |
| Dual-hook capture (session-end + pre-compact) | `hooks/*.py` | rejected — editor-session machinery; ET equivalents are service seams |
| Per-operation cost tracking | `compile.py:147-161` | covered — `TokenAccountant` |

## E. gpt-researcher (`gpt-researcher/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Outline-first section orchestration + similar-written-content dedup + TOC check | `prompts.py:569-589`, `skills/context_manager.py:88-118`, `actions/markdown_processing.py:68-92` | **→ 31.7** |
| Recursive deep research (learnings + follow-up questions per level) | `skills/deep_research.py:372-519` | covered/partial — ARC §B3 refine/pivot (pending) owns the in-investigation loop; the *follow-up-question* output feeds the existing `ProgramOpenQuestion` wiring, note added to 31.7 |
| Dynamic persona binding (`choose_agent`) | `actions/agent_creator.py:18-62`, `prompts.py:486-511` | **→ 31.8** |
| Source curation: LLM ranks sources by role-aware credibility | `skills/curator.py:33-96` | covered — 30.4's layered literature design (deferred) is the ET-shaped version; web-source curation has no ET surface today |
| Sub-query generation with model-fallback chain | `actions/query_processing.py:37-110` | covered — formulation + LlmRouter fallback already equivalent |
| Embedding context compression (chunk + similarity filter, fast path <8KB) | `context/compression.py:85-178` | covered — phase 20.1 tool-result compression + 20.2 compaction |
| Citation tracking → references section | `actions/markdown_processing.py:94-112` | covered — citations-as-schema (phase 11) is stronger |
| Multi-agent editor/reviewer/reviser/publisher (LangGraph) | `multi_agents/agents/orchestrator.py:56-90` | covered — professor/student + `ReviewPanelService` + revision flow |
| Tone/format/word-count threading | `agent.py:52-82`, `prompts.py:414-483` | covered — `PromptTemplateRegistry` owns style; 31.8 carries the persona slice |
| Hallucination eval (judge output vs source docs) | `evals/hallucination_eval/evaluate.py:18-53` | covered — validator ladder + ARC §B2 metrics manifest (pending) is the stronger write-once form |
| Cost tracking with provider pricing tables | `utils/costs.py:136-182` | covered — `TokenAccountant` / `BudgetGateMetrics` |
| Multi-retriever / MCP retriever abstraction | `actions/retriever.py:109-146` | rejected — no ET web-retrieval surface; 30.4 stays the design of record |

## F. autoresearch (karpathy) (`autoresearch/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Fixed time budget per experiment (commensurability) | `prepare.py:31-32`, `train.py:603`, `program.md:21-23` | **→ 31.9** |
| Append-only TSV results journal + analysis notebook | `program.md:64-88`, `analysis.ipynb` | **→ 31.10** |
| Simplicity-first acceptance criterion | `program.md:37-38` | **→ 31.10** (written policy) |
| Keep/discard with git-reset ratchet | `program.md:90-106` | covered — phase-29 §29.2.1 rollback ratchet (pending) at revision level; flag-gated A/B at pipeline level |
| Vocab-independent metric (BPB) | `prepare.py:343-365` | covered in spirit — the B7 benchmark's frozen rubric is ET's commensurable metric; 31.9 pins its budgets |
| Single editable file / locked harness | `program.md:25-31` | covered — ET's flag-gated, domain-neutral validator convention is the same blast-radius control |
| Never-stop autonomous loop, crash tolerance | `program.md:108-114` | covered — `@Scheduled` cycles + `GateRetryQueue` + stuck-draft reaper |
| Stateless agent (all state in git + TSV) | `program.md:4-18` | covered — DB-backed state; 31.6 closes the remaining file-state gap |
| Progress-fraction-aware schedules | `train.py:517-532` | **→ 31.9** (prompt states remaining budget) |

## G. Auto-Deep-Research (`Auto-Deep-Research/`) — reviewed, no adoption

ET's pipeline is a deterministic Spring service chain with flag-gated stages; ADR is a
general-purpose chat-agent harness. Verdict mirrors the ARC anti-patterns section: the flow of
value is ET→ADR, not ADR→ET.

| Mechanism | Key files | Why not |
|---|---|---|
| Triage agent + dynamic agent handoff (`Result` carries next Agent) | `autoagent/agents/system_agent/system_triage_agent.py:8-64`, `core.py:167-183,429-438` | ET's stage order *is* the quality contract (pre-registration → compute → classify → narrate → validate → panel); dynamic re-routing would reopen the post-hoc-rationalization hole the deterministic order closes |
| Context-variables shared mutable state | `core.py:86,219-220` | ET threads typed records (`PaperDraft`, `NarrationResult`); untyped dict state is a regression |
| Event-driven async flow engine | `autoagent/flow/core.py:83-175` | same verdict as ARC's 23-stage machinery: ET cycles are ~$0.01/minutes; orchestration frameworks buy nothing |
| Dual-mode function calling (native + prompt-injected XML) | `core.py:106-163`, `fn_call_converter.py:34-60` | covered — §2.B schema-pinned reviewers + non-tool fallback path already shipped |
| Tool-output truncation with file-pointer fallback | `registry.py:19-24`, `tools/tool_utils.py:1-24` | covered — phase 20.1 tool-result compression |
| ChromaDB RAG memory | `memory/rag_memory.py:14-85` | superseded by the 31.1–31.3 design (index-first, pgsql-backed) |
| Exponential-backoff retry taxonomy | `core.py:38-54` | covered — `LlmRouter` failover + pool retries |

---

## Cross-cutting observation

All four memory repos independently converge on the same five-stage loop —
**capture → extract/compress → consolidate (dedup + supersede + contradiction-resolve) →
retrieve (budgeted, source-attributed) → lint/decay** — exactly as the four self-improving
frameworks converged on archive/selection/anti-gaming (ext addendum) and the simulation frameworks
on closure/null/identifiability (sim addendum). Convergence across independent codebases has been
the strongest predictor of pattern value in the previous three reviews; the loop above is this
review's equivalent and is what 31.1–31.5 adopt.
