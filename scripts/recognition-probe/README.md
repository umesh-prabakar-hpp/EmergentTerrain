# Recognition coverage probe — phase-26 §26.6

Acceptance gate for `app.julia.recognise.enabled=true`. Feeds 30
known constants to `/recognise/constant` (PSLQ via mpmath against
the BASIC + PHYSICS basis sets) and 30 known integer sequences to
`/recognise/sequence` (OEIS Superseeker over HTTPS), reports per-
fixture pass/fail, and confirms two acceptance bars:

| Stream | Bar | Why this number |
|---|---|---|
| Constants | ≥25/30 | 5 fixtures in the set are deliberately should-miss (1/π, Khinchin, Mills, two random-digit streams) — confirms PSLQ isn't hallucinating. The remaining 25 should all recognise. |
| Sequences | ≥27/30 | 3 fixtures are deliberately should-miss random integer streams. The remaining 27 should all hit OEIS. |

## When to run

1. After installing mpmath under PyCall (`dnf install python3-mpmath`
   on Fedora — usually a transitive dep of python3-sympy installed
   in phase 25).
2. After upgrading the sidecar binary to a commit that includes
   `0bb4546` (recognise/constant) AND `d142dad` (recognise/sequence).
3. Before flipping `app.julia.recognise.enabled=true` in
   `application.yml`.
4. As a smoke test after any code change to `handle_recognise_constant`
   / `handle_recognise_sequence` / the basis allowlists.

## Run

```bash
./run.sh
```

Custom sidecar URL:

```bash
RECOGNITION_PROBE_BASE_URL=http://other-host:8088 ./run.sh
```

Constants-only (faster; skips the OEIS rate-limited section):

```bash
./run.sh --skip-sequences
```

Looser OEIS rate-limit (if your network is slow OR OEIS started
rate-limiting at 1 req/sec):

```bash
RECOGNITION_PROBE_RATE_LIMIT_S=2.0 ./run.sh
```

Expected wall-clock: ~10–15s for 30 constants (PSLQ is fast under
mpmath) + ~35s for 30 sequences at the 1s OEIS rate-limit = ~50s
total.

## Fixture categories

**Constants (30):**
1. The 8 BASIC-basis constants directly: `pi, e, ln(2), ln(3),
   gamma, zeta(3), pi^2, -pi`.
2. The 4 PHYSICS-only additions: `zeta(2), zeta(5), Catalan, phi`.
3. 5 integer multiples of basis elements: `2*pi, 3*e, 2*gamma,
   -zeta(3), 5*ln(2)`.
4. 5 small integer linear combinations: `pi+e, e-pi, ln(2)+ln(3),
   gamma+ln(2), zeta(2)+zeta(3)`.
5. 3 larger-coefficient combinations: `2*pi+3*e, phi+ln(2),
   Catalan+gamma`.
6. 5 deliberately should-miss: `1/pi, Khinchin, Mills, two random-
   digit streams`. PSLQ must return no_match on each.

**Sequences (30):**
1. 15 classical A-numbered sequences: Fibonacci, primes, powers of
   2/3, triangular/square/cube numbers, Catalan, factorial, Lucas,
   all-ones, naturals, Bell, partitions, tetrahedral.
2. 7 more well-known OEIS classics: pentagonal, hexagonal, Pell,
   tribonacci, derangements, Motzkin, Fubini.
3. 5 less famous but still in OEIS: centered hexagonal, Padovan,
   Schröder, heptagonal, squares-of-Fib.
4. 3 deliberately should-miss random integer streams.

## Result-format flexibility

PSLQ-rendered names may differ from the fixture's `expect_name`
across mpmath / SymPy versions (e.g. `zeta(3)` vs `apery`). The
probe uses **substring match** on the returned name — any of the
expected substrings hitting is enough. When a fixture starts
failing because PSLQ renders the name slightly differently, the
right fix is to **add** the new form to the fixture's
`expect_name` rather than weaken the matcher.

OEIS classification ("identity" vs "transformed") is informational
only; the probe checks the A-number against the fixture's
`expect_oeis_id`, not the transform type.

## Why not the two corpus-level acceptance pieces?

Phase 26 §26.6's bar has three pieces; this probe is piece 1.
Pieces 2 ("zero hallucinated cite-recognised:* handles across the
first 30 papers shipped with the flag on") and 3 ("≥80% of pre-
existing math papers re-narrated under Phase 26 read as well or
better") are operator workflow items that need a live corpus run.
The validator from §26.4b (`RecognitionCitationValidator`) is the
tool the operator uses to mechanise piece 2; piece 3 is a manual
read.
