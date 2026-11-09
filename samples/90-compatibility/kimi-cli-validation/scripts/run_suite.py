#!/usr/bin/env python3
"""Run a frozen pytest manifest and preserve machine-readable evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from importlib.metadata import distributions
import uuid


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_hashes(root, suite):
    candidates = [
        root / f"case_manifest.{suite}.json",
        root / f"requirements-{suite}.txt",
        root / f"requirements-{suite}.lock",
        root / "pytest.ini",
        root / "tests" / "conftest.py",
    ]
    candidates.extend(sorted((root / "tests" / suite).glob("*.py")))
    candidates.extend(sorted((root / "src" / "swarm_cli01").glob(f"{suite}_app.py")))
    return {
        str(path.relative_to(root)): sha256(path)
        for path in candidates
        if path.is_file()
    }


def case_status(testcase):
    if testcase.find("failure") is not None:
        return "failed"
    if testcase.find("error") is not None:
        return "failed"
    if testcase.find("skipped") is not None:
        return "skipped"
    return "passed"


def observed_value(testcase):
    properties = testcase.find("properties")
    if properties is not None:
        for prop in properties.findall("property"):
            if prop.attrib.get("name") == "observed":
                raw = prop.attrib.get("value", "")
                try:
                    return json.loads(raw)
                except json.JSONDecodeError:
                    return raw
    details = []
    for kind in ("failure", "error", "skipped"):
        element = testcase.find(kind)
        if element is not None:
            details.append({
                "kind": kind,
                "message": element.attrib.get("message"),
                "text": element.text,
            })
    return {"assertion_observation": "not recorded", "details": details}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    parser.add_argument("--manifest")
    parser.add_argument("--test-path")
    parser.add_argument("--requirements")
    parser.add_argument("--evidence-root", type=Path, help="Parent for fresh per-run evidence")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    suite = args.suite
    manifest_path = root / (args.manifest or f"case_manifest.{suite}.json")
    test_path = args.test_path or f"tests/{suite}"
    requirements = args.requirements or f"requirements-{suite}.lock"
    run_id = "run-" + uuid.uuid4().hex
    evidence_parent = args.evidence_root or root / "evidence" / suite / "runs"
    evidence = evidence_parent / run_id
    evidence.mkdir(parents=True, exist_ok=False)
    junit_path = evidence / "junit.xml"

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    before_hashes = source_hashes(root, suite)
    freeze_record = {
        "recorded_at": utc_now(),
        "run_id": run_id,
        "command_report": str(junit_path),
        "manifest_sha256": sha256(manifest_path),
        "source_sha256": before_hashes,
    }
    (evidence / "pre_execution_freeze.json").write_text(
        json.dumps(freeze_record, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    packages = {
        dist.metadata["Name"]: dist.version
        for dist in distributions()
        if dist.metadata.get("Name")
    }
    environment = {
        "python_version": sys.version,
        "python_executable": sys.executable,
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "system": platform.system(),
        "machine": platform.machine(),
        "os_name": os.name,
        "packages": dict(sorted(packages.items(), key=lambda item: item[0].lower())),
        "requirements_file": requirements,
    }
    (evidence / "environment.json").write_text(
        json.dumps(environment, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    command = [
        sys.executable,
        "-m",
        "pytest",
        test_path,
        "-vv",
        "--junitxml",
        str(junit_path),
    ]
    started_at = utc_now()
    execution_error = None
    try:
        completed = subprocess.run(
            command,
            cwd=root,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        execution_error = type(error).__name__ + ": " + str(error)
        completed = subprocess.CompletedProcess(command, 2, "", execution_error)
    if completed.returncode not in (0, 1):
        execution_error = execution_error or "pytest execution failed: exit " + str(completed.returncode)
    ended_at = utc_now()
    (evidence / "pytest.stdout.txt").write_text(completed.stdout, encoding="utf-8")
    (evidence / "pytest.stderr.txt").write_text(completed.stderr, encoding="utf-8")

    junit_cases = {}
    if execution_error is None:
        try:
            if junit_path.is_symlink() or not junit_path.is_file():
                raise ValueError("No fresh JUnit report in this run directory")
            if junit_path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError("JUnit report exceeds local sample bound")
            testcases = list(ET.parse(junit_path).getroot().iter("testcase"))
            names = [case.attrib["name"] for case in testcases]
            expected_names = {case["test_name"] for case in manifest["cases"]}
            if len(names) != len(set(names)) or set(names) - expected_names:
                raise ValueError("Ambiguous or unexpected JUnit testcase identity")
            junit_cases = dict(zip(names, testcases))
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            execution_error = type(error).__name__ + ": " + str(error)
            completed.returncode = 2
    evidence_cases = []
    counts = {
        "candidate": len(manifest["cases"]),
        "executed": 0,
        "passed": 0,
        "failed": 0,
        "blocked": 0,
        "skipped": 0,
    }
    for case in manifest["cases"]:
        testcase = junit_cases.get(case["test_name"])
        if testcase is None:
            status = "blocked"
            observed = {"reason": execution_error or "No matching testcase in fresh JUnit report."}
            counts["blocked"] += 1
            duration = None
        else:
            status = case_status(testcase)
            observed = observed_value(testcase)
            duration = float(testcase.attrib.get("time", "0"))
            counts["executed"] += 1
            counts[status] += 1
        evidence_cases.append({
            "case_id": case["case_id"],
            "category": case["category"],
            "test_name": case["test_name"],
            "fixed_input": case["fixed_input"],
            "expected": case["expected"],
            "observed": observed,
            "status": status,
            "duration_seconds": duration,
        })
    if counts["blocked"]:
        completed.returncode = completed.returncode or 2

    run_metadata = {
        "queue": "SWARM-CLI-01",
        "suite": suite,
        "run_id": run_id,
        "execution_error": execution_error,
        "claim_scope": "package/harness runtime only; not Swarm Native PASS or SIT PASS",
        "existing_116_denominator_changed": False,
        "source": manifest["source"],
        "started_at": started_at,
        "ended_at": ended_at,
        "command_argv": command,
        "command_shell": shlex.join(command),
        "working_directory": str(root),
        "exit_code": completed.returncode,
        "stdout_file": "pytest.stdout.txt",
        "stderr_file": "pytest.stderr.txt",
        "stdout": completed.stdout,
        "stderr": completed.stderr,
        "junit_report": "junit.xml",
        "manifest_sha256_before": freeze_record["manifest_sha256"],
        "manifest_sha256_after": sha256(manifest_path),
        "source_sha256_before": before_hashes,
        "source_sha256_after": source_hashes(root, suite),
        "summary_counts": counts,
    }
    run_metadata["frozen_inputs_unchanged"] = (
        run_metadata["manifest_sha256_before"] == run_metadata["manifest_sha256_after"]
        and run_metadata["source_sha256_before"] == run_metadata["source_sha256_after"]
    )
    (evidence / "run_metadata.json").write_text(
        json.dumps(run_metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    case_document = {
        "queue": "SWARM-CLI-01",
        "suite": suite,
        "run_id": run_id,
        "execution_error": execution_error,
        "started_at": started_at,
        "ended_at": ended_at,
        "command_shell": run_metadata["command_shell"],
        "process_exit_code": completed.returncode,
        "summary_counts": counts,
        "cases": evidence_cases,
    }
    (evidence / "case_evidence.json").write_text(
        json.dumps(case_document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    lines = [
        f"# {suite} 實際執行摘要",
        "",
        f"- 開始（UTC）：`{started_at}`",
        f"- 結束（UTC）：`{ended_at}`",
        f"- 原始命令：`{run_metadata['command_shell']}`",
        f"- pytest process exit code：`{completed.returncode}`",
        f"- 凍結輸入未變更：`{run_metadata['frozen_inputs_unchanged']}`",
        "- 宣稱範圍：僅套件／harness runtime；不是 Swarm Native PASS 或 SIT PASS。",
        "- 既有 116 denominator：未變更，本案不計入既有完成數。",
        "",
        "| candidate | executed | passed | failed | blocked | skipped |",
        "|---:|---:|---:|---:|---:|---:|",
        "| {candidate} | {executed} | {passed} | {failed} | {blocked} | {skipped} |".format(**counts),
        "",
        "| Case | 類別 | 狀態 |",
        "|---|---|---|",
    ]
    lines.extend(
        f"| {case['case_id']} | {case['category']} | {case['status']} |"
        for case in evidence_cases
    )
    (evidence / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    hash_paths = []
    for relative in [
        f"case_manifest.{suite}.json",
        f"requirements-{suite}.txt",
        f"requirements-{suite}.lock",
        "pytest.ini",
        "README.md",
    ]:
        path = root / relative
        if path.is_file():
            hash_paths.append(path)
    for folder in [root / "src", root / "tests" / suite, evidence, root / "licenses"]:
        if folder.exists():
            hash_paths.extend(path for path in sorted(folder.rglob("*")) if path.is_file())
    unique_paths = sorted(set(hash_paths))
    checksum_lines = [
        f"{sha256(path)}  {path.relative_to(root) if path.is_relative_to(root) else Path('evidence') / path.relative_to(evidence)}"
        for path in unique_paths
        if path.name != "SHA256SUMS.txt"
    ]
    (evidence / "SHA256SUMS.txt").write_text(
        "\n".join(checksum_lines) + "\n", encoding="utf-8"
    )

    print(json.dumps({"suite": suite, "exit_code": completed.returncode, **counts}))
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
