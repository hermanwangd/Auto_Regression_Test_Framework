import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "provider-conformance.py"
spec = importlib.util.spec_from_file_location("provider_conformance", SCRIPT)
kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kit)


class ProviderConformanceTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.result = self.root / "result.json"

    def test_native_receipt_requires_actual_success_on_the_registered_target(self):
        data = {"status": "passed", "suite_id": "s", "profile": "isolated", "run_id": "one",
                "provider_results": [{"provider_contract": "cache.v0.3", "target": "cache", "runtime_mode": "native"}],
                "step_results": [{"target": "cache", "operation": "read", "status": "passed", "outputs": {"count": 0}},
                                 {"target": "other", "operation": "write", "status": "passed", "outputs": {"count": 1}}]}
        self.result.write_text(json.dumps(data))
        self.assertEqual(["read"], kit.check("cache.v0.3", ["read"], [self.result])["operations"])
        with self.assertRaisesRegex(ValueError, "missing.*write"):
            kit.check("cache.v0.3", ["read", "write"], [self.result])
        data["provider_results"][0]["runtime_mode"] = "mock"
        self.result.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "missing native"):
            kit.check("cache.v0.3", ["read"], [self.result])

    def test_primary_failure_does_not_stand_in_for_cleanup_failure(self):
        self.result.write_text(json.dumps({"status": "failed", "failure": {
            "code": "MONGO_OPERATION_FAILED", "classification": "PROVIDER_RUNTIME_ERROR", "cleanup_failures": []},
            "cleanup_failures": []}))
        with self.assertRaisesRegex(ValueError, "cleanup outcome"):
            kit.check_outage("mongodb.v0.3", self.result)

    def test_required_behavior_case_must_have_executed_successfully(self):
        report = self.root / "TEST-example.xml"
        case = '<testcase classname="test.Boundary" name="check"/>'
        with patch.object(kit, "BEHAVIOR", {"limits": ["Boundary.check"]}):
            for child in ["", case.replace("/>", "><skipped/></testcase>"), case.replace("/>", "><failure/></testcase>")]:
                report.write_text("<testsuite>" + child + "</testsuite>")
                with self.assertRaisesRegex(ValueError, "required conformance test"):
                    kit.check_behavior_reports(self.root, {})
            report.write_text("<testsuite>" + case + "</testsuite>")
            self.assertEqual({"limits": {"Boundary.check": 1}}, kit.check_behavior_reports(self.root, {}))
