# Pending items — rolling tracker

**Last refreshed:** 2026-06-06 (post-enforce-flip, `b75b659`)
**Maintainer:** Claude Code review/build sessions; refresh after each working day or `/phase-review`.
**Convention:** items leave this list by shipping (link the commit) or by being explicitly
parked into a phase doc. This file is in the always-live set (never archived).

---

## 1. Awaiting next-session verification (deployed, unverified)

| Item | Deployed in | What to watch |
|---|---|---|
| Enforce loop first live fire | `38b822f` + `b75b659` | `manifest-enforce:` lines; `value-arm reasons retired` INFOs; dynamic VALUE PROVENANCE rule driving misses → ~0 before the gate fires |
| Miner gates' acceptance effect | `376cace` | first session where degenerate pairings/non-finite params are seed-blocked; expect the 33↔83% acceptance oscillation to dampen |
| Pivot / resample accumulation | `517cdfc` / `e80ceda` | 1 pivot success + 2 resample rescues so far; pivot acceptance bar wants ≥1 pivoted paper publishing clean over 2 sessions |

## 2. Phase-32 — to closure

| Item | Blocker | Next action |
|---|---|---|
| **Wave 3 calibration run** | label set has ZERO Reject rows → Reject-recall n/a → deploy bar cannot pass | 2026-06-06 checked: a3289005 report archives are NOT on disk — Reject labels must accumulate naturally (none in 7 sessions, itself a quality signal). Revisit the bar or wait; then one dedicated start with `ET_REVIEW_CALIBRATION=1` (~25 calls) |
| Wave 4 — 32.5 literature-grounding sidecar (PaperQA2) | none for the BUILD (dormant anytime); FLIP gated on first promotion paper | build py-sidecar `/ground/claim` + curated NT corpus per `phase-32-manuscript-grounding.md` §32.5; 32.6's citation-API-only guard rides with it |
| 32.6 small guards (second-pass refinement, reference-position check) | none | ride along with any narration PR |
| Figure agent (ARC §B1) | parked until papers have figures | re-survey AI-Scientist-v2 VLM machinery when scheduled |

## 3. Phase-31 — the next big build

| Item | Why it's next | Status |
|---|---|---|
| **31.1 capture layer** (`research_memory` store + deterministic writers) | the five-paper cramer convergence (214/218/222/232/240: ceiling ≈0.43–0.45 on [3×10⁵, 1.8×10⁶], violated <2.5×10⁵) has NO assembler — every session adds a tile to a map nothing can see | ✅ **shipped 2026-06-06** — `ResearchMemoryEntity/Repository/Service` (append-only, SHA-256 insert-or-reinforce, room/bot/run scoping) + two writers (paper-outcome FACT at publish; retirement FACT in `persistRetired`, reseed-churn-safe via hash dedup). Flag ON (capture-only). Acceptance: next session, every published/retired artifact has ≥1 row; replay-stable counts |
| 31.2 consolidation / 31.3 recall / 31.7 synthesis papers | downstream of 31.1 — **next builds**; 31.7's first target is the cramer-convergence synthesis | not started |
| `memory_audit` log | 31.1 now produces mutations to record — unblocked, fold into 31.2 | was deferred |

## 4. Carried small / conditional

| Item | Trigger |
|---|---|
| Trace-reconcile timing (run at drain, not startup — startup always reads traces=0 since `llm-traces/` is per-run) | low priority; recorded so traces=0 isn't misread as a bug |
| `promotionRefutedAt` persistence (cross-restart docket seals) | only if re-hearings ever crowd fresh candidates out (not yet — 2 hearings/session, all correct REFUTEDs) |
| Lever 3 — panel verify tools (reviewers recompute, not just read) | tool-calling roadmap; would have caught P-215 without any gate |
| Behavioral-signature discovery detector (sim #12) | phase-30 W6 leftover, low |
| Host memory ELEVATED watch / deepseek panel seat / k=0 legacy-stratum serve filter | standing low-priority operational items |
| 29.x tiers (rollback ratchet, code-visible fitness, clade credit…) | rollback-ratchet's judge metric is now planned as the calibrated anchor (32.4) — revisit after calibration |

## 5. Standing triggers (no action until they fire)

| Trigger | Then |
|---|---|
| First promotion paper (`ANOMALY_PROMOTION` survives the gate) | flip 32.5 grounding; un-defer 30.4; W7 acceptance met |
| First genuine mis-specification fixture (vs off-target trace) | build the amend-hypothesis pivot arm (32.3's deferred half) |
| Reject labels accumulate (or a328 backfill supplies them) | run Wave-3 calibration; on pass, flip `review-anchor.enabled` (advisory) |
| Re-hearing crowding | persist `promotionRefutedAt` |

---

**Recommended next stint:** §2's Reject-label backfill (minutes) → §3's 31.1 capture layer
in the worktree. Calibration run and Wave 4 slot in whenever their triggers/spend make sense.
