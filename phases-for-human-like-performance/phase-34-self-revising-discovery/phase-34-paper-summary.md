# Paper summary — *Self-Revising Discovery Systems for Science: A Categorical Framework for Agentic Artificial Intelligence*

**Authors:** Fiona Y. Wang, Markus J. Buehler (MIT Laboratory for Atomistic and Molecular
Mechanics — the same LAMM lineage behind ScienceClaw / SciAgents / ProtAgents / Sparks that the
phase-30 external-pattern reviews already drew on). **arXiv:** 2606.01444v1, cs.AI, 31 May 2026.
PDF in this folder: [`2606.01444v1.pdf`](2606.01444v1.pdf).

Companion: [`phase-34-patterns.md`](phase-34-patterns.md) (what ET can adopt + scoping).

---

## One-paragraph thesis

Scientific discovery is not just answer generation; it is **revision of the representational
regime** in which evidence, artifacts, operations, and verifiers are typed. The paper gives a
category-theoretic account that cleanly separates three operations that agentic systems routinely
conflate — **retrieval** (add an already-representable artifact), **search** (find a new path or
object *inside a fixed vocabulary*), and **discovery** (change the vocabulary itself: new types,
operations, verifiers, or tools). It then shows two working systems instantiating the framework: a
protein-mechanics **Builder/Breaker** system gated by Minimum Description Length, and
**CategoryScienceClaw**, a proof-carrying categorical layer over the ScienceClaw multi-agent
substrate.

The headline mathematical objects: a fixed-regime state is a **copresheaf** `Iₜ : 𝒮_b → Set`
(each artifact *type* maps to the set of artifacts of that type); realized **provenance** is the
**category of elements** `∫ Iₜ` (the typed provenance DAG); a fixed-regime update is an
**endofunctor** `Φ_b` that must preserve provenance/refinements; and **discovery** is a **verified
regime transition** `u : 𝒮_b → 𝒮_b'` whose old artifacts are carried forward by **left Kan
extension** `Lan_u Iₜ`, with a comparison map `ρ̄` whose **residual** (`I'_{t+1} \ im(ρ̄)`) is
exactly the content that transport alone cannot supply. This separates retrieval, search, and
discovery *without appealing to subjective novelty*.

---

## The core distinction (Figure 1, the spine of the paper)

| Operation | What it does | Vocabulary | Categorical form |
|---|---|---|---|
| **Retrieval** | adds an artifact already expressible in the schema | same | new element in an existing fiber `Iₜ(A)` |
| **Search** | finds a new path/object inside the fixed schema | same | new path inside `∫ Iₜ` (iteration of `Φ_b`) |
| **Discovery** | changes the types/operations under which artifacts are judged | **new** | verified regime transition `u : 𝒮_b → 𝒮_b'` + residual |

The central claim: scaling a system that is fluent at *search* (recombine/optimize inside a fixed
scientific vocabulary) is **qualitatively different** from building one that can *construct new
representational commitments*. Most current "AI scientists" are extraordinarily good at search and
almost never do discovery in the strong sense.

---

## Formal skeleton (Materials & Methods, Defs 1–6, Props 4–8)

- **Def 1 — discovery regime** `b = (𝒮_b, Γ_b, 𝒱_b, L_b)`: schema category of artifact *types* +
  allowed *operations* (`𝒮_b`), a grammar `Γ_b` for composing artifacts, a verifier/gate `𝒱_b`,
  and an optional description-length / model-selection functional `L_b`.
- **Def 2 — artifact-state copresheaf** `Iₜ : 𝒮_b → Set`. Fiber `Iₜ(A)` = artifacts of type `A`
  currently held; for operation `f : A → B`, `Iₜ(f)` records how a type-`A` artifact gives rise to
  a type-`B` one.
- **Def 3 — category of elements** `∫_{𝒮_b} Iₜ`: objects are pairs `(A, x)`, morphisms are realized
  operations. "Not a metaphor for provenance — it *is* the typed provenance graph."
- **Def 4 — fixed-regime update** `Φ_b`: an endofunctor on knowledge states `𝒦_b` (the subcategory
  of copresheaves with **componentwise-injective** refinement morphisms). The injectivity is the
  formal version of a **practical audit rule**: a refinement may add, annotate, or supersede
  artifacts, **but may not silently identify two previously distinct accepted artifacts**.
- **Def 6 — verified regime transition**: a schema functor `u : 𝒮_b → 𝒮_b'` + preservation natural
  transformation `ρ`, satisfying four compatibility conditions: (1) `u` faithful on morphisms /
  injective on objects *except* where a type split/quotient/recoding is explicitly recorded;
  (2) `ρ` componentwise-injective and provenance-preserving (old artifacts stay inspectable);
  (3) old commitments stay accepted after transport (`𝒱_b(Iₜ)=1 ⟹ 𝒱_b'(im(ρ̄))=1`); (4) the new
  state is gate-accepted in its own regime. Transition is **nontrivial** when `𝒮_b'` contains a new
  object/morphism/verifier/grammar-production/tool not generated inside `u(𝒮_b)`.
- **Kan transport (Eq. 9):** `(Lan_u Iₜ)(A') ≅ colim Iₜ(A)` over old artifacts mapping toward `A'`.
- **The Kan obstruction (Prop 4) — the load-bearing result.** If the comma category `(u ↓ A')` is
  empty (an isolated new type receives no morphisms from old types), the colimit is the initial
  object `∅`: **transport supplies nothing there**. Any artifact accepted at such a type is *forced
  residual content* — empirical/representational content the system had to acquire beyond
  reinterpreting old evidence. This is the precise, non-subjective signature of discovery.
- **Discovery cost (Prop 4.3):** when a relative description-length functional is present, the cost
  of a move is `L_b'(I'_{t+1} | im(ρ̄))` — the bits to describe the post-transition state *given*
  transported evidence. Nonempty forced residuals give a strictly positive discovery cost.
- **Props 5–6:** refinements lift to a faithful functor on provenance (the audit-ledger rule);
  verified transitions **compose** (a multi-stage investigation is one audited discovery move when
  each intermediate transition records a preservation map and clears its gate).
- **Prop 8 — multicategorical obstruction:** the same statement for colored-operadic schemas with
  multi-input operations `φ : (A₁,…,A_k) → B`; a target reachable only via a *newly admitted k-ary
  multimorphism* is residual schema content.

Five recurring components every system in the paper shares: **(1)** a schema of artifact types +
operations, **(2)** a population of artifacts over it, **(3)** a provenance graph, **(4)** a
gate/verifier (MDL, pressure scoring, peer review, …), **(5)** a regime-update mechanism for when
evidence can't be represented in the current schema.

---

## Case study 1 — Builder/Breaker (quantitative MDL discovery)

A protein-mechanics symbolic world model revised under an MDL gate
(code: `github.com/lamm-mit/BreakingTheWorld`; LLM backbone GPT-5.5, `reasoning_effort=high`).

- **Roles:** the **Breaker** selects/generates protein evidence designed to *expose failure modes*
  of the current symbolic model (adversarial stress test); the **Builder** proposes symbolic-DAG
  edits (add/remove/swap features, adjust thresholds).
- **Gate (Eq. 5/8):** total description length `L(M,D) = L_model(M) + L_data(D|M)`. A revision `M'`
  is committed only when `L(M', D∪E) < L(M, D∪E)` **after both models are refit on the same
  accumulated evidence**. Acceptance is therefore *not* monotone improvement of a single predictive
  score.
- **The discovered law (Eq. 7):** within-chain crystallographic B-factor pattern ∼ **all-mode
  elastic compliance × slow collective-mode participation** — `B̂ = α + β·φ·ψ` with `φ` = z-scored
  log-compliance and `ψ` = ReLU-thresholded slowest-mode participation, fitted `α=−0.1332,
  β=0.2239, θ=2.2678`. A *mode-conditioned compliance* constitutive surrogate, not a fitted curve.
- **The categorical content (Fig 4 / Table 2):** a two-level Kan audit classifies each new type as
  **generator-reachable** (immediate unary morphism from an old type — e.g. LogNormCompliance,
  ReLUModeAmpl) vs **composite-reachable** (only via a *newly admitted* multi-input operation — e.g.
  `LogNormCompliance × ReLUModeAmpl → ModeConditionedCompliance`) vs **isolated**. The genuine
  discovery is **not** the appearance of a normal mode (available from the start); it is the
  **transition at which the schema admits the new product/interaction type** — accepted with a
  54.3-bit MDL gain.
- **The R² lesson (load-bearing for ET):** the descriptive-fit `R²` trajectory is **non-monotone**:
  `0.48 → 0.68 → 0.54 → 0.41`. This is *not* a failed optimization — the four values are fits on
  *successively widened and more adversarial* evidence sets. Monotone `R²` would be the **wrong**
  success criterion (it rewards adding terms to chase heterogeneous data). The MDL gate instead
  asks whether a revised structure survives **paired comparison on the same enlarged evidence after
  paying its model-code cost**. Signed `ΔL_model` shows the later accepted transitions actually
  *reduce* model code length (−14.4, −10.3 bits) while yielding the largest MDL gains: **discovery
  includes retraction and compression of vocabulary, not just accumulation.**
- **Gate selectivity (Figs 6–7):** across the run only **25 of 388 proposals (6.4%)** are accepted;
  acceptance is strongly operator-dependent (seeds 21%, swaps 11%, bare feature-adds 3%); feature
  **removals** are a high-yield operator. The gate "is not decorative."

## Case study 2 — CategoryScienceClaw (proof-carrying categorical layer)

A categorical/audit layer lifting the ScienceClaw × Infinite substrate (typed skills, immutable
artifacts with content hashes + parent lineage, open needs, planner-less coordination, pressure
scoring, workflow mutation, public-discourse posts/votes/reputation) into typed objects and
morphisms: skills → morphism signatures, artifacts → typed objects, open needs → typed
holes/lifting problems, gates/stress-tests → predicates, public discourse → a discourse category
with a **publication map** `πₜ` carrying audited artifact paths into public claim objects.

- **Worked example — fiber-network mechanics:** the system decides whether network mechanics is
  better described by a scalar **fiber-count descriptor** (`M₀`) or an **orientation-tensor
  anisotropic-stiffness surrogate** (`M₁`). Rule: `accept M₁ over M₀ ⟺ AIC(M₀) − AIC(M₁) > 0`.
  `M₁` accepted, `ΔAIC = 123.87`. Crucially, **the rejected `M₀` remains a first-class typed
  provenance object** — "model comparison itself becomes provenance." The objectwise residual
  diagnostic `R(A') = I'_{t+1}(A') \ im(ρ̄_{A'})` records exactly the orientation tensor /
  stress-test content that was *not* obtainable by transporting the old fiber inputs.
- SI adds three more audited cases (7T10 structure-contact tensile mechanics; mechanobiology
  force-paths; membrane curvature-energy), each with the same skeleton: candidate set, accepted
  model, **retained rejected alternative**, gate, stress test, regime-transition claim, figure.

---

## Open problems the paper poses (§2.10)

1. **Convergence on growing regimes** — when does the sequence of transported states converge vs
   stay in productive (or unproductive) exploration?
2. **Scaling laws for discovery** — the right observable is the *rate/quality/accepted-value of
   regime enlargements*, not loss inside a fixed regime.
3. **Verification tooling for agentic loops** — replay downstream artifact chains, check approximate
   naturality, account for description length, record retractions without deleting provenance.
4. **Learning the base schema `𝒮_b`** — currently an engineering choice; learn it (and `L_b`) from
   working scientific corpora.
5. **Multicategorical discovery** — learn the typed multicategory / operadic schema from traces and
   estimate the artifact + operation components of discovery cost at scale.

---

## Why this paper is directly relevant to EmergentTerrain

It is the **same research lineage** ET's phase-30/31/32 external reviews already mined (Buehler
LAMM; ScienceClaw, SciAgents, ProtAgents, Sparks). But where those reviews supplied *engineering
patterns*, this paper supplies a **formal vocabulary and non-subjective criteria** for things ET
has been deciding by heuristic: what counts as genuinely novel (regime transition vs search), how
to audit a new DERIVED scalar (generator- vs composite-reachable), why a non-monotone fit metric is
the right behavior under widening evidence (ET's anti-stagnation / stochastic-null work), and how
to keep rejected alternatives and old commitments as inspectable provenance across revisions (ET's
room-reset + provenance-unification problem). The patterns and a flag-gated scoping are in
[`phase-34-patterns.md`](phase-34-patterns.md).
