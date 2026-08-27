"""Graffiti CLI: load runs, update the conjecture store, print survivors.

Usage:
  # Seed from a directory of trend dumps:
  python scripts/graffiti/run.py --runs-dir apps/research-service/research/graffiti/dumps

  # Update the existing store with one new dump:
  python scripts/graffiti/run.py --runs-dir apps/research-service/research/graffiti/dumps/new-batch

  # Fetch live from the running app and store one run:
  python scripts/graffiti/run.py --room <room-id> --token <signed-token>

Store lives at apps/research-service/research/graffiti/store.json by default
(under the ignored research-service runtime-artifact tree).
"""

from __future__ import annotations

import argparse
import os
import sys

# Allow running as a script: `python scripts/graffiti/run.py`
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import conjectures
import invariants
import load as loader  # avoid shadowing builtin
import sweep as sweep_mod


DEFAULT_STORE = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "apps", "research-service", "research",
                 "graffiti", "store.json")
)


def _to_samples(points: list[dict]) -> list[dict[str, float]]:
    samples: list[dict[str, float]] = []
    for p in points:
        s = invariants.extract(p)
        s["_tick"] = int(p.get("tick", len(samples)))
        samples.append(s)
    return samples


def _print_summary(store: list[conjectures.Inequality], retired: int) -> None:
    promoted = conjectures.promoted(store)
    surviving = sorted(
        store,
        key=lambda c: (-c.confirmed_runs, c.p99_slack),
    )[:25]
    print(f"\nSurviving conjectures: {len(store)}  (promoted: {len(promoted)})")
    for c in surviving:
        configs = len(c.confirmed_configs)
        marker = "*" if c in promoted else " "
        print(
            f"  {marker} {c.lhs:>22s} <= {c.k:+.3f} * {c.rhs:<22s} {c.c:+.3f}"
            f"   runs={c.confirmed_runs:<3d} cfgs={configs:<2d} p99_slack={c.p99_slack:+.4f}"
        )
    print(f"\nRetired this batch: {retired}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default=DEFAULT_STORE)
    ap.add_argument("--runs-dir", help="Directory of trend dump JSON files.")
    ap.add_argument("--room", help="Live mode: room UUID to fetch.")
    ap.add_argument("--token", help="Live mode: signed QR token (GovernmentTrendRequest.token).")
    ap.add_argument("--sweep", help="Path to a JSON sweep spec (see sweep.py docstring).")
    ap.add_argument(
        "--knob-filter-threshold", type=float, default=0.95,
        help="|Spearman| above which a conjecture is treated as a knob-restatement.",
    )
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--window-minutes", type=int, default=360)
    ap.add_argument("--reset", action="store_true", help="Discard existing store before running.")
    ap.add_argument(
        "--yes", action="store_true",
        help="Opt in to restart-based automation in spec.auto (stops/starts the local app).",
    )
    args = ap.parse_args()

    if args.reset and os.path.exists(args.store):
        os.remove(args.store)

    store = conjectures.load(args.store)
    seeded = not store
    confirmed_total = 0
    retired_total = 0
    runs_processed = 0

    def process(tag: loader.RunTag, points: list[dict]) -> None:
        nonlocal store, confirmed_total, retired_total, runs_processed
        samples = _to_samples(points)
        if not samples:
            print(f"  skip {tag.key()}: empty")
            return
        if not store:
            store = conjectures.seed(samples)
            print(f"  seeded {len(store)} candidate inequalities from {tag.key()}")
        else:
            confirmed, retired = conjectures.update(store, tag.key(), tag.config_hash, samples)
            confirmed_total += confirmed
            retired_total += retired
            print(f"  update {tag.key()}: confirmed={confirmed} retired={retired}")
        runs_processed += 1

    sweep_dropped = 0
    sweep_axes: list = []

    if args.sweep:
        spec = sweep_mod.load_spec(args.sweep)
        plan = sweep_mod.plan(spec, args.sweep, args.base_url, args.window_minutes, yes=args.yes)
        sweep_axes = plan.axes
        print(f"sweep: {len(plan.runs)} runs across {len(plan.axes)} knob axis/axes")
        for tag, points in plan.runs:
            process(tag, points)
    elif args.runs_dir:
        for tag, points in loader.load_dir(args.runs_dir):
            process(tag, points)
    elif args.room and args.token:
        points = loader.fetch_trend(args.room, args.token, args.base_url, args.window_minutes)
        tag = loader.RunTag(room_id=args.room, config_hash="live", seed="live")
        process(tag, points)
    else:
        ap.error("provide --sweep, --runs-dir, or both --room and --token")

    if sweep_axes:
        sweep_dropped = conjectures.filter_knob_restatements(
            store, sweep_axes, threshold=args.knob_filter_threshold
        )
        print(f"knob-restatement filter dropped {sweep_dropped} conjecture(s)")

    conjectures.save(store, args.store)
    print(f"\nProcessed {runs_processed} run(s). Store: {args.store}")
    if seeded:
        print("(this was the seeding run; rerun with more runs to start refuting)")
    _print_summary(store, retired_total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
