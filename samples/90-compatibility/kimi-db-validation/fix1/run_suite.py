#!/usr/bin/env python3
"""DB-02 hardening r2: trusted-wheel, isolated, source-bound fail-closed runner."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import re
import sqlite3
import sys
import traceback
import uuid

from approved_runtime import remove_runtime_bytecode
from process_policy import run_bounded_subprocess
from run_common import (
    base_record, collect_case_records, create_run_context, evaluate_test_artifacts,
    execute_pytest, persist_run, sha256, utcnow, write_failure, write_json,
)
from source_policy import create_snapshot, verify_against_snapshot
from verify_runtime import verify as verify_locked_runtime

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT / "evidence"
MANIFEST = ROOT / "case_manifest.json"
LOCK = ROOT / "requirements.lock"
BINDINGS = json.loads((ROOT / "case-binding.json").read_text())["suites"]


def ensure_wheels(wheelhouse: Path, run_dir: Path, timeout: float) -> dict:
    wheels = sorted(wheelhouse.glob("*.whl"))
    if wheels:
        return {"status": "reused-pending-full-verification", "exit_code": 0, "wheel_count": len(wheels), "wheelhouse": str(wheelhouse)}
    wheelhouse.mkdir(parents=True, exist_ok=True)
    command = [
        sys.executable, "-m", "pip", "download", "--index-url", "https://pypi.org/simple/",
        "--only-binary=:all:", "--no-deps", "--disable-pip-version-check",
        "--require-hashes", "-r", str(LOCK), "-d", str(wheelhouse),
    ]
    result = run_bounded_subprocess(
        command, cwd=ROOT, stdout_path=run_dir / "wheel-download.stdout.log",
        stderr_path=run_dir / "wheel-download.stderr.log", timeout_seconds=timeout,
    )
    result.update({"wheelhouse": str(wheelhouse), "wheel_count": len(list(wheelhouse.glob("*.whl")))})
    return result


def install_runtime(runtime: Path, wheelhouse: Path, run_dir: Path, timeout: float) -> dict:
    lock_hash = sha256(LOCK)
    marker = runtime / ".swarm-db02-r2-requirements-sha256"
    if runtime.exists() and marker.is_file() and marker.read_text().strip() == lock_hash:
        return {"status": "reused-pending-full-verification", "exit_code": 0, "runtime": str(runtime), "requirements_sha256": lock_hash}
    if runtime.exists():
        runtime.rename(runtime.with_name(runtime.name + "-retired-" + uuid.uuid4().hex))
    runtime.mkdir(parents=True)
    command = [
        sys.executable, "-m", "pip", "install", "--no-index", "--find-links", str(wheelhouse),
        "--target", str(runtime), "--disable-pip-version-check", "--require-hashes", "-r", str(LOCK),
    ]
    result = run_bounded_subprocess(
        command, cwd=ROOT, stdout_path=run_dir / "install.stdout.log",
        stderr_path=run_dir / "install.stderr.log", timeout_seconds=timeout,
    )
    result.update({"runtime": str(runtime), "requirements_sha256": lock_hash, "acquisition": "offline-from-locked-wheelhouse"})
    if result["status"] == "completed" and result["exit_code"] == 0 and result["cleanup_pass"]:
        result["status"] = "installed"
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, default=ROOT / ".runtime")
    parser.add_argument("--wheelhouse", type=Path, default=ROOT / ".wheelhouse")
    parser.add_argument("--install-timeout", type=float, default=300.0)
    parser.add_argument("--test-timeout", type=float, default=120.0)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    wheelhouse = args.wheelhouse.resolve()
    manifest = json.loads(MANIFEST.read_text())
    binding = BINDINGS[manifest["suite"]]
    context = create_run_context(EVIDENCE, manifest["suite"])
    started_at = utcnow()
    initial = base_record(context, manifest, started_at)
    initial.update({"status": "running", "stage": "setup"})
    persist_run(context, initial)
    install = None
    try:
        manifest_hash = sha256(MANIFEST)
        if manifest_hash != (ROOT / "provenance/frozen-manifest.sha256").read_text().strip():
            raise ValueError("frozen case manifest mismatch")
        source = create_snapshot(ROOT, context["run_dir"])
        if not source["pass"]:
            raise ValueError(f"control source preflight rejected: {source['failures']}")
        source_digest = source["source_digest"]
        wheel_result = ensure_wheels(wheelhouse, context["run_dir"], args.install_timeout)
        if wheel_result.get("status") not in {"completed", "reused-pending-full-verification"} or wheel_result.get("exit_code") != 0 or not wheel_result.get("cleanup_pass", True):
            raise RuntimeError("trusted wheel acquisition failed")
        install = install_runtime(runtime, wheelhouse, context["run_dir"], args.install_timeout)
        if install["status"] not in {"installed", "reused-pending-full-verification"} or install["exit_code"] != 0:
            raise RuntimeError(f"runtime installation rejected: {install['status']}")
        preparation = remove_runtime_bytecode(runtime)
        write_json(context["run_dir"] / "runtime-preparation.json", preparation)
        verification = verify_locked_runtime(runtime, LOCK, wheelhouse)
        write_json(context["run_dir"] / "runtime-verification.json", verification)
        if not verification["pass"]:
            raise ValueError("trusted wheel, RECORD or isolated runtime verification failed")
        (runtime / ".swarm-db02-r2-requirements-sha256").write_text(sha256(LOCK) + "\n")
    except Exception as exc:
        failure_exit = install.get("exit_code", 1) if isinstance(install, dict) else 1
        record = write_failure(
            context, manifest, started_at, "setup", f"{type(exc).__name__}: {exc}",
            exit_code=failure_exit or 1, manifest_sha256=locals().get("manifest_hash"),
            source_preflight=locals().get("source"), runtime_install=install,
            traceback=traceback.format_exc(),
        )
        print(json.dumps(record, indent=2, ensure_ascii=False))
        return record["exit_code"]

    test = execute_pytest(context, runtime, manifest_hash, source_digest, "tests", args.test_timeout)
    post_source = verify_against_snapshot(ROOT, context["run_dir"], source_digest)
    artifacts = evaluate_test_artifacts(context, binding, manifest_hash, source_digest)
    aggregation = collect_case_records(
        context["case_output_dir"], manifest, binding, context["run_token"], manifest_hash, source_digest,
    )
    subprocess_result = test["subprocess"]
    success = all([
        verification["pass"], post_source["pass"], artifacts["probe_pass"], artifacts["junit"]["pass"],
        aggregation["pass"], subprocess_result["status"] == "completed",
        subprocess_result["exit_code"] == 0, subprocess_result["cleanup_pass"],
    ])
    exit_code = 0 if success else (subprocess_result["exit_code"] or 1)
    ended_at = utcnow()
    record = base_record(context, manifest, started_at)
    record.update({
        "status": "passed" if success else "rejected", "success": success,
        "acceptance": "accepted" if success else "rejected", "stage": "complete",
        "ended_at": ended_at, "exit_code": exit_code,
        "manifest_sha256": manifest_hash, "source_digest": source_digest,
        "source_preflight": source, "source_postcheck": post_source,
        "source_snapshot": str(context["run_dir"] / "source-snapshot.json"),
        "requirements_sha256": sha256(LOCK), "wheel_acquisition": wheel_result,
        "runtime_install": install, "runtime_preparation": str(context["run_dir"] / "runtime-preparation.json"),
        "runtime_verification": str(context["run_dir"] / "runtime-verification.json"),
        "runtime_verification_pass": verification["pass"],
        "test_command": test["command"], "test_subprocess": subprocess_result,
        "isolation_probe": str(context["probe"]), "isolation_probe_pass": artifacts["probe_pass"],
        "isolation_probe_error": artifacts["probe_error"],
        "junit": str(context["junit"]), "junit_validation": artifacts["junit"],
        "case_aggregation": {key: value for key, value in aggregation.items() if key != "records"},
        "counts": aggregation["counts"], "derived_from_r1": True, "derived_from_r2": True,
        "original_db01_db02_r1_overwritten": False, "original_r2_overwritten": False, "independent_reviewer": None,
        "runtime": {
            "python": sys.version, "python_executable": sys.executable,
            "interpreter_mode": "-B -I -S", "pytest_plugin_autoload": False,
            "external_plugin_allowlist": [], "runtime_path": str(runtime),
            "platform": platform.platform(), "machine": platform.machine(),
            "sqlite_runtime": sqlite3.sqlite_version,
            "installed_distributions": verification["installed_versions"],
            "external_database_used": False, "mocks_used": False,
        },
    })
    executions = []
    for spec in manifest["cases"]:
        observed = aggregation["records"].get(spec["case_id"])
        executions.append({
            "queue": "DB-02-runtime-hardening-r2-fix1", "suite": manifest["suite"],
            "case_id": spec["case_id"], "purpose": spec["purpose"],
            "fixed_input": spec["fixed_input"], "expected": spec["expected"],
            "observed": observed.get("observed") if observed else None,
            "expected_matches_observed": observed.get("expected_matches_observed_recomputed") if observed else False,
            "pytest_outcome": observed.get("pytest_outcome") if observed else "NOT RUN",
            "diagnostics": observed.get("diagnostics") if observed else None,
            "run_id": context["run_id"], "run_token": context["run_token"],
            "manifest_sha256": manifest_hash, "source_digest": source_digest,
            "suite_started_at": started_at, "suite_ended_at": ended_at,
            "suite_exit_code": exit_code, "derived_result": True, "batch_accepted": success,
        })
    write_json(context["run_dir"] / "execution.json", executions)
    persist_run(context, record)
    print(json.dumps(record, indent=2, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
