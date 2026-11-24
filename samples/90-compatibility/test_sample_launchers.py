"""Exercise real child-process boundaries using a private venv and observation-only stubs."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import venv

SAMPLE = Path(__file__).resolve().parent / "kimi-cli-validation"
PROBE = """import json
from pathlib import Path
import sys
observation = dict(executable=sys.executable, prefix=sys.prefix, argv=sys.argv[1:],
                   isolated=sys.flags.isolated, no_user_site=sys.flags.no_user_site)
"""


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LauncherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        cls.root = Path(directory.name).resolve()
        cls.env = cls.root / "test venv"
        venv.EnvBuilder(with_pip=False, symlinks=os.name != "nt").create(cls.env)
        if os.name == "nt":
            cls.python = cls.env / "Scripts/python.exe"
            site = cls.env / "Lib/site-packages"
        else:
            cls.python = cls.env / "bin/python"
            version = f"python{sys.version_info.major}.{sys.version_info.minor}"
            site = cls.env / "lib" / version / "site-packages"
        # Deliberately emit no JUnit. Both harnesses must reject the probe run.
        (site / "pytest").mkdir()
        (site / "pytest/__main__.py").write_text(PROBE + "\nprint(json.dumps(observation))\n")
        (site / "pytest/__init__.py").write_text("")
        metadata = site / "launch_probe-1.0.dist-info"
        metadata.mkdir()
        (metadata / "METADATA").write_text("Metadata-Version: 2.1\nName: launch-probe\nVersion: 1.0\n")
        cls.legacy = cls.root / "legacy"
        cls.r4 = cls.root / "r4"
        for source, target in [(SAMPLE, cls.legacy), (SAMPLE / "r4-fix1", cls.r4)]:
            shutil.copytree(source, target, ignore=shutil.ignore_patterns(
                "r4-fix1", "__pycache__", "evidence", "targeted-evidence", "r4_control_inputs", ".pytest_cache",
            ))

    def assert_interpreter(self, observation):
        self.assertEqual(Path(observation["executable"]), self.python)
        self.assertEqual(Path(observation["prefix"]), self.env)
        self.assertEqual(observation["no_user_site"], 1)

    def assert_pytest_launch(self, metadata, expected_target):
        probe = json.loads(metadata["stdout"])
        self.assert_interpreter(probe)
        self.assertEqual(probe["isolated"], 1)
        self.assertEqual(probe["argv"][:3], [expected_target, "-vv", "--junitxml"])
        self.assertEqual(len(probe["argv"]), 4)
        self.assertTrue(Path(probe["argv"][3]).is_absolute())
        if os.name == "nt":
            self.assertEqual(metadata["command_argv"][:4], ["python", "-I", "-m", "pytest"])

    def test_legacy_runner_starts_selected_interpreter(self):
        with patch.object(sys, "path", [str(self.legacy / "scripts"), *sys.path]):
            runner = load_module(self.legacy / "scripts/run_suite.py", "legacy_launch_probe")
        with (patch.object(sys, "executable", str(self.python)),
              patch.object(sys, "argv", ["run_suite.py", "--suite", "click"]),
              contextlib.redirect_stdout(io.StringIO())):
            self.assertEqual(runner.main(), 2)
        report, = (self.legacy / "evidence/click/runs").glob("*/run_metadata.json")
        self.assert_pytest_launch(json.loads(report.read_bytes()), "tests/click")

    def test_r4_control_runner_starts_selected_interpreter(self):
        with patch.object(sys, "path", [str(self.r4), *sys.path]):
            support = load_module(self.r4 / "tests/hardening/control_support.py", "r4_launch_probe")
        suite = "control-platform-launch"
        manifest, control = support.stage_control(suite)
        requirements = control / "effective.lock"
        requirements.write_text("launch-probe==1.0\n")
        config = support.runtime_config(control)
        with patch.object(sys, "executable", str(self.python)):
            result = support.invoke_subprocess(manifest, suite, requirements, config)
        self.assertEqual(result.code, 3, result.stderr)
        _, metadata, _ = support.load_result(result)
        self.assertEqual(metadata["harness_error"]["type"], "FileNotFoundError", metadata["harness_error"])
        self.assert_pytest_launch(metadata, (control / "test_control.py").relative_to(self.r4).as_posix())

    def test_real_click_case_starts_selected_module_in_both_samples(self):
        # The case imports Click for its unrelated in-process cases. Supply only
        # those import names here; the child writes its own execution receipt.
        testing = types.ModuleType("click.testing")
        testing.CliRunner = object
        app = types.ModuleType("swarm_cli01.click_app")
        app.click_app = object()
        for sample in (self.legacy, self.r4):
            with self.subTest(sample=sample.name):
                (sample / "src/swarm_cli01/click_app.py").write_text(
                    PROBE + '\nPath("probe.json").write_text(json.dumps(observation))\n'
                    + 'Path("process.txt").write_text("process\\nprocess\\n")\n'
                    + 'print("wrote=process.txt;count=2;upper=false")\n'
                )
                work = sample / "click-work"
                work.mkdir()
                with (patch.dict(sys.modules, {"click.testing": testing, "swarm_cli01.click_app": app}),
                      patch.object(sys, "executable", str(self.python))):
                    module = load_module(sample / "tests/click/test_click_cli.py", "click_launch_probe")
                    module.test_clk_006_real_subprocess_invocation(lambda *args: None, work)
                probe = json.loads((work / "probe.json").read_bytes())
                self.assert_interpreter(probe)
                self.assertEqual(probe["argv"], ["--name", "process", "--count", "2", "process.txt"])


if __name__ == "__main__":
    unittest.main()
