"""Offline regression checks for the public harness authorization boundary."""
import copy
import datetime as dt
import hashlib
import json
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location("public_nats", Path(__file__).with_name("nats.py"))
nats = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(nats)


class NatsHarnessBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        now = dt.datetime.now(dt.timezone.utc)
        self.plan = {"port": 43199, "source_commit": "5a815f11a911efa2c88fd1dbfb18ed89a449bfc1",
                     "reviewed_at": (now - dt.timedelta(seconds=1)).isoformat(),
                     "valid_until": (now + dt.timedelta(hours=1)).isoformat(), "cases": {}}
        for index, case in enumerate(nats.CASES):
            suite = self.root / case
            subject = "sit." + f"{index:032x}" + ".echo"
            for relative, document in nats.expected_suite(case, self.plan["port"], subject).items():
                nats.write_json(suite / relative, document)
            (suite / "fixtures").mkdir()
            shutil.copyfile(nats.HERE / "nats-payload.json", suite / "fixtures/payload.json")
            (suite / "contracts").mkdir()
            shutil.copyfile(nats.CONTRACT, suite / "contracts/nats.yaml")
            nats.write_json(suite / "handoff/reviewed-tests.json", nats.expected_handoff(case, self.plan, suite))
            self.plan["cases"][case] = {"subject": subject, "expected_verdict": nats.CASES[case],
                                       "handoff_sha256": nats.sha(suite / "handoff/reviewed-tests.json")}

    def tearDown(self):
        self.temporary.cleanup()

    def test_exact_static_graph_is_accepted_and_negative_oracle_is_false(self):
        nats.validate_suites(self.plan, self.root)
        wrong = nats.read_json(self.root / "wrong-oracle/test_cases/publish.yaml")
        self.assertIs(wrong["verify"][0]["assert"]["expected"], False)

    def test_added_operation_is_rejected_before_dispatch(self):
        path = self.root / "positive/test_cases/publish.yaml"
        document = nats.read_json(path)
        document["execute"].append(copy.deepcopy(document["execute"][0]))
        nats.write_json(path, document)
        with self.assertRaisesRegex(ValueError, "committed expectation"):
            nats.validate_suites(self.plan, self.root)

    def test_external_endpoint_is_rejected_before_dispatch(self):
        path = self.root / "positive/env_profiles/local_native.yaml"
        document = nats.read_json(path)
        document["targets"]["event_bus"]["bindings"]["connection.uri"] = "nats://192.0.2.1:4222"
        nats.write_json(path, document)
        with self.assertRaisesRegex(ValueError, "committed expectation"):
            nats.validate_suites(self.plan, self.root)

    def test_payload_tamper_cannot_be_approved_by_rehashing(self):
        (self.root / "positive/fixtures/payload.json").write_text('{"changed":true}')
        with self.assertRaisesRegex(ValueError, "reviewed fixture"):
            nats.validate_suites(self.plan, self.root)

    def test_mapping_tamper_cannot_be_approved_by_rehashing(self):
        path = self.root / "positive/handoff/reviewed-tests.json"
        document = nats.read_json(path)
        document["requirement_mapping"][0]["oracle_refs"] = []
        nats.write_json(path, document)
        self.plan["cases"]["positive"]["handoff_sha256"] = nats.sha(path)
        with self.assertRaisesRegex(ValueError, "committed expectations"):
            nats.validate_suites(self.plan, self.root)

    def test_duplicate_fields_are_rejected(self):
        path = self.root / "duplicate.json"
        path.write_text('{"approved":false,"approved":true}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            nats.read_json(path)

    def test_wildcard_subject_and_extra_case_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "generated local case"):
            nats.expected_suite("positive", 4222, "sit.*.echo")
        self.plan["cases"]["extra"] = {}
        with self.assertRaisesRegex(ValueError, "case coverage"):
            nats.validate_suites(self.plan, self.root)

    def test_ci_policy_requires_an_independent_pin(self):
        for pin in [None, "", "unreviewed"]:
            with self.assertRaisesRegex(ValueError, "independently supplied"):
                nats.validate_policy(pin)

    def test_low_disk_stops_before_execution(self):
        with mock.patch.object(nats.shutil, "disk_usage", return_value=SimpleNamespace(free=nats.MIN_FREE - 1)):
            with self.assertRaisesRegex(ValueError, "5 GiB"):
                nats.require_free_space(self.root)

    def policy_fixture(self):
        root = self.root / "policy-source-fixture"
        root.mkdir()
        policy = copy.deepcopy(nats.read_json(nats.POLICY))
        self.assertEqual(set(policy["source_files"]), set(policy["required_source_files"]))
        for relative in policy["required_source_files"]:
            self.assertFalse(Path(relative).is_absolute())
            self.assertNotIn("..", Path(relative).parts)
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = b"synthetic-policy-source:" + relative.encode("utf-8")
            target.write_bytes(source)
            policy["source_files"][relative] = hashlib.sha256(source).hexdigest()
        policy_path = root / "policy.json"
        encoded = (json.dumps(policy, indent=2, sort_keys=True) + "\n").encode("utf-8")
        policy_path.write_bytes(encoded)
        return root, policy_path, policy, hashlib.sha256(encoded).hexdigest()

    def assert_policy_rejected_before_execution(self, expected_pin, output, message):
        artifact = self.root / "synthetic-unused-artifact.jar"
        artifact.write_bytes(b"synthetic artifact; policy must reject before use")
        artifact_pin = hashlib.sha256(artifact.read_bytes()).hexdigest()
        args = SimpleNamespace(ci_policy_sha256=expected_pin, framework_jar=artifact,
                               framework_sha256=artifact_pin, module_cli=artifact,
                               module_sha256=artifact_pin, asset_dir=self.root / "unused-assets")
        with mock.patch.object(nats, "download_asset") as download, \
                mock.patch.object(nats.Broker, "start") as start, \
                mock.patch.object(nats.subprocess, "run") as child:
            with self.assertRaisesRegex(ValueError, message):
                nats.prepare(args, output)
            download.assert_not_called()
            start.assert_not_called()
            child.assert_not_called()
        self.assertFalse(output.exists())

    def test_wrong_policy_digest_stops_before_download_or_start(self):
        root, policy_path, policy, pin = self.policy_fixture()
        with mock.patch.object(nats, "ROOT", root), mock.patch.object(nats, "POLICY", policy_path), \
                mock.patch.object(nats, "CONTRACT", root / "docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml"):
            self.assertEqual(nats.validate_policy(pin), policy)
            wrong_pin = ("0" if pin[0] != "0" else "1") + pin[1:]
            self.assert_policy_rejected_before_execution(
                wrong_pin, self.root / "wrong-policy-output", "^Independent CI policy digest mismatch$")

    def test_source_tamper_stops_before_download_or_start(self):
        root, policy_path, policy, pin = self.policy_fixture()
        with mock.patch.object(nats, "ROOT", root), mock.patch.object(nats, "POLICY", policy_path), \
                mock.patch.object(nats, "CONTRACT", root / "docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml"):
            self.assertEqual(nats.validate_policy(pin), policy)
            changed = root / "scripts/public-projects/nats.py"
            changed.write_bytes(changed.read_bytes() + b"x")
            self.assert_policy_rejected_before_execution(
                pin, self.root / "source-tamper-output",
                "^CI policy source digest mismatch: scripts/public-projects/nats\\.py$")


if __name__ == "__main__":
    unittest.main()
