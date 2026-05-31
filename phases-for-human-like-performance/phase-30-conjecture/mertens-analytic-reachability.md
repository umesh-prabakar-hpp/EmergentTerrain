# Can the interesting Mertens regime be reached in *this* harness?

This note answers two related questions that come up once you accept that the app's
`mertens_per_sqrt_W` conjecture is verified only out to the sidecar's `NT_MAX_N = 2×10⁶`
(see [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) for what the
bound actually is, and [`conjecture-engine-triviality.md`](conjecture-engine-triviality.md) for why
it is one of 212 identically-shaped trivia):

1. **Can the *analytic* method** that humans used to settle the Mertens conjecture be run, and
   verified, inside this harness? (§1–§4)
2. If summation can't reach the interesting scale, **can frontier LLMs be asked to invent *novel
   numeric techniques* that deviate from known methods**, and try them here? (§5–§7)

The two answers point in opposite directions, and the contrast is the useful part: the harness's
evaluate-and-check model is a **wall** for analytic *proofs* but an **asset** for novel
*computations*.

Source of truth for the harness limits: `research/conjecture/ConjectureVerifier.java`
(`MAX_N = 2_000_000`, `MAX_WORK = 100_000`), `../julia-math-sidecar/src/numbertheory.jl`
(`NT_MAX_N`, `nt_factor` throws past it), `../julia-math-sidecar/src/julia_aliases.jl`
(`isprime` trial-division fallback), and the sidecar's `MAX_TIMEOUT_MS = 30_000` single-thread call
ceiling.

---

## 1. What the analytic method actually is

Odlyzko & te Riele (1985) disproved the Mertens conjecture `|M(n)| < √n` **without evaluating
`M(n)` at large `n`**. They bypassed evaluation:

1. The **explicit formula** links the summatory Möbius to the nontrivial zeros `ρ = ½ + iγ` of the
   Riemann zeta function. Heuristically,
   `M(x)/√x ≈ Σ_ρ 2·Re( x^{iγ} / (ρ·ζ'(ρ)) )` (plus lower-order terms).
2. Compute a few thousand zeros `γ` to high precision (Riemann–Siegel + root-finding).
3. Run **LLL lattice-basis reduction** to find a `log x` at which those cosine terms constructively
   align enough to push the sum past `1`, proving `limsup M(x)/√x > 1` (later sharpened to ≳ 1.8).

The output is a **non-constructive existence proof**: counterexamples exist, at *unspecified,
astronomical* `x` (implied bounds are of order `exp(10⁶⁴)`). It never yields an `M(n)` at a concrete
`n`. It yields a **theorem**.

That single property is what makes it unverifiable here, for two independent reasons.

## 2. Wall 1 — the harness can't host the computation

The Julia sidecar is structurally the wrong machine for the *ingredients*:

- **30 s per-call ceiling, single-threaded** (`MAX_TIMEOUT_MS`). High-precision zeta-zero
  computation + LLL reduction is not a bounded single call.
- **`using` is rejected by the safety filter** by design — and the method needs arbitrary-precision
  arithmetic (Arb/BigFloat) and a lattice library (Nemo/Hecke). The sidecar deliberately cannot load
  them.
- The sidecar is built around an **SPF sieve over concrete integers** — an *evaluation* engine.
  Nothing in it represents a zeta zero or an explicit-formula term.

This wall is "merely" engineering — you could precompute zeros offline and load a table. But that
only moves you to Wall 2, which is not engineering.

## 3. Wall 2 — the result doesn't fit the harness's epistemic model (the real wall)

The conjecture engine **verifies bounds by evaluation**: sample windows, fit the tightest
`lhs ≤ k·rhs + c`, check for a counterexample at concrete integers (`ConjectureEngine.update`,
`ConjectureVerifier`). Its entire vocabulary is scalar invariants over integer windows.

An analytic result is *non-constructive existence at unspecified x*. **There is no slot** in "sample
a window and check the inequality" to hold "a counterexample exists somewhere near `exp(10⁶⁴)`." The
harness cannot represent it, fit it, or refute/confirm it by its own machinery. The truth of the
statement does not live at any window the engine can sample.

So the mismatch is not "too slow" — it is a **category error**: an evaluation engine cannot verify a
proof-by-zero-distribution. For this harness the interesting Mertens regime is not far away; it is
*off the axis entirely*. No `NT_MAX_N` increase, sieve extension, or compute budget changes that.

## 4. The narration trap

A frontier LLM *knows* the analytic method from training and can write a fluent paragraph: "by the
explicit formula and LLL reduction, the conjecture fails." That is **reciting established
mathematics**, not the harness computing or verifying anything — the purest form of the
"100% known-result verification" failure this project already diagnosed
([`conjecture-engine-triviality.md`](conjecture-engine-triviality.md)). Reaching for analytic
methods would dress the known-result problem in deeper theory, making it *harder* to detect, not
closer to genuine novelty. Any "analytic Mertens" paper must therefore be **literature-grounded and
explicitly not-derived-here** (the regime-awareness fix; cf. Phase 30.4 literature retrieval).

---

## 5. The pivot: novel *computations* are verifiable even though novel *proofs* are not

Here the same evaluate-and-check model that blocked §1–§4 becomes the asset. The harness can run
arbitrary Julia (within the sidecar limits) and **cross-validate a proposed computation against the
trusted SPF sieve on the overlap range `[0, NT_MAX_N]`**. That overlap is a ground-truth oracle.

So the productive question is not "can the LLM prove something new" (no — Wall 2) but **"can the LLM
propose a *numeric method* whose output the harness can check, and that reaches further than naive
summation?"** (often yes). The distinction:

| Ask the LLM for…            | Harness can verify?                         | Verdict |
|-----------------------------|---------------------------------------------|---------|
| A non-constructive proof    | No — no evaluable claim (Wall 2)            | Out of scope |
| A faster/cleverer `M(x)` computation | **Yes** — check vs. sieve on `[0, 2×10⁶]`, then extend | **In scope** |
| A heuristic/estimator of `M(x)` | Yes — measure its error vs. ground truth | In scope (as an *observable*, not a bound) |

## 6. What "deviate from known methods" can realistically mean for Mertens

In increasing order of ambition (and decreasing order of reliability):

1. **Reach further with known-but-non-naive algorithms.** Segmented/mod-based Möbius sieves and
   combinatorial `M(x)` algorithms (Meissel–Mertens lineage, Deléglise–Rivat `O(x^{2/3})`) compute
   `M(x)` at `x ≈ 10¹²–10¹⁵` without the dense `O(x)` array. Frontier LLMs know these. They are
   "novel **to the harness**" (it currently only sums) and, crucially, they produce **checkable
   values**. This is the realistic win: lift Mertens from "trivially confirm at 2×10⁶" to "compute
   `M(x)` where the `√x` ratio's growth is actually visible."
2. **Invent a variant / combination.** Ask the model to modify such an algorithm (different blocking,
   a sublinear estimator, a randomized partial sum with an error model). Most proposals will be
   subtly wrong — and that is exactly what the overlap-range oracle filters out before any
   extrapolation is trusted.
3. **Genuinely new-to-mathematics technique.** Extraordinarily unlikely, and the harness cannot
   certify *novelty* (only correctness on the overlap). Treat any such claim as a known-result-style
   recitation until proven otherwise.

## 7. The design that makes this honest: self-verifying algorithm discovery

If this direction is pursued, the loop should be:

```
LLM proposes a numeric method for M(x)  (or another summatory invariant)
        │
        ▼
implement it in the sidecar (within the 30 s / no-`using` constraints, or an offline batch path)
        │
        ▼
cross-validate against the SPF sieve on the overlap range [0, NT_MAX_N]   ← ground-truth oracle
        │
   pass │ fail → reject (hallucinated method); feed the discrepancy back as a counterexample
        ▼
trust the extension to x ≫ NT_MAX_N; record M(x) at the new scale as the genuinely novel observable
```

The evaluation model is the **moat against hallucinated methods**: a proposed algorithm earns trust
only by reproducing ground truth where the two ranges meet. This turns "ask a frontier model to be
creative" from a recitation risk into a *checkable* search.

### Honest ceilings (so the doc doesn't overclaim)
- **It still won't *solve* the Mertens conjecture.** Even a perfect `O(x^{2/3})` `M(x)` reaching
  `10¹⁵` is short of where the conjecture provably fails; that regime is closed by §1's analytic
  result and unreachable by any feasible computation.
- **"Novel to the LLM" ≠ "novel to mathematics."** The harness certifies *correctness on the
  overlap*, never *novelty*. Claims of mathematical novelty need external literature grounding
  (Phase 30.4), not the harness's say-so.
- **Best targets are evaluation-reachable problems.** The composition / cross-density lane
  (steps 2 / 2.5; [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md)) is structurally closer to
  *verifiable* novelty than Mertens will ever be, because its interesting regime lives at windows the
  engine can actually sample. Mertens is the cautionary boundary case, not the model to optimise for.

---

## Cross-references

- [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) — what the app's
  `mertens_per_sqrt_W` bound is, and how it differs from real Mertens research.
- [`conjecture-engine-triviality.md`](conjecture-engine-triviality.md) — the engine-level
  generalisation (212 identically-shaped mined inequalities; known-result verification).
- [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) — the Phase 30 proposal
  these notes support.
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — the composition lane (a verifiable-novelty
  target, unlike Mertens).
