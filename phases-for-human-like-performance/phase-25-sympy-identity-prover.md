# Phase 25 — SymPy Combinatorial-Identity Prover (Gosper / WZ / Sister Celine)

**Status (2026-05-22):** DRAFT. Not started. **Depends on Phase 22**
(Julia sidecar) per the 2026-05-22 consolidation decision — see
`reference-conjecture-engines.md` § "Decisions — Julia consolidation
(2026-05-22)". Phase 24 (PySR) need not have shipped, but the sidecar
must be live.

---

## Decision update — 2026-05-22 (supersedes the architecture below)

**Invocation path changed from "Python subprocess per call from the
JVM" to "SymPy embedded in the Julia sidecar via `PyCall.jl`."**

The sections labelled "Why a Python subprocess, not JEP/Jython/JNI,"
"Why subprocess each call vs long-lived worker," and the
ProcessBuilder / RLIMIT machinery in §25.1–25.3 below are kept as
historical context but **do not reflect the current direction**.
When this phase starts, the implementation rewrites against:

- A `/prove_identity` HTTP route on `julia-math-sidecar` (port 8088).
- `PyCall.jl` initialised at sidecar startup with SymPy imported
  once; per-call cost amortizes to a Python function dispatch
  rather than a 150ms+ interpreter cold start.
- Safety isolation moves into the PyCall wrapper: expression
  denylist (`__`, `os.`, `subprocess.`, backticks) applied before
  the call; wall-clock timeout enforced by Julia (`Base.timedwait`
  or `Task` + interrupt) since RLIMIT no longer applies in-process.
- Flag: `app.julia.sympy.enabled` (default off). The Java client
  `SympyIdentityClient` becomes a peer of `JuliaMathClient.pysr()`
  — same HTTP shape, same `JuliaSidecarReporter` accounting.

Rationale: one operator-visible sidecar instead of JVM-spawned
Python subprocesses; one Python install footprint (unchanged in
surface area, but living inside the sidecar's environment); no
per-call subprocess startup cost.

Detailed phasing rewrite is deferred until the phase actually
starts; the §25.4 reviewer-panel integration and §25.5 acceptance
work are not affected by this change.

---

**Motivation.** Phase 23.6 introduced the `EvidenceLabel` floor
(`THEOREM` / `EMPIRICAL_RESULT` / `CONJECTURE` /
`UNEXPLAINED_OBSERVATION`) and let the reviewer panel refuse the
higher labels without evidence. As of today the panel **cannot**
grant `THEOREM` legitimately because no tool in the suite produces
a machine-checkable proof. The label is reserved but unreachable —
exactly the gap noted in `phase-23-conjecture-driven-novelty.md`
("the `THEOREM` label is reserved but unreachable until a separate
proof slice is built").

Combinatorial identities are the slice where this is cheapest to
fix. Hypergeometric summation has three algorithms — **Gosper**
(1978), **Zeilberger / WZ** (1990), and **Sister Celine** (1945) —
that emit a **machine certificate** alongside their result. The
certificate mechanically proves the identity. SymPy ships all three.
A new `prove_identity` tool wraps them via a Python subprocess; when
it returns a certificate, the panel is justified in stamping
`THEOREM` on that specific claim.

This is narrowly scoped: it does not handle algebraic identities,
geometric theorems, real-analysis bounds, or anything the Graffiti-
style miner produces. It handles **sums of hypergeometric terms** —
binomial-coefficient identities, ratio-of-rising-factorials sums,
and the like — which is a real and historically rich corner of
combinatorics with thousands of open variants in the literature.

---

## Scope

What Phase 25 adds:

| Today | After Phase 25 |
|---|---|
| `verify_conjecture` checks a conjecture on held-out windows; passing means "no counterexample in this range" | New `prove_identity` tool calls SymPy's Gosper/Zeilberger machinery; passing means "machine-certified proof" |
| `EvidenceLabel.THEOREM` is reserved but unreachable | THEOREM is reachable for hypergeometric-sum identities; panel sees the certificate string in the audit trail |
| The conjecture miner sometimes surfaces identities that are actually known closed forms (caught by OEIS gate where possible) | When mining produces a sum-shape conjecture, the system attempts a proof before publishing; a proven identity is published as `THEOREM`, an unprovable one stays `CONJECTURE` |

What Phase 25 explicitly does **not** add:

- Lean / Coq / Isabelle integration. No interactive proof assistant.
  The point of WZ-class methods is exactly that they don't need one.
- Algebraic identity proofs (Risch-style integration, polynomial
  GCD identities). SymPy can do some of these but they belong with
  the existing `run_symbolic` path, not a separate prover tool.
- Real-analysis theorems, geometric theorems, number-theoretic
  theorems (Fermat, primes, etc.). Out of scope; those need a real
  theorem prover and are deliberately deferred per the Phase 23
  "what's out of scope" list.
- A persistent proof corpus. Certificates are stored with the paper
  record; there is no separate `proofs` table.

---

## Architecture

```
EmergentTerrain (Spring Boot)
└── MathToolSuite
    ├── verify_conjecture    → ConjectureVerifier (existing, 23.5)
    ├── prove_identity       → SympyIdentityClient (NEW)
    └── ...

SympyIdentityClient (Java, this repo)
└── ProcessBuilder("python3", "-m", "scripts.sympy_prover.main")
    │  stdin:  JSON {algorithm, lhs_summand, var, lower, upper, target?}
    │  stdout: JSON {ok, status, result, certificate, wallMs}
    └── safety: subprocess runs with --no-site-packages, RLIMIT_CPU=10s,
                RLIMIT_AS=512MB, working dir = /tmp/sympy-prover, no
                network, no filesystem write outside cwd.

scripts/sympy_prover/main.py
└── Dispatch:
      "gosper"          → sympy.concrete.gosper.gosper_sum
      "zeilberger_wz"   → sympy.simplify.hyperexpand + sympy.concrete.summations
      "sister_celine"   → wrapper around SymPy's hyperexpand / Sum.rewrite

   Input: hypergeometric summand F(k) and bounds; optional target
          closed form for verification mode.
   Output:
     - ok=true,  status="proved",      result=<closed form>, certificate=<rational R(n,k)>
     - ok=true,  status="recurrence",  result=<recurrence>,  certificate=<recurrence coeffs>
     - ok=true,  status="no_closed_form", result=null
     - ok=false, status="timeout" | "parse_error" | "unsupported"
```

**Why a Python subprocess, not JEP/Jython/JNI.** JEP requires libpython
in the JVM process and has a long history of GC interactions; Jython
stopped at Python 2.7 and doesn't run modern SymPy; JNI to libpython
is the same problem as JEP with more pain. A subprocess is the
operationally cheapest interop — start it, write JSON to stdin, read
JSON from stdout, kill it on timeout. Per-call cost is ~150 ms
process spawn + Python+SymPy import (~400 ms cold, dropped to ~50 ms
warm with a small idle-pool of pre-imported workers if needed in
v2).

**Why subprocess each call vs long-lived worker.** A single long-lived
Python worker would cut the 400 ms import cost. The cost is that any
SymPy operation that misbehaves (segfault in a C extension,
unbounded recursion, GIL deadlock) kills the worker and we need a
supervisor + restart logic. For v1, **fresh subprocess per call** —
the cost is acceptable (proof attempts are seconds, not
milliseconds) and the supervisory cost is zero. A pool can land in
v2 if profiling shows the import cost matters.

**Safety boundary.** SymPy itself is library code, no shell or
network. The wrapper script:
- Runs with `python3 -I -B` (isolated mode, no `.pyc` writes).
- Sets `RLIMIT_CPU=10` (hard 10-second CPU cap).
- Sets `RLIMIT_AS` to 512 MB.
- `os.chdir('/tmp/sympy-prover')` and locks out parent traversal.
- Input is the raw expression string but is parsed only by SymPy's
  parser (`sympify` with restricted locals); no `eval`, no `exec`.

A pre-flight regex on the JVM side rejects obviously malicious input
(`__`, `os.`, `subprocess.`, backticks) before the subprocess starts,
matching the existing `JuliaSafetyFilter` pattern (22.3).

---

## Phasing

### 25.1 — `scripts/sympy_prover/` Python wrapper

Standalone Python entrypoint. No dependencies beyond `sympy` and
the standard library.

**Files:**
- `scripts/sympy_prover/main.py` — argument dispatch + JSON I/O.
- `scripts/sympy_prover/gosper.py` — wrapper around
  `sympy.concrete.gosper.gosper_sum`; on success extracts the WZ
  certificate via the standard `s(k+1) − s(k) = t(k)` factorization.
- `scripts/sympy_prover/zeilberger.py` — wrapper around SymPy's
  hypergeometric recurrence finder.
- `scripts/sympy_prover/sister_celine.py` — wrapper using SymPy's
  `Sum.rewrite('gosper')` / undetermined-coefficients ansatz.
- `scripts/sympy_prover/sandbox.py` — RLIMIT setup, working-dir
  lockdown, `sympify` with restricted locals.
- `scripts/sympy_prover/test_main.py` — unit tests covering the
  three classical identities (Σ C(n,k) = 2ⁿ, Σ k·C(n,k) = n·2ⁿ⁻¹,
  Σ C(n,k)² = C(2n,n)) plus a known-no-closed-form case (Σ 1/k²,
  no hypergeometric closed form) plus a timeout case.
- `scripts/sympy_prover/requirements.txt` — pinned `sympy==1.14.0`.
- `scripts/sympy_prover/README.md` — install, invocation, protocol.

**Acceptance:** `echo '{"algorithm":"gosper","lhs_summand":"binomial(n,k)",
"var":"k","lower":0,"upper":"n"}' | python3 -m scripts.sympy_prover.main`
returns `{"ok":true,"status":"proved","result":"2**n","certificate":"...",
"wallMs":<N>}`.

**Effort:** ~2 days (most of which is getting the certificate
extraction right; SymPy's API for WZ certificates isn't always
exposed cleanly).

### 25.2 — `SympyIdentityClient` Java bean

`@Service` similar in shape to `JuliaMathClient`. Configuration:
`app.sympyProver.enabled` (default `false`),
`app.sympyProver.timeoutMs` (default 10000),
`app.sympyProver.python` (default `python3`),
`app.sympyProver.scriptDir` (default
`/opt/apps/ide/eclipse/workspace-web/EmergentTerrain/scripts/sympy_prover`).

API:
```java
public record IdentityProof(
    Status status,        // PROVED / RECURRENCE / NO_CLOSED_FORM / TIMEOUT / PARSE_ERROR
    String result,        // e.g. "2**n" or null
    String certificate,   // rational R(n,k) string or recurrence coeffs
    long wallMs) {}

IdentityProof prove(String algorithm, String lhsSummand,
                    String var, String lower, String upper,
                    Optional<String> target);
```

Counter fields (`identityCalls`, `identityProvedCalls`,
`identityLatencyMsTotal`, `identityLatencyMsMax`) surfaced through
a new `SympyProverReporter` (mirrors `JuliaSidecarReporter` from
22.6).

**Files:**
- `src/main/java/com/emergentterrain/llm/SympyIdentityClient.java`
- `src/main/java/com/emergentterrain/llm/SympyIdentityException.java`
- `src/main/java/com/emergentterrain/llm/SympyProverReporter.java`
- `src/main/java/com/emergentterrain/llm/SympyInputFilter.java` —
  pre-flight regex (mirrors `JuliaSafetyFilter`).
- `src/main/resources/application.yml` — new `app.sympyProver.*`
  block.
- `src/test/java/com/emergentterrain/llm/SympyIdentityClientTest.java`
  — uses a fake `python3` script (a small bash shim that emits
  fixed JSON) to test happy/timeout/parse-error/safety-reject paths
  without requiring SymPy in CI.
- `src/test/java/com/emergentterrain/llm/SympyIdentityClientLiveTest.java`
  — runs against real `python3 + sympy` if available, skipped
  otherwise.

**Acceptance:** unit tests pass without SymPy; live tests pass on a
host with SymPy installed; counters increment correctly.

**Effort:** ~1 day.

### 25.3 — `MathToolSuite.prove_identity` tool

Register the tool when `sympyIdentityClient != null && enabled`.
Description:

> Attempt a machine-certified proof of a hypergeometric-sum
> identity. Input is a summand `F(k)`, a summation variable, lower
> and upper bounds, and (optionally) a target closed-form. Returns
> `proved` with a closed form + WZ certificate when the sum is
> hypergeometrically summable; `recurrence` with a polynomial-
> recurrence certificate when the sum satisfies one but isn't
> closed; `no_closed_form` when neither applies. Use only on
> sums of hypergeometric terms (products of factorials, binomials,
> Pochhammer symbols, rational functions).

Schema:
```json
{
  "name": "prove_identity",
  "parameters": {
    "algorithm":   { "type": "string", "enum": ["gosper", "zeilberger_wz", "sister_celine", "auto"] },
    "summand":     { "type": "string", "maxLength": 512 },
    "var":         { "type": "string", "pattern": "^[A-Za-z_][A-Za-z0-9_]*$" },
    "lower":       { "type": "string", "maxLength": 64 },
    "upper":       { "type": "string", "maxLength": 64 },
    "target":      { "type": "string", "maxLength": 256 }
  },
  "required": ["summand", "var", "lower", "upper"]
}
```

`auto` algorithm tries Gosper first (cheapest), falls back to
Zeilberger/WZ if Gosper returns `no_closed_form`, falls back to
Sister Celine only if explicitly forced (rarely useful when WZ is
available; included for completeness).

**Files:**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java` —
  new `PROVE_IDENTITY_DESC` constant, new `proveIdentity(JsonNode)`
  method, dispatch case, schema inclusion gated on
  `sympyIdentityClient != null`.
- `src/test/java/com/emergentterrain/service/MathToolSuiteProveIdentityTest.java`
  — tool absent without client, present with client, classical
  identity proofs, no-closed-form returns clean status, markdown
  output shape, auto-dispatch falls through Gosper → WZ correctly.

**Acceptance:** the three classical identities mentioned in 25.1
all reach `PROVED` status through the tool path; LLM-facing
formatted output includes the certificate verbatim.

**Effort:** ~1 day.

### 25.4 — Reviewer panel + EvidenceLabel integration

The panel currently downgrades `THEOREM` to `EMPIRICAL_RESULT` if no
proof evidence is in the audit trail (Phase 23.6 `LabelFloor`). Wire
`prove_identity` results so that:

1. A `proved` result from `prove_identity` injects a
   `ProofEvidence(certificateString, algorithm, wallMs)` into the
   paper's audit chain.
2. `LabelFloor.computeFloor(...)` recognises `ProofEvidence` as
   sufficient justification for `THEOREM`.
3. The reviewer-panel prompt template gets a new optional section
   "machine-certified proof attached" that, when populated, biases
   the panel toward `THEOREM` and asks reviewers to validate that
   the certificate string is syntactically a rational function (a
   cheap structural check the LLM can do).

**Files:**
- `src/main/java/com/emergentterrain/research/conjecture/ProofEvidence.java`
  — new record.
- `src/main/java/com/emergentterrain/research/conjecture/LabelFloor.java`
  — recognise `ProofEvidence`.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — panel prompt template edit; capture proof evidence when
  `prove_identity` is called in the professor stage.
- Tests for the floor logic and the panel template.

**Acceptance:** a synthetic paper whose hypothesis is "Σ_{k=0}^{n}
binomial(n,k) = 2^n" routes through `prove_identity`, gets
`PROVED`, and the panel emits `THEOREM` rather than the
default-floor `CONJECTURE`.

**Effort:** ~1 day.

### 25.5 — Acceptance + rollout

Three checks:

1. **Library coverage probe.** A standalone script in
   `scripts/sympy_prover/probe.py` runs 10 classical identities
   from Petkovsek–Wilf–Zeilberger's *A=B* book. At least 8 reach
   PROVED status under the default timeout. Run once before the
   production flip.

2. **In-corpus uptake.** After 30 MATHEMATICS-domain papers ship
   with the flag on, look for ≥2 papers in the corpus where
   `prove_identity` was called and `THEOREM` was granted by the
   panel.

3. **No false `THEOREM` grants.** Spot-check every `THEOREM`-
   labeled paper in the first 30 ships: the certificate string
   must be present, must be a rational function in the summation
   variables, and must satisfy `s(k+1) − s(k) = F(k)` symbolically
   when re-checked offline. **Any failure here is a P0 incident** —
   it means the panel granted a label the evidence didn't support
   and the floor logic missed it.

**Acceptance bar:** all three of the above; in particular zero
false `THEOREM` grants. If a false grant slips through, the right
fix is to harden `LabelFloor.computeFloor` to re-verify the
certificate string itself before allowing the label, not to
weaken the engine.

**Effort:** ~1 day probe + corpus run unattended.

---

## Open architecture questions

1. **Should the certificate string be stored verbatim or normalized?**
   SymPy's certificate format is canonical for the algorithm used.
   Storing it verbatim preserves the audit trail; normalising risks
   information loss. Lean verbatim with a note in the schema
   describing which algorithm produced it.

2. **Sister Celine in the default `auto` path?** Sister Celine is
   slower than Gosper/WZ and rarely finds something the others
   miss. Default `auto` skips it; explicit `sister_celine` calls
   still work. Revisit only if an audit shows >5% of identities
   were unreachable by Gosper/WZ but solvable by Sister Celine.

3. **Multi-variable summands (Σ_{j,k} F(n,j,k))?** WZ has a
   multi-variable extension; SymPy's implementation is partial.
   v1 supports single-variable summation only; multi-variable
   joins the deferred list.

4. **Symbolic limits (`lower = "0"`, `upper = "n"`).** SymPy
   handles symbolic upper bounds natively. v1 supports this. The
   pre-flight regex on `lower` / `upper` permits identifier-like
   strings up to 64 chars.

5. **Idempotence under repeated runs.** Same input + same algorithm
   should produce the same certificate. SymPy is deterministic for
   these algorithms; no seed needed. Verified by the probe in 25.5.

---

## Cost / benefit summary

**Total effort:** ~6 days end-to-end (2 wrapper + 1 client + 1 tool
+ 1 panel + 1 acceptance).

**Operational tax:** Python 3 + SymPy on the deployment host (~80
MB). One subprocess spawn per call (~150 ms + 50 ms warm SymPy
import). One more script directory in the operator runbook. No
new long-running service.

**Expected impact.** The `THEOREM` label becomes reachable. In the
existing corpus, math-domain papers occasionally surface
hypergeometric-sum identities (the `MathConjectureSubstrate`
generates divisor-sum and binomial-style conjectures); today
those publish as `CONJECTURE` regardless of whether a proof is
trivial. After Phase 25, the trivially-provable ones publish
honestly as `THEOREM`. Beyond label honesty, this gives the
EmergentTerrain corpus its first claim to **machine-verifiable
results** — a credibility step the panel can't deliver on its own.

**Recommendation.** Schedule 25.1 + 25.2 together (engine + binding).
25.3–25.5 land in successive days. Flag `app.sympyProver.enabled`
defaults OFF; flip after the library coverage probe passes and at
least one synthetic end-to-end paper produces a `THEOREM` label
correctly.
