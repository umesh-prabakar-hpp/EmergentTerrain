# Phase Wishlist — Human-Level Paper-Building Levers (reference, not queued)

**Status:** **Wishlist document.** Not a queued PR, not scheduled work. This
file captures high-leverage interventions that move the system from
"fewer fabrications" (Phase 12's defensive scope) to "actually better
content" — papers that read as genuine, human-level research artifacts.
Each piece is sized and pointed at concrete files so future planning can
pull from this catalog directly, but the order of execution and the
decision to start at all is deliberately deferred.

**Scope:** P1–P4 only — the original four high-leverage levers (~3w, ~2w,
~1.5w, ~2w respectively). Each one upgrades what a paper *can do* (more
tools, lower sandbox friction, structured reviewer signals, derived
cross-domain couplings) rather than patching a specific failure mode.

**File-rename + scope-narrowing note (2026-05-18):** Renamed from
`phase-wishlist.md` to `phase-wishlist-human-level.md`. Sections P5–P8
were trimmed out because they were tactical, REVIEW_REPORT-driven items
(narrator scrubs, corpus-aware narration, topic-clone backoff, Phase 17
diagnostics) rather than human-level paper-building work. Most of P5–P8
shipped between 2026-05-17 and 2026-05-18; the still-pending P8c + P8d
items moved to `phase-18-pending-followups.md`. The migration table at
the bottom of this doc records where each former P5–P8 item now lives.

**Why this is wishlist, not queue:**

- Each item below has at least one open architecture question that needs
  operator input (model choice, sandbox runtime swap, schema-pin vendor
  compatibility, dimensional-analysis library choice). Resolving those is
  cheaper than writing the work backwards from a guess.
- Phase 12 is still rolling out. Acceptance rate response from D1–D6 needs
  one to two REVIEW_REPORT refreshes to confirm the projected 8.8% → ~15%
  lift before doubling down on infrastructure.
- The four pieces are mostly independent; any one of them could be the
  start of the next implementation phase depending on which signal Phase
  12's rollout surfaces most clearly. Locking sequencing now would predict
  the wrong starting point.

**Read this document when:**

- Phase 12 has stabilised and the operator wants to plan the next leverage
  move.
- A REVIEW_REPORT refresh names a specific failure mode that maps to one
  of the four items below.
- An external constraint (model deprecation, vendor schema change, GPU
  capacity shift) makes one of the items cheaper than it is today.

---

## The four pieces (one-line summaries)

1. **Expanded tool surface** — `MathToolSuite` ships 5 tools today. Adding
   numerical integration, ODE solver, curve-fitting, dimensional units,
   and a CODATA reference-value lookup would let the LLM compute things it
   currently fakes in prose. ~3 weeks.
2. **Reduced sandbox friction** — 28 grammar rejections per REVIEW_REPORT
   refresh (≥5 on each of 10 papers). The sandbox grammar restricts
   object literals, `Math.*`, array returns, and `.method()` chains; the
   LLM retries 4–5× with cosmetic edits and gives up. Three layered
   interventions: grammar relaxation, per-failure correction template,
   Python-syntax fallback runtime. ~2 weeks.
3. **Schema-pinned reviewer prompts** — 39 `rev:parse_failed` events per
   refresh; the reviewer model returns prose where JSON was requested; the
   panel degrades to n<4; D4's demotion stamps NEEDS_REVISION rather than
   fixing the parse. Swap reviewers to tool-call schema enforcement so the
   parse can't fail. ~1.5 weeks.
4. **Coupling-equation gates for INTERDISCIPLINARY papers** — all 3
   INTERDISCIPLINARY papers in the May 2026 batch are Rejects with no
   stated physical coupling. Require an explicit coupling equation in the
   Hypothesis, dimensional-check it, and reject papers that assert the
   coupling without deriving it. ~2 weeks.

---

## P1 — Expanded tool surface

**Effort:** ~3 weeks (5 tools × ~3 days each + integration + tests).
**Pre-condition:** none structurally; depends on operator decision about
sandbox runtime if `numerical_integrate` etc. need a separate adapter.

**Files added:**
- `src/main/java/com/emergentterrain/llm/tools/NumericalIntegrator.java` —
  Simpson, Gauss-Legendre, adaptive Romberg over `[a, b]` for a closed-form
  `f(x)`. Returns `{value, estimated_error, method, samples}`.
- `src/main/java/com/emergentterrain/llm/tools/OdeSolver.java` — RK4 and
  implicit-trapezoid for stiff systems. Takes `{f(t, y) expression,
  y0, t_range, method, max_step}`. Returns `{t[], y[], steps_taken}`.
- `src/main/java/com/emergentterrain/llm/tools/CurveFitter.java` —
  Levenberg-Marquardt least-squares fit of a closed-form to a sweep. Takes
  `{form, sweep_pairs, free_params, bounds}`. Returns
  `{fitted_params, residual_std, R²}`.
- `src/main/java/com/emergentterrain/llm/tools/DimensionalArithmetic.java`
  — unit-aware add/multiply/exponent. Takes `{lhs: "5 m/s", op: "*", rhs:
  "2 s"}`. Returns `{value: 10, unit: "m"}`. Throws when units don't
  match for additive ops.
- `src/main/java/com/emergentterrain/llm/tools/ReferenceLookup.java` —
  CODATA / NIST constant table lookup. Takes `{symbol}` or `{phrase}`.
  Returns `{value, unit, uncertainty, source}`. Examples: `σ` →
  `5.670374419e-8 W·m⁻²·K⁻⁴`; `Avogadro` → `6.02214076e23 /mol`.

**Files touched:**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java:117-220`
  (`toolsJson`) — register the new tools' JSON schemas. Plus the executor
  branch in `buildExecutor` to dispatch each.
- `src/main/resources/application.yml` — flag set
  `app.research.tools.{integrator,ode,curve_fit,dimensional,reference}.enabled`,
  each defaulting on for new rooms / off for backfill.

**Architecture:**

```
                  Current toolset (May 2026)
                  ──────────────────────────
  run_param_sweep         — parametric scan over engine topics
  run_predicate_scan      — number-theoretic property scan
  run_sandboxed_code      — arbitrary JS-flavoured pseudo-code
  evaluate_symbolic       — Symja for f(n) over integer range
  run_gpu_eval            — GPU-backed numerical evaluator

                  Wishlist P1 additions
                  ─────────────────────
  numerical_integrate     — closed-form ∫f(x)dx with quadrature
  solve_ode               — RK4 / implicit for {f(t, y), y₀, t}
  fit_curve               — LM least-squares to {form, sweep}
  dimensional_arithmetic  — unit-aware +/*/^ with mismatch errors
  lookup_reference        — CODATA / NIST constant table
```

**Why each tool:**

| Tool | Failure mode it would prevent (corpus evidence) |
|------|------------------------------------------------|
| `numerical_integrate` | Beer-Lambert / Mertens / Wien runs that need ∫f dx fall back to Riemann sums in sandboxed code, hit grammar rejections, and end up with fabricated numerics. |
| `solve_ode` | Lotka-Volterra, Verhulst, RLC transient, and pendulum-large-angle papers all need time evolution; today the LLM hand-codes an Euler scheme in the sandbox and exceeds the iteration cap. |
| `fit_curve` | P-a328-417 (Hall-Petch) and P-426 (Arrhenius) both reported a single point and prose-claimed a "sweep". A real fit tool gives them a sweep they can cite. |
| `dimensional_arithmetic` | P-410 (vdW → composite 11 GPa) and P-432 (Hall sign) both made cross-domain magnitude leaps with no dimensional check. The tool fails LOUD when units don't balance. |
| `lookup_reference` | Wien b, Stefan-Boltzmann σ, Faraday F, Planck h are hardcoded in `MathPhysicsEngine`. When the LLM cites them in prose it has no anchor — the lookup tool makes the citation traceable. |

**Acceptance:**
- Each tool ships with ≥10 unit tests against canonical references
  (Simpson's rule against Gaussian integral; RK4 against Lotka-Volterra
  fixed point; LM fit against `y = a·exp(-bx)` with known noise).
- A scratch run with all five new tools wired to the system prompt
  produces a paper that cites each at least once over a 20-paper batch.
- After three REVIEW_REPORT refreshes, the "sandbox-rejected by safety"
  count drops by at least 30% — the LLM stops trying to roll its own
  integrator / ODE solver in pseudo-code when the dedicated tool exists.

**Risk:**
- Five tools is a substantial prompt-context expansion. Each adds ~250
  tokens to every professor-stage call. At today's pre-debit budget
  pressure (G9 firing 272×/run) this hits the daily-spend wall faster.
- Tool descriptions need to be carefully scoped — overlap with
  `evaluate_symbolic` (which already does Mathematica-style calculus)
  invites the LLM to pick the wrong tool.

---

## P2 — Reduced sandbox friction

**Status (2026-05-18):** **PROMOTED to scoping doc** —
`phase-19-sandbox-friction.md`. Layer 3 (Python fallback) explicitly
deferred there. The detail below is retained as the original wishlist
text; the scoping doc supersedes it for any actual implementation.

**Effort:** ~2 weeks. **Pre-condition:** none.

**The bug:** REVIEW_REPORT refresh #3 §7 reports 28 `REJECTED by safety
review` events per run, concentrated on 10 papers (≥5 rejects each).
Rejected papers map 1:1 to "0 successes, prose fabricated" Reject verdicts.
The recurring grammar mistakes from the corpus:

| Pattern | Why rejected today | Wishlist P2 fix |
|---------|--------------------|-----------------|
| `return {mean: m, std: s}` | Object literal in return | Allow object returns; whitelist as final-expression position. |
| `return [a, b, c]` | Array literal in return | Allow array returns; same position whitelist. |
| `Math.sqrt(x)`, `Math.PI` | `Math.*` static dispatch | Whitelist a sandboxed `Math` namespace (`sqrt, exp, log, sin, cos, PI, E, …`). |
| `arr.length`, `arr.filter(…)` | `.method()` on identifiers | Allow `.length` (zero-arg property) always; allow `.map / .filter / .reduce` over array literals with arity ≤2. |
| `for (let n of arr)` | `let`, `of` not in grammar | Allow `let`; allow `of`-iteration over arrays. |

**Files touched:**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java:507-600`
  (safety-review block) — relax the static-rule rejection set; keep the
  LLM-stage advisory check unchanged.
- `src/main/java/com/emergentterrain/research/SandboxParser.java` (or
  wherever the grammar is; need to verify the actual file) — extend the
  parser allowlist per the table above.
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  — extend the sandbox-tool description with a "currently-supported
  syntax" example block listing the relaxed forms.
- `src/main/resources/application.yml` — flag set
  `app.research.sandbox.relaxed-grammar.enabled` defaulting on for new
  rooms / off for backfill.

**Layer 1 — Grammar relaxation.** Allow the five patterns in the table.
The static review's "REJECTED (static): object literal" verdicts disappear
for legitimate cases. ~3 days.

**Layer 2 — Per-failure correction template.** When the safety review
rejects, attach BOTH the rule violated AND a concrete corrected snippet:

```
REJECTED by safety review: object literal in return.
Rewrite suggestion: instead of `return {mean: m, std: s}`, use
`return mean + "," + std` and parse the result with String.split(",") on
the caller side.
```

Today the rejection is bare — the LLM doesn't know the alternative.

Implementation: per-rule template strings in `SafetyReviewer`. ~2 days.

**Layer 3 — Python-syntax fallback runtime.** When the LLM emits Python-
shaped code (`def`, `import`, `[x for x in …]`), the static review
rejects. But the model's pre-training emphasis on Python is overwhelming;
many emissions are unambiguously Python. Option: detect Python syntax
heuristically and run it on a sandboxed CPython (PyExecJS / Jython / RestrictedPython)
with the same wall-clock cap. ~1 week.

The Python fallback is the riskiest piece — needs a sandbox-hardening
review before shipping. If the static review can't bound resource usage
on Python the same way it can on the JS-flavoured grammar, ship layers
1+2 and skip layer 3.

**Acceptance:**
- After P2, `sandbox_reject` count drops by ≥50% across a 30-paper batch.
- The 10 papers in refresh #3 with ≥5 rejects each (per the §7 table) all
  produce at least one successful tool call when replayed.
- Layer 3 sees ≥3 successful Python-syntax sandboxed runs per refresh, or
  it's deferred entirely (no half-shipped runtime).

**Risk:**
- Allowing `.map / .filter / .reduce` expands the iteration surface. The
  100k iteration cap in `evaluate_symbolic` becomes important — verify
  it propagates to the sandboxed-code path too.
- Python fallback widens the sandbox surface. Treat as opt-in via
  `app.research.sandbox.python-fallback.enabled` (default off) and
  defer the flip until a security review passes.

---

## P3 — Schema-pinned reviewer prompts

**Effort:** ~1.5 weeks (1 week for the tool-call review path; 3 days for
the multi-vendor compatibility shims; tests).
**Pre-condition:** none; runs alongside the existing `ReviewPanelService`
shape.

**The bug:** REVIEW_REPORT refresh #3 §7 reports 39 `rev:parse_failed`
events per run. The reviewer model is asked to emit JSON
(`{grades, citations, omissions, contradictions, recommendations}`) and
returns prose instead; the parse falls back, the panel degrades to n=3 or
n=2; the LLM signs off on whatever is left. Phase 12 D4 demoted these
papers to NEEDS_REVISION, but the underlying parse failure is still 39
events of wasted call-budget per refresh.

**Architecture today:**

```
ReviewPanelService.runPanel
  ├─ pick {panelSize} model handles
  ├─ for each handle, call LlmRouter.callForRole("reviewer", prompt)
  ├─ parse response as JSON
  │      └─ parse fail → drop reviewer, panel degrades
  └─ aggregate surviving reviewers' grades / citations
```

**Wishlist P3 — tool-call review path:**

```
ReviewPanelService.runPanel (P3)
  ├─ pick {panelSize} model handles
  ├─ for each handle, call LlmRouter.callForRoleWithTools("reviewer",
  │      prompt, tools=[submit_review])
  │   └─ LLM must call submit_review(grades, citations, omissions, ...)
  │   └─ provider-side schema validation; parse cannot fail
  ├─ extract tool-call arguments (already structured JSON)
  └─ aggregate
```

**Files added:**
- `src/main/java/com/emergentterrain/llm/tools/SubmitReviewTool.java` —
  the schema definition the reviewer model must call. Defines the four
  required fields (`grades`, `citations`, `omissions`, `contradictions`)
  + the optional `recommendations` array.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ReviewPanelService.java` —
  switch from `callForRole` to a new `callForRoleWithTools` overload.
- `src/main/java/com/emergentterrain/service/LlmRouter.java` — add
  `callForRoleWithTools` mirroring the existing `callPrimaryWithTools`
  shape (Phase 12 D7 already extended this to carry `skippedModels`).
- Each pool's `callWithToolsOnModel` already exists from Phase 11 C1;
  no per-pool change needed if the role-tagged dispatch is layered on top.
- `src/main/resources/application.yml` — flag
  `app.research.review-panel.tool-call-schema.enabled` defaulting OFF for
  rollout, then flipped after one refresh.

**Per-vendor compatibility:**
- **Groq**: native tool calls work cleanly. No adapter needed.
- **OpenRouter**: routes to whatever upstream the chosen model uses; some
  upstreams (e.g. Anthropic) emit `tool_use` blocks rather than
  `tool_calls`. Adapter at `OpenRouterPool.callWithToolsOnModel` normalises.
- **NVIDIA**: hosted models' tool-call support varies per model. The
  `available_models.yml` registry needs a `supports_tool_calls` field;
  models without it fall back to JSON-prose review with a `rev:parse_failed`
  retry loop (P3 layer 2 — see below).
- **Cerebras**: same as NVIDIA — model-by-model.

**Layer 2 — Inline-validator retry loop for non-tool-call models.** When
the reviewer doesn't support tool calls and the JSON parse fails, re-prompt
the same model with:

```
Your previous response could not be parsed as the required JSON shape.
Specifically: {parse_error}.

Please respond with valid JSON matching this schema:
{schema_json}

Here is your previous response for reference, marked with the parse
failure location: {previous_response_with_marker}.
```

Today the parse failure silently drops the reviewer; layer 2 gives one
retry before the drop. ~3 days.

**Acceptance:**
- After P3, `rev:parse_failed` events drop by ≥80% across a 30-paper
  batch (every tool-call-capable reviewer emits structured JSON; the
  20% remainder is non-tool-call models behind layer 2).
- Panel size measured at n=4 in ≥90% of refresh #3 events (today: 70%
  from the n=2/n=3/n=4 distribution in §7).
- Phase 12 D4's panel-degradation-demote rate drops correspondingly —
  fewer papers carry the `panel:n=N:degraded:demoted_to_needs_revision`
  marker because fewer panels degrade in the first place.

**Risk:**
- Tool-call review path is conditional on every reviewer model supporting
  tool calls. Today some NVIDIA and OpenRouter models don't. The fallback
  to JSON-prose with retry is mandatory; without it, P3 makes things
  worse for those vendors.
- The schema definition (`SubmitReviewTool.java`) is a contract change
  for the reviewer prompt. Existing reviewer prompts in
  `PromptTemplateRegistry` need to be updated; old free-form review
  expectations need to be wound down.

---

## P4 — Coupling-equation gates for INTERDISCIPLINARY papers

**Effort:** ~2 weeks. **Pre-condition:** P1's `DimensionalArithmetic` tool
(for the unit-balance check) makes P4 strictly easier. Workable without
P1 using a stripped-down dimensional parser, but the full tool is cleaner.

**The bug:** REVIEW_REPORT refresh #3 §1 rec #4 names P-a328-410 (vdW
pressure → composite longitudinal stress = 11099 MPa, 11.1 GPa — 3-4×
steel UTS). All 3 INTERDISCIPLINARY papers in the May 2026 batch are
Rejects with no stated physical coupling. Today's
`CrossDomainHypothesisGate` (`CrossDomainHypothesisGate.java:83`)
**explicitly skips** INTERDISCIPLINARY domain, treating it as legitimately
cross-domain.

**Three layered changes:**

### P4a — Mandatory coupling-equation slot in Hypothesis

**Files touched:**
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  — the INTERDISCIPLINARY hypothesis template gains a required slot:

```
**Coupling Equation:** <one-line equation showing how the variable from
domain A maps to the observable in domain B>

  Example (legitimate): "Hall voltage V_H = I·B / (n·q·t), where I is
  current from the electrical sweep and B is the field set by the
  Helmholtz-coil topic."

  Example (illegitimate, would be rejected): "Composite stress σ scales
  with vdW pressure P_vdW" (no equation; just an assertion).
```

- `src/main/java/com/emergentterrain/research/NarrationResult.java` — add
  a `couplingEquation` field on the parsed hypothesis structure.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — the parser splits the hypothesis into prose + coupling-equation when
  the domain is INTERDISCIPLINARY.

When the coupling-equation slot is missing or non-parseable on an
INTERDISCIPLINARY paper, route to NEEDS_REVISION (same precedence as G6
topic-specific). ~3 days.

### P4b — Dimensional-balance check

**Files added:**
- `src/main/java/com/emergentterrain/research/CouplingEquationGate.java`
  — parses the coupling equation, extracts left-hand and right-hand
  dimensions via `DimensionalArithmetic` (P1), and flags imbalance.

```java
public static List<String> validate(String domain, String couplingEquation) {
    if (!"INTERDISCIPLINARY".equalsIgnoreCase(domain)) return List.of();
    if (couplingEquation == null || couplingEquation.isBlank()) return List.of();
    Dimensions lhs = DimensionalArithmetic.dimensionsOf(leftSide(couplingEquation));
    Dimensions rhs = DimensionalArithmetic.dimensionsOf(rightSide(couplingEquation));
    if (!lhs.equals(rhs)) {
        return List.of(String.format(
            "Coupling equation dimensions don't balance: LHS=%s, RHS=%s.",
            lhs, rhs));
    }
    return List.of();
}
```

Routes to REJECTED (structural defect, not revisable) when the check
fires. ~4 days.

### P4c — Operator allowlist

The coupling-equation parser accepts a small grammar of allowed operations:

- Proportional: `A = α·B` (α free parameter; physical interpretation
  required in prose).
- Inverse: `A = α/B`.
- Power-law: `A = α·B^β`.
- Exponential: `A = α·exp(-β·B)`.
- Threshold-step: `A = α·θ(B − B₀)`.

Anything more exotic (sum of multiple domains' variables, products of
three or more variables, transcendental forms) requires a citation to a
real-world coupling that's been observed. The citation goes in the
`closed_form` field of a new `literature-corpus.yml` entry; the parser
references it.

~5 days for the grammar, allowlist enforcement, and the YAML cross-check.

**Files touched (P4c):**
- `src/main/resources/literature-corpus.yml` — new entries for any
  legitimate cross-domain coupling (Hall effect, acoustic Doppler in
  moving media, Schottky barrier thermionic emission). The Phase 7 F3.5
  INTERDISCIPLINARY engine-constructed bridges already have sentinel
  entries; P4c upgrades them to real entries with cited coupling forms
  OR retires the bridges that don't have a real-world precedent.

**Acceptance:**
- Every INTERDISCIPLINARY paper after P4 contains a parseable
  `**Coupling Equation:**` block in the Hypothesis.
- Dimensionally-imbalanced couplings publish as REJECTED.
- The May 2026 corpus's three INTERDISCIPLINARY Rejects (P-410, P-427,
  P-432) when replayed produce explicit coupling equations OR get caught
  at P4a's mandatory-slot check.
- No false-positive REJECTED on a legitimate INTERDISCIPLINARY topic
  (verify via the engine-constructed bridges' replays after the YAML
  upgrade).

**Risk:**
- The coupling-equation parser is its own small language; getting the
  grammar right takes more iteration than the rest of the piece. Start
  with the proportional + power-law cases (cover P-410 and P-427); add
  exponential / threshold-step only if real corpus papers need them.
- Retiring the engine-constructed bridges that don't have a real-world
  precedent breaks Phase 7 F3.5 topic surfacing. Confirm with the
  operator before doing so — the corpus might want them as deliberate
  "engine-constructed null cases" for experimental purposes.

---

## How these four interact

```
                  P1 expanded tool surface
                  ────────────────────────
                          │
              ┌───────────┼──────────────┐
              │           │              │
              ▼           ▼              ▼
        numerical_     fit_curve     dimensional_
        integrate                    arithmetic
              │           │              │
              │           │              ▼
              │           │         (used by P4b)
              ▼           ▼
        (used by every closed-form
         physics / materials / chem paper)

                  P2 reduced sandbox friction
                  ───────────────────────────
                  (independent of P1, but
                   P1's dedicated tools reduce
                   the LLM's reliance on the
                   sandbox in the first place)

                  P3 schema-pinned reviewer prompts
                  ─────────────────────────────────
                  (independent of P1 / P2;
                   improves panel signal quality;
                   reduces D4 demotion frequency)

                  P4 coupling-equation gates
                  ──────────────────────────
                  P4a hypothesis slot — independent
                  P4b dimensional check — needs P1's
                      DimensionalArithmetic tool
                  P4c operator allowlist — needs P4a
```

P1 is structurally upstream of P4b. Otherwise the pieces compose without
ordering constraints — operator can pick any starting point based on
which signal the next REVIEW_REPORT refresh names loudest.

---

## What this phase deliberately does NOT include

- **Better prompt templates** — every refresh has highlighted prompt-
  template gaps. Phase 12 stayed away from prompt edits to keep rollback
  surface small; that choice extends here. A separate prompt-engineering
  phase would be its own scoping document.
- **Model swaps** — moving from `meta/llama-3.3-70b` to a newer reviewer
  or professor model would shift the baseline. Not in scope. Use the
  existing PrimaryModelRegistry (Phase 9 U3) to test models in
  shadow-mode first.
- **GPU-tier expansion** — Phase 8's `run_gpu_eval` is fine for now;
  expanding it isn't on this list because GPU capacity is the binding
  constraint, not tool surface.
- **Streaming refinement** — multi-turn refinement after the first
  publish (a paper learns from peer scores and emits a v2). Big change,
  separate phase.
- **Cross-paper citation graph** — Phase 5 C1 already does citation-
  aware scoring. Building a full graph (with PageRank-style influence)
  is its own phase.
- **Domain expansion** — Phase 7 F3 added BIOLOGY, CHEMISTRY, ARCHITECTURE
  variants. Adding more domains (econ, neuro, …) isn't in scope here.

---

## Acceptance gates (per piece — if any of these ship)

| Piece | Headline acceptance test |
|-------|--------------------------|
| **P1** | After 3 refreshes, sandbox-reject count down ≥30%; every new tool cited at least once across a 20-paper batch. |
| **P2** | After P2, sandbox-reject count down ≥50%; the 10 papers in §7 with ≥5 rejects each produce ≥1 successful tool call when replayed. |
| **P3** | rev:parse_failed events down ≥80%; ≥90% of panels reach n=4. |
| **P4** | Every INTERDISCIPLINARY paper has a parseable Coupling Equation; dimensionally-imbalanced couplings publish as REJECTED; the 3 May 2026 Rejects (P-410/427/432) get caught at the new gate when replayed. |

**Phase-level success:** acceptance rate moves from Phase 12's projected
~15% to ≥25% over a 20-paper window. The bar is **content quality**, not
fabrication elimination — papers should actually use the new tools, the
reviewer should produce structured panel signals, and cross-domain papers
should derive their couplings rather than assert them.

That last sentence is the load-bearing one. Phase 12 stopped publishing
fabricated content. The next phase (whichever subset of P1–P4 ships) starts
producing better content. Without that distinction this document is
nothing more than the next round of defensive plumbing.

---

## Migration table — former P5–P8 sections (2026-05-18 trim)

The pre-trim wishlist accumulated tactical REVIEW_REPORT-driven items
under labels P5–P8. None of them were "human-level paper-building" in
the original sense; they were defensive narrator scrubs, corpus-aware
narration upgrades, scheduler backoff, and Phase 17 diagnostics. This
table records where each former piece lives now so that historical
references (`archive/phase-13-recommendation-fixes.md` line 311 et al.) remain traceable.

| Former label | Title | Disposition | Where it lives now |
|--------------|-------|-------------|--------------------|
| P5a | Suppress canned conclusion when status ≠ PUBLISHED | SHIPPED | commit `8b5c46a`; site in `PromptSanitizer` |
| P5b | Strip number-words harness leaks | SHIPPED | commit `8b5c46a` |
| P6a | Cross-paper citation verdict propagation | SHIPPED; flag flip tracked | commit `af4cc97`; flag in `phase-flags.md` |
| P6b | Prior-result injection at formulation time | SHIPPED; flag flip tracked | commit `72e99e1`; flag in `phase-flags.md` |
| P7 | Topic-clone scheduler backoff | SHIPPED; flag flip tracked | commit `4d9cad3`; flag in `phase-flags.md` |
| P8a | `rev:parse_failed→fp` raw-byte diagnostic | SHIPPED | commit `90cb52f` |
| P8b | D3 zero-successes counter symmetry | SHIPPED | commit `840b9b4` |
| P8c | Magnitude-range validator scans tool-call trace | OPEN | `phase-18-pending-followups.md` §18.1 |
| P8d | Unsourced-claim validator ("math right, prose fabricated") | OPEN | `phase-18-pending-followups.md` §18.2 |
| P8e | Internal-fragment detector ("conjunction + bare number") | TEST-PINNED, wishlist-only | commit `133fe83` pins the test on P-647; promote to implementation only after a second observed occurrence |

---

*This is a reference document, not a queued PR. Pull from it as Phase 12's
rollout signals which lever to pull next.*
