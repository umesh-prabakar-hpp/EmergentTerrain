# Phase 31 — items 31.7–31.10: composition & process patterns (gpt-researcher, autoresearch)

All items flag-gated **OFF**. Smaller and independent of the 31.1–31.6 memory loop except where
noted (31.7 reads the memory store if present, but degrades gracefully without it).

---

## 31.7 — Synthesis papers: outline-first multi-section composition over the corpus  *(new genre)*

- **Source:** gpt-researcher detailed-report orchestration —
  `gpt_researcher/prompts.py:569-589` (generate subtopics from gathered context, bounded by
  `max_subtopics`), `gpt_researcher/skills/context_manager.py:88-118`
  (**similar-written-content lookup**: before drafting a section, retrieve what earlier sections
  already said so the new section builds on rather than repeats),
  `gpt_researcher/actions/markdown_processing.py:68-92` (TOC extracted from headers post-hoc as a
  structure check); deep-research recursion `gpt_researcher/skills/deep_research.py:372-519`
  (each level emits *learnings + follow-up questions*, recursion narrows breadth).
- **The gap in ET:** every ET paper is a single-investigation artifact (one conjecture, one
  hypothesis, fixed Hypothesis/Methods/Findings/Conclusion shape). Human research output includes
  **review/synthesis articles** — "what do the 14 surviving mertens-family bounds collectively
  say; where do they conflict; what is open" — and ET has the raw material (papers, derivation
  graph, QD archive, memory store once 31.2 ships) but no genre or composer for it.
- **Adoption shape:** a scheduled low-frequency `SynthesisPaperService` (e.g. one per N published
  papers per domain): (1) **outline first** — one planning call proposes 3-6 sections from the
  domain's live findings (the gpt-researcher subtopic step, grounded in DB rows + memory store,
  not web search); (2) sections drafted sequentially, each draft call receiving a digest of
  *already-written sections* (the similar-written-content trick) to prevent repetition;
  (3) every claim in a section must carry a `paperId`/conjecture-id citation — the existing
  `CitationValidator` + citations-as-schema machinery applies unchanged; (4) the panel reviews it
  like any paper, with `evidence_label` capped at the *weakest* label among its cited sources
  (a synthesis can't launder a CONJECTURE into an EMPIRICAL_RESULT by aggregation).
  NULL synthesis ("the corpus does not yet support a synthesis") is a first-class outcome.
- **Why this moves "human quality":** the paper-review reports repeatedly score ET output as
  isolated artifacts; synthesis is the visible marker of a research *program* (and exercises the
  derivation graph + clade machinery Phase 30 built, giving them a publishable surface).
- **Flag:** `app.research.synthesis.enabled` (+ `…synthesis.everyNPapers`, default high).
- **Acceptance:** one synthesis paper publishes citing ≥5 prior papers with zero
  citation-validator violations; its label respects the weakest-source cap; section drafts show no
  near-duplicate paragraphs (validator spot-check); with the flag OFF, no behavior change.

## 31.8 — Dynamic persona binding per topic  *(cheap, prompt-only)*

- **Source:** gpt-researcher `gpt_researcher/actions/agent_creator.py:18-62` (one low-temperature
  call maps the query to a domain persona + role prompt from a taxonomy,
  `prompts.py:486-511`; the persona then conditions *every* downstream stage — search, tone,
  structure).
- **The gap in ET:** narrator/professor/student/reviewer roles are static across all domains; a
  Mertens investigation and an RLC-transient investigation get the same generic researcher voice.
  The phase-29 ε-greedy template variants mutate templates, not *personas*.
- **Adoption shape:** at draft creation, one fast-tier call (or a static topic→persona table to
  start — zero LLM cost) selects a specialist persona ("analytic number theorist", "experimental
  physicist — circuits") injected as a preamble into narration + professor + student prompts for
  that paper. Reviewer prompts deliberately keep the *generalist* persona (independence — the
  reviewer should not share the author's frame). Persona text versioned in
  `PromptTemplateRegistry` so the existing A/B + rollback machinery governs it.
- **Flag:** `app.research.persona.enabled` (start `mode=static-table`, `mode=llm` later).
- **Acceptance:** persona block present in narration prompts when ON; panel-grade distribution
  does not regress over a refresh (A/B); reviewer prompts unchanged.

## 31.9 — Fixed-budget commensurable investigations  *(measurement discipline)*

- **Source:** autoresearch `prepare.py:31-32` (`TIME_BUDGET`: every experiment runs the same
  wall-clock budget, making iterations directly comparable regardless of what changed),
  `train.py:603` (loop terminates on budget, not step count), `program.md:21-23` (rationale:
  remove the slow-vs-fast confound).
- **The gap in ET:** investigation compute varies freely (professor tool loops ran 176–329 s in
  the phase-29 rollout log; frontier runs are budgeted but ordinary conjecture investigations are
  not), so cross-run quality metrics (confirmedRuns, survival rates, panel grades, the B7
  benchmark once it ships) compare apples to oranges, and any pipeline change that merely *spends
  more* looks like an improvement.
- **Adoption shape:** define per-investigation-class budgets (sidecar CPU-seconds + tool-call
  count + wall-clock) enforced at the professor-tool-loop level — the loop receives its remaining
  budget and the prompt states it (the autoresearch progress-fraction trick), so the model
  schedules its own depth. Papers record budget-consumed in the envelope; the B7 benchmark
  (phase-30-arc-patterns §B7) runs under pinned budgets, making before/after scores honest.
  Complements (does not replace) the ARC cost guard (§B5, dollar-level) — this is the
  *commensurability* budget, not the spend cap.
- **Flag:** `app.research.investigationBudget.enabled` (+ per-class budget config).
- **Acceptance:** with the flag ON, ≥95% of investigations complete within budget without the
  blanket `narrationTimeoutMs` firing; benchmark variance across identical replays shrinks vs
  flag-OFF; no increase in DEGENERATE/NULL rate (the budget must not truncate legitimate work —
  size budgets from the observed p95 first).

## 31.10 — Append-only pipeline experiment journal  *(automates phase-flags discipline)*

- **Source:** autoresearch `program.md:64-106` (every experiment appends one TSV row:
  `commit, metric, peak-resource, keep|discard|crash, description`; keep/discard decided by the
  frozen metric; `git reset` on regression; `analysis.ipynb` reads the journal to plot the
  running-best frontier), plus the **simplicity-first acceptance criterion** (`program.md:37-38`:
  a marginal metric win does not justify added complexity; an equal-metric simplification is
  kept).
- **The gap in ET:** flag flips and A/B outcomes are tracked *manually and narratively* in
  `phase-flags.md` and the phase docs — exemplary discipline, but not machine-readable, so nothing
  can plot the quality frontier across the 30+ flips to date, and the F7.3-style regression
  (flipped ON, FP rate climbed across refreshes, rolled back 2026-05-20) was caught by operator
  log-reading rather than by a journal diff.
- **Adoption shape:** a tiny append-only `pipeline_experiments` table (or TSV under `logs/`):
  one row per flag flip / template variant / config change —
  `date, change (flag+value or commit), benchmark score (B7 when it ships; panel-grade
  mean/distribution until then), resource (calls/$ per paper), verdict (keep|revert), notes`.
  Writers: the operator (a one-line CLI/REST append), and automatically the
  `PromptTemplateRollbackJudge` + any future A/B machinery. The phase-29 §29.2.3 directed
  prompt-improvement loop reads this journal as its measurement substrate. Adopt the
  simplicity-first criterion as written policy: an equal-score change that *deletes* code/config
  wins.
- **Flag:** none needed (operational tooling, like §B7) — ship the table + append path; the
  convention does the rest.
- **Acceptance:** the journal reconstructs the known history for one past episode (e.g. the F7.3
  flip→regression→rollback) when backfilled from `phase-flags.md`; every new flip during one
  refresh cycle lands a row; a quality-frontier plot (running best vs date) is producible from the
  table alone.

---

## Interaction map

```
31.6 provenance ──→ 31.1 store ──→ 31.2 consolidation ──→ 31.3 recall ──→ 31.4 skills
                                        │
                                        └──(reads)── 31.7 synthesis papers
31.9 fixed budgets ──→ honest metrics ──→ 31.10 journal ──→ feeds 29.2.3 A/B + B7 benchmark
31.8 persona — independent, prompt-only
```
