# Pending items — rolling tracker

**Last refreshed:** 2026-07-04 (`/phase-review` full-walk audit; prior refresh 2026-06-06 predated the
whole June G1–G5 / gates / measurement-frame arc and was badly stale)
**Maintainer:** Claude Code review/build sessions; refresh after each working day or `/phase-review`.
**Convention:** items leave this list by shipping (link the commit) or by being explicitly
parked into a phase doc. This file is in the always-live set (never archived).

---

## 1. Awaiting next-run verification (deployed, unverified)

App has been down since 2026-06-30 21:15 — all of these need a live run (see `RUN-MINIMAL.md`).

| Item | Deployed in | What to watch |
|---|---|---|
| **G5 dirichlet θ̂ measurement** (√x-span `verify_oscillation` ladder fix) | 2026-06-30 (`4bb9970` cluster) | a dirichlet_error *oscillation/measurement* paper reporting a bounded O(1) envelope (flat ~2.85 across rungs, not the artefactual 2.92→1.23 decline); program closing bar = panel-novelty ≥ 0.7 |
| G1 openness bar | ON since 2026-06-14 | ≥ 60% of conjecture topics open-behavior / second-order, held across ≥ 3 refreshes |
| G3 vocab-expansion admission | shipped per open-behavior doc (status 2026-06-30) | first grammar-admitted scalar against a *real residual* target reaching a published conjecture (log has been `60 probed, 0 admitted` every pass) |
| G4 QD-archive retention burn-in | `4bb9970`, `qdSelectionEnabled` ON | `qd selection — N rows → M cells` collapse ratio (first positive signal 2026-06-30: 595→539) + no single-LHS monoculture across refreshes |
| scope-recover-gate first budgeted fire | ON since 2026-06-18 | `prereg:pivot:scope_recover_rerun` → `scope_recovered`; the 2026-06-22 detection was budget-starved (published NULL 1.5s later) |
| measurement-target seed served | `b129b39`, flag ON | the injected `dirichlet_error` `_measurement_target_` row actually selected for a paper (not surfaced 06-22) |

## 2. Phase-32 — to closure

| Item | Blocker | Next action |
|---|---|---|
| **Wave 3 calibration run** | label set STILL has zero Reject rows (none through the 06-29/06-30 batches — itself a quality signal; latest batches bottom out at Major revision) | revisit the deploy bar or wait; then one dedicated start with `ET_REVIEW_CALIBRATION=1` (~25 calls) |
| Wave 4 — 32.5 literature-grounding sidecar (PaperQA2) | none for the BUILD (dormant anytime); FLIP gated on first promotion paper | build py-sidecar `/ground/claim` + curated NT corpus per `phase-32-manuscript-grounding.md` §32.5; 32.6's citation-API-only guard rides with it |
| 32.6 small guards (second-pass refinement, reference-position check) | none | ride along with any narration PR |
| Figure agent (ARC §B1) | parked until papers have figures | re-survey AI-Scientist-v2 VLM machinery when scheduled |

## 3. Phase-31 — capture→consolidate→recall loop CLOSED (this section was stale)

| Item | Status |
|---|---|
| 31.1 capture layer | ✅ shipped 2026-06-06, flag ON |
| 31.2 consolidation | ✅ flipped ON 2026-06-08 (`memory-consolidation:` passes live; supersession + LESSON synthesis working) |
| 31.3 recall | ✅ flipped ON 2026-06-08 (`memory-recall: topic=` injection live; first compiled lessons incl. cramer_gap_ratio) |
| 31.7 synthesis papers | ✅ live; evidence-based re-synthesis (`d2e4983`, `reSynthesisMinNewSources: 5`); synthesis NPE fixed 2026-06-30 (`4bb9970`), confirmed running on the dirichlet family |
| `memory_audit` log | ⏳ still not built (no code hits 2026-07-04); fold into the next 31.x touch |
| remaining 31.4–31.6 / 31.8–31.10 composition & process items | see `phase-31-research-memory/` docs — not audited this pass |

## 4. Carried small / conditional

| Item | Trigger / note |
|---|---|
| **30.7 P3b — persist accepted mechanisms onto papers** | verified unbuilt 2026-07-04 (no persistence code); the one open W6 follow-on |
| **Lever F — write-back only sees the winning-candidate trace** | a tight bound in a panel-demoted paper is lost; next write-back fix (see memory `project_writeback_decision_a`) |
| **Paper-review recs 2026-06-30** (a) ground scalar definitions from the tool schema (P-741 fabricated `d(n)>ω·Ω` vs substrate `ω²`); (b) generation guard vs disjoint-predicate recreational densities (P-744/P-739 family); (c) published-topic recency gate vs cross-cycle duplicate serve (P-742/P-745, same bound 4 min apart) | the two genuine quality leaks + one redundancy — all generation-side; fix before/with the next live run |
| Title-drift re-stamp for NON-measurement objects (title = seed bound after a pivot/retract) | covered for MEASUREMENT_FRAMES objects (0c56d6b/7967038); general fix open, lower priority |
| Lever 3 — panel verify tools (reviewers recompute, not just read) | tool-calling roadmap |
| Behavioral-signature discovery detector (sim #12) | phase-30 W6 leftover, low |
| Trace-reconcile timing (run at drain, not startup) | low priority; recorded so traces=0 isn't misread as a bug |
| `promotionRefutedAt` persistence (cross-restart docket seals) | only if re-hearings crowd fresh candidates out |
| Host memory ELEVATED watch / deepseek panel seat / k=0 legacy-stratum serve filter | standing low-priority operational items |
| 29.x tiers (rollback ratchet, code-visible fitness, clade credit…) | revisit after 32.4 calibration |

## 5. Standing triggers (no action until they fire)

| Trigger | Then |
|---|---|
| First promotion paper (`ANOMALY_PROMOTION` survives the gate) | flip 32.5 grounding; un-defer 30.4; W7 acceptance met |
| First genuine mis-specification fixture (vs off-target trace) | build the amend-hypothesis pivot arm (32.3's deferred half) |
| Reject labels accumulate | run Wave-3 calibration; on pass, flip `review-anchor.enabled` (advisory) |
| Re-hearing crowding | persist `promotionRefutedAt` |

---

**Recommended next stint:** a live run (app + sidecar per `RUN-MINIMAL.md`) to exercise §1 —
especially the G5 dirichlet measurement, the G1 ≥60% bar, and scope-recover's first budgeted fire —
then §4's two generation-side paper-review recs (scalar-definition grounding + disjoint-predicate
guard).
