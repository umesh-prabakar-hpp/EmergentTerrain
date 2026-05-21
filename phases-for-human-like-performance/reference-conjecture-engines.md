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
