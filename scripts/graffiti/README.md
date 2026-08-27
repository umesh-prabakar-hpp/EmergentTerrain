# Graffiti — emergent-law mining for ET

Mines candidate inequalities of the form `a ≤ k·b + c` between per-tick
scalars from `/room/{id}/government-trend`. Inequalities that survive many
runs across many `app.government.feedback.*` configurations are surfaced as
candidate "laws" of the simulation. Inequalities that any tick refutes are
dropped with a witness. Inequalities whose tightness moves monotonically
with a swept knob are dropped as knob-restatements.

Named after Fajtlowicz's 1980s `Graffiti` program, which used the same
seed/refute/tighten pattern on graph invariants.

This is purely a read-only consumer of ET — no Java changes, no schema
changes, no new endpoints. It just calls the existing trend endpoint and
the existing `scripts/{start,stop}.sh`.

## File layout

| File | Role |
|---|---|
| `load.py` | Loads runs: either pre-existing dump files (`load_dir`) or live POST to `/room/{id}/government-trend` (`fetch_trend`). |
| `invariants.py` | The named-scalar vocabulary (14 base + 4 derived). Curate this — tautological combos must never enter. |
| `conjectures.py` | `Inequality` dataclass, seed/update/promote/persist, novelty filter (\|corr\| > 0.98), knob-restatement filter (Spearman \|ρ\| ≥ 0.95). |
| `sweep.py` | JSON-spec sweep planner. Groups runs by config_hash so the knob filter has axes to test against. |
| `auto_sweep.py` | Restart-based automation: writes Spring `.properties` overrides, drives `scripts/{start,stop}.sh`, polls `/actuator/health`, soaks, dumps. Gated on `--yes`. |
| `run.py` | CLI. |

Outputs (store, dumps, override files) land under `apps/research-service/research/graffiti/`,
which is gitignored — generated state stays out of the repo.

## Three invocation modes

### Mode 1 — replay pre-existing dumps

You have JSON files on disk in the dump format (`{room_id, seed, config, points}`)
or bare `[{...}, {...}]` lists of points.

```bash
python3 scripts/graffiti/run.py \
    --runs-dir apps/research-service/research/graffiti/dumps \
    --reset
```

`--reset` discards any existing store before this batch. Omit to update the
store across multiple batches.

### Mode 2 — fetch one live run

The app must be running. You need a signed token and a room UUID (see
"Harvesting a token" below).

```bash
python3 scripts/graffiti/run.py \
    --room "<uuid>" \
    --token "<signed-token>"
```

Fetches `/room/{room}/government-trend` once, treats it as one run, updates
the store. Useful for ad-hoc spot-checking; doesn't sweep knobs.

### Mode 3 — sweep across configs

Lets the knob-restatement filter work. Two flavors:

**3a. You produce the dumps yourself** (manual restart loop, or different
rooms with different configs already running). The spec just lists where
the dumps are:

```jsonc
// apps/research-service/research/graffiti/sweeps/pressure-scale.json
{
  "knobs": ["app.government.feedback.pressure.trustPenaltyScale"],
  "configs": [
    {
      "name": "low",
      "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.010},
      "runs": [
        {"type": "dump", "path": "../dumps/low-r1.json"},
        {"type": "dump", "path": "../dumps/low-r2.json"}
      ]
    },
    {
      "name": "mid",
      "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.025},
      "runs": [{"type": "dump", "path": "../dumps/mid-r1.json"}]
    },
    {
      "name": "high",
      "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.040},
      "runs": [{"type": "dump", "path": "../dumps/high-r1.json"}]
    }
  ]
}
```

Paths in `runs[].path` are resolved relative to the spec file's directory.

```bash
python3 scripts/graffiti/run.py --sweep apps/research-service/research/graffiti/sweeps/pressure-scale.json --reset
```

**3b. Auto-sweep — let the driver restart the app.** Add an `auto` block
and pass `--yes`. The driver writes a `.properties` override per config,
restarts the JVM via `scripts/start.sh`, waits for `/actuator/health`,
soaks, dumps, runs `scripts/stop.sh`, repeats.

```jsonc
{
  "auto": {
    "token": "<paste a real signed token here>",
    "room": "<room UUID with an active government>",
    "soak_seconds": 120,
    "window_minutes": 30,
    "dump_dir": "apps/research-service/research/graffiti/dumps/auto",
    "repo_root": ".",
    "boot_timeout_seconds": 180,
    "base_url": "http://localhost:8081",
    "health_url": "http://localhost:8081/actuator/health"
  },
  "knobs": ["app.government.feedback.pressure.trustPenaltyScale"],
  "configs": [
    {"name": "low",  "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.010}, "overrides": {"app.government.feedback.pressure.trustPenaltyScale": 0.010}},
    {"name": "mid",  "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.025}, "overrides": {"app.government.feedback.pressure.trustPenaltyScale": 0.025}},
    {"name": "high", "values": {"app.government.feedback.pressure.trustPenaltyScale": 0.040}, "overrides": {"app.government.feedback.pressure.trustPenaltyScale": 0.040}}
  ]
}
```

```bash
python3 scripts/graffiti/run.py --sweep apps/research-service/research/graffiti/sweeps/auto-pressure.json --reset --yes
```

Wall time ≈ `len(configs) × (boot + soak + stop)`. For 3 configs at 120 s
soak each, expect 10–12 minutes.

`overrides` defaults to `values` if absent. Keep them separate when you
want `values` to record the *intent* (knob axis position) and `overrides`
to add ancillary properties that aren't on a knob axis.

## Sweep spec reference

```jsonc
{
  // OPTIONAL: present only for auto-sweep (mode 3b).
  "auto": {
    "token":               string,   // required
    "room":                string,   // required (UUID)
    "soak_seconds":        int,      // default 90; bump for fuller trend
    "window_minutes":      int,      // default 30; how much trend history to fetch
    "dump_dir":            string,   // default apps/research-service/research/graffiti/dumps/auto
    "repo_root":           string,   // default = spec file's directory
    "start_script":        string,   // default ./scripts/start.sh
    "stop_script":         string,   // default ./scripts/stop.sh
    "health_url":          string,   // default http://localhost:8081/actuator/health
    "base_url":            string,   // default http://localhost:8081
    "boot_timeout_seconds": int      // default 180
  },

  // Knob axes the Spearman filter should consider. Each name is a Spring
  // property path (e.g. "app.government.feedback.pressure.trustPenaltyScale").
  "knobs": [string, ...],

  "configs": [
    {
      "name":      string,                  // human label
      "values":    { knob: number, ... },   // position on each axis
      "overrides": { property: value, ... },// what to write to the .properties file
                                            // (defaults to `values` when absent)
      "runs": [                             // only required for mode 3a;
                                            // auto mode populates this for you
        {"type": "dump", "path": string, "seed": string?, "room": string?},
        {"type": "live", "room": string,  "token": string, "seed": string?}
      ]
    }
  ]
}
```

## Harvesting a token

Tokens are minted by the web profile flow and signed with `app.qr.hmacSecret`.
The driver does NOT mint tokens — you harvest one from a real browser session
and paste it into the spec. Steps:

1. Start the app: `./scripts/start.sh`.
2. Register or log in via `http://localhost:8081/`.
3. Create or load a profile; you'll be redirected to `/game?token=...`.
4. Copy the `token` query parameter — that's the signed token.
5. The same page tells you the active room UUID, or you can read it from
   the URL after creating a room (`/room/{uuid}`).

The token is stable across restarts because `app.qr.hmacSecret` doesn't
change between runs. The room is stable because PostgreSQL persists across
restarts. So one harvest is good for many sweeps.

## Reading the output

After a run, the CLI prints up to 25 surviving conjectures sorted by
confirmations and slack:

```
  *  legitimacy_mean  <=  +0.820 * implementation        +0.110   runs=73  cfgs=9  p99_slack=+0.0180
     trust_service    >=  +0.400 * civic_pressure        -0.050   runs=51  cfgs=6  p99_slack=+0.0220
```

- A leading `*` marks **promoted** conjectures (≥50 confirmed runs across ≥5 configs).
- `runs` = total runs that confirmed it; `cfgs` = distinct config_hashes.
- `p99_slack` = worst observed margin (positive = closer to violation).
  Lower is tighter; a tight conjecture across many configs is the interesting case.

The retired/dropped counts are printed separately:

```
  update high-r0|a538f128f5|...: confirmed=211 retired=2
  ...
knob-restatement filter dropped 93 conjecture(s)
```

The persisted store (`apps/research-service/research/graffiti/store.json` by default) keeps
witness records for every refutation so you can audit which tick broke
which conjecture.

## Caveats

1. **Knob-restatement filter needs ≥3 distinct configs per axis.** With 2
   configs every monotonic trend gets flagged. Sweep three or more values.
2. **Cross-config bleed.** PostgreSQL persists across restarts, so config B
   sees some tail of config A's trend. The default 30-minute window plus
   the 120-second soak bounds this but doesn't eliminate it. For hard
   isolation, drop the room's trend table between configs.
3. **Tautological survivors.** Bounded count fields (`active_bills ≤ 5`)
   often survive everything. They're not interesting; they belong in a
   separate `Bound` family (v2). For now, eyeball-filter them.
4. **Linear inequalities only.** No `a ≤ b²`, no conservation laws, no
   temporal lags. Those are v2 — `Ratio` and `Conservation` families.
5. **Single calling thread; restarts are serial.** The auto-sweep
   assumes sole control of the local app for the duration of the run.
   Don't start another mvn process in parallel.

## Quick reference

```bash
# Replay dumps you already have
python3 scripts/graffiti/run.py --runs-dir apps/research-service/research/graffiti/dumps --reset

# One ad-hoc fetch from a live app
python3 scripts/graffiti/run.py --room <uuid> --token <token>

# Sweep over pre-existing dumps with knob axes
python3 scripts/graffiti/run.py --sweep apps/research-service/research/graffiti/sweeps/<name>.json --reset

# Auto-sweep (restarts the app per config)
python3 scripts/graffiti/run.py --sweep apps/research-service/research/graffiti/sweeps/<auto-name>.json --reset --yes

# Override store path
python3 scripts/graffiti/run.py --sweep <spec> --store /tmp/store.json
```

## Tuning constants

In `conjectures.py`:

| Constant | Default | Meaning |
|---|---|---|
| `TOL` | `0.02` | Slack tolerance in scalar units. Tick violations above `2 * TOL` retire the conjecture. |
| `PROMOTE_RUNS` | `50` | Confirmed runs needed for promotion. |
| `PROMOTE_CONFIGS` | `5` | Distinct config_hashes needed for promotion. |
| `NEAR_IDENTITY_CORR` | `0.98` | Seed-time correlation cutoff that drops near-identities. |

In `auto_sweep.py:AutoConfig`:

| Field | Default | Notes |
|---|---|---|
| `soak_seconds` | `90` | Government simulation ticks every 2.5 s, so 90 s ≈ 36 tick samples. |
| `window_minutes` | `30` | Narrow window keeps each config's dump fresh and reduces cross-config bleed. |
| `boot_timeout_seconds` | `180` | JVM + Maven + Spring startup is ~30 s on a warm dev shell, longer on cold. |
