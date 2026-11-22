#!/usr/bin/env python3
"""Execute frozen r2 controls under the same strict policy as product cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import traceback

from approved_runtime import remove_runtime_bytecode
from run_common import (
    base_record, collect_case_records, create_run_context, evaluate_test_artifacts,
    execute_pytest, persist_run, sha256, utcnow, write_failure, write_json,
)
from source_policy import create_snapshot, verify_against_snapshot
from verify_runtime import verify as verify_locked_runtime

ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "controls/manifest.json"
EVIDENCE = ROOT / "controls-evidence"
BINDINGS = json.loads((ROOT / "case-binding.json").read_text())["suites"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--wheelhouse", type=Path, default=ROOT / ".wheelhouse")
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    wheelhouse = args.wheelhouse.resolve()
    manifest = json.loads(MANIFEST.read_text())
    binding = BINDINGS[manifest["suite"]]
    context = create_run_context(EVIDENCE, manifest["suite"])
    started_at = utcnow()
    initial = base_record(context, manifest, started_at)
    initial.update({"status": "running", "stage": "setup", "control_scope": True})
    persist_run(context, initial)
    try:
        manifest_hash = sha256(MANIFEST)
        if manifest_hash != (ROOT / "controls/provenance/frozen-manifest.sha256").read_text().strip():
            raise ValueError("frozen controls manifest mismatch")
        source = create_snapshot(ROOT, context["run_dir"])
        if not source["pass"]:
            raise ValueError(f"control source preflight rejected: {source['failures']}")
        source_digest = source["source_digest"]
        preparation = remove_runtime_bytecode(runtime)
        write_json(context["run_dir"] / "runtime-preparation.json", preparation)
        verification = verify_locked_runtime(runtime, ROOT / "requirements.lock", wheelhouse)
        write_json(context["run_dir"] / "runtime-verification.json", verification)
        if not verification["pass"]:
            raise ValueError("controls runtime failed trusted-wheel/RECORD/isolation verification")
    except Exception as exc:
        record = write_failure(
            context, manifest, started_at, "setup", f"{type(exc).__name__}: {exc}",
            manifest_sha256=locals().get("manifest_hash"), source_preflight=locals().get("source"),
            traceback=traceback.format_exc(), control_scope=True,
        )
        print(json.dumps(record, indent=2, ensure_ascii=False))
        return record["exit_code"]

    test = execute_pytest(
        context, runtime, manifest_hash, source_digest, "controls/tests", args.timeout,
        extra_env={"SWARM_DB02_RUNTIME": str(runtime), "SWARM_DB02_WHEELHOUSE": str(wheelhouse)},
    )
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
        "requirements_sha256": sha256(ROOT / "requirements.lock"),
        "runtime": str(runtime), "wheelhouse": str(wheelhouse),
        "runtime_preparation": str(context["run_dir"] / "runtime-preparation.json"),
        "runtime_verification": str(context["run_dir"] / "runtime-verification.json"),
        "runtime_verification_pass": verification["pass"],
        "test_command": test["command"], "test_subprocess": subprocess_result,
        "isolation_probe": str(context["probe"]), "isolation_probe_pass": artifacts["probe_pass"],
        "isolation_probe_error": artifacts["probe_error"],
        "junit": str(context["junit"]), "junit_validation": artifacts["junit"],
        "case_aggregation": {key: value for key, value in aggregation.items() if key != "records"},
        "counts": aggregation["counts"], "control_scope": True,
        "original_db01_db02_r1_overwritten": False, "independent_reviewer": None,
    })
    executions = []
    for spec in manifest["cases"]:
        observed = aggregation["records"].get(spec["case_id"])
        executions.append({
            "case_id": spec["case_id"], "purpose": spec["purpose"],
            "fixed_input": spec["fixed_input"], "expected": spec["expected"],
            "observed": observed.get("observed") if observed else None,
            "expected_matches_observed": observed.get("expected_matches_observed_recomputed") if observed else False,
            "pytest_outcome": observed.get("pytest_outcome") if observed else "NOT RUN",
            "run_id": context["run_id"], "run_token": context["run_token"],
            "manifest_sha256": manifest_hash, "source_digest": source_digest,
            "suite_exit_code": exit_code, "batch_accepted": success,
        })
    write_json(context["run_dir"] / "execution.json", executions)
    persist_run(context, record)
    print(json.dumps(record, indent=2, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
