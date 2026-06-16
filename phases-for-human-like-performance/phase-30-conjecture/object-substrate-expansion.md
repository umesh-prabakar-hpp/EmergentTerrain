# Phase 30 — substrate expansion: per-integer object generation + sieve reach

**Status: DESIGN + STAGE 1 (2026-06-16).** The vocabulary-expansion arc
([[novelty-ceiling-and-vocab-activation.md]]) conclusively closed the *scalar-recombination* lever — the
MDL gate is correct and admits 0 because the 25 window aggregates span that space (best candidate 5.27
bits short under the corrected cost). This phase attacks the substrate one level **down** (the per-integer
functions, where structure lives before aggregation) and one dimension **out** (the sieve reach), the two
substrate-bound limits identified in the novelty-ceiling analysis.

## Why one level down

Vocab-expansion recombines the 25 window aggregates (`mean_omega`, `abundant_density`, …) — but
aggregation smooths out the per-integer signal, so aggregates-of-aggregates rarely carry new structure.
The 25 scalars *span* that recombination. Genuine novelty lives in the per-`n` composition **before**
aggregation — a conditional density or a new arithmetic predicate the 25 aggregates can't represent. The
MDL gate would actually admit such an object scalar, because it compresses residuals existing vocabulary
can't.

## Architecture (confirmed)

Per-integer scalars are computed in the **sidecar sieve**
[`numbertheory.jl:nt_evaluate_window`](../../../julia-math-sidecar/src/numbertheory.jl) (a single pass
over `n ∈ [start, start+W)` accumulating from per-`n` primitives `nt_omega/nt_big_omega/nt_divisor_count/
nt_sigma/nt_mobius`), emitted as BASE scalars and **passed through** to
[`MathInvariants.BASE`](../../src/main/java/com/emergentterrain/research/conjecture/MathInvariants.java).
A new BASE object therefore touches three files in one commit, guarded by the §28.4 parity test:
1. `numbertheory.jl` — accumulate + emit in `nt_evaluate_window`.
2. `MathInvariants.java` `BASE` — add the name (pass-through).
3. `math_invariants.jl` `MATH_BASE_NAMES` — add the name (mirror).
…then **restart the sidecar** (the new scalar is dormant until the Julia process reloads —
[[project_mathinvariants_java_julia_mirror]]).

## Stage 1 (SHIPPED) — three hand-chosen new objects

To prove the new-object pipeline end-to-end before building a generator, three objects computable from the
per-`n` primitives already in the loop (no extra factorization), well-posed (natural densities):

| Object | Predicate | Expected | Role |
|--------|-----------|----------|------|
| `squarefree_density`       | `Ω(n) == ω(n)`      | → 6/π² ≈ 0.6079 | **validation** (known constant — proves the substrate computes correctly end-to-end) |
| `richly_divisible_density` | `d(n) > ω(n)²`      | uncharted        | **novelty** (no standard result for this predicate's density) |
| `omega_excess_density`     | `Ω(n) − ω(n) ≥ 2`   | uncharted        | **novelty** (density of integers with a cube or ≥2 squared primes) |

None reduces to an existing scalar; none is base-representation-dependent (so the well-posedness gate is
silent). They flow through the existing conjecture→verify→paper pipeline unchanged.

**Acceptance (next run):** (a) a paper rediscovers `squarefree_density → 0.6079 ≈ 6/π²` (substrate is
correct); (b) `richly_divisible_density` / `omega_excess_density` produce papers scoring panel-novelty
**past 0.4 on a non-rediscovery** (the first genuinely-new objects). If (b) lands, the lever is real →
build Stage 2/3; if only (a), the substrate computes but these particular predicates aren't novel-rich →
iterate object choices.

## Stage 2/3 (design) — the per-integer object GENERATOR

Mirror the vocab-expansion arc at the object level:
- **Stage 2** — a per-`n` predicate/aggregator grammar: primitives `{μ, d, ω, Ω, σ, n, isPrime, spf, lpf}`
  × predicates `{<, >, ==, ∣, mod}` × aggregators `{density, conditional-mean, max-gap-between}`. Enumerate
  candidates, evaluate over the window via the sieve, feed as conjecture targets.
- **Stage 3** — MDL admission for objects (reuse `MdlGate` + the type-aware cost + permutation null +
  triviality/self-reference filters, all hardened this session). A new object admits when it compresses a
  residual no existing scalar does — which, unlike scalar-recombination, is genuinely possible here.
- Reuses the whole hardened pipeline; the only new surface is the per-`n` evaluation in the sieve.

## Sieve reach (the second substrate limit)

The cap is `ConjectureVerifier.MAX_N = NT_MAX_N = 2×10⁶` — the dense SPF array `nt_factor` indexes
(`nt_factor` throws above it). Two extensions:

- **STAGE 1 (SHIPPED) — lockstep dense bump 2×10⁶ → 2×10⁷.** A 10× larger SPF array (Int32, ~80 MB; build
  <0.5 s), same correct algorithm, no new code. `NT_MAX_N` (Julia) and `MAX_N` (Java) bumped together (the
  doc's stated coupling). Immediately gives every scalar — including the cumulative
  `dirichlet_error`/`mertens` (computed via the O(√x) hyperbola method, not the dense array) — a 10× larger
  held-out range, so a held bound or an oscillation envelope is a *much* stronger empirical statement
  against an open problem.
- **STAGE 2 (design) — segmented window sieve for ≥10⁹.** The dense array is unnecessary for a *window*:
  factor `[L, L+W)` with primes up to √(L+W) via a segmented sieve (for L=10⁹, that's primes ≤ ~31623 — a
  tiny fixed sieve). Decouples window evaluation from `NT_MAX_N` entirely → window-local scalars (densities,
  means, divisor extremes) reach 10⁹⁺. The cumulative scalars (`mertens`) stay on the existing frontier
  path. This is the real reach unlock; deferred because it's a careful segmented-sieve implementation +
  verification, not a one-line bump.

## Honest ceiling (unchanged)

Even at 2×10⁷ (or 10⁹ via Stage 2), results remain *measurements*, not proofs — but a held bound /
converged density on a **genuinely new object** at higher x is the realistic prize the novelty-ceiling
analysis named: a novel computational measurement against an open question, not a theorem. New objects
(Stage 1–3) raise the *novelty* ceiling; sieve reach raises the *strength* of each measurement. Convention:
each stage flag/restart-gated, acceptance bar = next-run review.
