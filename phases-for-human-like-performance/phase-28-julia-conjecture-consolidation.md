# Phase 28 — Julia Sidecar Consolidation of Conjecture + Math Compute

**Status (2026-05-23):** §28.1 → §28.5 SHIPPED, §28.6 PARTIAL (conjecture
flag flipped; physics-topic cutover seam landed and the **MATHEMATICS +
PHYSICS + MATERIALS + INTERDISCIPLINARY domains (38 topics) are now
cut-over-capable**, primary flag default OFF). Promoted from
`reference-conjecture-engines.md` §
"Decisions — Julia consolidation (2026-05-22)" Slice 1.

**§28.6 prose decision (2026-05-23): approach B — JVM composes from
sidecar `detail`.** The §28.6a/b split below offered two ways to get the
findings/hypothesis/equation prose into the cutover path. We chose **B**:
the sidecar emits *numeric* `detail` only (no prose), and the JVM composes
the prose from those numbers with its existing `String.format` templates.
This sidesteps the Java-`String.format`-vs-Julia-`@sprintf` byte-format
friction entirely — all formatting stays JVM-side and is byte-identical by
construction because the in-process path and the cutover path call the *same*
`compose*` method. Trade-off: `MathPhysicsEngine` shrinks to a prose-composer
rather than being deleted outright (the numeric body becomes dead once every
topic is cut over). Approach A (port prose into Julia) is shelved.

Sub-phase status:
- ✅ §28.1 — `/numbertheory/window` endpoint + SPF sieve port
- ✅ §28.2 — `MathConjectureSubstrateDelegate` + parity test
- ✅ §28.3 — `/conjecture/seed` + `/conjecture/update`
- ✅ §28.4 — `/conjecture/verify` batched endpoint + `ConjectureVerifier`
  delegate routing
- ✅ §28.5 — `/physics/topic` + `/predicate/scan`. Full coverage: all 9
  MathPhysicsEngine domains × ~5–10 topics each (63 ported topics)
  with bit-identical parity confirmed by 65/65
  `PhysicsTopicParityTest`. `MathPhysicsEngineDelegate` ships in
  shadow-read mode (default OFF); 5 callers
  (`ResearchCycleService`, `BotLifecycleService`, `ReplicationService`,
  `ResearchNarrationService`, `MathToolSuite`) wired through the seam.
  Live integration test (`MathPhysicsEngineDelegateIntegrationTest`)
  validates shadow-read parity counters against a running sidecar.
- ◐ §28.6 — partial cutover (2026-05-22). `app.julia.conjecture.enabled`
  flipped `false → true` ahead of the full N-day burn-in due to
  runtime-availability constraints. Justification: 19/19 parity tests
  green on the live sidecar (`numbertheory_window`,
  `conjecture_seed`/`update`/`verify`, `predicate_scan` all
  byte-identical), zero sidecar errors across ~179 phase-28 endpoint
  calls. Graceful fallback intact: `MathConjectureSubstrateDelegate`
  logs WARN and falls through to legacy on any sidecar exception.
  **Physics-topic read cutover seam landed (2026-05-23)** via
  approach B (`SidecarResultComposer` + `app.julia.physics.topic.primary`,
  default OFF) — the MATHEMATICS + PHYSICS + MATERIALS + INTERDISCIPLINARY
  domains (38 topics) are cut-over-capable; 25 composer branches remain
  across the other 5 domains (see §28.6a/b below). Legacy removal across all
  six ported classes remains deferred until prose-parity burn-in is clean.

**§28.6 physics-topic — wire-contract finding (2026-05-22).** An
attempt to implement primary-read cutover for `/physics/topic`
surfaced a blocker: the Julia handlers in `physics_*.jl` deliberately
return *scalars + ClaimStatus only* (per the wire-contract comment in
`physics_mathematics.jl`:11-13 — "the JVM caller composes the
findings / hypothesis / equation prose locally — avoids
byte-identical-format friction between Java String.format and Julia
@sprintf"). A flag-only flip from shadow-read to primary-read therefore
can't replace the in-process `MathPhysicsEngine.compute` call — the
sidecar response doesn't carry the findings prose the JVM
`ResearchResult` requires. Splitting §28.6 into two sub-phases:

- **§28.6a — sidecar `detail` completeness** (per-topic). Under
  approach B, the sidecar emits *no* prose — it must instead carry
  every numeric value the JVM prose template consumes in its
  `detail` map. **MATHEMATICS + PHYSICS done (topics 0–9 each):** for
  MATHEMATICS the audit added `wM2` (collatz/goldbach) and `happyM2`
  (happy) for the Welford `fmtMean`, `seedN` (divisor), and `sampleSweep`
  arrays for the sweep-embedding topics (mertens/riemann/stern-brocot).
  For PHYSICS the §28.5 port already carried the scalars; the audit added
  `sampleSweep` arrays for the five sweep-embedding topics (pendulum 16
  steps, VdW 6 pressures, diffraction 7 wavelengths, RLC 6 multipliers,
  schrödinger first-4) plus VdW's `tolerance`. Topics whose sweep is built
  but never embedded (math 6/9, physics 5/7/8/9) stay numeric-only. For
  MATERIALS the inner-window topics (0,2,3,4) needed no sidecar change —
  the JVM recomputes the cheap winning window from the emitted param; the
  six aggregate/single-method topics got `sampleSweep` arrays (sphere-
  packing strided, hall-petch/bragg/carnot/basquin/young-laplace first-4)
  plus a few seed params (bragg `seedDnm`, carnot `seedTc`/`seedTh`,
  young-laplace `seedRadiusMm`). For INTERDISCIPLINARY all 8 topics are
  single-method aggregate+sweep; each got a `sampleSweep` array plus the
  `param1`/`param2` their cross-domain prose formats. The remaining 25
  (domain, topicIdx) handlers across the other 5 domains need the same audit.
- **§28.6b — JVM `composeFromSidecar` cutover** — **seam SHIPPED
  (2026-05-23, MATHEMATICS + PHYSICS + MATERIALS + INTERDISCIPLINARY).** New `SidecarResultComposer`
  parses the sidecar `detail` JsonNode and calls the same shared
  `MathPhysicsEngine.compose*` method the in-process path uses (so
  prose is byte-identical), then stamps `equationFor`. New
  `app.julia.physics.topic.primary` flag (default OFF): when on,
  `MathPhysicsEngineDelegate.compute` returns the composed result
  for any topic the composer covers, while still computing
  in-process + recording scalar parity so the cutover is observable
  and reversible. Topics without a composer branch fall back
  in-process — the cutover lights up topic-by-topic. **Done for the
  MATHEMATICS + PHYSICS + MATERIALS + INTERDISCIPLINARY domains (38 topics);
  25 composer branches remain** across the other 5 domains (one per topic,
  mechanical: extract that topic's prose into a shared `compose*` method,
  add the `detail`-key branch, extend the parity tests). Three structural
  shapes recur and are now templated: inner-window prose (where the inner
  *At method is a cheap pure function of the winning param — recompute it,
  no sweep array; math 0–2, physics 0, materials 0/2/3/4), outer-overlay
  via `withFindingsAndClaim` (math 3,4), and aggregate + sample-sweep array
  (math 5–9, physics 1–9, materials 1/5–9, all of interdisc 0–7). Dropping
  the in-process call from the hot path (the actual perf win) is the final
  legacy-removal step, once prose-parity burn-in is clean.

Neither sub-phase is blocking burn-in — the existing shadow-read
mode already drives parity counters, and the conjecture-side flag
flip (above) is the genuinely operator-visible §28.6 step.

**Tests (2026-05-23).** `SidecarResultComposerTest` (42 cases, pure
JVM — one byte-identity case per covered topic across MATHEMATICS +
PHYSICS + MATERIALS (0–9 each) + INTERDISCIPLINARY (0–7), pinning the
`detail`-key + equation wiring against the shared template, plus fall-back /
null-detail / modulo-reduction cases; all green).
`MathPhysicsEngineDelegateTest` gains two primary-read cases (cutover
serves the composed result; uncovered topics fall back).
`MathPhysicsEngineDelegateIntegrationTest` asserts live-sidecar
full-`ResearchResult` byte-parity across every topic (by `topicCount`) of
MATHEMATICS + PHYSICS + MATERIALS + INTERDISCIPLINARY.
`MathPhysicsEngineRegressionTest` (15, no Mockito)
confirms every in-process `compose*` extraction is byte-identical. NB: the mock-based
delegate cases don't run on this dev shell (JDK-25 / Mockito-inline
can't mock `JuliaMathClient` — see [[project_dev_shell_jdk_state]]).

**Motivation.** The JVM currently hosts six conjecture / math
compute classes — `MathConjectureSubstrate`, `MathInvariants`,
`MathPhysicsEngine`, `DynamicComputeEngine`, `ConjectureEngine`,
`ConjectureVerifier` — that are all pure numeric compute over
parameters the LLM harness chooses. None of them needs JVM-specific
features (no Spring wiring, no JPA, no LLM-tool plumbing). They're
JVM-resident only because that's where the project started.

Phase 22 stood up the Julia sidecar as a peer service. Phase 24
proved the pattern by adding PySR. This phase ports the JVM
conjecture-substrate stack to the sidecar so that:

- There's one operator-visible compute process (the sidecar) instead
  of JVM + Julia + planned Python subprocesses (Phases 25, 26).
- The Dalmatian-filter / window-evaluator hot path runs on Julia's
  numeric stack instead of JVM-allocating maps on every window.
- The pattern is in place for Phases 25 + 26, which will host SymPy
  (via PyCall.jl) and Nemo.jl LLL inside the same sidecar.

The JVM remains the orchestrator: `ConjectureMinerService`'s
`@Scheduled` loop stays, the `Conjecture` JPA entity stays,
`MathToolSuite` keeps the LLM tool routing. The compute *body* of
each call moves; the wiring around it doesn't.

**Why a flag-gated migration, not a hard cut-over.** The legacy
in-process path is proven against the corpus. The Julia
re-implementations are new code. We ship them behind
`app.julia.conjecture.enabled` (default off), let the operator run
a side-by-side comparison on a paper sample, and flip the flag only
when the parity check passes. Matches the
[[feedback_flag_gated_dormant]] pattern.

---

## Scope

| Today | After Phase 28 |
|---|---|
| `MathConjectureSubstrate` allocates a JVM int[] sieve to 1.05M; `evaluateWindow` returns a `LinkedHashMap<String,Double>` per call | New `/numbertheory/window` endpoint on the sidecar; JVM client returns the same shape. Flag flip swaps the implementation at the caller |
| `MathInvariants.BASE` + `MathInvariants.DERIVED` evaluated in JVM by `MathInvariantSet.evaluateAll` | Derived scalars folded into the `/numbertheory/window` response (algebraic composition is trivial in Julia) |
| `MathPhysicsEngine.compute(domain, topicIdx, p1, p2)` lookup-and-evaluate over a hardcoded topic registry | New `/physics/topic` endpoint hosts the same formulae; the topic-registry index stays JVM-side as a routing table |
| `DynamicComputeEngine` interprets predicate / metric specs in JVM via `MathPhysicsEngine` helpers | New `/predicate/scan` endpoint accepts the same spec and returns the same shape |
| `ConjectureEngine.seed(samples)` and `update(...)` run Pearson + tightestKC in JVM | New `/conjecture/seed` + `/conjecture/update` endpoints |
| `ConjectureVerifier.verify(inequality, windows)` lazy-builds the substrate, runs held-out checks in JVM | New `/conjecture/verify` endpoint |
| Sandbox primitives (`isPrime`, `isHappy`, `sigmaSum`, `mertensPartialSum`) recomputed inside `SandboxEngine` per call | `SandboxEngine` math primitives optionally backed by the sidecar's number-theory cache when `app.julia.conjecture.enabled` is on — falls back to in-process for tight inner loops |

What Phase 28 explicitly does **not** change:

- `ConjectureMinerService` — `@Scheduled` cadence + the orchestration
  body stay JVM-side. The per-cycle compute calls flip to the
  sidecar.
- `Conjecture` JPA entity, `ConjectureRepository`,
  `ConjectureMapper`, `ConjectureTopicProvider`, `ConjectureQueue`
  — persistence and Spring wiring stay JVM.
- `MathToolSuite` tool JSON schema + routing — LLM harness stays JVM.
- `SandboxEngine` safety controller (5K iter / 50K stmt / 64-frame
  caps) — re-implementing the sandbox in Julia is the *work*, not
  the value. Only the math primitives can optionally delegate.
- `SymbolicEngine` (Symja) deprecation — separate decision, not
  bundled here. Already covered by Julia's `/eval` whenever the
  caller opts in.
- GPU Rust engine (`GpuEngineAdapter` + gRPC) — orthogonal concern,
  different sidecar.

---

## Architecture

The JVM stays the entry point for every conjecture-related call
chain. For each ported class, the JVM gets a thin
`Julia*Delegate` that holds both implementations (legacy + Julia
client) and routes based on
`juliaClient.isConjectureAvailable()`. The Julia client surface
expands by six endpoints, each following the existing
`JuliaMathClient` pattern (POST + JSON envelope + `JuliaMathException`).

```
ConjectureMinerService (@Scheduled)            ─┐
                                                │
ConjectureVerifier  ──┬─►  MathConjectureSubstrate (legacy in-process)
                      │
                      └─►  MathConjectureSubstrateDelegate
                                        │
                                        ▼
                          app.julia.conjecture.enabled?
                              │           │
                            true        false
                              │           │
                              ▼           ▼
                  JuliaMathClient    legacy substrate
                  /numbertheory/window
                              │
                              ▼
                      julia-math-sidecar
                      (Primes / NumberTheory)
```

The Java result records match the legacy `Map<String,Double>` key
ordering exactly so call sites stay byte-identical for any caller
that JSON-serialises the result.

**Endpoint naming.** All new routes live under domain-scoped
prefixes (`/numbertheory/*`, `/physics/*`, `/conjecture/*`,
`/predicate/*`) rather than top-level. This keeps the route table
readable as the sidecar grows past 20 endpoints; existing routes
(`/eval`, `/sweep`, `/pysr`) stay at the top level for back-compat.

**Safety filter.** Number-theory endpoints take **numeric input
only** (start, size, window arrays). No expression strings means no
safety-filter exposure. The expression-bearing endpoints (Phase 26
recognise, future `/predicate/scan` with embedded predicate
strings) reuse the existing `safety_check` from `safety.jl`.

**Per-endpoint flags.** A single
`app.julia.conjecture.enabled` master flag governs the whole
suite. No per-endpoint sub-flags in v1 — the rollout strategy is
"all-or-nothing per JVM" rather than fine-grained selective
delegation. If burn-in reveals one endpoint regresses against the
legacy implementation, we revert by flipping the master flag, not
by carving exceptions.

---

## Phasing

### 28.1 — `/numbertheory/window` endpoint + Java binding

**Status:** ships with this phase doc.

Foundational sub-phase. Establishes the pattern every later sub-phase
follows. Smallest port — `MathConjectureSubstrate.evaluateWindow`
is pure compute, 9 scalars, no upstream dependency.

What lands:

- **Sidecar:** new `src/numbertheory.jl` with SPF sieve +
  `is_prime`, `omega`, `big_omega`, `sigma`, `divisor_count`,
  `mobius`, `is_happy`, `evaluate_window`. Module-level `SPF_SIEVE`
  built lazily on first call (so module load stays fast).
- **Sidecar:** new `handle_numbertheory_window` in `handlers.jl`,
  route `POST /numbertheory/window` in `server.jl`. Request body
  `{start, size, timeoutMs?}`; response body
  `{ok, result: {abundant_density, deficient_density, prime_density,
  happy_density, mean_omega, mean_Omega, mean_divisor_count,
  max_divisor_count, mertens_delta}, elapsed_ms}`. Same key
  ordering as the JVM legacy path.
- **Sidecar test:** new integration testset in `test/runtests.jl`
  covering happy path, out-of-range window, bad input.
- **JVM:** new `JuliaMathClient.numberTheoryWindow(int start,
  int size) → NumberTheoryWindow` method. Two new `@Value`
  injections: `app.julia.conjecture.enabled` (default false) and
  `app.julia.conjecture.timeoutMs` (default 5000). New
  `isConjectureAvailable()` getter on the client.
- **JVM test:** new testset in `JuliaMathClientTest` covering
  happy path, disabled-client guard, transport failure, error
  envelope.

What does **not** land in 28.1:

- No caller migration. `MathConjectureSubstrate` and its consumers
  (`ConjectureVerifier`, `ConjectureMinerService`) keep calling the
  in-process implementation. The flag exists but no JVM code
  branches on it yet. This is intentional — 28.1 is the foundation
  that 28.2 then wires up.
- No `MathConjectureSubstrateDelegate` router class. That's 28.2.
- No `/numbertheory/window` parity test vs the legacy substrate.
  Lands in 28.2 alongside the delegate.

**Acceptance:** Sidecar test suite (`julia --project=. -e
'using Pkg; Pkg.test()'`) passes. JVM `JuliaMathClientTest` passes.
The new endpoint can be exercised manually via curl with the
sidecar running:

```bash
curl -s -X POST http://localhost:8088/numbertheory/window \
  -H 'Content-Type: application/json' \
  -d '{"start": 100, "size": 50}' | jq .
```

### 28.2 — Substrate parity check + caller migration

What lands:

- `MathConjectureSubstrateDelegate` class wrapping both
  implementations; constructor takes the legacy substrate +
  `JuliaMathClient`, dispatches on `isConjectureAvailable()`.
- `ConjectureVerifier` and any other current substrate caller
  switch to inject the delegate instead of building the substrate
  directly. Legacy in-process path stays the default until the
  flag flips.
- New `MathConjectureSubstrateParityTest` runs both paths over a
  fixed corpus of windows (e.g. `(100, 50)`, `(10_000, 200)`,
  `(500_000, 1_000)`) and asserts byte-identical scalar maps. Test
  is `@Tag("integration")` and skipped unless the sidecar is up.

**Acceptance:** parity test green; legacy path untouched when
flag is off; flag-on selects the sidecar path; sidecar outage
gracefully falls back to legacy (logged as warning, no exception).

### 28.3 — `/conjecture/seed` + `/conjecture/update` endpoints

What lands:

- Sidecar: `src/conjecture.jl` with Pearson correlation, dominance
  filter, `tightestKC` bound fit. `handle_conjecture_seed` and
  `handle_conjecture_update` in `handlers.jl`. JSON shapes mirror
  `ConjectureEngine.seed(List<SampleRow>) → List<Inequality>` and
  `update(List<Inequality>, SampleRow) → List<Inequality>`.
- JVM: `JuliaMathClient.conjectureSeed(samples)` +
  `conjectureUpdate(existing, sample)`.
- Parity test analogous to 28.2.

### 28.4 — `/conjecture/verify` endpoint

What lands:

- Sidecar: `handle_conjecture_verify`. Body
  `{lhs, rhs, k, c, windows:[{start,size},...]}`; response carries
  per-window margins + held/refuted verdict.
- JVM: `JuliaMathClient.conjectureVerify(inequality, windows)`.
- Migration: `ConjectureVerifier.verify` delegates via the new
  delegate when the flag is on.

### 28.5 — `/physics/topic` + `/predicate/scan` endpoints — ✅ SHIPPED

Sidecar layout — one file per JVM domain so future ports land
cleanly (§28.5b-cont3 split):

- `src/physics.jl` — small dispatch (`physics_compute`) only
- `src/physics_primitives.jl` — `is_prime`, `sigma_sum`, `mobius_of`,
  `stern_brocot_depth_of`, `is_squarefree`, `collatz_steps`
- `src/physics_mathematics.jl` — MATHEMATICS topics 0..9+ (incl.
  aliquot Set-based cycle detection + Möbius-driven Mertens)
- `src/physics_physics.jl` — PHYSICS topics 0..9+ (incl. 5000-step
  Euler projectile trajectory)
- `src/physics_materials.jl` — MATERIALS topics 0..9+ (incl. Math.exp
  Arrhenius + fractional-pow Basquin)
- `src/physics_interdisc.jl` — INTERDISCIPLINARY topics 0..7+
- `src/physics_biology.jl` — BIOLOGY topics 0..4+ (incl. Lotka-Volterra
  400-step ODE)
- `src/physics_chemistry.jl` — CHEMISTRY topics 0..4+ (incl. Van't
  Hoff exp+log compound + Pitzer-Curl Z-factor)
- `src/physics_arch_terr.jl` — ARCHITECTURE_TERRESTRIAL topics 0..4+
- `src/physics_arch_liquid.jl` — ARCHITECTURE_LIQUID topics 0..4+
- `src/physics_arch_space.jl` — ARCHITECTURE_SPACE topics 0..4+ (incl.
  pow(.., 0.25) vacuum thermal balance)

JVM:
- `JuliaMathClient.physicsTopic(...)` — returns
  `Optional<PhysicsTopicResultWire>`. Empty signals not_ported, so
  callers fall back to in-process compute transparently.
- `JuliaMathClient.predicateScan(spec)` — §28.5a.
- `MathPhysicsEngineDelegate` — shadow-read wrapper, flag-gated
  dormant (`app.julia.physics.topic.enabled=false` by default).
  When enabled, calls in-process compute (source of truth) AND
  sidecar in parallel; compares the four scalars + ClaimStatus
  byte-for-byte; tracks `parityMatches/parityMismatches/sidecarErrors/
  sidecarEmpty` counters for the §28.6 cutover precondition.

Callers wired through the delegate (5 services):
- `ResearchCycleService` (paper-write hot path, 2 sites)
- `BotLifecycleService` (competency puzzle)
- `ReplicationService` (replication verification)
- `ResearchNarrationService` (refinement loops, 2 sites)
- `MathToolSuite` (`run_param_sweep` tool; setter-chained from RNS)

Bit-identity discipline: Java
`Math.{sin, cos, sqrt, asin, exp, log, pow}` on OpenJDK x86-64 agrees
byte-for-byte with Julia's openlibm primitives across all ported
topics — confirmed by 65/65 `PhysicsTopicParityTest` cases under
strict `.isEqualTo()`. Compounds tested include 5000-step Euler
trajectories, 400-step ODEs, 30-step Newton iterators, exp+log
chains, and fractional-pow expressions.

DynamicComputeEngine migration was reconsidered: it doesn't call
`MathPhysicsEngine.compute` — it uses the primitive helpers
(`isPrime`, `isHappy`, `sigmaSum`, `collatzSteps`) directly. Those
stay JVM-side; per-call HTTP would be prohibitive given the inner
loops over millions of integers (architecture question #1 in this
doc).

### 28.6 — Flag flip + legacy removal (deferred — operational)

Not a code lane. After 28.1–28.5 ship and burn-in passes (N papers
green on the Julia path, parity tests stable for one release), the
operator flips the per-feature flags to true by default and the
legacy JVM implementations move to `@Deprecated`. Actual class
removal waits one further release so the rollback path stays open.

Feature flags to flip (default OFF today):
- `app.julia.conjecture.enabled` — substrate + verify + seed/update
- `app.julia.physics.topic.enabled` — physics topic + predicate scan

Cutover precondition for `app.julia.physics.topic.enabled`:
- Operator runs production with the flag on for N days
- `MathPhysicsEngineDelegate.Stats.parityMismatches == 0` throughout
- `sidecarErrors / shadowCalls` below some threshold (e.g. <0.1%)

After cutover, §28.6 work involves:
- Changing the delegate from shadow-read to sidecar-primary
- Composing the JVM `ResearchResult` prose from the sidecar's `detail`
  JsonNode (each topic emits the detail fields the JVM caller's prose
  templates already read)
- Deprecating + then removing `MathPhysicsEngine.compute` and its
  per-topic methods
- Mirror sequence for the conjecture stack


---

## Open architecture questions

1. **`SandboxEngine` math primitives.** The sandbox today
   reimplements `isPrime`, `sigmaSum`, etc. inline. Could those
   delegate to the sidecar's number-theory module? Pro: one source
   of truth. Con: the sandbox runs LLM-generated code that may
   loop over millions of integers, and an HTTP hop per call is
   prohibitive. Likely resolution: keep sandbox primitives
   in-process; add a batched `/numbertheory/predicate` endpoint
   later if the sandbox grows analytic surface.

2. **Sieve size on the sidecar side.** The JVM substrate sizes
   to 1.05M. Julia's default `Int` is 64-bit so memory cost is
   ~8 MB for a 1M-entry table — fine. But if we ever need to mine
   windows past 10^7 the sieve grows linearly and the sidecar's
   memory budget (default 512 MB on systemd) gets squeezed.
   Document the trade-off; defer dynamic resizing until a paper
   actually demands it.

3. **Deterministic numerics.** JVM `double` and Julia `Float64`
   both follow IEEE-754, so identical inputs should yield identical
   outputs to the bit — but only if the operation order matches.
   The parity test in 28.2 will catch any divergence; if it
   surfaces, the resolution is "match Julia's order in the JVM
   port" rather than allowing epsilon tolerances (the conjecture
   queue compares exact equality of inequality coefficients).

4. **Concurrency.** The sidecar handles one connection per task,
   and number-theory endpoints are stateless after the SPF sieve
   builds. Initial sieve build is single-threaded (~50 ms for 1M
   entries) and protected by a once-only flag. Subsequent calls
   are concurrent-safe.

---

## Cost / benefit summary

**Effort:** ~12-14 days end-to-end for §28.1–§28.5. §28.1 is the
foundational ~1.5-day port; subsequent endpoints amortize on the
shared pattern (~2-2.5 days each including parity test).

**Operational win:** zero new sidecars (everything lands on the
existing julia-math-sidecar). Eliminates the upcoming Python
subprocess type from Phase 25 and the FPLLL native binary from
Phase 26 by establishing the "this lives in the sidecar" pattern
before those phases start.

**Risk:** medium. The math is well-tested in the JVM; the port is
mechanical. The risk is in IEEE ordering (mitigated by parity
tests) and in the flag-gated rollout (mitigated by keeping legacy
paths intact through 28.6).

**Recommendation.** Ship §28.1 first as the pattern foundation.
§28.2 (parity check + caller migration) is the first sub-phase
that delivers user-visible behaviour — the operator can flip the
flag on one window-evaluator call and observe identical results.
After §28.2 lands cleanly, §28.3–§28.5 can run in parallel since
the JVM delegate pattern is settled.
