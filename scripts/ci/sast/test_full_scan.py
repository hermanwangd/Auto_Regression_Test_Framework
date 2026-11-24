import json
import tempfile
import unittest
from pathlib import Path

from full_gate import JOB_TEMPLATE, RENDERED, evaluate, full_gate


def report(errors=(), findings=(), scanned=("existing.java",)):
    return {"results": list(findings), "errors": list(errors), "paths": {"scanned": list(scanned)}}


class FullScanTest(unittest.TestCase):
    def test_existing_high_and_critical_block_and_report_locations(self):
        for rating in ("High", "Critical"):
            finding = {"extra": {"metadata": {"security-severity": rating}}, "check_id": "marker",
                       "path": "existing.java", "start": {"line": 2}}
            result = evaluate(report(findings=[finding]), rendered=False)
            self.assertFalse(result["passed"])
            self.assertEqual(result["blocking"][0]["line"], 2)
            self.assertEqual(result[rating.lower()], 1)

    def test_only_the_raw_job_parser_diagnostic_allows_render_compensation(self):
        for kind, path, passed in [("Other syntax error", JOB_TEMPLATE, True),
                                   ("Timeout", JOB_TEMPLATE, False),
                                   ("Other syntax error", "other.yaml", False)]:
            error = {"type": kind, "path": path}
            self.assertEqual(evaluate(report(errors=[error]), False)["passed"], passed)
            self.assertFalse(evaluate(report(errors=[error], scanned=RENDERED), True)["passed"])

    def test_requires_both_rendered_files_and_nonempty_repository_scan(self):
        self.assertTrue(evaluate(report(scanned=RENDERED), True)["passed"])
        for files, rendered in [([], False), (["produce.yaml"], True)]:
            with self.assertRaises(ValueError):
                evaluate(report(scanned=files), rendered)

    def test_raw_template_exception_needs_both_families_to_finish_the_render_scan(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "targets").write_bytes(JOB_TEMPLATE.encode() + b"\0")
            raw = report(errors=[{"type": "Other syntax error", "path": JOB_TEMPLATE}], scanned=[JOB_TEMPLATE])
            for family in ("gitlab", "community"):
                (out / f"{family}-repository.json").write_text(json.dumps(raw))
                (out / f"{family}-rendered.json").write_text(json.dumps(report(scanned=RENDERED)))
            self.assertEqual(full_gate(out, out, "test"), 0)
            (out / "community-rendered.json").unlink()
            self.assertEqual(full_gate(out, out, "test"), 1)
            self.assertFalse(json.loads((out / "summary.json").read_bytes())["passed"])
