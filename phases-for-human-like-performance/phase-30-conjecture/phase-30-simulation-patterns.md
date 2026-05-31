# Phase 30 — patterns from chemical-reaction / artificial-life / simulation frameworks

Second addendum to [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md), companion
to [`phase-30-external-patterns.md`](phase-30-external-patterns.md). Where the self-improving-framework
addendum supplied the **bookkeeping** layer (archive / selection / anti-gaming), these
chemical-reaction, artificial-chemistry, artificial-life and simulation frameworks supply the
**content criteria** Phase 30 lacked: *what makes a finding irreducible*, *how to verify a mechanism*,
and *what the proper null and convergence gates are*. Read alongside the triviality diagnosis
([`conjecture-engine-triviality.md`](conjecture-engine-triviality.md)) — patterns 1, 2, 5, 9, 10 below
directly retire the "212 near-identical curve-fits, none retired" pathology.

**Sources reviewed** (under
`/opt/apps/ide/vscode/ai/reference-from-github/chemical-reaction-simulations/`): `artificial-chemistry`
(RAF, chemoton, alchemy), `digital-life` (Avida, Tierra), `lenia`, `stochastic-chemical-kinetics`
(GillesPy2, stochpy, rebop), `systems-biology` (COPASI, libSBML, RoadRunner, tellurium, SNAKES),
`molecular-dynamics` (GROMACS, LAMMPS, OpenMM).

The four genuinely transformative additions (none in the current Phase 30 draft): **#1 RAF closure**,
**#3 mechanism-as-runnable-model**, **#5 FIM identifiability gate**, **#9 convergence gate**. #5 and #9
are cheap and directly retire the trivial-fit flood.

---

## A. Irreducibility = closure, not fit

### 1. RAF closure → report "organizations," not isolated inequalities  *(highest priority)*
- **Source:** `artificial-chemistry/RAF/src/ReacSet.cxx::applyRAFalgo` (the fixpoint), `::computeClosureF`,
  `::randomiRAF`/`::sampleiRAFs` (minimal cores), `::isClosed`/`::samplecRAFs` (closed sets / organizations).
- **Idea:** map findings to a reaction network — food = raw observables; a finding `A ~ f(B,C)` = a
  catalyzed reaction producing `A` from `B,C`. The RAF fixpoint extracts the **maxRAF**: the largest
  self-supporting subset where every finding's inputs are produced by other findings, grounded in
  primitives. Isolated curve-fits are pruned; findings in a closed, mutually-reinforcing derivation web
  survive. The **iRAF** (greedy leave-one-out) is a constructive irreducibility proof — "delete any
  member and closure collapses." A **cRAF/organization** (RAF ∩ closed: produces nothing it doesn't
  account for) is the emergent self-contained unit.
- **Phase 30 amendment — 30.3 + irreducibility gate (sharper than per-finding MDL):** build a derivation
  network of accepted findings, run `applyRAFalgo`, promote/report the **organization** (or iRAF) as the
  publishable unit, and flag findings in nobody's RAF for retirement. One closed law replaces dozens of
  near-duplicate edges.
- **Tier:** CAF (`applyCAFalgo`, strictly forward-constructive, no circular support) = high-trust
  "constructively derived from data"; RAF-but-not-CAF = "self-consistent but bootstrapped → route to
  30.6 adversarial replication."

### 2. Copy/identity suppression — kill tautological self-reproducers
- **Source:** `artificial-chemistry/alchemy-docker/AlChemy/LambdaReactor/interact.c` (~lines 111-135:
  product byte-identical to an input is a "copy action," dropped with prob `1 - copy_prob`).
- **Why:** Fontana found pure copiers ("identity functions") trivially dominate the reactor — the exact
  analog of the 212 near-identical inequalities.
- **Phase 30 amendment — irreducibility gate / retirement:** auto-reject any conjecture whose RHS is an
  order-preserving re-expression of its LHS (the `A ≤ k·A'` tautology family); tunable near-duplicate
  suppression knob.

---

## B. Mechanism rigor — from prose to runnable model (transforms 30.7)

### 3. Mechanism = an executable model, not freeform prose  *(highest priority for 30.7)*
- **Source:** `systems-biology/tellurium/tellurium.py` (`te.loada` Antimony → SBML → `r.simulate()`);
  `systems-biology/roadrunner/source/IntegratorFactory.*` (one model, ODE *and* Gillespie behind one
  `simulate()`); `tellurium/teconverters/convert_antimony.py` (DSL → canonical IR).
- **Idea:** a mechanism is authored in a terse declarative DSL (`S1 -> S2; k1*S1`) that is both
  human-readable and executable, lowered to a canonical standard, then simulated and diffed against data.
- **Phase 30 amendment — 30.7:** constrain the `Mechanism kind:` field to a small grammar that must parse
  and lower to a `predict()` callable; **reject any mechanism whose `predict()` cannot regenerate the
  claimed relation out-of-sample.** Run the same mechanism deterministically (`predict_mean`) and
  stochastically (`predict_ensemble`) — a finding survives only if it holds in the mean AND is not
  swamped by ensemble spread.

### 4. Mechanism closure check
- **Source:** `artificial-chemistry/chemoton-game/chemoton/chemotonbf.cpp::setup` (the metabolic core
  `X+A1→A2→A3→A4→A5→2A1` regenerates its own seed with gain; the three subsystems each regenerate the
  inputs the others consume).
- **Phase 30 amendment — 30.7:** represent the claimed mechanism as a directed quantity-graph and verify
  it *closes* — every intermediate it introduces is both produced and consumed within the stated
  relations (no dangling un-grounded term, no un-consumed product). Deterministic replacement for
  mechanism prose; same closure check as #1 at mechanism granularity.

---

## C. Reference-data-free rejection gates (cheap, high yield)

### 5. FIM parameter-identifiability gate — cheapest win against data-mined constants
- **Source:** `systems-biology/COPASI/copasi/parameterFitting/CFitProblem.{h,cpp}` — `calcFIM`, `calcCov`,
  `getFisherInformationEigenvalues/Eigenvectors`, `getCorrelations`.
- **Idea:** after fitting, a near-zero FIM eigenvalue ⇒ the constant lies in a sloppy/unidentifiable
  direction; correlation ≈ ±1 between two parameters ⇒ they aren't separately identifiable.
- **Phase 30 amendment — the core "data-mined constants" problem:** reject `lhs ≤ k·rhs + c` unless `k`
  and `c` have bounded confidence intervals and low mutual correlation under the FIM. Much of the 212
  flood fails this — the constants aren't pinned by the data. Complements the existing PySR
  search-bound-vs-algorithmic split (`project_pysr_failure_classification`). Distinguish *structural*
  identifiability (rank/null-space of the design — can it *ever* be identified from these observables?)
  from *practical* (is it identified by *this* data?).

### 6. Dimensional/unit-consistency + overdetermined checks
- **Source:** `systems-biology/libsbml/src/sbml/validator/UnitConsistencyValidator.cpp`,
  `OverdeterminedValidator.cpp`.
- **Phase 30 amendment — 30.7 sanity gate / multiple-comparisons hygiene (reject before testing):**
  (1) dimensional balance of `lhs ≤ k·rhs + c` (kills "meters ≤ k·seconds"-class flat-vocabulary fits);
  (2) overdetermination — a composed claim adding an already-implied constraint is non-novel/contradictory.

### 7. Conservation / moiety invariants as automatic validity gates
- **Source:** `systems-biology/COPASI/copasi/moieties/CMoietiesMethod.*` (Householder reduction of the
  stoichiometry matrix → conserved quantities); `roadrunner/source/conservation/ConservedMoietyConverter.*`;
  MD: `molecular-dynamics/gromacs/docs/dev-manual/physical_validation.rst` (integrator conservation),
  `lammps/doc/src/thermo_style.rst` (`econserve = pe+ke+ecouple` stays constant even under thermostats),
  `openmm/tests/TestLangevinIntegrator.h` (friction=0 → energy conserved to tol).
- **Idea:** invariants are derivable from structure alone; a run violating a conserved sum is provably
  buggy with no reference data — and (GROMACS insight) a conservation violation *fingerprints a model
  defect*, not a tuning miss. The LAMMPS `econserve` trick: when a claim legitimately injects/removes a
  quantity, check the *augmented* balance, not reject outright.
- **Phase 30 amendment — 30.7 + irreducibility:** auto-extract each mechanism's invariant set (conserved
  combinations, dimensional balance, monotonicities); any prediction violating an invariant auto-rejects
  and is logged as a model defect.

---

## D. Proper nulls and convergence (fixes the "trivially loose at large start" pathology)

### 8. Generative stochastic null + intrinsic-noise floor
- **Source:** `stochastic-chemical-kinetics/GillesPy2/gillespy2/core/results.py` (`average_ensemble`,
  `stddev_ensemble`, ~lines 551/588); `stochpy/.../StochSim.py` (`GetWaitingtimes`,
  `GetSpeciesAutocovariances`), `Analysis.py` (`GetSpeciesDistributions`, Fano/CV).
- **Idea:** the Gillespie SSA gives the *exact* trajectory distribution of a network — the correct null.
  "Is this deviation real?" becomes "does the observed value fall outside the simulated ensemble band?"
  Intrinsic noise has known scaling (Fano ≈ 1, CV ∝ 1/√N).
- **Phase 30 amendment — null-model floor + 30.1:** replace assumed-Gaussian nulls with a *generated*
  one — simulate the no-effect mechanism N times, demand the real statistic beat the
  (multiple-comparisons-corrected) ensemble quantile, and reject deviations below the Fano/CV envelope.
  Waiting-time / autocorrelation shape vs the null is a validator-independent structure detector feeding
  30.2 / 30.6.

### 9. Convergence / equilibration gate — directly fixes "trivially loose at large start"
- **Source:** `molecular-dynamics/gromacs/docs/reference-manual/averages.rst` (discard the
  pre-equilibration transient; closed-form recompute over the post-equilibration window);
  `openmm/tests/TestAndersenThermostat.h` (`step(10000)` discarded *before* the measurement loop).
- **Idea:** a statistic computed over a window that includes the transient is *invalid, not just
  imprecise.*
- **Phase 30 amendment — new convergence gate:** before reporting any fitted statistic, run a
  convergence diagnostic (block-average stability, drift test) and discard the un-converged prefix.
  **Refuse to publish a bound whose value still depends on where the window starts** — precisely the
  mertens pathology (fitted on one regime, asserted universally).

### 10. Stochastic-pass assertion semantics
- **Source:** `molecular-dynamics/openmm/openmmapi/include/openmm/internal/AssertionUtilities.h`
  (`ASSERT_USUALLY_TRUE` / `ASSERT_USUALLY_EQUAL_TOL` — "This test is stochastic and may occasionally
  fail").
- **Phase 30 amendment — null-model floor + adversarial replication:** formally distinguish
  *stochastic-pass* checks (must hold *usually*, with a quantified failure rate, across N seeds) from
  *exact* ones, so a single lucky pass can't publish and a single unlucky re-seed can't falsely retract.

---

## E. Dedup & discovery-detection primitives

### 11. Avida phenotype-ID bitmask = the dedup primitive that kills 212-duplicates
- **Source:** `digital-life/avida/avida-core/source/main/cPhenotype.h::CalcID` (`phen_id = Σ 1<<i` over
  exhibited tasks).
- **Striking parallel:** Lenia *lacks* this gate — `lenia/Lenia/Python/LeniaND.py::append_found_file`
  appends every survivor with no descriptor dedup, and `Python/found/` literally contains
  `212.json … 333.json` near-duplicate creatures — the *same* failure mode as the 212 inequalities. Lenia
  has the behavioral descriptor (`Analyzer.calc_stats`) but never checks it before saving.
- **Phase 30 amendment — 30.4 / retirement / the 212-duplicate problem:** hash each claim to a
  capability-set bitmask (which discovery-classes it instantiates); suppress/retire any claim whose
  bitmask already exists. This is the dedup gate the descriptor-archive (external-patterns #1) needs to
  actually fire — take descriptor **and** dedup gate together.

### 12. Truth-table fingerprint = honest "a genuinely new capability appeared" detector
- **Source:** `digital-life/avida/avida-core/source/main/cTaskLib.cc::SetupTests` + `cTaskContext::GetLogicId`
  (canonical I/O signature from replayed inputs; rejects the moment any bit is inconsistent with a single
  boolean function; EQU = `logic_id ∈ {153,165,195}`).
- **Phase 30 amendment — 30.6 + 30.7 + honest-labeling:** give each claimed phenomenon a canonical
  behavioral signature from *held-out* predictions; label it a "discovery" only if the signature exactly
  and consistently matches a target class on every test case; keep a registry of which signatures are
  hard/novel vs already-known-trivial.

### 13. Objective-free survival search + discovery-yield health metric
- **Source:** `lenia/Lenia/Python/LeniaND.py::do_search` (no fitness objective — reject `is_empty` "dead"
  and `is_full` "exploded"; keep what non-trivially persists; track `search_success/search_total`).
- **Phase 30 amendment — 30.4 / 30.6 / anti-stagnation:** invert objective-maximization — reject the two
  degenerate regimes (trivially-true / no out-of-sample support, and tautology / holds-for-everything),
  promote only candidates that survive both on held-out data, and track "fraction of mining attempts
  yielding a claim that is non-degenerate **and** new-to-archive" as the open-endedness health metric
  (complements the gaming-rate metric from `phase-30-external-patterns.md`).

---

## F. Amendments to existing sub-items

### 14. Closed generative operator grammar (30.5 — escape the flat-vocabulary trap)
- **Source:** `artificial-chemistry/alchemy-docker/.../interact.c::_lambda_action` (a "reaction" =
  apply object A to B as a λ-term, reduce to normal form — product is a new expression, not an
  enumerated pair); `RAF/src/BinPolModel.cxx::initBinPolModel` (concatenation grammar generates the whole
  species set from a tiny food set); admissibility sieve `alchemy filter.c::syntax_sieve` (positive AND
  negative match constraints + type-synthesis filter before expensive reduction).
- **Phase 30 amendment — 30.5:** chemistry never enumerates pairs from a flat list — it has a small set of
  *closed* operators (output type = input type, so products feed back as new reactants). Replace the
  `lhs ≤ k·rhs + c` pair-miner with ~5 closed observable-construction operators (ratio, product,
  difference, log, finite-difference/gradient), applied recursively to bounded depth, **MDL-gated by the
  description length of the operator tree**. Gate proposals through cheap structural sieves
  (dimensional-analysis type check + a *negative* "must NOT be a monotone re-expression of an existing
  finding" filter) before any out-of-sample validation — shrinking the multiple-comparisons denominator.

### 15. Typed-port composition + flatten (30.3)
- **Source:** `systems-biology/snakes/snakes/plugins/synchro.py` (`PetriNet.synchronise/restrict/scope` —
  compose sub-nets by matching named conjugate actions, retaining provenance); `libsbml` L3 `comp` package
  (submodel + port + flattening).
- **Phase 30 amendment — 30.3:** give each finding typed input/output ports; `compose(A,B)` fires only
  when an output port of A unifies with an input port of B, emitting a derived finding with provenance
  edges to both parents; **flatten** the composite back to a single model that must re-pass the same
  null-model + invariant gates as a primitive finding.

---

## How this complements `phase-30-external-patterns.md`

| Layer | Source family | What it provides |
|-------|---------------|------------------|
| **Process / bookkeeping** | self-improving frameworks (DGM, HGM, STOP) | descriptor archive, clade-value selection, private validator + gaming-rate, sublinear budget, retirement-by-revert |
| **Content / verification** | chemistry / ALife / simulation (this doc) | RAF-closure irreducibility, mechanism-as-runnable-model, FIM identifiability, conservation/unit gates, generative null + convergence gate, dedup/discovery primitives |

The two are orthogonal and additive. The **descriptor archive** (external #1) needs the **dedup gate**
(#11 here) to actually fire; the **clade-value selection** (external #2) ranks what the **RAF/iRAF**
(#1 here) deems irreducible; the **private validator** (external #3) runs the **runnable-model +
invariant + identifiability** gates (#3/#5/#7) as its hidden checks. Process layer decides *which* to
keep; content layer decides *what is real*.
