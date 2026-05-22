#!/usr/bin/env python3
"""
phase-25 §25.5 — SymPy library-coverage probe.

Feeds ten classical hypergeometric / combinatorial identities (drawn from
Petkovsek–Wilf–Zeilberger's *A=B* and standard summation textbooks) to the
julia-math-sidecar /prove_identity endpoint and reports whether each one
reaches `status="proved"`. Run once before flipping
`app.julia.sympy.enabled=true` in production.

Acceptance bar: ≥8 of 10 fixtures recover. Below that, either SymPy isn't
correctly installed under PyCall's Python (rare given dnf install
python3-sympy), or one of the dispatchers (gosper / zeilberger_wz)
regressed. Don't flip the flag with <8/10 passing.

Usage:
    ./run.sh
or:
    python3 probe.py --base-url http://localhost:8088 --timeout-ms 30000

Pure Python stdlib — no `requests` / `pip install`. Mirrors the layout of
`scripts/pysr-probe/probe.py`.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import List, Optional


# ─────────────────────────────────────────────────────────────────────
# Fixture definitions — each names a known identity, the inputs to send
# to /prove_identity, and a list of acceptable result strings.
#
# SymPy can render the same closed form in multiple ways (the canonical
# example is binomial(2n, n) vs 4^n * Γ(n+½)/(√π · Γ(n+1)) — equal but
# textually different). `accepted_results` is a list of substrings; if
# any is contained in the returned `result` string we treat it as
# correct. Use `None` for no validation (status=proved is enough).
# ─────────────────────────────────────────────────────────────────────


@dataclass
class Fixture:
    name: str
    algorithm: str
    summand: str
    var: str
    lower: str
    upper: str
    # Substring match against the returned `result` string. If any
    # element matches, the fixture passes. Empty list → no match check.
    accepted_results: List[str] = field(default_factory=list)
    note: str = ""


FIXTURES: List[Fixture] = [
    # 1. Σ k from 0 to n = n(n+1)/2 — schoolbook arithmetic series.
    Fixture(
        name="sum-k",
        algorithm="gosper",
        summand="k", var="k", lower="0", upper="n",
        accepted_results=["n*(n + 1)/2", "n*(n+1)/2"],
        note="Σ k = n(n+1)/2",
    ),
    # 2. Σ k² from 0 to n = n(n+1)(2n+1)/6 — Faulhaber, p=2.
    Fixture(
        name="sum-k-squared",
        algorithm="auto",
        summand="k**2", var="k", lower="0", upper="n",
        accepted_results=["n*(n + 1)*(2*n + 1)/6", "n*(2*n + 1)*(n + 1)/6",
                          "(2*n + 1)*n*(n + 1)/6"],
        note="Σ k² = n(n+1)(2n+1)/6",
    ),
    # 3. Σ k³ from 0 to n = (n(n+1)/2)² — Faulhaber, p=3.
    Fixture(
        name="sum-k-cubed",
        algorithm="auto",
        summand="k**3", var="k", lower="0", upper="n",
        accepted_results=["n**2*(n + 1)**2/4", "(n + 1)**2*n**2/4"],
        note="Σ k³ = (n(n+1)/2)²",
    ),
    # 4. Σ binomial(n,k) from 0 to n = 2^n — binomial theorem.
    Fixture(
        name="binomial-theorem",
        algorithm="auto",
        summand="binomial(n, k)", var="k", lower="0", upper="n",
        accepted_results=["2**n"],
        note="Σ C(n,k) = 2^n",
    ),
    # 5. Σ k·binomial(n,k) from 0 to n = n·2^(n-1).
    Fixture(
        name="k-times-binomial",
        algorithm="auto",
        summand="k * binomial(n, k)", var="k", lower="0", upper="n",
        accepted_results=["2**(n - 1)*n", "n*2**(n - 1)"],
        note="Σ k·C(n,k) = n·2^(n-1)",
    ),
    # 6. Σ binomial(n,k)² from 0 to n = C(2n,n) — Vandermonde-like.
    # SymPy renders this as 4^n · Γ(n+½) / (√π · Γ(n+1)), which is the
    # gamma-function form of C(2n,n). Accept either form.
    Fixture(
        name="binomial-squared",
        algorithm="auto",
        summand="binomial(n, k)**2", var="k", lower="0", upper="n",
        accepted_results=["binomial(2*n, n)", "4**n", "gamma(n + 1/2)"],
        note="Σ C(n,k)² = C(2n,n)",
    ),
    # 7. Σ 1/(k·(k+1)) from 1 to n = n/(n+1) — classic telescoping.
    Fixture(
        name="telescoping-1-over-k-kplus1",
        algorithm="gosper",
        summand="1/(k*(k+1))", var="k", lower="1", upper="n",
        accepted_results=["n/(n + 1)", "n/(1 + n)"],
        note="Σ 1/(k(k+1)) = n/(n+1)",
    ),
    # 8. Σ r^k from 0 to n = (r^(n+1) - 1)/(r - 1) — geometric series.
    Fixture(
        name="geometric-series",
        algorithm="gosper",
        summand="r**k", var="k", lower="0", upper="n",
        accepted_results=["(r**(n + 1) - 1)/(r - 1)",
                          "r**(n + 1)/(r - 1) - 1/(r - 1)"],
        note="Σ r^k = (r^(n+1) - 1)/(r - 1)",
    ),
    # 9. Σ k·k! from 1 to n = (n+1)! - 1 — factorial-coefficient sum.
    Fixture(
        name="k-times-k-factorial",
        algorithm="auto",
        summand="k * factorial(k)", var="k", lower="1", upper="n",
        accepted_results=["factorial(n + 1) - 1",
                          "-1 + factorial(n + 1)"],
        note="Σ k·k! = (n+1)! - 1",
    ),
    # 10. Σ 1/(k·(k+1)·(k+2)) from 1 to n — telescoping triple.
    # Closed form: n(n+3) / (4·(n+1)·(n+2)). SymPy actually returns it
    # with the denominator expanded: `n*(n + 3)/(4*(n**2 + 3*n + 2))`
    # since n²+3n+2 = (n+1)(n+2). Accept both factored and expanded
    # denominators since they're algebraically identical.
    Fixture(
        name="telescoping-triple",
        algorithm="auto",
        summand="1/(k*(k+1)*(k+2))", var="k", lower="1", upper="n",
        accepted_results=["n*(n + 3)/(4*(n + 1)*(n + 2))",
                          "n*(n + 3)/(4*(n**2 + 3*n + 2))",
                          "(n*(n + 3))/(4*(n + 1)*(n + 2))"],
        note="Σ 1/(k(k+1)(k+2))",
    ),
]


# ─────────────────────────────────────────────────────────────────────
# HTTP
# ─────────────────────────────────────────────────────────────────────


def call_prove(base_url: str, body: dict, timeout_s: float) -> dict:
    req = urllib.request.Request(
        url=f"{base_url.rstrip('/')}/prove_identity",
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
    status: str               # "proved" | "no_closed_form" | "(transport)" | etc.
    result: Optional[str]
    certificate: Optional[str]
    algorithm_used: str
    elapsed_ms: int
    error: Optional[str] = None


def probe_fixture(fixture: Fixture, base_url: str,
                  per_call_timeout_ms: int) -> ProbeOutcome:
    body = {
        "algorithm": fixture.algorithm,
        "summand":   fixture.summand,
        "var":       fixture.var,
        "lower":     fixture.lower,
        "upper":     fixture.upper,
        "timeoutMs": per_call_timeout_ms,
    }
    http_timeout_s = (per_call_timeout_ms / 1000.0) + 15.0
    try:
        resp = call_prove(base_url, body, http_timeout_s)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return ProbeOutcome(
            fixture=fixture.name, ok=False,
            status="(transport)", result=None, certificate=None,
            algorithm_used="-", elapsed_ms=0,
            error=f"transport: {e}",
        )

    if not resp.get("ok"):
        return ProbeOutcome(
            fixture=fixture.name, ok=False,
            status="(server-error)", result=None, certificate=None,
            algorithm_used="-",
            elapsed_ms=int(resp.get("elapsed_ms", 0)),
            error=f"{resp.get('error')}: {resp.get('explanation')}",
        )

    status = resp.get("status", "")
    result = resp.get("result")
    cert = resp.get("certificate")
    algorithm_used = resp.get("algorithm_used", "-")
    elapsed_ms = int(resp.get("elapsed_ms", 0))

    if status != "proved":
        return ProbeOutcome(
            fixture=fixture.name, ok=False, status=status,
            result=result, certificate=cert,
            algorithm_used=algorithm_used, elapsed_ms=elapsed_ms,
            error=f"status={status}",
        )

    # Validate the result substring-matches one of the accepted forms.
    # Empty accepted_results = trust SymPy (proved is proved).
    matched = (not fixture.accepted_results) or any(
        expected in (result or "") for expected in fixture.accepted_results
    )
    if not matched:
        return ProbeOutcome(
            fixture=fixture.name, ok=False, status="proved-but-unexpected-form",
            result=result, certificate=cert,
            algorithm_used=algorithm_used, elapsed_ms=elapsed_ms,
            error=(f"result {result!r} matched none of "
                   f"{fixture.accepted_results}"),
        )
    return ProbeOutcome(
        fixture=fixture.name, ok=True, status="proved",
        result=result, certificate=cert,
        algorithm_used=algorithm_used, elapsed_ms=elapsed_ms,
    )


# ─────────────────────────────────────────────────────────────────────
# Orchestration
# ─────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088",
                        help="julia-math-sidecar base URL (default: %(default)s)")
    parser.add_argument("--timeout-ms", type=int, default=30_000,
                        help="per-fixture wall-clock cap, in ms "
                             "(default: %(default)s)")
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

    print("== SymPy prover coverage probe (phase-25 §25.5) ==")
    print(f"sidecar: {args.base_url}")
    print(f"timeoutMs={args.timeout_ms}")
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
        print(f"-> {fix.name:<35}  {fix.note}")
        t0 = time.time()
        out = probe_fixture(fix, base_url=args.base_url,
                            per_call_timeout_ms=args.timeout_ms)
        elapsed_s = time.time() - t0
        outcomes.append(out)
        mark = "PASS" if out.ok else "FAIL"
        result_snip = (out.result or "")[:60]
        print(f"   {mark}  wall={elapsed_s:5.1f}s  status={out.status} "
              f"  alg={out.algorithm_used}")
        if out.result:
            print(f"        result: {result_snip}")
        if out.certificate:
            cert_snip = (out.certificate or "")[:80]
            print(f"        cert:   {cert_snip}")
        if out.error and not out.ok:
            print(f"        reason: {out.error}")
        print()

    overall_s = time.time() - overall_t0
    passed = sum(1 for o in outcomes if o.ok)
    total = len(outcomes)
    print("== summary ==")
    print(f"passed: {passed}/{total}   total wall: {overall_s:.1f}s")
    threshold = max(1, math.ceil(0.8 * total))   # 80% bar; 8/10 by default
    if passed >= threshold:
        print(f"OK — at least {threshold}/{total} fixtures proved cleanly.")
        return 0
    print(f"BELOW BAR — need {threshold}/{total}, got {passed}/{total}.")
    print(f"  Don't flip app.julia.sympy.enabled=true yet. Diagnose:")
    print(f"  - If most fixtures fail with `transport`: sidecar not running or")
    print(f"    /prove_identity route not registered (sidecar commit predates db2b649).")
    print(f"  - If most fail with `eval_error`: SymPy missing under PyCall's")
    print(f"    Python — verify with `python3 -c 'import sympy'` on the host.")
    print(f"  - If one or two fail with `proved-but-unexpected-form`: SymPy's")
    print(f"    canonical changed; add the new form to the fixture's")
    print(f"    `accepted_results` and re-run.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
