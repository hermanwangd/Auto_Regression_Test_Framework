import importlib.util
import json
from datetime import date
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("quality_summary", Path(__file__).resolve().parents[1] / "quality_summary.py")
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)
COMMIT = "a" * 40


class QualitySummaryTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def summary(self):
        return quality.summarize(COMMIT, self.root, date(2026, 11, 28))

    def test_missing_inputs_keep_unknown_verdicts_and_list_platform_boundaries(self):
        report = self.summary()
        self.assertFalse(report["evidence_complete"])
        self.assertIsNone(report["sast"])
        self.assertIsNone(report["junit"]["Linux framework"])
        self.assertIsNone(report["product"]["outcomes"])
        self.assertTrue(report["adapter_conformance"]["missing"])
        self.assertIn("Company SIT", report["unverified"])
        self.assertIn("Product verdicts unavailable", quality.markdown(report))

    def test_other_sha_is_refused_even_if_all_producers_are_green(self):
        (self.root / "ci.json").write_text(json.dumps({"headSha": "b" * 40, "headBranch": "main",
            "event": "push", "status": "completed", "conclusion": "success"}))
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.summary()

    def test_failed_and_skipped_junit_cases_keep_their_own_counts(self):
        file = self.root / "TEST-Sample.xml"
        file.write_text("<testsuite><testcase/><testcase><failure/></testcase><testcase><error/></testcase>"
                        "<testcase><skipped/></testcase></testsuite>")
        self.assertEqual(quality.junit([file]), {"passed": 1, "failed": 1, "errors": 1, "skipped": 1})

    def test_receipt_without_invocation_never_counts_as_a_pass(self):
        (self.root / "receipt.json").write_text(json.dumps({"run_id": None, "outcome": "BLOCKED"}))
        self.assertEqual(quality.product_cases(self.root, [])["outcomes"]["NOT_RUN"], 1)
        self.assertEqual(quality.product_cases(self.root, [])["outcomes"]["passed"], 0)

    def test_native_receipt_from_previous_attempt_is_rejected(self):
        contract = quality.document(quality.ROOT / "src/test/resources/provider-conformance.json")[0]
        receipt = {"contract": contract["contract"], "proof": contract["proof"],
                   "revision": COMMIT, "job_run": "5", "job_attempt": "1",
                   "operations": contract["operations"], "scenarios": ["observed"]}
        (self.root / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "Mismatched conformance receipt"):
            quality.conformance(self.root, COMMIT, {"databaseId": 5, "attempt": 2}, [])

    def test_traceability_digest_changes_are_refused(self):
        (self.root / "coverage.json").write_text("{}")
        run = {"coverage": {"path": "coverage.json", "sha256": "0" * 64}}
        (self.root / "index.json").write_text(json.dumps({"schema_version": "ci-run-traceability.v1", "runs": [run]}))
        with self.assertRaisesRegex(ValueError, "artifact digest"):
            quality.traceability(self.root, [])

    def test_missing_product_family_is_reported_even_with_a_receipt(self):
        (self.root / "receipt.json").write_text(json.dumps({"run_id": None}))
        gaps = []
        quality.product_cases(self.root, gaps)
        self.assertIn("Missing or ambiguous product inventory for NATS", gaps)
        self.assertIn("Missing or ambiguous product inventory for SQLite", gaps)

    def test_junit_refuses_document_type_declarations(self):
        from defusedxml.common import DTDForbidden
        path = self.root / "TEST-Sample.xml"
        path.write_text("<!DOCTYPE testsuite><testsuite><testcase/></testsuite>")
        with self.assertRaises(DTDForbidden):
            quality.junit([path])
