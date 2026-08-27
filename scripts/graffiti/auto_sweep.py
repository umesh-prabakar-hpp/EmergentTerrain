"""Restart-based sweep automation.

For each config in a spec's `configs[]`, this:
  1. writes a Spring Boot .properties override with the config's `overrides`
  2. exports SPRING_CONFIG_ADDITIONAL_LOCATION pointing to it
  3. runs scripts/start.sh (the existing background launcher)
  4. polls /actuator/health until 200 OK or boot_timeout
  5. soaks for `soak_seconds` so the government simulation can tick
  6. POSTs to /room/{room}/government-trend and writes the response as a dump
  7. runs scripts/stop.sh

The driver does NOT mint tokens or create rooms -- it expects `auto.token`
and `auto.room` to be set in the spec to a known good (token, room) pair.
The token survives restarts because the HMAC secret in application.yml is
stable; the room survives because PostgreSQL persists across restarts.

Refuses to operate unless `yes=True` is passed by the caller.
"""

from __future__ import annotations

import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

import load as loader


@dataclass
class AutoConfig:
    token: str
    room: str
    soak_seconds: int = 90
    base_url: str = "http://localhost:8081"
    health_url: str = "http://localhost:8081/actuator/health"
    boot_timeout_seconds: int = 180
    window_minutes: int = 30          # narrow window so the trend reflects the soak, not history
    dump_dir: str = "apps/research-service/research/graffiti/dumps/auto"
    start_script: str = "./scripts/start.sh"
    stop_script: str = "./scripts/stop.sh"
    repo_root: str = "."

    @classmethod
    def from_dict(cls, d: dict, spec_dir: str) -> "AutoConfig":
        repo_root = d.get("repo_root") or spec_dir
        repo_root = os.path.abspath(repo_root)
        merged = {**d, "repo_root": repo_root}
        if "dump_dir" in merged and not os.path.isabs(merged["dump_dir"]):
            merged["dump_dir"] = os.path.join(repo_root, merged["dump_dir"])
        return cls(**{k: v for k, v in merged.items() if k in cls.__dataclass_fields__})


def _write_overrides(overrides: dict, path: str) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    lines = [f"# graffiti auto-sweep override; do not edit by hand"]
    for k, v in overrides.items():
        # Spring relaxed binding accepts dotted keys verbatim in .properties.
        lines.append(f"{k}={v}")
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def _wait_health(url: str, deadline_s: float) -> bool:
    while time.monotonic() < deadline_s:
        try:
            with urllib.request.urlopen(url, timeout=3.0) as resp:
                if resp.status == 200:
                    return True
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(2.0)
    return False


def _run_script(script: str, repo_root: str, env: dict | None = None) -> int:
    full_env = {**os.environ, **(env or {})}
    result = subprocess.run(
        ["bash", script],
        cwd=repo_root,
        env=full_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    # mirror to our stdout so the operator can see what start.sh said
    if result.stdout:
        for line in result.stdout.rstrip().splitlines():
            print(f"    [{os.path.basename(script)}] {line}")
    return result.returncode


def _stop_quietly(cfg: AutoConfig) -> None:
    try:
        _run_script(cfg.stop_script, cfg.repo_root)
    except Exception as e:
        print(f"    (stop.sh raised {e!r}; continuing)")


def _cycle_one_config(cfg: AutoConfig, name: str, overrides: dict) -> str | None:
    """Run one config; return the dump path on success, None on failure."""
    overrides_path = os.path.join(cfg.dump_dir, f"override-{name}.properties")
    dump_path = os.path.join(cfg.dump_dir, f"{name}.json")
    _write_overrides(overrides, overrides_path)
    print(f"  config '{name}': override → {overrides_path}")

    # Ensure no stale instance is running before we start ours.
    _stop_quietly(cfg)

    env = {"SPRING_CONFIG_ADDITIONAL_LOCATION": f"file:{overrides_path}"}
    print(f"  config '{name}': starting app …")
    rc = _run_script(cfg.start_script, cfg.repo_root, env=env)
    if rc != 0:
        print(f"  config '{name}': start.sh exited {rc}; aborting this config")
        _stop_quietly(cfg)
        return None

    deadline = time.monotonic() + cfg.boot_timeout_seconds
    print(f"  config '{name}': waiting for {cfg.health_url} (up to {cfg.boot_timeout_seconds}s)")
    if not _wait_health(cfg.health_url, deadline):
        print(f"  config '{name}': health never became 200; aborting this config")
        _stop_quietly(cfg)
        return None

    print(f"  config '{name}': healthy. Soaking {cfg.soak_seconds}s for trend to populate.")
    time.sleep(cfg.soak_seconds)

    try:
        points = loader.fetch_trend(
            cfg.room, cfg.token, base_url=cfg.base_url, window_minutes=cfg.window_minutes
        )
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        print(f"  config '{name}': trend fetch failed: {e!r}; aborting this config")
        _stop_quietly(cfg)
        return None

    if not points:
        print(f"  config '{name}': trend returned 0 points; nothing to dump for this config")
        _stop_quietly(cfg)
        return None

    blob = {
        "room_id": cfg.room,
        "seed": name,
        "config": overrides,
        "points": points,
    }
    with open(dump_path, "w") as f:
        json.dump(blob, f, indent=2)
    print(f"  config '{name}': dumped {len(points)} points → {dump_path}")

    _stop_quietly(cfg)
    return dump_path


def materialize(spec: dict, spec_dir: str, yes: bool = False) -> dict:
    """Drive the app through every config in `spec`. Mutates spec[configs][*].runs.

    `yes` must be True to actually run; otherwise raises. The driver assumes
    sole control of the local app for the duration of the sweep.
    """
    if "auto" not in spec:
        return spec
    if not yes:
        raise RuntimeError(
            "auto sweep is gated on --yes (it stops/starts the local app for each config)"
        )
    cfg = AutoConfig.from_dict(spec["auto"], spec_dir)
    os.makedirs(cfg.dump_dir, exist_ok=True)
    print(f"auto-sweep: {len(spec.get('configs', []))} config(s), dump_dir={cfg.dump_dir}")

    for entry in spec.get("configs", []):
        name = entry.get("name") or "unnamed"
        # `overrides` is what gets written to the .properties file. Defaults to
        # `values` because in most sweeps those are the same Spring property paths.
        overrides = entry.get("overrides") or entry.get("values") or {}
        if not overrides:
            print(f"  config '{name}': no overrides; skipping")
            continue
        dump_path = _cycle_one_config(cfg, name, overrides)
        if dump_path:
            entry.setdefault("runs", []).append({"type": "dump", "path": dump_path})

    return spec
