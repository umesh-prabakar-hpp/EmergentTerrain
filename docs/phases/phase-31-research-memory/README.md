# Phase 31 — Cross-Run Research Memory & Composition (patterns from the memory/deep-research repo review)

**Status: CORE LOOP SHIPPED + ON (updated 2026-07-04; originally proposal 2026-06-04).** 31.1 capture
(shipped 2026-06-06) + 31.2 consolidation + 31.3 recall (both flipped ON 2026-06-08) — the
capture→consolidate→recall assembler is closed and live; 31.7 synthesis papers live with evidence-based
re-synthesis (`d2e4983`). Still open: `memory_audit` log; 31.4–31.6 / 31.8–31.10 unaudited (see
`PENDING.md` §3). Fourth external-pattern review, after the
self-improving-frameworks addendum
([`../phase-30-conjecture/phase-30-external-patterns.md`](../phase-30-conjecture/phase-30-external-patterns.md)),
the simulation-frameworks addendum
([`../phase-30-conjecture/phase-30-simulation-patterns.md`](../phase-30-conjecture/phase-30-simulation-patterns.md)),
and the AutoResearchClaw review
([`../phase-30-conjecture/phase-30-arc-patterns.md`](../phase-30-conjecture/phase-30-arc-patterns.md)).
Every adopted item ships flag-gated **OFF** (`@Value … = false`) per house convention, flipped only
after its acceptance bar.

**Sources reviewed (2026-06-04)** — seven fresh clones under
`/opt/apps/ide/vscode/ai/reference-from-github/`:

| Repo | One-line mechanism | Layer it contributes |
|---|---|---|
| `mem0` (mem0ai) | universal memory layer: phased fact extraction, hash+semantic dedup, hybrid retrieval (semantic+BM25+entity), history audit, procedural memory | memory **semantics** |
| `LightMem` (zjunlp, ICLR 2026) | sensory→short-term→long-term buffers, offline update queue (update/delete/ignore), timestamp-causal filtering, consolidation tiers | memory **lifecycle** |
| `agentmemory` (rohitg00) | observation compression → crystallization → lessons/skills → reflection; strength/decay/reinforcement; temporal graph with versioned edges; recall benchmarks | memory **curation** |
| `claude-memory-compiler` (coleam00) | hook capture → daily logs → LLM-compiled wiki (index, lint, contradiction check); index-first retrieval without RAG; hash-incremental compile | memory **compilation** |
| `gpt-researcher` (assafelovic) | recursive deep research, outline-first section orchestration, source curation, dynamic persona binding | paper **composition** |
| `autoresearch` (karpathy) | fixed-time-budget experiments, append-only TSV journal, keep/discard ratchet, vocab-independent metric, simplicity-first acceptance | process **discipline** |
| `Auto-Deep-Research` (HKUDS) | triage-agent orchestration, dual-mode function calling, event-driven flow | reviewed — **no adoption** (see survey §D) |

---

## The thesis (what the previous reviews did not cover)

Phase 30 hardened **within-run** generation and verification (gates, nulls, holdouts, mechanisms).
Phase 29 hardened **selection** (best-of-N, cascade, clade credit pending). The ARC review added the
**production** layer (figures, manifest, refine/pivot, lesson overlay pending). What no existing doc
covers is the **cross-run knowledge layer**: EmergentTerrain currently *forgets almost everything
between runs* —

- `research/<roomId>/` papers and `REVIEW_REPORT.md` are wiped on app restart (AGENTS.md; arch-map
  gap #20); only the DB rows and frontier tables survive.
- The only durable *method* memory is the frontier track's `verified_methods` (domain-specific);
  a successful professor tool-loop strategy on an ordinary conjecture paper is never extracted or
  reused (gap #1).
- Panel verdicts, W4 retirement reasons, and `NARRATION_AUDIT` categories are emitted and dropped;
  the pending ARC lesson overlay (§B4) would replay them as a *raw decayed prompt overlay* but
  nothing **compiles** them into curated, deduplicated, contradiction-checked knowledge.
- Provenance is fragmented three ways (paper markdown / DB row / `research/llm-traces/P-*.jsonl`)
  with no unified ID linkage (gap #21).

The four memory repos converge on the same closed loop —
**capture → extract → consolidate → retrieve → lint** — with different emphases. Phase 31 adopts
that loop as ET's missing layer, plus a small set of composition/process patterns from
gpt-researcher and autoresearch that the prior reviews did not touch.

**Honest ceiling.** A memory layer does not make claims more novel or more true — Phase 30's gates
do that. What it buys: (a) the system stops re-investigating what it has already settled,
(b) lessons survive restarts and compound, (c) contradictions between runs surface instead of
silently coexisting, and (d) a new *synthesis-paper* genre becomes possible. It is the
**compounding** layer, not the **correctness** layer.

---

## Folder contents

| Doc | Contents |
|---|---|
| [`phase-31-cross-run-memory.md`](phase-31-cross-run-memory.md) | items 31.1–31.6: the memory store, consolidation, recall, procedural skills, hygiene, provenance unification |
| [`phase-31-composition-and-process.md`](phase-31-composition-and-process.md) | items 31.7–31.10: synthesis papers / outline-first composition, dynamic persona binding, fixed-budget commensurability, pipeline experiment journal |
| [`phase-31-repo-survey.md`](phase-31-repo-survey.md) | per-repo pattern→disposition tables: adopted / already-covered / rejected, with file refs into the clones |
| [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) | dependency-ordered waves + flag inventory + acceptance roll-up |

## Relationship to pending items elsewhere (no duplication)

- **ARC lesson overlay (phase-30-arc-patterns §B4, pending)** — complementary, not superseded: the
  overlay is the cheap *short-loop* (raw lessons, 30-day decay, prompt-injected); 31.1/31.2 are the
  *long-loop* (compiled, deduplicated, contradiction-resolved store). They share capture plumbing —
  build the capture hooks once (see IMPLEMENTATION-ORDER W1).
- **Phase 29 §29.3.4 skill library (pending)** — 31.4 (procedural memory) is the concrete first
  slice: extract prompt-level *procedures* from successful tool-loop traces; no code synthesis, no
  new execution surface. The sandboxed code-tool half of 29.3.4 stays where it is (build last).
- **Phase 29 §29.2.3 directed prompt self-improvement (pending)** — 31.10's experiment journal is
  the measurement substrate its A/B needs; cross-referenced, not duplicated.
- **30.4 literature grounding (deferred)** — unchanged; 31.3's recall is *internal*-corpus
  retrieval, orthogonal to external-literature retrieval.
- **P6b prior-result injection (shipped)** — 31.3 injects *compiled lessons/insights*, not raw
  prior findings; the A/B in W3 measures the delta over the P6b baseline specifically.
