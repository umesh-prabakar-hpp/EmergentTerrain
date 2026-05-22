#!/usr/bin/env python3
"""
phase-26 §26.6 — Recognition coverage probe.

Feeds 30 known constants to /recognise/constant (PSLQ via mpmath)
and 30 known integer sequences to /recognise/sequence (OEIS
Superseeker), reports per-fixture pass/fail, and confirms the
§26.6 acceptance bars:

    constants:  ≥25/30 recognise under BASIC + PHYSICS basis sets
    sequences:  ≥27/30 recognise via OEIS

The bars allow ~17% miss rate per stream — deliberately-novel
inputs in the fixture set should miss, which sanity-checks that
the engines aren't producing false positives.

OEIS rate-limit: OEIS TOS asks for ≤1 req/sec. The probe sleeps
1.0s between /recognise/sequence calls so a 30-fixture run takes
~35s wall-clock without tripping any rate limit. The JVM-side
cache (phase 26.1) will absorb subsequent runs.

Usage:
    ./run.sh
or:
    python3 probe.py --base-url http://localhost:8088 --timeout-ms 15000

Pure stdlib — no requests/pip install. Layout mirrors
scripts/pysr-probe/ and scripts/sympy-prover-probe/.
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
# Constant fixtures
# ─────────────────────────────────────────────────────────────────────
#
# Decimals generated via mpmath at ≥90 digits so PSLQ has enough
# precision to confirm relations. Each fixture names the expected
# recognised name (substring match against the sidecar's rendered
# `name`) plus the basis to use. Negative fixtures use `should_miss`
# to invert the pass criterion.


@dataclass
class ConstantFixture:
    name: str               # human-readable label for the probe row
    value: str              # decimal string fed to /recognise/constant
    basis: str              # "BASIC" or "PHYSICS"
    expect_name: Optional[str] = None  # substring expected in returned name
    should_miss: bool = False  # set True for fixtures we WANT no_match


# All values below are mpmath-generated at ≥99 digits — typing them by
# hand or copying from web sources loses precision around digit 60, which
# causes PSLQ to reject legitimate relations because the input's noise
# floor is higher than the tol heuristic assumes. Regenerate by running
# `julia --project=. -e 'using PyCall; mp = pyimport("mpmath"); ...'`
# against the canonical mpmath constants.
CONSTANT_FIXTURES: List[ConstantFixture] = [
    # 1-8: each named constant in BASIC, identity match
    ConstantFixture("pi",
        "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211707",
        "BASIC", expect_name="pi"),
    ConstantFixture("e",
        "2.71828182845904523536028747135266249775724709369995957496696762772407663035354759457138217852516643",
        "BASIC", expect_name="e"),
    ConstantFixture("ln(2)",
        "0.693147180559945309417232121458176568075500134360255254120680009493393621969694715605863326996418688",
        "BASIC", expect_name="ln(2)"),
    ConstantFixture("ln(3)",
        "1.09861228866810969139524523692252570464749055782274945173469433363749429321860896687361575481373209",
        "BASIC", expect_name="ln(3)"),
    ConstantFixture("gamma",
        "0.57721566490153286060651209008240243104215933593992359880576723488486772677766467093694706329174675",
        "BASIC", expect_name="gamma"),
    ConstantFixture("zeta(3)",
        "1.20205690315959428539973816151144999076498629234049888179227155534183820578631309018645587360933526",
        "BASIC", expect_name="zeta(3)"),
    ConstantFixture("pi^2",
        "9.86960440108935861883449099987615113531369940724079062641334937622004482241920524300177340371855223",
        "BASIC", expect_name="pi^2"),
    ConstantFixture("-pi",
        "-3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211707",
        "BASIC", expect_name="pi"),

    # 9-12: PHYSICS-basis-only constants
    ConstantFixture("zeta(2) = pi²/6",
        "1.64493406684822643647241516664602518921894990120679843773555822937000747040320087383362890061975871",
        "PHYSICS", expect_name="zeta(2)"),
    ConstantFixture("zeta(5)",
        "1.03692775514336992633136548645703416805708091950191281197419267790380358978628148456004310655713334",
        "PHYSICS", expect_name="zeta(5)"),
    ConstantFixture("Catalan",
        "0.915965594177219015054603514932384110774149374281672134266498119621763019776254769479356512926115106",
        "PHYSICS", expect_name="catalan"),
    ConstantFixture("phi (golden ratio)",
        "1.61803398874989484820458683436563811772030917980576286213544862270526046281890244970720720418939114",
        "PHYSICS", expect_name="phi"),

    # 13-17: integer multiples of basis constants
    ConstantFixture("2*pi",
        "6.28318530717958647692528676655900576839433879875021164194988918461563281257241799725606965068423414",
        "BASIC", expect_name="pi"),
    ConstantFixture("3*e",
        "8.15484548537713570608086241405798749327174128109987872490090288317222989106064278371414653557549928",
        "BASIC", expect_name="e"),
    ConstantFixture("2*gamma",
        "1.1544313298030657212130241801648048620843186718798471976115344697697354535553293418738941265834935",
        "BASIC", expect_name="gamma"),
    ConstantFixture("-zeta(3)",
        "-1.20205690315959428539973816151144999076498629234049888179227155534183820578631309018645587360933526",
        "BASIC", expect_name="zeta(3)"),
    ConstantFixture("5*ln(2)",
        "3.46573590279972654708616060729088284037750067180127627060340004746696810984847357802931663498209344",
        "BASIC", expect_name="ln(2)"),

    # 18-22: simple integer linear combinations
    ConstantFixture("pi + e",
        "5.8598744820488384738229308546321653819544164930750653959419122200318930366397565931994170038672835",
        "BASIC", expect_name="pi"),  # rendered name will mention both
    ConstantFixture("e - pi",
        "-0.423310825130748003102355911926840386439922305675146246007976964583739775932661404056652646816950641",
        "BASIC", expect_name="pi"),
    ConstantFixture("ln(2) + ln(3)",
        "1.79175946922805500081247735838070227272299069218300470585537434313088791518830368247947908181015078",
        "BASIC", expect_name="ln(2)"),
    ConstantFixture("gamma + ln(2)",
        "1.27036284546147817002374421154057899911765947030017885292644724437826134874735938654281039028816544",
        "BASIC", expect_name="gamma"),
    ConstantFixture("zeta(2) + zeta(3)",
        "2.84699097000782072187215332815747517998393619354729731952782978471184567618951396402008477422909396",
        "PHYSICS", expect_name="zeta"),

    # 23-25: larger-coefficient sums (still under MAX_PSLQ_COEFF)
    ConstantFixture("2*pi + 3*e",
        "14.4380307925567221830061491806169932616660800798500903668507920677878627036330607809702161862597334",
        "BASIC", expect_name="pi"),
    ConstantFixture("phi + ln(2)",
        "2.31118116930984015762181895582381468579580931416601811625612863219865408478859716531307053118580983",
        "PHYSICS", expect_name="phi"),
    ConstantFixture("Catalan + gamma",
        "1.49318125907875187566111560501478654181630871022159573307226535450663074655391944041630357621786186",
        "PHYSICS", expect_name="catalan"),

    # 26-30: deliberately should-miss cases — these are NOT in any basis,
    # PSLQ correctly returns no_match. Confirms the engine isn't
    # hallucinating relations on novel constants.
    ConstantFixture("1/pi (NOT in basis)",
        "0.318309886183790671537767526745028724068919291480912897495334688117793595268453070180227605532506172",
        "BASIC", should_miss=True),
    ConstantFixture("Khinchin's constant (NOT in basis)",
        "2.6854520010653064453097148354817956938203822939944629530511523455572188595371520028011411749318477",
        "PHYSICS", should_miss=True),
    ConstantFixture("Mills' constant approx (NOT in basis)",
        "1.3063778838630806904686144926026057129167845851567136443680537599664340537668",
        "PHYSICS", should_miss=True),
    ConstantFixture("random digits 0.789... (NOT recognisable)",
        "0.78901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234",
        "BASIC", should_miss=True),
    ConstantFixture("random digits 0.456... (NOT recognisable)",
        "0.45678912345678912345678912345678912345678912345678912345678912345678912345678912345678912345678912",
        "PHYSICS", should_miss=True),
]


# ─────────────────────────────────────────────────────────────────────
# Sequence fixtures
# ─────────────────────────────────────────────────────────────────────


@dataclass
class SequenceFixture:
    name: str
    sequence: List[int]
    expect_oeis_id: Optional[str] = None  # e.g. "A000045"
    should_miss: bool = False


SEQUENCE_FIXTURES: List[SequenceFixture] = [
    # 1-15: classical OEIS A-numbers
    SequenceFixture("Fibonacci", [1, 1, 2, 3, 5, 8, 13, 21, 34, 55], "A000045"),
    SequenceFixture("Primes", [2, 3, 5, 7, 11, 13, 17, 19, 23, 29], "A000040"),
    SequenceFixture("Powers of 2", [1, 2, 4, 8, 16, 32, 64, 128, 256, 512], "A000079"),
    SequenceFixture("Powers of 3", [1, 3, 9, 27, 81, 243, 729, 2187], "A000244"),
    SequenceFixture("Triangular numbers", [1, 3, 6, 10, 15, 21, 28, 36, 45, 55], "A000217"),
    SequenceFixture("Squares", [1, 4, 9, 16, 25, 36, 49, 64, 81, 100], "A000290"),
    SequenceFixture("Cubes", [1, 8, 27, 64, 125, 216, 343, 512, 729], "A000578"),
    SequenceFixture("Catalan numbers", [1, 1, 2, 5, 14, 42, 132, 429, 1430], "A000108"),
    SequenceFixture("Factorial", [1, 1, 2, 6, 24, 120, 720, 5040, 40320], "A000142"),
    SequenceFixture("Lucas numbers", [2, 1, 3, 4, 7, 11, 18, 29, 47, 76], "A000032"),
    SequenceFixture("All ones", [1, 1, 1, 1, 1, 1, 1], "A000012"),
    SequenceFixture("Natural numbers", [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], "A000027"),
    SequenceFixture("Bell numbers", [1, 1, 2, 5, 15, 52, 203, 877, 4140], "A000110"),
    SequenceFixture("Partition numbers", [1, 1, 2, 3, 5, 7, 11, 15, 22, 30], "A000041"),
    SequenceFixture("Tetrahedral numbers", [1, 4, 10, 20, 35, 56, 84, 120], "A000292"),

    # 16-22: more OEIS classics
    SequenceFixture("Pentagonal numbers", [1, 5, 12, 22, 35, 51, 70, 92, 117], "A000326"),
    SequenceFixture("Hexagonal numbers", [1, 6, 15, 28, 45, 66, 91, 120, 153], "A000384"),
    SequenceFixture("Pell numbers", [0, 1, 2, 5, 12, 29, 70, 169, 408], "A000129"),
    SequenceFixture("Tribonacci", [0, 0, 1, 1, 2, 4, 7, 13, 24, 44, 81], "A000073"),
    SequenceFixture("Derangements", [1, 0, 1, 2, 9, 44, 265, 1854, 14833], "A000166"),
    SequenceFixture("Motzkin numbers", [1, 1, 2, 4, 9, 21, 51, 127, 323], "A001006"),
    SequenceFixture("Fubini numbers", [1, 1, 3, 13, 75, 541, 4683], "A000670"),

    # 23-27: less famous but still in OEIS
    SequenceFixture("Centered hexagonal", [1, 7, 19, 37, 61, 91, 127, 169], "A003215"),
    SequenceFixture("Padovan sequence", [1, 1, 1, 2, 2, 3, 4, 5, 7, 9, 12, 16], "A000931"),
    SequenceFixture("Schröder numbers", [1, 2, 6, 22, 90, 394, 1806], "A006318"),
    SequenceFixture("Heptagonal numbers", [1, 7, 18, 34, 55, 81, 112, 148], "A000566"),
    SequenceFixture("Squares-of-Fib", [1, 1, 4, 9, 25, 64, 169, 441], "A007598"),

    # 28-30: deliberately should-miss — random integer streams without
    # an OEIS canonical hit. Either truly random or arithmetic
    # progressions with weird offsets.
    SequenceFixture("Random 8-digits stream",
        [98765, 87654, 76543, 65432, 54321], should_miss=True),
    SequenceFixture("Random sparse stream",
        [11, 23, 47, 89, 173, 339, 661], should_miss=True),
    SequenceFixture("Random ascending stream",
        [3, 7, 13, 29, 61, 137, 311], should_miss=True),
]


# ─────────────────────────────────────────────────────────────────────
# HTTP helpers
# ─────────────────────────────────────────────────────────────────────


def call_endpoint(base_url: str, path: str, body: dict, timeout_s: float) -> dict:
    req = urllib.request.Request(
        url=f"{base_url.rstrip('/')}{path}",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


# ─────────────────────────────────────────────────────────────────────
# Constant probe
# ─────────────────────────────────────────────────────────────────────


@dataclass
class FixtureOutcome:
    name: str
    ok: bool
    detail: str   # short human-readable summary
    error: Optional[str] = None


def probe_constant(fixture: ConstantFixture, base_url: str,
                    timeout_ms: int) -> FixtureOutcome:
    body = {
        "value": fixture.value,
        "basis": fixture.basis,
        "precisionDigits": 200,
        "timeoutMs": timeout_ms,
    }
    http_timeout_s = (timeout_ms / 1000.0) + 15.0
    try:
        resp = call_endpoint(base_url, "/recognise/constant", body, http_timeout_s)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return FixtureOutcome(fixture.name, False, "(transport)",
                              error=f"transport: {e}")
    if not resp.get("ok"):
        return FixtureOutcome(fixture.name, False, "(server-error)",
                              error=f"{resp.get('error')}: {resp.get('explanation')}")

    recognized = resp.get("recognized", False)
    if fixture.should_miss:
        if not recognized:
            return FixtureOutcome(fixture.name, True, "no_match (expected)")
        return FixtureOutcome(fixture.name, False,
                              f"falsely recognised as: {resp.get('name')}",
                              error="false-positive on should-miss fixture")

    if not recognized:
        return FixtureOutcome(fixture.name, False, "no_match",
                              error="expected a recognition; got no_match")

    name = resp.get("name", "")
    if fixture.expect_name and fixture.expect_name not in name:
        return FixtureOutcome(fixture.name, False, f"recognised={name}",
                              error=f"expected '{fixture.expect_name}' in name")
    return FixtureOutcome(fixture.name, True, f"recognised={name}")


def probe_sequence(fixture: SequenceFixture, base_url: str,
                    timeout_ms: int) -> FixtureOutcome:
    body = {
        "sequence": fixture.sequence,
        "timeoutMs": timeout_ms,
    }
    http_timeout_s = (timeout_ms / 1000.0) + 15.0
    try:
        resp = call_endpoint(base_url, "/recognise/sequence", body, http_timeout_s)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return FixtureOutcome(fixture.name, False, "(transport)",
                              error=f"transport: {e}")
    if not resp.get("ok"):
        return FixtureOutcome(fixture.name, False, "(server-error)",
                              error=f"{resp.get('error')}: {resp.get('explanation')}")

    recognized = resp.get("recognized", False)
    if fixture.should_miss:
        if not recognized:
            return FixtureOutcome(fixture.name, True, "no_match (expected)")
        return FixtureOutcome(fixture.name, False,
                              f"falsely recognised as: {resp.get('oeis_id')}",
                              error="false-positive on should-miss fixture")

    if not recognized:
        return FixtureOutcome(fixture.name, False, "no_match",
                              error="expected an OEIS hit; got no_match")

    oeis_id = resp.get("oeis_id", "")
    if fixture.expect_oeis_id and oeis_id != fixture.expect_oeis_id:
        return FixtureOutcome(fixture.name, False, f"recognised={oeis_id}",
                              error=f"expected {fixture.expect_oeis_id}")
    return FixtureOutcome(fixture.name, True, f"recognised={oeis_id}")


# ─────────────────────────────────────────────────────────────────────
# Orchestration
# ─────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8088",
                        help="julia-math-sidecar base URL (default: %(default)s)")
    parser.add_argument("--timeout-ms", type=int, default=15_000,
                        help="per-fixture sidecar wall-clock cap, in ms")
    parser.add_argument("--oeis-rate-limit-s", type=float, default=1.0,
                        help="sleep this long between sequence calls; OEIS TOS asks for ≤1 req/sec")
    parser.add_argument("--skip-sequences", action="store_true",
                        help="run only the constant fixtures (faster; avoids OEIS rate-limit)")
    parser.add_argument("--skip-constants", action="store_true",
                        help="run only the sequence fixtures")
    args = parser.parse_args()

    print("== Recognition coverage probe (phase-26 §26.6) ==")
    print(f"sidecar: {args.base_url}")
    print(f"timeoutMs={args.timeout_ms} oeis_rate_limit={args.oeis_rate_limit_s}s")
    print()

    # /healthz first — fail fast on dead sidecar.
    try:
        req = urllib.request.Request(f"{args.base_url.rstrip('/')}/healthz",
                                      method="GET")
        with urllib.request.urlopen(req, timeout=5.0) as r:
            health = json.loads(r.read().decode("utf-8"))
        if not health.get("ok"):
            print(f"!! sidecar /healthz returned ok=false: {health}", file=sys.stderr)
            return 3
        print(f"sidecar /healthz: OK (julia version {health.get('service', '?')})\n")
    except Exception as e:
        print(f"!! sidecar unreachable at {args.base_url}: {e}", file=sys.stderr)
        return 3

    constant_outcomes: List[FixtureOutcome] = []
    sequence_outcomes: List[FixtureOutcome] = []

    # Constants first — fast (no rate limit).
    if not args.skip_constants:
        print(f"== Constants ({len(CONSTANT_FIXTURES)} fixtures) ==")
        for fix in CONSTANT_FIXTURES:
            t0 = time.time()
            out = probe_constant(fix, args.base_url, args.timeout_ms)
            elapsed = time.time() - t0
            mark = "PASS" if out.ok else "FAIL"
            print(f"  {mark}  {fix.name:<40}  {elapsed:5.1f}s  {out.detail}")
            if out.error and not out.ok:
                print(f"        reason: {out.error}")
            constant_outcomes.append(out)
        print()

    # Sequences second — paced at 1 req/sec per OEIS TOS.
    if not args.skip_sequences:
        print(f"== Sequences ({len(SEQUENCE_FIXTURES)} fixtures, "
              f"{args.oeis_rate_limit_s}s rate-limit between calls) ==")
        for i, fix in enumerate(SEQUENCE_FIXTURES):
            if i > 0:
                time.sleep(args.oeis_rate_limit_s)
            t0 = time.time()
            out = probe_sequence(fix, args.base_url, args.timeout_ms)
            elapsed = time.time() - t0
            mark = "PASS" if out.ok else "FAIL"
            print(f"  {mark}  {fix.name:<40}  {elapsed:5.1f}s  {out.detail}")
            if out.error and not out.ok:
                print(f"        reason: {out.error}")
            sequence_outcomes.append(out)
        print()

    # Summary + acceptance bars.
    print("== summary ==")
    c_pass = sum(1 for o in constant_outcomes if o.ok)
    c_total = len(constant_outcomes)
    s_pass = sum(1 for o in sequence_outcomes if o.ok)
    s_total = len(sequence_outcomes)

    c_bar = math.ceil(0.83 * c_total) if c_total else 0   # 25/30 ≈ 83%
    s_bar = math.ceil(0.9  * s_total) if s_total else 0   # 27/30 ≈ 90%

    if c_total:
        print(f"  constants: {c_pass}/{c_total}  (bar ≥{c_bar})")
    if s_total:
        print(f"  sequences: {s_pass}/{s_total}  (bar ≥{s_bar})")

    c_ok = c_total == 0 or c_pass >= c_bar
    s_ok = s_total == 0 or s_pass >= s_bar

    if c_ok and s_ok:
        print("\nOK — both acceptance bars cleared. Safe to flip "
              "app.julia.recognise.enabled=true.")
        return 0
    print("\nBELOW BAR — don't flip app.julia.recognise.enabled=true yet.")
    if not c_ok:
        print(f"  Constants {c_pass}/{c_total} < {c_bar}. Likely cause: PSLQ basis "
              f"missing a constant the fixture set claims, OR mpmath not "
              f"installed under PyCall (verify with `python3 -c 'import mpmath'`).")
    if not s_ok:
        print(f"  Sequences {s_pass}/{s_total} < {s_bar}. Likely cause: OEIS "
              f"rate-limited the probe (try --oeis-rate-limit-s 2.0), OR the "
              f"sidecar's outbound HTTP is blocked.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
