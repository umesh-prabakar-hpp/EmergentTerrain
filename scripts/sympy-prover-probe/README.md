# SymPy coverage probe — phase-25 §25.5

Acceptance gate for `app.julia.sympy.enabled=true`. Feeds ten classical
hypergeometric / combinatorial identities to the `julia-math-sidecar`
`/prove_identity` endpoint and reports whether each one reaches
`status="proved"` with a closed form matching a known canonical form.

## When to run

1. After installing SymPy on the sidecar host (`dnf install python3-sympy`
   on Fedora; per-distro equivalent elsewhere).
2. After upgrading the sidecar binary to a commit that includes
   `db2b649` or later (the commit that registers `/prove_identity`).
3. Before flipping `app.julia.sympy.enabled=true` in `application.yml`.
4. As a smoke test after any code change to `handle_prove_identity`
   in the sidecar or to `try_gosper` / `try_wz_summation`.

## Run

Standard run (assumes sidecar on `localhost:8088`):

```bash
./run.sh
```

Custom sidecar URL:

```bash
SYMPY_PROBE_BASE_URL=http://other-host:8088 ./run.sh
```

Single-fixture probe (useful for debugging one regression):

```bash
./run.sh --filter binomial-theorem
```

Higher per-fixture budget (default 30s; SymPy on classical identities
should finish in <1s each):

```bash
SYMPY_PROBE_TIMEOUT_MS=60000 ./run.sh
```

## Fixtures

| # | Identity | Algorithm |
|---|---|---|
| 1 | Σ k from 0 to n = n(n+1)/2 | gosper |
| 2 | Σ k² from 0 to n = n(n+1)(2n+1)/6 | auto |
| 3 | Σ k³ from 0 to n = (n(n+1)/2)² | auto |
| 4 | Σ binomial(n,k) from 0 to n = 2^n | auto (zeilberger_wz) |
| 5 | Σ k·binomial(n,k) from 0 to n = n·2^(n-1) | auto (zeilberger_wz) |
| 6 | Σ binomial(n,k)² from 0 to n = C(2n,n) | auto (zeilberger_wz) |
| 7 | Σ 1/(k(k+1)) from 1 to n = n/(n+1) (telescoping) | gosper |
| 8 | Σ r^k from 0 to n = (r^(n+1)-1)/(r-1) (geometric) | gosper |
| 9 | Σ k·k! from 1 to n = (n+1)! - 1 | auto |
| 10 | Σ 1/(k(k+1)(k+2)) from 1 to n (triple telescoping) | auto |

All ten have known closed forms. Acceptance bar: **≥8/10 proved**.
Below that means either SymPy is misconfigured under PyCall (rare —
`python3 -c 'import sympy'` confirms install) or a regression
landed in the sidecar's Gosper / WZ dispatchers.

## Result-format flexibility

SymPy can render the same closed form in multiple textually-different
ways — the canonical example is `binomial(2n, n)` vs
`4^n · Γ(n+½) / (√π · Γ(n+1))`. Each fixture lists `accepted_results`
as a set of substrings; any single match counts as PASS. When SymPy's
canonical form changes (a new SymPy release, an internal simplification
rewrite), the right fix is to **add** the new form to the fixture's
`accepted_results` list, not to weaken the matcher.

## Why not the in-corpus uptake check + no-false-THEOREM spot check?

Phase 25 §25.5's acceptance bar has three pieces; this probe is piece 1.
Pieces 2 (`≥2 papers with prove_identity → THEOREM` in the first 30
MATHEMATICS shipped) and 3 (`zero false THEOREM grants`) are operator
workflow items that need a live corpus run. They're tracked in the
phase-24/25 status block, not gated through code in this directory.
