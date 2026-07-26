# Locked max-divisor-count measurement preregistration

`conjecture/max_divisor_count` now uses `locked-max-divisor-count-measurement:v1` rather than the generic affine registration.

The study begins with a fixed `verify_growth` Wigert scale ladder (`window_size=5_000`, `num_windows=20` per automatic rung, `target=0.69315`), then may select an affine `max_divisor_count` versus `wigert_bound` pair only on the fixed fit grid:

- `window_start=1_000_000_000`, `window_size=5_000`, `num_windows=20`

After the first tight fit (`relative slack <= 0.05`), its `k` and `c` are locked. The selected trace must then retain:

- a changed-intercept `REFUTED` call on that same fit grid;
- a location holdout at `1_500_000_000, 5_000, 20`;
- an overlapping-offset holdout at `1_000_005_000, 5_000, 20`; and
- a width holdout at `1_000_000_000, 10_000, 10`.

Each holdout must be a tight `HELD` result with the unchanged fit pair. A separately fitted, loose, or differently shaped envelope is exploratory evidence only.

Workflow promotion has an independent literature condition: the selected trace must call `search_literature` with exactly the `divisor` and `wigert` subject anchors, obtain an issued key, successfully `read_reference` that same key, and cite `[key]` in Related Work. This prevents an unrelated grounding result, a search-only hit, or an uncited source from satisfying the Wigert comparison requirement. If either the computational or source condition is absent, the enabled gate routes the paper to `NEEDS_REVISION` and records an auditable call-path marker.

The protocol is enabled by `app.research.max-divisor-count-measurement-preregistration-gate.enabled`. Set it to `false` only for a deliberate rollback. Its declared regression command is:

```bash
mvn test -Dtest=MaxDivisorCountMeasurementPreRegistrationTest
```
