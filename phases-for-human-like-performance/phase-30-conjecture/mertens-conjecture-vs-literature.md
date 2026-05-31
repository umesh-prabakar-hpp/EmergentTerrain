# The app's `mertens_per_sqrt_W` conjecture vs. real Mertens-function research

This note documents (a) what the EmergentTerrain conjecture flow actually computes for the
`mertens_per_sqrt_W` topic, and (b) how that differs from genuine Mertens-function research. It exists
because room `b72c4e10` overwhelmingly produces papers refuting this one bound, and it is easy to
mistake the activity for "doing Mertens research." It is not — for the structural reasons below.

> The triviality is **not** specific to this conjecture — it is one of 212 identically-shaped mined
> inequalities. For that engine-level generalisation see
> [`conjecture-engine-triviality.md`](conjecture-engine-triviality.md).

Source of truth for the formula: `research/conjecture/MathInvariants.java`,
`research/conjecture/OeisCatalog.java`, `research/conjecture/ConjectureEngine.java`,
`research/conjecture/BindingRegimeProbe.java`, and `service/MathToolSuite.java` (tool output).

---

## 1. What the app actually computes

**Conjecture (inequality):**

```
mertens_per_sqrt_W  ≤  k · log_window_start + c        mined: k = 2.0000, c = −7.7621
```

**LHS — `mertens_per_sqrt_W`** (`MathInvariants.java:60-63`):

```
mertens_per_sqrt_W = mertens_delta / √(window_size)
```

**`mertens_delta`** (`OeisCatalog.java:48`, OEIS A002321) — the **windowed** Mertens sum, i.e. the
Möbius function summed over the block:

```
mertens_delta = Σ_{n = window_start}^{window_start + window_size − 1} μ(n)
```

computed by the Julia sidecar's number-theory window aggregator (SPF sieve → μ; stubbed in unit tests).

**RHS scalar — `log_window_start`** (`MathInvariants.java:52-55`):

```
log_window_start = log₁₀(window_start)        ← base-10
```

**Fully expanded:**

```
( Σ_{n=window_start}^{window_start+window_size−1} μ(n) ) / √(window_size)
        ≤  2.0000 · log₁₀(window_start) − 7.7621
```

### Worked check (P-b72c-20, call_1)
At `start=200000, window_size=1000`, the tool reported `lhs = +0.66408`. Recover the raw sum:
`0.66408 × √1000 ≈ 21.0` → the Möbius sum over `[200000, 201000)` is **+21** (integer ✓). At
`start=2000`: `−0.34785 × √1000 ≈ −11.0` → sum **−11** ✓. The `√1000 = 31.62` normalizer is also why the
LHS prints as messy 5-decimals rather than clean hundredths — it is the formula, not precision loss.

### Two known wrinkles (surfaced in the b72c4e10 review)
- **Normalizer is `√(window_size)`, not `√(window_start)`** — the "W" in `_per_sqrt_W` is
  `_window_size`. Narrators have mis-stated this (P-b72c-18); the `DEFINITION_GROUNDING_RULE` targets it.
- **`log` is base-10**, while the RH-conditional motivation `|M(x)| = O(√x log x)` uses natural log —
  the `verify_conjecture` output now labels the base and prints the `k/ln(10)` slope to prevent the
  mismatch.

---

## 2. The conjecture *flow* (why it recirculates and always "fails")

- **Mined, not derived.** `ConjectureEngine.seed` / `tightestKC` fit the tightest `(k, c)` such that the
  inequality holds across **the run's sample windows** — which sit at large window starts. No theory.
- **Stated universally.** The `Inequality` record carries only `(lhs, rhs, k, c)` — **no domain field**.
  A bound evidenced only on large starts is asserted over the whole allowed domain (up to `MAX_N`).
- **Never retired.** `ConjectureEngine.update` re-checks only against the same large-start sampling
  regime, where it holds, so every mine pass logs `0 retired (counterexample)`. Published-paper
  refutations are **not** fed back to the store.
- **Trivially refuted in papers.** `verify_conjecture` + the `BindingRegimeProbe` test `start=2000`,
  where the RHS `2·log₁₀(2000) − 7.7621 = −1.16` is **negative** — any positive block sum violates by
  construction. Below `x₀ ≈ 7600` the RHS is negative, so failure there is arithmetic, not arithmetic
  *number theory*.
- **Capped at `MAX_N = 2,000,000`** — far below any regime where real Mertens phenomena occur.

Net: an immortal, over-scoped curve fit that is re-investigated and re-refuted indefinitely.

---

## 3. Comparison to genuine Mertens research

The most fundamental difference: **the app does not bound `M(x)` at all.** All real lines below concern
the *cumulative* Mertens function `M(x) = Σ_{n≤x} μ(n)` (or `M(x)/√x`). The app bounds a *windowed block
sum normalized by √(window size)* — a local increment of the Mertens walk, standardized by √W. Under the
standard random model for μ, such a block sum has std `~ √(6/π²)·√W`, so `block_sum/√W` is an **O(1),
mean-≈0 fluctuation**: a standardized noise term that carries almost none of the structure of `M(x)/√x`.

### 1. Explicit analytic bounds — e.g. Lee & Leong (2022), `M(x) ≪ x·exp(−c√log x)`
Rigorous, from explicit zero estimates of ζ(s); bounds the cumulative function by something growing
almost like `x`. **App:** bound is linear in `log₁₀(window_start)` with data-mined constants and no
derivation. There is no analog in the literature for "bound a block sum by a linear function of log of
*where the block starts*" — to leading order a block sum's size is independent of `x₀`, so the
`x₀`-dependence is a pure fitting artifact.

### 2. Computational / extremal bounds — e.g. Hurst (2016), `limsup M(x)/√x > 1.8`, `liminf < −1.8`
Closest *in spirit* (both computational), opposite *in substance*. Hurst-type work computes `M(x)` to
~10¹⁶ to establish **genuine extrema** and shows `M(x)/√x` is not constant-bounded (conjectured
unbounded). **App:** studies the flat block statistic, not `M(x)/√x`; is capped at `2×10⁶`
(8+ orders below where extrema live); its "worst violations" (≈ +1.86 near start 7000) are O(1) noise
excursions, not the structural cumulative extrema Hurst establishes.

### 3. Mertens-type conjecture for number fields — Hu, Kaneko, Martin, Schildkraut (2024)
Its lesson — *naïve global "always below this curve" Mertens bounds systematically fail unless heavily
modified* — is exactly what the app embodies **by accident**. It mines a clean global inequality,
asserts it un-domained, and refutes it — but for the trivial sign reason (negative RHS below
`x₀≈7600`), not the deep large-x / generalized-setting reason the paper is about.

### 4. Small-counterexample localization — "first failure can be < exp(~10⁴⁰)"
These make the profound point that *computational evidence can look perfect for astronomically long*
before a counterexample appears (none below ~10¹⁶ for the Mertens conjecture, yet one provably exists
below ~`exp(1.6×10⁴⁰)`). This is the question the app **cannot even ask**: `MAX_N = 2×10⁶` sits far
below 10¹⁶, so it has zero access to the regime where "does it eventually fail, and where?" plays out.
Its refutations are instant and small-x.

### Summary table

| Dimension       | Real Mertens papers (1–4)                              | This app's "conjecture"                         |
|-----------------|--------------------------------------------------------|-------------------------------------------------|
| Quantity        | cumulative `M(x)` / `M(x)/√x`                           | windowed block sum `/√(window_size)` — O(1) noise |
| Bound form      | `x·exp(−c√log x)`; or `M/√x` unbounded extrema          | linear in `log₁₀(window_start)`, no analog       |
| Constants       | derived / proven                                       | data-mined `tightestKC` fit, no derivation       |
| Domain claimed  | their regime stated; failures studied at large x       | universal (no domain field); refuted trivially at small x₀ |
| Regime reached  | 10¹⁶ (extrema); exp(10⁴⁰) (counterexample bounds)      | capped at `MAX_N = 2×10⁶`                         |
| Epistemic output| tighter bounds, new extrema, generalization, locale    | "REFUTED" papers about a self-mined artifact     |

---

## 4. Resource scaling by `window_start` magnitude

Two genuinely different costs answer two readings of "solving this problem":

- **(A) the app's actual per-window check** at start `x` — sieve μ over one block, which only needs
  primes up to `√x` (segmented bit-sieve): memory ≈ `√x` bits, time ≈ `√x` ops.
- **(B) reaching the regime where the real Mertens question lives** — compute the *cumulative*
  `M(x) = Σ_{n≤x} μ(n)` up to `x`: naive `O(x)`, or single-point `~x^{2/3}` via the Deléglise–Rivat
  algorithm (how Hurst-type work reached 10¹⁶).

**Assumptions:** one optimized core at ~10¹⁰ ops/s; segmented bit-sieve (prime base to `√x`); D–R for
single-point `M(x)` (~`x^{2/3}` time, ~`x^{1/3}` space).

| `window_start` x | √x        | (A) sieve-base mem ≈√x bits | (A) per-window ≈√x ops | (B) `M(x)` naive O(x) | (B) `M(x)` D–R ~x^{2/3} | regime                                |
|------------------|-----------|-----------------------------|------------------------|-----------------------|-------------------------|---------------------------------------|
| 10⁶              | 10³       | 120 B                       | 0.1 µs                 | 100 µs                | 1 µs                    | **≈ app `MAX_N` = 2×10⁶**             |
| 10⁷              | 3.2×10³   | 400 B                       | 0.3 µs                 | 1 ms                  | 5 µs                    |                                       |
| 10⁸              | 10⁴       | 1.2 KB                      | 1 µs                   | 10 ms                 | 22 µs                   |                                       |
| 10⁹              | 3.2×10⁴   | 3.9 KB                      | 3 µs                   | 100 ms                | 100 µs                  |                                       |
| 10¹⁰             | 10⁵       | 12 KB                       | 10 µs                  | 1 s                   | 0.5 ms                  |                                       |
| 10¹¹             | 3.2×10⁵   | 39 KB                       | 32 µs                  | 10 s                  | 2 ms                    |                                       |
| 10¹²             | 10⁶       | 120 KB                      | 100 µs                 | 1.7 min               | 10 ms                   |                                       |
| 10¹³             | 3.2×10⁶   | 390 KB                      | 0.3 ms                 | 17 min                | 46 ms                   |                                       |
| 10¹⁴             | 10⁷       | 1.2 MB                      | 1 ms                   | 2.8 hr                | 0.2 s                   |                                       |
| 10¹⁵             | 3.2×10⁷   | 3.8 MB                      | 3 ms                   | 1.2 days              | 1 s                     |                                       |
| 10¹⁶             | 10⁸       | 12 MB                       | 10 ms                  | 12 days               | 4.6 s                   | **computed frontier (extrema)**       |
| 10¹⁸             | 10⁹       | 120 MB                      | 0.1 s                  | 3.2 yr                | 1.7 min                 |                                       |
| 10²⁰             | 10¹⁰      | 1.2 GB                      | 1 s                    | 320 yr                | 36 min                  |                                       |
| 10³⁰             | 10¹⁵      | 110 TB                      | ~1.2 days              | 3×10¹² yr             | ~320 yr                 | counterexample-localization territory |
| 10⁴⁰             | 10²⁰      | 11 EB                       | ~320 yr                | 3×10²² yr             | ~1.5×10⁹ yr             | ~ first-counterexample upper bounds   |
| exp(10⁴⁰)        | exp(5×10³⁹) | physically impossible¹    | impossible             | impossible            | impossible              | proven counterexample ceiling         |

¹ `√x = exp(5×10³⁹)` is a number with ~2×10³⁹ decimal digits; even *indexing* that many cells exceeds
the ~10⁸⁰ atoms in the observable universe by a factor of ~10^(2×10³⁹).

**What the table shows:**

1. **The app's cap is a config choice, not a resource wall.** Column (A) — what the app actually
   computes — is trivial even at 10¹⁶ (≈12 MB, ≈10 ms per window). Nothing technical stops the windowed
   statistic running far past `MAX_N = 2×10⁶`; that ceiling is arbitrary.
2. **But the app computes the cheap (wrong) thing.** The `√x` scaling is cheap precisely because it only
   touches one local block. The real Mertens phenomena live in the cumulative `M(x)` — column (B) —
   which is `O(x)` naive or `~x^{2/3}` single-point, and that is what caps real research near 10¹⁶.
3. **The interesting regime is physically out of reach.** A counterexample provably exists below
   ~`exp(10⁴⁰)` but none has been found below ~10¹⁶: even single-point `M(x)` is centuries of compute by
   10²⁰ and exceeds the age of the universe before 10⁴⁰, and `exp(10⁴⁰)` exceeds the universe's storage
   outright. This is why computational evidence "looks perfect for a long time" — the failure is
   physically unreachable.

Blunt takeaway: lifting `MAX_N` lets the app push column (A) cheaply, but it still would not be *doing
Mertens research* — just sampling the same flat block statistic at bigger `x`. The cost that actually
matters (column B, to the counterexample regime) is unreachable by any hardware.

---

## 5. Honest characterization & the one path toward real research

The app runs a **closed empirical loop**: mine the tightest linear-in-log fit on a flat windowed
statistic over a small sampled range, assert it universally, then have an agent re-discover that an
un-domained curve fit fails outside its fit region (mostly at small `x₀`, for the trivial sign reason).
It happens to *re-derive the meta-lesson of papers 3 and 4* — "clean global Mertens-type inequalities
don't hold" — but as an artifact of over-scoped curve-fitting, not as a study of `M(x)`. This is why the
review consistently scores these papers novelty ≈ 0.2–0.45: correct refutations of a non-result.

The single path that would move the work toward genuine type-2 / type-4 territory — named by every
paper, executed by none — is to **refit on a domain-restricted large-start regime and characterize the
transition threshold `L₀`**, rather than re-refuting the universal claim at small `x₀`. Even then, the
`MAX_N = 2×10⁶` ceiling keeps it far from where the actual mathematics (extrema at 10¹⁶, counterexample
localization at exp(10⁴⁰)) lives. Lifting that ceiling and switching the bounded quantity from the
windowed block sum to the cumulative `M(x)/√x` would be the prerequisites for engaging the real
questions.

> Caveat: the external numbers (1985 Odlyzko–te Riele disproof of `|M(x)|<√x`; `limsup/liminf` of
> `M(x)/√x` exceeding ≈ ±1.8; no counterexample below ~10¹⁶; first counterexample below ~`exp(10⁴⁰)`)
> are recalled to a 2026 knowledge cutoff; the exact localization constants have been sharpened over
> time. The structural comparison does not depend on the precise constants.
