# Phase 32 — Manuscript Grounding & Calibrated Evaluation (patterns from the paper-pipeline repo review)

**Status: PARTIALLY DELIVERED; original proposal still has open lanes (reconciled 2026-09-15).**
The original 2026-06-06 review is retained below. Since then, later phases have delivered the
literature and publication-grounding portions: Phase 34 supplies publication labels and grounded
artifact gates, Phase 35 supplies local FTS retrieval/recovery, and Phase 37 adds the screened
literature index. The original value-level provenance manifest, bounded re-goal loop, and
calibrated absolute-review head remain open design/implementation lanes; do not describe the
whole Phase 32 proposal as either wholly unbuilt or wholly complete. This is the fifth
external-pattern review, after the
self-improving-frameworks addendum
([`../phase-30-conjecture/phase-30-external-patterns.md`](../phase-30-conjecture/phase-30-external-patterns.md)),
the simulation-frameworks addendum
([`../phase-30-conjecture/phase-30-simulation-patterns.md`](../phase-30-conjecture/phase-30-simulation-patterns.md)),
the AutoResearchClaw review
([`../phase-30-conjecture/phase-30-arc-patterns.md`](../phase-30-conjecture/phase-30-arc-patterns.md)),
and the memory/deep-research review
([`../phase-31-research-memory/`](../phase-31-research-memory/)).
Every adopted item ships flag-gated **OFF** (`@Value … = false`) per house convention, flipped only
after its acceptance bar.

**Sources reviewed (2026-06-06)** — two retained reference-only clones under
`/opt/apps/ide/vscode/ai/reference-from-github/`:

| Repo | One-line mechanism | Layer it contributes |
|---|---|---|
| `data-to-paper` (Technion-Kishony-lab) | every number in the manuscript wrapped in a provenance hyperlink traceable through the analysis chain to raw data; constrained value types (`PValue`); hypothesis locked before analysis with a bounded re-goal loop | number **traceability** |
| `paper-qa` (Future-House, PaperQA2) | citation-faithful RAG over scientific papers: per-chunk relevance-scored evidence, answers constrained to retrieved citation keys, "I cannot answer" honesty path, contradiction-detection prompt variant, fully-offline mode (Tantivy + local embeddings) | literature **grounding** |

Per-repo adopted/covered/rejected dispositions: [`phase-32-repo-survey.md`](phase-32-repo-survey.md).
Item detail: [`phase-32-manuscript-grounding.md`](phase-32-manuscript-grounding.md).
Sequencing + flags: [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md).

---

## The thesis (what the previous reviews did not cover)

Phase 30 hardened **claim generation and verification** (gates, nulls, holdouts, promotion).
Phase 31 (proposal) covers **cross-run knowledge**. The layer neither touches is the
**manuscript itself as a verified artifact**. Four structural gaps, each with a named
production-grade pattern in the clones:

1. **Numbers in prose are validated post-hoc, not constructed traceable.** ET's defense is a
   ladder of validators (`NumericalConsistencyValidator`, `UnsourcedClaimValidator`,
   binding-regime, single-point-sweep, …) that grep narrated prose for numbers and try to match
   them back to tool output — and the ladder has needed **nine** FP/TP-class fixes in one week
   (derived arithmetic, value bands, CI constants, executed-range union, unit interval,
   negation-context, …). `data-to-paper` inverts the construction: every computed value carries
   provenance from birth (`ReferencedValue` + LaTeX hypertarget), the writer may only cite
   provenance-carrying values, and an untraceable number is a *type error*, not a regex miss.
   ET already has the call-level analog (`[call_N]` markers); the missing half is value-level.

2. **An investigation that discovers its hypothesis is mis-specified has no pivot.** The
   2026-06-06 review found P-b72c-210 (every call on the wrong LHS → tautology), P-b72c-215
   (preregistered pair never invoked, published a self-described "Critical Gap"), and the new
   hypothesis-LHS gate fired live on P-b72c-217 the same morning. The gate routes these to
   revision — but the *generative* fix is `data-to-paper`'s bounded re-goal loop (novelty/validity
   failure → return to GOAL stage, max N refinements, hypothesis re-locked). ARC §B3 supplies the
   complementary investigation-control-flow shape.

3. **Review scores are relative, with no absolute anchor.** The panel's axis medians rank papers
   against each other; nothing answers "would a competent human reviewer accept this?"
   The calibration method is to grade against a labeled benchmark of real human decisions and
   trust the absolute head only after agreement metrics stabilize. ET's labeled set already
   exists — every `/paper-review` session produces per-paper operator verdicts
   (Acceptable / Minor / Major / Reject); they are currently **discarded on room reset**
   (Phase 31's 31.6 makes them durable — explicit cross-dependency).

4. **Literature grounding is a stub.** `search_literature` queries a tiny local corpus and almost
   always returns "no references found. Make no author-year citations." — so no claim is ever
   situated against human knowledge, and 30.4 sits deferred behind the 23.8 calibration. PaperQA2
   is the engine pattern: evidence-scored retrieval, answers constrained to retrieved citation
   keys, an honest cannot-answer path, a contradiction-classification mode, and a fully-offline
   deployment shape that fits ET's existing python-sidecar convention (ports 8088/8089).

**Live motivating evidence (2026-06-06 morning, same session as this proposal):** P-b72c-217
carried both failure classes at once — the LHS-coverage gate caught its off-target trace, and the
constant-recognition PSLQ leg injected a spurious 8-term integer relation with a **zero target
coefficient** (`(...)/0`) into its narration prompt. Post-hoc gates caught the first; nothing
constructs the second out of existence. That is this phase's thesis in one paper.

---

## Relationship to pending work

| Pending item | How phase 32 treats it |
|---|---|
| ARC §B2 verified-metrics manifest | **becomes 32.1/32.2** (the data-to-paper treatment is strictly stronger than the ARC sketch) |
| ARC §B3 refine/pivot + fresh pre-registration | **becomes 32.3** (control-flow shape from data-to-paper re-goal) |
| ARC §B7 graded paper benchmark | **becomes the calibration set inside 32.4** |
| 30.4 literature grounding (deferred) | **engine selected in 32.5**; deferral trigger restated (first promotion paper) |
| 29.2.1 rollback ratchet (did-it-help judge) | untouched here; 32.4's absolute head is the natural judge metric once calibrated |
| 31.6 provenance unification | **hard dependency of 32.4** (operator verdicts must survive restarts to form the calibration set) |
| Figure agent (ARC §B1) | out of scope until ET papers have figures |
