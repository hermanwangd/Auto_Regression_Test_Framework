import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCANNER = Path(__file__).resolve().parents[1] / "security-scan.sh"


class DependencyScanTest(unittest.TestCase):
    def scan(self, findings, threshold="4", *, file_name="example.js", packages=(),
             suppressed=(), stale=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scanner = root / "scripts/ci/security-scan.sh"
            scanner.parent.mkdir(parents=True)
            shutil.copyfile(SCANNER, scanner)
            (root / "mvnw").write_text("#!/bin/sh\n" + (
                "exit 0\n" if stale else "cp scan.json target/dependency-check-report.json\n"
            ))
            (root / "mvnw").chmod(0o755)
            report = root / "target/dependency-check-report.json"
            report.parent.mkdir()
            fixture = report if stale else root / "scan.json"
            fixture.write_text(json.dumps({"dependencies": [
                {"fileName": file_name, "packages": packages, "vulnerabilities": findings,
                 "suppressedVulnerabilities": suppressed}
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

    def test_owasp_cvss_v2_score_takes_precedence_over_severity(self):
        finding = {"name": "v2-only", "severity": "MEDIUM", "cvssv2": {"score": 6.8}}
        result = self.scan([finding], "6")
        self.assertEqual(result.returncode, 1)
        self.assertIn("v2-only | severity=MEDIUM | cvss=6.8", result.stdout)
        self.assertIn("unscored_vulnerability_count: 0", result.stdout)

    def test_zero_threshold_blocks_a_finding_with_unknown_severity(self):
        result = self.scan([{"name": "unrated"}], "0")
        self.assertEqual(result.returncode, 1)
        self.assertIn("unscored_blocking_vulnerability_count: 1", result.stdout)
        self.assertIn("blocking: example.js | unrated | severity=UNKNOWN | cvss=unscored", result.stdout)

    def test_wiremock_finding_still_in_report_blocks_by_filename_or_package_id(self):
        artifact = "wiremock-grpc-extension-core"
        version = "1.0.0-beta.5"
        finding = {"name": "CVE-2018-9116", "cvssv3": {"baseScore": 9.1}}
        for file_name, packages in (
            (f"{artifact}-{version}.jar", []),
            ("different-name.jar", [{"id": f"pkg:maven/org.wiremock/{artifact}@{version}"}]),
        ):
            with self.subTest(file_name=file_name):
                result = self.scan([finding], file_name=file_name, packages=packages)
                self.assertEqual(result.returncode, 1)
                self.assertIn("medium_high_vulnerability_count: 1", result.stdout)

    def test_reports_scanner_suppressions_without_hiding_active_findings(self):
        finding = {"name": "CVE-2023-41329", "cvssv3": {"baseScore": 4.8}}
        result = self.scan([], suppressed=[finding])
        self.assertEqual(result.returncode, 0)
        self.assertIn("suppressed_dependency_vulnerability_count: 1", result.stdout)
        self.assertIn("suppressed: example.js | CVE-2023-41329", result.stdout)
        result = self.scan([finding], suppressed=[finding])
        self.assertEqual(result.returncode, 1)
        self.assertIn("medium_high_vulnerability_count: 1", result.stdout)

    def test_requires_a_report_from_this_scan(self):
        result = self.scan([], stale=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("JSON report was not generated", result.stdout)
