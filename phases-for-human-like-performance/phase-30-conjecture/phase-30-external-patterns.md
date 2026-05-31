# Phase 30 — patterns from external self-improving frameworks

Addendum to [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md). Distils
transferable patterns from four open-source self-improvement frameworks and maps each to a specific
Phase 30 amendment. The frameworks evolve *coding agents*; what transfers is their **meta-strategy**
(open-ended exploration, archive/selection, credit-assignment, anti-reward-hacking), not coding
specifics. Read alongside the triviality diagnosis ([`conjecture-engine-triviality.md`](conjecture-engine-triviality.md)):
patterns 1, 2, 4, 7 below directly attack the "212 near-identical curve-fits, none retired" pathology.

**Sources reviewed** (under `/opt/apps/ide/vscode/ai/reference-from-github/self-improving-frameworks/`):
`darwin-godel-machine` (DGM), `huxley-godel-machine` (HGM), `self-taught-optimizer` (STOP),
`live-swe-agent` (thin landing-page repo — two minor patterns only).

---

## Top patterns (ranked by value to Phase 30)

### 1. Quality-diversity archive keyed on a behavioral descriptor — collapses the duplicate flood
- **Source:** DGM `DGM_outer.py::update_archive` (keep-all) + `analysis/visualize_archive.py`
  (per-node *descriptor*, separate from raw score); STOP
  `tasks/meta_optimization/secret_seed_algorithm_improved.py` (dedup via `solutions_cache` set + top-k
  truncation per round).
- **Idea:** keep-all only works when every candidate is binned into a MAP-Elites *descriptor cell* and
  you keep one elite per cell, not every fit. STOP dedups + truncates to top-k; DGM bins by behavior.
- **Phase 30 amendment — NEW (30.8 *or* fold into the miner):** give each conjecture a descriptor
  tuple `(variable-set signature, derivation type [raw | anomaly | residual | composition],
  Mechanism kind [30.7])` and keep **one elite per cell**. The 212 near-identical `lhs ≤ 2·rhs + c`
  fits collapse into a handful of cells by construction.

### 2. Select by expected lineage value, NOT immediate fit — solves the open promotion/selection problem
- **Source:** HGM `tree.py::get_decendant_evals` (a node's value = pooled 0/1 held-out record of its
  whole **clade**) + `hgm.py::TS_sample`/`expand` (Thompson sampling over
  `Beta(1+clade_wins, 1+clade_losses)`; the node's own `mean_utility` is used **only as an eligibility
  gate**, `hgm.py:388`, never in the ranking). Also DGM `DGM_outer.py::choose_selfimproves`
  (`score_child_prop`: probability ∝ `sigmoid(score) × 1/(1+children_count)` — performance × inverse
  popularity).
- **Idea:** ranking candidates by their own fit is the exact "tightest curve-fit wins" trap HGM was
  built to fix. Rank instead by the validated productivity of a candidate's *descendants*.
- **Phase 30 amendment — 30.6 (promotion):** rank a finding by the held-out success of everything
  *derived from it* (residuals/compositions/anomalies it seeded); use its own fit (R²/p) only as an
  on/off eligibility gate; sample (Thompson) rather than greedily take the max.

### 3. Hidden/private validator + a "gaming-rate" health metric — teeth for the anti-HARKing guardrails
- **Source:** STOP `tasks/.../secret_utility.py` (the generator is shown a *fake* `utility.str`; real
  scoring runs a **secret** validator it cannot read, `secret_utility.py:140-143`); val/test split with
  *different seeds AND sizes*, test never feeds selection (`parity_noise/secret_utility.py:21-27`,
  `123-135`); sandbox-bypass attempts *counted as a first-class metric* (`tasks/sandbox/`, README).
  DGM `self_improve_step.py::diagnose_improvement` uses a **separate verifier model** from the agent;
  `visualize_archive.py::get_hallucination_score` tracks a hallucination axis *alongside* score.
- **Phase 30 amendment — guardrails:** (a) the narrator/miner must not see the exact acceptance/
  validator code or the held-out replication slice — score against a private validator it can't
  introspect; (b) track a **"passed public gate, failed private gate" rate** as a corpus-health metric.
  Pointed for this room: the narrator already games (P-b72c-19 fabricated `1.35`; P-b72c-24 misapplied
  the ln(10) hint). Keep generator and validator orthogonal — see memory
  `narrator_model_time_confound`.

### 4. Sublinear generation budget — a structural curb on the explosion
- **Source:** HGM `hgm.py:413-416` — expand a new child only when `n_task_evals**alpha >= tree_size`
  (`alpha=0.6 < 1`); otherwise spend the step *measuring* an existing node. STOP `config.py:11-15` +
  `language_model.py:97-100` — a hard per-cycle generation budget.
- **Idea:** most compute goes to *verifying* existing candidates, not minting new ones.
- **Phase 30 amendment — guardrails:** cap new-conjecture generation as a **sublinear function of total
  validations performed** (`n_validations**α`). Forces replication/refutation over endless mining — the
  direct antidote to "212 and counting."

### 5. Tiered cheap→expensive gates — budget the expensive Phase 30 steps
- **Source:** DGM `self_improve_step.py` (`test_more_threshold`: large eval only if the small set
  clears a bar) + `DGM_outer.py::get_full_eval_threshold` (full eval reserved for near-top nodes).
  HGM `utils/eval_utils.py::score_tie_breaker` (LLM adjudicator invoked **only** on ties).
- **Phase 30 amendment — pipeline ordering:** in-sample fit → held-out (30.1) → null-model beat (30.2)
  → **only survivors** pay for embedding retrieval (30.4), mechanism rigor (30.7), and adversarial
  re-runs (30.6). Don't spend the expensive gates on every mined relation.

### 6. Pseudo-count Bayesian smoothing — a cleaner multiple-comparisons stand-in
- **Source:** HGM `tree.py:45-46::get_pseudo_decendant_evals` (a node with `< n_pseudo` evals has its
  record padded toward its conservative mean; `config.yaml:29` `n_pseudo_descendant_evals`).
- **Phase 30 amendment — 30.1:** shrink each candidate's promotion score toward a conservative prior
  until it has accrued enough *independent* held-out confirmations — a Bayesian complement to the
  Bonferroni/BH correction already specified.

### 7. Keep-only-if-improves + revert — operationalises retirement
- **Source:** STOP `run_improver.py:141-147` (re-evaluate after a change; `if checked_utility == 0`
  roll back to the previous improver).
- **Phase 30 amendment — retirement (the §2 diagnosis fix):** replace "keep any that fits, never
  retire" with keep-iff-it-beats-its-predecessor-on-held-out + revert.

### 8. (minor) Runtime operator synthesis + forced post-result reflection + irreversible submit
- **Source:** live-swe-agent `config/livesweagent.yaml` ("Creating your own tools"; reflect-after-every-
  observation) + `livesweagent_swebench.yaml` (reproduce→fix→re-run→submit, with a post-submit lock).
- **Phase 30 amendment:** let the system synthesize task-specific analysis operators (a custom
  residual-extractor / null-model sampler) per conjecture rather than only a fixed library (30.3/30.5);
  gate publication behind an independent reproduction run with an **irreversible submit boundary** so a
  claim can't be silently re-tuned after acceptance (reinforces the pre-registration discipline).

---

## What the frameworks *validate* about the existing Phase 30 plan

- **30.5's MDL guard is load-bearing and correct.** STOP is the cautionary counter-example: it has
  **no** description-length penalty anywhere (its `helpers.py::find_largest_code_block` even rewards the
  *longest* block) and relies only on a prompt sentence ("must be novel and non-trivial"). Phase 30
  should go *beyond* STOP here — `score = held-out fit − λ·description_length(feature)`.
- **Out-of-sample-only** (all four), **adversarial replication** (30.6, ≈ STOP's gaming-attempt
  counting + live-swe's verify-before-submit), and **separate-verifier mechanism rigor** (30.7 ≈ DGM's
  independent `diagnose_model`) are all independently corroborated.

## One caveat to carry into implementation

Both DGM and HGM **never prune** — they control bad candidates purely by *never selecting* sterile
lineages (their Beta posterior stays low; selection probability decays). But Phase 30's system
**publishes** from the archive, so selection-suppression alone is insufficient: it additionally needs an
explicit **retirement/quarantine gate** (pattern 7), or stale low-clade-value conjectures still surface
as output. Adopt clade-value *selection* (pattern 2) **and** the retirement fix — complementary, not
substitutes.

---

## Highest-leverage summary

Patterns **1 (descriptor archive)** and **2 (clade-value selection)** are the two that, together, make
the curve-fit flood collapse by construction *and* fix the credit-assignment problem Phase 30 left open.
Pattern **3 (private validator + gaming-rate)** is the most pointed given this room's narrator-gaming.
None of these are in the current Phase 30 draft; all four serious-framework reviews converge on the same
diagnosis — Phase 30's gap is **selection + diversity + anti-gaming bookkeeping**, exactly the layer
DGM/HGM/STOP exist to provide.
