import json
from pathlib import Path

from scripts import run_suite
from tests.hardening.control_support import (
    ROOT, invoke, load_result, reject_codes, runtime_config, stage_control,
)


def assert_rejected(metadata, code):
    assert metadata["accepted"] is False
    assert metadata["acceptance"]["accepted"] is False
    assert metadata["runner_exit_code"] != 0
    assert metadata["status"] in {"rejected", "harness_error"}
    assert code in reject_codes(metadata)


def test_r3_301_pytest_zero_but_required_case_missing(tmp_path):
    manifest, _ = stage_control("control-r3-missing", source="def test_other():\n    assert True\n")
    result = invoke(manifest, "control-r3-missing")
    assert result == 4
    _, metadata, cases = load_result(result)
    assert_rejected(metadata, "REQUIRED_CASE_NOT_PASSED")
    assert "UNKNOWN_TESTCASE_IDENTITY" in reject_codes(metadata)
    assert metadata["pytest_exit_code"] == 0
    assert metadata["summary_counts"]["executed"] == 0
    assert metadata["summary_counts"]["blocked"] == 1
    assert metadata["summary_counts"]["passed"] == 0
    assert cases["cases"][0]["status"] == "blocked"


def test_r3_302_pytest_zero_but_required_case_skipped(tmp_path):
    manifest, _ = stage_control("control-r3-skipped",
                                source="import pytest\ndef test_control():\n    pytest.skip('injected skip')\n")
    result = invoke(manifest, "control-r3-skipped")
    assert result == 4
    _, metadata, cases = load_result(result)
    assert_rejected(metadata, "REQUIRED_CASE_NOT_PASSED")
    assert metadata["pytest_exit_code"] == 0
    assert metadata["summary_counts"]["executed"] == 1
    assert metadata["summary_counts"]["skipped"] == 1
    assert metadata["summary_counts"]["passed"] == 0
    assert cases["cases"][0]["status"] == "skipped"


def test_r3_303_manifest_mutation_rejects_despite_test_pass(tmp_path):
    suite = "control-r3-manifest-mutation"
    source = (
        "from pathlib import Path\n"
        "def test_control():\n"
        "    Path(__import__('json').loads(__import__('os').environ['unused']) if False else __import__('pathlib').Path(__file__).with_name('manifest.json')).write_text('{\"changed\": true}', encoding='utf-8')\n"
    )
    manifest, _ = stage_control(suite, source=source)
    result = invoke(manifest, suite)
    assert result == 4
    _, metadata, _ = load_result(result)
    assert_rejected(metadata, "FROZEN_INPUTS_CHANGED")
    assert metadata["pytest_exit_code"] == 0
    assert metadata["summary_counts"]["passed"] == 1
    assert metadata["manifest_sha256_before"] != metadata["manifest_sha256_after"]
    assert metadata["frozen_inputs_unchanged"] is False


def test_r3_304_source_mutation_rejects_despite_test_pass(tmp_path):
    suite = "control-r3-source-mutation"
    target = ROOT / "src" / "swarm_cli01" / f"{suite}_app.py"
    target.write_text("VALUE = 'frozen-before'\n", encoding="utf-8")
    source = (
        "from pathlib import Path\n"
        "def test_control():\n"
        f"    Path({str(target)!r}).write_text(\"VALUE = 'changed-after'\\n\", encoding='utf-8')\n"
    )
    try:
        manifest, _ = stage_control(suite, source=source)
        result = invoke(manifest, suite)
        assert result == 4
        _, metadata, _ = load_result(result)
        assert_rejected(metadata, "FROZEN_INPUTS_CHANGED")
        assert metadata["pytest_exit_code"] == 0
        assert metadata["summary_counts"]["passed"] == 1
        assert metadata["source_sha256_before"] != metadata["source_sha256_after"]
        assert metadata["frozen_inputs_unchanged"] is False
    finally:
        target.unlink(missing_ok=True)


def test_r3_305_runtime_distribution_missing_fails_before_launch(tmp_path):
    suite = "control-r3-runtime-missing"
    manifest, control = stage_control(suite)
    requirements = control / "missing.lock"
    requirements.write_text("swarm-r3-distribution-that-does-not-exist==1.0.0\n", encoding="utf-8")
    config = runtime_config(control)
    result = invoke(manifest, suite, requirements, config)
    assert result == 3
    evidence, metadata, cases = load_result(result)
    assert_rejected(metadata, "RUNTIME_PIN_GATE_FAILED")
    assert metadata["harness_error"]["stage"] == "runtime_preflight"
    assert metadata["pytest_exit_code"] is None
    assert metadata["summary_counts"]["not_run"] == 1
    assert cases["cases"][0]["status"] == "NOT RUN"
    runtime = json.loads((evidence / "runtime_gate.json").read_text())
    assert runtime["rejections"][0]["code"] == "DISTRIBUTION_MISSING"
    assert runtime["rejections"][0]["expected"] == "1.0.0"
    assert runtime["rejections"][0]["observed"] is None


def test_r3_306_runtime_wrong_version_fails_before_launch(tmp_path):
    suite = "control-r3-runtime-wrong"
    manifest, control = stage_control(suite)
    requirements = control / "wrong.lock"
    requirements.write_text("pytest==0.0.0\n", encoding="utf-8")
    config = runtime_config(control)
    result = invoke(manifest, suite, requirements, config)
    assert result == 3
    evidence, metadata, cases = load_result(result)
    assert_rejected(metadata, "RUNTIME_PIN_GATE_FAILED")
    assert metadata["harness_error"]["stage"] == "runtime_preflight"
    assert metadata["summary_counts"]["not_run"] == 1
    assert cases["cases"][0]["status"] == "NOT RUN"
    runtime = json.loads((evidence / "runtime_gate.json").read_text())
    mismatch = next(r for r in runtime["rejections"] if r["code"] == "DISTRIBUTION_VERSION_MISMATCH")
    assert mismatch["expected"] == "0.0.0"
    assert mismatch["observed"] == run_suite.distribution("pytest").version
    assert mismatch["observed"] != "0.0.0"


def test_r3_307_external_interpreter_fallback_fails_closed(tmp_path, monkeypatch):
    monkeypatch.setattr(run_suite.sys, "prefix", run_suite.sys.base_prefix)
    manifest, _ = stage_control("control-r3-runtime-external")
    result = invoke(manifest, "control-r3-runtime-external")
    assert result == 3
    evidence, metadata, cases = load_result(result)
    assert_rejected(metadata, "RUNTIME_PIN_GATE_FAILED")
    assert metadata["harness_error"]["stage"] == "runtime_preflight"
    assert metadata["summary_counts"]["not_run"] == 1
    assert cases["cases"][0]["status"] == "NOT RUN"
    runtime = json.loads((evidence / "runtime_gate.json").read_text())
    codes = {r["code"] for r in runtime["rejections"]}
    assert "INTERPRETER_OUTSIDE_ISOLATED_ENVIRONMENT" in codes
    assert runtime["observed"]["isolated_venv"] is False


def test_r3_308_external_distribution_fallback_fails_closed(tmp_path, monkeypatch):
    real_distribution = run_suite.distribution
    click_dist = real_distribution("click")

    class ExternalDistribution:
        version = click_dist.version

        def locate_file(self, path):
            return tmp_path

    def fake_distribution(name):
        if name.lower().replace("_", "-") == "click":
            return ExternalDistribution()
        return real_distribution(name)

    monkeypatch.setattr(run_suite, "distribution", fake_distribution)
    manifest, _ = stage_control("control-r3-dist-external")
    result = invoke(manifest, "control-r3-dist-external")
    assert result == 3
    evidence, metadata, cases = load_result(result)
    assert_rejected(metadata, "RUNTIME_PIN_GATE_FAILED")
    assert metadata["summary_counts"]["not_run"] == 1
    assert cases["cases"][0]["status"] == "NOT RUN"
    runtime = json.loads((evidence / "runtime_gate.json").read_text())
    rejection = next(r for r in runtime["rejections"] if r["code"] == "DISTRIBUTION_OUTSIDE_ISOLATED_ENVIRONMENT")
    assert rejection["distribution"] == "click"
    assert rejection["observed"] == str(tmp_path)


def test_r3_309_all_conditions_correct_accepts(tmp_path):
    manifest, _ = stage_control("control-r3-normal")
    result = invoke(manifest, "control-r3-normal")
    assert result == 0
    evidence, metadata, cases = load_result(result)
    assert metadata["accepted"] is True
    assert metadata["acceptance"] == {
        "accepted": True, "runner_exit_code": 0, "pytest_exit_code": 0,
        "reject_reasons": [], "required_case_count": 1,
        "runtime_gate_accepted": True, "frozen_inputs_unchanged": True,
    }
    assert metadata["status"] == "accepted"
    assert metadata["summary_counts"] == {
        "candidate": 1, "executed": 1, "passed": 1, "failed": 0,
        "blocked": 0, "skipped": 0, "not_run": 0,
    }
    assert cases["cases"][0]["status"] == "passed"
    assert (evidence / metadata["junit_report"]).is_file()
    runtime = json.loads((evidence / "runtime_gate.json").read_text())
    assert runtime["accepted"] is True
    assert runtime["observed"]["isolated_venv"] is True
    assert runtime["observed"]["interpreter_inside_venv"] is True
