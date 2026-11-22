import json
from pathlib import Path
import subprocess
from types import SimpleNamespace

from scripts import run_suite
from tests.hardening.control_support import ROOT, invoke, load_result, stage_control


OLD_PASS = """<?xml version="1.0" encoding="utf-8"?>
<testsuite name="stale-pass" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="old" name="test_control" time="0.001" />
</testsuite>
"""


def assert_not_run(metadata, cases, error_type):
    assert metadata["status"] == "harness_error"
    assert metadata["harness_error"]["type"] == error_type
    assert metadata["junit_report_read"] is False
    assert metadata["summary_counts"] == {
        "candidate": 1, "executed": 0, "passed": 0, "failed": 0,
        "blocked": 0, "skipped": 0, "not_run": 1,
    }
    assert [case["status"] for case in cases["cases"]] == ["NOT RUN"]


def test_hrn_001_old_pass_report_is_ignored_when_startup_fails(tmp_path, monkeypatch):
    suite = "control-startup"
    legacy_evidence = ROOT / "evidence" / suite
    legacy_evidence.mkdir(parents=True, exist_ok=True)
    old_report = legacy_evidence / "junit.xml"
    old_report.write_text(OLD_PASS, encoding="utf-8")
    old_hash = run_suite.sha256(old_report)
    manifest, _ = stage_control(suite)

    def fail_startup(*args, **kwargs):
        raise OSError("injected subprocess startup failure")

    monkeypatch.setattr(run_suite.subprocess, "run", fail_startup)
    result = invoke(manifest, suite)
    assert result == 3
    _, metadata, cases = load_result(result)
    assert_not_run(metadata, cases, "OSError")
    assert metadata["harness_error"]["stage"] == "subprocess_startup"
    assert metadata["exit_code"] is None
    assert metadata["junit_report"] != "junit.xml"
    assert run_suite.sha256(old_report) == old_hash


def test_hrn_002_timeout_fails_closed(tmp_path, monkeypatch):
    manifest, _ = stage_control("control-timeout")

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(cmd=args[0], timeout=kwargs["timeout"], output="partial", stderr="late")

    monkeypatch.setattr(run_suite.subprocess, "run", timeout)
    result = invoke(manifest, "control-timeout")
    assert result == 3
    _, metadata, cases = load_result(result)
    assert_not_run(metadata, cases, "TimeoutExpired")
    assert metadata["harness_error"]["stage"] == "subprocess_timeout"
    assert metadata["exit_code"] is None
    assert metadata["stdout"] == "partial"
    assert metadata["stderr"] == "late"


def test_hrn_003_zero_exit_without_report_fails_closed(tmp_path, monkeypatch):
    manifest, _ = stage_control("control-missing")

    def missing_report(command, **kwargs):
        return subprocess.CompletedProcess(command, 0, "no report produced", "")

    monkeypatch.setattr(run_suite.subprocess, "run", missing_report)
    result = invoke(manifest, "control-missing")
    assert result == 3
    _, metadata, cases = load_result(result)
    assert_not_run(metadata, cases, "FileNotFoundError")
    assert metadata["exit_code"] == 0
    assert metadata["summary_counts"]["passed"] == 0


def test_hrn_004_invalid_report_fails_closed(tmp_path, monkeypatch):
    manifest, _ = stage_control("control-invalid")

    def invalid_report(command, **kwargs):
        report = Path(command[command.index("--junitxml") + 1])
        report.write_text("<not-valid-xml", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, "invalid report produced", "")

    monkeypatch.setattr(run_suite.subprocess, "run", invalid_report)
    result = invoke(manifest, "control-invalid")
    assert result == 3
    _, metadata, cases = load_result(result)
    assert_not_run(metadata, cases, "ParseError")
    assert metadata["harness_error"]["stage"] == "junit_validation"


def test_hrn_005_preexisting_unique_report_is_rejected_before_launch(tmp_path, monkeypatch):
    suite = "control-noncurrent"
    fixed_id = "00112233445566778899aabbccddeeff"
    evidence = ROOT / "evidence" / "runs" / suite / fixed_id
    evidence.mkdir(parents=True, exist_ok=False)
    stale = evidence / f"junit-{fixed_id}.xml"
    stale.write_text(OLD_PASS, encoding="utf-8")
    stale_hash = run_suite.sha256(stale)
    monkeypatch.setattr(run_suite.uuid, "uuid4", lambda: SimpleNamespace(hex=fixed_id))
    launched = False

    def must_not_launch(*args, **kwargs):
        nonlocal launched
        launched = True
        raise AssertionError("subprocess must not launch with a preexisting run directory")

    monkeypatch.setattr(run_suite.subprocess, "run", must_not_launch)
    manifest, _ = stage_control(suite)
    result = invoke(manifest, suite)
    assert result == 3
    assert result.payload["harness_error"]["type"] == "NonExclusiveRunDirectory"
    assert result.payload["harness_error"]["stage"] == "evidence_preflight"
    assert launched is False
    assert run_suite.sha256(stale) == stale_hash
    assert list(evidence.iterdir()) == [stale]


def test_hrn_006_current_valid_report_is_used_normally(tmp_path):
    manifest, _ = stage_control("control-normal", test_name="test_control_normal",
                                source="def test_control_normal():\n    assert True\n")
    result = invoke(manifest, "control-normal")
    assert result == 0
    evidence, metadata, cases = load_result(result)
    # R4 compatibility: R3 replaced the legacy completed label with explicit acceptance.
    assert metadata["status"] == "accepted"
    assert metadata["accepted"] is True
    assert metadata["harness_error"] is None
    assert metadata["junit_report_read"] is True
    assert metadata["junit_report"].startswith("junit-")
    assert metadata["junit_report"] != "junit.xml"
    assert (evidence / metadata["junit_report"]).is_file()
    assert metadata["summary_counts"] == {
        "candidate": 1, "executed": 1, "passed": 1, "failed": 0,
        "blocked": 0, "skipped": 0, "not_run": 0,
    }
    assert [case["status"] for case in cases["cases"]] == ["passed"]
