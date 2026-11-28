import sys
import hashlib
import json
from datetime import date
from pathlib import Path
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quality_summary as quality
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

    def test_skipped_db2_does_not_claim_native_coverage(self):
        (self.root / "ci.json").write_text(json.dumps({"headSha": COMMIT, "event": "push", "headBranch": "main",
            "status": "completed", "databaseId": 5, "attempt": 1, "url": "", "conclusion": "success",
            "jobs": [{"steps": [{"name": "Verify native JDBC / db2", "conclusion": "skipped"}]}]}))
        report = self.summary()
        self.assertIn("Db2 native", report["unverified"])
        self.assertEqual(report["scenarios"]["Linux / Db2"], "skipped")
        self.assertNotIn("db2", report["native_jdbc"])
        self.assertFalse(any("Db2" in gap for gap in report["evidence_gaps"]))
        self.assertIn("Missing verified native JDBC receipt for Oracle", report["evidence_gaps"])

    def test_green_step_without_receipt_is_unverified(self):
        (self.root / "ci.json").write_text(json.dumps({"headSha": COMMIT, "event": "push", "headBranch": "main",
            "status": "completed", "databaseId": 5, "attempt": 1, "url": "", "conclusion": "success",
            "jobs": [{"steps": [{"name": "Verify native JDBC / oracle", "conclusion": "success"}]}]}))
        report = self.summary()
        self.assertIn("Oracle native", report["unverified"])
        self.assertIn("Missing verified native JDBC receipt for Oracle", report["evidence_gaps"])

    def test_jdbc_receipt_binds_attempt_dialect_results_and_external_state(self):
        receipt = {"schema_version": "native-jdbc.v1", "dialect": "oracle", "revision": COMMIT, "job_run": "5",
                   "job_attempt": "2", "image": "fixture@sha256:" + "b" * 64, "engine_version": "Oracle fixture",
                   "driver": "fixture.jar", "driver_sha256": "c" * 64, "cases": {}}
        for number, status in enumerate(("passed", "failed")):
            order = "ORD-" + str(number) * 24
            outputs = [{"affected_rows": 1}, {"row_count": 1, "sample_rows": [{"ORDER_ID": order, "STATUS": "CREATED"}]},
                       {"matched": True}, {"affected_rows": 1}]
            case = {"test_case_id": "JDBC-NATIVE-ORDER", "profile": "external_oracle", "status": status, "run_id": str(number),
                    "provider_results": [{"provider_contract": "jdbc.v0.3", "target": "order_db",
                                          "profile": "external_oracle", "runtime_mode": "native"}],
                    "cleanup_failures": [], "failure": {"code": None if number == 0 else "ASSERTION_FAILED"},
                    "step_results": [{"operation": op, "status": "passed", "outputs": out} for op, out in
                                     zip(("db_seed", "db_query", "db_record_exists", "db_cleanup"), outputs)],
                    "verify_results": [{"id": "one_order_found", "status": status}]}
            raw = json.dumps(case).encode()
            (self.root / (status + ".json")).write_bytes(raw)
            receipt["cases"][status] = {"order_id": order, "result_sha256": hashlib.sha256(raw).hexdigest(),
                                        "remaining_rows": ["other-run KEEP"], "evidence_validation": "validated"}
        file = self.root / "receipt.json"
        file.write_text(json.dumps(receipt))
        self.assertEqual(quality.validate_native_jdbc(self.root, "oracle", COMMIT, 5, 2), receipt)
        for args in (("db2", COMMIT, 5, 2), ("oracle", "d" * 40, 5, 2), ("oracle", COMMIT, 5, 1)):
            with self.assertRaisesRegex(ValueError, "provenance"):
                quality.validate_native_jdbc(self.root, *args)
        receipt["cases"]["failed"]["remaining_rows"] = []
        file.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "evidence"):
            quality.validate_native_jdbc(self.root, "oracle", COMMIT, 5, 2)
        (self.root / "passed.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "digest"):
            quality.validate_native_jdbc(self.root, "oracle", COMMIT, 5, 2)

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
