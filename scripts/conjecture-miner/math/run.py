"""Math-domain conjecture miner — Phase 23.1 CLI.

Reuses scripts/graffiti/conjectures.py as the engine; provides only the
math substrate (substrate.py) and vocabulary (invariants.py). The engine
imports `vocabulary` from whichever `invariants` module is first on
sys.path, which is why we prepend the math/ directory before graffiti/.

Sample axis = window position (one row per window).
Run axis    = window size W in {500, 5_000, 50_000}.
Knob axis   = window_size, so the Spearman filter can drop conjectures
              whose tightness just restates W.

Usage:
  python3 scripts/conjecture-miner/math/run.py
  python3 scripts/conjecture-miner/math/run.py --out apps/research-service/research/conjectures-math.json --reset
"""

from __future__ import annotations

import argparse
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
# Order matters: math/ first so its invariants.py shadows graffiti/'s
# when conjectures.py does `from invariants import vocabulary`.
sys.path.insert(0, os.path.normpath(os.path.join(_HERE, "..", "..", "graffiti")))
sys.path.insert(0, _HERE)

import conjectures                # from scripts/graffiti/
import invariants                 # from scripts/conjecture-miner/math/
import substrate                  # ditto
from sweep import SweepAxis       # from scripts/graffiti/


# Each entry: (run_name, window_size, [offsets...])
RUNS = [
    ("W500_low",   500,    [100, 1_000, 5_000, 10_000, 50_000, 100_000]),
    ("W5k_mid",    5_000,  [5_000, 50_000, 100_000, 500_000]),
    ("W50k_high",  50_000, [50_000, 500_000, 1_000_000]),
]


DEFAULT_OUT = os.path.normpath(
    os.path.join(_HERE, "..", "..", "..", "apps", "research-service", "research",
                 "conjectures-math.json")
)


def _materialize_run(prep, window_size: int, offsets: list[int]) -> list[dict[str, float]]:
    samples: list[dict[str, float]] = []
    for offset in offsets:
        raw = substrate.evaluate_window(prep, offset, window_size)
        raw["_window_start"] = offset
        raw["_window_size"] = window_size
        samples.append(invariants.extract(raw))
    return samples


def _max_n_required(runs) -> int:
    return max(start + size for _, size, offsets in runs for start in offsets)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--reset", action="store_true",
                    help="Discard existing output file before mining.")
    args = ap.parse_args()

    if args.reset and os.path.exists(args.out):
        os.remove(args.out)

    max_n = _max_n_required(RUNS)
    print(f"prep: building smallest-prime-factor table for n <= {max_n:,} ...")
    t0 = time.monotonic()
    prep = substrate.PreparedRange(max_n)
    print(f"  done in {time.monotonic() - t0:.1f}s")

    store: list[conjectures.Inequality] = []
    axes_map: dict[str, dict[str, float]] = {"window_size": {}}

    for run_name, window_size, offsets in RUNS:
        cfg_hash = f"W{window_size}"
        print(f"\nrun '{run_name}' (W={window_size:,}, {len(offsets)} offsets)")
        t0 = time.monotonic()
        samples = _materialize_run(prep, window_size, offsets)
        print(f"  evaluated {len(samples)} windows in {time.monotonic() - t0:.1f}s")

        axes_map["window_size"][cfg_hash] = float(window_size)

        if not store:
            store = conjectures.seed(samples)
            print(f"  seeded {len(store)} candidate inequalities")
        else:
            confirmed, retired = conjectures.update(store, run_name, cfg_hash, samples)
            print(f"  confirmed={confirmed} retired={retired}")

    axes = [SweepAxis(knob=k, config_values=v) for k, v in axes_map.items() if v]
    dropped = conjectures.filter_knob_restatements(store, axes)
    print(f"\nknob-restatement filter dropped {dropped} conjecture(s)")

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    conjectures.save(store, args.out)
    print(f"\n{len(store)} surviving conjectures → {args.out}")

    # Two views: pure upper bounds (k=0) sorted by c are usually the most
    # interpretable known-result rediscoveries; non-edge linear fits
    # (k not on the grid extreme) are the candidates for real cross-scalar
    # relations. The engine's grid is coarse (v1 limitation), so most
    # non-zero k survivors end up at the ±2 edge and aren't informative —
    # filter those out for the human-facing summary.
    GRID_EDGE = {-2.0, 2.0}
    bounds = sorted([c for c in store if c.k == 0.0], key=lambda c: c.c)
    fits = sorted(
        [c for c in store if c.k != 0.0 and c.k not in GRID_EDGE],
        key=lambda c: c.c,
    )

    # Deduplicate by (lhs, c) — k=0 conjectures pair with every rhs but
    # the bound itself is independent of rhs, so they show up many times.
    seen: set[tuple[str, float]] = set()
    dedup_bounds: list[conjectures.Inequality] = []
    for c in bounds:
        key = (c.lhs, round(c.c, 6))
        if key not in seen:
            seen.add(key)
            dedup_bounds.append(c)

    print(f"\nPure upper bounds (k=0), deduped, sorted by tightness:")
    for c in dedup_bounds[:15]:
        configs = len(c.confirmed_configs)
        print(f"  {c.lhs:>26s} <= {c.c:+.4f}"
              f"   runs={c.confirmed_runs:<2d} cfgs={configs:<2d}"
              f"   p99_slack={c.p99_slack:+.4f}")

    print(f"\nLinear fits (k not on grid edge), sorted by c:")
    if not fits:
        print("  (none — k-grid coarseness leaves most fits at the ±2 boundary;")
        print("   see conjectures._tightest_k_c for the v1 grid)")
    for c in fits[:10]:
        configs = len(c.confirmed_configs)
        print(f"  {c.lhs:>22s} <= {c.k:+.2f} * {c.rhs:<22s} {c.c:+.4f}"
              f"   runs={c.confirmed_runs:<2d} cfgs={configs:<2d}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
