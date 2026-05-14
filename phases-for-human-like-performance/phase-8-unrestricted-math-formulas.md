# Phase 8 — Unrestricted LLM Math Formulas (scoping)

**Scope:** Move the math sandbox from a hardcoded whitelist of ~28 builtins to a layered architecture where the LLM can express (and the system can safely execute) effectively arbitrary math formulas — numeric, symbolic, and eventually batched on GPU.

**Status:** Scoping document. No code in this phase yet.

**Prerequisite:** [Phase 7 F4](phase-7-multi-provider-and-math-expansion.md) shipped (computations-per-paper bumped past 2) and at least one corpus run with the deterministic [SandboxStaticChecker](../src/main/java/com/emergentterrain/research/SandboxStaticChecker.java) in place (commit `d7175ee`, 2026-05-13). The `sandbox_reject` telemetry from commit `828efca` is the input signal that tells us *which* unrestricted-math gaps the narrator actually reaches for.

**Goal:** Four phases, three composition patterns. The phases are tiers of ambition; the patterns are how they combine.

**Why this is risky:**

- Today's safety story (`d7175ee` — deterministic parser-level rejection of strings/arrays/imports/member access) is the foundation. Any phase that imports a third-party math engine re-opens the surface that commit just closed. Mitigation: each new engine must come with its own deterministic static check before LLM-emitted input touches it.
- Tool routing (Pattern B) shifts the safety burden from one parser to one parser per tool. More surfaces, more rejection paths. The `sandbox_reject` counter in [`LlmCallPath`](../src/main/java/com/emergentterrain/research/UniversityService.java) generalises to per-tool counters; without that, regressions become invisible.
- "Cross-tool builtins" (Pattern C) smuggles foreign-engine surface area into the sandbox's boundary. Tempting because it's ergonomic — `integrate(...)` just works — but the *bridge* itself needs the same static-check treatment the sandbox got in `d7175ee`.

---

## Composition patterns

Phases 1–4 below are **tiers of capability**. Patterns A/B/C are **how they compose**. The recommended actual sequence at the bottom of this doc is a mix.

### Pattern A — Stack tiers inside one engine
Phase 1 + Phase 2 both live inside [`SandboxEngine`](../src/main/java/com/emergentterrain/research/SandboxEngine.java). Reflective `Math.*` exposure (Phase 1) plus user-defined `function f(x) { ... }` (Phase 2) compose trivially — primitives plus the ability to build helpers from them.

### Pattern B — Tool router
[`MathToolSuite`](../src/main/java/com/emergentterrain/service/MathToolSuite.java) already exposes `run_sandboxed_code`. Add siblings, each with its own static checker:

| Tool | Engine | Use when |
|---|---|---|
| `run_sandboxed_code` | Augmented `SandboxEngine` (Phase 1+2) | Scalar numeric with control flow |
| `run_symbolic` | Symja (Phase 3) | Integration, derivatives, simplification, exact roots |
| `run_gpu_eval` | wgpu shader in existing Rust engine (Phase 4) | f(n) over huge ranges |

The LLM picks the tool. Each backend keeps its own rejection telemetry.

### Pattern C — Cross-tool builtins (hybrid execution)
The sandbox stays as the LLM's primary interface, but specific builtins delegate to other engines under the hood. Example: `integrate(expr, "x", 0, PI)` is a sandbox builtin whose implementation packages args and calls Symja. LLM never knows Symja exists; it just sees a richer builtin set.

**Risk:** the bridge smuggles foreign surface area into the sandbox's safety boundary. Each bridge builtin needs its own static checker (same pattern as today's `SandboxStaticChecker`).

---

## Phase 1 — Reflective `Math.*` exposure (1 day)

**What:** Replace the hardcoded `BUILTINS` set + `evalCall` switch in [SandboxEngine:30–39](../src/main/java/com/emergentterrain/research/SandboxEngine.java) and [SandboxEngine:478–509](../src/main/java/com/emergentterrain/research/SandboxEngine.java) with reflective dispatch against `java.lang.Math`:

```java
Method m = Math.class.getMethod(name, double.class /*, double.class*/);
return (double) m.invoke(null, args);
```

Plus a physics-constant registry seeded with `WIEN_B`, `STEFAN_BOLTZMANN_SIGMA`, `PLANCK_H`, `BOLTZMANN_K`, `SPEED_OF_LIGHT`, `AVOGADRO`, etc. (already-instantiated in `Interpreter.env` next to `PI`/`E` at [SandboxEngine:401–402](../src/main/java/com/emergentterrain/research/SandboxEngine.java)).

**What it unlocks:** ~50 math functions automatically — `log10`, `sinh`/`cosh`/`tanh`, `signum`, `cbrt`, `hypot`, `expm1`, `log1p`, `IEEEremainder`, etc. Plus the constants needed for [Phase 7 F3](phase-7-multi-provider-and-math-expansion.md) physics topics (Wien, Stefan-Boltzmann, Schrödinger).

**Safety unchanged:** the parser still rejects strings/arrays/imports at lex/parse time. Reflection target is `Math.class` only — no general method lookup.

**Also wire up existing helpers** in [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) that already exist but aren't whitelisted:
- `mobius` (line 1124)
- `sternBrocotDepthOf` (line 1145)
- `isSquarefree` (line 1158)
- `mertensPartialSum` (line 1117 — currently private)

These line up exactly with the topics added in commit `21e824c` (Mertens, Stern-Brocot, Squarefree) where the topic is registered but the underlying helper is unreachable from the sandbox.

**Risks:**
- `java.lang.Math` has a handful of methods that take/return non-double (`min(int,int)`, `abs(long)`). Dispatch must filter for `double` signatures only; otherwise reflection picks the wrong overload.
- Some `Math.*` methods may surprise with edge cases (`Math.floorMod` on negatives, `Math.IEEEremainder` semantics). Decide explicitly which to expose; consider a small denylist.

**Done when:** corpus run produces zero `Unknown function:` errors on standard transcendentals; physics topics that depended on hardcoded constants now reference them by name.

---

## Phase 2 — User-defined functions in the DSL (2–3 days)

**What:** Extend `SandboxEngine`'s parser to accept top-level function definitions:

```js
function binomial(n, k) {
    if (k < 0 || k > n) return 0;
    var result = 1;
    for (var i = 0; i < k; i++) {
        result = result * (n - i) / (i + 1);
    }
    return result;
}

return binomial(start, param1);
```

Add a `FunctionDef` statement type, a function table in `Interpreter`, and recursive call handling with a depth cap (e.g. 64) to prevent runaway recursion.

**What it unlocks:** LLM builds its own helpers from primitives. No external library, no whitelist to maintain. Combined with Phase 1, narrators can express:
- Bessel functions via recurrence
- Continued fractions
- Newton-Raphson / bisection as named helpers (not inlined)
- Riemann zeta partial sums, Dirichlet series
- Numerical integration (trapezoid, Simpson) over a user-defined `f(x)`

**Safety:**
- Existing iteration/statement caps already bound runaway execution
- Add an explicit recursion-depth cap
- No closures over external state, no first-class functions — just named definitions with their own scope

**Risks:**
- A function defined as `function f(n) { return f(n+1) + 1; }` will blow the recursion cap. Must surface clearly in the `sandbox_reject` reason (today's failure messages from `d7175ee` are the model).
- Parser change is non-trivial — `function` becomes a reserved word. Must check no existing topic prompt uses `function` as an identifier.

**Done when:** at least one corpus paper successfully defines and uses a multi-line helper that today's sandbox can't express inline.

---

## Phase 3 — Embed Symja for symbolic math (1–2 weeks)

**What:** Add a new tool `run_symbolic` to [MathToolSuite](../src/main/java/com/emergentterrain/service/MathToolSuite.java), backed by [Symja](https://github.com/axkr/symja_android_library). The LLM emits Mathematica-like syntax:

```
Integrate[Sin[x]^2, {x, 0, Pi}]
D[x^3 + 2*x, x]
Solve[x^2 - 5*x + 6 == 0, x]
Series[Exp[x], {x, 0, 5}]
Simplify[Sin[x]^2 + Cos[x]^2]
```

Symja is pure-Java, **LGPL-v3 licensed** (this doc previously claimed MIT — corrected during P3.1), no native dependencies — drops cleanly into the existing Maven build. LGPL is compatible with internal/SaaS deployment of EmergentTerrain (the "convey" trigger doesn't fire without binary distribution); if you ever ship binaries, you must comply with LGPL's relinking and notice obligations. Transitive footprint ~18 MB on disk (matheclipse-core, matheclipse-parser, matheclipse-external, apfloat, eight Hipparchus modules, jgrapht-io, Guava).

**What it unlocks:** *Symbolic* verification of derivations. Today's sandbox is numeric; Symja can verify that the LLM's claimed derivative is actually correct, that two expressions are equivalent after simplification, that an integral has a known closed form. This is qualitatively different from numeric sandboxing and is the right tool for the [Phase 7 F3.4](phase-7-multi-provider-and-math-expansion.md) MATHEMATICS topics (Riemann, divisor sum) which today rely on numeric approximation alone.

**Safety:**
- Symja has a `F.setSecurityMode(true)` that disables file/system functions. **Must be enabled before any LLM input is parsed.**
- Need a static checker analogous to [SandboxStaticChecker](../src/main/java/com/emergentterrain/research/SandboxStaticChecker.java) that rejects Symja heads it shouldn't accept: `Import`, `Export`, `Run`, `URLFetch`, `OpenRead`, etc. **Whitelist heads, don't blacklist** — same lesson as `d7175ee`.
- Time/memory cap via `EvalEngine.setIterationLimit` and a wall-clock timeout on the evaluator thread.

**Risks:**
- Symja's evaluator can hang on unconstrained symbolic loops (`Sum[k, {k, 1, Infinity}]` without convergence). Wall-clock cap is mandatory.
- Symja errors are not deterministic across versions; pin to one Symja release and add it to the dependency lockfile.
- The output is an `IExpr` tree, not a number. Persistence layer needs to decide: store the LaTeX form? The simplified string? Both? Affects [NumericalConsistencyValidator](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java).

**Done when:** at least one PUBLISHED paper contains a symbolic-derivation block that was verified, not just numerically sampled.

---

## Phase 4 — JIT to GPU (research project)

**What:** Add `run_gpu_eval` as a third tool in [MathToolSuite](../src/main/java/com/emergentterrain/service/MathToolSuite.java). The LLM provides an expression `f(n)` and a range `[start, end)`; the tool compiles the expression to a WGSL compute shader and dispatches it on the existing [Rust GPU engine](../../EmergentTerrainGPUEngine) over gRPC at `127.0.0.1:50051` (per [AGENTS.md:56](../AGENTS.md)).

Two real implementation paths:
- **Runtime shader codegen via `naga`** in the existing Rust engine. Translate the `SandboxEngine` AST → WGSL → wgpu dispatch. Stays consistent with the current GPU architecture (Vulkan/wgpu, AMD-friendly).
- **TornadoVM** (Java→GPU JIT). Works on AMD via OpenCL. Mature but adds a heavy JVM agent and a second GPU stack alongside the Rust engine.

**Recommended path:** the first — extend the existing Rust engine. One GPU stack in the repo, not two. The new gRPC method might be `EvalExpr(formula_str, start, end) -> {mean, std, min, max, n}` mirroring [SandboxEngine.evalExpr at line 60](../src/main/java/com/emergentterrain/research/SandboxEngine.java).

**What it unlocks:** batch formula evaluation at millions-of-n scale. Use cases:
- Monte Carlo estimates of integrals
- High-resolution lattice sweeps for stat-mech topics
- Prime-density / number-theory statistics over `[1, 10^7]` ranges that today's `MAX_STMTS = 50_000` cap forbids

**Safety:** the formula language is a *strict subset* of `SandboxEngine`'s — no control flow, no function defs, just expressions over `n` and parameters. Smaller surface than the sandbox itself.

**Risks:**
- WGSL has no `i64`; large-range counters need careful framing
- Floating-point determinism across CPU and GPU is *not* guaranteed. The Call Path comparison between `run_sandboxed_code` (CPU) and `run_gpu_eval` (GPU) outputs must allow small epsilon
- gRPC dispatch overhead is ~ms — the tool description must make clear this is for `end - start ≫ 10⁴`, not for short ranges

**Done when:** at least one corpus paper validates a numerical claim against a GPU-evaluated range that the CPU sandbox literally couldn't reach within `MAX_STMTS`.

**Only ship this if** corpus telemetry from Phases 1–3 actually shows formula evaluation over big ranges as a bottleneck. Currently it isn't.

---

## Recommended actual sequence (the mix)

1. **Phase 1** — one PR, no new dependencies, immediate ~10× expressive range. Lands the physics constants needed by [Phase 7 F3](phase-7-multi-provider-and-math-expansion.md). Highest signal-to-effort ratio on the board.

2. **Phase 2** — function definitions inside the sandbox. Combined with Phase 1, the LLM can express almost any closed-form classical-physics or number-theory formula without a single external dependency.

3. **Phase 3** — `run_symbolic` as a *second tool*, fully isolated behind its own tool boundary. Pattern B (tool router) takes precedence over Pattern C (hybrid bridges) here: explicit tools keep the Call Path observable; implicit fallbacks are unobservable and hard to budget.

4. **Phase 3 → Pattern C bridges** (optional, only after Phase 3 has bake time) — promote the *most-used* symbolic operations (`integrate`, `differentiate`, `simplify`) to sandbox builtins. Each gets its own static checker. Adds ergonomics without removing the explicit tool option.

5. **Phase 4** — only if a corpus run shows actual range-evaluation bottleneck. Don't pre-build GPU capability for a need that doesn't exist.

**The thing to avoid:** implicit cascading evaluation ("try sandbox, fall back to Symja, fall back to GPU"). Implicit routing is unobservable and hard to budget. Explicit tools + clear descriptions let the LLM be the router, and the `sandbox_reject` / `symbolic_reject` / `gpu_reject` counters stay clean.

---

## Decision points to revisit before starting

- **`sandbox_reject` corpus telemetry** must show which functions narrators actually reach for. Phase 1's denylist/allowlist of `Math.*` methods should be data-driven, not speculative.
- **Should `Interpreter.env` constants be configurable per topic?** A `MATHEMATICS` topic doesn't need `STEFAN_BOLTZMANN_SIGMA`; a `PHYSICS` topic doesn't need `MOBIUS_THRESHOLD`. Per-topic constant sets keep the LLM's prompt context lean.
- **Symja licensing review.** LGPL-v3 (this doc originally said MIT — incorrect; verified via the matheclipse-core POM during P3.1, 2026-05-13). Transitive deps include Apache 2.0 (Hipparchus, Guava) — compatible. For internal/SaaS use the LGPL's "convey" trigger does not fire; for binary distribution the relinking + notice obligations apply.
- **Do we want Symja's `Compile[]`?** Symja can compile expressions to bytecode/JVM. This is a *third* execution path on top of (a) sandbox and (b) Symja interpreter — worth knowing exists, probably not worth using until Phase 4 forces the question.
