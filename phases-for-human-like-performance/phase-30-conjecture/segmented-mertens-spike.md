# Spike scope — validated segmented `M(x)` beyond `NT_MAX_N`

A flag-gated spike to compute the cumulative Mertens function `M(x) = Σ_{n≤x} μ(n)` for
`x ≫ NT_MAX_N (2×10⁶)`, **trusted only after cross-validation against the dense SPF sieve on the
overlap range `[0, NT_MAX_N]`**. This is the first concrete realisation of the *self-verifying
algorithm discovery* loop in [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md)
§7 — but deliberately the *minimal* version: it builds the **verification moat** with one
known-correct method, and proves the gated-tool plumbing stays scoped to a single invariant class.
It does **not** yet ask an LLM to invent a method (that comes after the moat exists — you can't
validate a proposal before the validator is built).

> Scope discipline: this rides a **per-invariant capability gate**, not a prompt change. Nothing in
> the shared narration prompt (`ResearchNarrationService` revision rules) is touched. See
> [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) for why the M(x) class
> alone is the target, and the §"Scope boundary" below.

---

## 1. Goal & non-goals

**Goal.** A `nt_mertens_segmented(x)` sidecar function + a Java-side validated wrapper that:
1. reproduces dense-sieve `M(x)` **exactly** at checkpoints `≤ NT_MAX_N` (the moat), and
2. returns `M(x)` for `x` up to a configured ceiling (spike target: `~10⁸`, in-call) under a hard
   work/time guard,
3. exposed as a tool **only** to mertens-class `CONJECTURE_INVESTIGATION` papers, **only** when the
   flag is on.

**Non-goals (explicit scope boundary).**
- ❌ The sublinear `O(x^{2/3})` combinatorial `M(x)` (Deléglise–Rivat lineage). That's the *follow-on*
  that reaches `10¹²–10¹⁵`; the spike uses the simpler **segmented Möbius sieve** (linear, reaches
  `~10⁸` in a 30 s call) because the spike's risk is the *moat + plumbing*, not the algorithm.
- ❌ Reaching the counterexample regime (`≫10¹⁴`). Out of reach by any feasible computation
  ([`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) §7 ceilings). `M(10⁸)` is
  *less trivial*, not *solved*.
- ❌ LLM-proposed algorithms. The spike wires the validator + one reference method; LLM creativity
  plugs into the same validator in a later phase.
- ❌ Any change to the conjecture vocabulary, the mining engine, or the shared narration prompt.

## 2. Why segmented sieve reaches further (and where it stops)

The `2×10⁶` ceiling is the **dense `spf[]` array** (`nt_factor` throws past it). A *segmented* Möbius
sieve never builds that array: it sieves `μ` over blocks `[a, a+B)` using only primes `≤ √(a+B)`.

- The existing sieve already holds **all primes ≤ 2×10⁶**, which suffices to sieve `μ` for any
  `n ≤ (2×10⁶)² = 4×10¹²` — so *memory* is not the limit.
- The limit is *time*: the segmented sieve is still `O(x)` total work. In a single 30 s, no-`using`,
  single-thread sidecar call that caps the spike at **`x ≈ 10⁸`** (a few seconds of sieving). Beyond
  that needs either the sublinear algorithm (follow-on) or a checkpointed multi-call/offline batch
  path (out of spike scope).

`|M(x)|` grows like `√x`, so values stay tiny (`|M(10¹²)| ~ 10⁶`) — **Int64 is safe**, no float, no
precision concern.

## 3. The moat — cross-validation against ground truth

The trust boundary, done **Java-side** so it's explicit and unit-testable with a mocked client:

```
checkpoints K = {10³, 10⁴, 10⁵, 10⁶, 1.5×10⁶, 2×10⁶}  (+ a few non-round offsets), all ≤ NT_MAX_N
for k in K:
    truth_k     = dense-sieve M(k)            # Σ μ(n) over the trusted SPF sieve
    candidate_k = nt_mertens_segmented(k)     # the method under test, restricted to [0,k]
    require truth_k == candidate_k            # EXACT integer equality
reject (and log the discrepancy as a counterexample) on any mismatch;
only on all-pass is nt_mertens_segmented(x) for x > NT_MAX_N returned/trusted.
```

- `M(10⁶) = 212` is **reproducible in-range** (within the dense sieve), so it's the anchor checkpoint
  that needs no external reference.
- Beyond-range sanity: compare a couple of returned values to **published `M(10ⁿ)`** figures (verify
  against literature to the 2026 cutoff; e.g. `M(10⁶)=212` in-range, `M(10⁹)=−222` out-of-range) — a
  secondary check, not the trust gate (the overlap equality is the gate).
- Non-round offsets in `K` guard against a method that's accidentally correct only at powers of ten.

## 4. Flags (all default OFF)

Under `app.research.conjectureMiner.*` (or a new `app.numberTheory.*` namespace — decide at impl):

| flag | default | meaning |
|---|---|---|
| `segmentedMertensEnabled` | `false` | gates the whole capability (sidecar call + tool) |
| `segmentedMertensMaxX` | `100_000_000` | hard ceiling on requested `x` (work guard; reject above) |

Tool *exposure* is additionally gated on the conjecture invariant being mertens-class — see §6.

## 5. Files touched (scoped blast radius)

**Sidecar** (⚠️ recompile hazard — stop/restart 8088+8089 around any `src/` change; the running
process mmaps the `.ji` — see the `project_sidecar_recompile_crash` lesson):
- `../julia-math-sidecar/src/numbertheory.jl` — add `nt_mertens_segmented(x; block=...)` (pure base
  Julia, no `using`), plus a `nt_mertens_dense(x)` helper if not already trivially available for the
  in-range ground truth.
- `../julia-math-sidecar/src/handlers.jl` — a `mertens_segmented` request handler with the work guard
  (`x > segmentedMertensMaxX → ArgumentError`, mirroring the existing `NT_MAX_N` guards).
- sidecar unit test for the new function (exact match to dense `M` on `[0, 2×10⁶]` checkpoints).

**Java:**
- `JuliaMathClient` / `MathConjectureSubstrateDelegate` — a client method `mertensSegmented(x)`.
- `SegmentedMertensValidator` (NEW) — runs the §3 moat; returns a verdict + (on pass) a trusted
  `M(x)` accessor. Pure/unit-testable: mock the client, feed correct vs corrupted candidate values,
  assert accept vs reject. This is the spike's primary unit.
- `MathToolSuite` — a new tool (e.g. `mertens_at_scale`) gated on `segmentedMertensEnabled` **and**
  the conjecture invariant being mertens-class; offered to `CONJECTURE_INVESTIGATION` only (same seam
  as the existing `verify_conjecture` wiring). Its use-hint is scoped to that tool, **not** appended
  to the shared narration prompt.
- `application.yml` — the two flags, documented, default OFF.

## 6. The per-invariant gate (where "scoped, not common" is enforced)

The tool is added to the math tool set **only when**:
```
segmentedMertensEnabled
  && paperKind == CONJECTURE_INVESTIGATION
  && invariantOf(conjecture) ∈ MERTENS_CLASS        // {mertens_delta, mertens_per_sqrt_W}
```
`MERTENS_CLASS` is a small explicit set keyed on `MathInvariants` scalar names. Density/primality
conjectures never see the tool — they don't need it and would only invite hallucinated use. This
mirrors the existing per-scalar branching already in `MathToolSuite`.

## 7. Acceptance criteria

1. `nt_mertens_segmented(k) == dense M(k)` (exact) for every checkpoint `k ≤ NT_MAX_N` — sidecar test.
2. `SegmentedMertensValidator` **accepts** the correct method and **rejects** a deliberately
   corrupted one (negative test), logging the discrepancy — Java unit test, no live sidecar.
3. For `x ∈ {10⁷, 10⁸}` the call returns within the 30 s / work guard and matches the published
   reference value (manual/integration check; `x = 10⁹` only if it fits the guard).
4. Tool present **iff** flag ON **and** invariant is mertens-class; absent otherwise — Java test on
   the gate predicate.
5. Flag OFF ⇒ zero behaviour change (no new sidecar calls, tool absent). Existing conjecture tests
   stay green.

## 8. Phasing (build the moat before the creativity)

1. **Sidecar method + sidecar test** — `nt_mertens_segmented`, exact-match on overlap. (Isolated;
   restart sidecars around the change.)
2. **Java validator + unit tests** — `SegmentedMertensValidator`, the §3 moat, accept/reject. (No
   live sidecar needed; mock the client.)
3. **Gated tool + gate test** — `MathToolSuite` exposure under §6 predicate. Flag-gated OFF.
4. **Flip + watch** — enable on a mertens-class conjecture paper; confirm the tool validates in-range
   then reports `M(10⁸)`, and that the narrator frames it regime-honestly ("computed to 10⁸", not
   "solved").
5. **(Follow-on, out of spike)** — sublinear `O(x^{2/3})` `M(x)` for `10¹²–10¹⁵`; and the LLM-proposed
   -method variant routed through the *same* validator.

## 9. Risks

- **Sidecar recompile crash** — any `numbertheory.jl`/`handlers.jl` edit requires stop/restart of
  8088+8089; never recompile under the live process (`project_sidecar_recompile_crash`).
- **30 s single-thread ceiling** — caps the in-call reach at `~10⁸`; the work guard must reject
  larger `x` cleanly rather than time out. Larger scales are explicitly follow-on.
- **`using` filter** — the segmented sieve must be pure base Julia (it can be; no deps).
- **Regime overclaim** — `M(10⁸)` is below the counterexample scale; the narrator must say "computed
  to `x`", not "resolved the conjecture". Pairs with the regime-awareness framing in
  [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) §4.
- **Reference-value drift** — published `M(10ⁿ)` constants are recalled to a 2026 cutoff; the *trust
  gate* is the in-range overlap equality (sieve ground truth), not the external numbers, so the spike
  doesn't depend on them.

---

## Cross-references
- [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) — why analytic proofs are
  unverifiable here but novel *computations* are (the loop this spike implements).
- [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) — what the app's
  mertens bound is; why the M(x) class alone is the target.
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — the composition lane (the *other*,
  evaluation-reachable novelty target).
