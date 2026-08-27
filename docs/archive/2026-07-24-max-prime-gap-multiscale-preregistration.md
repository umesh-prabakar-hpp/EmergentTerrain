# `max_prime_gap` preregistered multi-scale protocol

## Purpose

The prior shared-envelope comparison accepted one affine `max_prime_gap` versus
`log_sq_window_start` relation across several locations even when most grids were loose. The
coefficient could also be tightened after seeing each grid. That is exploratory calibration, not
an auditable multi-scale confirmation.

## Registered design

`locked-max-prime-gap-multiscale:v1` fixes these verifier grids before computation:

| Role | `window_start` | `window_size` | `num_windows` |
| --- | ---: | ---: | ---: |
| Fit | 100,000,000 | 10,000 | 10 |
| Near location holdout | 150,000,000 | 10,000 | 10 |
| Far location holdout | 900,000,000 | 10,000 | 10 |
| Overlapping-offset holdout | 900,005,000 | 10,000 | 10 |
| Width holdout | 900,000,000 | 20,000 | 5 |

The width holdout spans exactly 100,000 integers (20,000 × 5), matching the verifier's
fixed maximum-work cap. This preserves an independent doubled-width test without issuing a
request the verifier must reject.

The professor may select `k,c` only from a tight (`relative slack <= 0.05`) HELD fit call. That
exact pair is locked before any holdout is read. The fit also needs a same-grid `REFUTED` call
with the same growth coefficient and a changed intercept. Every registered holdout must be a
tight HELD call with the unchanged pair.

## Consequences

A holdout failure is evidence against the fitted finite envelope; it is not permission to refit
that holdout. Extra windows remain useful exploratory sensitivity checks, but cannot replace a
registered call. The narration repair loop supplies the missing exact directive, and the enabled
publication gate routes any incomplete trace to `NEEDS_REVISION`.

## Protocol-state execution

With `app.research.max-prime-gap-multiscale-preregistration-executor.enabled=true`, the app does
not ask the model to repeat a verifier-owned operational choice. Once a fit-grid result says
`LOOSE: ... re-verify at k=...`, `MaxPrimeGapProtocolStateExecutor` carries that exact suggested
`k` onto the registered fit grid. Once a tight result says `TIGHT HOLD ... re-verify with c=...`,
it carries that exact instrument-named intercept into the same-grid refutation. It then executes
the fixed holdouts in their registered order with the locked pair.

This is deliberately bounded to six calls: tight fit, refutation, and four holdouts. It never
invents a coefficient or intercept, retries a failed automatic call, or refits a holdout. The AI
still chooses the study framing and interprets the completed or failed trace; the executor only
makes the pre-registered measurement mechanics reproducible. Set the flag to `false` to return to
prompt-only corrective reruns.

This protocol supports only the recorded finite-window result. It does not claim an asymptotic
law for prime gaps.
