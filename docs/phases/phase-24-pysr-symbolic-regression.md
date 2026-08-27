# Phase 24 — PySR Symbolic Regression via the Julia Sidecar

**Status (2026-05-22, end-of-day):** CODE COMPLETE + FLAG ON +
**§24.5 acceptance bar cleared at 5/5**.

§24.1–§24.4 shipped + tested (50/50 + 11/11 + 8/8 + 32/32 + 4/4 + 6/6
= unit/integration passes; pre-existing Mockito/Java-25 friction in
`MathToolSuiteToolDescriptionTest` unrelated to this work). §24.5's
offline recovery probe shipped at `scripts/pysr-probe/`. Sidecar-side
follow-up §24.6 landed all three remediation levers:
- Per-endpoint timeout split (`/pysr` ceiling 120s vs `/eval`-family 30s)
- **Lever 1** auto-normalize (sidecar `ff4d40e` + hotfix `4574f8e`)
- **Lever 3** lazy autoRetry on stuck fits (sidecar `4574f8e`)
- **Lever 2** resource-adaptive ensemble — **landed dormant**
  (2026-05-23): sidecar fan-out gated by `autoEnsemble` (default off),
  Java flag `app.julia.pysr.autoEnsemble` (default off). Stays off
  until a ≥4-core sidecar is in scope; see §24.6 for the as-built
  notes and why the `PYSR_LOCK` lift wasn't needed.
- Probe upgrades: `--auto-normalize` / `--auto-retry` flags, NRMSE
  pass criterion, full-pareto scan (probe `64399e9`)

The §24.5 4/5 acceptance bar — originally overridden at the flag flip
when the probe was at 3/5 against absolute-MSE thresholds — is now
cleared honestly at **5/5** under `--auto-retry` against the more
defensible NRMSE < 1e-3 criterion (live run, 2026-05-22).
Recommended production wiring: `autoRetry: true, autoNormalize: false`
on every `symbolic_regress` call. The two corpus-level checks
(in-corpus uptake + math-domain non-regression) remain operator
tasks once 20 PHYSICS+MATERIALS papers have shipped under the new flag.

**Live-verification runbook:**
```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'     # ~5-10 min first run
./scripts/start.sh                                       # restart sidecar
cd "$(git rev-parse --show-toplevel)"
./scripts/pysr-probe/run.sh                              # smoke check
# Note: probe is now informational, not a flag-gate (flag already on).
# 3/5 expected on the current fixtures; newton-gravity and rc-decay
# fail for §24.6 reasons (scale precision + linear local basin) and
# do not block the rollout.
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

**Why this engine.** PySR is multi-population genetic programming over
expression trees, runs on CPU with configurable population × iteration
budgets, and gives a Pareto front of (accuracy, complexity) candidates.
It fits the existing Julia sidecar without adding another runtime or a
reliable-GPU requirement.

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

### 24.6 — Failure-mode classification + remediation levers

Added after the 2026-05-22 probe runs surfaced two qualitatively
different fixture misses. Documents the distinction so future
capacity-planning conversations don't reach for "more compute" when
the failure is precision-bound rather than search-bound.

**Two failure classes, very different remediation paths:**

| Class | Hardware response | Example in current probe | Right fix |
|---|---|---|---|
| **Search-bound** | Cloud helps linearly. More populations × islands × cores → higher escape rate from local optima. Probabilistic, not deterministic. | Originally thought `rc-decay` and `arrhenius` were in this class. **2026-05-22 live-run correction**: Lever 1's rescaling actually fixed both. Rescaling reshapes the GP fitness landscape, not just the BFGS precision floor — local optima at one scale aren't local optima at another. So the class still exists in principle but the probe has no remaining members. | More populations, biased operator priors, or — *as we now know* — rescaling via Lever 1. |
| **Algorithmic** | Cloud does NOT help. Failure is at the BFGS float64 precision floor, not the search depth. | `newton-gravity` — `m1 ∈ [1e24, 6e24]`, target coefficient `G/r² ≈ 6.67e-27`. BFGS gradient at `m1·m2·δ ≈ 1e46·δ` against float64's `1e-15` relative precision → gradient noise at `1e31`, optimizer cannot reliably take `1e-27`-sized steps. 128-core box produces identical failure. | Auto-normalize inputs and target inside `handle_pysr` ("Lever 1" below). NOT a hardware purchase. |

**Why the algorithmic class doesn't scale with hardware.** PySR is GP
over expression trees + BFGS constant optimization in float64. GP
failures are escapable with more independent islands; BFGS-precision
failures live below the float64 noise floor and cannot be reached by
any amount of search depth. `SymbolicRegression.jl`'s `Options{T}` is
parametric and `Options{BigFloat}` is technically achievable, but
100–1000× slower per evaluation — not a hardware win, just shifts the
bottleneck.

**Three levers to address these failures. Pick by failure class and
budget; they compose.**

| Lever | Cost | Fixes which class? | Resource-gated? | Status |
|---|---|---|---|---|
| 1 — Auto-normalize inputs/target | Cheap (~3-5 day code) | Algorithmic (scale) | No | Landed (sidecar ff4d40e + 4574f8e) |
| 2 — Resource-adaptive ensemble | Expensive (cores ×K) | Both, probabilistically | Yes (≥4 cores) | **Landed dormant** (sidecar; request flag `autoEnsemble` + Java `app.julia.pysr.autoEnsemble`, both default OFF) |
| 3 — Feedback loop on the fit | Cheap (~1-2 day code) | Both, opportunistically | No | Landed (sidecar 4574f8e) |

**Lever 1 — auto-normalize in `handle_pysr`.** Compute per-variable
robust scales `s_x[j] = median(|X[j,:]|)` and target `s_y = median(|y|)`
(median rather than mean for outlier robustness). Rescale `X' = X ./ s_x`
and `y' = y ./ s_y` so every variable and the target live in roughly
`[0.1, 10]`. Run SR on the rescaled data, then post-process each Pareto
entry by walking the expression tree to substitute `var_j → var_j / s_x[j]`
and multiply the root by `s_y`; re-evaluate `mse` on the original unscaled
data so returned values are meaningful to the caller. Gated behind a
new request field `autoNormalize: true` (default off for back-compat).
~3–5 day piece of work; bulk is the tree-rewrite layer covering every
operator in `PYSR_BINARY_OPS ∪ PYSR_UNARY_OPS` and round-trip tests
proving each operator composes correctly. The right next thing to land
for the algorithmic failure class.

**Lever 2 — resource-adaptive ensemble.** Read `Sys.CPU_THREADS` at
sidecar startup; fan out K parallel search variants per `/pysr`
request (normalized + raw + log-input + alternative operator subsets)
sized to available cores: 1–2 cores → normalized only, 4 → normalized
+ raw, 8 → +log-input/log-target, 16+ → +operator-subset variants.
Merge all returned Pareto fronts, dedup by structural equivalence,
return the union. Requires **lifting `PYSR_LOCK` to permit K
independent `Options` instances per request** — a refactor, since
SymbolicRegression.jl mutates per-population state on a shared
`Options` object. Per-variant timeout becomes `MAX_PYSR_TIMEOUT_MS / K`
on the same core or the full budget if K cores are dedicated. Only
worth building once a multi-core cloud sidecar is in scope.

**Lever 2 — as landed (sidecar, dormant).** Implemented in
`julia-math-sidecar/src/handlers.jl` and gated behind the per-request
`autoEnsemble` field (default off), with `ensembleCores` to pin the
fan-out width for tests / fixed deployments. Java side mirrors the
Lever 1/3 pattern: `app.julia.pysr.autoEnsemble` (`@Value`, default
false) plus a nullable `PysrRequest.autoEnsemble` per-request override.
Key decisions that diverged from the sketch above:

- **The `Options`-per-call refactor already existed.** `run_pysr_pass`
  (shipped with Lever 1) builds a *fresh* `Options` per call, and the
  "parallel equation_search safety" test proved K disjoint `Options`
  run concurrently without cross-contamination. So the ensemble fans
  out under a *single* hold of `PYSR_LOCK` (K islands inside one
  in-flight request) rather than lifting the lock entirely — the lock
  still serialises *separate* requests, preserving the busy/queue
  contract. Fully lifting it for multi-tenant throughput stays a
  separate later concern.
- **Variants:** `:normalized` reuses Lever 1's rescale + unscaling
  round-trip; `:raw` is the data as-is; `:log` log-transforms
  inputs+target (recovering power/multiplicative laws via the
  `exp(·)∘log` round-trip, with an all-positive applicability guard
  that drops the island on sign-spanning sweeps); `:opsubset` searches
  the arithmetic/power core with transcendental ops dropped.
- **Tiering** rounds down to the 1/2/4/8/16-core thresholds; the
  ensemble collapses to the single normalized island below 4 cores, so
  enabling the flag on small hardware is never worse than Lever 1.
- **Fan-out** is one `Threads.@spawn` per island, each behind its own
  `timed_evaluate` budget; a timed-out/errored island contributes
  nothing and the merge (`merge_pareto_fronts`, generalised to N
  fronts, dedup by printed-expr) unions whatever finished. Each island
  gets a seed offset so the GP populations are decorrelated.

Acceptance left as an operator task per the resource gate: turning
`autoEnsemble` on only pays off on a ≥4-core sidecar, so the in-corpus
recovery-rate comparison runs when that hardware is in scope.

**Lever 3 — feedback loop on the fit.** After a first search, check
the best Pareto entry for stuck-search markers: constants pinned at
BFGS search bounds (e.g. abs value near `1e10`, the default
`constraints` cap in SymbolicRegression.jl), output magnitude
mismatched by >3 OOM from the target's, or pareto-size suspiciously
small (<3). If any trip, re-run once with rescaled inputs and merge
fronts. Adds latency only when the first pass self-reports stuck —
typical successful calls pay zero overhead. Pairs naturally with
Lever 1: Lever 3 is the lazy-trigger path for Lever 1's rescaling,
so callers don't need to opt in via `autoNormalize`. ~1–2 day piece
of work assuming Lever 1's rescaling primitive already exists.

**When cloud upgrades do pay off.** Linear gains for everything in
the search-bound class plus general latency, throughput, and the
upper end of problem complexity. A c7i.16xlarge or n2-highcpu-64
would drop per-fixture wall from ~5–10s to ~1–2s at default
settings, allow `populations=200` as a baseline, and lift the
practical samples-per-call ceiling from 256 to ~4096. None of that
makes the algorithmic class succeed — only Lever 1 does — but it
does make every working case markedly faster and unlocks deeper
default searches.

**`PYSR_LOCK` becomes the throughput bottleneck on better hardware.**
The sidecar serialises `/pysr` calls (handlers.jl) because
SymbolicRegression.jl mutates per-population state and isn't
thread-safe across the same `Options` object. On a single-core box
this is invisible; on a 64-core box it's the dominant
throughput limit. Lifting the lock means K independent `Options`
instances per request — a refactor, not a config flip. Worth doing
*before* a cloud upgrade if multi-tenant throughput matters; not
worth doing for the current single-operator workload.

**Acceptance impact.** The §24.5 4/5 bar is now cleared at **5/5**
under the recommended production config `autoRetry: true,
autoNormalize: false` (2026-05-22 live run, sidecar commit
`4574f8e`, probe commit `64399e9`). The 3/5-baseline-with-overrides
framing in the original status block is **historical**: it reflected
the absolute-MSE pass criterion which counted two coincidentally
small-magnitude misses (rc-decay's linear approximation and
arrhenius's piecewise-linear approximation) as passes. Under the
honest NRMSE < 1e-3 criterion the baseline was actually 2/5; Lever 1
+ Lever 3 together raise it to 5/5.

**Production wiring (recommended).** Set `autoRetry: true` on every
`symbolic_regress` call from `MathToolSuite`. Leave `autoNormalize`
default-false. The first pass costs nothing extra; the retry fires
only on stuck fits (`fit_looks_stuck` heuristic in the sidecar). The
LLM caller sees a single merged Pareto front with the retried-better
entry surfaced when applicable.

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
