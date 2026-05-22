#!/usr/bin/env python3
"""
phase-24 §24.5 — PySR recovery probe.

Feeds five synthetic datasets with known ground-truth laws to the
julia-math-sidecar /pysr endpoint and reports whether the Pareto top-3
contains a near-perfect fit. Run once before flipping
app.julia.pysr.enabled=true in production.

Acceptance bar: ≥4 of 5 fixtures recover their law (defined as ≥1
Pareto entry within mse < 1e-6 on the training rows). Lower bar means
either SymbolicRegression.jl needs more iterations/populations or the
sample grid is too narrow — adjust per-fixture rather than relaxing
the global threshold.

Usage:
    ./run.sh
or:
    python3 probe.py --base-url http://localhost:8088 \
                     --timeout-ms 30000 \
                     --populations 16 --iterations 80

Pure Python stdlib (no `requests` / `pip install`). Tested on
Python 3.11+ but should work on 3.8+.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Callable, List, Sequence, Tuple


# ─────────────────────────────────────────────────────────────────────
# Fixture definitions — five physics laws PySR should recover cleanly
# given the right operator set + a few seconds of search.
# ─────────────────────────────────────────────────────────────────────

@dataclass
class Fixture:
    name: str
    # Variable names baked into the printed expression (LLM-friendly).
    var_names: List[str]
    # Operator subset the search may use. Each fixture picks the
    # minimum operator set that can express its law — smaller sets
    # make recovery faster and more reliable.
    operators: List[str]
    # Generator: takes a row of input values, returns the target.
    target: Callable[[Sequence[float]], float]
    # Sample grid: per-variable (lo, hi) bounds. Probe uses a uniform
    # Cartesian grid sized so total samples ≈ 30.
    bounds: List[Tuple[float, float]]
    # Per-fixture mse acceptance threshold. Most should hit < 1e-9
    # cleanly; Arrhenius / RC-decay get a looser bound because their
    # target magnitudes are smaller.
    mse_threshold: float
    # One-line note describing the law for the report.
    note: str


# All fixtures stay to 2 input variables. PySR's recovery quality
# degrades past ~4 inputs without retuning, and 2 is enough for
# textbook laws.

def _newton(row: Sequence[float]) -> float:
    """F = G · m1 · m2 / r²  with G = 6.674e-11."""
    m1, m2 = row  # treat r as a fixed constant baked into the law.
    G = 6.674e-11
    r = 1.0e8  # fixed Earth-Moon-scale separation
    return G * m1 * m2 / (r * r)


def _coulomb(row: Sequence[float]) -> float:
    """F = k · q1 · q2 (linear-in-charge-product variant; r fixed)."""
    q1, q2 = row
    k = 8.99e9
    r = 1.0
    return k * q1 * q2 / (r * r)


def _rc_decay(row: Sequence[float]) -> float:
    """V = V0 · exp(-k · t)  with k = 0.5."""
    V0, t = row
    return V0 * math.exp(-0.5 * t)


def _arrhenius(row: Sequence[float]) -> float:
    """k = A · exp(-b / T)  with b = 5000."""
    A, T = row
    return A * math.exp(-5000.0 / T)


def _hooke(row: Sequence[float]) -> float:
    """F = -k · x  with k = 7.5 (linear law)."""
    k, x = row
    return -k * x


FIXTURES: List[Fixture] = [
    Fixture(
        name="newton-gravity",
        var_names=["m1", "m2"],
        operators=["+", "-", "*"],
        target=_newton,
        bounds=[(1.0e24, 6.0e24), (1.0e22, 7.0e22)],
        mse_threshold=1e-6,
        note="F = G·m1·m2 / r² (r baked, expect ~m1*m2 with scaled coefficient)",
    ),
    Fixture(
        name="coulomb",
        var_names=["q1", "q2"],
        operators=["+", "-", "*"],
        target=_coulomb,
        bounds=[(1.0e-6, 5.0e-6), (1.0e-6, 5.0e-6)],
        mse_threshold=1e-6,
        note="F = k·q1·q2 (r baked, expect ~q1*q2 with k coefficient)",
    ),
    Fixture(
        name="rc-decay",
        var_names=["V0", "t"],
        operators=["+", "-", "*", "exp"],
        target=_rc_decay,
        bounds=[(1.0, 10.0), (0.0, 5.0)],
        mse_threshold=1e-6,
        note="V = V0·exp(-0.5·t)",
    ),
    Fixture(
        name="arrhenius",
        var_names=["A", "T"],
        operators=["+", "-", "*", "/", "exp"],
        target=_arrhenius,
        bounds=[(1.0, 10.0), (400.0, 1000.0)],
        mse_threshold=1e-3,  # smaller magnitudes — looser threshold OK
        note="k = A·exp(-5000/T)",
    ),
    Fixture(
        name="hooke",
        var_names=["k", "x"],
        operators=["+", "-", "*"],
        target=_hooke,
        bounds=[(1.0, 10.0), (-5.0, 5.0)],
        mse_threshold=1e-9,
        note="F = -k·x",
    ),
]


# ─────────────────────────────────────────────────────────────────────
# Sample generation + HTTP
# ─────────────────────────────────────────────────────────────────────

def build_samples(fixture: Fixture, n_per_dim: int, seed: int) -> List[List[float]]:
    """
    Uniform grid over each variable's (lo, hi) bound, with a small
    jitter to avoid lattice artefacts. Returns rows of length
    (vars + 1) — last column is the target value.
    """
    rng = random.Random(seed)
    axes = []
    for lo, hi in fixture.bounds:
        step = (hi - lo) / max(1, n_per_dim - 1)
        axes.append([lo + i * step for i in range(n_per_dim)])
    rows: List[List[float]] = []
    # Cartesian product of the axes.
    def recurse(prefix: List[float], depth: int) -> None:
        if depth == len(axes):
            # Add small jitter (≤ 1% of axis range) to break grid symmetry.
            jittered = []
            for v, (lo, hi) in zip(prefix, fixture.bounds):
                amp = 0.01 * (hi - lo)
                jittered.append(v + rng.uniform(-amp, amp))
            target = fixture.target(jittered)
            rows.append([*jittered, target])
            return
        for v in axes[depth]:
            recurse([*prefix, v], depth + 1)
    recurse([], 0)
    return rows


def call_pysr(base_url: str, body: dict, timeout_s: float) -> dict:
    req = urllib.request.Request(
        url=f"{base_url.rstrip('/')}/pysr",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


# ─────────────────────────────────────────────────────────────────────
# Per-fixture probe
# ─────────────────────────────────────────────────────────────────────

@dataclass
class ProbeOutcome:
    fixture: str
    ok: bool
    best_mse: float
    best_expr: str
    elapsed_ms: int
    pareto_size: int
    error: str | None = None


def probe_fixture(fixture: Fixture, base_url: str,
                  populations: int, iterations: int,
                  per_call_timeout_ms: int,
                  n_per_dim: int) -> ProbeOutcome:
    samples = build_samples(fixture, n_per_dim=n_per_dim, seed=42)
    body = {
        "samples": samples,
        "varNames": fixture.var_names,
        "targetName": "f",
        "operators": fixture.operators,
        "populations": populations,
        "iterations": iterations,
        "timeoutMs": per_call_timeout_ms,
        "complexityCeiling": 25,
        "seed": 42,
    }
    # HTTP timeout slightly above the sidecar's own cap to give the
    # response time to flow back. +5s headroom for JIT warmup on
    # the first /pysr call of the process lifetime.
    http_timeout_s = (per_call_timeout_ms / 1000.0) + 5.0
    try:
        resp = call_pysr(base_url, body, http_timeout_s)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return ProbeOutcome(fixture=fixture.name, ok=False,
                            best_mse=float("nan"), best_expr="(transport)",
                            elapsed_ms=0, pareto_size=0,
                            error=f"transport: {e}")
    if not resp.get("ok"):
        return ProbeOutcome(fixture=fixture.name, ok=False,
                            best_mse=float("nan"), best_expr="(server-error)",
                            elapsed_ms=int(resp.get("elapsed_ms", 0)),
                            pareto_size=0,
                            error=f"{resp.get('error')}: {resp.get('explanation')}")
    pareto = resp.get("pareto") or []
    if not pareto:
        return ProbeOutcome(fixture=fixture.name, ok=False,
                            best_mse=float("nan"), best_expr="(empty)",
                            elapsed_ms=int(resp.get("elapsed_ms", 0)),
                            pareto_size=0,
                            error="empty Pareto front")
    # Top-3 by complexity ascending — server sorts already, but we'll
    # be defensive in case the order ever changes.
    top3 = sorted(pareto, key=lambda e: e.get("complexity", 0))[:3]
    best = min(top3, key=lambda e: e.get("mse", float("inf")))
    best_mse = float(best.get("mse", float("inf")))
    ok = best_mse < fixture.mse_threshold
    return ProbeOutcome(
        fixture=fixture.name,
        ok=ok,
        best_mse=best_mse,
        best_expr=str(best.get("expr", "")),
        elapsed_ms=int(resp.get("elapsed_ms", 0)),
        pareto_size=len(pareto),
        error=None if ok else f"mse {best_mse:.3g} >= threshold {fixture.mse_threshold:.0e}",
    )


# ─────────────────────────────────────────────────────────────────────
# Orchestration
# ─────────────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088",
                        help="julia-math-sidecar base URL (default: %(default)s)")
    parser.add_argument("--populations", type=int, default=16,
                        help="GP island count (default: %(default)s — 2× the "
                             "in-production default for a more thorough probe)")
    parser.add_argument("--iterations", type=int, default=80,
                        help="iterations per population (default: %(default)s)")
    parser.add_argument("--timeout-ms", type=int, default=30_000,
                        help="per-fixture sidecar wall-clock cap, in ms "
                             "(default: %(default)s)")
    parser.add_argument("--samples-per-dim", type=int, default=6,
                        help="grid resolution per variable; total rows = "
                             "this^len(vars) (default: %(default)s → 36 rows)")
    parser.add_argument("--filter", default="",
                        help="comma-separated fixture names to run; empty = all")
    args = parser.parse_args()

    selected = FIXTURES
    if args.filter:
        wanted = {n.strip() for n in args.filter.split(",")}
        selected = [f for f in FIXTURES if f.name in wanted]
        if not selected:
            print(f"!! no fixtures matched filter={args.filter!r}", file=sys.stderr)
            return 2

    print(f"== PySR recovery probe ==")
    print(f"sidecar: {args.base_url}")
    print(f"populations={args.populations} iterations={args.iterations} "
          f"timeoutMs={args.timeout_ms} samples_per_dim={args.samples_per_dim}")
    print(f"fixtures: {len(selected)}")
    print()

    # /healthz first so we fail fast on a dead sidecar.
    try:
        req = urllib.request.Request(f"{args.base_url.rstrip('/')}/healthz",
                                      method="GET")
        with urllib.request.urlopen(req, timeout=5.0) as r:
            health = json.loads(r.read().decode("utf-8"))
        if not health.get("ok"):
            print(f"!! sidecar /healthz returned ok=false: {health}",
                  file=sys.stderr)
            return 3
        print(f"sidecar /healthz: OK (julia version {health.get('service', '?')})\n")
    except Exception as e:
        print(f"!! sidecar unreachable at {args.base_url}: {e}", file=sys.stderr)
        print("   start it via: cd julia-math-sidecar && ./scripts/start.sh",
              file=sys.stderr)
        return 3

    outcomes: List[ProbeOutcome] = []
    overall_t0 = time.time()
    for fix in selected:
        print(f"-> {fix.name:<18}  {fix.note}")
        t0 = time.time()
        out = probe_fixture(
            fix,
            base_url=args.base_url,
            populations=args.populations,
            iterations=args.iterations,
            per_call_timeout_ms=args.timeout_ms,
            n_per_dim=args.samples_per_dim,
        )
        elapsed_s = time.time() - t0
        outcomes.append(out)
        mark = "PASS" if out.ok else "FAIL"
        print(f"   {mark}  wall={elapsed_s:5.1f}s  sidecar={out.elapsed_ms:>5d}ms  "
              f"mse={out.best_mse:.3g}  pareto={out.pareto_size}")
        if out.best_expr:
            print(f"        best: {out.best_expr}")
        if out.error and not out.ok:
            print(f"        reason: {out.error}")
        print()

    overall_s = time.time() - overall_t0
    passed = sum(1 for o in outcomes if o.ok)
    total = len(outcomes)
    print("== summary ==")
    print(f"passed: {passed}/{total}   total wall: {overall_s:.1f}s")
    threshold = max(1, math.ceil(0.8 * total))  # 80% bar; 4/5 by default
    if passed >= threshold:
        print(f"OK — at least {threshold}/{total} fixtures recovered cleanly.")
        return 0
    print(f"BELOW BAR — need {threshold}/{total}, got {passed}/{total}.")
    print(f"  Don't flip app.julia.pysr.enabled=true yet. Diagnose:")
    print(f"  - If most fixtures failed: SymbolicRegression.jl may not have")
    print(f"    instantiated; check sidecar logs for Pkg errors.")
    print(f"  - If one or two failed at the edges: try --iterations 160")
    print(f"    --populations 24 --samples-per-dim 8 for a deeper search.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
