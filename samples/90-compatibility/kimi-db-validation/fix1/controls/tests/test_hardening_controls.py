from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from junit_policy import validate_junit
from process_policy import group_members, run_bounded_subprocess
from run_common import collect_case_records
from source_policy import create_snapshot, verify_against_snapshot
from verify_runtime import encoded_sha256, verify, verify_distribution

RUNTIME = Path(os.environ["SWARM_DB02_RUNTIME"])
WHEELHOUSE = Path(os.environ["SWARM_DB02_WHEELHOUSE"])
LOCK = ROOT / "requirements.lock"
TOKEN = "test-token"
MANIFEST_HASH = "manifest-hash"
SOURCE_DIGEST = "source-digest"
BINDING = {"cases": {
    "A": {"classname": "sample", "name": "test_a"},
    "B": {"classname": "sample", "name": "test_b"},
}}


def artifact(name: str) -> Path:
    return Path(os.environ["SWARM_DB02_CASE_OUTPUT_DIR"]).parent / name


def record_row(name: str, raw: bytes | None) -> str:
    if raw is None:
        return f"{name},,"
    return f"{name},sha256={encoded_sha256(raw)},{len(raw)}"


def make_record_fixture(path: Path, *, pyc: bool = False, installer: bool = False) -> tuple[Path, Path, dict]:
    runtime = path / "runtime"
    dist_info = runtime / "fixture-1.0.dist-info"
    dist_info.mkdir(parents=True)
    source = b"VALUE = 1\n"
    meta = b"Metadata-Version: 2.1\nName: fixture\nVersion: 1.0\n"
    wheel_meta = b"Wheel-Version: 1.0\nGenerator: fixture\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
    (runtime / "pkg").mkdir()
    (runtime / "pkg/__init__.py").write_bytes(source)
    (dist_info / "METADATA").write_bytes(meta)
    (dist_info / "WHEEL").write_bytes(wheel_meta)
    files = {"pkg/__init__.py": source, "fixture-1.0.dist-info/METADATA": meta, "fixture-1.0.dist-info/WHEEL": wheel_meta}
    rows = [record_row(name, raw) for name, raw in files.items()]
    rows.append("fixture-1.0.dist-info/RECORD,,")
    if pyc:
        generated = importlib.util.MAGIC_NUMBER + b"\0" * 12
        pyc_path = runtime / "pkg/__pycache__/__init__.cpython-312.pyc"
        pyc_path.parent.mkdir()
        pyc_path.write_bytes(generated)
        rows.append("pkg/__pycache__/__init__.cpython-312.pyc,,")
    if installer:
        (dist_info / "INSTALLER").write_bytes(b"pip\n")
        (dist_info / "REQUESTED").write_bytes(b"")
        rows.extend(record_row(name, raw) for name, raw in {
            "fixture-1.0.dist-info/INSTALLER": b"pip\n", "fixture-1.0.dist-info/REQUESTED": b"",
        }.items())
    (dist_info / "RECORD").write_text("\n".join(rows) + "\n")
    wheel = path / "fixture-1.0-py3-none-any.whl"
    with zipfile.ZipFile(wheel, "w") as archive:
        for name, raw in files.items():
            archive.writestr(name, raw)
        archive.writestr("fixture-1.0.dist-info/RECORD", "\n".join(record_row(name, raw) for name, raw in files.items()) + "\nfixture-1.0.dist-info/RECORD,,\n")
    locked = {"name": "fixture", "version": "1.0", "wheel_sha256": hashlib.sha256(wheel.read_bytes()).hexdigest()}
    return runtime, wheel, locked


def junit(path: Path, nodes: list[tuple[str, str, str | None]], counters: dict | None = None) -> None:
    observed = {
        "tests": len(nodes),
        "failures": sum(outcome == "failure" for _, _, outcome in nodes),
        "errors": sum(outcome == "error" for _, _, outcome in nodes),
        "skipped": sum(outcome == "skipped" for _, _, outcome in nodes),
    }
    declared = observed if counters is None else counters
    body = []
    for classname, name, outcome in nodes:
        child = "" if outcome is None else f"<{outcome} message='{outcome}'/>"
        body.append(f"<testcase classname='{classname}' name='{name}'>{child}</testcase>")
    properties = (
        f"<properties><property name='run_token' value='{TOKEN}'/>"
        f"<property name='manifest_sha256' value='{MANIFEST_HASH}'/>"
        f"<property name='source_digest' value='{SOURCE_DIGEST}'/></properties>"
    )
    path.write_text(
        f"<testsuite name='sample' tests='{declared['tests']}' failures='{declared['failures']}' "
        f"errors='{declared['errors']}' skipped='{declared['skipped']}'>{properties}{''.join(body)}</testsuite>"
    )


def validate(path: Path) -> dict:
    return validate_junit(path, BINDING, TOKEN, MANIFEST_HASH, SOURCE_DIGEST)


@pytest.mark.case_id("HR2-RECORD-EMPTY-001")
def test_record_empty_rejected(tmp_path, case):
    runtime, wheel, locked = make_record_fixture(tmp_path)
    (runtime / "fixture-1.0.dist-info/RECORD").write_bytes(b"")
    result = verify_distribution(runtime, runtime / "fixture-1.0.dist-info", locked, wheel)
    case["observed"] = {"pass": result["pass"], "empty_detected": any("empty" in failure for failure in result["failures"]), "no_hashed_detected": any("no hashed" in failure for failure in result["failures"])}


@pytest.mark.case_id("HR2-RECORD-UNHASHED-002")
def test_only_unhashed_and_dehashed_required_rejected(tmp_path, case):
    runtime, wheel, locked = make_record_fixture(tmp_path / "only")
    record = runtime / "fixture-1.0.dist-info/RECORD"
    record.write_text("fixture-1.0.dist-info/RECORD,,\n")
    only = verify_distribution(runtime, runtime / "fixture-1.0.dist-info", locked, wheel)
    runtime2, wheel2, locked2 = make_record_fixture(tmp_path / "dehash")
    record2 = runtime2 / "fixture-1.0.dist-info/RECORD"
    lines = ["pkg/__init__.py,," if line.startswith("pkg/__init__.py,") else line for line in record2.read_text().splitlines()]
    record2.write_text("\n".join(lines) + "\n")
    dehashed = verify_distribution(runtime2, runtime2 / "fixture-1.0.dist-info", locked2, wheel2)
    case["observed"] = {"only_unhashed_rejected": not only["pass"] and any("no hashed" in failure for failure in only["failures"]), "required_dehash_rejected": not dehashed["pass"] and any("dehashed" in failure for failure in dehashed["failures"])}


@pytest.mark.case_id("HR2-RECORD-COVERAGE-003")
def test_missing_row_and_changed_bytes_rejected(tmp_path, case):
    runtime, wheel, locked = make_record_fixture(tmp_path / "missing")
    record = runtime / "fixture-1.0.dist-info/RECORD"
    record.write_text("\n".join(line for line in record.read_text().splitlines() if not line.startswith("pkg/__init__.py,")) + "\n")
    missing = verify_distribution(runtime, runtime / "fixture-1.0.dist-info", locked, wheel)
    runtime2, wheel2, locked2 = make_record_fixture(tmp_path / "bytes")
    (runtime2 / "pkg/__init__.py").write_bytes(b"VALUE = 2\n")
    changed = verify_distribution(runtime2, runtime2 / "fixture-1.0.dist-info", locked2, wheel2)
    case["observed"] = {"deleted_row_rejected": not missing["pass"] and any("missing wheel-covered row" in failure for failure in missing["failures"]), "changed_bytes_rejected": not changed["pass"] and any("installed bytes differ" in failure for failure in changed["failures"])}


@pytest.mark.case_id("HR2-RECORD-EXCEPTIONS-004")
def test_valid_record_and_pyc_exceptions_positive(tmp_path, case):
    runtime, wheel, locked = make_record_fixture(tmp_path, pyc=True, installer=True)
    result = verify_distribution(runtime, runtime / "fixture-1.0.dist-info", locked, wheel)
    types = {item["type"] for item in result["exceptions"]}
    case["observed"] = {"pass": result["pass"], "wheel_files_verified": result["wheel_files_verified"], "installed_wheel_files_verified": result["installed_wheel_files_verified"], "record_self": "record_self" in types, "generated_pyc": "generated_pyc" in types, "installer_metadata": "installer_metadata" in types}


@pytest.mark.case_id("HR2-WHEEL-TRUST-005")
def test_wrong_wheel_hash_rejected(tmp_path, case):
    runtime, wheel, locked = make_record_fixture(tmp_path)
    locked["wheel_sha256"] = "0" * 64
    result = verify_distribution(runtime, runtime / "fixture-1.0.dist-info", locked, wheel)
    case["observed"] = {"pass": result["pass"], "trusted_hash_rejected": any("SHA-256" in failure for failure in result["failures"]), "wheel_files_verified": result["wheel_files_verified"]}


@pytest.mark.case_id("HR2-JUNIT-NODES-006")
def test_fake_counters_missing_duplicate_unknown_rejected(tmp_path, case):
    empty = tmp_path / "empty.xml"
    junit(empty, [], {"tests": 9, "failures": 0, "errors": 0, "skipped": 0})
    missing = tmp_path / "missing.xml"
    junit(missing, [("sample", "test_a", None)])
    duplicate = tmp_path / "duplicate.xml"
    junit(duplicate, [("sample", "test_a", None), ("sample", "test_a", None), ("sample", "test_b", None)])
    unknown = tmp_path / "unknown.xml"
    junit(unknown, [("sample", "test_a", None), ("sample", "test_b", None), ("sample", "test_c", None)])
    results = [validate(path) for path in (empty, missing, duplicate, unknown)]
    case["observed"] = {"empty_rejected": not results[0]["pass"] and results[0]["testcase_node_count"] == 0, "missing_rejected": not results[1]["pass"] and any("missing testcase" in error for error in results[1]["errors"]), "duplicate_rejected": not results[2]["pass"] and any("duplicate testcase" in error for error in results[2]["errors"]), "unknown_rejected": not results[3]["pass"] and any("unknown testcase" in error for error in results[3]["errors"])}


@pytest.mark.case_id("HR2-JUNIT-OUTCOME-007")
def test_hidden_failure_error_skip_rejected(tmp_path, case):
    paths = []
    for outcome in ("failure", "error", "skipped"):
        path = tmp_path / f"{outcome}.xml"
        junit(path, [("sample", "test_a", outcome), ("sample", "test_b", None)])
        paths.append(path)
    results = [validate(path) for path in paths]
    case["observed"] = {"failure_rejected": not results[0]["pass"], "error_rejected": not results[1]["pass"], "skipped_rejected": not results[2]["pass"], "all_outcomes_parsed": all(result["case_outcomes"]["A"] == expected for result, expected in zip(results, ("failed", "error", "skipped")))}


@pytest.mark.case_id("HR2-JUNIT-COUNTER-008")
def test_counter_node_mismatch_rejected(tmp_path, case):
    path = tmp_path / "counter.xml"
    junit(path, [("sample", "test_a", None), ("sample", "test_b", None)], {"tests": 9, "failures": 0, "errors": 0, "skipped": 0})
    result = validate(path)
    case["observed"] = {"pass": result["pass"], "counter_mismatch_detected": any("counter mismatch" in error for error in result["errors"]), "real_node_count": result["testcase_node_count"]}


@pytest.mark.case_id("HR2-JUNIT-POSITIVE-009")
def test_valid_testcase_nodes_positive(tmp_path, case):
    path = tmp_path / "positive.xml"
    junit(path, [("sample", "test_a", None), ("sample", "test_b", None)])
    result = validate(path)
    case["observed"] = {"pass": result["pass"], "tests": result["counts"]["tests"], "case_outcomes": result["case_outcomes"], "properties_match": result["properties"] == {"run_token": TOKEN, "manifest_sha256": MANIFEST_HASH, "source_digest": SOURCE_DIGEST}}


@pytest.mark.case_id("HR2-ISOLATION-AMBIENT-010")
def test_ambient_package_and_plugin_neutralized(tmp_path, case):
    ambient = tmp_path / "ambient"
    fixture = tmp_path / "fixture"
    ambient.mkdir(); fixture.mkdir()
    marker = tmp_path / "ambient-loaded"
    (ambient / "ambient_poison.py").write_text(f"from pathlib import Path\nPath({str(marker)!r}).write_text('loaded')\n")
    (fixture / "test_tiny.py").write_text("def test_ok():\n    assert True\n")
    probe = artifact("ambient-probe.json")
    report = tmp_path / "ambient.xml"
    command = [sys.executable, "-I", "-S", str(ROOT / "isolated_pytest.py"), "--runtime", str(RUNTIME), "--source-root", str(fixture), "--probe-output", str(probe), "--", "-q", "--junitxml", str(report), "test_tiny.py"]
    env = os.environ.copy()
    env.update({"PYTHONPATH": str(ambient), "PYTEST_PLUGINS": "ambient_poison", "PYTEST_ADDOPTS": "-p ambient_poison"})
    result = run_bounded_subprocess(command, cwd=fixture, env=env, stdout_path=artifact("ambient.stdout.log"), stderr_path=artifact("ambient.stderr.log"), timeout_seconds=30)
    probe_data = json.loads(probe.read_text())
    case["observed"] = {"exit_code": result["exit_code"], "probe_pass": probe_data["pass"], "ambient_marker_absent": not marker.exists(), "plugin_distinfo_empty": probe_data["plugin_distinfo"] == [], "ambient_absent_sys_path": all(str(ambient) not in value for value in probe_data["final_sys_path"]), "no_site_flags": probe_data["python_flags"]["isolated"] == 1 and probe_data["python_flags"]["no_site"] == 1}


@pytest.mark.case_id("HR2-TIMEOUT-TREE-011")
def test_timeout_kills_owned_child_and_grandchild(tmp_path, case):
    grandchild = tmp_path / "grandchild.py"
    child = tmp_path / "child.py"
    grandchild.write_text("import os,signal,time\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nprint(f'GRANDCHILD {os.getpid()}',flush=True)\ntime.sleep(60)\n")
    child.write_text("import os,signal,subprocess,sys,time\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\ng=subprocess.Popen([sys.executable,'-u',sys.argv[1]])\nprint(f'CHILD {os.getpid()} GRANDCHILD {g.pid}',flush=True)\ntime.sleep(60)\n")
    result = run_bounded_subprocess([sys.executable, "-u", str(child), str(grandchild)], cwd=tmp_path, stdout_path=artifact("timeout-tree.stdout.log"), stderr_path=artifact("timeout-tree.stderr.log"), timeout_seconds=0.3, term_grace_seconds=0.2, kill_grace_seconds=0.5)
    (artifact("timeout-tree-result.json")).write_text(json.dumps(result, indent=2) + "\n")
    logs = Path(result["stdout"]).read_text()
    case["observed"] = {"status": result["status"], "exit_code": result["exit_code"], "bounded_wall_time": result["duration_seconds"] < 5, "parent_reaped": result["cleanup"]["parent_reaped"], "no_live_owned_group_members": result["cleanup"]["live_members_after"] == [] and group_members(result["pgid"]) == [], "cleanup_pass": result["cleanup_pass"], "child_and_grandchild_started": "CHILD" in logs and "GRANDCHILD" in logs}


@pytest.mark.case_id("HR2-TIMEOUT-NORMAL-012")
def test_normal_child_grandchild_exit(tmp_path, case):
    grandchild = tmp_path / "grandchild.py"; child = tmp_path / "child.py"
    grandchild.write_text("print('GRANDCHILD DONE',flush=True)\n")
    child.write_text("import subprocess,sys\nprint('CHILD START',flush=True)\nr=subprocess.run([sys.executable,sys.argv[1]])\nprint('CHILD DONE',flush=True)\nraise SystemExit(r.returncode)\n")
    result = run_bounded_subprocess([sys.executable, "-u", str(child), str(grandchild)], cwd=tmp_path, stdout_path=artifact("timeout-normal.stdout.log"), stderr_path=artifact("timeout-normal.stderr.log"), timeout_seconds=10)
    (artifact("timeout-normal-result.json")).write_text(json.dumps(result, indent=2) + "\n")
    case["observed"] = {"status": result["status"], "exit_code": result["exit_code"], "not_timed_out": not result["timed_out"], "cleanup_pass": result["cleanup_pass"], "no_owned_group_members": group_members(result["pgid"]) == [], "grandchild_completed": "GRANDCHILD DONE" in Path(result["stdout"]).read_text()}


@pytest.mark.case_id("HR2-SOURCE-DIGEST-013")
def test_control_source_change_rejected_after_real_pytest(tmp_path, case):
    fixture = tmp_path / "fixture"; fixture.mkdir(); (fixture / "provenance").mkdir()
    test_file = fixture / "test_sample.py"
    test_file.write_text("def test_sample():\n    assert True\n")
    digest = hashlib.sha256(test_file.read_bytes()).hexdigest()
    manifest_bytes = json.dumps({"files": {"test_sample.py": digest}}, indent=2).encode()
    (fixture / "control-source-manifest.json").write_bytes(manifest_bytes)
    (fixture / "provenance/control-source-digest.sha256").write_text(hashlib.sha256(manifest_bytes).hexdigest() + "\n")
    run_dir = tmp_path / "run"; run_dir.mkdir()
    pre = create_snapshot(fixture, run_dir)
    probe = tmp_path / "probe.json"
    command = [sys.executable, "-I", "-S", str(ROOT / "isolated_pytest.py"), "--runtime", str(RUNTIME), "--source-root", str(fixture), "--probe-output", str(probe), "--", "-q", "--junitxml", str(tmp_path / "actual.xml"), "test_sample.py"]
    result = run_bounded_subprocess(command, cwd=fixture, stdout_path=artifact("source-change.stdout.log"), stderr_path=artifact("source-change.stderr.log"), timeout_seconds=30)
    test_file.write_text(test_file.read_text() + "# controlled source changed after pytest\n")
    post = verify_against_snapshot(fixture, run_dir, pre["source_digest"])
    rejection = {"actual_pytest_exit": result["exit_code"], "post_source": post, "batch_accepted": result["exit_code"] == 0 and post["pass"]}
    (artifact("source-change-rejection.json")).write_text(json.dumps(rejection, indent=2) + "\n")
    (artifact("source-change-actual.junit.xml")).write_bytes((tmp_path / "actual.xml").read_bytes())
    case["observed"] = {"actual_pytest_passed": result["exit_code"] == 0, "post_source_rejected": not post["pass"], "batch_rejected": not rejection["batch_accepted"], "reject_reason_present": any("controlled source changed" in failure or "digest" in failure for failure in post["failures"]), "snapshot_preserved": (run_dir / "source-snapshot/test_sample.py").read_bytes() != test_file.read_bytes()}


@pytest.mark.case_id("HR2-CASE-SOURCE-014")
def test_case_wrong_source_digest_rejected(tmp_path, case):
    manifest = json.loads((ROOT / "controls/manifest.json").read_text())
    binding = json.loads((ROOT / "case-binding.json").read_text())["suites"][manifest["suite"]]
    spec = manifest["cases"][0]; identity = binding["cases"][spec["case_id"]]
    case_dir = tmp_path / "case-records"; case_dir.mkdir()
    record = {"case_id": spec["case_id"], "run_token": "token", "manifest_sha256": "hash", "source_digest": "0" * 64, "junit_classname": identity["classname"], "junit_name": identity["name"], "observed": spec["expected"], "pytest_outcome": "passed"}
    (case_dir / "case.json").write_text(json.dumps(record))
    result = collect_case_records(case_dir, manifest, binding, "token", "hash", "1" * 64)
    case["observed"] = {"pass": result["pass"], "wrong_source_digest_detected": any(error.startswith("wrong_source_digest") for error in result["errors"]), "executed": result["counts"]["executed"]}


@pytest.mark.case_id("HR2-RUNTIME-POSITIVE-015")
def test_full_trusted_runtime_positive(tmp_path, case):
    result = verify(RUNTIME, LOCK, WHEELHOUSE)
    (artifact("runtime-positive.json")).write_text(json.dumps(result, indent=2) + "\n")
    case["observed"] = {"pass": result["pass"], "locked_count": result["locked_count"], "installed_count": result["installed_count"], "wheel_count": result["wheel_count"], "record_failures": result["record_failures"], "isolated_import_failures": result["isolated_import_check"]["failures"], "tzdata_pass": result["records"]["tzdata"]["pass"]}


@pytest.mark.case_id("HR2F1-PYC-016")
def test_magic_valid_changed_pyc_rejected_by_approved_inventory(tmp_path, case):
    runtime = tmp_path / "runtime"
    shutil.copytree(RUNTIME, runtime)
    source = runtime / "sqlite_utils/__init__.py"
    pyc = Path(importlib.util.cache_from_source(str(source)))
    pyc.parent.mkdir()
    raw = importlib.util.MAGIC_NUMBER + b"changed-but-not-trusted" * 4
    pyc.write_bytes(raw)
    relative = pyc.relative_to(runtime).as_posix()
    record = next(runtime.glob("sqlite_utils-*.dist-info/RECORD"))
    rows = [line for line in record.read_text().splitlines() if line.split(",", 1)[0] != relative]
    rows.append(f"{relative},,")
    record.write_text("\n".join(rows) + "\n")
    result = verify(runtime, LOCK, WHEELHOUSE)
    (artifact("fix1-pyc-rejection.json")).write_text(json.dumps(result, indent=2) + "\n")
    case["observed"] = {
        "pass": result["pass"],
        "record_exception_still_classified": result["records"]["sqlite-utils"]["pass"],
        "bytecode_rejected": relative in result["approved_importable_inventory"]["bytecode_files"] and not result["approved_importable_inventory"]["pass"],
        "magic_valid": raw[:4] == importlib.util.MAGIC_NUMBER,
    }


@pytest.mark.case_id("HR2F1-IMPORTABLE-017")
def test_unrecorded_importable_rejected_before_approved_import(tmp_path, case):
    runtime = tmp_path / "runtime"
    shutil.copytree(RUNTIME, runtime)
    marker = tmp_path / "poison-imported"
    poison = runtime / "sqlite_utils/unapproved_poison.py"
    poison.write_text(f"from pathlib import Path\nPath({str(marker)!r}).write_text('loaded')\n")
    result = verify(runtime, LOCK, WHEELHOUSE)
    (artifact("fix1-importable-rejection.json")).write_text(json.dumps(result, indent=2) + "\n")
    relative = poison.relative_to(runtime).as_posix()
    case["observed"] = {
        "pass": result["pass"],
        "unapproved_importable_rejected": relative in result["approved_importable_inventory"]["unexpected_importables"] and not result["approved_importable_inventory"]["pass"],
        "record_verification_otherwise_passed": not result["record_failures"],
        "poison_not_imported": not marker.exists(),
    }
