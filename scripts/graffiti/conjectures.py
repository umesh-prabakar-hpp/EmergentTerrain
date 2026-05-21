"""Inequality conjectures: seed, refute, tighten, persist.

v1 supports a single family:  lhs <= k * rhs + c
For each ordered pair (lhs, rhs) of vocabulary names, we maintain the
tightest (k, c) such that the inequality holds across all observed
ticks. A run that produces a tick violating the inequality by more
than 2 * TOL retires the conjecture and records the witness.
"""

from __future__ import annotations

import itertools
import json
import math
import os
from dataclasses import asdict, dataclass, field
from typing import Iterable

from invariants import vocabulary

TOL = 0.02            # slack tolerance (in scalar units)
PROMOTE_RUNS = 50     # confirmed runs needed to "promote"
PROMOTE_CONFIGS = 5   # across this many distinct config hashes
NEAR_IDENTITY_CORR = 0.98  # drop trivially-correlated pairs


@dataclass
class Witness:
    run_key: str
    tick: int
    margin: float


@dataclass
class Inequality:
    lhs: str
    rhs: str
    k: float
    c: float
    confirmed_runs: int = 0
    confirmed_configs: list[str] = field(default_factory=list)
    refuted_by: list[Witness] = field(default_factory=list)
    p99_slack: float = 0.0
    # worst observed margin per config_hash (negative = headroom, positive = closer to violation)
    slack_by_config: dict[str, float] = field(default_factory=dict)
    dropped_by_knob: str | None = None  # set by filter_knob_restatements

    @property
    def id(self) -> str:
        return f"{self.lhs}<={self.k:+.3f}*{self.rhs}{self.c:+.3f}"

    def evaluate(self, samples: list[dict[str, float]]) -> tuple[float, int]:
        """Return (worst_margin, tick_index_of_worst). margin > 0 == violation."""
        worst = -math.inf
        worst_idx = 0
        for i, s in enumerate(samples):
            margin = s[self.lhs] - (self.k * s[self.rhs] + self.c)
            if margin > worst:
                worst = margin
                worst_idx = i
        return worst, worst_idx


# ---------- seeding ----------

def _tightest_k_c(lhs_vals: list[float], rhs_vals: list[float]) -> tuple[float, float] | None:
    """Pick (k, c) minimizing c such that lhs[i] <= k*rhs[i] + c for all i.

    We sweep k over a small grid and take c = max(lhs - k*rhs) for each.
    The (k, c) with the lowest c is returned. Cheap and good enough for v1;
    swap in a proper LP later if needed.
    """
    if not lhs_vals or not rhs_vals or len(lhs_vals) != len(rhs_vals):
        return None
    best: tuple[float, float] | None = None
    for k in [-2.0, -1.0, -0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]:
        c = max(lv - k * rv for lv, rv in zip(lhs_vals, rhs_vals))
        if best is None or c < best[1]:
            best = (k, c)
    return best


def _pearson(xs: list[float], ys: list[float]) -> float:
    n = len(xs)
    if n < 3:
        return 0.0
    mx = sum(xs) / n
    my = sum(ys) / n
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    if dx == 0 or dy == 0:
        return 0.0
    return num / (dx * dy)


def seed(samples: list[dict[str, float]]) -> list[Inequality]:
    """One-shot seed from the first run. Returns a deduped, novelty-filtered list."""
    names = vocabulary()
    out: list[Inequality] = []
    series = {n: [s[n] for s in samples] for n in names}
    for lhs, rhs in itertools.permutations(names, 2):
        if abs(_pearson(series[lhs], series[rhs])) >= NEAR_IDENTITY_CORR:
            continue  # near-identity, not a conjecture
        fit = _tightest_k_c(series[lhs], series[rhs])
        if fit is None:
            continue
        k, c = fit
        out.append(Inequality(lhs=lhs, rhs=rhs, k=k, c=c))
    return out


# ---------- update ----------

def update(
    store: list[Inequality],
    run_key: str,
    config_hash: str,
    samples: list[dict[str, float]],
) -> tuple[int, int]:
    """Refute or tighten every conjecture against one run. Returns (confirmed, retired)."""
    confirmed = 0
    retired = 0
    survivors: list[Inequality] = []
    for ineq in store:
        worst, worst_idx = ineq.evaluate(samples)
        if worst > 2 * TOL:
            ineq.refuted_by.append(
                Witness(run_key=run_key, tick=samples[worst_idx].get("_tick", worst_idx), margin=worst)
            )
            retired += 1
            continue  # drop from the living list
        # confirmed; tighten c toward the observed slack
        if worst < 0:
            # we have headroom; shrink c by half of the headroom (anti-overfitting damp)
            ineq.c = ineq.c + worst * 0.5
        ineq.confirmed_runs += 1
        if config_hash not in ineq.confirmed_configs:
            ineq.confirmed_configs.append(config_hash)
        ineq.p99_slack = max(ineq.p99_slack, worst)
        prev = ineq.slack_by_config.get(config_hash, -math.inf)
        if worst > prev:
            ineq.slack_by_config[config_hash] = worst
        confirmed += 1
        survivors.append(ineq)
    store[:] = survivors
    return confirmed, retired


def _spearman(xs: list[float], ys: list[float]) -> float:
    """Spearman rank correlation = Pearson on ranks. Ties get average ranks."""
    n = len(xs)
    if n < 3:
        return 0.0

    def rank(arr: list[float]) -> list[float]:
        order = sorted(range(n), key=lambda i: arr[i])
        ranks = [0.0] * n
        i = 0
        while i < n:
            j = i
            while j + 1 < n and arr[order[j + 1]] == arr[order[i]]:
                j += 1
            avg = (i + j) / 2.0
            for m in range(i, j + 1):
                ranks[order[m]] = avg
            i = j + 1
        return ranks

    return _pearson(rank(xs), rank(ys))


def filter_knob_restatements(
    store: list[Inequality],
    axes: list,  # list[SweepAxis] from sweep.py; typed loosely to avoid circular import
    threshold: float = 0.95,
    min_configs: int = 3,
) -> int:
    """Drop conjectures whose per-config slack moves monotonically with a knob.

    Returns the number dropped. Each drop is annotated with the knob name in
    `dropped_by_knob` so the caller can report what's been filtered.
    """
    if not axes:
        return 0
    survivors: list[Inequality] = []
    dropped = 0
    for ineq in store:
        culprit: str | None = None
        for axis in axes:
            xs: list[float] = []
            ys: list[float] = []
            for cfg_hash, knob_val in axis.config_values.items():
                if cfg_hash in ineq.slack_by_config:
                    xs.append(knob_val)
                    ys.append(ineq.slack_by_config[cfg_hash])
            if len(xs) >= min_configs and abs(_spearman(xs, ys)) >= threshold:
                culprit = axis.knob
                break
        if culprit:
            ineq.dropped_by_knob = culprit
            dropped += 1
            continue
        survivors.append(ineq)
    store[:] = survivors
    return dropped


def promoted(store: Iterable[Inequality]) -> list[Inequality]:
    return [
        c for c in store
        if c.confirmed_runs >= PROMOTE_RUNS and len(c.confirmed_configs) >= PROMOTE_CONFIGS
    ]


# ---------- persistence ----------

def save(store: list[Inequality], path: str) -> None:
    blob = {
        "version": 1,
        "tol": TOL,
        "inequalities": [
            {**asdict(c), "refuted_by": [asdict(w) for w in c.refuted_by]}
            for c in store
        ],
    }
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as f:
        json.dump(blob, f, indent=2, sort_keys=False)


def load(path: str) -> list[Inequality]:
    if not os.path.exists(path):
        return []
    with open(path) as f:
        blob = json.load(f)
    out: list[Inequality] = []
    known = {f.name for f in Inequality.__dataclass_fields__.values()}
    for row in blob.get("inequalities", []):
        witnesses = [Witness(**w) for w in row.get("refuted_by", [])]
        row = {k: v for k, v in row.items() if k in known}
        row["refuted_by"] = witnesses
        out.append(Inequality(**row))
    return out
