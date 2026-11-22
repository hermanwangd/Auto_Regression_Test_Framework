import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCANNER = Path(__file__).resolve().parents[1] / "security-scan.sh"


class DependencyScanTest(unittest.TestCase):
    def scan(self, findings, threshold="4"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scanner = root / "scripts/ci/security-scan.sh"
            scanner.parent.mkdir(parents=True)
            shutil.copyfile(SCANNER, scanner)
            (root / "mvnw").write_text("#!/bin/sh\nexit 0\n")
            (root / "mvnw").chmod(0o755)
            report = root / "target/dependency-check-report.json"
            report.parent.mkdir()
            report.write_text(json.dumps({"dependencies": [
                {"fileName": "example.js", "vulnerabilities": findings}
            ]}))
            return subprocess.run(
                ["bash", str(scanner)], capture_output=True, text=True, check=False,
                env={"PATH": os.environ["PATH"], "ALLOW_SLOW_NVD_WITHOUT_API_KEY": "true",
                     "DEPENDENCY_CHECK_CVSS_THRESHOLD": threshold,
                     "DEPENDENCY_CHECK_DATA_DIR": str(root / "cache")},
            )

    def test_reports_unscored_separately_and_gates_by_source_severity(self):
        findings = [{"name": severity, "severity": severity, "unscored": True}
                    for severity in ("critical", "HIGH", "medium", "low", "unknown")]
        result = self.scan(findings)
        self.assertEqual(result.returncode, 1)
        self.assertIn("medium_high_vulnerability_count: 0", result.stdout)
        self.assertIn("unscored_vulnerability_count: 5", result.stdout)
        self.assertIn("unscored_blocking_vulnerability_count: 3", result.stdout)
        self.assertIn("severity=UNKNOWN | cvss=unscored", result.stdout)

    def test_configured_threshold_and_present_scores_take_precedence(self):
        findings = [
            {"name": "scored", "severity": "HIGH", "cvssv3": {"baseScore": 3.8}},
            {"name": "unscored", "severity": "MEDIUM"},
        ]
        result = self.scan(findings, "7")
        self.assertEqual(result.returncode, 0)
        self.assertIn("unscored_vulnerability_count: 1", result.stdout)
        self.assertIn("unscored_blocking_vulnerability_count: 0", result.stdout)
        result = self.scan([{"name": "scored", "cvssv3": {"baseScore": 7.0}}], "7")
        self.assertEqual(result.returncode, 1)
        self.assertIn("medium_high_vulnerability_count: 1", result.stdout)
