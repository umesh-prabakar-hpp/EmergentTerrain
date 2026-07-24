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
| Width holdout | 900,000,000 | 20,000 | 10 |

The professor may select `k,c` only from a tight (`relative slack <= 0.05`) HELD fit call. That
exact pair is locked before any holdout is read. The fit also needs a same-grid `REFUTED` call
with the same growth coefficient and a changed intercept. Every registered holdout must be a
tight HELD call with the unchanged pair.

## Consequences

A holdout failure is evidence against the fitted finite envelope; it is not permission to refit
that holdout. Extra windows remain useful exploratory sensitivity checks, but cannot replace a
registered call. The narration repair loop supplies the missing exact directive, and the enabled
publication gate routes any incomplete trace to `NEEDS_REVISION`.

This protocol supports only the recorded finite-window result. It does not claim an asymptotic
law for prime gaps.
