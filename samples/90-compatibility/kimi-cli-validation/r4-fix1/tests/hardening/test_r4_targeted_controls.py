import concurrent.futures
import json
from pathlib import Path
import subprocess

import pytest

from scripts import run_suite
from tests.hardening.control_support import (
    ROOT, directory_hashes, invoke, invoke_subprocess, load_result,
    record_invocation, reject_codes, runtime_config, stage_control,
)


DUPLICATE_COLLECTION_HOOK = (
    "def pytest_collection_modifyitems(items):\n"
    "    items.append(items[0])\n"
)


def duplicate_source(mode):
    if mode == "pass-pass":
        assertion = "assert True"
    elif mode == "pass-fail":
        assertion = "assert calls != 2"
    else:
        assertion = "assert calls != 1"
    return (
        "calls = 0\n"
        "def test_control():\n"
        "    global calls\n"
        "    calls += 1\n"
        f"    {assertion}\n"
    )


def assert_harness_rejection(invocation, code):
    evidence, metadata, _ = load_result(invocation)
    assert invocation.code == 3
    assert metadata["accepted"] is False
    assert metadata["harness_error"]["code"] == code
    assert code in reject_codes(metadata)
    assert metadata["summary_counts"]["passed"] == 0
    return evidence, metadata


def inject_report(monkeypatch, report_bytes, exit_code=0):
    real_run = subprocess.run

    def fake_run(command, **kwargs):
        if "--junitxml" not in command:
            return real_run(command, **kwargs)
        report = Path(command[command.index("--junitxml") + 1])
        report.write_bytes(report_bytes)
        return subprocess.CompletedProcess(command, exit_code, "injected raw JUnit control", "")
    monkeypatch.setattr(run_suite.subprocess, "run", fake_run)


@pytest.mark.parametrize("mode,label", [
    ("pass-pass", "r4-401-duplicate-pass-pass"),
    ("pass-fail", "r4-402-duplicate-pass-fail"),
    ("fail-pass", "r4-403-duplicate-fail-pass"),
])
def test_r4_401_403_duplicate_raw_testcases_rejected(record_property, mode, label):
    manifest, _ = stage_control(label, source=duplicate_source(mode), conftest=DUPLICATE_COLLECTION_HOOK)
    result = invoke_subprocess(manifest, label)
    evidence, metadata = assert_harness_rejection(result, "DUPLICATE_TESTCASE_IDENTITY")
    raw = (evidence / metadata["junit_report"]).read_bytes()
    assert raw.count(b"<testcase ") == 2
    record_invocation(record_property, label, result, {"raw_testcase_node_count": 2})


def test_r4_404_same_name_wrong_classname_rejected(record_property):
    label = "r4-404-classname-mismatch"
    manifest, _ = stage_control(label, classname="wrong.Classname")
    result = invoke_subprocess(manifest, label)
    assert result == 4
    _, metadata, cases = load_result(result)
    assert metadata["accepted"] is False
    assert {"MISSING_TESTCASE_IDENTITY", "UNKNOWN_TESTCASE_IDENTITY"} <= reject_codes(metadata)
    assert cases["cases"][0]["status"] == "blocked"
    record_invocation(record_property, label, result)


def test_r4_405_same_name_wrong_file_rejected(record_property):
    label = "r4-405-file-mismatch"
    manifest, _ = stage_control(label, file_override="tests/r4_control_inputs/wrong.py")
    result = invoke_subprocess(manifest, label)
    assert result == 4
    _, metadata, cases = load_result(result)
    assert {"MISSING_TESTCASE_IDENTITY", "UNKNOWN_TESTCASE_IDENTITY"} <= reject_codes(metadata)
    assert cases["cases"][0]["status"] == "blocked"
    record_invocation(record_property, label, result)


def test_r4_406_unknown_identity_rejected(record_property):
    label = "r4-406-unknown-identity"
    manifest, _ = stage_control(label, source="def test_other():\n    assert True\n")
    result = invoke_subprocess(manifest, label)
    assert result == 4
    _, metadata, cases = load_result(result)
    assert {"MISSING_TESTCASE_IDENTITY", "UNKNOWN_TESTCASE_IDENTITY"} <= reject_codes(metadata)
    assert cases["cases"][0]["status"] == "blocked"
    record_invocation(record_property, label, result)


def test_r4_407_missing_raw_identity_rejected(record_property, monkeypatch):
    label = "r4-407-missing-raw-identity"
    manifest, _ = stage_control(label)
    report = b'<testsuite name="pytest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test_control" file="x.py" /></testsuite>'
    inject_report(monkeypatch, report)
    result = invoke(manifest, label)
    assert_harness_rejection(result, "MISSING_TESTCASE_IDENTITY")
    record_invocation(record_property, label, result, {"injected_raw_junit": report.decode()})


def test_r4_408_counters_mismatch_rejected(record_property, monkeypatch):
    label = "r4-408-counter-mismatch"
    manifest, control = stage_control(label)
    case = json.loads(manifest.read_text())["cases"][0]
    identity = case["junit_identity"]
    report = (f'<testsuite name="pytest" tests="2" failures="0" errors="0" skipped="0">'
              f'<testcase classname="{identity["classname"]}" name="test_control" file="{identity["file"]}" />'
              '</testsuite>').encode()
    inject_report(monkeypatch, report)
    result = invoke(manifest, label)
    assert_harness_rejection(result, "JUNIT_COUNTER_MISMATCH")
    record_invocation(record_property, label, result, {"injected_raw_junit": report.decode()})


def test_r4_409_inconsistent_outcomes_rejected(record_property, monkeypatch):
    label = "r4-409-inconsistent-outcomes"
    manifest, _ = stage_control(label)
    case = json.loads(manifest.read_text())["cases"][0]
    identity = case["junit_identity"]
    report = (f'<testsuite name="pytest" tests="1" failures="1" errors="0" skipped="1">'
              f'<testcase classname="{identity["classname"]}" name="test_control" file="{identity["file"]}">'
              '<failure message="x">failure</failure><skipped message="y">skip</skipped></testcase></testsuite>').encode()
    inject_report(monkeypatch, report)
    result = invoke(manifest, label)
    assert_harness_rejection(result, "INCONSISTENT_TESTCASE_OUTCOME")
    record_invocation(record_property, label, result, {"injected_raw_junit": report.decode()})


def test_r4_410_unique_valid_testcase_accepted(record_property):
    label = "r4-410-unique-valid"
    manifest, _ = stage_control(label)
    result = invoke_subprocess(manifest, label)
    assert result == 0
    _, metadata, _ = load_result(result)
    assert metadata["accepted"] is True
    assert metadata["summary_counts"]["passed"] == 1
    record_invocation(record_property, label, result)


def test_r4_411_external_unfrozen_test_path_rejected_before_launch(record_property, tmp_path):
    label = "r4-411-external-unfrozen-path"
    external = tmp_path / "test_external.py"
    external.write_text("def test_control():\n    assert True\n", encoding="utf-8")
    manifest, _ = stage_control(label)
    result = invoke_subprocess(manifest, label, test_path=external)
    evidence, metadata = assert_harness_rejection(result, "FROZEN_EXECUTION_MISMATCH")
    assert metadata["pytest_exit_code"] is None
    assert not (evidence / metadata["junit_report"]).exists()
    record_invocation(record_property, label, result)


def test_r4_412_source_replaced_before_launch_rejected(record_property):
    label = "r4-412-source-replaced-prelaunch"
    manifest, control = stage_control(label)
    test_file = control / "test_control.py"
    test_file.write_text("def test_control():\n    assert False, 'replaced after freeze'\n", encoding="utf-8")
    result = invoke_subprocess(manifest, label)
    evidence, metadata = assert_harness_rejection(result, "FROZEN_SOURCE_MISMATCH")
    assert metadata["pytest_exit_code"] is None
    assert not (evidence / metadata["junit_report"]).exists()
    record_invocation(record_property, label, result)


def test_r4_413_unfrozen_node_selection_rejected_before_launch(record_property):
    label = "r4-413-selection-override"
    manifest, _ = stage_control(label, node_selection="test_control")
    result = invoke_subprocess(manifest, label, node_selection="test_other")
    evidence, metadata = assert_harness_rejection(result, "FROZEN_EXECUTION_MISMATCH")
    assert metadata["pytest_exit_code"] is None
    assert not (evidence / metadata["junit_report"]).exists()
    record_invocation(record_property, label, result)


def test_r4_414_exact_frozen_selection_accepted(record_property):
    label = "r4-414-frozen-selection-valid"
    manifest, control = stage_control(label, node_selection="test_control")
    test_path = control / "test_control.py"
    result = invoke_subprocess(manifest, label, test_path=test_path, node_selection="test_control")
    assert result == 0
    _, metadata, _ = load_result(result)
    assert metadata["accepted"] is True
    assert "::test_control" in metadata["command_shell"]
    record_invocation(record_property, label, result)


def test_r4_415_sequential_runs_preserve_first_evidence(record_property):
    suite = "r4-415-sequential-same-suite"
    first_manifest, _ = stage_control(suite)
    first = invoke_subprocess(first_manifest, suite)
    assert first == 0
    first_hashes = directory_hashes(first.evidence)
    second_manifest, _ = stage_control(suite)
    second = invoke_subprocess(second_manifest, suite)
    assert second == 0
    assert first.run_id != second.run_id
    assert first.evidence != second.evidence
    assert directory_hashes(first.evidence) == first_hashes
    first_record = record_invocation(record_property, "r4-415-first-run", first,
                                     {"bytes_unchanged_after_second": True})
    record_invocation(record_property, "r4-415-second-run", second,
                      {"first_run_id": first.run_id, "first_run_sha256": first_record["junit_sha256"]})


def test_r4_416_bounded_parallel_same_suite_uses_exclusive_runs(record_property):
    suite = "r4-416-parallel-same-suite"
    first_manifest, _ = stage_control(suite)
    second_manifest, _ = stage_control(suite)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(invoke_subprocess, first_manifest, suite),
                   executor.submit(invoke_subprocess, second_manifest, suite)]
        results = [future.result(timeout=40) for future in futures]
    assert all(result == 0 for result in results)
    assert results[0].run_id != results[1].run_id
    assert results[0].evidence != results[1].evidence
    for result in results:
        _, metadata, _ = load_result(result)
        assert metadata["run_id"] == result.run_id
        assert metadata["accepted"] is True
        assert Path(result.evidence / metadata["junit_report"]).parent == result.evidence
    record_invocation(record_property, "r4-416-parallel-a", results[0],
                      {"other_run_id": results[1].run_id, "bounded_workers": 2})
    record_invocation(record_property, "r4-416-parallel-b", results[1],
                      {"other_run_id": results[0].run_id, "bounded_workers": 2})


def test_r4_417_failed_run_cannot_borrow_successful_evidence(record_property):
    suite = "r4-417-no-history-fallback"
    success_manifest, _ = stage_control(suite)
    success = invoke_subprocess(success_manifest, suite)
    assert success == 0
    success_hashes = directory_hashes(success.evidence)
    failed_manifest, _ = stage_control(suite, source="def test_other():\n    assert True\n")
    failed = invoke_subprocess(failed_manifest, suite)
    assert failed == 4
    success_evidence, success_metadata, _ = load_result(success)
    failed_evidence, failed_metadata, failed_cases = load_result(failed)
    assert failed.run_id != success.run_id
    assert failed_metadata["accepted"] is False
    assert failed_cases["cases"][0]["status"] == "blocked"
    assert failed_metadata["summary_counts"]["passed"] == 0
    assert failed_evidence / failed_metadata["junit_report"] != success_evidence / success_metadata["junit_report"]
    assert directory_hashes(success.evidence) == success_hashes
    record_invocation(record_property, "r4-417-success-run", success,
                      {"bytes_unchanged_after_failed_run": True})
    record_invocation(record_property, "r4-417-failed-run", failed,
                      {"prior_success_run_id": success.run_id, "history_fallback_used": False})


def test_r4_418_duplicate_manifest_case_id_rejected_before_launch(record_property):
    label = "r4-418-duplicate-case-id"
    manifest, _ = stage_control(label)
    document = json.loads(manifest.read_text(encoding="utf-8"))
    document["cases"].append(dict(document["cases"][0]))
    manifest.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = invoke_subprocess(manifest, label)
    evidence, metadata = assert_harness_rejection(result, "DUPLICATE_CASE_ID")
    assert metadata["pytest_exit_code"] is None
    assert metadata["accepted"] is False
    assert metadata["summary_counts"]["candidate"] == 2
    assert metadata["summary_counts"]["not_run"] == 2
    assert not (evidence / metadata["junit_report"]).exists()
    record_invocation(record_property, label, result, {"duplicate_case_id": "R4-CONTROL"})


@pytest.mark.parametrize("kind,label", [
    ("runtime-config", "r4-419-runtime-config-mutation"),
    ("requirements-lock", "r4-420-requirements-lock-mutation"),
])
def test_r4_419_420_effective_runtime_file_mutation_rejected(record_property, kind, label):
    manifest, control = stage_control(label)
    config = runtime_config(control)
    requirements = control / "effective.lock"
    requirements.write_bytes((ROOT / "requirements-click.lock").read_bytes())
    filename = "runtime.json" if kind == "runtime-config" else "effective.lock"
    source = (
        "from pathlib import Path\n"
        "def test_control():\n"
        f"    target = Path(__file__).with_name({filename!r})\n"
        "    target.write_bytes(target.read_bytes() + b'\\n# post-freeze mutation\\n')\n"
    )
    (control / "test_control.py").write_text(source, encoding="utf-8")
    document = json.loads(manifest.read_text(encoding="utf-8"))
    test_relative = str((control / "test_control.py").relative_to(ROOT))
    document["execution"]["frozen_source_sha256"][test_relative] = run_suite.sha256(control / "test_control.py")
    manifest.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = invoke_subprocess(manifest, label, requirements, config)
    assert result == 4
    evidence, metadata, cases = load_result(result)
    assert metadata["accepted"] is False
    assert metadata["pytest_exit_code"] == 0
    assert metadata["harness_error"] is None
    assert "FROZEN_INPUTS_CHANGED" in reject_codes(metadata)
    assert metadata["summary_counts"]["passed"] == 1
    assert cases["cases"][0]["status"] == "passed"
    effective_relative = str((control / filename).relative_to(ROOT))
    assert effective_relative in metadata["source_sha256_before"]
    assert effective_relative in metadata["source_sha256_after"]
    assert metadata["source_sha256_before"][effective_relative] != metadata["source_sha256_after"][effective_relative]
    record_invocation(record_property, label, result, {
        "effective_mutated_file": effective_relative,
        "before_sha256": metadata["source_sha256_before"][effective_relative],
        "after_sha256": metadata["source_sha256_after"][effective_relative],
    })
