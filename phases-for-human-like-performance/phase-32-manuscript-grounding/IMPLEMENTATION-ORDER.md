# Phase 32 — implementation order

Sequencing for items 32.1–32.6 ([`phase-32-manuscript-grounding.md`](phase-32-manuscript-grounding.md)).
House convention throughout: every wave ships flag-gated **OFF**, flipped only after its acceptance
bar; pipeline-wide pieces stay domain-neutral; shadow-before-enforce wherever a flip changes
terminal paper status (the W4 stochastic-null precedent).

---

## Dependency DAG (text)

```
32.1 manifest capture ──→ 32.2 shadow validator ──→ 32.2 enforce (narration + consolidation)
                                                          │
31.6 provenance/restart survivability (phase 31) ──→ 32.4 benchmark substrate ──→ 32.4 review head
                                                          │
ScoreCalibration.hypothesisLhsUncovered (shipped 2026-06-06) ──→ 32.3 pivot
                                                          │
30.6 first promotion paper (standing trigger) ───────→ 32.5 flag flip (engine builds dormant anytime)
32.6 guards — no dependencies (ride along with any wave)
```

Two independent tracks: the **manifest track** (32.1→32.2) and the **evaluation track**
(32.4, dependent on phase-31's 31.6). 32.3 and 32.5 hang off already-shipped substrate.

## Wave 0 — Manifest capture (32.1) — *write-only shadow; do first*

| Piece | Flag | Acceptance |
|---|---|---|
| `ManifestEntry` + per-draft assembly at tool-result rendering | `app.research.manifest.capture.enabled` | one session: manifest recall ≥ validator-ladder recall, ≤ few KB/paper |
| Derived-entry materialization (k·x, same-call x±y) | same flag | derived entries present for every pattern the ladder recognizes |
| Persist as paper appendix / sidecar JSON | same flag | survives publish; visible in `/paper-review` |

## Wave 1 — Manifest read path (32.2) — *shadow, then enforce*

| Piece | Flag | Acceptance |
|---|---|---|
| `ManifestTraceabilityValidator` (tolerance rules ported from data-to-paper `check_numeric_values.py`) | `app.research.manifest.validator-mode: shadow` | ≥2 sessions: ≥95% verdict agreement with the ladder + ≥1 net-new catch + zero new FP classes |
| Narration prompt: manifest as single citable source | `…validator-mode: enforce` | acceptance rate within noise of trailing baseline |
| Consolidate `NumericalConsistencyValidator` / `UnsourcedClaimValidator` numeric arms onto manifest lookup | same flip | the nine hand-fixed FP classes reproduce as manifest tolerance rules (regression-pinned) |

## Wave 2 — Pivot / fresh pre-registration (32.3)

| Piece | Flag | Acceptance |
|---|---|---|
| Post-professor trace classification (deterministic first: LHS-uncovered ⇒ MIS_SPECIFIED) | `app.research.pivot.enabled` | gate firing rate on *published* papers → 0 over two sessions |
| Single bounded re-registration (`pivot.max-refinements: 1`), old prereg retired-in-record, `prereg:pivoted` stamp | same flag | ≥1 pivoted paper publishes clean; calls/paper +<15%; zero cap breaches |
| Pivot is discretionary spend (drain-aware) | inherits `RunBudgetTracker` | no pivot starts during drain |

## Wave 3 — Calibrated review head (32.4) — *blocked on 31.6 for the durable label store*

| Piece | Flag | Acceptance |
|---|---|---|
| Benchmark substrate: durable operator-verdict store + backfill from `research/archive/*/REVIEW_REPORT.md` | none (ops, ARC §B7 slot) | ≥100 labeled papers banked |
| Calibration harness (sweep ensemble/reflections/few-shot; accuracy/F1/Reject-recall) | none (ops) | binary accuracy ≥80%, Reject-recall ≥90% on held-out 20% |
| Advisory review head, stamps `anchor:<verdict>:<p>`, no routing | `app.research.review-anchor.enabled` | ≥75% agreement with the next live `/paper-review` session |
| (later) anchor as 29.2.1 did-it-help judge / publish-gate input | separate flip, second calibration pass | — |

## Wave 4 — Literature-grounding sidecar (32.5) — *build dormant anytime; flip on the 30.6 trigger*

| Piece | Flag | Acceptance |
|---|---|---|
| py-sidecar `POST /ground/claim` (PaperQA2 lib, deterministic agent, Tantivy + local embeddings, contracrow trichotomy) | env-gated like `ET_DISCOVER_EXEC` | 20-claim probe set: verdict accuracy ≥80%, zero fabricated citations |
| Curated offline NT corpus (papers / OEIS dumps / textbook excerpts) + build-time metadata enrichment | n/a (asset) | probe set passes against it |
| Java `search_literature` grounding mode, promotion-track-only caller policy | `app.research.literature.grounding.enabled` | first promotion paper carries a verdict or honest SILENT |

## Ops track (schedulable anytime, no dependencies)

| Item | Note |
|---|---|
| 32.6 guards | citation API-only rule rides with 32.5; second-pass refinement + reference-position check ride with any narration PR |
| Survey debt | VLM figure machinery (v2) parked — re-survey with ARC §B1 figure agent; LLM node-selection (v2) parked until investigations exceed ~10 calls |

## Flag inventory (all default OFF)

```yaml
app.research.manifest.capture.enabled        # 32.1
app.research.manifest.validator-mode         # 32.2: off | shadow | enforce
app.research.pivot.enabled                   # 32.3
app.research.pivot.max-refinements           # 32.3 (config, default 1)
app.research.review-anchor.enabled           # 32.4 (advisory)
app.research.literature.grounding.enabled    # 32.5 (Java caller)
# 32.5 sidecar arming is env-side (start-script), per the ET_DISCOVER_EXEC convention
# 32.4 benchmark + calibration harness are operational tooling — no runtime flag (B7 convention)
```

## The shape of it

Wave 0 is free (write-only) and immediately useful in `/paper-review` (the reviewer reads the
manifest instead of reconstructing arithmetic). Wave 1 is where the validator whack-a-mole ends —
its enforce flip is the phase's riskiest moment and gets the full shadow A/B treatment. Wave 2
converts this week's gate-patches into generative behavior. Waves 3–4 are the two anchors to the
human world: human-calibrated judgment and human literature. If only one wave ever ships, ship
Wave 0+1 — every later wave reads from the manifest.
