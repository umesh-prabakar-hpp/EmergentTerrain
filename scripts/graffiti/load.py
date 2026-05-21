"""Loaders for government-trend rows.

Two modes:
  - load_dir(path): read trend dumps already on disk (one JSON file per run)
  - fetch_trend(room_id, payload, ...): POST to the live endpoint

Each run is returned as (tag, points) where tag identifies the run
(room_id + config_hash + seed) and points is a list[dict] of per-tick rows
with the GovernmentTrendPoint fields, camelCase preserved.
"""

from __future__ import annotations

import hashlib
import json
import os
import urllib.request
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class RunTag:
    room_id: str
    config_hash: str
    seed: str

    def key(self) -> str:
        return f"{self.room_id}|{self.config_hash}|{self.seed}"


def _hash_config(config: dict | None) -> str:
    if not config:
        return "default"
    blob = json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha1(blob).hexdigest()[:10]


def load_file(path: str) -> tuple[RunTag, list[dict]]:
    """Load a single dump file.

    Expected dump shape (produced by run.py --dump):
      {
        "room_id": "...",
        "config": {...},          # optional, used for config_hash
        "seed": "...",            # optional
        "points": [ {...}, ... ]  # GovernmentTrendPoint rows
      }
    Also accepts a bare list of points (room_id derived from filename).
    """
    with open(path) as f:
        blob = json.load(f)
    if isinstance(blob, list):
        points = blob
        room_id = os.path.splitext(os.path.basename(path))[0]
        tag = RunTag(room_id, "default", "0")
    else:
        points = blob.get("points") or blob.get("data") or []
        room_id = blob.get("room_id") or os.path.splitext(os.path.basename(path))[0]
        tag = RunTag(
            room_id=room_id,
            config_hash=_hash_config(blob.get("config")),
            seed=str(blob.get("seed", "0")),
        )
    return tag, points


def load_dir(dir_path: str) -> Iterable[tuple[RunTag, list[dict]]]:
    """Yield (tag, points) for every *.json under dir_path."""
    for name in sorted(os.listdir(dir_path)):
        if name.endswith(".json"):
            yield load_file(os.path.join(dir_path, name))


def fetch_trend(
    room_id: str,
    token: str,
    base_url: str = "http://localhost:8081",
    window_minutes: int = 360,
    timeout: float = 10.0,
) -> list[dict]:
    """POST to /room/{roomId}/government-trend and return the points list.

    `token` is the signed QR token the controller expects on the body
    (GovernmentTrendRequest.token).
    """
    body = json.dumps({"token": token, "windowMinutes": window_minutes}).encode()
    req = urllib.request.Request(
        f"{base_url}/room/{room_id}/government-trend",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.load(resp)
    return data.get("points") or data.get("data") or []
