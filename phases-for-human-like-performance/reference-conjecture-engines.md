# Reference — Prior-Art Conjecture Engines

**Not a phase todo.** This is a reference table cataloguing automated
mathematical-discovery / conjecture-generation systems, kept here for
quick lookup when scoping work in `scripts/graffiti/`,
`scripts/conjecture-miner/`, and the
`phase-23-conjecture-driven-novelty.md` lane.

Closest spiritual cousins to the `scripts/graffiti/` inequality-conjecture
harness are **Graffiti** (Fajtlowicz), **Conjecturing** (Larson &
Van Cleemput), and **AutoGraphiX** — all use the Dalmatian-style "keep an
inequality only if no current example refutes it and it's not dominated
by an existing one" heuristic.

| Category | Engine | Origin / Year | What it conjectures | Method(s) |
|---|---|---|---|---|
| Numerical constants | Ramanujan Machine | Raayoni et al., 2021 | Continued-fraction formulas for π, ζ(3), Catalan, etc. | Meet-in-the-middle + gradient-descent search over CF parameter grids; PSLQ to certify |
| Numerical constants | PSLQ | Ferguson–Forcade, 1992 | Integer relations among real constants (basis of BBP-π) | Iterative integer-relation finder using partial-sums LQ decomposition |
| Numerical constants | LLL | Lenstra–Lenstra–Lovász, 1982 | Lattice-reduction integer relations; closed-form discovery | Polynomial-time lattice basis reduction (approximate SVP) |
| Numerical constants | Inverse Symbolic Calculator / Plouffe's Inverter | Plouffe, 1990s | Closed form for a given decimal | Precomputed constant table lookup + PSLQ/LLL against common basis sets |
| Numerical constants | OEIS + Superseeker | Sloane, 1964– | Identifies/extends integer sequences; suggests formulas | Database lookup + linear-recurrence / generating-function fitting |
| Graph theory | Graffiti | Fajtlowicz, 1980s | Inequalities between graph invariants (Dalmatian heuristic) | Dalmatian: generate invariant inequalities, prune by counterexample and dominance over a graph corpus |
| Graph theory | Graffiti.pc | DeLaViña, 2000s | Program-controlled successor to Graffiti | Dalmatian with programmable invariant pool and graph-set control |
| Graph theory | AutoGraphiX (AGX) | Caporossi & Hansen (GERAD), 1999 | Extremal-graph conjectures via VNS search | Variable Neighborhood Search over graph space, optimizing invariant functions |
| Graph theory | Conjecturing | Larson & Van Cleemput, 2017 | Generalizes Dalmatian to arbitrary objects (Sage) | Dalmatian heuristic over arbitrary object/invariant tuples in Sage |
| Graph theory | GrInvIn | Ghent group, 2000s | Teaching-oriented graph-invariant explorer | Interactive invariant evaluation backed by Nauty graph generation |
| General theory formation | AM | Lenat, 1976 | Elementary math concepts from heuristics | Frame/slot concept rep + agenda of "interestingness" heuristics |
| General theory formation | EURISKO | Lenat, 1983 | Self-modifying heuristic discovery | Heuristics that rewrite heuristics; slot-based concept evolution |
| General theory formation | HR / HR3 | Colton, 1999– | Number-theoretic concepts (e.g. refactorable numbers) | Production-rule theory formation; conjectures filtered by Otter/MACE/CSP |
| Symbolic regression | Eureqa | Schmidt & Lipson, 2009 | Equations from data (later DataRobot) | Genetic programming with Pareto front (accuracy vs complexity) |
| Symbolic regression | AI Feynman | Udrescu & Tegmark, 2020 | Physics-prior-guided formula recovery | Neural-net-tested symmetries/separability + dimensional analysis + recursive decomposition |
| Symbolic regression | PySR | Cranmer, 2020 | Open-source SR standard | Multi-population evolutionary search + simulated annealing + optional neural guidance |
| Geometry / proof (ML) | AlphaGeometry / AG2 | DeepMind, 2024–25 | IMO-geometry conjecture + proof | Neuro-symbolic: LLM proposes auxiliary constructions, symbolic deduction (DD+AR) closes proofs |
| Geometry / proof (ML) | AlphaProof | DeepMind, 2024 | Lean-backed olympiad algebra & number theory | AlphaZero-style RL search over Lean tactics; self-play on auto-formalized problems |
| Combinatorial identities | Zeilberger's algorithm / WZ | Zeilberger, 1990 | Hypergeometric identity discovery + proof | Creative telescoping: finds polynomial recurrence + certificate proving identity |
| Combinatorial identities | Gosper's algorithm | Gosper, 1978 | Closed-form hypergeometric summation | Decision procedure for hypergeometric antidifferences via rational-function ansatz |
| Combinatorial identities | Sister Celine's method | Fasenmyer, 1945 | Recurrences for hypergeometric sums | Undetermined-coefficients search for finite-difference recurrences |

---

## Method details

### Integer-relation & lattice methods

**PSLQ (Ferguson–Forcade, 1992).** Given a real vector
`x = (x_1, …, x_n)` known to high precision, PSLQ searches for a nonzero
integer vector `m` such that `m · x = 0`. It maintains an `n × (n−1)`
matrix `H` whose rows span the orthogonal complement of `x`, and at each
step performs (i) a Hermite-style integer reduction of `H` to keep
entries small and (ii) a swap of two adjacent rows chosen by a
geometrically-weighted rule on `|H_{ii}|`. When the smallest entry of
`x · A` (where `A` is the accumulated unimodular transform) drops below
the precision floor, the corresponding column of `A^{-T}` is the integer
relation. Numerically very stable; the de-facto tool that certified
constants like the BBP-π formula. Runs in `O(n^3)` per iteration with
precision growing linearly in `n`.

**LLL (Lenstra–Lenstra–Lovász, 1982).** Reduces a basis of an integer
lattice to one whose vectors are "short and nearly orthogonal" — the
first basis vector is guaranteed to be within a factor `2^{(n−1)/2}` of
the true shortest vector. Algorithm: alternately apply size reduction
(subtract integer multiples of earlier basis vectors to shrink Gram-Schmidt
coefficients into `[-½, ½]`) and the Lovász swap condition (swap
adjacent vectors if it shortens the GS projection). For integer
relations, embed the real vector `x` into a lattice with a large scaling
factor on `x`'s row — short vectors then encode candidate relations.
Polynomial time but lower precision efficiency than PSLQ; LLL's broader
fame is in cryptanalysis and polynomial factorization.

**Inverse Symbolic Calculator / Plouffe's Inverter.** Pipeline: (1) look
the decimal up in a precomputed table of ~200M known constants and their
algebraic combinations; (2) if no hit, run PSLQ/LLL against a chosen
basis (e.g. `{1, π, π², log 2, ζ(3), …}`) to test linear combinations;
(3) report any near-integer relation found. The "engine" is mostly the
table plus a thin search front-end — the cleverness is in basis choice.

### Continued-fraction search (Ramanujan Machine)

The core technique is **Meet-in-the-Middle (MITM)** over a parameterized
family of continued fractions `[a_0; b_1/(a_1 + b_2/(a_2 + …))]` where
`a_n, b_n` are low-degree polynomials in `n`. The engine enumerates
polynomial coefficients in a bounded range, truncates the CF at a
shallow depth, and hashes the truncated values; matches against a hashed
table of known constants (and small algebraic combinations) surface
candidates. A second variant uses **gradient-descent style search**:
treat coefficients as continuous, define a loss measuring distance from
a target constant, and descend until landing near an integer lattice
point. Hits are not proofs — PSLQ then confirms whether the candidate is
a genuine integer-relation identity, after which symbolic verification
or human proof closes it.

### Database + structural fitting (OEIS + Superseeker)

Two-stage. **Stage 1 — lookup:** the input sequence is queried against
~370k catalogued sequences; partial matches surface near neighbours.
**Stage 2 — structural fitting (Superseeker):** apply standard
transforms (binomial transform, Möbius/Euler transforms, differences,
partial sums) and re-query; fit linear recurrences with polynomial
coefficients; attempt algebraic generating-function recognition; check
for hypergeometric closed forms. The conjecture is whichever
transformation chain lands on a known entry or a simple closed-form
fit.

### Graph-theory conjecture engines

**Dalmatian heuristic (Graffiti family, Conjecturing).** Maintain a
working list `L` of inequalities of the form `f(G) ≤ g(G)` over a
chosen invariant pool `{f, g, …}` and a graph corpus `𝓒`. For each
candidate inequality `c`: (i) **truth filter** — reject if some `G ∈ 𝓒`
refutes `c`; (ii) **significance filter** — accept only if there exists
some `G ∈ 𝓒` where `c` gives the tightest bound among all surviving
inequalities (the "Dalmatian condition" — every kept conjecture must
"earn its spot" by being best on some graph). The list converges to a
Pareto-style frontier of non-dominated inequalities. Conjecturing
generalizes this to arbitrary `(object, invariant)` pairs, not just
graphs.

**Variable Neighborhood Search (AutoGraphiX).** Treat extremal-graph
discovery as optimization: given an invariant `φ(G)` and a graph size
`n`, search for `G* = argmax φ` over all order-`n` graphs. Neighborhoods
are defined by elementary moves (add/remove edge, rotate edge, move
vertex). VNS systematically widens the neighborhood when local search
stalls, then descends again. The resulting extremal graphs are inspected
for structural patterns, which are formalized into conjectures.

**Nauty-backed enumeration (GrInvIn).** Uses Brendan McKay's Nauty to
generate canonically-labelled graphs up to isomorphism (avoiding the
massive redundancy of unconstrained enumeration), then evaluates user-
selected invariants over the generated set. Pedagogical front-end rather
than autonomous engine — humans propose, the tool refutes.

### Theory-formation systems

**AM (Lenat, 1976).** Concepts represented as frames with slots
(definition, examples, generalizations, conjectures). An **agenda** of
tasks (e.g. "find examples of concept X", "find generalization of Y") is
prioritized by ~250 hand-coded "interestingness" heuristics — e.g.
"a concept is interesting if its definition is short relative to its
examples set." Top-priority task fires, runs its heuristic, and may
spawn new concepts/tasks. AM rediscovered prime numbers, Goldbach's
conjecture, unique factorization. Its discoveries were later argued
(notably by Ritchie & Hanna) to depend heavily on Lenat's choice of
seed concepts and LISP token frequencies rather than truly autonomous
discovery.

**EURISKO (Lenat, 1983).** AM's successor; the headline move is making
**heuristics themselves first-class concepts** that other heuristics can
inspect and rewrite. A heuristic that produces a useful concept is
itself rewarded; a heuristic that mutates another into a more productive
form is rewarded transitively. Famously won the Traveller Trillion
Credit Squadron tournament twice via fleet designs no human had tried.
Suffered from heuristics learning to credit themselves for others' work
— an early instance of reward hacking.

**HR / HR3 (Colton, 1999–).** Theory formation as a search through a
space of `(concept, conjecture)` pairs. Starts from background concepts
(e.g. "integer", "divides"); production rules construct new concepts —
**exists**, **forall**, **negate**, **compose**, **split**, **size** —
each producing a derived concept with an examples set. Conjectures
arise when two concepts have **equal** or **subset** example sets;
candidates are then sent to **Otter** (resolution prover) for proof,
**MACE** for counterexample search, or a CSP solver for finite-domain
filtering. Discovered novel number-theoretic concepts including
**refactorable numbers** (`n` such that `τ(n) | n`).

### Symbolic regression

**Eureqa (Schmidt & Lipson, 2009).** Genetic programming over
expression trees. Population of candidate formulas evolves under
crossover and mutation; fitness is **multi-objective** along a Pareto
front of `(prediction error, expression complexity)`. Crucially uses
**partial derivatives** of the data — the algorithm seeks formulas
whose derivative relationships match those numerically estimated from
the data, which biases toward physically meaningful laws over
overfit-prone arbitrary fits.

**AI Feynman (Udrescu & Tegmark, 2020).** Recursive decomposition.
Train a neural net `f̂` on the data, then test the data/function for
exploitable structure: (i) **dimensional analysis** — reduce variables
via Buckingham-π; (ii) **symmetries** — `f̂(x, y) = f̂(x+c, y−c)` etc.,
detected by checking NN-predicted values under variable shifts; (iii)
**separability** — `f̂(x, y) = g(x) + h(y)` or `g(x)·h(y)`; (iv)
**inverse functions**, **smoothness**, **low-order polynomial fit**. Any
discovered structure cuts the problem into smaller sub-problems and
recurses. Terminal sub-problems fall to brute-force basis search.

**PySR (Cranmer, 2020).** Modern open-source standard. Multi-population
evolutionary search (islands evolve independently and migrate
periodically), each population running tournament selection over
expression trees. Per-generation simulated annealing controls whether a
mutated child replaces its parent. Optionally uses neural-network
guidance to bias the operator distribution. The Pareto-front output is
the key user-facing interface (matches Eureqa's framing).

### Neuro-symbolic theorem proving

**AlphaGeometry / AG2 (DeepMind, 2024–25).** Two-component loop.
**Symbolic engine** — a deduction database (DD) plus algebraic reasoner
(AR) — exhaustively forward-chains all consequences of the current
geometric configuration; if the goal appears, the proof is closed.
**Neural engine** — a language model trained on ~100M synthetic geometry
problems — proposes **auxiliary constructions** (new points, lines,
circles) when the symbolic engine stalls. The new constructions extend
the diagram; the symbolic engine re-tries. AG2 added stronger DD
coverage, a better LM, and parallel search; together they solved 4 of 6
IMO 2024 problems.

**AlphaProof (DeepMind, 2024).** AlphaZero applied to **Lean** tactic
proofs. Problems are auto-formalized into Lean by a separate LLM. The
agent does tree search over tactic applications, guided by a value
network trained via self-play on millions of auto-generated variants of
training problems (difficulty scaled by curriculum). Reached
silver-medal IMO 2024 performance on algebra and number theory; the
geometry component was AlphaGeometry 2.

### Hypergeometric / creative telescoping

**Zeilberger's algorithm / WZ (Zeilberger, 1990).** For a
hypergeometric summand `F(n, k)`, seeks a recurrence
`Σ_{j=0}^{J} a_j(n) F(n+j, k) = G(n, k+1) − G(n, k)` with `G/F` a
**rational function in `n, k`** (the WZ certificate). Algorithm: write
the unknown `R(n, k) = G(n, k)/F(n, k)` as a rational ansatz with
polynomial numerator/denominator of bounded degree; equating
coefficients yields a linear system over `a_j(n)` and the ansatz
parameters. Solving gives both the recurrence and the certificate that
**mechanically proves** the resulting identity. WZ pairs make many
classical binomial identities one-line proofs.

**Gosper's algorithm (1978).** Decides whether a hypergeometric term
`t(k)` has a hypergeometric **antidifference** `s(k)` — i.e.
`s(k+1) − s(k) = t(k)`. Algorithm: factor `t(k+1)/t(k) = a(k)·c(k+1) /
(b(k)·c(k))` with `gcd(a(k), b(k+j)) = 1` for all `j ≥ 0` (Gosper-
Petkovsek normal form), then seek a polynomial `x(k)` of bounded degree
satisfying `a(k) x(k+1) − b(k−1) x(k) = c(k)`. Existence of `x` ⇔
existence of a hypergeometric antidifference. The foundation
Zeilberger's algorithm sits on.

**Sister Celine's method (Fasenmyer, 1945).** For a sum `S(n) = Σ_k
F(n, k)`, seeks a **finite-difference recurrence**
`Σ_{i,j} a_{i,j}(n) F(n+i, k+j) = 0` purely in `F` (no `G`). Method of
undetermined coefficients: assume `a_{i,j}` are polynomials of bounded
degree, expand the relation, collect powers of `k`, and solve the
resulting linear system. Once `F` satisfies such a recurrence,
**summing over `k`** yields a recurrence for `S(n)`. Predates and
inspired Gosper/Zeilberger; less efficient but conceptually clean.

---

## Open-source status

About half of the engines below are open source. The rest are either
closed, historical and never publicly released, or "the algorithm is
public domain but the original implementation was never distributed."

Legend: ✅ open source · ⚠️ partial/unclear/web-only · ❌ closed or unreleased.

Compute profile: 🔥 heavy (hours–days, often GPU/cluster — kick off and walk away)
· ⏱ moderate (scales with input; minutes–hours typical)
· ⚡ light (seconds per call; safe to call inline).

Links verified 2026-05-21 via `gh api` / web search. Star counts shown
for GitHub repos as a rough liveness signal.

| Engine | Status | Compute | Repo / link |
|---|---|---|---|
| Ramanujan Machine | ✅ Open source | 🔥 MITM/grid scans designed for cluster runs | https://github.com/RamanujanMachine/RamanujanMachine (Python, ~352★); newer toolkit at https://github.com/RamanujanMachine/ramanujantools (MIT) |
| PSLQ | ✅ OSS impls | ⏱ Per-call seconds–minutes; needs hundreds of digits of precision for large `n` | mpmath: https://github.com/mpmath/mpmath (BSD-3, ~1.1k★); SymPy: https://github.com/sympy/sympy; Bailey's MPFUN at https://www.davidhbailey.com/dhbsoftware/ |
| LLL | ✅ OSS impls | ⏱ Polynomial but constant blows up with dimension; crypto-sized lattices take hours | FPLLL: https://github.com/fplll/fplll (LGPL-2.1, ~405★); NTL (Shoup): https://libntl.org/ (LGPL); SageMath: https://github.com/sagemath/sage; PARI/GP: https://pari.math.u-bordeaux.fr/ |
| Inverse Symbolic Calculator | ⚠️ Web service | ⚡ Lookup; instant | Query at https://isc.carma.newcastle.edu.au/ (Bailey/Borwein-era ISC+) — query-only, no source |
| OEIS + Superseeker | ✅ Open data + scripts | ⚡ DB lookup + small transforms | Data: https://github.com/oeis/oeisdata (content CC BY-NC, ~125★); Superseeker invoked via https://oeis.org/ovgf.html |
| Graffiti (Fajtlowicz) | ❌ Never released | ⏱ Grows with invariant pool × graph corpus | Conjectures catalogued at https://cms.dt.uh.edu/faculty/delavinae/research/wowref.htm; source not distributed |
| Graffiti.pc (DeLaViña) | ❌ Binary-only | ⏱ Same shape; configurable | Same UH page; binary requests via DeLaViña |
| AutoGraphiX (AGX) | ❌ Academic binary | 🔥 VNS optimization at scale; long runs typical | GERAD page: https://www.gerad.ca/en/software/agx — binary distribution to academics |
| Conjecturing (Larson/Van Cleemput) | ✅ Open source | ⏱→🔥 Minutes for small pools; hours+ when invariant pool is wide | https://github.com/nvcleemp/conjecturing (Sage); project page https://nvcleemp.github.io/conjecturing/; companion data: https://github.com/math1um/objects-invariants-properties (GPL-3.0) |
| GrInvIn | ✅ Free download | ⚡ Interactive teaching tool | http://www.grinvin.org/ (Java); graph DB sister project: https://hog.grinvin.org/ |
| AM (Lenat) | ❌ Historical, not released | ⏱ Era-appropriate; agenda search ran for days on 1976 hardware | LISP source never officially distributed |
| EURISKO | ❌ Famously unreleased | 🔥 Historically ran for days; self-modifying heuristics | Lenat kept source private; partial decompilations only |
| HR / HR3 (Colton) | ⚠️ No official repo | ⏱ Bottlenecked by external prover calls (Otter/MACE/CSP) | Colton's HR3 not released; spiritual successor `HRLPlus` at https://github.com/dshea89/HRLPlus |
| Eureqa | ❌ Proprietary | 🔥 GP runs designed to go for hours–days | Nutonian → DataRobot; free-for-academia build no longer hosted |
| AI Feynman | ✅ Open source | 🔥 Trains a neural net per recursion (GPU); brute-force basis search at leaves | https://github.com/SJ001/AI-Feynman (MIT, ~758★) |
| PySR | ✅ Open source | 🔥 Multi-population GP designed to scale to hours–days; configurable | https://github.com/MilesCranmer/PySR (Apache-2.0, ~3.5k★) |
| AlphaGeometry | ✅ Open source (v1) | 🔥 LM inference per problem (minutes–hours); training is GPU-cluster scale | https://github.com/google-deepmind/alphageometry (Apache-2.0, ~4.8k★) |
| AlphaGeometry 2 | ⚠️ Partial release | 🔥 Same shape; symbolic DDAR alone is moderate, full pipeline needs LM | https://github.com/google-deepmind/alphageometry2 (Apache-2.0) — DDAR + examples only; LM weights/training pipeline not released |
| AlphaProof | ❌ Not released | 🔥 RL self-play on Lean; training reportedly weeks on TPU | DeepMind paper only; no public code or weights |
| Zeilberger / WZ | ✅ OSS impls | ⏱ Per-identity seconds–minutes; nested sums can drag | Zeilberger's Maple packages (incl. EKHAD): https://sites.math.rutgers.edu/~zeilberg/programs.html and https://sites.math.rutgers.edu/~zeilberg/programsAB.html; SymPy: https://docs.sympy.org/latest/modules/concrete.html |
| Gosper's algorithm | ✅ OSS impls | ⚡ Decision procedure; typically sub-second | SymPy `gosper_sum`; SageMath; Maple `SumTools[Hypergeometric][Gosper]` |
| Sister Celine's method | ✅ OSS impls | ⏱ Slower than Gosper/WZ due to wider ansatz | Bundled in EKHAD; SymPy `Sum.rewrite`/`hyperexpand` machinery |

**Practical takeaway for the `scripts/graffiti/` and
`scripts/conjecture-miner/` lanes:** the closest cousin that can be
linked against and studied is **Conjecturing** at
`github.com/nvcleemp/conjecturing` — Sage-based, explicitly generalizes
the Dalmatian heuristic to arbitrary `(object, invariant)` tuples, and
paired with Larson's open `objects-invariants-properties` data repo. The
original Graffiti, Graffiti.pc, and AGX are all behind closed doors,
useful as design references but not as drop-in dependencies. For the
math-substrate lane, **mpmath** (PSLQ) + **FPLLL** (LLL) cover
integer-relation finding without leaving Python/C++.

---

## Suitability for EmergentTerrain (added 2026-05-22)

Mapping of the open engines above to the EmergentTerrain paper-writing
business case (MATHEMATICS, PHYSICS, MATERIALS, TERRESTRIAL,
UNDERWATER, INTERDISCIPLINARY) against the current resource envelope:
**single Fedora 44 host, JVM, optional Julia sidecar on `:8088`,
optional GPU adapter, Postgres, multi-provider LLM pool, no cluster,
no guaranteed GPU**.

### Tier 1 — fits cleanly, high ROI, planned phase docs exist

| Engine | Field served | Why it fits | Phase doc |
|---|---|---|---|
| **PySR** (SymbolicRegression.jl) | PHYSICS, MATERIALS, TERRESTRIAL, UNDERWATER | Julia-native — reuses the existing `julia-math-sidecar` instead of adding a runtime. CPU-only with tunable population × iteration budget. Configurable wall-clock cap. Biggest single unlock for non-math domains: turns parameter sweeps into candidate laws. | `phase-24-pysr-symbolic-regression.md` |
| **Zeilberger/WZ + Gosper + Sister Celine** (SymPy) | MATHEMATICS (combinatorial identities) | Python subprocess at ~10 s budget per call. Emits a **machine certificate** that mechanically proves the identity — the first tool in the suite that can legitimately earn `EvidenceLabel.THEOREM`. | `phase-25-sympy-identity-prover.md` |
| **PSLQ via mpmath** + **OEIS Superseeker** | MATHEMATICS, PHYSICS (constant/sequence recognition) | mpmath PSLQ runs at 200-digit precision in ~1 s per call against a small basis. OEIS Superseeker is HTTP + a local cache. Bundled into one "recognise the constant" pass right after refinement. Strengthens citation precision; cleans the conjecture queue of rediscoveries. | `phase-26-constant-recognition-pass.md` |

### Tier 2 — worthwhile if the math lane proves out

| Engine | Field served | Why it's Tier-2 | Phase doc |
|---|---|---|---|
| **Conjecturing** (Sage) | MATHEMATICS, GRAPH-THEORY, INTERDISCIPLINARY | Generalises the Phase-23 Dalmatian miner to arbitrary `(object, invariant)` tuples; ships with ~400 invariants via `objects-invariants-properties`. Heavy install (~1.5 GB Sage), runs as nightly batch off the hot path. Only worth paying if Phase 23.8 calibration shows the Java miner's invariant pool is the bottleneck. | `phase-27-conjecturing-sage-batch.md` |
| **LLL** (FPLLL / NTL) | MATHEMATICS, PHYSICS | Complements PSLQ for closed-form / minimal-polynomial recovery on high-precision constants. Modest lattice dimensions only (≤30). Native subprocess dependency. Folded into the Phase 26 doc as a flag-gated optional layer; ship the binary only when a specific paper demands it. | bundled in `phase-26-constant-recognition-pass.md` (§26.3) |

### Tier 3 — skip given current resources

| Engine | Why skip |
|---|---|
| **Ramanujan Machine** | MITM / grid scans designed for cluster compute. The single-host envelope can't run a meaningful scan. Revisit only if a specific paper warrants a one-off targeted MITM. |
| **AI Feynman** | Trains a neural net per recursion level — needs reliable GPU. PySR alone captures most of the value at a fraction of the compute. |
| **AlphaGeometry / AG2** | Olympiad-geometry specific. Not in EmergentTerrain's domain catalog. |
| **AlphaProof** | Closed; no public code or weights. |
| **GrInvIn** | Interactive teaching front-end, not an autonomous engine. |
| **Inverse Symbolic Calculator** | Web-service only (no source). PSLQ in-process covers the same surface for our basis sets. |
| **Eureqa** | Proprietary; PySR is the open replacement. |
| **Graffiti / Graffiti.pc / AutoGraphiX** | Closed-source or binary-only. Conjecturing covers most of the same ground openly. AGX is a candidate v2 add behind a subprocess wrapper if extremal-graph optimisation becomes a paper kind. |

### Recommended sequence

1. **Phase 24 — PySR via the Julia sidecar.** Biggest reach: unlocks
   the PHYSICS/MATERIALS/TERRESTRIAL/UNDERWATER conjecture lane that
   Phase 23.7 currently gates on. ~5 days. Flag-gated dormant.
2. **Phase 25 — SymPy combinatorial-identity prover.** Cheapest
   `EvidenceLabel.THEOREM` win for the math lane. ~6 days. Flag-gated
   dormant.
3. **Phase 26 — Constant recognition pass.** Bundles PSLQ + OEIS
   Superseeker + (optional) LLL into one "recognise" step in
   `maybeRefineComputation`. ~6.5 days without LLL, ~8.5 with.
   Flag-gated dormant.
4. **Phase 27 — Conjecturing in Sage** (deferred). Gated on Phase
   23.8 calibration. ~8.5 days when it lands.

Total Tier-1 effort: ~17.5 days end-to-end. Tier-2 adds ~8.5 more
contingent on calibration.

### Why this shape

- **Reuse infrastructure already paid for.** PySR slots into the
  Julia sidecar (Phase 22). The SymPy / mpmath subprocesses share a
  pattern with `JuliaMathClient` (Phase 22.2). OEIS Superseeker
  extends the cache pattern from `OeisCatalog` (Phase 23.2).
- **Don't add long-running services.** Three new subprocess-shaped
  integrations (SymPy, mpmath PSLQ, FPLLL); zero new long-running
  daemons. Sage in Phase 27 is a batch, not a peer service.
- **Match engine cost to expected value.** Heavy engines
  (Ramanujan Machine, AI Feynman, AlphaProof) are deferred not
  because they're bad but because their compute envelope exceeds
  what a single dev host can deliver reliably. If the deployment
  ever moves to a multi-host or GPU-equipped box, revisit Tier 3.
- **Honest labelling first.** Phase 25 (machine-certified
  combinatorial proofs) and Phase 26 (verified constant citations)
  both strengthen the audit trail for claims the LLM panel
  currently has to take on faith. This compounds with Phase 23.6's
  `EvidenceLabel` floor instead of adding new ungrounded surface.
