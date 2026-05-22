# Phase 24 — PySR Symbolic Regression via the Julia Sidecar

**Status (2026-05-22):** CODE COMPLETE. §24.1–§24.4 shipped + tested
(50/50 + 11/11 + 8/8 + 32/32 + 4/4 + 6/6 = unit/integration passes;
pre-existing Mockito/Java-25 friction in `MathToolSuiteToolDescriptionTest`
unrelated to this work). §24.5's offline recovery probe shipped at
`scripts/pysr-probe/`; the two corpus-level checks (in-corpus uptake +
math-domain non-regression) remain operator tasks once the production
sidecar has been restarted with the new binary AND `Pkg.instantiate`
has pulled SymbolicRegression.jl on the deployment host.

**Live-verification runbook:**
```bash
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'     # ~5-10 min first run
./scripts/start.sh                                       # restart sidecar
cd ../EmergentTerrain
./scripts/pysr-probe/run.sh                              # acceptance probe
# If ≥4/5 pass, flip app.julia.pysr.enabled=true in application.yml
# and let 20 PHYSICS+MATERIALS papers ship before checking corpus uptake.
```

**Original motivation (preserved below):**

**Motivation.** Phase 23 unlocked novelty for the MATHEMATICS domain
via the `MathConjectureSubstrate` + Java Dalmatian miner. PHYSICS,
MATERIALS, TERRESTRIAL, and UNDERWATER are still served by the fixed
`(domain, topic)` catalog. Phase 23.7 is the structural fix (mine
parameter sweeps); Phase 24 is the **engine** that turns numeric
sweep rows into candidate laws — symbolic regression. PySR
(github.com/MilesCranmer/PySR) is the open-source standard and is
Julia-native, which means it can run inside the existing
`julia-math-sidecar` rather than spawning a new runtime.

**Why this engine, not AI Feynman.** AI Feynman trains a neural net
per recursion level — it needs reliable GPU we don't have. PySR is
multi-population genetic programming over expression trees, runs on
CPU with configurable population × iteration budgets, and gives a
Pareto front of (accuracy, complexity) candidates. AI Feynman is the
better tool in the limit but PySR captures most of the value at a
fraction of the compute. See `reference-conjecture-engines.md` for
the full comparison.

**Scoping note.** This phase ships **the regressor as a tool**, not a
new paper kind. Surfacing PySR results as a publishable conjecture
(`PaperKind.SYMBOLIC_REGRESSION_LAW` or extending
`CONJECTURE_INVESTIGATION` to non-math fits) is a follow-on once
Phase 23.7's substrates exist. v1 of this phase is the LLM (in
professor stage) being able to call `symbolic_regress` over a
sweep window and have a candidate law come back.

---

## Scope

What Phase 24 adds:

| Today | After Phase 24 |
|---|---|
| Professor stage can call `run_sweep` and `run_predicate_scan` to get numeric arrays | New `symbolic_regress` tool returns a Pareto front of `(expression, mse, complexity)` from those arrays |
| Physics narration emits hand-fit constants ("we observe k ≈ 0.04") with no functional form | Narration cites the PySR Pareto-front winner as the proposed law; `verify_conjecture`-style held-out check confirms or refutes |
| Materials parameter sweeps feed into descriptive prose only | Sweeps feed PySR; recovered scaling exponents become the hypothesis the panel reviews |

What Phase 24 explicitly does **not** add:

- A new `PaperKind`. The tool result is consumed by the existing
  professor → student → panel flow. A dedicated paper kind is a
  separate later phase.
- A neural-guided variant (PySR's optional `weights_neural` mode).
  CPU-only multi-population GP first; revisit only if the corpus
  shows PySR finding nothing on >20% of sweeps where a closed form
  exists.
- Replacing `MathToolSuite.runSymbolic` or `evaluate_symbolic`. Those
  remain the per-expression evaluation tools; `symbolic_regress`
  is a peer that takes data → expression.
- Auto-discovery of which variables to sweep. The professor stage
  composes the sweep request from the existing parameter grid; this
  phase does not introduce variable-selection heuristics.

---

## Architecture

```
EmergentTerrain (Spring Boot)
└── MathToolSuite
    ├── run_symbolic         → Julia /eval         (existing)
    ├── evaluate_symbolic    → Julia /sweep        (existing)
    ├── symbolic_regress     → Julia /pysr         (NEW)
    └── verify_conjecture    → ConjectureVerifier  (existing, 23.5)

julia-math-sidecar
└── POST /pysr               → SymbolicRegression.jl (Julia-native PySR core)
    Request:
      {
        "samples":   [[x1, y1, z1, target1], ...],     // <= maxRows
        "varNames":  ["x", "y", "z"],
        "targetName": "f",
        "operators": ["+", "-", "*", "/", "exp", "log", "^"],
        "populations": 8,
        "iterations":  40,
        "timeoutMs":  20000,
        "complexityCeiling": 30
      }
    Response:
      {
        "ok":  true,
        "pareto": [
          {"expr": "x*y",                       "mse": 1.2e-3, "complexity": 3},
          {"expr": "x*y + 0.5*z^2",             "mse": 4.8e-7, "complexity": 11},
          {"expr": "x*y + 0.5*z^2 + 0.03*y/x",  "mse": 1.1e-9, "complexity": 19}
        ],
        "wallMs":     18420,
        "evaluated":  124800
      }
```

**Why SymbolicRegression.jl, not the PySR Python wrapper.** PySR's
own engine is `SymbolicRegression.jl`; the Python package is a thin
PyCall wrapper that round-trips through `juliacall`. Skipping the
Python layer (a) avoids adding a Python runtime to the sidecar, (b)
removes a known source of GC-pause hangs in long PyCall sessions,
(c) lets us use the native Julia API directly. The cost is losing
PySR's Python-side post-processing (sympy beautification, latex
export) — both are easy to recover with a small Julia post-process
or to handle later in the JVM.

**Resource budget.** PySR's default 40 populations × 100 iterations
over a 4-variable expression takes 30–60 s on CPU with 8 worker
threads. The phase-24 default is **8 populations × 40 iterations**
with a **20-second wall-clock cap** — enough to recover laws like
`F = G·m1·m2 / r²` and `v = v0·exp(-k·t)`, but not enough to chase
arbitrary high-complexity fits. The cap is a `@Value`-tunable knob
(`app.julia.pysr.timeoutMs`, default 20000).

**Concurrency.** The professor stage runs serially per paper today,
so at most one `symbolic_regress` is in flight at a time. The
sidecar enforces a `Semaphore(1)` on `/pysr` calls anyway, since
SymbolicRegression.jl is not reentrant-safe across populations on
shared worker pools. If a second call lands while one is running,
the second blocks up to `app.julia.pysr.queueMs` (default 5000ms)
before returning a `busy` error code; the Java side then surfaces
this as a `JuliaMathException` and the professor's tool loop
either retries with backoff or moves on, same fall-through pattern
as 22.2.

---

## Phasing

### 24.1 — `julia-math-sidecar` /pysr endpoint

Add `SymbolicRegression.jl` to the sidecar's `Project.toml`. Implement
`POST /pysr` with the request/response schema above. Enforce the
20-second hard cap via `Threads.@spawn` + timeout. Return
`safety_rejected` if any user-supplied operator falls outside the
allowlist (`+ - * / ^ exp log sqrt abs sin cos`).

**Files (sidecar repo):**
- `Project.toml` — add `SymbolicRegression = "8254be44-..."`.
- `src/handlers.jl` — new `handle_pysr(req)` function.
- `src/server.jl` — route table entry.
- `src/safety.jl` — operator allowlist constant + check function.
- `test/pysr_tests.jl` — unit cases: recovers `x*y` from synthetic
  data in <5s; rejects forbidden operators; honors timeout; returns
  Pareto front sorted by complexity.

**Acceptance:** `curl -X POST localhost:8088/pysr -d
'{"samples":[[1,2,2],[2,3,6],[3,4,12],[4,5,20]],
"varNames":["x","y"], "targetName":"f", "operators":["+","-","*"]}'`
returns a Pareto front whose simplest entry is `x*y` (or
equivalent) in under 20 s.

**Effort:** ~1.5 days.

### 24.2 — `JuliaMathClient.pysr(...)` Java binding

Add a 7th endpoint method to `JuliaMathClient`. Request record
`PysrRequest(double[][] samples, List<String> varNames, String
targetName, List<String> operators, int populations, int iterations,
long timeoutMs, int complexityCeiling)`. Response record
`PysrResult(List<ParetoEntry> pareto, long wallMs, long evaluated)`
where `ParetoEntry(String expr, double mse, int complexity)`.

Wire the existing safety filter — operator list goes through
`JuliaSafetyFilter` (rejects shell/Pkg/etc.), variable names
through the existing `[A-Za-z_][A-Za-z0-9_]*` regex. Counter
fields on `JuliaMathClient` get `pysrCalls`, `pysrLatencyMsTotal`,
`pysrLatencyMsMax` so `JuliaSidecarReporter` surfaces them in the
5-minute heartbeat.

**Files (this repo):**
- `src/main/java/com/emergentterrain/llm/JuliaMathClient.java` —
  new `pysr()` method, new records, new counters.
- `src/test/java/com/emergentterrain/llm/JuliaMathClientTest.java` —
  new cases against the `HttpServer` stub: happy path, busy
  response, timeout, safety reject on bad operator, counter
  increment.

**Acceptance:** all client tests pass; new fields surface in the
heartbeat log when called.

**Effort:** ~0.5 day.

### 24.3 — `MathToolSuite.symbolic_regress` tool

Register the new tool in `MathToolSuite.toolsJson()`. Schema:

```json
{
  "name": "symbolic_regress",
  "description": "Fit a closed-form expression to a small numeric table. Input is rows of [v1, v2, ..., vN, target]. Returns a Pareto front of candidate expressions (accuracy vs complexity). Use when you suspect a clean functional law underlies a sweep — gravity, ideal-gas, Arrhenius, RC-decay — and want a machine to recover it rather than hand-fitting.",
  "parameters": {
    "samples":     { "type": "array", "items": {"type": "array", "items": {"type": "number"}}, "maxItems": 256 },
    "var_names":   { "type": "array", "items": {"type": "string"}, "maxItems": 6 },
    "target_name": { "type": "string" },
    "operators":   { "type": "array", "items": {"type": "string", "enum": ["+","-","*","/","^","exp","log","sqrt","abs","sin","cos"]} }
  },
  "required": ["samples", "var_names", "target_name"]
}
```

Handler in `MathToolSuite.symbolicRegress(JsonNode args)`:
- Reject if `juliaMathClient == null || !isAvailable()`. No Symja
  fallback — this tool simply isn't available without the sidecar.
  The tool description is omitted from `toolsJson()` in that case,
  so the LLM never sees it.
- Reject `samples.size() > 256` to keep per-call wall-clock bounded.
- Reject `varNames.size() > 6` (PySR's effective ceiling for
  recovery quality without per-call retuning).
- Default operators to `["+","-","*","/","^","exp","log"]` if
  omitted.
- Delegate to `juliaMathClient.pysr(...)`.
- Format result for the LLM as a markdown table of the Pareto front
  (existing `MathToolSuite` pattern for tool returns).

**Files:**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java` —
  new constant `SYMBOLIC_REGRESS_DESC`, new method
  `symbolicRegress(JsonNode)`, dispatch case in
  `executeTool(String, JsonNode)`.
- `src/test/java/com/emergentterrain/service/MathToolSuiteSymbolicRegressTest.java`
  — new, ~8 cases: tool absent from schema when Julia is down,
  tool present and callable when Julia is up, samples-cap
  enforcement, var-cap enforcement, operator-allowlist enforcement,
  markdown output shape, Pareto-front sorted by complexity in the
  LLM-facing string, end-to-end recovery of `x*y` against the live
  sidecar (skipped when sidecar unreachable).

**Acceptance:** all tests pass; a smoke paper in a manual run shows
the professor stage calling `symbolic_regress` and the student
revision quoting the Pareto winner.

**Effort:** ~1 day.

### 24.4 — Professor-stage prompt + validator hook

Two narration-layer changes:

1. **Prompt nudge** in `ResearchNarrationService.PROFESSOR_PROMPT_V*`:
   when the paper's domain ∈ {PHYSICS, MATERIALS, TERRESTRIAL,
   UNDERWATER} and the parameter grid has ≥2 dimensions, the
   professor stage is told "if the sweep suggests a closed-form
   relationship, consider calling `symbolic_regress`." No hard rule;
   the LLM picks.

2. **`SymbolicRegressionEvidenceValidator`** — a lightweight
   validator that scans the final narration for a quoted PySR
   expression and confirms (a) the expression appears verbatim in
   the tool-call log, (b) the reported mse matches what the tool
   returned (within 1% relative tolerance). Failure flags the paper
   for revision rather than blocking publish; matches the pattern
   from the citation-corpus / topic-consistency validators
   (commits `87c7c62`, `2809e6a`).

**Files:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — prompt template edit.
- `src/main/java/com/emergentterrain/service/validation/SymbolicRegressionEvidenceValidator.java`
  — new validator, wired into `UniversityService.publishPaper`'s
  validator chain.
- Tests for both.

**Acceptance:** validator catches fabricated PySR expressions
(LLM hallucinating an mse the tool never returned); does not
false-positive on prose that quotes legitimate tool output.

**Effort:** ~1 day.

### 24.5 — Acceptance + rollout

Three checks on the first batch run with `app.julia.pysr.enabled=true`:

1. **Recovery probe (offline).** A scripted test in
   `scripts/pysr-probe/` feeds 5 synthetic datasets with known
   ground-truth laws (Newton gravity, Coulomb, RC discharge,
   Arrhenius, Hooke). At least 4 of 5 recover the law in the Pareto
   top-3 within the default 20-second cap. Run it once before
   flipping the production flag.

2. **In-corpus signal.** After 20 PHYSICS+MATERIALS papers ship
   with the flag on, look for ≥3 papers where the narration cites
   a non-trivial PySR-recovered expression and the
   `SymbolicRegressionEvidenceValidator` passes.

3. **No regression on math-domain papers.** The math domain
   shouldn't be calling `symbolic_regress` because its substrates
   produce inequalities, not parameter sweeps. Confirm via the tool
   call log: 0 `symbolic_regress` calls on math-domain papers in
   the 20-paper sample.

**Acceptance bar to close this phase:** all three of the above. If
the recovery probe passes but in-corpus uptake is <3, the issue is
prompt routing, not the engine — handled by a tighter prompt nudge
in 24.4, not a rework of 24.1–24.3.

**Effort:** ~1 day to write the probe; the corpus run is unattended.

---

## Open architecture questions

1. **Should `symbolic_regress` consume the existing GPU sweep
   output?** The `EmergentTerrainGPUEngine` already produces
   million-sample sweeps. PySR can't ingest a million points
   meaningfully (it'd subsample anyway). For v1, downsample to
   ≤256 stratified rows on the JVM side before sending. Revisit
   only if recovery quality demands it.

2. **Operator allowlist scope.** The proposed allowlist covers most
   physics laws but excludes `tanh`, `erf`, `gamma`. Adding them is
   one line in the sidecar's safety filter. Hold off in v1; add
   each new operator only when a specific paper needs it.

3. **Pareto front size.** Default truncation is top-10 by
   complexity. The professor stage will typically only quote the
   simplest few; sending all 10 lets it pick. If token budget
   becomes the bottleneck, cap at top-5.

4. **Mathematica-style `^` vs Julia-style `^`.** Julia uses `^`
   natively (same as Python's `**`). The Julia path already advertises
   `^` (per 22.4 dual-syntax descriptions), so no translation needed.

5. **Determinism.** PySR's GP is seeded; same input + same seed →
   same Pareto front. The sidecar accepts an optional `seed`
   parameter and defaults it to a value derived from
   `hash(samples + varNames)` so unit tests are repeatable.

---

## Cost / benefit summary

**Total effort:** ~5 days end-to-end (1.5 sidecar + 0.5 client + 1
tool + 1 narration + 1 acceptance).

**Operational tax:** SymbolicRegression.jl is ~150 MB of compiled
Julia state. Sidecar RSS grows from ~200 MB to ~350 MB. One more
PackageCompiler sysimage line if precompilation is enabled. No new
service, no new port, no new Python.

**Expected impact.** Non-math papers in the current corpus carry
their parameter sweeps as descriptive text — "we observe k between
0.03 and 0.05." A successful PySR call replaces that with a
named law. The novelty axis can move off zero for non-math domains
the same way Phase 23 moved it for math.

**Recommendation.** Ship 24.1 + 24.2 together (the engine is
useless without the binding). 24.3–24.5 can land in successive
days. Flag `app.julia.pysr.enabled` defaults OFF per the
`feedback_flag_gated_dormant` pattern — flip it after the recovery
probe in 24.5 passes.
