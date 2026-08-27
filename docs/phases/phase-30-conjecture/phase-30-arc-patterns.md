# Phase 30 — patterns from AutoResearchClaw (autonomous research pipeline)

Third external-review addendum to
[`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md), after the
self-improving-frameworks addendum ([`phase-30-external-patterns.md`](phase-30-external-patterns.md))
and the simulation-frameworks addendum
([`phase-30-simulation-patterns.md`](phase-30-simulation-patterns.md)). Where those two contributed
the *process* layer (selection/diversity/anti-gaming) and the *content* layer
(irreducibility/mechanism/null gates), this one contributes the **pipeline/production layer**:
figure generation, metrics grounding, investigation-level decision loops, lesson overlays, and
operational guardrails.

**Source reviewed (2026-06-04):** `aiming-lab/AutoResearchClaw` under
`/opt/apps/ide/vscode/ai/reference-from-github/AutoResearchClaw/` — a 23-stage autonomous
research-to-LaTeX-paper pipeline (~50K LOC Python, 2,699 tests). Security review of the clone
completed first: **reference-only**; its execution model must not be ported (see Anti-patterns, §C).

**Status: proposal — nothing below is built.** Every adopted item ships flag-gated **OFF**
(`@Value … = false`) per house convention, flipped only after its acceptance bar.

---

## A. Already covered — ARC features that map to existing/pending ET work (no new items)

| ARC feature | ET equivalent | Status |
|---|---|---|
| Quality-diversity archive, inverse-popularity selection | ext #1 `QualityDiversityArchive`, ext #2 `CladeValueSelector` | shipped (flags) |
| Private validator / anti-gaming | ext #3 `PrivateHoldoutValidator` + gaming-rate | shipped flag-OFF |
| Multi-agent debate (hypothesis/analysis/review) | professor–student critique + `ReviewPanel` tool-calling reviewers | live |
| Derivation/knowledge graph with typed provenance edges | W2 `DerivationGraph` + `DERIVES_FROM`; RAF closure (W6) | shipped |
| Composition claims | 30.3 `ConjectureComposer` | shipped (flag) |
| Frontier pivoting on stall | branch taxonomy B3→B5 ([`frontier-escalation-loop.md`](frontier-escalation-loop.md) §4) | live |
| Self-evolution: directed prompt improvement | phase-29 §29.2.3 (pending) — **improved below, item #4** | pending |
| Skill library / lesson→skill | phase-29 §29.3.4 (pending) — **improved below, item #4** | pending |
| Multi-provider LLM routing, retry, cost *tracking* | `LlmRouter` / `TokenAccountant` (equal or better than ARC's) | live |
| External citation resolution | 30.4 literature grounding — **design updated in
  [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) §30.4, still pending** | pending (23.8-gated) |

---

## B. New patterns (ranked by value to ET; none previously defined or pending)

### 1. Figure agent — plan → generate → render → critique → integrate  *(highest value)*
- **Source:** ARC `agents/figure_agent/` — a 5-stage pipeline: Planner (choose chart types from the
  metrics), CodeGen (matplotlib + SciencePlots styling, 300 DPI, colorblind-safe palettes), Renderer
  (execute + verify the file exists/parses), Critic (tri-modal review: numerical fidelity, caption
  text, visual quality), Integrator (placement + captions). Configurable min/max figures.
- **ET today:** papers publish prose + numbers with **zero figures**, while the substrate is
  plot-ready — `M(x)/√x` frontier ratchets, stall-predictor cost curves, conjecture window sweeps,
  residual series (30.2), government trend buckets.
- **Adoption shape:** render via the existing Python sidecar (`:8089`) under the existing
  review-before-exec discipline (fast-4b code review, ulimit/timeout) — *not* a new execution
  surface. The Critic's load-bearing check is **numerical fidelity**: every value in the plot must
  resolve to the metrics manifest (#2 below). Render failure degrades to no-figure; it never blocks
  publication.
- **Flag:** `app.research.figures.enabled` (default OFF).
- **Acceptance:** a published paper carries ≥1 figure whose plotted series matches the computed
  metrics exactly (spot-check by replay); zero papers blocked by figure failures; sidecar guardrails
  hold (no new sandbox-friction class in the logs).

### 2. Verified-metrics manifest as the single grounding source
- **Source:** ARC's anti-fabrication core — every metric from experiment execution is recorded once
  in an immutable per-run manifest (with checksums); the draft/revision stages validate every cited
  number by **manifest lookup**, not by re-scanning transcripts.
- **ET today:** the same goal is met by *scanning* — prose-constant grounding guard,
  `NumericalConsistencyValidator`, unsourced-claim downgrade (P8c scans the tool-call trace). Each
  validator re-derives "what numbers are legitimate" independently.
- **Adoption shape:** invert to write-once — `MathPhysicsEngine` + professor tool-loop outputs append
  to a per-paper immutable metrics manifest at computation time; the validators (and the figure
  Critic, #1) become lookups against it. The fabricated-constant class (P-b72c-19's invented `1.35`)
  is then caught at draft time by a failed lookup instead of post-hoc by pattern heuristics.
- **Flag:** `app.research.metricsManifest.enabled` (default OFF; shadow mode first — log
  would-fail lookups alongside the existing validators, à la `w4ShadowEnabled`).
- **Acceptance:** on a replay corpus, manifest-lookup catches a superset of what the unsourced-claim
  validator catches today, with zero false flags on legitimately-cited reference constants
  (`lookup_reference`-style values must be manifest-registered too).

### 3. Investigation-level REFINE / PIVOT with a fresh pre-registration
- **Source:** ARC Stage 15 (RESEARCH_DECISION) — after results, an explicit PROCEED / REFINE / PIVOT
  decision with artifact versioning and a bounded pivot count; REFINE loops to refinement, PIVOT
  loops to hypothesis generation.
- **ET today:** the frontier track pivots (B3→B5) but the *paper* pipeline is single-shot — a weak
  investigation publishes NULL_RESULT or re-enters via `GateRetryQueue` under the **same** framing.
  "Mid-investigation hypothesis revision" is a known gap, deliberately unfilled because naive
  revision is HARKing.
- **Adoption shape (the discipline that makes it honest):** a post-computation decision step may
  REFINE (re-run at different windows/parameterization) or PIVOT (switch conjecture), but **every
  REFINE/PIVOT mints a fresh `PreRegistration`** and marks the superseded one `SUPERSEDED` —
  persisted, never amended. Cycles bounded (≤2 refines, ≤1 pivot per investigation). The published
  paper cites its full pre-registration chain, so a reader sees every abandoned framing. NULL_RESULT
  remains a first-class terminal outcome — the decision step must not become a
  "retry-until-positive" loop (decision rationale logged; refile-rate becomes a health metric).
- **Flag:** `app.research.investigationDecision.enabled` (default OFF).
- **Acceptance:** on replay, ≥1 investigation that today publishes a low-value NULL_RESULT instead
  pivots and publishes a distinct supported claim **with the superseded pre-registration visibly
  chained**; the NULL_RESULT *rate* over the corpus does not collapse (anti-gaming check — pivoting
  must not launder nulls away).

### 4. Continuous lesson overlay with time decay  *(improves pending 29.2.3 / 29.3.4)*
- **Source:** ARC `evolution.py` — every run auto-extracts categorized lessons (SYSTEM / LLM / DATA /
  CODE / RESEARCH) from stage failures and quality flags into a JSONL store; future runs inject the
  top-k lessons per stage as a prompt overlay, weighted by 30-day time decay. Their
  `metaclaw_bridge` then promotes recurring lessons to reusable prompt-skill files — measured
  **+18.3% robustness, −24.8% stage retries, −40% refine cycles** in their controlled tests.
- **ET today / pending:** phase-29 §29.2.3 (directed prompt self-improvement) is the *structural*
  variant — periodic, one diagnosed template change, A/B-gated. Nothing covers the *continuous*
  variant: per-run lesson extraction injected as decayed overlays without touching templates.
- **Adoption shape:** extract lessons from `ReviewPanel` verdicts, gate retirements (the W4 shadow
  would-retire log is a ready-made source), and `NARRATION_AUDIT` categories → JSONL keyed by
  (stage, category); inject top-k decayed lessons into narration/professor prompts. Complementary to
  29.2.3, not a substitute: overlay handles the long tail cheaply; A/B handles template-level
  changes safely. Cross-reference notes added to phase-29 §29.2.3/§29.3.4 (both remain pending).
- **Flag:** `app.research.lessonOverlay.enabled` (default OFF).
- **Acceptance:** with the overlay ON over ≥3 refreshes, NEEDS_REVISION rate drops vs. the
  flag-OFF baseline with no change in gate strictness; overlay text never exceeds a fixed token
  budget; deleting the JSONL reverts behavior exactly (no hidden state).

### 5. Budget-threshold cost guard with auto-pause  *(small)*
- **Source:** ARC `hitl/cost_guard.py` — 50% / 80% / 100% budget thresholds; alert, then auto-pause.
- **ET today:** `TokenAccountant` / `TokenBudgetReporter` *measure* spend (~$0.0098/paper) but
  nothing acts on it mid-run.
- **Adoption shape:** per-cycle and per-day budget config; at 80% log + notify, at 100% stop
  *starting* new investigations (in-flight papers complete — never truncate a paper mid-narration,
  which would manufacture exactly the degenerate/stub output the validators exist to catch).
- **Flag:** `app.research.costGuard.enabled` (default OFF).
- **Acceptance:** a deliberately low budget in a test run pauses new starts at the threshold,
  completes in-flight papers, and emits the notification; normal budgets never trigger.

### 6. Publication-boundary HITL gate + escalation tiers  *(low priority until volume warrants)*
- **Source:** ARC `hitl/` — a spectrum of 6 intervention modes; the relevant slice is **gate-only**:
  pause at a small number of critical gates, approve/reject from another terminal
  (`attach`/`approve`/`reject`), escalation tiers (terminal → message channel → halt) on unattended
  pauses, with per-gate timeout → configured default action.
- **ET today:** fully autonomous publish; operator review is post-hoc (the paper-review workflow).
  30.6's adversarial promotion gate is machine-only.
- **Adoption shape (minimal):** an optional gate where papers classified SUPPORTED above a novelty
  bar park as `PENDING_APPROVAL` before `PUBLISHED`; REST approve/reject; timeout → auto-approve
  (configurable). Sequence it *after* 30.6 — machine refutation first, human gate last, so the human
  only sees survivors. Notification plumbing is shared with #5.
- **Flag:** `app.research.publishApproval.enabled` (default OFF).
- **Acceptance:** with the flag ON, no paper reaches `PUBLISHED` without an approval record or an
  explicit timeout-default record; with it OFF, behavior is byte-identical to today.

### 7. Graded paper benchmark — the ARC-Bench pattern  *(operational, no runtime flag)*
- **Source:** ARC `experiments/arc_bench/` — 55 frozen topic manifests (research question,
  conditions, metrics) + a scoring rubric, versioned and replayable, used to measure whether
  pipeline changes actually improve output.
- **ET today:** per-phase acceptance bars + manual paper-review reports; the wishlist's acceptance
  rows already imply replays (P1/P2/P4 "replay P-410/427/432"), but there is no *frozen, versioned
  corpus* run on every flag flip.
- **Adoption shape:** freeze a benchmark set — N representative topics/conjectures + the known
  failure replays + the paper-review rubric encoded as machine-scorable dimensions
  (grounding, citation integrity, label honesty, statistical framing). Run it before/after each
  flag flip; the diff is the regression signal. This is tooling (a script + a frozen manifest dir),
  not runtime behavior.
- **Acceptance:** the benchmark reproduces a stable score on an unchanged build (±noise band), and
  retro-detects a known regression (e.g., the F7.3 FP-rate climb that forced the 2026-05-20
  rollback) when replayed across those two builds.

### 8. (minor, recorded for completeness — do not build now) Trend-driven topic selection
ARC's `trends/` (feed monitoring → emerging-topic scoring → topic suggestions) could feed
`ConjectureTopicProvider`. Explicitly **deferred** behind the same 23.8 calibration that defers
30.4 — the room's standing finding is *generation, not retrieval, is the blocker*. Listed so the
idea isn't re-litigated from scratch later.

---

## C. Anti-patterns — reviewed and rejected (do not port)

- **ARC's execution model.** Its default "sandbox" is an **unsandboxed host subprocess** with the
  full environment; its AST validator is a bypassable name-denylist not applied on the primary
  path; agent modes default to `--dangerously-skip-permissions`; Docker mode silently falls back to
  host execution when the daemon is down. ET's `SandboxEngine` (closed mini-language, static
  whitelist, ulimit, review-before-exec) and the
  [`runtime-self-verifying-execution.md`](runtime-self-verifying-execution.md) subprocess-isolation
  boundary are **stronger** — the flow of value here is ET→ARC, not ARC→ET.
- **23-stage contract/resume machinery.** ARC needs it because a run costs dollars-to-tens-of-dollars
  and hours; ET cycles are ~$0.01 and minutes — checkpoint/resume overhead buys nothing.
- **Multi-provider LLM layer.** ET's `LlmRouter`/pool/priority-reservation stack is equal or better.

---

## Flag inventory (all default OFF)

```yaml
app.research.figures.enabled                  # B1 figure agent
app.research.metricsManifest.enabled          # B2 verified-metrics manifest (shadow mode first)
app.research.investigationDecision.enabled    # B3 refine/pivot + fresh pre-registration
app.research.lessonOverlay.enabled            # B4 lesson overlay (with phase-29 29.2.3)
app.research.costGuard.enabled                # B5 budget thresholds + auto-pause
app.research.publishApproval.enabled          # B6 publication-boundary HITL gate
# B7 benchmark is operational tooling — no runtime flag
```

## Suggested ordering

**#2 (manifest) first** — it is the substrate #1's critic and the existing validators converge on,
and it ships safely in shadow mode. Then **#4 (lesson overlay)** — cheapest measured-payoff item,
piggybacking on the W4 shadow log that already exists. **#1 (figures)** next for visible output
value. **#3 (refine/pivot)** only after W4's evaluation hardening is flipped ON, since its
anti-gaming acceptance leans on honest verdicts. **#5/#6/#7** are operational, schedulable anytime.

## Cross-references

- ARC clone + security review: `/opt/apps/ide/vscode/ai/reference-from-github/AutoResearchClaw/`
- 30.4 design update (layered citation resolution):
  [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) §30.4
- Lesson overlay / skill library pending items: `../phase-29-self-improving-paper-creation.md`
  §29.2.3, §29.3.4
- Deferred non-priority ARC features (voice, calendar, collaboration pub-sub, MCP, wizard,
  OpenClaw bridge): [`../arc-feature-considerations.md`](../arc-feature-considerations.md)
