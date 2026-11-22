"""Narrow regression controls for ambient inputs and report freshness."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class RunnerSafetyTests(unittest.TestCase):
    def test_ambient_variables_do_not_supply_default_or_required_values(self):
        sys.path.insert(0, str(ROOT / 'src'))
        cases = load(ROOT / 'tests/click/test_click_cli.py', 'click_cases')
        with patch.dict(os.environ, {'SWARM_NAME': 'ambient', 'SWARM_COUNT': '3'}):
            for name in ['test_clk_002_explicit_name_and_default_count',
                         'test_clk_003_missing_required_option_is_rejected']:
                with self.subTest(name=name):
                    getattr(cases, name)(lambda *args: None)

    def exercise(self, outcome):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'scripts').mkdir()
            shutil.copy2(ROOT / 'case_manifest.click.json', root / 'case_manifest.click.json')
            old = root / 'evidence/click'
            old.mkdir(parents=True)
            stale = '<testsuites><testsuite>' + ''.join(
                '<testcase name="' + case['test_name'] + '"/>'
                for case in json.loads((root / 'case_manifest.click.json').read_text())['cases']
            ) + '</testsuite></testsuites>'
            (old / 'junit.xml').write_text(stale)
            runner = load(ROOT / 'scripts/run_suite.py', 'runner_under_test')
            runner.__file__ = str(root / 'scripts/run_suite.py')
            run = patch.object(runner.subprocess, 'run', side_effect=outcome) if isinstance(outcome, Exception) else patch.object(runner.subprocess, 'run', return_value=outcome)
            with patch.object(runner.platform, 'platform', return_value='test-platform'), patch.object(sys, 'argv', ['run_suite.py', '--suite', 'click']), run:
                code = runner.main()
            self.assertNotEqual(code, 0)
            self.assertEqual((old / 'junit.xml').read_text(), stale)
            results = list((root / 'evidence').rglob('case_evidence.json'))
            self.assertEqual(len(results), 1)
            self.assertNotEqual(results[0].parent, old)
            counts = json.loads(results[0].read_text())['summary_counts']
            self.assertEqual(counts['passed'], 0)
            self.assertEqual(counts['blocked'], 6)
            self.assertEqual(counts['executed'], 0)

    def test_stale_report_is_not_used_after_startup_error(self):
        self.exercise(OSError('simulated pytest startup failure'))

    def test_stale_report_is_not_used_after_configuration_failure(self):
        self.exercise(subprocess.CompletedProcess(['pytest'], 4, '', 'configuration failure'))

    def test_success_without_fresh_report_is_blocked(self):
        self.exercise(subprocess.CompletedProcess(['pytest'], 0, '', ''))

    def test_genuine_assertion_failure_is_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / 'scripts').mkdir()
            manifest = json.loads((ROOT / 'case_manifest.click.json').read_text())
            (root / 'case_manifest.click.json').write_text(json.dumps(manifest))
            runner = load(ROOT / 'scripts/run_suite.py', 'runner_assertion_test')
            runner.__file__ = str(root / 'scripts/run_suite.py')
            def assertion_failure(command, **kwargs):
                report = Path(command[command.index('--junitxml') + 1])
                report.write_text('<testsuites><testsuite>' + ''.join(
                    '<testcase name="' + c['test_name'] + '"><failure message="expected assertion failure"/></testcase>' for c in manifest['cases']) + '</testsuite></testsuites>')
                return subprocess.CompletedProcess(command, 1, '', '')
            with patch.object(runner.platform, 'platform', return_value='test-platform'), patch.object(sys, 'argv', ['run_suite.py', '--suite', 'click']), patch.object(runner.subprocess, 'run', side_effect=assertion_failure):
                code = runner.main()
            self.assertEqual(code, 1)
            result = next((root / 'evidence').rglob('case_evidence.json'))
            counts = json.loads(result.read_text())['summary_counts']
            self.assertEqual(counts['failed'], 6)
            self.assertEqual(counts['passed'], 0)
            self.assertEqual(counts['blocked'], 0)

if __name__ == '__main__':
    unittest.main()
