# Phase Wishlist — Quality Levers (reference, not queued)

**Status:** **Wishlist document.** Not a queued PR, not scheduled work. This
file captures high-leverage interventions that move the system from
"fewer fabrications" (Phase 12's defensive scope) to "actually better
content." Each piece is sized and pointed at concrete files so future
planning can pull from this catalog directly, but the order of execution
and the decision to start at all is deliberately deferred.

**Pieces:** P1-P4 are the original four high-leverage levers (~3w, ~2w,
~1.5w, ~2w respectively). P5-P7 were added 2026-05-17 from REVIEW_REPORT
refreshes #3-#4 of `research/a3289005/` — three smaller items
(narrator scrubs, corpus-aware narration, topic-clone backoff) that
target net-new failure modes the original four did not cover.

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

## P5 — Narrator scrub list (small defensive items)

Two small narrator-stage rules surfaced by REVIEW_REPORT refreshes #3-#4
of `research/a3289005/`. Neither needs architecture changes — each is a
guarded `if` at a single point in the narration pipeline. Packaged
together because they ship as one commit.

### P5a — Suppress canned conclusion when status ≠ PUBLISHED

**What:** When the narrator-fallback path emits the canned line
*"Computed values were independently reproduced by peer reviewers, and
the topic-specific validator supports the hypothesis"*, gate it on the
pipeline status. If `status ∈ {NEEDS_REVISION, REJECTED}` or
`successful_tool_calls == 0`, fall through to a neutral "No verified
evidence produced this submission" string instead.

**Why:** P-a328-553 shipped with the canned line despite `LLM Call
Path = gate:closed:professor → publish:no_methods→needs_revision` —
zero successful tool calls, status NEEDS_REVISION, no Methods section,
yet the published markdown claims "values were independently
reproduced." The boilerplate is emitted by a fallback path that does
not consult the publish status.

**File:** `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
— grep for the literal canned-line string to find the emit site.

**Risk:** Very low. The replacement string is bit-identical to today
when status == PUBLISHED.

### P5b — Strip number-words harness leaks

**What:** Extend the existing Hypothesis/Findings scrub to also strip
number-words tokens (`one|two|...|hundred`, plus decimal compound forms
like `point` glue: `seven point one`). Pair with the existing numeric-
form scrub (`param2=4.7` etc.).

**Why:** Existing scrub catches numeric forms but P-a328-541 emitted
"*starting position = fifty, scale = seven point one*" in Findings —
number-words form defeats the digit-regex. Alternative implementation:
scrub the structured `param1=…, param2=…` BEFORE it reaches the LLM
prompt rather than catching it on the way out (more robust but a
larger change).

**File:** `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
or `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
— wherever the existing parameter-leak filter lives.

**Acceptance:** Across one REVIEW_REPORT refresh after ship, zero
papers carry `starting position = <number-word>` or `scale = <number-
word>` phrasing in Hypothesis or Findings.

**Risk:** Low. The number-word list is closed (zero through hundred +
common decimals), regex-replaceable.

---

## P6 — Corpus-aware narration (cross-citation verdict + prior-conclusion injection)

Two related upgrades that feed prior-paper knowledge into the narration
+ formulation prompts. Both surfaced as "the system treats the corpus
as topic-indexed but not conclusion-indexed" in REVIEW_REPORT refresh
#3 §1 rec #7 and the quality-and-novelty section.

### Why this matters

Refresh #3 produced three cross-paper citations (P-521 → P-510;
P-538 → P-526; P-539 → P-534) — a first for the corpus and a
methodological positive. Two went well:

- P-538 → P-526: cited values η ≈ 0.761 at T_c ≈ 357 K, T_h ≈ 1496 K
  *match* P-526's reported numbers. Verified true.

The third failed because the citing author had no access to the cited
paper's review verdict:

- P-539 → P-534: P-534 was REJECTED for the same `(1/N)` vs `(1/N²)`
  convergence-fabrication failure that P-539 then **re-committed**.
  Because the narrator only saw "prior paper exists on this topic," it
  treated the prior as supportive rather than seeing it was rejected
  for the exact pattern P-539 was about to repeat.

A separate but related case: P-541 directly **inverts** P-516's correct
Reuss `V_f = 1` math, claiming an "interior crossover" that contradicts
the closed-form solution. The system treats the topic as un-tackled
when in fact a prior paper has a strong-confidence answer.

### P6a — Cross-paper citation verdict propagation

**What:** When the narrator includes a Related Work cross-citation,
attach the cited paper's `panel_verdict` (and Honesty score) to the
prompt context. Two policy options:

1. **Disclaimer mode** (lower risk): prepend "*Note: the cited paper
   P-X was classified <verdict>. Do not propagate its conclusions
   without independently verifying its tool-call trace.*"
2. **Suppression mode** (stronger): suppress cross-citations whose
   target is REJECTED or DISPUTED.

Default to disclaimer mode; flag suppression behind
`app.research.corpus-citations.suppress-rejected` (default off, escalate
after one refresh).

**File:** `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
(prompt assembly). The verdict fetch is a single query against the
`papers` table — already keyed by `paperId` in `PapersRepository`.

**Acceptance:** After ship, zero papers cite a REJECTED prior in
Related Work without the disclaimer phrase appearing in the published
markdown. If suppression flag is on, zero REJECTED priors appear in
Related Work at all.

**Risk:** Low for disclaimer mode (extra prompt context, no behaviour
change). Medium for suppression mode (drops one signal the LLM might
use for novelty assessment).

### P6b — Prior-result injection at formulation time

**What:** When a new paper is formulated on a topic that already has a
`classification == SUPPORTED, panelHonesty ≥ 0.5, math-verified` prior,
the formulator's prompt should include:

> *Prior result: <citation> established that <one-sentence
> conclusion>. Either replicate the result at different parameters,
> refute it with new evidence and explicitly cite the contradiction,
> or pick a different sub-question.*

Picks the highest-peer-score PUBLISHED + SUPPORTED prior on the topic.
If none exists, the prompt is bit-identical to today.

**File:** wherever topic-selection + formulation lives — likely
`src/main/java/com/emergentterrain/research/ExperimentPlannerService.java`
or `ResearchProgramService`. The "best prior" lookup is a SQL `SELECT`
ordered by `peer_score DESC` with the filter predicates.

**Acceptance:** After ship, papers landing on a topic with a high-
confidence prior either (a) cite that prior in Related Work, (b) cite
it explicitly in the Conclusion as "extends" / "refutes", or (c) shift
sub-question and the new Hypothesis is materially different from the
prior. A papers-vs-priors audit script can spot-check by querying for
same-topic papers and diffing Hypothesis text.

**Risk:** Medium. Prompt expansion adds ~80-120 tokens. The "best
prior" selector could be game-able if multiple priors disagree —
restrict the injection to topics where exactly one math-verified prior
exists in the first iteration.

### Open questions before sequencing P6

- **Pull or push?** P6a is a *pull* (narrator fetches the verdict at
  citation time); P6b is a *push* (formulator gets the prior context
  before any LLM call). Same data source, two different injection
  points. P6b is the bigger ergonomic shift but also the more useful
  one — it shapes the hypothesis, not just the prose.
- **Verdict granularity.** Today verdict is one of
  `PUBLISHED|REJECTED|DISPUTED|NEEDS_REVISION|NULL_RESULT`. For P6a's
  disclaimer mode that's enough. For P6b's "refute with explicit
  contradiction" path, the LLM also needs the cited paper's
  *conclusion* — see if `paper.conclusion` is already a column or if
  it needs to be derived from the markdown.
- **De-duplication with Phase 5 C1.** C1 already does citation-aware
  scoring at publish time. P6 is upstream of that — feeding context
  at narration/formulation. Confirm we're not duplicating logic.

---

## P7 — Topic-clone scheduler backoff

**What:** In the topic-selection logic, count recent rejections per
topic; multiply the selection weight by `0.5 ^ consecutive_rejections`.
After two consecutive rejections the topic is effectively de-
prioritised; after four, it falls below all positive-weight
alternatives.

**Why:** Divisor-sum-average was attempted **four times** in the
corpus (P-a328-534, 539, 553, plus one more attempt rejected pre-disk
per the operational-log §). All four failed with overlapping `(1/N)`
vs `(1/N²)` convergence-fabrication patterns. The scheduler kept
re-picking the topic because nothing in the picker reflected "this
topic has been failing." Cited as REVIEW_REPORT refresh #3 §1 rec #7.

**Files:**

- Topic-selection logic — likely
  `src/main/java/com/emergentterrain/research/ExperimentPlannerService.java`
  (the planner is where topic picks happen) or
  `src/main/java/com/emergentterrain/research/ResearchProgramService.java`
  (where programs are seeded). Grep for `selectTopic` /
  `pickTopic` / weight-computation methods.
- New: a small `TopicHealthService` (or method on an existing service)
  that counts rejections per `(domain, topic)` over a configurable
  rolling window (default 5 most-recent attempts) and exposes a
  `multiplier(domain, topic) → double` for the picker.

**Acceptance:** After ship, no topic accumulates ≥3 consecutive
rejections within a 5-paper sliding window without being de-
prioritised. A "topic heat" debug log line per refresh shows the top-5
hottest topics; the formulation pipeline routes around them
automatically.

**Risk:** Medium. Coupling to existing reward dynamics (Phase 5 C1
citation scoring, Phase 1 program rewards) needs care — a topic that
is rejected for fabrication reasons looks identical at the
selection-weight layer to a topic that is genuinely hard. Mitigation:
the multiplier *decays* with time (a 5-paper rolling window) rather
than permanently penalising. Topics with merit will recover once the
oldest rejection ages out.

**Open questions:**

- **Window size.** Five is a guess. Worth a one-line YAML knob
  (`app.research.topic-backoff.window-size`) so refresh evidence can
  tune it.
- **Domain-level fallback.** If every topic in a domain is on cooldown
  (possible if the domain just had a bad batch), what's the fallback?
  Probably: pick the coolest topic regardless of multiplier rather
  than block the domain entirely.

---

## P8 — Phase 17 follow-on: diagnostics + narrator scrubs (refresh #1+#2 evidence)

Four small items surfaced in post-Phase-17 refreshes #1 + #2 (see
`research/a3289005/REVIEW_REPORT.md`). Each is narrower than P1–P7 —
paired diagnostic/fix work or a targeted narrator scrub. Listed by
triage priority.

### P8a — `rev:parse_failed→fp` raw-byte diagnostic + repair

**What:** Capture the first 500 chars of the raw revision stream when
`rev:parse_failed→fp` fires, at WARN level. After one refresh of
captured bytes, decide whether the fix is provider-side (Groq not
honoring `response_format: json_object` for v3 structured-findings
templates) or parser-side (LenientJsonParser missing a repair pattern).

**Why:** `rev:parse_failed→fp` is the dominant tier-2 failure across
refreshes #1 and #2 — 24 occurrences in refresh #2 alone, 6 of 10
disk-resident papers carry the stamp. Phase 17 JSON-output mode
(commit `2ac020d`) was supposed to close this and hasn't. Without
raw bytes, both hypotheses (provider-side ignore vs parser gap) are
equally plausible and a guess-fix has 50/50 odds of being wrong.

**Files:**

- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — the `rev:parse_failed→fp` stamp site (around line 770–780). Add
  a WARN log with `revised.toString().substring(0, Math.min(500,
  revised.length()))` before the fallback. Guard behind a flag
  (`app.research.rev-parse-failed-byte-capture.enabled`).
- `src/main/java/com/emergentterrain/research/LenientJsonParser.java`
  — likely site for any parser fix once bytes are captured.

**Acceptance:** Diagnostic ships behind a flag, observability-only.
After one refresh produces 10+ captured samples, the dominant
failure shape is named. The fix that follows (in `LenientJsonParser`
or the revision prompt) drops `rev:parse_failed→fp` to under 10% of
revisions over one refresh.

**Risk:** Low — diagnostic flag is observability-only. Subsequent
parser/prompt change is bounded by the existing `:parse_failed→fp`
fallback chain.

**Open questions:**

- **Log volume.** 500 chars × 24 firings per 30-min refresh is ~12 KB
  of log per refresh — bounded. No PII risk on a research pipeline.
- **Cap.** Should the diagnostic auto-disable after N captures?
  Maybe — a `max-captures: 50` cap prevents log floods on a
  pathologically broken release.

### P8b — D3 `zero_successes` counter audit + symmetry fix

**What:** Audit `MathToolSuite.ToolMetrics` to confirm that
`run_symbolic` rejections increment `sandboxRejectCount` /
decrement `totalSuccesses` the same way `run_sandboxed_code`
rejections do. Hypothesis is asymmetric counter increment, causing
D3 (`totalSuccesses == 0` gate) to miss papers where every tool
call was a `run_symbolic` failure.

**Why:** P-a328-613 (Phase 17 refresh #2) had 5/5 tool calls
rejected — 4 `run_symbolic` syntax errors on `\theta` escapes + 1
timeout — and still got `prof:llama:ok` stamped instead of
`zero_successes→suppressed`. The paper then published with
fabricated "experimental measurements" content and was caught only
by Phase 5 C2 replication (DISPUTED). D3 should have caught it at
the professor stage. The call_path shows `sandbox_reject=1` but
visibly 5 calls failed — the increment count is wrong by 4×.

**Files:**

- `src/main/java/com/emergentterrain/service/MathToolSuite.java` —
  inspect `ToolMetrics` increment paths in the executor closure.
  Likely the `run_symbolic` branch increments only on
  static-rejected calls, not on `run_symbolic`-side rejections
  (non-whitelisted heads, timeouts, parse failures).
- Test: a new test class `MathToolSuiteCounterSymmetryTest.java`
  that pins both tool types' rejection paths against the same
  counter.

**Acceptance:** A paper with 5/5 `run_symbolic` rejections produces
`metrics.totalSuccesses() == 0` AND `metrics.totalRejects() == 5`;
D3 fires and stamps `zero_successes→suppressed`. Existing D3 tests
continue to pass.

**Risk:** Low. Counter symmetry is a bug fix, not a feature change.
The D3 gate's behaviour on existing well-counted papers is preserved.

**Open questions:**

- **Backfill.** Do we re-evaluate already-published papers with the
  corrected count? Probably no — the gate is forward-looking;
  retroactive status changes would invalidate existing citations.

### P8c — Magnitude-range validator scans tool-call results, not just Findings prose

**What:** Extend `MagnitudeRangeValidator` to iterate
`narration.toolCallTrace()` entries (in addition to the prose) and
apply the same per-(domain, topic) ceilings to numeric results in
tool outputs. Today the validator only scans Findings text, so an
unphysical magnitude that lives in the trace but doesn't surface
in the prose escapes.

**Why:** P-a328-593 (Phase 17 refresh #1) had call_4 reporting
`Best: T=785 K → P_c=52 atm → longitudinal σ=5790 MPa` — 5.79 GPa,
clearly above the 3 GPa composite-stress ceiling the validator
already enforces. The Findings prose dropped the 5790 MPa number,
so the validator's prose-scanning pass found nothing to flag.
Status PUBLISHED. The new panel-grade-floor gate (once flipped)
catches the panel-grade aspect; this item closes the
magnitude-range aspect at the same publish-time tier.

**Files:**

- `src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java`
  — add a tool-call-trace overload alongside the existing
  findings-text overload; share the per-topic ceiling table.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — at the call site (around line 800–810), pass the trace alongside
  the findingsBlocks.

**Acceptance:** Replaying P-a328-593 through the publish pipeline
with this change produces a `magnitude-range:trace_violation` stamp
and routes REJECTED based on the 5790 MPa trace value, independent
of the Findings prose. Existing prose-violation papers continue to
flag as before.

**Risk:** Low–medium. False positives if a tool legitimately returns
intermediate numerics that exceed the ceiling but aren't final
results (e.g. a parameter sweep that finds the max and then filters
to the operating window). Mitigation: only scan the final
`result` field of completed calls, not the sweep grid.

**Open questions:**

- **Per-topic granularity.** The existing ceiling table is keyed by
  (domain, topic). Should the trace scan use a wider net to catch
  cross-topic spillover (e.g. a `materials/basquin` paper that
  computes a composite stress)? Default to topic-scoped for v1.

### P8d — Narrator scrub: "math right, prose fabricated" pattern

**What:** Add to the existing P5-class narrator scrub list a rule
that flags Findings prose making quantitative claims (derivatives,
uncertainty propagation, error magnitudes, sample sizes) without a
corresponding `[call_N]` reference. Currently the panel catches
these (honesty/completeness sink to 0.00–0.10) but the LLM keeps
emitting them; only the panel-grade-floor demote (once flipped)
saves the corpus from publishing them.

**Why:** Phase 17 refresh #2 had two papers in this shape — both
with **correct tool-call math** but Findings prose that invented
specific numeric claims no call produced:

- **P-a328-603 Snell.** Call 3 returned exact
  `arcsin(1.49/1.50) = 83.3804°`. Findings prose then invented
  "1.5° maximum observed error" and "331° per unit n₂ derivative"
  — neither has a supporting tool call.
- **P-a328-605 Kepler.** Calls 3 and 5 returned exact 4.12% and
  4.08% T²/a³ deviations. Findings prose then claimed "53 samples"
  and "20 samples" — only 5 calls ran. `single_point_sweep`
  validator caught the sample-size claim; nothing caught the
  derivative claim.

The pattern: LLM produces an honest computation, then pads the
prose with "physical interpretation" content that reads plausible
but has no tool-call provenance. Looks like P-571's textbook-canon
drift but at a finer grain (no clear single tweaked-number; entire
claim is invented).

**Files:**

- `src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java`
  — likely site for a new check. Existing Check-C
  (unsourced-failed-call) targets specific numbers without a
  `[call_N]`; this extends to *types of claim* (derivative /
  uncertainty / sample-size) that should always carry a citation.
- Or: a new validator `UnsourcedClaimValidator.java` co-located
  with the others.

**Acceptance:** Replaying P-603 + P-605 through the publish pipeline
produces an `unsourced_claim:derivative` /
`unsourced_claim:sample_size` stamp that routes NEEDS_REVISION
when derivative/uncertainty/sample-size prose appears without a
paired `[call_N]`. P-595 Basquin (correct math AND correct prose)
continues to pass.

**Risk:** Medium. The regex for "derivative / uncertainty / sample-
size phrase" is fuzzy; false positives on legitimately-cited claims
are plausible. Mitigation: ship in `warn` mode first (stamps but
doesn't route), promote to `downgrade` after one refresh confirms
the FP rate is < 5%.

**Open questions:**

- **Vocabulary list.** Which phrases are the canonical
  derivative/uncertainty/sample-size triggers? Likely:
  `derivative\s+(of|dθ_c/d|d\w/d)`, `uncertainty\s+of`,
  `(\d+)\s+samples?`, `error\s+(magnitude|propagation)`,
  `±\s*\d`. Empirical from refresh #2's two examples.
- **Interaction with `single_point_sweep`.** That validator already
  catches the "53 samples" claim. P8d should not double-stamp;
  defer to F3's existing handling for sample-size and target only
  the derivative / uncertainty / error-magnitude trio.

### P8e — Internal-fragment detector ("conjunction + bare number" shape)

**What:** A new validator that flags mid-sentence fragments of the form
*"<conjunction>, <number>, <continuation-word>"* — e.g.
`"However,30, suggesting..."` — where a sentence-starting connective
is followed by a bare comma-bound number with no unit, no noun phrase,
and no antecedent. Ship in `warn` mode first (stamps
`internal_fragment:warn` on call_path without changing status), soak
one refresh, escalate to `downgrade` only if the false-positive rate
is acceptable.

**Why:** P-a328-647 (refresh #4) had Conclusion *"…mean deviation of
0.001. However,30, suggesting that the divisor-sum average's behavior
is more complex than initially anticipated."* The fragment
*"However,30,"* is grammatically broken — likely a JSON re-serialization
or LLM-generation glitch that elided the original *"However, with only
30 samples, suggesting…"*. The existing Phase 5 A1 truncation-guard
correctly does NOT fire because the text ends with `anticipated.` — a
proper terminator. The malformation is internal, not tail. Documented
by `PublishPaperTest.d5_p647InternalMalformationIsNotATailTruncation`
(commit `164d541`) which pins the truncation-guard's correct
non-firing behavior on this exact text.

**Files:**

- New: `src/main/java/com/emergentterrain/research/InternalFragmentValidator.java`
  — single static method
  `List<String> detect(String hypothesis, Map<Integer,String> findingsBlocks, String conclusion)`
  returning the set of detected fragment phrases. Returns empty list
  when no match — bit-identical to flag-off behaviour.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — wire the validator at the same publish-time layer as
  `direction-of-finding` / `single-point-sweep`. Stamp
  `internal_fragment:warn` on call_path when matches found; route
  status NEEDS_REVISION only in `downgrade` mode.

**Acceptance:**

- `warn` mode: re-running P-647 produces `internal_fragment:warn`
  stamp on the call_path; status unchanged. Refresh #5 measures
  observed firings — fewer than ~1/30 papers is acceptable
  false-positive rate for the LLM's typical prose density.
- `downgrade` mode (escalate after FP rate confirmed): same paper
  shape routes NEEDS_REVISION with `internal_fragment:downgraded`
  stamp.

**Risk:** Medium → High depending on regex tightness. The risk is
real because the legitimate phrasing *"However, 30 samples were
collected, suggesting..."* — after whitespace normalization or a
JSON-stream tokenization quirk — could match the same pattern. The
guard must distinguish "30," followed by a continuation word (broken)
from "30 samples" followed by a verb (legitimate). One signal: in
the legitimate case, the number is followed by a noun phrase before
the next comma. The regex should require that the next 1-2 tokens
after `,\d+,` are non-noun (verbs like *suggesting*, *implying*,
*indicating* — i.e. participles). Ship behind a flag.

**Open questions:**

- **Conjunction vocabulary.** Initial set: *However, Therefore, Thus,
  Hence, Furthermore, Moreover, Consequently, Nonetheless, Nevertheless*.
  Add to a config-driven list so refresh-driven tuning is a yaml flip.
- **Continuation-word allow/deny lists.** Should the validator
  require the post-number token to be a present-participle verb
  (suggesting / implying / indicating / showing) to fire? That tightens
  the FP rate substantially but risks under-fire on novel broken
  shapes.
- **Single-shot vs corpus-aware.** Run only at publish time, or fold
  into the Phase 17 panel-revision prompt as an explicit "do not emit
  bare comma-bound numbers after conjunctions" guidance line? The
  prompt-side fix is cheaper but less observable.
- **Could the existing `harness-leak-filter` regex be extended?**
  It already strips simulator-coord phrases. Adding the
  conjunction-fragment pattern there would reuse infrastructure, but
  the leak-filter rewrites text whereas this validator should
  *flag*, not rewrite — different semantics, separate validator is
  cleaner.

**Status:** Wishlist only. Single observed occurrence (P-a328-647).
Hold for one more refresh — if a second example appears with the
same shape, promote to a flag-gated implementation. If the corpus
goes 3+ refreshes without a recurrence, deprioritise.

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
| **P5** | Zero papers ship with the canned "values reproduced by peer reviewers" line under NEEDS_REVISION; zero papers carry number-words harness phrasing in Hypothesis/Findings across one refresh after flip. |
| **P6** | Cross-citation of a REJECTED prior appears with the disclaimer line (or doesn't appear at all under suppression mode). Papers landing on a topic with a high-confidence prior either replicate, refute-with-citation, or shift sub-question — measured by Hypothesis-text diff. |
| **P7** | No topic accumulates ≥3 consecutive rejections within a 5-paper window without being de-prioritised. Per-refresh "topic heat" log lists the top-5 hottest topics; formulation routes around them. |

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

*This is a reference document, not a queued PR. Pull from it as Phase 12's
rollout signals which lever to pull next.*
