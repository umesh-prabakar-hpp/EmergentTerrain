# Phase 26 — Constant Recognition Pass (PSLQ + OEIS Superseeker + LLL)

**Status (2026-05-22):** **SHIPPED + FLAG ON (2026-05-22)** —
`app.julia.recognise.enabled: true`. *(Scope qualifier, 2026-06-04:
infrastructure shipped + flag ON; the narration-side `recognise()`
invocation is still gated by a follow-on slice — see the CAVEAT — so
the pass is wired but not yet driving narration.)*

> **Flag-name note (2026-06-04):** body sections below predate the
> Julia-consolidation pivot and still reference `app.recognise.*`
> (the abandoned subprocess/FPLLL design). The live flag namespace is
> `app.julia.recognise.*` (LLL sub-flag `app.julia.recognise.lll.enabled`,
> deliberately OFF, per-paper opt-in).

Bundles three closely-
related engines from `reference-conjecture-engines.md` into one
phase because they all answer variants of the same question:
**"this number / sequence the refinement just produced — is it
something already known?"**

> **Update (2026-05-23):** Implemented and live. New `recognise/`
> package: `PslqRecogniserClient`, `OeisRecogniserClient`,
> `LllRecogniserClient`, `ConstantRecognitionService`; sidecar
> `/recognise/sequence` route. Probe passed 30/30 constants + 30/30
> sequences (2026-05-22). CAVEATS: `recognise.lll.enabled` stays OFF
> (higher false-positive surface); the narration-side `recognise()`
> invocation is still gated by a follow-on slice (orchestrator wired,
> not yet driving narration). Original DRAFT framing below is retained
> as historical context.

---

## Decision update — 2026-05-22 (supersedes subprocess architecture below)

**All three recognition engines now host inside the Julia sidecar
(`julia-math-sidecar`, port 8088). No Python subprocess spawned from
the JVM. No FPLLL native binary.** See
`reference-conjecture-engines.md` § "Decisions — Julia consolidation
(2026-05-22)" for the framing.

Specifically:

- **PSLQ:** native Julia implementation against `Arb` / `BigFloat`
  (~few-hundred-line port of the standard algorithm). PyCall→mpmath
  is the *fallback* if burn-in shows precision regression vs a
  reference run. The sections labelled "PslqRecogniserClient + mpmath
  subprocess" in §26.2 below describe the **historical** plan and do
  not reflect the current direction.
- **LLL:** `Nemo.jl` wraps FLINT's LLL — no FPLLL build, no
  `scripts/lll_recogniser/` C++ binary, no subprocess dependency.
  Replaces §26.3's "wrapped through a small C++ binary" plan
  outright. §26.3 becomes a sidecar endpoint behind
  `app.julia.recognise.lll.enabled` (default off).
- **OEIS Superseeker:** HTTP call hosted in the Julia sidecar.
  Cache writes flow back to Postgres through the JVM's
  `OeisCatalog` repo (single cache source of truth, even though
  the lookup originates in Julia). §26.1's overall pipeline stands
  — only the caller changes.

**New endpoints expected on the sidecar:**

| Route | Replaces | Returns |
|---|---|---|
| `POST /recognise/constant` | PslqRecogniserClient subprocess | `{recognized, name, basis, citation}` or `none` |
| `POST /recognise/sequence` | OeisSuperseekerClient HTTP+cache | `{recognized, oeis_id, transform_chain, citation}` or `none` |
| `POST /recognise/minpoly` | LllRecogniserClient binary | `{minPoly, degree, evidence}` or `none` |

**Java client surface stays:** `ConstantRecognitionService` +
`{Pslq,Oeis,Lll}RecogniserClient` peers. They all call the sidecar
via the same HTTP shape `JuliaMathClient` uses; safety isolation
(timeout, denylist, value-range checks) moves into the sidecar.

**Flag knob:** `app.julia.recognise.enabled` (master flag, default
off), with sub-flags `app.julia.recognise.lll.enabled` for the LLL
layer. Matches the flag-gated-dormant pattern.

**Effort impact:** roughly neutral. Saves the FPLLL build/distribute
work (~1 day) and the JVM-side subprocess plumbing for two engines
(~0.5 day), but pays for native PSLQ in Julia (~1 day). Net ~6-7
days, LLL included — slightly under the original "~8.5 with LLL"
estimate.

Detailed phasing rewrite is deferred until the phase starts; §26.4
(`ConstantRecognitionService` + narration wiring) and §26.5
(conjecture-miner integration) and §26.6 (acceptance + rollout) are
not affected by this change.

---

**Motivation.** `ResearchNarrationService.maybeRefineComputation`
already runs an optional pass that turns LLM-suggested numerics
into refined numerics (via Julia sidecar / Symja / GPU). What
happens next today: the refined value flows into the narration as
a decimal. Most of the time that's fine. But when the refinement
produces something like `1.2020569...` or the sequence
`1, 1, 2, 3, 5, 8, 13`, the narration cites a decimal where it
should cite **ζ(3)** or **the Fibonacci numbers**. The OEIS gate in
Phase 23.2 (`OeisCatalog`) covers a narrow constant map; full
recognition needs the actual engines.

This phase adds a thin **recognise** stage right after refinement.
Three engines layered cheapest-first:

1. **OEIS Superseeker** (integer-sequence input) — cheap HTTP/local
   transforms + database lookup, sub-second.
2. **PSLQ via mpmath** (real-constant input) — integer-relation
   search against a basis of known constants, seconds.
3. **LLL via FPLLL** (deeper constant search, optional) — lattice
   reduction for closed-form recovery when PSLQ alone is
   insufficient.

Each layer reports `recognized=<name>` with a citation handle the
existing citation validators understand, or `none`. The narration
template then chooses prose ("equal to ζ(3) ≈ 1.20205...") over
naked-decimal prose, and a `CitationEvidence` row is added to the
paper's audit chain.

---

## Scope

What Phase 26 adds:

| Today | After Phase 26 |
|---|---|
| `maybeRefineComputation` returns a refined numeric, narration prints it | New `recognise` step calls Superseeker / PSLQ / LLL; if a known constant matches, the narration cites it by name |
| `OeisCatalog` is a hand-curated constant map of ~30 entries | Superseeker covers the full OEIS plus its 18 standard transforms; PSLQ covers basis sets of constants beyond OEIS |
| Citation validators check that named constants in prose are correct | New `RecognitionCitationValidator` confirms that any `cite-recognised:*` claim matches a recognition-engine result in the audit trail |
| `CONJECTURE_INVESTIGATION` papers that mine math conjectures sometimes restate known closed forms (caught only by the v1 OEIS map) | Mined conjectures are pre-filtered through the recognition pass before entering the queue; rediscoveries of OEIS / Inverse-Symbolic-Calculator constants are demoted from `CONJECTURE` to `KNOWN_RESULT` |

What Phase 26 explicitly does **not** add:

- A new paper kind. Recognition is a metadata step; the resulting
  citation flows into the existing narration + validator surface.
- A public OEIS submission flow. The recognition is **input-side**
  (use OEIS to identify) not **output-side** (submit new
  sequences). Submitting is a manual operator decision.
- The Ramanujan Machine / continued-fraction search. Per
  `reference-conjecture-engines.md`, that engine wants cluster
  compute; out of scope for this phase.
- Plouffe's Inverter web service. Network round-trip + uncertain
  TOS; PSLQ in-process covers the same surface for our basis sets.
- Heavy LLL work (cryptanalysis-sized lattices). LLL stays at
  modest dimensions (≤30) where it's seconds, not hours.

---

## Architecture

```
ResearchNarrationService.maybeRefineComputation
  └── (after refinement) → ConstantRecognitionService.recognise(value, context)
                              │
                              ├── if value is a small integer sequence (≥4 terms):
                              │      OeisSuperseekerClient → /oeis/superseeker
                              │      → {sequence_name?, oeis_id?, transform_chain?}
                              │
                              ├── if value is a single real (≥15 digits precision):
                              │      PslqRecogniserClient → mpmath PSLQ
                              │      → {constant_name?, integer_relation?, basis_used?}
                              │
                              └── if PSLQ returns "none" but value has ≥30 digits:
                                     LllRecogniserClient → FPLLL
                                     → {polynomial_root?, minimal_polynomial?}

ConstantRecognitionService → returns RecognitionResult:
  PrimaryHit(engine, name, citation, evidence)   // OEIS A-number / mpmath basis / minpoly
  | NoMatch                                       // nothing recognised
  | LowConfidence(reasons)                        // PSLQ found something at borderline precision

Persistence:
  - new "recognitions" Postgres table, FK to paper_id, scored
  - RecognitionEvidence row in the paper's audit chain (consumed by
    citation validators)
```

**OEIS Superseeker.** Two implementations possible:

1. **Local script** — clone `github.com/oeis/oeisdata` (~150 MB
   stripe-down to b-files only), reimplement Superseeker's standard
   transforms (binomial, Möbius, Euler, differences, partial sums)
   in Python; query offline.
2. **HTTP** — call `https://oeis.org/search` / `/transforms`
   endpoints; rate-limited per OEIS TOS (best-effort, ~1 req/sec).

v1 ships **HTTP with a local fallback cache** (Postgres table keyed
on sequence-prefix hash). The local cache turns repeat queries into
sub-millisecond lookups; the live HTTP only fires on cache miss.
This is similar in spirit to the way `OeisCatalog` works today, but
the cache is dynamic instead of compile-time.

**PSLQ via mpmath.** Python subprocess pattern from Phase 25 reused:
JSON in, JSON out, RLIMIT-bounded. Default basis sets:

- **basic**: `{1, π, π², e, ln(2), ln(3), γ, ζ(3)}` — 8 elements
- **physics**: basic + `{ζ(2)=π²/6, ζ(5), Catalan, Apéry, golden ratio}`
- **combinatorial**: basic + `{e², log γ, log π, π·e, 1/π}`

The professor stage chooses the basis by paper domain. Per-call
PSLQ at 200-digit precision over an 8-element basis runs in ~1s.

**LLL via FPLLL.** Wrapped through a small C++ binary at
`scripts/lll_recogniser/`, called as a subprocess. Input is the real
value + degree bound; output is the minimal polynomial (when one
exists with rational coefficients in the requested degree) plus the
LLL-reduced basis as audit evidence. Used only when PSLQ at 200
digits returns nothing and the operator-configured
`app.recognise.lllEnabled` is on; default off because PSLQ covers
the common cases.

**Safety.** All three engines accept a real number or integer
sequence; none execute LLM-supplied code. The Python and C++
subprocesses use the same RLIMIT pattern as Phase 25's SymPy
prover. The HTTP-side OEIS call uses the existing `HttpClient`
with timeouts and is rate-limited at 1 request per second per the
OEIS TOS.

---

## Phasing

### 26.1 — `OeisSuperseekerClient` + cache

**Files:**
- `src/main/java/com/emergentterrain/research/recognise/OeisSuperseekerClient.java`
  — HTTP client to `oeis.org/search`. Method
  `lookup(int[] sequence)` returns `Optional<OeisHit>` containing
  the A-number, name, and matching transform chain (e.g.
  "binomial transform of A000045").
- `src/main/java/com/emergentterrain/research/recognise/OeisCache.java`
  — Postgres-backed cache. Schema: `(sequence_hash text primary key,
  oeis_id text nullable, name text nullable, transform text nullable,
  hit boolean not null, cached_at timestamp not null)`.
- `db/migration/V32__recognition_tables.sql` — Postgres migration
  creating `oeis_cache` and `recognitions` tables.
- Tests: 4 cases — Fibonacci recognises as A000045; partial sums of
  Fibonacci recognises via Euler transform; nonsense sequence
  returns NoMatch; cache returns the same answer on second call
  without HTTP.

**Acceptance:** lookup of `[1,1,2,3,5,8,13]` returns
`OeisHit("A000045", "Fibonacci numbers", "identity")`. Cache hit
rate ≥ 90% on a corpus-typical workload (the same handful of
sequences keeps coming up).

**Effort:** ~1.5 days.

### 26.2 — `PslqRecogniserClient` + mpmath subprocess

**Files:**
- `scripts/pslq_recogniser/main.py` — Python entry point; reads
  `{value, precisionDigits, basis}` JSON; runs `mpmath.pslq` against
  the basis; emits `{ok, recognized?, name?, integerRelation?,
  residual?, wallMs}`.
- `scripts/pslq_recogniser/basis.py` — basis-set definitions
  (`BASIC`, `PHYSICS`, `COMBINATORIAL`).
- `scripts/pslq_recogniser/sandbox.py` — RLIMIT setup mirroring
  Phase 25.
- `scripts/pslq_recogniser/test_main.py` — unit tests: recognises
  `1.6449340668482264...` as `π²/6` via BASIC basis; recognises
  `2.7182818...` as `e` directly; returns `none` on a random
  irrational; honors timeout; rejects unsafe input strings.
- `src/main/java/com/emergentterrain/research/recognise/PslqRecogniserClient.java`
  — subprocess wrapper, ProcessBuilder pattern from Phase 25's
  `SympyIdentityClient`.
- `src/main/java/com/emergentterrain/research/recognise/PslqRecognitionException.java`
- Tests: subprocess fake (bash shim) covers happy/timeout/
  parse-error paths in CI; live test runs against real mpmath when
  available.

**Acceptance:** `1.2020569031595942...` recognises as `ζ(3)`
under the BASIC basis at 200 digits in <2s. Counter fields
populate.

**Effort:** ~1.5 days.

### 26.3 — `LllRecogniserClient` + FPLLL binary (optional, gated)

**Files:**
- `scripts/lll_recogniser/recognize.cpp` — small C++ program
  linking FPLLL. Reads `{value, degreeBound, precisionDigits}`,
  attempts minimal-polynomial recovery via LLL-reduced basis,
  emits `{ok, minPolyCoeffs?, residual?, wallMs}`.
- `scripts/lll_recogniser/Makefile` — build with
  `-lfplll -lmpfr -lgmp`.
- `scripts/lll_recogniser/test/` — golden cases: `√2 + √3`
  recognises as root of `x⁴ − 10x² + 1`; a random transcendental
  returns `none`; degree-bound enforcement.
- `src/main/java/com/emergentterrain/research/recognise/LllRecogniserClient.java`
  — Java wrapper; config gated on `app.recognise.lllEnabled`
  (default false), `app.recognise.lllBinary` (path to the compiled
  binary).
- Tests: subprocess fake + live test.

**Acceptance:** with the binary built and the flag flipped on,
`√2 + √3` recognises correctly. With the flag off (default), the
client reports `disabled` and the recognise pipeline skips it.

**Effort:** ~2 days. The Makefile + portable build are the heavy
part; the C++ itself is ~100 lines.

### 26.4 — `ConstantRecognitionService` + narration wiring

**Files:**
- `src/main/java/com/emergentterrain/research/recognise/ConstantRecognitionService.java`
  — orchestration: try OEIS for sequences, PSLQ for reals, LLL for
  reals if PSLQ failed and LLL is enabled. Returns a unified
  `RecognitionResult` sum type.
- `src/main/java/com/emergentterrain/research/recognise/RecognitionEvidence.java`
  — audit record (engine, name, citation, evidence string, wallMs).
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — call `ConstantRecognitionService.recognise(...)` after
  refinement; if recognised, pass the result into the narration
  template's `recognised_constants` slot.
- Narration template edit: when `recognised_constants` is populated,
  prefer "ζ(3) ≈ 1.20206" over "1.20206" in prose; emit a
  `cite-recognised:A000045` style handle the validator can check.
- `src/main/java/com/emergentterrain/service/validation/RecognitionCitationValidator.java`
  — new validator: every `cite-recognised:*` handle in prose must
  match a `RecognitionEvidence` row in the audit chain.

**Acceptance:** a synthetic paper whose refinement produces
`1.2020569...` cites it as ζ(3) in prose, and the
`RecognitionCitationValidator` passes.

**Effort:** ~1.5 days.

### 26.5 — Conjecture-miner integration

The math miner currently filters via the hand-curated `OeisCatalog`
constant map. Replace that with a call into
`ConstantRecognitionService` when a mined conjecture's lhs/rhs
reduces to a numeric (e.g. `Σ_{k=1}^{n} 1/k² → π²/6`). Conjectures
that fully reduce to a recognised constant are demoted to a new
`KNOWN_RESULT` evidence label so they don't pollute the queue.

**Files:**
- `src/main/java/com/emergentterrain/research/conjecture/ConjectureMinerService.java`
  — replace `OeisCatalog.match` with `recognitionService.recognise`
  on numerical reduction; demote on hit.
- `src/main/java/com/emergentterrain/research/conjecture/EvidenceLabel.java`
  — add `KNOWN_RESULT` enum value.
- Migration of the existing `OeisCatalog` constants into the
  `oeis_cache` seed (so cold-start has the curated entries
  pre-populated and HTTP is a backstop, not a blocker).
- Tests: a conjecture asserting `Σ 1/k² = π²/6` mines, then is
  demoted with the audit row pointing at the PSLQ recognition.

**Acceptance:** the existing OEIS-rediscovery rate (target <10%
per Phase 23.8 calibration) does not regress, and the failure
mode shifts from "rediscovery slips into queue" to "rediscovery
demoted with audit trail".

**Effort:** ~1 day.

### 26.6 — Acceptance + rollout

Three checks:

1. **Recognition probe.** A scripted test in
   `scripts/recognition-probe/` feeds 30 known constants and 30
   known sequences; expect ≥27/30 sequences recognised via OEIS,
   ≥25/30 constants recognised via PSLQ (BASIC + PHYSICS basis),
   and the misses to fall into the expected categories
   (genuinely-novel constants, sequences not on OEIS).

2. **Citation precision in corpus.** After 30 papers ship with the
   flag on, audit the `cite-recognised:*` handles: every cited
   constant must point at a verifiable engine result in the
   `recognitions` table. Zero hallucinated citations.

3. **No narration regression.** Papers that previously read cleanly
   should not now read awkwardly because the recognition pass
   over-cited. Spot check: ≥80% of pre-existing math papers
   re-narrated under Phase 26 read as well or better.

**Acceptance bar:** all three. Citation precision is the
non-negotiable one — a recognised constant cited incorrectly is
worse than no recognition at all.

**Effort:** ~1 day probe + corpus run unattended.

---

## Open architecture questions

1. **OEIS HTTP rate-limit policy.** OEIS asks for ≤1 req/sec. The
   cache shoulders most of the traffic; the cold-start probe in
   26.6 should warm the cache for the common cases before
   production. If the live host gets rate-limited, the client
   serves NoMatch (not an error) so narration falls back to the
   naked decimal — degraded but not broken.

2. **PSLQ basis tuning per domain.** The three basis sets above
   cover most cases but a HISTORICAL ("number theory deep cuts")
   basis with Catalan numbers, Glaisher-Kinkelin, etc. might
   help advanced math papers. Hold off in v1; add per-paper
   audit data on what's missing.

3. **LLL binary distribution.** FPLLL needs to be built or
   installed on the deployment host. Document a `dnf install
   fplll-devel` step (Fedora) or apt equivalent. Skip the LLL
   slice if the operator doesn't want the dependency — the flag
   is off by default precisely for this reason.

4. **Precision floor for PSLQ input.** PSLQ needs ~30 digits to be
   meaningful on a small basis; the refinement step today returns
   double-precision (~15 digits). Wire `maybeRefineComputation`
   to also return an `mpfr`-precision variant (or recompute via
   Julia sidecar at 200 digits) when recognition is requested.
   The Julia sidecar already has `BigFloat` support; this is a
   one-line `evalprec` parameter addition.

5. **Should recognition fire on every numeric, or only "interesting"
   ones?** Firing on every numeric in narration is wasteful (most
   numerics are run-of-the-mill measurements). Default behaviour:
   fire only on numerics tagged by refinement as "compact-closed-
   form candidate" (a heuristic check: digit-pattern entropy below
   threshold, or stated as a series sum/integral that simplified
   to a numeric). The professor stage can also call recognition
   explicitly via a new tool (see open question 6).

6. **Should there be a dedicated `recognise_constant` tool?** Yes,
   probably — the professor stage might want to ask "is this
   value something I should look up?" without waiting for the
   automatic post-refinement hook. v1.5 work; not blocking.

---

## Cost / benefit summary

**Total effort:** ~8.5 days end-to-end (1.5 OEIS + 1.5 PSLQ + 2
LLL + 1.5 service + 1 miner integration + 1 acceptance). LLL is
optional and removes ~2 days if deferred.

**Operational tax:** Python + mpmath on the deployment host (same
runtime as Phase 25, so no incremental cost if 25 ships first).
Optional FPLLL native dependency for LLL. Postgres has two new
tables (`oeis_cache`, `recognitions`). One more outbound HTTP
endpoint to monitor (`oeis.org`).

**Expected impact.** Citation richness goes up across math-heavy
papers — naked decimals become named constants. The conjecture
miner's queue gets cleaner (rediscoveries demoted, not queued).
On the audit side, every named-constant claim in a paper now
points at a deterministic engine result, which strengthens the
"machine-verifiable" line the panel can credibly draw.

**Recommendation.** Ship 26.1 (OEIS Superseeker + cache) first; it
has the highest hit rate and the cheapest implementation. 26.2
(PSLQ) lands second and unlocks the constants case. 26.3 (LLL) is
a nice-to-have whose flag stays off until a specific paper
demands it. 26.4 + 26.5 + 26.6 follow in order. Default flag
`app.recognise.enabled: false` per the dormant-flag pattern.
