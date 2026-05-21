# Phase 22 — Julia Math Sidecar

**Status (2026-05-20):** Scoping doc. No code yet. Motivated by the
2026-05-20e paper-review refresh, which showed ~40% of failures are
math-engine-shaped (sandbox grammar/limit rejects, symbolic-vs-numeric
routing confusion, wrong-domain tool execution). The hypothesis: a more
capable, less restricted math engine reduces that fraction substantially.

**App naming.** The Julia component is generic — its protocol (JSON over
HTTP, `eval` endpoint, `nsolve`/`quadgk`/`solve` endpoints) is not
EmergentTerrain-specific and could serve any caller. **Proposed name:
`julia-math-sidecar`.** A separate top-level repository / directory peer
to `EmergentTerrain` and `EmergentTerrainGPUEngine`. If the operator
prefers a project-prefixed name, `emergent-julia-math` is the fallback.

EmergentTerrain's bindings to it (the Spring beans, the
`MathToolSuite.runJuliaSymbolic` / `runJuliaNumeric` methods, the
TokenAccountant integration, the safety filter on outbound code) stay
inside this repo and reference the sidecar by URL.

**This doc tracks the integration work from EmergentTerrain's side; the
sidecar's own README will describe its protocol and capabilities.**

---

## Scope

What the sidecar replaces in EmergentTerrain:

| Today (in-process Symja) | Tomorrow (Julia sidecar) |
|---|---|
| `MathToolSuite.runSymbolic(...)` → Symja `EvalEngine.evaluate(...)` | HTTP POST to `julia-math-sidecar/eval` with Mathematica-style expression |
| `MathToolSuite.evaluateSymbolic(...)` over a range | HTTP POST to `/sweep` with `expression`, `var`, `start`, `end`, `samples` |
| `ECHO_PRONE_HEADS` retry-with-`N[…]` plumbing | Removed — Julia's `nsolve` is the natural fallback when `solve` is symbolic-only |
| `^` → `**` rewrite in `run_gpu_eval` | Removed for the Julia path — `^` is native Julia syntax |
| Sandbox grammar rewrites (`.push`, `.length`, etc.) | Removed for the Julia path; sandbox stays for the JS-style code tool |
| `Module/With/Block/CompoundExpression` admission | Removed — Julia has native scoping (`function`/`let`/`begin...end`) |

What the sidecar **does not** replace:

- The JS-style sandboxed code tool (`run_sandboxed_code`). That stays in
  EmergentTerrain because (a) operator code in the sandbox is what the
  LLM emits when it wants procedural logic the symbolic engine can't
  express, (b) the existing static-checker provides defense-in-depth
  against the JS surface. Julia eval is a peer to symbolic eval, not to
  arbitrary procedural code.
- The GPU eval tool (`run_gpu_eval`). The Rust-backed
  `EmergentTerrainGPUEngine` runs large-batch numerical sweeps; Julia
  is for ad-hoc symbolic/numeric evaluation up to a few hundred ms per
  call.

---

## Architecture

```
EmergentTerrain (Spring Boot, JVM)
├── MathToolSuite
│   ├── run_sandboxed_code   → JS-style sandbox (existing, unchanged)
│   ├── evaluate_symbolic    → JuliaMathClient over HTTP
│   ├── run_symbolic         → JuliaMathClient over HTTP
│   └── run_gpu_eval         → GPU engine (existing, unchanged)
└── JuliaMathClient
    ├── http.send(POST julia-math-sidecar/eval, {expr, timeoutMs})
    ├── safety filter (reject Pkg./run(/include(/eval(/Base.run/Sys./read(/write(/open(/`...`)
    ├── retry with N[…]-equivalent on echo (Julia: simplify→nsolve)
    └── per-call tokens accounting via TokenAccountant

julia-math-sidecar (Julia 1.10+, separate process)
├── HTTP.jl server on configurable port (default 8088)
├── POST /eval            → arbitrary expression, returns JSON {result, type, ok}
├── POST /sweep           → expression + range, returns stats {mean, std, min, max, samples}
├── POST /solve           → equation + variable, returns roots (symbolic if available, else numeric)
├── POST /nsolve          → numeric root from initial guess
├── POST /integrate       → symbolic integrate via Symbolics.jl + SymPy.jl fallback
├── GET /healthz          → readiness probe
├── GET /metrics          → call counts, avg latency, current JIT-warm modules
└── packages: Symbolics.jl, SymPy.jl (fallback), Roots.jl, QuadGK.jl,
              DifferentialEquations.jl, JSON3.jl, HTTP.jl
```

**Process lifecycle.** The sidecar is a long-lived child process,
started by a systemd unit (production) or by a `RUN.md` step
(development). EmergentTerrain detects it via `GET /healthz` at startup;
if absent, `JuliaMathClient` is wired with `enabled=false` and the
existing Symja path stays the default for the affected tools. **The
sidecar is opt-in, not a hard dependency.**

**Communication.** HTTP/1.1 JSON over localhost. Per-call timeout
configurable (`app.julia.timeoutMs`, default 5000). The HTTP overhead
(~5-20 ms localhost) is invisible against the multi-second LLM calls.

---

## Phasing

### 22.1 — sidecar bootstrap (this repo: zero changes) — **LANDED 2026-05-20**

Bring up `julia-math-sidecar` as a separate repo / directory with the
HTTP server, packages, basic endpoints (`/healthz`, `/eval`, `/solve`,
`/nsolve`, `/integrate`, `/sweep`, `/metrics`). Verify it runs standalone
with `curl` smoke tests against a fixture script. **Effort: ~2 days.**

**Acceptance:** `curl -X POST localhost:8088/eval -d
'{"expr":"floor(2.04e-6 / 400e-9)"}'` returns
`{"ok":true,"result":"5.0","type":"Float64",...}` in under 100 ms after JIT
warmup. (Note: native Julia syntax `floor(...)` rather than Mathematica
syntax `Floor[...]` — see Open Question 2.)

**Status (2026-05-20):** SHIPPED. Located at
`/opt/apps/ide/eclipse/workspace-web/julia-math-sidecar/` (sibling to
EmergentTerrain). Source files:

- `Project.toml` — Julia package manifest (Symbolics.jl, Roots.jl,
  QuadGK.jl, HTTP.jl, JSON3.jl)
- `src/JuliaMathSidecar.jl` — main module
- `src/server.jl` — HTTP route table + server loop
- `src/handlers.jl` — endpoint implementations
- `src/safety.jl` — substring-based code safety filter
- `src/metrics.jl` — per-endpoint call counts + latency
- `scripts/start.sh` — convenience launcher
- `systemd/julia-math-sidecar.service` — production unit with hardening
  directives
- `test/runtests.jl` — unit + HTTP integration tests
- `test/corpus_fixtures.jl` — **regression cases derived from real
  EmergentTerrain papers**: each fixture names the paper ID it came
  from and tests the Julia equivalent of the Mathematica/Symja
  expression the LLM emitted. Covers P-756 (Bragg floor), P-785
  (Bragg arcsin), P-751 (Schrödinger ground state), P-693 (TIR
  inverted indices → NaN), P-767 (Kepler symbolic solve), P-727
  (pendulum series), P-721/P-723 (abundant density / divisor-sum
  asymptotic), P-732 (degenerate parens handling), plus safety-filter
  cases and a timeout test.
- `README.md` — install, run, protocol documentation

**Wired into EmergentTerrain operator docs:** `STARTUP.md` §4.5 (full
install + run + verify), `RUN.md` §3 (terse copy-paste). Both note that
EmergentTerrain doesn't yet *use* the sidecar — that lands in 22.2.

**Not committed to EmergentTerrain's git tree** — the sidecar is a
peer top-level directory, not a submodule. The user can `git init`
it as its own repo if desired.

### 22.2 — `JuliaMathClient` Spring bean (this repo) — **LANDED 2026-05-20**

Add `src/main/java/com/emergentterrain/llm/JuliaMathClient.java` —
`@Service`, autowired `HttpClient`, configured via `app.julia.enabled`
(default `false`), `app.julia.baseUrl` (default
`http://localhost:8088`), `app.julia.timeoutMs` (default 5000), and
`app.julia.healthProbeTimeoutMs` (default 2000). Optional dependency
from `MathToolSuite` — when present and `isAvailable()` (enabled +
`/healthz` succeeded at @PostConstruct), `runSymbolic` and
`evaluateSymbolic` delegate to the sidecar; on any `JuliaMathException`
the call falls through to the existing Symja / SandboxEngine path
unchanged.

**Status (2026-05-20):** SHIPPED. Files added/modified:

- `src/main/java/com/emergentterrain/llm/JuliaMathClient.java` — new,
  5 endpoint methods (`eval`, `solve`, `nsolve`, `integrate`,
  `integrateSymbolicLimits`, `sweep`), 5 result records,
  reachability tracking via `AtomicBoolean` + `probeHealth()`.
- `src/main/java/com/emergentterrain/llm/JuliaMathException.java` —
  new, typed runtime exception with `errorCode()` accessor preserving
  the sidecar's response code (`safety_rejected`, `parse_error`,
  `timeout`, `eval_error`, `bad_request`, `transport`).
- `src/main/resources/application.yml` — new `app.julia.*` config
  block right after `app.e2e` (default `enabled: false`).
- `src/main/java/com/emergentterrain/service/MathToolSuite.java` —
  new 4-arg constructor takes the optional client; back-compat 2-arg
  and 3-arg constructors delegate with null. `runSymbolic` and
  `evaluateSymbolic` try the Julia path first when the client is
  available; fall through to existing Symja / SandboxEngine on
  `JuliaMathException`.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — autowires the `JuliaMathClient` (`required=false`) and passes it
  to the `MathToolSuite` constructor alongside `gpuEngineAdapter`.
- `src/test/java/com/emergentterrain/llm/JuliaMathClientTest.java` —
  14 tests using an in-process `com.sun.net.httpserver.HttpServer`
  stub. Covers disabled/enabled probing, /eval happy path + safety
  reject + timeout + transport failure, /solve symbolic-failed branch,
  /nsolve, /integrate, /integrate with symbolic limits, /sweep. All
  pass on JDK 25 in 1.9s.

**Acceptance:** all 14 unit tests pass; production wiring routes
correctly through ResearchNarrationService → MathToolSuite. Live
integration acceptance (real sidecar round-trip) is the integration
test we'll add next: hits a real sidecar at `localhost:8088` when
present, skips when unreachable.

**Important non-promise:** flipping `app.julia.enabled=true` today
will route the existing Mathematica-syntax inputs to a Julia parser
that mostly rejects them. The `JuliaMathException` fall-through means
Symja still serves every call (safely) — but the Julia path is
exercising only the safety-reject branch. Phase 22.4 will re-prompt
the LLM to emit Julia syntax; until then, leaving `enabled=false` is
the recommended default.

### 22.3 — Safety filter on outbound code — **LANDED 2026-05-20**

Static analysis of the expression before it leaves
`JuliaMathClient.send()`. Rejects any of:
- `using Pkg`, `Pkg.add`, `Pkg.update`
- `run(`, backtick command `\`...\``
- `include(`, `Base.include`, `eval(`, `@eval`
- `Sys.`, `read(`, `write(`, `open(`, `download(`
- `unsafe_`, `ccall(`, `@ccall`
- Module-loading: `import Base.run`, `import Base.Sys`
- Process control: `exit(`, `Base.exit`, `kill(`

This is necessary because Julia is Turing-complete. The list is
non-exhaustive; the actual defense is process isolation:

**Process isolation** (deployment requirement): the sidecar runs in a
Docker container or systemd-confined service with:
- Read-only root filesystem
- No network egress (only localhost ingress on the HTTP port)
- Memory limit ~512 MB
- CPU quota
- No `/dev` access beyond `/dev/null`, `/dev/urandom`

**Effort: ~1 day for the Java filter; ~½ day for the systemd unit.**

**Status (2026-05-20):** SHIPPED. Files added/modified:

- `src/main/java/com/emergentterrain/llm/JuliaSafetyFilter.java` —
  new package-private class with a list of forbidden substring
  patterns mirroring `julia-math-sidecar/src/safety.jl` line-by-line.
  Comment block above each pattern documents *why* the token is
  unsafe so auditors don't need Julia docs to read the list.
- `src/main/java/com/emergentterrain/llm/JuliaMathClient.java` —
  `post()` now runs every string-valued body field through
  `JuliaSafetyFilter.firstForbiddenPattern()` before HTTP. On match,
  throws `JuliaMathException` with error code
  `client_safety_rejected` (distinct from the server-side
  `safety_rejected`) so logs and tests can tell the two paths apart.
  Adds a `var`-identifier regex check (must match
  `[A-Za-z_][A-Za-z0-9_]*`) so non-identifier `var` values don't
  produce opaque server-side parse errors.
- `src/test/java/com/emergentterrain/llm/JuliaSafetyFilterTest.java` —
  12 cases: rejects Pkg / shell / FS / module-loading / FFI / process
  control / env access / direct eval, accepts ordinary math
  including common library names that could superficially trip a
  prefix matcher (`minimum`, `maximum`, `sum`, `prod`, etc.).
- `src/test/java/com/emergentterrain/llm/JuliaMathClientTest.java` —
  +4 cases on the client-side filter wiring: confirms the filter
  rejects BEFORE HTTP (stub server never sees the request),
  validates `var` identifier shape, accepts valid identifiers,
  rejects forbidden tokens in symbolic limits.
- `julia-math-sidecar/Dockerfile` — new, two-stage build. `builder`
  runs `Pkg.instantiate` + `Pkg.precompile`; `runtime` ships only
  the depot + source, drops to non-root `julia-math` user, exposes
  port 8088 with a Docker HEALTHCHECK probe. `.dockerignore` keeps
  the build context lean.
- `julia-math-sidecar/README.md` — Docker section rewritten with
  the actual hardened `docker run` invocation
  (`--read-only --tmpfs /tmp --memory=512m --cpus=2 --cap-drop=ALL
  --security-opt=no-new-privileges`).

The pre-existing systemd unit (shipped in 22.1) already covers the
"process isolation" half of this phase — file system / network /
capability / syscall confinement directives are in place. The
Dockerfile is the alternative deployment path for sites that prefer
container runtimes over systemd.

**Acceptance:** all 37 Java tests pass (12 filter + 18 client + 7
live integration). Live integration runs against the actual sidecar
on `localhost:8088` and confirms the client-side filter catches
forbidden tokens before the HTTP request leaves the JVM.

### 22.4 — Dual-syntax tool descriptions + echo migration — **LANDED 2026-05-20**

Original 22.4 scope was narrower (remove `ECHO_PRONE_HEADS` when
Julia is active). On reflection during 22.2/22.3 implementation, the
larger blocker for `app.julia.enabled=true` becoming useful was that
the tool descriptions still ask the LLM for Mathematica syntax —
which Julia's parser doesn't accept. Expanded 22.4 to cover both:

1. **Dual-syntax tool descriptions** in `MathToolSuite.toolsJson()`.
   `run_symbolic` and `evaluate_symbolic` now have two description
   variants each — one Mathematica/Symja-flavoured (the existing
   default), one native-Julia. The variant served is selected at
   call time based on `juliaMathClient != null && isAvailable()`.
   When Julia is unreachable or disabled, the Symja description is
   served so the LLM emits inputs the fallback path handles.
2. **Echo detection** stays in the Symja fallback. The Julia path
   doesn't need it — `Symbolics.symbolic_solve` returns `nothing`
   (caught by the `/solve` handler as `symbolic_failed`) rather than
   echoing the input. `/nsolve` is the natural follow-up call shape.
   Since the existing code structure tries Julia first and falls
   through on `JuliaMathException`, the Symja echo-detection logic
   only runs when Symja is actually the back-end. No change needed —
   the existing fall-through pattern handles this correctly.

The Symja `ECHO_PRONE_HEADS` (`Solve`, `Reduce`, `Roots`,
`FindRoot`, `DSolve`, `NSolve`, `Integrate`, `Sum`, `Product`)
retry-with-`N[…]` plumbing stays in place; it only fires on the
Symja path. **Effort: ½ day (descriptions); 0 days (echo detection
— architecture handled it for free).**

**Status (2026-05-20):** SHIPPED. Files added/modified:

- `src/main/java/com/emergentterrain/service/MathToolSuite.java` —
  new constants `RUN_SYMBOLIC_DESC_SYMJA`, `RUN_SYMBOLIC_DESC_JULIA`,
  `EVALUATE_SYMBOLIC_DESC_SYMJA`, `EVALUATE_SYMBOLIC_DESC_JULIA`.
  New `useJuliaDescriptions()` predicate and `jsonEncode()` helper
  for safely interpolating the long descriptions into the JSON
  text block.
- `src/test/java/com/emergentterrain/service/MathToolSuiteToolDescriptionTest.java`
  — new, 7 cases: null Julia client serves Symja descriptions,
  disabled client serves Symja, available client serves Julia,
  `evaluate_symbolic` description swaps too (with native `^` not
  `**`), every combination produces valid parse-clean JSON,
  `jsonEncode` escapes correctly.

**Acceptance:** all 44 Julia-related tests pass (12 filter + 18
client + 7 live integration + 7 description). The Julia descriptions
include corpus-relevant examples (Bragg floor, pendulum series,
abundant density) so when `app.julia.enabled=true` the LLM has
working templates for the exact shapes that gave Symja trouble in
the recent corpus.

### 22.5 — Sandbox-limit relaxation for Julia path — **LANDED 2026-05-20**

The current sandbox `statement_limit` / `loop_limit` thresholds were
sized for the JS-style sandbox. For the Julia path, the integration
loop runs server-side in JIT-compiled Julia — no statement counting
needed. Wire a separate ceiling (`app.julia.maxEvalMs`, default 5000)
that the sidecar itself enforces via `Threads.@spawn` + `wait` with
timeout.

P-764's projectile-RK4 archetype (4 of 5 sandbox calls killed by
limits) should succeed end-to-end under the Julia path.

**Status (2026-05-20):** SHIPPED. Two relaxations:

1. **`evaluate_symbolic` range cap.** SandboxEngine kept its
   historic 1000-element ceiling (bytecode interpreter is too slow
   for more). The Julia path gets `MAX_SYMBOLIC_RANGE_JULIA =
   100_000` — sized empirically to fit inside the 5-second per-call
   timeout with 30× margin. The active cap swaps at call time based
   on `useJuliaDescriptions()`, so a sidecar going offline mid-paper
   snaps back to the 1000 cap automatically. Tool description JSON
   advertises the correct ceiling to the LLM.
2. **Per-call timeout.** Already handled by the
   `app.julia.timeoutMs` knob landed in 22.2 (default 5000ms, max
   30s). The sidecar's `timed_evaluate` wrapper enforces this via
   `Threads.@spawn` + `sleep` race. No extra work here.
3. **Adaptive sample count.** When the LLM asks for a tight range,
   the sidecar's `/sweep` endpoint now receives one sample per
   integer (capped at 100_000) instead of always sub-sampling at
   100. Gives the LLM honest "evaluated every n in [a,b)" semantics.

P-764's projectile-RK4 case won't route through `evaluate_symbolic`
or `run_symbolic` — it's `run_sandboxed_code` shaped. The Phase 22
design explicitly keeps the JS-style sandbox in EmergentTerrain
(see "What this phase deliberately does NOT include"). The
acceptance argument for P-764 instead is: with the relaxed range
cap on `evaluate_symbolic`, the LLM can reformulate the projectile
sweep as a Julia comprehension fed through `run_symbolic` —
`(let v0=233, k=0.037, g=9.81; minimum(angle for angle in 5:0.5:45 if
range_at(angle) < target end) end)` and similar shapes. Real
acceptance lives in Phase 22.7.

**Files modified:**

- `src/main/java/com/emergentterrain/service/MathToolSuite.java`:
  - `MAX_SYMBOLIC_RANGE_JULIA = 100_000` constant added.
  - `evaluateSymbolic` runtime check now uses
    `useJuliaDescriptions() ? MAX_SYMBOLIC_RANGE_JULIA : MAX_SYMBOLIC_RANGE`.
  - `/sweep` call passes `samples = clamp(rangeSize, 100, 100_000)`
    instead of always 100.
  - `EVALUATE_SYMBOLIC_DESC_JULIA` updated to explain the ceiling.
  - Tool schema JSON now interpolates the range-cap string so the
    LLM sees the actual ceiling at the schema level.
- `src/test/java/com/emergentterrain/service/MathToolSuiteToolDescriptionTest.java`:
  - 3 new tests: Symja schema advertises 1000 cap, Julia schema
    advertises 100,000 cap, Julia description explains JIT.

### 22.6 — TokenAccountant integration

The sidecar doesn't consume LLM tokens, but it does consume **compute
budget** — the Spring side should be able to see "Julia: 42 calls,
avg 35ms, max 4900ms" in the 5-minute tier-reporter heartbeat.

Add a third "tier" to `TokenTierReporter`: `julia` (alongside `primary`
and `fast`). Or — cleaner — record the sidecar in a separate
`SidecarCallReporter` that mirrors the existing reporter's pattern but
counts compute time instead of tokens.

**Effort: 1 day; defer if the basic integration is working.**

### 22.7 — Acceptance + rollout

End-to-end acceptance against `research/a3289005/` after the sidecar is
wired:

1. Re-run P-a328-764 (projectile drag v=233). Expect the Julia path to
   succeed with a non-zero `bestAngle` in single-digit ms.
2. Re-run P-a328-778 (prime density [80, 130)). Expect Julia to compute
   the actual prime density via `count(isprime, 80:129) / 50` instead
   of an unrelated Wilson CI.
3. Re-run P-a328-767 (Kepler T²/a³). Expect Julia to either solve
   symbolically AND numerically sample the ratio for orbits near 2.35 AU.
4. Verify the JS-style `run_sandboxed_code` tool still works for papers
   the LLM chose to route there (no regression on the existing path).

**Acceptance bar:** post-sidecar batch shows acceptance rate ≥ 50%
(roughly double the current 22.2%) **or** at least 3 papers reach Q1
(publishable: math right + complete prose + coherent topic). Either
condition triggers full rollout.

---

## Open architecture questions

1. **Hot-reload vs cold-start.** Julia's JIT warmup is ~200 ms cold but
   sub-ms warm. The sidecar needs to either (a) precompile a `PackageCompiler.jl`
   sysimage at build time (fast cold-start, larger binary) or
   (b) accept warm-up latency on the first call of each shape. Lean toward
   (a) for production, (b) for dev.

2. **Mathematica syntax vs native Julia syntax.** The corpus's LLM
   already emits Mathematica syntax (`Solve[..., var]`, `Integrate[...]`)
   because that's what `run_symbolic` advertises. Two options:
   - **Translate at the sidecar** — wrap incoming expressions with
     `SymPy.parse_mathematica` (via SymPy.jl) before evaluation.
   - **Re-prompt the LLM to emit Julia syntax** — change the tool's
     description to say "Julia expression: `solve(...)` not `Solve[...]`".

   Lean toward the latter: cleaner long-term, the LLM is well-trained on
   Julia syntax, and removing the Mathematica translation layer cuts a
   class of parsing bugs.

3. **What if SymPy.jl is needed for hard integrals?** Symbolics.jl
   doesn't implement Risch; SymPy does. SymPy.jl uses PyCall under the
   hood — adds a Python runtime to the sidecar (~100 MB). Pros: best
   symbolic integration available. Cons: heavier container, two languages
   to maintain. Resolution: ship without SymPy.jl initially; add it only
   if the corpus shows ≥5 papers failing on hard integrals that
   Symbolics.jl couldn't crack.

4. **Restart-without-corpus-loss.** Today's design has no state to lose —
   the sidecar holds JIT-compiled functions, not data. A restart costs
   ~200 ms × N first-call shapes. Acceptable for now; revisit if JIT
   warmup tail latency starts gating throughput.

5. **Should this also replace `run_gpu_eval`?** No — the GPU engine
   serves a different scale (millions of samples per call). The Julia
   sidecar serves the few-hundred-sample-per-call regime. Keep them
   separate.

---

## Acceptance for closing this phase

This doc moves to `archive/` when:

1. ✅ 22.1–22.5 land in commits and tests pass.
2. ✅ A REVIEW_REPORT.md refresh shows acceptance rate ≥ 50% on at least
   one corpus run after the Julia path goes live.
3. ✅ A reproducibility test pins P-764-shape projectile, P-778-shape
   prime density, and P-767-shape Kepler running cleanly through Julia.

If 22.6 / 22.7 are deferred indefinitely (e.g. operator decides the
Java/Symja path is good enough after the upstream prompt-routing fixes
land), this doc gets a closing note with that decision and moves to
`archive/` anyway.

---

## What this phase deliberately does NOT include

- **Replacing Symja with SymPy in-process.** The math-engine analysis
  (Q in this session) ruled this out — SymPy is Python, in-process via
  JEP is operationally painful, and the math improvement over Symja for
  EmergentTerrain's actual call shapes is marginal vs. the ~5 days of
  work. Julia is the better lateral move.
- **A unified math abstraction layer in the JVM.** Tempting to wrap
  Symja AND Julia AND GPU behind a common `MathEngine` interface. Don't
  — they have genuinely different semantics (symbolic, numeric/symbolic
  hybrid, raw GPU). The current `MathToolSuite` tool dispatch is the
  right granularity.
- **A Mathematica → Julia transpiler.** See Open Question 2. The
  prompt-re-train path is cheaper.

---

## Cost / benefit summary

**Total effort:** ~7 days focused work for 22.1–22.5; +2 days for 22.6;
+1 day for full acceptance verification = **~10 days end-to-end** to
production.

**Operational tax:** another runtime (Julia ~200 MB RSS, ~50 MB on disk
with PackageCompiler sysimage). One more thing to monitor (`/healthz`
probe). One more service to upgrade when security advisories land.

**Expected impact (per the 2026-05-20e batch breakdown):**
- ~30% of papers fail on sandbox grammar/limit issues today → Julia
  removes the syntax-translation surface entirely. Estimated drop to
  ~5% (sandbox stays for JS-style code, but it's a much narrower call
  surface).
- ~10% of papers fail on echo / Symja-can't-crack-it → Julia's
  symbolic+numeric fallback is cleaner. Estimated drop to ~3%.
- ~60% of papers fail on narration / prompt / panel issues → unchanged.
  Julia doesn't touch this surface.

**Expected acceptance rate after sidecar lands:** 35-50% (from current
22.2%). Not a 2× improvement on its own but compounding with the four
other 2026-05-20 patches it could clear 50%.

**Recommendation:** schedule 22.1 (sidecar bootstrap) for next available
focused 2-day block. Defer 22.2–22.7 until 22.1 demonstrates the basic
shape works. If the upstream prompt-routing fixes from the open
recommendations (P-778 wrong-domain, P-767 symbolic-vs-numeric) close
the gap on their own, 22.2+ may not be needed.
