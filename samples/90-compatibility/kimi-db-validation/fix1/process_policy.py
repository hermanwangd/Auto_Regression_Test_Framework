#!/usr/bin/env python3
"""Bounded subprocess execution and run-owned process-group cleanup."""
from __future__ import annotations

import datetime as dt
import os
from pathlib import Path
import signal
import subprocess
import time


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def group_members(pgid: int) -> list[dict]:
    members = []
    proc = Path("/proc")
    if not proc.is_dir():
        return members
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            raw = (entry / "stat").read_text()
            fields = raw[raw.rfind(")") + 2:].split()
            state = fields[0]
            observed_pgid = int(fields[2])
            if observed_pgid == pgid:
                members.append({"pid": int(entry.name), "state": state})
        except (OSError, ValueError, IndexError):
            continue
    return sorted(members, key=lambda item: item["pid"])


def live_members(pgid: int) -> list[dict]:
    return [item for item in group_members(pgid) if item["state"] != "Z"]


def wait_bounded(process: subprocess.Popen, seconds: float) -> tuple[bool, int | None]:
    try:
        return True, process.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        return False, None


def terminate_owned_group(pgid: int, term_grace: float, kill_grace: float) -> dict:
    """Only signals this run's PGID; every wait and poll has a hard deadline."""
    started = time.monotonic()
    before = group_members(pgid)
    term_error = None
    kill_error = None
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except OSError as exc:
        term_error = str(exc)
    deadline = time.monotonic() + term_grace
    while time.monotonic() < deadline and live_members(pgid):
        time.sleep(min(0.02, max(0, deadline - time.monotonic())))
    try:
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except OSError as exc:
        kill_error = str(exc)
    deadline = time.monotonic() + kill_grace
    while time.monotonic() < deadline and live_members(pgid):
        time.sleep(min(0.02, max(0, deadline - time.monotonic())))
    remaining = group_members(pgid)
    live_remaining = [item for item in remaining if item["state"] != "Z"]
    return {
        "pgid": pgid,
        "members_before": before,
        "members_after": remaining,
        "live_members_after": live_remaining,
        "cleanup_pass": not live_remaining,
        "term_error": term_error,
        "kill_error": kill_error,
        "cleanup_duration_seconds": round(time.monotonic() - started, 6),
        "term_grace_seconds": term_grace,
        "kill_grace_seconds": kill_grace,
    }


def run_bounded_subprocess(
    command: list[str],
    *,
    cwd: Path,
    stdout_path: Path,
    stderr_path: Path,
    timeout_seconds: float,
    env: dict | None = None,
    term_grace_seconds: float = 0.4,
    kill_grace_seconds: float = 1.0,
) -> dict:
    if min(timeout_seconds, term_grace_seconds, kill_grace_seconds) <= 0:
        raise ValueError("all timeout and cleanup bounds must be positive")
    started_at = utcnow()
    monotonic_start = time.monotonic()
    result = {
        "command": command, "cwd": str(cwd), "timeout_seconds": timeout_seconds,
        "started_at": started_at, "stdout": str(stdout_path), "stderr": str(stderr_path),
        "timed_out": False, "process_group_policy": "start_new_session; owned PGID only",
        "stdio_policy": "exclusive files; no inherited PIPE wait",
    }
    process = None
    with stdout_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
        try:
            process = subprocess.Popen(
                command, cwd=cwd, env=env, stdout=stdout, stderr=stderr,
                start_new_session=True,
            )
        except OSError as exc:
            result.update({
                "status": "startup-failed", "exit_code": 126,
                "error": f"{type(exc).__name__}: {exc}", "ended_at": utcnow(),
                "cleanup_pass": False,
            })
            result["duration_seconds"] = round(time.monotonic() - monotonic_start, 6)
            return result
        result["pid"] = process.pid
        result["pgid"] = process.pid
        completed, exit_code = wait_bounded(process, timeout_seconds)
        if not completed:
            result["timed_out"] = True
            result["error"] = f"subprocess exceeded {timeout_seconds} seconds"
            cleanup = terminate_owned_group(process.pid, term_grace_seconds, kill_grace_seconds)
            reaped, observed_exit = wait_bounded(process, kill_grace_seconds)
            cleanup["parent_reaped"] = reaped
            cleanup["cleanup_pass"] = cleanup["cleanup_pass"] and reaped
            result.update({
                "status": "timeout", "exit_code": 124,
                "parent_exit_after_kill": observed_exit,
                "cleanup": cleanup, "cleanup_pass": cleanup["cleanup_pass"],
                "ended_at": utcnow(),
            })
        else:
            leftovers = live_members(process.pid)
            cleanup = None
            if leftovers:
                cleanup = terminate_owned_group(process.pid, term_grace_seconds, kill_grace_seconds)
            cleanup_pass = cleanup is None or cleanup["cleanup_pass"]
            result.update({
                "status": "completed" if not leftovers else "completed-with-descendant-cleanup",
                "exit_code": exit_code if not leftovers else 125,
                "parent_exit_code": exit_code,
                "unexpected_descendants_after_parent_exit": leftovers,
                "cleanup": cleanup, "cleanup_pass": cleanup_pass,
                "ended_at": utcnow(),
            })
    result["duration_seconds"] = round(time.monotonic() - monotonic_start, 6)
    return result
