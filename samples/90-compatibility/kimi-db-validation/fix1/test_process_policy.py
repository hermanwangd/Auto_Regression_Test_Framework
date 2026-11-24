"""Regression checks for the compatibility runner's allowed Python entry points."""
from pathlib import Path
import sys
import tempfile
import unittest

from process_policy import ROOT, reviewed_python_arguments, run_bounded_subprocess


class ProcessPolicyTest(unittest.TestCase):
    def test_rejects_other_executables_modules_scripts_and_pytest_plugins(self):
        pytest_args = [sys.executable, "-I", "-S", str(ROOT / "isolated_pytest.py"),
                       "--runtime", "/runtime", "--source-root", "/source", "--probe-output", "/probe",
                       "--", "-q", "-p", "different_plugin", "--junitxml", "/result", "tests"]
        for command in (["/bin/true"], [sys.executable, "-c", "pass"], [sys.executable, "-m", "other"],
                        [sys.executable, "-I", "-S", "/other.py"], pytest_args):
            with self.subTest(command=command), self.assertRaises(ValueError):
                reviewed_python_arguments(command)

    def test_reviewed_process_tree_completes_and_times_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mode, timeout, expected in [("normal", 10, 0), ("timeout", 0.4, 124)]:
                with self.subTest(mode=mode):
                    result = run_bounded_subprocess(
                        [sys.executable, "-u", str(ROOT / "controls/process_tree_fixture.py"), mode],
                        cwd=root, stdout_path=root / (mode + ".out"), stderr_path=root / (mode + ".err"),
                        timeout_seconds=timeout,
                    )
                    self.assertEqual(result["exit_code"], expected)
                    self.assertTrue(result["cleanup_pass"])
                    self.assertIn("GRANDCHILD", Path(result["stdout"]).read_text())
                    if mode == "timeout":
                        self.assertTrue(result["cleanup"]["parent_reaped"])
                        self.assertEqual(result["cleanup"]["live_members_after"], [])
