"""Math-domain scalar vocabulary for Phase 23.

Each sample is one window evaluation. The shared engine
(scripts/graffiti/conjectures.py) calls `vocabulary()` to enumerate
conjecture-eligible names; when this module precedes graffiti/ on sys.path,
the engine speaks math instead of government-trend.

Window metadata (_window_start, _window_size, _tick) is carried through
the sample dict but kept OUT of the vocabulary -- it's available to
DERIVED lambdas (so e.g. log_window_size becomes a real scalar) but
isn't itself a conjecture term.
"""

from __future__ import annotations

import math


BASE: list[str] = [
    "abundant_density",
    "deficient_density",
    "prime_density",
    "happy_density",
    "mean_omega",
    "mean_Omega",
    "mean_divisor_count",
    "max_divisor_count",
    "mertens_delta",
]


# Each DERIVED lambda reads from the flat scalar dict and may also read
# _window_start / _window_size which are kept alongside the named scalars.
DERIVED: dict[str, "callable"] = {
    "abundant_minus_deficient": lambda r: r["abundant_density"] - r["deficient_density"],
    "omega_Omega_gap":          lambda r: r["mean_Omega"] - r["mean_omega"],
    "log_window_size":          lambda r: math.log10(r["_window_size"]) if r["_window_size"] > 0 else 0.0,
    "sqrt_window_size":         lambda r: math.sqrt(r["_window_size"]),
    "log_window_start":         lambda r: math.log10(r["_window_start"]) if r["_window_start"] > 0 else 0.0,
    # Natural Mertens-conjecture scaling: |M(N)| / sqrt(N) should stay O(1)
    # in observed regimes even though Mertens' actual conjecture is false
    # asymptotically. Putting this in the vocabulary gives the engine a
    # chance to surface conjectures about that ratio.
    "mertens_per_sqrt_W":       lambda r: r["mertens_delta"] / math.sqrt(r["_window_size"]) if r["_window_size"] > 0 else 0.0,
}


def extract(point: dict) -> dict[str, float]:
    """Flatten one window-evaluation dict into the named-scalar vocabulary."""
    flat: dict[str, float] = {}
    # metadata first so DERIVED lambdas can read it
    flat["_window_size"]  = float(point.get("_window_size", 0))
    flat["_window_start"] = float(point.get("_window_start", 0))
    flat["_tick"]         = int(point.get("_window_start", 0))

    for name in BASE:
        v = point.get(name, 0)
        flat[name] = float(v) if v is not None else 0.0

    for name, fn in DERIVED.items():
        try:
            flat[name] = float(fn(flat))
        except (KeyError, TypeError, ValueError):
            flat[name] = 0.0
    return flat


def vocabulary() -> list[str]:
    """All names available to conjectures, in deterministic order."""
    return list(BASE) + list(DERIVED.keys())
