import contextlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

from scripts import run_suite


ROOT = Path(run_suite.__file__).resolve().parents[1]


class Invocation:
    def __init__(self, suite, code, stdout, stderr, payload):
        self.suite = suite
        self.code = code
        self.stdout = stdout
        self.stderr = stderr
        self.payload = payload
        self.run_id = payload.get("run_id")
        self.evidence = ROOT / payload["evidence_dir"] if payload.get("evidence_dir") else None

    def __int__(self):
        return self.code

    def __eq__(self, other):
        return self.code == other


def payload_from_stdout(stdout):
    lines = [line for line in stdout.splitlines() if line.strip()]
    if not lines:
        return {}
    return json.loads(lines[-1])


def safe_name(value):
    return re.sub(r"[^A-Za-z0-9_]", "_", value)


def stage_control(suite, test_name="test_control", source=None, classname=None,
                  file_override=None, node_selection=None, conftest=None):
    directory_name = f"{safe_name(suite)}_{uuid.uuid4().hex[:10]}"
    control_dir = ROOT / "tests" / "r4_control_inputs" / directory_name
    control_dir.mkdir(parents=True, exist_ok=False)
    test_file = control_dir / "test_control.py"
    if source is None:
        source = "def test_control():\n    assert True\n"
    test_file.write_text(source, encoding="utf-8")
    test_relative = str(test_file.relative_to(ROOT))
    selected_path = control_dir if conftest is not None else test_file
    if conftest is not None:
        (control_dir / "conftest.py").write_text(conftest, encoding="utf-8")
    frozen_hashes = {str(path.relative_to(ROOT)): run_suite.sha256(path)
                     for path in sorted(control_dir.glob("*.py"))}
    expected_classname = classname or f"tests.r4_control_inputs.{directory_name}.test_control"
    manifest = {
        "source": {"component": "r4-frozen-harness-control", "package_result": False},
        "execution": {
            "test_path": str(selected_path.relative_to(ROOT)),
            "node_selection": node_selection,
            "frozen_source_sha256": frozen_hashes,
        },
        "cases": [{
            "case_id": "R4-CONTROL",
            "category": "harness-control",
            "required": True,
            "test_name": test_name,
            "junit_identity": {
                "classname": expected_classname,
                "name": test_name,
                "file": file_override if file_override is not None else test_relative,
            },
            "fixed_input": {"injected": True},
            "expected": {"fail_closed_unless_current_valid_frozen_junit": True},
        }],
    }
    manifest_path = control_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest_path, control_dir


def runtime_config(control_dir):
    import platform
    path = control_dir / "runtime.json"
    path.write_text(json.dumps({
        "python_version": platform.python_version(),
        "require_isolated_venv": True,
        "require_distributions_inside_venv": True,
        "hash_lock_file": None,
    }), encoding="utf-8")
    return path


def invoke(manifest, suite, requirements="requirements-click.lock", config="runtime_gate.json",
           test_path=None, node_selection=None, timeout=10):
    argv = ["--suite", suite, "--manifest", str(manifest), "--requirements", str(requirements),
            "--runtime-config", str(config), "--timeout-seconds", str(timeout)]
    if test_path is not None:
        argv.extend(["--test-path", str(test_path)])
    if node_selection is not None:
        argv.extend(["--node-selection", node_selection])
    stdout_buffer = io.StringIO()
    stderr_buffer = io.StringIO()
    with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
        code = run_suite.main(argv)
    stdout = stdout_buffer.getvalue()
    return Invocation(suite, code, stdout, stderr_buffer.getvalue(), payload_from_stdout(stdout))


def invoke_subprocess(manifest, suite, requirements="requirements-click.lock", config="runtime_gate.json",
                      test_path=None, node_selection=None, timeout=20):
    argv = [sys.executable, str(ROOT / "scripts" / "run_suite.py"), "--suite", suite,
            "--manifest", str(manifest), "--requirements", str(requirements),
            "--runtime-config", str(config), "--timeout-seconds", str(timeout)]
    if test_path is not None:
        argv.extend(["--test-path", str(test_path)])
    if node_selection is not None:
        argv.extend(["--node-selection", node_selection])
    completed = subprocess.run(argv, cwd=ROOT, text=True, encoding="utf-8", capture_output=True,
                               timeout=timeout + 10, check=False)
    return Invocation(suite, completed.returncode, completed.stdout, completed.stderr,
                      payload_from_stdout(completed.stdout))


def load_result(invocation):
    assert invocation.evidence is not None
    metadata = json.loads((invocation.evidence / "run_metadata.json").read_text(encoding="utf-8"))
    cases = json.loads((invocation.evidence / "case_evidence.json").read_text(encoding="utf-8"))
    return invocation.evidence, metadata, cases


def reject_codes(metadata):
    return {reason["code"] for reason in metadata["acceptance"]["reject_reasons"]}


def directory_hashes(directory):
    return {str(path.relative_to(directory)): run_suite.sha256(path)
            for path in sorted(directory.rglob("*")) if path.is_file()}


def record_invocation(record_property, label, invocation, extra=None):
    assert invocation.evidence is not None
    _, metadata, _ = load_result(invocation)
    record = {
        "label": label,
        "nested_runner_command": [sys.executable, str(ROOT / "scripts" / "run_suite.py")],
        "nested_runner_return_code": invocation.code,
        "nested_runner_stdout": invocation.stdout,
        "nested_runner_stderr": invocation.stderr,
        "nested_pytest_return_code": metadata["pytest_exit_code"],
        "accepted": metadata["accepted"],
        "reject_reasons": metadata["acceptance"]["reject_reasons"],
        "harness_error": metadata["harness_error"],
        "run_id": invocation.run_id,
        "run_directory": str(invocation.evidence.relative_to(ROOT)),
        "junit_report": metadata["junit_report"],
        "junit_sha256": run_suite.sha256(invocation.evidence / metadata["junit_report"])
            if (invocation.evidence / metadata["junit_report"]).is_file() else None,
        "summary_counts": metadata["summary_counts"],
    }
    if extra:
        record.update(extra)
    output = ROOT / "targeted-evidence" / label
    output.mkdir(parents=True, exist_ok=True)
    (output / "nested-runner.stdout.txt").write_text(invocation.stdout, encoding="utf-8")
    (output / "nested-runner.stderr.txt").write_text(invocation.stderr, encoding="utf-8")
    (output / "observation.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    record_property("observed", json.dumps(record, ensure_ascii=False))
    return record
