#!/usr/bin/env python3
"""Shared r2 run, case-binding and bounded-pytest policy."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid

from junit_policy import validate_junit
from process_policy import run_bounded_subprocess

ROOT = Path(__file__).resolve().parent


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".tmp-{os.getpid()}-{uuid.uuid4().hex}")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str) + "\n")
    temporary.replace(path)


def create_run_context(evidence: Path, suite: str) -> dict:
    evidence.mkdir(parents=True, exist_ok=True)
    runs = evidence / "runs"
    runs.mkdir(exist_ok=True)
    token = uuid.uuid4().hex
    run_id = "run-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + token
    run_dir = runs / run_id
    run_dir.mkdir(exist_ok=False)
    case_dir = run_dir / "case-records"
    case_dir.mkdir(exist_ok=False)
    return {
        "suite": suite, "evidence": evidence, "run_token": token, "run_id": run_id,
        "run_dir": run_dir, "case_output_dir": case_dir,
        "junit": run_dir / "junit.xml", "probe": run_dir / "isolation-probe.json",
        "test_stdout": run_dir / "test.stdout.log", "test_stderr": run_dir / "test.stderr.log",
    }


def persist_run(context: dict, record: dict) -> None:
    write_json(context["run_dir"] / "summary.json", record)
    write_json(context["evidence"] / "summary.json", record)


def blocked_counts(manifest: dict) -> dict:
    total = len(manifest["cases"])
    return {"candidate": total, "executed": 0, "passed": 0, "failed": 0, "blocked": total, "skipped": 0}


def base_record(context: dict, manifest: dict, started_at: str) -> dict:
    return {
        "queue": "DB-02-runtime-hardening-r2-fix1", "suite": manifest["suite"],
        "run_id": context["run_id"], "run_token": context["run_token"],
        "run_directory": str(context["run_dir"]),
        "case_output_directory": str(context["case_output_dir"]),
        "started_at": started_at, "exit_code": 1, "counts": blocked_counts(manifest),
        "success": False, "acceptance": "rejected",
        "evidence_class": "derived package/runtime and targeted controls only",
        "native_pass": False, "sit_pass": False, "formal_acceptance": False,
        "existing_denominator": 116, "existing_denominator_changed": False,
        "candidate_cases_added_to_existing_denominator": False,
    }


def write_failure(context: dict, manifest: dict, started_at: str, stage: str, error: str, **details) -> dict:
    record = base_record(context, manifest, started_at)
    record.update({"status": "rejected", "stage": stage, "ended_at": utcnow(), "error": error, **details})
    record["exit_code"] = details.get("exit_code", 1) or 1
    persist_run(context, record)
    return record


def sanitized_pytest_env(context: dict, manifest_hash: str, source_digest: str) -> dict:
    env = os.environ.copy()
    for name in list(env):
        if name.startswith("PYTHON") or name.startswith("PYTEST_") or name == "VIRTUAL_ENV":
            env.pop(name, None)
    env.update({
        "SWARM_DB02_RUN_ID": context["run_id"],
        "SWARM_DB02_RUN_TOKEN": context["run_token"],
        "SWARM_DB02_CASE_OUTPUT_DIR": str(context["case_output_dir"]),
        "SWARM_DB02_MANIFEST_SHA256": manifest_hash,
        "SWARM_DB02_SOURCE_DIGEST": source_digest,
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
        "PYTHONNOUSERSITE": "1",
    })
    return env


def execute_pytest(
    context: dict,
    runtime: Path,
    manifest_hash: str,
    source_digest: str,
    test_target: str,
    timeout_seconds: float,
    extra_env: dict | None = None,
) -> dict:
    for path in (context["junit"], context["probe"], context["test_stdout"], context["test_stderr"]):
        if path.exists():
            raise FileExistsError(f"refusing preexisting run artifact: {path}")
    command = [
        sys.executable, "-B", "-I", "-S", str(ROOT / "isolated_pytest.py"),
        "--runtime", str(runtime), "--source-root", str(ROOT),
        "--probe-output", str(context["probe"]), "--",
        "-q", "-p", "no:cacheprovider", "--junitxml", str(context["junit"]), test_target,
    ]
    env = sanitized_pytest_env(context, manifest_hash, source_digest)
    if extra_env:
        env.update(extra_env)
    subprocess_result = run_bounded_subprocess(
        command, cwd=ROOT, env=env,
        stdout_path=context["test_stdout"], stderr_path=context["test_stderr"],
        timeout_seconds=timeout_seconds,
    )
    return {"command": command, "subprocess": subprocess_result}


def collect_case_records(
    case_dir: Path,
    manifest: dict,
    binding: dict,
    run_token: str,
    manifest_hash: str,
    source_digest: str,
) -> dict:
    specs = {case["case_id"]: case for case in manifest["cases"]}
    expected_ids = set(specs)
    expected_binding = binding["cases"]
    errors = []
    valid = {}
    seen = set()
    files = sorted(case_dir.glob("*.json"))
    parsed = 0
    for path in files:
        try:
            record = json.loads(path.read_text())
            parsed += 1
        except Exception as exc:
            errors.append(f"malformed:{path.name}:{type(exc).__name__}:{exc}")
            continue
        case_id = record.get("case_id")
        if case_id not in expected_ids:
            errors.append(f"unknown_case:{path.name}:{case_id}")
            continue
        if case_id in seen:
            errors.append(f"duplicate_case:{case_id}:{path.name}")
            continue
        seen.add(case_id)
        if record.get("run_token") != run_token:
            errors.append(f"wrong_run_token:{case_id}")
            continue
        if record.get("manifest_sha256") != manifest_hash:
            errors.append(f"wrong_manifest_hash:{case_id}")
            continue
        if record.get("source_digest") != source_digest:
            errors.append(f"wrong_source_digest:{case_id}")
            continue
        expected_identity = expected_binding[case_id]
        if record.get("junit_classname") != expected_identity["classname"] or record.get("junit_name") != expected_identity["name"]:
            errors.append(f"wrong_junit_identity:{case_id}")
            continue
        spec = specs[case_id]
        record = dict(record)
        record["expected_matches_observed_recomputed"] = spec["expected"] == record.get("observed")
        valid[case_id] = record
    missing = sorted(expected_ids - set(valid))
    errors.extend(f"missing_case:{case_id}" for case_id in missing)
    passed = sum(
        record.get("pytest_outcome") == "passed" and record["expected_matches_observed_recomputed"]
        for record in valid.values()
    )
    skipped = sum(record.get("pytest_outcome") == "skipped" for record in valid.values())
    counts = {
        "candidate": len(expected_ids), "executed": len(valid), "passed": passed,
        "failed": len(expected_ids) - passed - skipped, "blocked": 0, "skipped": skipped,
    }
    return {
        "pass": not errors and len(valid) == len(expected_ids) and passed == len(expected_ids),
        "records": valid, "json_file_count": len(files), "parsed_record_count": parsed,
        "errors": errors, "missing_case_ids": missing, "counts": counts,
        "run_token": run_token, "manifest_sha256": manifest_hash, "source_digest": source_digest,
        "case_output_directory": str(case_dir),
    }


def evaluate_test_artifacts(context: dict, binding: dict, manifest_hash: str, source_digest: str) -> dict:
    try:
        probe = json.loads(context["probe"].read_text())
        probe_error = None
    except Exception as exc:
        probe = {"pass": False, "failures": ["probe missing or unreadable"]}
        probe_error = f"{type(exc).__name__}: {exc}"
    junit = validate_junit(context["junit"], binding, context["run_token"], manifest_hash, source_digest)
    return {
        "probe": probe, "probe_error": probe_error,
        "probe_pass": probe.get("pass") is True and not probe.get("failures") and probe.get("plugin_distinfo") == [],
        "junit": junit,
    }
