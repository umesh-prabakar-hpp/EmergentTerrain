# Phase 32 — items 32.1–32.6: the verified-manuscript loop

The loop in one line: **construct** every prose-citable value with provenance (32.1), **constrain**
narration to the manifest (32.2), let an investigation **re-register** instead of publishing its own
mis-specification (32.3), score the result against an **absolutely-calibrated** reviewer head (32.4),
and **situate** promotion-track claims against literature (32.5) — with a handful of cheap
writeup-stage guards alongside (32.6).

House rules apply throughout: every item flag-gated OFF; pipeline-wide wording domain-neutral;
inform-don't-suppress (an untraceable number or a literature contradiction *feeds back* to revision,
it never silently deletes content).

---

## 32.1 — Verified-values manifest  *(the substrate)*

**Pattern (data-to-paper).** Every computed value is born with provenance (`ReferencedValue` +
hypertarget label) and flows to the writer as a labeled, format-locked token — an untraceable
number is a construction error, not a post-hoc regex catch.

**ET shape.** ET already has call-level provenance (`[call_N]` markers validated against the
trace). 32.1 adds the value level: when `MathToolSuite` / `SidecarResultComposer` render a tool
result, they *also* emit structured manifest entries:

```
ManifestEntry { label,            // m_<call>_<seq>, stable within a draft
                value,            // canonical double
                rendering,        // the exact string the tool printed (sign, precision)
                producedBy,       // call_N
                kind }            // MARGIN | SCALAR | COUNT | BOUND | STAT | CI | ...
```

The manifest is assembled per-draft beside the tool-call trace (same lifecycle, persisted with the
paper as a `## Verified Values` appendix or sidecar JSON). Capture is **write-only** in this item —
nothing reads it yet, so the flag (`manifest.capture.enabled`) is risk-free to flip early.
Derived-value entries (k·x, x±y over same-call values — the patterns
`NumericalConsistencyValidator` already recognizes) are materialized into the manifest at capture
time, so the derivation knowledge lives in one place.

**Deliberately rejected from the source:** the `PValue` operator-override machinery (Python-exec
interception) — ET's values are composed in Java from structured tool results; there is no
foreign code to intercept.

**Acceptance bar.** One full session with capture ON: every value the existing validators trace
successfully also appears in the manifest (zero recall regressions, measured by replaying the
validator ladder against manifest lookup in shadow); manifest size ≤ a few KB/paper.

## 32.2 — Manifest-constrained narration + validator consolidation  *(the read path)*

**Pattern (data-to-paper).** The writer sees the labeled values in its context and must hyperlink
every number; violations are detected by label↔target matching with rounding tolerance, a small
trivial-number allowlist, percent (100×) ambiguity handling, and a `<`-bound exemption; the honest
escape hatch is marking a value `[unknown]`.

**ET shape, two stages:**

- **Shadow (`manifest.validator-mode: shadow`).** A `ManifestTraceabilityValidator` resolves every
  prose number against the manifest (tolerance rules ported from `check_numeric_values.py`) and
  *stamps only* (`publish:manifest_miss:warn`). Run it beside the existing ladder for ≥2 sessions
  and diff verdicts. The four tolerance rules generalize four FP classes ET fixed by hand
  (derived arithmetic, CI constants, value bands, unit interval) — the diff quantifies that claim.
- **Enforce.** Narration prompts gain the manifest as the **single citable source** ("every number
  in Findings/Conclusion must be a manifest value or be explicitly derived from named manifest
  values in the same sentence"), and the ladder's number-tracing validators
  (`NumericalConsistencyValidator`'s matching core, `UnsourcedClaimValidator`'s numeric arm)
  delegate to manifest lookup. The validators' *routing/stamping* shells stay; only the
  number-matching brains consolidate. Negation/meta and absence sentences keep the 2026-06-06
  exemption — a sentence with no digits never consults the manifest.

**Acceptance bar (shadow→enforce flip).** Shadow agreement ≥95% with the existing ladder on
papers the ladder judges correctly, AND at least one manifest-miss catch the ladder missed or
mis-classed, AND zero new FP classes over two sessions. Enforce keeps the per-paper acceptance
rate within noise of the trailing baseline.

## 32.3 — REFINE / PIVOT with fresh pre-registration  *(investigation control flow)*

**Pattern (data-to-paper + AI-Scientist-v2).** Hypothesis locked before analysis; a validity or
novelty failure loops back to re-goal, bounded by `max_goal_refinement_iterations`; v2 represents
the investigation as a node tree where buggy nodes spawn *debug* children and good nodes spawn
*improve* children, with persistent-error patterns surfaced to the next decision.

**ET shape.** Today an investigation that discovers mis-specification (P-b72c-210's wrong-LHS
tautology, P-b72c-215's never-tested pair, P-b72c-217 caught live by the new LHS gate) can only
publish-and-be-gated. 32.3 inserts one decision point after the professor stage, before narration:

```
classify trace:  ON_TARGET        → proceed (today's path, unchanged)
                 MIS_SPECIFIED    → PIVOT: re-register amended hypothesis (fresh prereg, old one
                                    recorded as RETIRED_MISSPECIFIED), re-run professor once
                 REFUTED_AS_STATED→ REFINE: optional single re-registration at amended k/c
                                    (the P-211 sensitivity-analysis numbers make this mechanical)
```

Bounds: `pivot.max-refinements` (default 1 — one re-registration per paper, mirroring
data-to-paper's small cap), pivot consumes the draft's existing budget (no new LLM call class:
the re-registration prompt replaces the would-be revision round). The MIS_SPECIFIED classifier is
deterministic where possible (`ScoreCalibration.hypothesisLhsUncovered` is exactly the trigger;
the gate stops being the last line and becomes the pivot signal). Every pivot is stamped
(`prereg:pivoted:from=<old-topic>`) so HARKing is auditable by construction — the old
registration is retired *in the record*, never edited.

**Acceptance bar.** Over two sessions with the flag ON: the LHS-coverage gate's firing rate on
*published* papers drops to zero (pivots absorb them upstream); at least one pivoted paper
publishes clean; no paper exceeds the refinement cap; mean calls/paper rises by <15%.

## 32.4 — Calibrated absolute review head  *(the anchor)*

**Pattern (AI-Scientist).** A reviewer with an absolute decision head, made trustworthy not by
prompt-craft but by a **calibration harness**: grade N papers with known human verdicts, compute
accuracy/F1/AUC/FPR/FNR, sweep reflections / few-shot / ensemble / temperature until the metrics
stabilize, and only then deploy.

**ET shape.** The labeled set is ET's own review history: every `/paper-review` session assigns
each paper Acceptable / Minor revision / Major revision / Reject with key-issue notes. Two parts:

- **Benchmark substrate (ops, no runtime flag — the ARC §B7 slot).** Persist per-paper operator
  verdicts durably (CSV/DB beside the trajectory table). **Hard dependency on 31.6** restart
  survivability — today these verdicts die with the room reset. Backfill from
  `research/archive/*/REVIEW_REPORT.md` (the archived room snapshots retain prior reports).
  Target ≥100 labeled papers; the 2026-06 corpus already exceeds this across archives.
- **Review head (`review-anchor.enabled`).** A reviewer prompt with ET's own rubric (the
  `/paper-review` rubric, which is already written down and battle-tested) producing a 4-class
  verdict + binary accept; calibration harness sweeps ensemble∈{1,3}, reflections∈{0,1},
  few-shot∈{0,2} against the labeled set. Deploy threshold: binary accuracy ≥80% and
  Reject-recall ≥90% (missing a Reject is the expensive error). Runs once per paper after the
  panel, stamps `anchor:<verdict>:<p>`; **advisory-only at first** (no routing) — it becomes the
  29.2.1 did-it-help judge and a publish-gate input only after a second calibration pass on
  post-deployment labels.

**Acceptance bar.** Calibration metrics above threshold on a held-out 20% split; advisory stamps
agree with the *next* live `/paper-review` session's operator verdicts on ≥75% of papers.

## 32.5 — Literature-grounding sidecar  *(un-defers 30.4)*

**Pattern (PaperQA2).** Evidence-scored retrieval over a local corpus; answers constrained to
retrieved citation keys; "I cannot answer" honesty; a contradiction-classification prompt variant;
fully offline (Tantivy BM25 + local embeddings + in-memory vector store).

**ET shape.** A third sidecar endpoint set (extends the existing python-sidecar on 8089, same
start-script + env-gate conventions):

- `POST /ground/claim` → `{claim, topK}` → `{verdict: SUPPORTED|CONTRADICTED|SILENT,
  evidence: [{key, snippet, score, source}], confidence}` — deterministic fake-agent shape
  (search → gather → classify via the contracrow trichotomy), no LLM-driven tool selection in v1.
- Corpus: a local directory of curated number-theory references (papers, OEIS entry dumps,
  textbook excerpts) — built once, enriched offline via the metadata cascade; **no external API
  on the request path** (the request path must work when the room runs overnight).
- Java side: `search_literature` gains a grounding mode behind `literature.grounding.enabled`;
  the *caller* policy is unchanged-by-default — only **promotion-track papers** (30.6
  `ANOMALY_PROMOTION` kind) and recognition hits invoke grounding, keeping per-paper cost flat
  for the ordinary corpus. SILENT keeps today's honest "no references found — make no
  author-year citations" behavior; SUPPORTED/CONTRADICTED inject the evidence keys with
  PaperQA2's citation-key fidelity rules (cite only provided keys).

**Deferral discipline.** 30.4's gating logic stands: this item *builds the engine dormant* and
flips only on the standing trigger — the first promotion paper (or first recognition hit that
survives the plausibility guard). The 23.8 lesson ("generation is the blocker") is respected:
nothing here spends until generation produces a claim worth grounding.

**⚠ 2026-07-04 REFRAME — the deferral rationale is overturned; 32.5 is now the binding novelty
lever (run-16 evidence).** The G5 flagship measurement landed end-to-end (P-b72c-77627758:
dirichlet_error flat O(1) envelope across 1e5→1e9 via the √x-span ladder, plus a genuinely new
positive-bias observation) with openness, claim frame, reach, and honesty ALL delivered — and
scored panel novelty **0.26** vs the ≥0.7 bar. The panel's stated reason: *"the literature search
returned no references, so novelty claims are weak."* Every paper in every recent batch logs
`search_literature: no references found` (the corpus-less stub) — so the panel cannot credit
novelty without prior-work contrast and the narrator cannot situate against
Hardy–Littlewood/van der Corput/Huxley. Generation is no longer the blocker; grounding is.
Two deliberate deviations from the design above follow:

1. **Caller policy widened**: promotion-track **plus measurement-frame / priority-target
   conjecture papers** (the flagship class, ~3–4 papers/run — still bounded; ordinary-corpus
   per-paper cost stays flat at zero). Promotion-track-only would leave the papers that actually
   hit the novelty ceiling ungrounded.
2. **Flip-trigger recommendation**: replace "first promotion paper" with "**the 20-claim probe set
   passes**" (operator decision — everything still ships flag-OFF, so the flip stays a one-line
   YAML edit either way).

**32.5b — panel novelty grounding (NEW sub-item; the actual ceiling mechanism).** Give the panel's
novelty assessment the same `/ground/claim` verdict for the paper's core claim, separate flag
(`app.research.literature.panel-novelty-grounding.enabled`), domain-neutral prompt wording. This
inverts the failure mode: today "no references found" reads as *cannot-assess → low novelty*; with
a real curated corpus, **SILENT becomes positive evidence of novelty** ("no match in a curated NT
corpus"), SUPPORTED becomes an honest rediscovery cap with a citation, CONTRADICTED a flag. Without
32.5b, grounding only helps the narrator cite; it does not move the score the G5 bar is measured on.

**Build sequence (agreed 2026-07-04; ~2–3 focused days + trickle corpus + two 10-min operator
actions):**
0. *Operator*: free OpenAlex API key → `~/.emergentterrain/secrets.enc`; pick daily credit ceiling
   (default 0.50, half the free tier).
1. OpenAlex JVM client + credit ledger + JSONL cache (addendum A; dormant substrate).
2. Offline corpus build (addendum B) — **seeded from the `ScalarOpenness`/`MEASUREMENT_FRAMES`
   object list** (Dirichlet divisor, Cramér, 2C₂, Erdős–Kac, Mertens, 2γ, Wigert, Deléglise
   abundant density, 6/π² squarefree…), not just the generic NT-subfield pull; ~1e5 abstracts
   (≈$0.10) + 300–500 OA full-texts (TEI), free-tier trickle or ~$5 one-shot.
3. py-sidecar `POST /ground/claim` — **v1 BM25-only** (the box has ~7.6 GB with app+sidecars up and
   the py-sidecar is single-threaded; local embeddings become v2 only if BM25 recall fails the
   probe set). Index loads at sidecar startup (stop-before-recompile rules apply).
4. Java caller: `search_literature` grounding mode behind `literature.grounding.enabled`, widened
   caller policy per deviation 1; the 32.6 cite-only-provided-keys rule rides along.
5. 32.5b panel novelty grounding (own flag).
6. Probe set → flip decision (deviation 2).
7. Live acceptance (below).
Addendum items C (citation resolver) and D (semantic probe) stay deferred — they are
anti-fabrication/measurement tooling, not the novelty unlock.

**Acceptance bar.** Engine: on a 20-claim hand-labeled probe set (10 known results — 2γ, 0.77316,
6/π², 0.2476, 2C₂, Cramér, Wigert, EK…; 5 contradictions of known results; 5 genuinely open,
including P-758's flat-envelope and positive-bias claims), verdict accuracy ≥80% with zero
fabricated-citation incidents. Pipeline (first run after the flip): a measurement-frame paper
carries a grounding verdict with at least one resolvable citation key or an honest corpus-backed
SILENT; the narrator cites classical results from provided keys only; panel novelty on a
genuinely-new measurement clears ~0.5; zero network on the request path.

## 32.6 — Writeup-stage small guards  *(cheap, mostly prompt-side)*

Three v1 patterns adopted as narration-side refinements, each small enough to ride along:

1. **API-results-only citation rule** (`perform_writeup.py:237`): when 32.5 evidence is injected,
   the narration prompt carries the hard rule — cite only the provided keys, never invent an
   author-year. (ET's citation validator already catches inventions post-hoc; this is the
   construction-side twin.)
2. **Per-section tips + two-pass refinement** (`perform_writeup.py:130-217`): ET's section
   contracts already encode most tips; the adoptable delta is the *second refinement pass*
   prompt shape ("tighten, dedup, no new claims") as an optional final revision-round variant.
3. **Reference-section position check** (v2 `perform_icbinb_writeup.py:121-183`): trivially
   adapted to ET's markdown contract — citations may not appear after Related Work. Validator
   one-liner.

**Acceptance bar.** No measurable acceptance-rate change required — these ship as
quality-of-prose refinements; the bar is simply zero new gate firings attributable to them over
one session.

---

## Cost & risk

| Item | New LLM calls/paper | Risk | Mitigation |
|---|---|---|---|
| 32.1 capture | 0 | near-zero (write-only) | flag |
| 32.2 shadow | 0 | zero (stamp-only) | shadow mode |
| 32.2 enforce | 0 | prompt-regression on narration | A/B vs trailing acceptance rate; rollback = mode flip |
| 32.3 pivot | ≤1 professor re-run per pivoted paper | budget growth | refinement cap 1; drain-aware (pivot is discretionary spend) |
| 32.4 anchor | 1–3 (ensemble) per paper | mis-calibrated anchor misleads | advisory-only until second calibration; Reject-recall threshold |
| 32.5 grounding | 1–2 per *promotion-track* paper only | corpus quality; sidecar load | offline corpus curated once; deterministic agent; env-gated like ET_DISCOVER_EXEC |
| 32.6 | 0–1 (optional second pass) | minimal | per-guard flags |

The dominant risk is 32.2-enforce regressing narration quality; the shadow stage exists to make
that flip evidence-based, exactly like the W4 shadow A/B that preceded the stochastic-null flip.
