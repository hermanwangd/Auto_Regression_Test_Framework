import json
import tempfile
import unittest
from pathlib import Path

from policy import gate


class GatePolicyTest(unittest.TestCase):
    def check_report(self, findings=(), errors=()):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.json"
            path.write_text(json.dumps({"results": findings, "errors": errors}))
            return gate([path])

    def test_threshold_and_metadata_precedence(self):
        for rating, scanner_level, expected in [
            ("Critical", "WARNING", 1), ("High", "INFO", 1), (7.0, "WARNING", 1),
            ("6.9", "ERROR", 0), ("Info", "ERROR", 0), (None, "ERROR", 1),
            (None, "WARNING", 0), (None, "CRITICAL", 1),
        ]:
            with self.subTest(rating=rating, scanner_level=scanner_level):
                meta = {} if rating is None else {"security-severity": rating}
                finding = {"extra": {"severity": scanner_level, "metadata": meta}}
                self.assertEqual(self.check_report([finding]), expected)

    def test_incomplete_scan_and_empty_result(self):
        self.assertEqual(self.check_report(), 0)
        for kind in ["Timeout", "Rule parse error", "Other syntax error"]:
            self.assertEqual(self.check_report(errors=[{"type": kind, "level": "warn", "path": "new.py"}]), 1)
