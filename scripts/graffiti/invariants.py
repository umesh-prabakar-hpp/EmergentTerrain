"""Named-scalar vocabulary for Graffiti conjectures.

Keep BASE small and curated. Tautological combos (budget shares that sum
to 1 by construction, etc.) must NEVER appear here -- they will be
"discovered" as zero-slack identities and crowd out real conjectures.
"""

from __future__ import annotations

from typing import Callable

# Per-tick scalars derived directly from GovernmentTrendPoint fields.
# Names are the conjecture-facing labels; the values are the trend keys.
BASE: dict[str, str] = {
    "trust":                 "federalTrust",
    "service":               "federalServiceAccess",
    "state_trust":           "avgStateTrust",
    "state_service":         "avgStateServiceAccess",
    "pressure":              "pressureIntensity",
    "implementation":        "implementationScore",
    "petitioning_share":     "petitioningShare",
    "sanctioned_share":      "sanctionedShare",
    "active_bills":          "activeBillCount",
    "active_orders":         "activeExecutiveOrderCount",
    "active_courts":         "activeCourtCaseCount",
    "active_admin":          "activeAdministrativeCount",
    "active_conflicts":      "activeConflictCount",
    "backfires":             "implementationBackfireCount",
}

# Composite scalars. Each lambda gets the *flattened* dict produced by
# `extract`, so it can reference any BASE name.
DERIVED: dict[str, Callable[[dict], float]] = {
    "trust_service":     lambda r: r["trust"] * r["service"],
    "load_total":        lambda r: r["active_admin"] + r["active_conflicts"],
    "civic_pressure":    lambda r: r["petitioning_share"] + r["sanctioned_share"],
    "fed_state_trust_gap": lambda r: r["trust"] - r["state_trust"],
}


def extract(point: dict) -> dict[str, float]:
    """Flatten one GovernmentTrendPoint into the named-scalar vocabulary.

    Missing fields are coerced to 0.0 so a partially-populated dump
    doesn't crash the whole run.
    """
    flat: dict[str, float] = {}
    for name, src in BASE.items():
        v = point.get(src, 0)
        flat[name] = float(v) if v is not None else 0.0
    for name, fn in DERIVED.items():
        try:
            flat[name] = float(fn(flat))
        except (KeyError, TypeError):
            flat[name] = 0.0
    return flat


def vocabulary() -> list[str]:
    """All names available to conjectures, in deterministic order."""
    return list(BASE.keys()) + list(DERIVED.keys())
