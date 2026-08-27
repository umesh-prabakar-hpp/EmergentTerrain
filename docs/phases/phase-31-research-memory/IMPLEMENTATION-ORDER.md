# Phase 31 — implementation order

Dependency- and leverage-ordered sequencing of items 31.1–31.10
([`phase-31-cross-run-memory.md`](phase-31-cross-run-memory.md),
[`phase-31-composition-and-process.md`](phase-31-composition-and-process.md)).
Same conventions as
[`../phase-30-conjecture/IMPLEMENTATION-ORDER.md`](../phase-30-conjecture/IMPLEMENTATION-ORDER.md):
every wave ships flag-gated **OFF**, flipped only after its acceptance bar; pipeline-wide pieces
stay domain-neutral.

**Governing principle:** *capture before you compile, compile before you recall, and never let the
memory layer touch the write path of a paper until its read path has passed an A/B.* The store is
worthless if provenance is broken (W0 first) and dangerous if recall pollutes prompts (recall is
the last flip, behind its own A/B).

**Standing-blocker note:** per `project_phase23_8_calibration`, *generation, not retrieval, is the
blocker* for novelty. Phase 31 does not claim otherwise — it targets **compounding and
non-regression** (stop re-investigating settled questions, keep lessons across restarts, surface
contradictions), which is upstream of generation quality but does not substitute for Waves 5–7 of
Phase 30. Sequence Phase 31 around — not instead of — the remaining Phase 30 content work.

---

## Dependency DAG (text)

```
W0 provenance (31.6) ──→ W1 capture (31.1) ──→ W2 consolidation+lint (31.2+31.5) ──┬─→ W3 recall (31.3)
                                                                                   ├─→ W4 skills (31.4)
                                                                                   └─→ W5 synthesis (31.7)
Ops track (anytime): 31.10 journal ──→ strengthens every A/B above
                     31.9 fixed budgets ──→ honest B7 scores (pairs with arc §B7 benchmark)
                     31.8 persona — independent, prompt-only
```

---

## Wave 0 — Provenance unification (31.6) — *do first, value even if nothing else ships*

Fixes architecture-review gaps #20/#21: research-output reset on restart, three-way provenance
fragmentation. No LLM, no schema risk beyond one audit table.

| Item | Flag | Acceptance |
|---|---|---|
| Retention: archive instead of truncate `research/<roomId>/` | `app.university.researchOutputRetention` | ✅ **shipped 2026-06-06** — survey found archiving already live (`archivePreviousRunDirs`); the config adds `retain` (default `archive` = bit-identical). Post-restart `/paper-review` sees prior papers in `research/archive/<room>-<stamp>/` |
| Startup reconciliation check (markdown ↔ DB ↔ llm-trace by paperId) | `app.research.provenance.reconcile.enabled` | ✅ **shipped 2026-06-06** (`ProvenanceReconciler`, flag ON — read-only). Both acceptance cases test-pinned: clean run zero orphans / planted orphan flagged. Severities: md-without-DB WARNs; trace-without-DB counted (expected D3 suppression); DB-without-md counted (expected under archive) |
| `memory_audit` append-only log | — (rides the above) | ⏳ deferred until a 31.x mutation exists to record (31.1 capture is the first writer) |

> **2026-06-06 addendum — the 32.4 verdict store landed with this wave**
> (`scripts/backfill-review-verdicts.sh`): harvests every REVIEW_REPORT
> per-paper table (archived rooms + live) into `research/review-verdicts.csv`.
> First run: 148 rows from 30 reports — past the ≥100 calibration target for
> phase-32 Wave 3; older reports need column normalization at calibration time.

## Wave 1 — Capture (31.1) — *write-only shadow*

The `research_memory` table + deterministic capture writers (panel verdicts, gate retirements,
audit categories, paper outcomes). Zero read-path effect — this wave is the shadow mode of the
whole phase. **Shared plumbing note:** the ARC lesson overlay (§B4, pending) consumes the same
events; implement the capture seams once, with both consumers in mind.

**Acceptance:** one refresh ON ⇒ every artifact has ≥1 row with resolvable `sourceRefs`; replay
dedups by hash; OFF ⇒ byte-identical.

## Wave 2 — Consolidation + hygiene (31.2 + 31.5)

The offline compiler: concept-grouped synthesis (≤10 fast calls/pass), timestamp-causal
supersession, contradiction sweep, decay/dormancy, health metrics (live count, supersession rate,
contradiction rate). Hash-incremental: unchanged input ⇒ no-op.

**Acceptance:** known confirm-then-refute replay produces a superseding chain; regenerate-from-
scratch equivalence; planted contradiction flagged in one pass.

## Wave 3 — Recall (31.3) — *the first read-path flip; A/B-gated*

Index-first injection at formulation + professor prompts, hard token budget, source-attributed.
**This is where the phase pays or fails** — the A/B bar (repeat-investigation rate drops,
NEEDS_REVISION rate flat) decides whether Waves 4–5 proceed.

## Wave 4 — Procedural skills (31.4)

Extract-on-success only; injected via the Wave-3 path. First slice of 29.3.4 (prompt-level only).

## Wave 5 — Synthesis papers (31.7)

New genre over the compiled store + derivation graph. Gated on Wave 2 (needs compiled lessons) and
on the existing citation machinery; label capped at weakest cited source.

## Ops track (schedulable anytime, no dependencies)

| Item | Notes |
|---|---|
| 31.10 experiment journal | backfill one episode from `phase-flags.md` (the F7.3 rollback) as the smoke test; then every flip lands a row. Feeds 29.2.3's A/B and the §B7 benchmark. |
| 31.9 fixed budgets | size from observed p95 first; pin B7 replays to budgets. |
| 31.8 persona binding | start `mode=static-table` (zero LLM cost); reviewers stay generalist. |

---

## Flag inventory (all default OFF)

```yaml
app.research.memory.enabled                        # 31.1 capture (write-only)
app.research.memory.consolidation.enabled          # 31.2 + 31.5 (incl. lint; maxLlmCallsPerPass)
app.research.memory.recall.enabled                 # 31.3 (tokenBudget, topK)
app.research.memory.skills.enabled                 # 31.4
app.research.synthesis.enabled                     # 31.7 (everyNPapers)
app.research.persona.enabled                       # 31.8 (mode: static-table | llm)
app.research.investigationBudget.enabled           # 31.9 (per-class budgets)
app.research.provenance.reconcile.enabled          # 31.6 startup check
app.university.researchOutputRetention             # 31.6 archive|truncate (config, not flag)
# 31.10 journal is operational tooling — no runtime flag (B7 convention)
```

## The shape of it

- **W0+W1 are ~most of the practical payoff for least risk** — restart survivability + a durable
  record of every verdict/retirement, with zero read-path exposure. The natural continuation of
  the frontier track's `verified_methods` persistence, generalized.
- **W2 is the pivot** (same role W2-the-graph played in Phase 30): consolidation is what turns a
  log into knowledge; everything downstream reads its output.
- **W3 is the risk gate:** prompt injection of memory is the only step that can *hurt* paper
  quality — hence the hard budget, the source attribution, and the explicit A/B before W4/W5.
- **Goodhart guard carried from ext #3:** capture writers must never record private-validator
  internals (held-out windows, thresholds); lint includes the check.

| Wave | Theme | Effort | Payoff | Risk |
|---|---|---|---|---|
| 0 | provenance | low | high (standalone) | low |
| 1 | capture | low | enabling | low |
| 2 | consolidation + lint | med | high | med (over-compression; mitigated append-only) |
| 3 | recall | low-med | high | **med-high** (prompt pollution; A/B-gated) |
| 4 | skills | low | med | low (abstain-by-default) |
| 5 | synthesis | med | med-high (visible) | med (label laundering; weakest-source cap) |
| ops | journal/budgets/persona | low each | med | low |
