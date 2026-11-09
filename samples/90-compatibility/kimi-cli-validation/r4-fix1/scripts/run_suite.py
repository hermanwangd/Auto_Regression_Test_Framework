#!/usr/bin/env python3
"""Run frozen pytest cases with fail-closed runtime and acceptance gates."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from importlib.metadata import distribution, distributions, PackageNotFoundError


PIN_RE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9_.-]*)==([^\s\\]+)")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_inside(path, directory):
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def root_path(root, value):
    path = Path(value)
    if not path.is_absolute():
        path = root / path
    path = path.resolve()
    if not is_inside(path, root.resolve()):
        raise ValueError(f"Path escapes frozen source root: {value}")
    return path


def relative_path(root, path):
    return str(path.resolve().relative_to(root.resolve()))


def selected_source_hashes(root, test_path):
    if test_path.is_file():
        paths = [test_path]
    elif test_path.is_dir():
        paths = sorted(path for path in test_path.rglob("*.py") if path.is_file())
    else:
        raise FileNotFoundError(f"Frozen test path does not exist: {test_path}")
    if not paths:
        raise ValueError(f"Frozen test path contains no Python source: {test_path}")
    return {relative_path(root, path): sha256(path) for path in paths}


def source_hashes(root, suite, manifest_path=None, test_path=None):
    candidates = {
        root / f"case_manifest.{suite}.json",
        root / f"requirements-{suite}.txt",
        root / f"requirements-{suite}.lock",
        root / f"requirements-{suite}-hashes.txt",
        root / "runtime_gate.json",
        root / "pytest.ini",
        root / "scripts" / "run_suite.py",
        root / "tests" / "conftest.py",
    }
    candidates.update((root / "tests" / suite).glob("*.py"))
    candidates.update((root / "src" / "swarm_cli01").glob(f"{suite}_app.py"))
    if manifest_path is not None:
        candidates.add(manifest_path)
    if test_path is not None:
        if test_path.is_file():
            candidates.add(test_path)
        elif test_path.is_dir():
            candidates.update(test_path.rglob("*.py"))
    return {
        relative_path(root, path): sha256(path)
        for path in sorted(candidates)
        if path.is_file()
    }


def extend_frozen_hashes(root, hashes, paths):
    for path in paths:
        resolved = root_path(root, path)
        if not resolved.is_file():
            raise FileNotFoundError(f"Effective runtime input cannot enter frozen scope: {resolved}")
        hashes[relative_path(root, resolved)] = sha256(resolved)
    return hashes


def parse_pins(path):
    pins = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("--"):
            continue
        match = PIN_RE.match(line)
        if match:
            name, version = match.groups()
            pins[name.lower().replace("_", "-")] = version
    return pins


def parse_hash_lock(path):
    pins = parse_pins(path)
    hashes = {}
    current = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        match = PIN_RE.match(line)
        if match:
            current = match.group(1).lower().replace("_", "-")
            hashes[current] = []
        elif line.startswith("--hash=sha256:") and current:
            hashes[current].append(line.split(":", 1)[1])
    return pins, hashes


def runtime_pin_gate(root, config_path, requirements_path):
    config = json.loads(config_path.read_text(encoding="utf-8"))
    requirements_path = Path(requirements_path)
    if not requirements_path.is_absolute():
        requirements_path = root / requirements_path
    expected_pins = parse_pins(requirements_path)
    prefix = Path(sys.prefix).absolute()
    base_prefix = Path(sys.base_prefix).absolute()
    executable = Path(sys.executable).absolute()
    observed = {
        "python_version": platform.python_version(),
        "python_executable": str(executable),
        "python_executable_resolved": str(executable.resolve()),
        "sys_prefix": str(prefix),
        "sys_base_prefix": str(base_prefix),
        "isolated_venv": prefix != base_prefix,
        "interpreter_inside_venv": is_inside(executable, prefix),
        "requirements_file": str(requirements_path),
        "requirements_sha256": sha256(requirements_path),
        "distributions": {},
    }
    expected = {
        "python_version": config["python_version"],
        "isolated_venv": config.get("require_isolated_venv", True),
        "distributions_inside_venv": config.get("require_distributions_inside_venv", True),
        "pins": expected_pins,
    }
    rejections = []
    if observed["python_version"] != expected["python_version"]:
        rejections.append({"code": "PYTHON_VERSION_MISMATCH", "expected": expected["python_version"],
                           "observed": observed["python_version"], "path": str(executable)})
    if expected["isolated_venv"] and not observed["isolated_venv"]:
        rejections.append({"code": "INTERPRETER_OUTSIDE_ISOLATED_ENVIRONMENT",
                           "expected": "sys.prefix != sys.base_prefix",
                           "observed": {"sys_prefix": str(prefix), "sys_base_prefix": str(base_prefix)},
                           "path": str(executable)})
    if expected["isolated_venv"] and not observed["interpreter_inside_venv"]:
        rejections.append({"code": "INTERPRETER_PATH_OUTSIDE_VENV", "expected": str(prefix),
                           "observed": str(executable), "path": str(executable)})
    for name, expected_version in sorted(expected_pins.items()):
        item = {"expected_version": expected_version, "observed_version": None, "path": None}
        try:
            dist = distribution(name)
            item["observed_version"] = dist.version
            dist_path = Path(dist.locate_file("")).absolute()
            item["path"] = str(dist_path)
            if dist.version != expected_version:
                rejections.append({"code": "DISTRIBUTION_VERSION_MISMATCH", "distribution": name,
                                   "expected": expected_version, "observed": dist.version, "path": str(dist_path)})
            if expected["distributions_inside_venv"] and not is_inside(dist_path, prefix):
                rejections.append({"code": "DISTRIBUTION_OUTSIDE_ISOLATED_ENVIRONMENT", "distribution": name,
                                   "expected": str(prefix), "observed": str(dist_path), "path": str(dist_path)})
        except PackageNotFoundError:
            rejections.append({"code": "DISTRIBUTION_MISSING", "distribution": name,
                               "expected": expected_version, "observed": None, "path": None})
        observed["distributions"][name] = item
    hash_lock_name = config.get("hash_lock_file")
    if hash_lock_name:
        hash_lock_path = root_path(root, hash_lock_name)
        observed["hash_lock_file"] = str(hash_lock_path)
        observed["hash_lock_sha256"] = sha256(hash_lock_path)
        hash_pins, hashes = parse_hash_lock(hash_lock_path)
        if config.get("hash_lock_must_match_requirements", True) and hash_pins != expected_pins:
            rejections.append({"code": "HASH_LOCK_PIN_MISMATCH", "expected": expected_pins,
                               "observed": hash_pins, "path": str(hash_lock_path)})
        missing_hashes = sorted(name for name in expected_pins if not hashes.get(name))
        if missing_hashes:
            rejections.append({"code": "HASH_PIN_MISSING", "expected": "sha256 hash for every locked distribution",
                               "observed": missing_hashes, "path": str(hash_lock_path)})
    return {"checked_at": utc_now(), "accepted": not rejections, "expected": expected,
            "observed": observed, "rejections": rejections}


class JUnitValidationError(ValueError):
    def __init__(self, code, message, details=None):
        super().__init__(message)
        self.code = code
        self.details = details or []


def junit_identity(testcase):
    return {
        "classname": testcase.attrib.get("classname"),
        "name": testcase.attrib.get("name"),
        "file": testcase.attrib.get("file"),
    }


def identity_key(identity):
    return identity["classname"], identity["name"], identity.get("file")


def raw_outcome(testcase):
    outcomes = [kind for kind in ("failure", "error", "skipped") if testcase.find(kind) is not None]
    if len(outcomes) > 1:
        raise JUnitValidationError("INCONSISTENT_TESTCASE_OUTCOME",
                                   "A testcase contains mutually inconsistent outcome nodes.",
                                   [{"identity": junit_identity(testcase), "outcomes": outcomes}])
    for kind in ("failure", "error", "skipped"):
        if len(testcase.findall(kind)) > 1:
            raise JUnitValidationError("INCONSISTENT_TESTCASE_OUTCOME",
                                       "A testcase contains repeated outcome nodes.",
                                       [{"identity": junit_identity(testcase), "outcome": kind}])
    return outcomes[0] if outcomes else "passed"


def validate_junit_bytes(report_bytes):
    try:
        root = ET.fromstring(report_bytes)
    except ET.ParseError:
        raise
    testcases = list(root.iter("testcase"))
    if not testcases:
        raise JUnitValidationError("NO_TESTCASE_NODES", "The current JUnit report has no testcase nodes.")
    identities = []
    seen = {}
    for testcase in testcases:
        identity = junit_identity(testcase)
        if not identity["classname"] or not identity["name"]:
            raise JUnitValidationError("MISSING_TESTCASE_IDENTITY",
                                       "Every raw testcase must have non-empty classname and name before lookup.",
                                       [identity])
        if "file" in testcase.attrib and not identity["file"]:
            raise JUnitValidationError("MISSING_TESTCASE_IDENTITY", "A testcase file attribute is empty.", [identity])
        raw_outcome(testcase)
        key = identity_key(identity)
        if key in seen:
            raise JUnitValidationError("DUPLICATE_TESTCASE_IDENTITY",
                                       "Duplicate raw testcase identities are rejected before any lookup or deduplication.",
                                       [identity, {"outcomes": [raw_outcome(seen[key]), raw_outcome(testcase)]}])
        seen[key] = testcase
        identities.append(identity)
    for suite in root.iter("testsuite"):
        direct = list(suite.findall("testcase"))
        if not direct:
            continue
        outcomes = [raw_outcome(testcase) for testcase in direct]
        expected = {
            "tests": len(direct),
            "failures": sum(outcome == "failure" for outcome in outcomes),
            "errors": sum(outcome == "error" for outcome in outcomes),
            "skipped": sum(outcome == "skipped" for outcome in outcomes),
        }
        observed = {}
        for counter, expected_value in expected.items():
            raw = suite.attrib.get(counter)
            try:
                observed[counter] = int(raw)
            except (TypeError, ValueError):
                raise JUnitValidationError("INVALID_JUNIT_COUNTERS",
                                           "Testsuite counters must be integers.",
                                           [{"suite": suite.attrib.get("name"), "counter": counter, "raw": raw}])
            if observed[counter] != expected_value:
                raise JUnitValidationError("JUNIT_COUNTER_MISMATCH",
                                           "Testsuite counters do not match the raw testcase nodes.",
                                           [{"suite": suite.attrib.get("name"), "expected": expected, "observed": observed}])
    return testcases


def case_status(testcase):
    outcome = raw_outcome(testcase)
    return "failed" if outcome in {"failure", "error"} else outcome


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
            details.append({"kind": kind, "message": element.attrib.get("message"), "text": element.text})
    return {"assertion_observation": "not recorded", "details": details}


def initial_counts(candidate):
    return {"candidate": candidate, "executed": 0, "passed": 0, "failed": 0,
            "blocked": 0, "skipped": 0, "not_run": candidate}


def output_text(value):
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    parser.add_argument("--manifest")
    parser.add_argument("--test-path")
    parser.add_argument("--node-selection")
    parser.add_argument("--requirements")
    parser.add_argument("--runtime-config", default="runtime_gate.json")
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    parser.add_argument("--report-wait-seconds", type=float, default=2.0)
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parents[1]
    suite = args.suite
    run_id = uuid.uuid4().hex
    evidence_parent = root / "evidence" / "runs" / suite
    evidence_parent.mkdir(parents=True, exist_ok=True)
    evidence = evidence_parent / run_id
    try:
        evidence.mkdir(exist_ok=False)
    except FileExistsError:
        error = {"stage": "evidence_preflight", "type": "NonExclusiveRunDirectory",
                 "code": "NON_EXCLUSIVE_RUN_DIRECTORY", "run_id": run_id,
                 "message": "The UUID run directory already exists; refusing to modify or reuse it."}
        print(json.dumps({"suite": suite, "run_id": run_id, "status": "harness_error", "accepted": False,
                          "pytest_exit_code": None, "runner_exit_code": 3,
                          "reject_reasons": [error["code"]], "harness_error": error}))
        return 3

    junit_path = evidence / f"junit-{run_id}.xml"
    junit_work_dir = Path(tempfile.mkdtemp(prefix=f"swarm-r4-{run_id}-"))
    junit_work_path = junit_work_dir / junit_path.name
    started_at = utc_now()
    launch_wall_ns = time.time_ns()
    completed = None
    harness_error = None
    stdout = ""
    stderr = ""
    command = []
    manifest = {"source": {"component": "unavailable"}, "cases": []}
    manifest_path = None
    test_path = None
    requirements_path = None
    runtime_config_path = None
    before_hashes = {}
    after_hashes = {}
    freeze_record = {"recorded_at": started_at, "run_id": run_id, "manifest_sha256": None, "source_sha256": {}}
    runtime_record = {"checked_at": started_at, "accepted": False, "rejections": []}
    junit_cases = None
    report_visibility_waited = 0.0
    mapping_rejections = []

    try:
        manifest_path = root_path(root, args.manifest or f"case_manifest.{suite}.json")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        execution = manifest.get("execution")
        if not execution or not execution.get("test_path"):
            raise ValueError("Manifest execution.test_path is required; no unfrozen default is permitted.")
        test_path = root_path(root, execution["test_path"])
        requirements_path = root_path(root, args.requirements or f"requirements-{suite}.lock")
        runtime_config_path = root_path(root, args.runtime_config)
        if args.test_path is not None:
            override = root_path(root, args.test_path)
            if override != test_path:
                raise ValueError(f"UNFROZEN_TEST_PATH_OVERRIDE: {args.test_path} != {execution['test_path']}")
        manifest_selection = execution.get("node_selection") or None
        if args.node_selection is not None and args.node_selection != manifest_selection:
            raise ValueError(f"UNFROZEN_NODE_SELECTION_OVERRIDE: {args.node_selection} != {manifest_selection}")
        selection = args.node_selection if args.node_selection is not None else manifest_selection
        selected_hashes = selected_source_hashes(root, test_path)
        expected_hashes = execution.get("frozen_source_sha256")
        if not isinstance(expected_hashes, dict) or not expected_hashes:
            raise ValueError("Manifest execution.frozen_source_sha256 must bind every selected source file.")
        if selected_hashes != expected_hashes:
            harness_error = {"stage": "source_preflight", "type": "FrozenSourceMismatch",
                             "code": "FROZEN_SOURCE_MISMATCH",
                             "message": "Selected source hashes differ from the manifest before launch.",
                             "expected": expected_hashes, "observed": selected_hashes}
        case_ids = [case.get("case_id") for case in manifest["cases"]]
        if any(not isinstance(case_id, str) or not case_id for case_id in case_ids):
            raise ValueError("Every manifest case must have a non-empty string case_id.")
        duplicate_case_ids = sorted({case_id for case_id in case_ids if case_ids.count(case_id) > 1})
        if duplicate_case_ids:
            raise ValueError(f"DUPLICATE_CASE_ID: {duplicate_case_ids}")
        for case in manifest["cases"]:
            identity = case.get("junit_identity")
            if not identity or not identity.get("classname") or not identity.get("name"):
                raise ValueError(f"Manifest case {case.get('case_id')} lacks a complete JUnit identity.")
        before_hashes = source_hashes(root, suite, manifest_path, test_path)
        freeze_record = {"recorded_at": utc_now(), "run_id": run_id,
                         "manifest_sha256": sha256(manifest_path), "source_sha256": before_hashes,
                         "effective_test_path": relative_path(root, test_path),
                         "effective_node_selection": selection,
                         "selected_source_sha256": selected_hashes}
        command_target = relative_path(root, test_path) + (f"::{selection}" if selection else "")
        command = [sys.executable, "-m", "pytest", command_target, "-vv", "--junitxml", str(junit_work_path)]
    except Exception as exc:
        if harness_error is None:
            code = "DUPLICATE_CASE_ID" if str(exc).startswith("DUPLICATE_CASE_ID") else ("FROZEN_EXECUTION_MISMATCH" if str(exc).startswith(("UNFROZEN_TEST_PATH_OVERRIDE", "UNFROZEN_NODE_SELECTION_OVERRIDE", "Path escapes frozen source root")) else "INPUT_PREFLIGHT_ERROR")
            harness_error = {"stage": "input_preflight", "type": type(exc).__name__, "code": code, "message": str(exc)}

    runtime_frozen_paths = [path for path in (runtime_config_path, requirements_path) if path is not None]
    try:
        if runtime_config_path is None or requirements_path is None:
            raise ValueError("Runtime config and requirements could not be resolved inside the frozen root.")
        runtime_config_data = json.loads(runtime_config_path.read_text(encoding="utf-8"))
        if runtime_config_data.get("hash_lock_file"):
            runtime_frozen_paths.append(root_path(root, runtime_config_data["hash_lock_file"]))
        runtime_record = runtime_pin_gate(root, runtime_config_path, requirements_path)
        before_hashes = extend_frozen_hashes(root, before_hashes, runtime_frozen_paths)
        freeze_record["source_sha256"] = before_hashes
        freeze_record["runtime_frozen_files"] = [relative_path(root, path) for path in runtime_frozen_paths]
    except Exception as exc:
        runtime_record = {"checked_at": utc_now(), "accepted": False, "rejections": [
            {"code": "RUNTIME_CONFIG_ERROR", "type": type(exc).__name__, "message": str(exc)}]}
        if harness_error is None:
            harness_error = {"stage": "runtime_preflight", "type": type(exc).__name__,
                             "code": "RUNTIME_CONFIG_ERROR", "message": str(exc)}

    write_json(evidence / "pre_execution_freeze.json", freeze_record)
    packages = {dist.metadata["Name"]: dist.version for dist in distributions() if dist.metadata.get("Name")}
    environment = {"python_version": sys.version, "python_executable": sys.executable,
                   "implementation": platform.python_implementation(), "platform": platform.platform(),
                   "system": platform.system(), "machine": platform.machine(), "os_name": os.name,
                   "packages": dict(sorted(packages.items(), key=lambda item: item[0].lower())),
                   "requirements_file": str(requirements_path) if requirements_path else None,
                   "runtime_config": str(runtime_config_path) if runtime_config_path else None}
    write_json(evidence / "environment.json", environment)
    write_json(evidence / "runtime_gate.json", runtime_record)

    frozen_input_paths = set(runtime_frozen_paths)
    if manifest_path is not None:
        frozen_input_paths.add(manifest_path)
    for relative in freeze_record.get("selected_source_sha256", {}):
        frozen_input_paths.add(root / relative)
    for path in sorted(frozen_input_paths):
        if path.is_file() and is_inside(path.resolve(), root.resolve()):
            destination = evidence / "frozen-inputs" / relative_path(root, path)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(path.read_bytes())

    if harness_error is None and not runtime_record["accepted"]:
        harness_error = {"stage": "runtime_preflight", "type": "RuntimePinGateError",
                         "code": "RUNTIME_PIN_GATE_FAILED",
                         "message": "Runtime pin gate rejected execution before pytest launch.",
                         "rejections": runtime_record["rejections"]}
    if harness_error is None and (junit_path.exists() or junit_work_path.exists()):
        harness_error = {"stage": "junit_preflight", "type": "NonCurrentJUnitReport",
                         "code": "NON_CURRENT_JUNIT_REPORT",
                         "message": "A per-run JUnit path already existed; refusing to execute or read it."}
    if harness_error is None:
        try:
            completed = subprocess.run(command, cwd=root, text=True, encoding="utf-8", capture_output=True,
                                       timeout=args.timeout_seconds, check=False)
            stdout = output_text(completed.stdout)
            stderr = output_text(completed.stderr)
        except subprocess.TimeoutExpired as exc:
            harness_error = {"stage": "subprocess_timeout", "type": type(exc).__name__,
                             "code": "SUBPROCESS_TIMEOUT", "message": str(exc), "timeout_seconds": args.timeout_seconds}
            stdout = output_text(exc.stdout)
            stderr = output_text(exc.stderr)
        except OSError as exc:
            harness_error = {"stage": "subprocess_startup", "type": type(exc).__name__,
                             "code": "SUBPROCESS_STARTUP_ERROR", "message": str(exc)}
        except Exception as exc:
            harness_error = {"stage": "subprocess_launch", "type": type(exc).__name__,
                             "code": "SUBPROCESS_LAUNCH_ERROR", "message": str(exc)}

    ended_at = utc_now()
    (evidence / "pytest.stdout.txt").write_text(stdout, encoding="utf-8")
    (evidence / "pytest.stderr.txt").write_text(stderr, encoding="utf-8")

    if harness_error is None:
        wait_started = time.monotonic()
        deadline = wait_started + args.report_wait_seconds
        while not junit_work_path.is_file() and time.monotonic() < deadline:
            time.sleep(0.05)
        report_visibility_waited = time.monotonic() - wait_started
        try:
            if not junit_work_path.is_file():
                raise FileNotFoundError(f"Pytest exited but did not create the unique report: {junit_work_path.name}")
            launch_second_floor_ns = launch_wall_ns - (launch_wall_ns % 1_000_000_000)
            if junit_work_path.stat().st_mtime_ns < launch_second_floor_ns:
                raise ValueError("JUnit report predates this run; refusing to read it.")
            report_bytes = junit_work_path.read_bytes()
            junit_path.write_bytes(report_bytes)
            if junit_path.read_bytes() != report_bytes:
                raise OSError("Current JUnit readback differs from the bytes produced by pytest.")
            junit_cases = validate_junit_bytes(report_bytes)
        except JUnitValidationError as exc:
            harness_error = {"stage": "junit_validation", "type": type(exc).__name__, "code": exc.code,
                             "message": str(exc), "details": exc.details}
        except Exception as exc:
            harness_error = {"stage": "junit_validation", "type": type(exc).__name__,
                             "code": "JUNIT_READ_OR_PARSE_ERROR", "message": str(exc)}

    counts = initial_counts(len(manifest["cases"]))
    evidence_cases = []
    if harness_error is not None:
        for case in manifest["cases"]:
            evidence_cases.append({"case_id": case["case_id"], "category": case["category"],
                                   "test_name": case["test_name"], "required": case.get("required", True),
                                   "fixed_input": case["fixed_input"], "expected": case["expected"],
                                   "observed": {"harness_error": harness_error}, "status": "NOT RUN",
                                   "duration_seconds": None})
    else:
        expected_by_key = {identity_key(case["junit_identity"]): case for case in manifest["cases"]}
        actual_by_key = {identity_key(junit_identity(testcase)): testcase for testcase in junit_cases}
        for key, testcase in actual_by_key.items():
            if key not in expected_by_key:
                mapping_rejections.append({"code": "UNKNOWN_TESTCASE_IDENTITY", "identity": junit_identity(testcase)})
        counts["not_run"] = 0
        for case in manifest["cases"]:
            key = identity_key(case["junit_identity"])
            testcase = actual_by_key.get(key)
            if testcase is None:
                status = "blocked"
                counts["blocked"] += 1
                observed = {"reason": "No exact classname/name/file identity in the current unique JUnit report.",
                            "expected_identity": case["junit_identity"]}
                duration = None
                mapping_rejections.append({"code": "MISSING_TESTCASE_IDENTITY", "case_id": case["case_id"],
                                           "expected_identity": case["junit_identity"]})
            else:
                status = case_status(testcase)
                observed = observed_value(testcase)
                duration = float(testcase.attrib.get("time", "0"))
                counts["executed"] += 1
                counts[status] += 1
            evidence_cases.append({"case_id": case["case_id"], "category": case["category"],
                                   "test_name": case["test_name"], "junit_identity": case["junit_identity"],
                                   "required": case.get("required", True), "fixed_input": case["fixed_input"],
                                   "expected": case["expected"], "observed": observed, "status": status,
                                   "duration_seconds": duration})

    process_exit_code = completed.returncode if completed is not None else None
    try:
        if manifest_path is not None and test_path is not None:
            after_hashes = source_hashes(root, suite, manifest_path, test_path)
            after_hashes = extend_frozen_hashes(root, after_hashes, runtime_frozen_paths)
    except Exception as exc:
        harness_error = harness_error or {"stage": "post_execution_freeze", "type": type(exc).__name__,
                                          "code": "POST_EXECUTION_FREEZE_ERROR", "message": str(exc)}
    manifest_after = sha256(manifest_path) if manifest_path is not None and manifest_path.is_file() else None
    frozen_unchanged = (freeze_record.get("manifest_sha256") == manifest_after and before_hashes == after_hashes
                        and bool(before_hashes))
    reject_reasons = list(mapping_rejections)
    if harness_error:
        reject_reasons.append({"code": harness_error.get("code", "HARNESS_ERROR"), "detail": harness_error})
    if not runtime_record["accepted"]:
        reject_reasons.append({"code": "RUNTIME_PIN_GATE_FAILED", "detail": runtime_record["rejections"]})
    if harness_error is None and process_exit_code != 0:
        reject_reasons.append({"code": "PYTEST_NONZERO_EXIT", "expected": 0, "observed": process_exit_code})
    required_cases = [case for case in evidence_cases if case["required"]]
    if not required_cases:
        reject_reasons.append({"code": "NO_REQUIRED_CASES", "observed": 0})
    for case in required_cases:
        if case["status"] != "passed":
            reject_reasons.append({"code": "REQUIRED_CASE_NOT_PASSED", "case_id": case["case_id"],
                                   "test_name": case["test_name"], "expected": "passed", "observed": case["status"]})
    if not frozen_unchanged:
        reject_reasons.append({"code": "FROZEN_INPUTS_CHANGED", "manifest_before": freeze_record.get("manifest_sha256"),
                               "manifest_after": manifest_after, "source_before": before_hashes, "source_after": after_hashes})
    accepted = not reject_reasons
    runner_exit_code = 0 if accepted else (3 if harness_error else 4)
    status = "accepted" if accepted else ("harness_error" if harness_error else "rejected")
    acceptance = {"accepted": accepted, "runner_exit_code": runner_exit_code, "pytest_exit_code": process_exit_code,
                  "reject_reasons": reject_reasons, "required_case_count": len(required_cases),
                  "runtime_gate_accepted": runtime_record["accepted"], "frozen_inputs_unchanged": frozen_unchanged}
    run_metadata = {"queue": "SWARM-CLI-01-hardening-r4-fix1", "suite": suite, "run_id": run_id,
                    "evidence_dir": str(evidence.relative_to(root)), "status": status, "accepted": accepted,
                    "acceptance": acceptance, "harness_error": harness_error,
                    "claim_scope": "harness integration controls only; not Swarm Native PASS or SIT PASS",
                    "existing_116_denominator_changed": False, "source": manifest["source"],
                    "started_at": started_at, "ended_at": ended_at, "command_argv": command,
                    "command_shell": shlex.join(command) if command else None, "working_directory": str(root),
                    "timeout_seconds": args.timeout_seconds, "report_wait_seconds": args.report_wait_seconds,
                    "report_visibility_waited_seconds": report_visibility_waited,
                    "exit_code": process_exit_code, "pytest_exit_code": process_exit_code,
                    "runner_exit_code": runner_exit_code, "stdout_file": "pytest.stdout.txt",
                    "stderr_file": "pytest.stderr.txt", "stdout": stdout, "stderr": stderr,
                    "junit_report": junit_path.name, "junit_work_report": str(junit_work_path),
                    "junit_report_read": junit_cases is not None,
                    "manifest_sha256_before": freeze_record.get("manifest_sha256"), "manifest_sha256_after": manifest_after,
                    "source_sha256_before": before_hashes, "source_sha256_after": after_hashes,
                    "frozen_inputs_unchanged": frozen_unchanged, "summary_counts": counts,
                    "runtime_gate_file": "runtime_gate.json"}
    write_json(evidence / "run_metadata.json", run_metadata)
    case_document = {"queue": "SWARM-CLI-01-hardening-r4-fix1", "suite": suite, "run_id": run_id,
                     "status": status, "accepted": accepted, "started_at": started_at, "ended_at": ended_at,
                     "command_shell": run_metadata["command_shell"], "process_exit_code": process_exit_code,
                     "runner_exit_code": runner_exit_code, "summary_counts": counts, "cases": evidence_cases}
    write_json(evidence / "case_evidence.json", case_document)
    lines = [f"# {suite} 實際執行摘要", "", f"- Run ID：`{run_id}`", f"- 開始（UTC）：`{started_at}`",
             f"- 結束（UTC）：`{ended_at}`", f"- 原始命令：`{run_metadata['command_shell']}`",
             f"- Observed pytest exit code：`{process_exit_code}`", f"- Runner acceptance exit code：`{runner_exit_code}`",
             f"- Accepted：`{accepted}`", f"- Status：`{status}`",
             f"- Reject reasons：`{json.dumps(reject_reasons, ensure_ascii=False)}`",
             f"- Runtime pin gate：`{runtime_record['accepted']}`", f"- 凍結輸入未變更：`{frozen_unchanged}`",
             "- 宣稱範圍：僅 harness integration controls；不是 Swarm Native PASS 或 SIT PASS。",
             "- 既有 116 denominator：未變更。", "",
             "| candidate | executed | passed | failed | blocked | skipped | NOT RUN |", "|---:|---:|---:|---:|---:|---:|---:|",
             "| {candidate} | {executed} | {passed} | {failed} | {blocked} | {skipped} | {not_run} |".format(**counts),
             "", "| Case | 類別 | Required | 狀態 |", "|---|---|---|---|"]
    lines.extend(f"| {case['case_id']} | {case['category']} | {case['required']} | {case['status']} |" for case in evidence_cases)
    (evidence / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    checksum_paths = [path for path in evidence.rglob("*") if path.is_file() and path.name != "SHA256SUMS.txt"]
    checksum_lines = [f"{sha256(path)}  {path.relative_to(evidence)}" for path in sorted(checksum_paths)]
    (evidence / "SHA256SUMS.txt").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    latest_dir = root / "evidence" / "latest"
    latest_dir.mkdir(parents=True, exist_ok=True)
    latest_tmp = latest_dir / f".{suite}-{run_id}.tmp"
    latest = {"suite": suite, "run_id": run_id, "evidence_dir": str(evidence.relative_to(root)),
              "run_metadata_sha256": sha256(evidence / "run_metadata.json"), "convenience_only": True,
              "acceptance_evidence_source": False}
    write_json(latest_tmp, latest)
    os.replace(latest_tmp, latest_dir / f"{suite}.json")
    print(json.dumps({"suite": suite, "run_id": run_id, "evidence_dir": str(evidence.relative_to(root)),
                      "status": status, "accepted": accepted, "pytest_exit_code": process_exit_code,
                      "runner_exit_code": runner_exit_code,
                      "reject_reasons": [reason["code"] for reason in reject_reasons], **counts}))
    return runner_exit_code


if __name__ == "__main__":
    raise SystemExit(main())
