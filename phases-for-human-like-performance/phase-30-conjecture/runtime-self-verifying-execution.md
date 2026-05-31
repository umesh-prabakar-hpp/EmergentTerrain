# Fork 2b scope — runtime self-verifying execution

The sidecar **executes LLM-authored Julia at runtime**, gated by the moat: a model proposes a numeric
method, it runs in isolation, its output is trusted *only* if it reproduces a trusted oracle on the
overlap range. This is the genuinely novel capability behind "ask a frontier model to invent numeric
techniques" ([`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) §5–§7). It is a
**phase of its own, not a spike** — the headline cost is that executing model-authored code is RCE by
construction.

> **Prerequisite met.** Fork 2a ([`segmented-mertens-spike.md`](segmented-mertens-spike.md) §10) proved
> the loop *at dev time*: generation is robust (segmented + sublinear both passed blind first try), the
> moat has teeth (buggy variant rejected at `k=2`), and the repair loop converges (a model fixed a
> moat-caught bug from the discrepancy alone, 1 round). 2b turns that dev-time loop into a **runtime**
> capability. **Key implication from 2a:** natural textbook methods pass first try, so 2b's *value*
> concentrates on **genuinely novel** methods where first attempts are unreliable — the sandbox + repair
> loop is what makes trying those safe.

---

## 1. Threat model (why this is the hard part)

The model is **not adversarial** — it is trying to write a correct `M(x)`. But its code must be treated
as untrusted, because even a well-meaning attempt can:

- **not terminate** (infinite loop) → wall-clock bound;
- **allocate unbounded memory** (OOM) → address-space bound;
- **touch the filesystem / network / shell** (`open`, `read`, `run`, `download`) → no-FS/no-net sandbox;
- **corrupt shared module state** (the `NT_SPF` sieve, caches) → isolate from the live process;
- **crash the Julia process** (`ccall`, `unsafe_*`, segfault) → must not share a process with the server;
- **`using` a package / `eval`/`include`** → AST allowlist rejects.

So the design assumes worst-case *crashes*, not worst-case *intent*. The goal: **a pathological candidate
can waste a sandboxed process and nothing more** — never crash, slow, or exfiltrate from the live sidecar.

## 2. Architecture — subprocess isolation (the real boundary) + AST allowlist (defense-in-depth)

Two layers, cheapest first:

1. **AST allowlist (pre-flight, cheap).** Parse the candidate, walk the syntax tree, reject anything
   outside a numeric-only allowlist: no `using`/`import`/`include`/`eval`, no `ccall`/`unsafe_*`/`@ccall`,
   no I/O (`open`/`read`/`write`/`run`/`download`/`Base.Sys`/`ENV`), no `Task`/`@async`/threads. Reuses
   the spirit of the sidecar's existing safety filter (which already rejects `using`). Rejects obvious
   abuse before paying for a process spawn — but is **not** the security boundary (Julia eval is hard to
   truly sandbox in-process).
2. **Subprocess isolation (the boundary).** Run the candidate in a **fresh `julia` process** under OS
   limits:
   - `ulimit`/rlimits: CPU-seconds, address space (memory), file size 0, no core dump;
   - no network + minimal FS via namespaces (`unshare`/`nsjail`/`firejail`) or a restricted user + RO
     mounts; only a temp dir for the script;
   - hard wall-clock kill from the parent (`Cmd` + timeout → SIGKILL the process group).
   If the candidate hangs, OOMs, or segfaults, **only the child dies** — the sidecar server process is
   never in the blast radius. Container/VM isolation (gVisor/nsjail) is the stronger option if the
   namespace+rlimit cut proves insufficient; start with rlimit+namespace, escalate only if needed.

Cost: Julia process start + JIT (~seconds) per candidate. Candidates are *rare* (only during discovery),
so acceptable; a prebuilt sysimage can amortize startup if it matters.

## 3. Execution contract — candidate emits, trusted side judges

The single most important safety/honesty rule, carried over from 2a: **the candidate never sees the
oracle or computes the verdict.** It only emits raw values; a trusted party compares.

- The model produces a self-contained `candidate(x)::Int64` against a fixed contract (no args beyond `x`,
  no I/O, Int64 out, `x<1 → 0`).
- The runner writes `candidate` + a **fixed** driver into a temp script. The driver calls
  `candidate(k)` at the moat checkpoints + the requested target `x` and prints a single JSON line of raw
  values to stdout. Nothing else.
- The **parent** (trusted, holds the oracle) reads those values and judges. The candidate cannot fake a
  pass because it never emits a verdict — only numbers the parent checks.

## 4. The moat as a runtime gate

```
oracle = nt_mertens_dense over [0, NT_MAX_N]   (trusted endpoint, fixed reviewed code — already exists)
for each candidate (≤ MAX_REPAIR attempts):
    vals = run_sandboxed(candidate, checkpoints ∪ {target})      # untrusted, isolated
    if vals is TIMEOUT/OOM/CRASH/ASTREJECT:  feed reason back → repair
    elif any k: vals[k] != oracle[k]  (or != OEIS anchor beyond range):
                feed first discrepancy back → repair                # the 2a loop
    else:       ACCEPT — trust vals[target]; tag provenance; stop
```

- Checkpoints: the §3 set from the spike (`{…, 999983, 1234567, 2_000_000}` + OEIS anchors `M(10⁶)=212`,
  `M(10⁷)=1037`).
- The repair feedback is exactly what 2a showed sufficient: the first failing `(k, got, want)` — no
  hand-holding about the cause.
- Determinism: the candidate must be pure/deterministic (no `Random`/time — already blocked by the AST
  filter), so a PASS is reproducible.

## 5. Where it lives, flags, and the per-invariant gate

- **Orchestration: Java** (the pipeline already calls LLMs). The generate→validate→repair loop, the
  judging, the attempt cap, and provenance tagging are Java-side.
- **Execution: sidecar.** A new **untrusted** endpoint `/discover/validate` wraps the §2 sandboxed
  runner and returns the candidate's raw checkpoint values (or a failure reason). The **trusted** oracle
  comes from the *existing* `nt_mertens_dense` path — so the two are cleanly separated: fixed reviewed
  code (oracle) vs sandboxed code (candidate), judged in Java.
- **Flags (default OFF):** `app.research.discovery.runtimeExecEnabled`; `discovery.maxRepairAttempts`
  (e.g. 3); `discovery.sandboxCpuSeconds`, `discovery.sandboxMemMb`; `discovery.allowedXCeiling`.
- **Per-invariant gate (unchanged from the spike §6):** runtime exec is offered only for
  `CONJECTURE_INVESTIGATION` papers whose invariant ∈ `MERTENS_CLASS`, only when the flag is on. Never a
  shared-prompt change.

## 6. Provenance & honesty (non-negotiable)

A value produced by a runtime-discovered method is **not** an established result. Every such value must
carry: the candidate's code hash, the checkpoints it passed, and the target `x`. The narrator presents
it as *"computed by a method validated against M(x) on [0, 2×10⁶]"*, never as a literature fact — same
regime-awareness discipline as [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) §4.
A validated novel `M(x)` at `10¹⁵` is *less trivial, not a solution*; the math ceilings are unchanged.

## 7. Acceptance criteria

1. **Correct candidate** validates; its out-of-range value is trusted and tagged with provenance.
2. **Buggy candidate** is rejected with the first discrepancy surfaced; the repair loop converges within
   `maxRepairAttempts` (2a shows this works; here it must work *through the sandbox endpoint*).
3. **Containment (the load-bearing tests):**
   - infinite loop → killed by wall-clock;
   - unbounded allocation → killed by the memory rlimit;
   - `open("/etc/passwd")` / `run(\`...\`)` / `using X` / `ccall` → rejected by the AST filter, and even
     a hypothetically-slipped one has no FS/network and dies without touching the sidecar;
   - a deliberately segfaulting candidate kills only its subprocess — the live server keeps serving
     (prove with a concurrent health check).
4. **Flag OFF** → endpoint absent, tool absent, zero behavior change.

## 8. Phasing

1. ✅ **Sandbox runner + containment tests** (sidecar repo): `src/sandbox_runner.jl` (module
   `SandboxRunner`) — AST allowlist + isolated `julia` subprocess under `timeout` (wall) + `ulimit`
   (CPU, no file writes, no cores) + the candidate-emits/trusted-judges `moat_judge`. **Done** (23/23,
   `test/sandbox_runner_test.jl`): AST rejects `open`/`using`/`ccall`/`unsafe_*`/`run` + parse errors;
   moat ACCEPTS a correct candidate (validates `M(10⁷)=1037` beyond the oracle range) and REJECTS a
   wrong-but-running one; HANG→`:timeout`, OOM/stack-overflow contained, the FS-touch never creates its
   file; the runner stays reusable after a hang/crash (parent liveness). **Dormant** — not `include`d by
   the package, so no recompile; the live sidecars were verified up before and after. Memory limiting is
   `timeout`/allocation-failure for now; a hard RSS cap via cgroups is a Phase-2 hardening (a slow leak
   isn't bounded by `ulimit -v` because Julia reserves huge virtual memory).
2. ✅ **`/discover/validate` endpoint** wrapping the runner (untrusted) alongside the trusted
   `nt_mobius`/`nt_mertens_dense` oracle. **Done** (`handlers.jl: handle_discover_validate`,
   registered in `server.jl`; `discover_endpoint_test.jl`, 15/15). **ENV-gated**: the route is
   registered but the handler returns HTTP 403 — no parsing, no execution — unless
   `ET_DISCOVER_EXEC=1`, so it is dormant by default (verified 403 on the live server post-restart).
   The oracle is computed in the trusted process (one cumulative `nt_mobius` pass), never in the
   sandbox; `moat_judge` returns `accept|reject|na`. Tests cover dormant→403, ACCEPT a correct
   candidate (validating `M(10⁸)=1928` out-of-range), REJECT wrong-but-running, AST-containment of a
   malicious candidate, and out-of-range→400. (Note: judging happens in the trusted *sidecar* process
   — a refinement over the §5 "judged in Java" sketch — keeping oracle + comparison in one trusted
   place; Java orchestrates and can re-judge from the returned `values`+`oracle`.)
3. ✅ **Java orchestration**: generate→validate→repair loop, attempt cap, provenance tagging.
   **Done** (`research/discovery/`: `MertensDiscoveryService` + `DiscoveryTask`/`ValidationResult`/
   `DiscoveryOutcome` records + `CandidateGenerator`/`CandidateValidator` interfaces +
   `LlmCandidateGenerator` (router + fenced-code extraction) + `SidecarCandidateValidator` (HTTP →
   `/discover/validate`); 11/11 tests). Flag-gated OFF (`runtimeExecEnabled`): `discover()`
   short-circuits to a disabled outcome — model and sandbox untouched. The loop is **domain-neutral**
   (spec from the task); generation/validation are injected so the loop is unit-tested with fakes
   (repair converges on attempt 2, carrying the prior code + the moat discrepancy `M(2)=2 but correct
   0` — exactly the fork-2a signal). Accepted outcomes carry `Provenance` (code SHA-256 + checkpoint
   count + target), never a novelty claim. Dormant — nothing calls `discover()` until the Phase-4
   tool. (Judging is in the sidecar per Phase 2; Java consumes the verdict and runs the loop.)
4. ✅ **Gated tool** in `MathToolSuite` — `discover_mertens_value`. **Done**
   (`MathToolSuite.discoverMertensValue` + advertisement in `toolsJson`;
   `MertensDiscoveryService` field-injected into `ResearchNarrationService` via a `@PostConstruct`;
   `MathToolSuiteDiscoveryTest`, 15/15). Advertised + executable only when discovery is wired AND
   `runtimeExecEnabled` (omitted from the tool list + handler returns a disabled line otherwise);
   `ET_DISCOVER_EXEC` enforced downstream. Computes M(x) at x > NT_MAX_N, returns the validated value
   tagged **COMPUTED, not established** (honest framing baked into the tool output), or a failure
   note — never a guessed number. **Scope note:** the per-invariant gate was realised as the tool
   being *M(x)-only by construction* + the flag/instance gate (matching the `verify_conjecture`
   precedent), rather than threading per-paper invariant context through the pipeline-wide narration
   path — the tool is offered only during a deliberate discovery experiment, so a density/primality
   paper has no reason to call it. Dormant by default; no app restart needed until the flags flip.
5. ◐ **Flip + watch**. **Config flipped** (`runtimeExecEnabled: true`) and the **live sidecar moat
   verified end-to-end** (2026-05-31): with `ET_DISCOVER_EXEC=1` on the running 8088, a direct
   `POST /discover/validate` ACCEPTED a correct candidate — **validating `M(10⁸)=1928` out of
   range** (~11.6 s) — and REJECTED a wrong one (`firstFail:[1,0,1]`). So the sandbox-exec → oracle →
   moat path works in production. **Remaining (operator):** rebuild + restart the app to load the
   Phase-3/4 beans (boot-check with the flag now live), then watch a mertens-class
   `CONJECTURE_INVESTIGATION` paper whose professor calls `discover_mertens_value` — confirm the
   validated `M(x)` lands with provenance and the narrator frames it as "computed", not established.
   (The running app is still the pre-Phase-3 jar, so nothing fires until that restart.)

## 9. Risks

- **Sandbox escape / RCE** — the headline risk. Mitigation: subprocess + rlimit + namespace (no FS/net) +
  AST allowlist; the live server never shares a process with candidate code. Residual risk is a sandbox
  escape = security incident; this is mitigation, not proof — keep the isolation conservative and the
  feature flag-gated + per-invariant-scoped. Consider nsjail/gVisor if rlimit+namespace is deemed
  insufficient by review.
- **Julia subprocess startup/JIT cost** (~seconds/candidate) — acceptable for rare discovery; prebuild a
  sysimage if it bites.
- **AST-filter over/under-reach** — too strict blocks legitimate numeric code; too loose lets risky
  constructs through. Keep it conservative and tunable, and lean on the subprocess as the real boundary.
- **Provenance overclaim** — a discovered number narrated as an established result. Mitigation: §6 tagging
  is mandatory, enforced in the narrator path.
- **Value concentration** — on textbook methods 2b adds little over dev-time authoring (2a); its payoff is
  novel methods. Don't over-invest in the runtime path for problems a reviewed function already solves.

## Non-goals
- General-purpose code execution for arbitrary domains (gated to `MERTENS_CLASS`).
- Defeating a truly adversarial generator (the model isn't one; the sandbox handles worst-case *crashes*).
- Solving Mertens or reaching the counterexample regime (unchanged ceilings).

---

## Cross-references
- [`frontier-escalation-loop.md`](frontier-escalation-loop.md) — the autonomous *drive* that uses this
  tool: escalate a holding bound, predict the compute stall, and pivot to a verifiable "innovative"
  branch when computation runs out.
- [`segmented-mertens-spike.md`](segmented-mertens-spike.md) — Phase 1 + fork 2a (the dev-time loop this
  productionizes); §6 per-invariant gate; §10 the 2a/2a-#1 results.
- [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) — why novel *computations* are
  verifiable here (the loop), and the honest ceilings.
- [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) — why the M(x) class is the
  target.
