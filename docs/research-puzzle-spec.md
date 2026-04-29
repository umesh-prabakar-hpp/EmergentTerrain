# Research Puzzle Computation Specification

Puzzles for the university research simulation where:
- The problem instance is generated deterministically from a seed (`gameTick + authorId`)
- The solution is not pre-encoded — bots discover it through iterative search
- A continuous quality score drives genetic improvement across generations
- Peer review passes when two independent bots converge to the same solution within tolerance

---

## Genome Mapping

Each `Researcher` carries `topicWeights[5]` which, in puzzle mode, map to search strategy
parameters rather than topic selection. The same genome is reused across all puzzle types with
domain-specific interpretation.

| Gene index | Universal role | Typical range |
|---|---|---|
| `topicWeights[0]` | Step size / perturbation radius | 0.05 – 1.0 |
| `topicWeights[1]` | Restart probability when stuck | 0.0 – 0.5 |
| `topicWeights[2]` | Candidate sample budget multiplier | 0.5 – 3.0 |
| `topicWeights[3]` | Depth vs. breadth bias (0 = breadth, 1 = depth) | 0.0 – 1.0 |
| `topicWeights[4]` | Acceptance tolerance for "good enough" | 0.001 – 0.1 |

Evolution selects for genome configurations that publish more frequently, indirectly selecting
for search strategies that work well on each domain's puzzle class.

---

## Peer Review Protocol

For every puzzle type, peer review is defined as:

1. Both author and reviewer receive the **same problem instance** (same `domain`, `topicIndex`,
   `param1`, `param2`, `seed`).
2. Both run their own search independently using their own genome strategy.
3. **Pass condition**: `|author_score - reviewer_score| / max(reviewer_score, 1e-9) < REVIEW_TOLERANCE`
   where `REVIEW_TOLERANCE = 0.05` (relaxed from the current 1e-6 — solutions may differ but
   quality must be comparable).
4. Both bots record their best discovered solution value as `primaryValue` in `ResearchResult`.
5. If the author introduced a computational error (simulated by `errorRate()`), their reported
   `primaryValue` is perturbed by ±15% — this will cause divergence from the reviewer and
   trigger rejection.

---

## MATHEMATICS Puzzles

### Puzzle M1 — N-Queens Search

**Problem**: Place N non-attacking queens on an N×N chessboard.

**Instance generation**:
```
N = 8 + (param1 % 13)          // board size 8–20
seed = param1 * 997L + gameTick
```

**Search algorithm** (one pass, budget = N² × sampleMultiplier iterations):
```
start with a random permutation of rows (queen in column c is in row placement[c])
repeat for budget iterations:
    pick two columns i, j at random
    if swapping placement[i] and placement[j] reduces conflict_count: apply swap
    else: apply with probability restart_prob (simulated annealing style)
```

Conflict count = number of pairs of queens that attack each other (same row, or same diagonal).

**Score**:
```
score = (max_conflicts - conflict_count) / max_conflicts
max_conflicts = N * (N - 1) / 2
```
Score of 1.0 = perfect solution (zero conflicts). Score is continuous — partial solutions have value.

**Primary value**: `conflict_count` (lower is better — reviewers compare this directly).

**Findings string format**:
```
N-Queens N=%d: best_conflicts=%d, score=%.4f, iterations=%d. %s
  where %s = "Solution found." | "Best partial: %d conflicts remain."
```

**Peer review convergence**: Both bots report `conflict_count`; pass if within 20% of each other
(since different strategies may find equally valid but different placements with same conflict count).

---

### Puzzle M2 — Magic Square Construction

**Problem**: Fill an N×N grid with integers 1..N² such that all rows, columns, and both main
diagonals sum to the magic constant `M = N(N²+1)/2`.

**Instance generation**:
```
N = 3 + (param1 % 4)           // size 3–6
seed = param2 * 1337L + param1
```

**Search algorithm** (budget = N⁴ × sampleMultiplier):
```
start with a shuffled arrangement of 1..N²
repeat for budget iterations:
    pick two positions at random
    compute delta_deviation if values are swapped
    if delta_deviation < 0: apply swap
    else: apply with probability exp(-delta_deviation / (N * stepSize))
```

Total deviation = sum of |row_sum - M| for all rows + |col_sum - M| for all columns
               + |diag1_sum - M| + |diag2_sum - M|.

**Score**:
```
max_deviation = (N + N + 2) * (N * N²) / 2   // loose upper bound
score = 1.0 - (total_deviation / max_deviation)
```

**Primary value**: `total_deviation` (lower is better).

**Findings string format**:
```
Magic square N=%d (M=%d): deviation=%d, score=%.4f, iterations=%d. %s
  where %s = "Perfect magic square found." | "Best: rows_ok=%d/%d, cols_ok=%d/%d, diags_ok=%d/2."
```

**Peer review convergence**: Pass if `|author_deviation - reviewer_deviation| <= N`.

---

### Puzzle M3 — Collatz Chain Exploration

**Problem**: Within a number range, find the starting value with the longest Collatz chain and
characterise the statistical distribution of chain lengths.

**Instance generation**:
```
rangeStart = 1000 + (param1 * 97) % 50000    // start of probe range
rangeWidth = 500 + (param1 % 1000)            // how many values to sample
sampleCount = 50 + (int)(topicWeights[2] * 100)  // bot controls how many it probes
```

**Search algorithm**:
```
probeValues = sample sampleCount values from [rangeStart, rangeStart + rangeWidth)
              using topicWeights[3] to bias toward high-density subregions
for each probe value n:
    compute collatz chain length: while n != 1: n = (n%2==0) ? n/2 : 3n+1; count++
record: maxLength, meanLength, stdDev, longestStart
```

**Score**:
```
expectedMean = log2(rangeStart) * 3.0    // rough theoretical baseline
score = min(1.0, maxLength / (expectedMean * 2.5))
```

Score grows when bots find unusually long chains — bots that probe more intelligently
(using their genome to bias toward known high-yield regions) outperform random probers.

**Primary value**: `maxLength` (longer chain = more interesting finding).

**Findings string format**:
```
Collatz [%d, %d+%d): sampled=%d, max_chain=%d (start=%d), mean=%.1f, stdDev=%.1f.
  Theoretical baseline ln2(N)≈%.1f. Record ratio=%.2f×.
```

**Peer review convergence**: Both bots sample independently. Pass if
`|author_maxLength - reviewer_maxLength| / max(reviewer_maxLength, 1) < 0.30`
(different samples will find different records but should be in the same order of magnitude).

---

### Puzzle M4 — Circle Packing

**Problem**: Pack N non-overlapping unit circles into a square of minimum possible side length,
or equivalently: given a fixed square of side S, maximise the number of circles packed.

**Instance generation**:
```
N = 5 + (param1 % 26)          // circles to pack: 5–30
S = ceil(sqrt(N)) + 1.0        // initial bounding square side (generous)
// minimum known side: S_min ≈ sqrt(N / 0.9069) * 2 (FCC theoretical limit)
```

**Search algorithm** (force-directed relaxation, budget iterations):
```
place N circle centres randomly inside [1, S-1]² (leaving radius=1 margin)
repeat for budget iterations:
    for each circle i:
        compute repulsion force from all overlapping circles j:
            if dist(i,j) < 2: push i away from j by stepSize * (2 - dist) / 2
        clamp centre to [1, S-1]²
    if no overlaps remain: try shrinking S by tolerance and repeat
record: final S_achieved, overlap_count
```

**Score**:
```
S_theoretical = Math.sqrt(N / 0.9069) * 2.0 + 2.0   // FCC packing limit estimate
if overlap_count > 0:
    score = 0.1 * (1.0 - overlap_count / (N * (N-1) / 2.0))
else:
    score = S_theoretical / S_achieved    // closer to 1.0 = tighter packing
```

**Primary value**: `S_achieved` (lower is better; reviewers compare achieved square side).

**Findings string format**:
```
Circle packing N=%d: S_achieved=%.4f, S_theoretical≈%.4f, efficiency=%.4f, overlaps=%d.
  Density η=%.4f (FCC limit 0.9069). %s
  where %s = "Overlap-free packing." | "%d overlapping pairs remain."
```

**Peer review convergence**: Pass if `|S_author - S_reviewer| / S_reviewer < 0.05`.

---

## PHYSICS Puzzles

### Puzzle P1 — Numerical Integration Race

**Problem**: Approximate a definite integral using a fixed evaluation budget.
The integrand is a random sum of cosines — no closed form is pre-computed.

**Instance generation**:
```
// Build f(x) = sum_{k=1}^{K} A_k * cos(omega_k * x + phi_k)
K = 3 + (param1 % 5)          // 3–7 terms
a = 0.0                        // integration bounds
b = Math.PI * (1 + param1 % 5)
// Coefficients derived from seed:
for k in 1..K:
    A_k     = 0.5 + ((seed >> k*3) % 100) / 100.0
    omega_k = 1.0 + ((seed >> k*5) % 10)
    phi_k   = ((seed >> k*7) % 628) / 100.0   // 0 – 2π

evaluationBudget = 20 + (int)(topicWeights[2] * 80)   // 20–100 evaluations
```

**Methods** (bot selects based on `topicWeights[3]`):
```
if topicWeights[3] < 0.33:   use Trapezoid rule with evaluationBudget panels
if topicWeights[3] < 0.67:   use Simpson's rule (even panel count)
else:                         use 5-point Gaussian quadrature on sub-intervals
```

**Reference value** (for scoring only, not given to bot):
Computed with 10,000-panel Simpson as ground truth inside `MathPhysicsEngine`.

**Score**:
```
relativeError = |computed - reference| / max(|reference|, 1e-9)
score = exp(-10.0 * relativeError)   // 1.0 at perfect, decays smoothly
```

**Primary value**: `computed` integral value.

**Findings string format**:
```
Numerical integration f(x)=Σcos [%.2f,%.2f], K=%d terms, method=%s, n=%d evals:
  result=%.8f, reference=%.8f, relError=%.2e, score=%.4f.
  %s exceeds Simpson n=40 baseline.
```

**Peer review convergence**: Pass if `|author_computed - reviewer_computed| / |reviewer_computed| < 0.02`.

---

### Puzzle P2 — Stable Orbit Search

**Problem**: Given two fixed masses and one mobile body, find initial velocity for the mobile
body that maximises its bounded orbital duration (doesn't escape or collide).

**Instance generation**:
```
M1 = 1e12 + (param1 % 9) * 1e11    // mass 1 (fixed at origin)
M2 = 5e11 + (param1 % 5) * 1e11    // mass 2 (fixed at x=100)
// Mobile body starts at (50, 20)
// Bot proposes: vx in [-stepSize*10, +stepSize*10], vy in [-stepSize*10, +stepSize*10]
// Genome gene [0] controls velocity perturbation scale
maxSteps = 2000
dt = 0.1
```

**Search algorithm**:
```
best_duration = 0, best_vx = 0, best_vy = 0
candidates = sampleCount initial velocities (genome controls count and spread)
for each (vx, vy):
    simulate up to maxSteps using leapfrog integration
    stop if: dist to M1 < 5 (collision) or dist to any mass > 500 (escape)
    record steps_survived
    if steps_survived > best_duration: update best
```

**Score**:
```
score = best_duration / maxSteps
```

**Primary value**: `best_duration` (steps survived).

**Findings string format**:
```
3-body stability: M1=%.2e, M2=%.2e, mobile at (50,20).
  Best v=(%.3f, %.3f): survived %d/%d steps (%.1f%%).
  %s
  where %s = "Stable orbit found." | "Best: escaped at step %d." | "Best: collision at step %d."
```

**Peer review convergence**: Pass if `|author_duration - reviewer_duration| / maxSteps < 0.15`.

---

### Puzzle P3 — Diffusion Front Discovery

**Problem**: Simulate a random walk on a 2D grid with obstacles and characterise how far from
Gaussian the resulting density distribution is (measuring structural complexity of the obstacle field).

**Instance generation**:
```
gridSize = 20 + (param1 % 20)       // 20×20 to 40×40
obstacleFraction = 0.05 + (param1 % 20) / 100.0   // 5–25% blocked cells
walkerCount = 100 + (int)(topicWeights[2] * 400)   // bots control sample size
steps = 50 + param1 % 150
// Obstacle positions seeded from param1 using XOR-shift
```

**Computation**:
```
place walkers at grid centre
run steps steps of random walk (4-directional, bounce off obstacles and walls)
collect final positions → compute empirical density histogram
fit a 2D Gaussian to the histogram (least squares)
compute KL divergence between empirical density and fitted Gaussian
```

**Score**:
```
// Higher divergence = more interesting obstacle topology discovered
score = min(1.0, klDivergence * 5.0)
```

**Primary value**: `klDivergence` (higher = more interesting; bots compete to discover complex diffusion environments).

**Findings string format**:
```
Diffusion grid=%dx%d, obstacles=%.0f%%, walkers=%d, steps=%d:
  KL_divergence=%.4f, spread_x=%.2f, spread_y=%.2f (Gaussian σ_x=%.2f, σ_y=%.2f).
  %s
  where %s = "Near-Gaussian (simple topology)." | "Significant deviation: structured obstacle field." | "Strong anomalous diffusion detected."
```

**Peer review convergence**: Pass if `|author_kl - reviewer_kl| / max(reviewer_kl, 1e-6) < 0.25`
(stochastic walks; same seed gives same obstacles but walkers still vary).

---

## MATERIALS Puzzles

### Puzzle Ma1 — Grain Boundary Energy Minimisation

**Problem**: Assign rotation angles to N crystal grains to minimise total grain boundary energy.
Adjacent grains share a boundary; energy depends on misorientation angle between them.

**Instance generation**:
```
N = 5 + (param1 % 10)          // 5–14 grains
// Adjacency graph: grain i is adjacent to grain (i+1)%N and grain (i+N/2)%N
// Misorientation energy: E(theta) = theta * (1 - ln(theta)) for theta in (0, pi/4]  (Read-Shockley model)
//                                   1.0                              for theta > pi/4  (high-angle boundary)
```

**Search algorithm** (gradient-free hill climbing):
```
initialise rotation[i] = random angle in [0, 2π) for each grain
repeat for budget iterations:
    pick grain i at random
    propose new_angle = rotation[i] + gaussian(0, stepSize)  clamped to [0, 2π)
    compute delta_energy of all boundaries involving grain i
    if delta_energy < 0: accept
    else: accept with probability exp(-delta_energy / temperature)
    // temperature = initial_temp * (1 - iteration/budget)   (linear cooling)
record: total_energy, low_angle_boundary_count
```

Read-Shockley misorientation energy:
```java
double misorientation = Math.abs(rotation[i] - rotation[j]) % (Math.PI / 4);
double theta = Math.min(misorientation, Math.PI/4 - misorientation) / (Math.PI/4);
double E = theta > 0 ? theta * (1 - Math.log(theta + 1e-10)) : 0;
```

**Score**:
```
maxEnergy = N_boundaries * 1.0      // all high-angle boundaries = 1.0 each
score = 1.0 - (total_energy / maxEnergy)
```

**Primary value**: `total_energy` (lower is better).

**Findings string format**:
```
Grain boundary optimisation: N=%d grains, %d boundaries.
  E_total=%.4f (max=%.1f), score=%.4f, low_angle=%d/%d boundaries.
  Best config after %d iterations. Temperature cooled %.3f→%.3f.
```

**Peer review convergence**: Pass if `|author_energy - reviewer_energy| / max(reviewer_energy, 1e-9) < 0.10`.

---

### Puzzle Ma2 — Fibre Arrangement Optimisation

**Problem**: Given a fixed fibre volume fraction, find a spatial arrangement (fibre spacing pattern)
that maximises transverse stiffness while keeping stress concentration factor below a threshold.

**Instance generation**:
```
vf = 0.10 + (param1 % 60) / 100.0    // fibre volume fraction 10–70%
Ef = 200e3 + (param2 % 200) * 1e3    // fibre modulus MPa (200–400 GPa range)
Em = 2e3 + (param1 % 5) * 500.0      // matrix modulus MPa (2–4.5 GPa)
Vf_max = 0.9069                       // hexagonal close packing limit
arrangement = [HEXAGONAL, SQUARE, RANDOM_PERTURBED][topicWeights[3] mapped to index]
perturbation = topicWeights[0] * 0.3  // genome controls how much to perturb ideal lattice
```

**Computation**:
```
// Effective transverse modulus — Halpin-Tsai model:
zeta = 2.0                            // reinforcing factor for circular fibres, transverse
eta  = (Ef/Em - 1) / (Ef/Em + zeta)
E_T  = Em * (1 + zeta * eta * vf) / (1 - eta * vf)

// Stress concentration factor (Inglis approximation for fibre spacing s):
s = sqrt(PI / (4 * vf))              // centre-to-centre spacing (normalised)
Kt = 1 + 2 * (1 / s)                // approximate SCF for circular inclusion

// Arrangement quality modifier:
modifier = switch(arrangement):
    HEXAGONAL:        1.00 + perturbation * 0.05
    SQUARE:           0.95 + perturbation * 0.03
    RANDOM_PERTURBED: 0.85 + perturbation * 0.15   // high variance — can beat hex or fail

// Objective = maximise E_T while Kt < threshold
Kt_threshold = 2.5
penaltyFactor = Kt > Kt_threshold ? (Kt_threshold / Kt) : 1.0
adjustedModulus = E_T * modifier * penaltyFactor
```

**Score**:
```
E_T_voigt = vf * Ef + (1 - vf) * Em    // upper bound
score = adjustedModulus / E_T_voigt
```

**Primary value**: `adjustedModulus` (higher is better).

**Findings string format**:
```
Fibre composite: vf=%.2f, Ef=%.0f GPa, Em=%.1f GPa, arrangement=%s (perturb=%.2f).
  E_T_HalpinTsai=%.2f GPa, Kt=%.3f%s, adjusted=%.2f GPa, score=%.4f.
  Voigt bound=%.2f GPa, efficiency=%.1f%%.
  where %s = "" | " [ABOVE Kt threshold: penalised]"
```

**Peer review convergence**: Pass if `|author_modulus - reviewer_modulus| / reviewer_modulus < 0.05`.

---

## Integration with Existing Architecture

### Changes to `MathPhysicsEngine.compute()`

Each puzzle type becomes a new case in the `domain:topicIndex` dispatch. The `topicIndex`
selects the puzzle; `param1`/`param2` define the instance; the method returns a `ResearchResult`
with `primaryValue` = the quality metric used for peer review comparison.

```
MATHEMATICS:0  →  nQueensSearch
MATHEMATICS:1  →  magicSquareSearch
MATHEMATICS:2  →  collatzExploration
MATHEMATICS:3  →  circlePackingSearch
PHYSICS:0      →  numericalIntegration
PHYSICS:1      →  stableOrbitSearch
PHYSICS:2      →  diffusionFrontDiscovery
MATERIALS:0    →  grainBoundaryMinimisation
MATERIALS:1    →  fibreArrangementOptimisation
```

### Changes to `UniversityService.runResearchCycle()`

The `topicWeights` genome currently drives `selectTopic()`. In puzzle mode, the selected
topic index also controls which of the 5 genes to use as the dominant strategy parameter —
this is handled inside `MathPhysicsEngine.compute()` by passing the full `topicWeights` array.

**Signature change required**:
```java
// Current:
MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex, int param1, double param2)

// New:
MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                                         int param1, double param2,
                                         double[] genome)
```

The `genome` array is the researcher's `topicWeights`, forwarded through so each puzzle can
read the strategy parameters it cares about.

### Peer review tolerance change

`REVIEW_TOLERANCE` in `UniversityService` should be raised from `1e-6` (exact deterministic
match) to a per-puzzle tolerance negotiated by score comparison rather than raw value comparison.
The simplest approach: compare `score(author)` vs `score(reviewer)` instead of raw `primaryValue`.

```java
// Replace:
double relDiff = Math.abs(reportedPrimary - reviewPrimary) / Math.max(Math.abs(reviewPrimary), 1e-9);
boolean agrees = relDiff < REVIEW_TOLERANCE || (!authorError && !reviewerError);

// With:
double authorScore  = MathPhysicsEngine.scoreResult(domain, topicIndex, reportedPrimary);
double reviewScore  = MathPhysicsEngine.scoreResult(domain, topicIndex, reviewPrimary);
double scoreDiff    = Math.abs(authorScore - reviewScore);
boolean agrees      = scoreDiff < PUZZLE_REVIEW_TOLERANCE;   // new constant, 0.05
```

---

## Summary Table

| ID | Domain | Puzzle | Input controls | Score (1.0 = best) | Peer review tolerance |
|---|---|---|---|---|---|
| M1 | MATHEMATICS | N-Queens | N = 8–20 | 1 − conflicts/max_conflicts | ±20% conflict count |
| M2 | MATHEMATICS | Magic Square | N = 3–6 | 1 − deviation/max_deviation | deviation ≤ N |
| M3 | MATHEMATICS | Collatz Chains | range start, width | maxChain / baseline | ±30% max chain |
| M4 | MATHEMATICS | Circle Packing | N = 5–30 | S_theoretical / S_achieved | ±5% square side |
| P1 | PHYSICS | Numerical Integration | K terms, [a,b] | exp(−10 × relError) | ±2% computed value |
| P2 | PHYSICS | Stable Orbit | M1, M2, grid | steps_survived / maxSteps | ±15% step count |
| P3 | PHYSICS | Diffusion Front | grid, obstacles | KL divergence (higher = richer) | ±25% KL value |
| Ma1 | MATERIALS | Grain Boundary | N grains | 1 − energy/maxEnergy | ±10% energy |
| Ma2 | MATERIALS | Fibre Arrangement | vf, Ef, Em | E_T_adjusted / E_T_voigt | ±5% modulus |
