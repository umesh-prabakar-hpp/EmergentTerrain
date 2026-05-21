"""Sweep driver: walk a JSON spec of (config, runs) tuples.

A sweep spec ties each run to a *named knob configuration*, so the
post-processing filter can detect conjectures whose tightness moves
monotonically with a knob -- those are knob-restatements, not laws.

Spec format (JSON):

  {
    "knobs": ["implementation_drag", "pressure_sensitivity"],
    "configs": [
      {
        "name": "baseline",
        "values": {"implementation_drag": 0.10, "pressure_sensitivity": 0.5},
        "runs": [
          {"type": "dump", "path": "dumps/baseline-r1.json"},
          {"type": "live", "room": "<uuid>", "payload": "<signed>"}
        ]
      },
      {
        "name": "high-drag",
        "values": {"implementation_drag": 0.40, "pressure_sensitivity": 0.5},
        "runs": [{"type": "dump", "path": "dumps/high-drag-r1.json"}]
      }
    ]
  }

`config_hash` is derived from `values` (sorted JSON), so two configs with
identical knobs share a bucket regardless of their `name`.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Iterator

import auto_sweep
import load as loader


@dataclass
class SweepAxis:
    """One knob being varied across the sweep."""
    knob: str
    config_values: dict[str, float]   # config_hash -> knob value


@dataclass
class SweepPlan:
    runs: list[tuple[loader.RunTag, list[dict]]]
    axes: list[SweepAxis]


def load_spec(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _materialize_run(
    entry: dict,
    config_values: dict,
    base_url: str,
    window_minutes: int,
    spec_dir: str,
) -> tuple[loader.RunTag, list[dict]]:
    config_hash = loader._hash_config(config_values)
    kind = entry.get("type", "dump")
    if kind == "dump":
        raw_path = entry["path"]
        path = raw_path if os.path.isabs(raw_path) else os.path.join(spec_dir, raw_path)
        _, points = loader.load_file(path)
        seed = entry.get("seed") or os.path.basename(path)
        room = entry.get("room") or os.path.splitext(os.path.basename(path))[0]
        tag = loader.RunTag(room_id=room, config_hash=config_hash, seed=str(seed))
        return tag, points
    if kind == "live":
        points = loader.fetch_trend(
            entry["room"], entry["token"], base_url=base_url, window_minutes=window_minutes
        )
        tag = loader.RunTag(
            room_id=entry["room"],
            config_hash=config_hash,
            seed=str(entry.get("seed", "live")),
        )
        return tag, points
    raise ValueError(f"unknown run type: {kind!r}")


def plan(
    spec: dict,
    spec_path: str,
    base_url: str = "http://localhost:8081",
    window_minutes: int = 360,
    yes: bool = False,
) -> SweepPlan:
    """Resolve the spec into a list of (RunTag, points) and the knob axes.

    If the spec contains an `auto` block, drives the app through every config
    first (writes overrides, restart, soak, dump). Pass `yes=True` to opt in.
    """
    spec_dir = os.path.dirname(os.path.abspath(spec_path))
    if spec.get("auto"):
        spec = auto_sweep.materialize(spec, spec_dir, yes=yes)
    knobs: list[str] = spec.get("knobs") or []
    runs: list[tuple[loader.RunTag, list[dict]]] = []
    axes_map: dict[str, dict[str, float]] = {k: {} for k in knobs}

    for cfg in spec.get("configs", []):
        values = cfg.get("values") or {}
        config_hash = loader._hash_config(values)
        for knob in knobs:
            if knob in values:
                axes_map[knob][config_hash] = float(values[knob])
        for entry in cfg.get("runs", []):
            try:
                tag, points = _materialize_run(
                    entry, values, base_url, window_minutes, spec_dir
                )
            except (FileNotFoundError, KeyError) as e:
                print(f"  skip run in config {cfg.get('name','?')}: {e}")
                continue
            runs.append((tag, points))

    axes = [SweepAxis(knob=k, config_values=v) for k, v in axes_map.items() if v]
    return SweepPlan(runs=runs, axes=axes)
