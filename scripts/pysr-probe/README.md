# PySR recovery probe — phase 24 §24.5

Acceptance gate for `app.julia.pysr.enabled=true`. Feeds five synthetic
physics datasets to the `julia-math-sidecar` `/pysr` endpoint and
checks whether SymbolicRegression.jl can recover the underlying law to
near-zero MSE within the default search budget.

Run once after installing SymbolicRegression.jl on the sidecar and
before flipping the production flag. Re-run if you bump
`app.julia.pysr.{populations,iterations,timeoutMs}` to verify the new
budget still clears the bar.

## What it tests

| Fixture | Law | Operators | Default mse threshold |
|---|---|---|---|
| `newton-gravity` | `F = G·m1·m2 / r²` (r baked, expect `~m1*m2` after scaling) | `+ - *` | `1e-6` |
| `coulomb` | `F = k·q1·q2 / r²` (r baked) | `+ - *` | `1e-6` |
| `rc-decay` | `V = V0 · exp(-0.5·t)` | `+ - * exp` | `1e-6` |
| `arrhenius` | `k = A · exp(-5000/T)` | `+ - * / exp` | `1e-3` |
| `hooke` | `F = -k·x` (linear) | `+ - *` | `1e-9` |

Per-fixture sample grid is 6 × 6 = 36 rows by default. Each fixture
runs with its own minimal operator set — smaller sets converge faster
and reduce the chance of overfitting a spurious functional form.

## Acceptance bar

**≥4 of 5 fixtures pass** (best Pareto-top-3 entry's MSE under the
fixture threshold). The probe exits 0 on pass, 1 on miss.

If 5/5 passes, you're cleared to flip:
```yaml
app:
  julia:
    pysr:
      enabled: true
```

If 4/5 passes — investigate which fixture missed and whether your
production budget is large enough. The default
`populations=8 iterations=40` for production is lighter than the
probe's `populations=16 iterations=80`; if a fixture barely passed
the probe, it may not pass at production budget.

If <4/5 passes, do not flip the flag. Most likely causes:
1. `SymbolicRegression.jl` was added to `Project.toml` but
   `Pkg.instantiate` hasn't run on the sidecar host yet → all calls
   return server errors. Run:
   ```
   cd julia-math-sidecar
   julia --project=. -e 'using Pkg; Pkg.instantiate()'
   ```
2. The sidecar process is still JIT-compiling SymbolicRegression on
   the first probe call. The probe gives 5 s of headroom past
   `timeoutMs`; re-run after the first call completes to get the
   warm-path numbers.
3. The operator set or sample grid for the failing fixture is genuinely
   too narrow. Try a deeper search:
   ```
   PYSR_PROBE_ITERATIONS=160 PYSR_PROBE_POPULATIONS=24 ./run.sh
   ```

## Usage

Standard run (assumes sidecar on `localhost:8088`):
```bash
./run.sh
```

Custom sidecar URL:
```bash
PYSR_PROBE_BASE_URL=http://other-host:8088 ./run.sh
```

Run only one fixture (useful when iterating on operator choices):
```bash
./run.sh --filter hooke
./run.sh --filter newton-gravity,coulomb
```

Deeper search:
```bash
./run.sh --populations 24 --iterations 160 --samples-per-dim 8
```

## What's in scope (and what isn't)

In scope (this script's job):
- Offline acceptance of the SR engine's recovery quality on known laws.

Not in scope (operator tasks per phase-24 §24.5 checks 2 + 3):
- **In-corpus signal** — after 20 PHYSICS+MATERIALS papers ship with
  the flag on, ≥3 should cite a non-trivial PySR-recovered expression
  and pass `SymbolicRegressionEvidenceValidator`. Measure from the
  corpus directory, not here.
- **No math-domain regression** — math papers shouldn't call
  `symbolic_regress` because their substrates produce inequalities,
  not parameter sweeps. Audit the tool-call log; expect zero
  `symbolic_regress` calls on math-domain papers.

Both of those need a live system + paper corpus and don't belong in
this offline probe.

## File layout

| File | Role |
|---|---|
| `probe.py` | Main script — fixtures, HTTP, acceptance check. Pure stdlib. |
| `run.sh` | One-shot wrapper with env-var knobs. |
| `README.md` | This file. |

No Python deps to install. Tested on Python 3.11+ but should work
on 3.8+.
