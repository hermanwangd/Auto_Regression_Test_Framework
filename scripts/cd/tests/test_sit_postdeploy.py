"""Synthetic contract tests: no live adapter, provider, network or JVM is launched."""
from copy import deepcopy
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "run-sit-postdeploy.py"
spec = importlib.util.spec_from_file_location("sit_postdeploy", MODULE_PATH)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SitPostdeployTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        self.now = datetime.now(timezone.utc)
        self.profile = {"profile_id": "sit", "execution_mode": "sit_deployed", "targets": {
            "api": {"runtime_mode": "native", "bindings": {"base_url": "https://approved-sit.example"}}},
            "sit_readiness": {"timeout_ms": 1000, "checks": [{"id": "deployed", "kind": "dependency",
            "adapter": "approved-adapter", "expected_version": "commit-42", "config": {"namespace": "owned-test"}}]}}
        self.manifest = {"manifest_version": "v0.3", "suite_id": "SIT-TEST", "default_profile": "sit",
            "targets": {"api": {"provider_contract": "rest_client.v0.3"}},
            "env_profiles": {"sit": {"ref": "env_profiles/sit.yaml"}}, "tests": [{"ref": "test_cases/read.yaml"}]}
        self.test = {"dsl_version": "v0.3", "test_case_id": "READ", "title": "Read approved SUT",
            "execute": [{"id": "read", "target": "api", "op": "http_request",
                         "with": {"request.method": "GET", "request.path": "/ready"}}],
            "verify": [{"id": "status", "type": "assertion", "assert": {
                "actual": "step://read/response.status", "operator": "equals", "expected": 200}}]}
        self.handoff = {"contract_version": "cd-sit-postdeploy.v1", "environment_stage": "sit",
            "deployment": {"ref": "deployment/test", "expected_revision": "commit-42", "observed_revision": "commit-42",
                           "completed_at": (self.now - timedelta(seconds=20)).isoformat()},
            "suite": {"manifest": "suite/suite_manifest.yaml", "profile": "sit", "profile_ref": "suite/env_profiles/sit.yaml",
                      "suite_id": "SIT-TEST", "deployment_check_id": "deployed", "provider_contract_ref": "suite/contracts/rest_client_v0_3.yaml"},
            "checks": [{"id": "deployed", "kind": "dependency", "adapter": "approved-adapter",
                        "adapter_version": "1", "expected_version": "commit-42"}],
            "framework": {"path": "artifacts/framework.jar", "sha256": gate.digest(b"unit-only-framework")},
            "readiness_plugins": [{"path": "artifacts/adapter.jar", "sha256": gate.digest(b"unit-only-adapter")}],
            "files": [], "max_age_seconds": 300, "expected_plan_digest": "a" * 64}
        self.write("artifacts/framework.jar", b"unit-only-framework")
        self.write("artifacts/adapter.jar", b"unit-only-adapter")
        self.refresh()

    def write(self, name, data):
        dest = self.bundle / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)

    def refresh(self):
        docs = {"suite/suite_manifest.yaml": self.manifest, "suite/env_profiles/sit.yaml": self.profile,
                "suite/test_cases/read.yaml": self.test, "suite/contracts/rest_client_v0_3.yaml": {
                    "contract_version": "v0.3", "provider_contract": "rest_client.v0.3", "provider_type": "rest_client", "runtime_modes": ["native"]}}
        self.handoff["files"] = []
        for name, doc in docs.items():
            data = json.dumps(doc).encode()
            self.write(name, data)
            self.handoff["files"].append({"path": name, "sha256": gate.digest(data)})
        self.pin()

    def pin(self):
        data = json.dumps(self.handoff).encode()
        self.write("deployment.json", data)
        self.sha = gate.digest(data)

    def preflight(self):
        return gate.preflight(self.bundle, "deployment.json", self.sha, self.now)

    def test_valid_contract_is_not_execution_evidence(self):
        handoff, blobs = self.preflight()
        self.assertEqual(handoff["environment_stage"], "sit")
        self.assertEqual(len(blobs), 6)

    def test_missing_handoff_profile_readiness_fixture_and_pin_launch_zero_children(self):
        variants = ("handoff", "profile", "readiness", "local", "fixture", "pin")
        for variant in variants:
            with self.subTest(variant=variant):
                profile = deepcopy(self.profile)
                self.refresh()
                pin = self.sha
                if variant == "handoff":
                    (self.bundle / "deployment.json").unlink()
                elif variant == "profile":
                    (self.bundle / "suite/env_profiles/sit.yaml").unlink()
                elif variant == "readiness":
                    del self.profile["sit_readiness"]
                    self.refresh()
                    pin = self.sha
                elif variant == "local":
                    self.profile["execution_mode"] = "local"
                    self.refresh()
                    pin = self.sha
                elif variant == "fixture":
                    self.profile["sit_readiness"]["checks"][0]["adapter"] = "fixture"
                    self.refresh()
                    pin = self.sha
                else:
                    pin = "b" * 64
                output = self.root / ("blocked-" + variant)
                with redirect_stdout(io.StringIO()), patch.object(gate, "launch") as launch, patch("sys.argv", [str(MODULE_PATH), "--bundle", str(self.bundle),
                        "--expected-handoff-sha256", pin, "--output", str(output)]):
                    self.assertEqual(gate.main(), 1)
                    launch.assert_not_called()
                report = json.loads((output / "gate-result.json").read_text())
                self.assertEqual(report["status"], "BLOCKED")
                self.assertFalse(report["framework_launch_attempted"])
                self.profile = profile

    def test_tamper_path_escape_symlink_and_uncatalogued_input_block(self):
        self.write("suite/test_cases/read.yaml", b"{}")
        with self.assertRaises(gate.Blocked): self.preflight()
        self.refresh()
        self.handoff["files"][0]["path"] = "../escape"
        self.pin()
        with self.assertRaises(gate.Blocked): self.preflight()
        self.refresh()
        path = self.bundle / "suite/test_cases/read.yaml"
        raw = path.read_bytes()
        path.unlink()
        outside = self.root / "outside.json"
        outside.write_bytes(raw)
        path.symlink_to(outside)
        with self.assertRaises(gate.Blocked): self.preflight()
        path.unlink()
        self.refresh()
        self.write("suite/extra.json", b"{}")
        with self.assertRaises(gate.Blocked): self.preflight()

    def test_deployment_version_stale_future_and_readiness_expectation_block(self):
        original = deepcopy(self.handoff)
        for key, value in (("observed_revision", "other"), ("completed_at", (self.now + timedelta(seconds=1)).isoformat()),
                           ("completed_at", (self.now - timedelta(hours=1)).isoformat())):
            self.handoff = deepcopy(original)
            self.handoff["deployment"][key] = value
            self.pin()
            with self.assertRaises(gate.Blocked): self.preflight()
        self.handoff = deepcopy(original)
        self.handoff["checks"][0]["expected_version"] = "other"
        self.pin()
        with self.assertRaises(gate.Blocked): self.preflight()

    def test_unsupported_protocol_dynamic_or_fixture_inputs_block(self):
        for mutation in ("nats", "local_ref", "env", "product_test", "setup", "artifact", "headers"):
            with self.subTest(mutation=mutation):
                profile, manifest, test = deepcopy(self.profile), deepcopy(self.manifest), deepcopy(self.test)
                if mutation == "nats": self.manifest["targets"]["api"]["provider_contract"] = "nats.v0.3"
                if mutation == "local_ref": self.profile["targets"]["api"]["bindings"]["base_url"] = {"local_ref": "fake"}
                if mutation == "env": self.profile["targets"]["api"]["bindings"]["base_url"] = "env://URL"
                if mutation == "product_test": self.profile["product_test"] = {}
                if mutation == "setup": self.test["setup"] = []
                if mutation == "artifact": self.test["verify"][0]["assert"]["expected_ref"] = "artifact://data/oracle.json"
                if mutation == "headers": self.test["execute"][0]["with"]["request.headers.Authorization"] = "secret"
                self.refresh()
                with self.assertRaises(gate.Blocked): self.preflight()
                self.profile, self.manifest, self.test = profile, manifest, test

    def test_timeout_uses_existing_iso_duration_and_json_match_requires_unsupported_artifact(self):
        for value in (1, 10000, "1000", "PT0S", "PT11S", "PT1M"):
            self.test["execute"][0]["with"]["request.timeout"] = value
            self.refresh()
            with self.assertRaises(gate.Blocked): self.preflight()
        for value in ("PT0.001S", "PT1.5S", "PT10S"):
            self.test["execute"][0]["with"]["request.timeout"] = value
            self.refresh()
            self.preflight()
        self.test["verify"][0]["assert"]["operator"] = "json_match"
        self.refresh()
        with self.assertRaises(gate.Blocked): self.preflight()

    def canonical(self, output):
        run = output / "runtime/target/regression/SIT-TEST/BATCH/RUN"
        run.mkdir(parents=True)
        result = {"suite_id": "SIT-TEST", "profile": "sit", "run_id": "RUN", "batch_id": "BATCH", "plan_digest": "a" * 64,
                  "status": "passed", "evidence_refs": ["readiness/sit-readiness.json"]}
        readiness = {**{k: result[k] for k in ("suite_id", "profile", "run_id", "batch_id", "plan_digest")},
            "contract_version": "sit-readiness.v1", "status": "READY", "started_at": (self.now - timedelta(seconds=3)).isoformat(),
            "finished_at": (self.now - timedelta(seconds=1)).isoformat(), "checks": [{**self.handoff["checks"][0],
                "simulated": False, "status": "READY", "observed_version": "commit-42", "observed_at": (self.now - timedelta(seconds=2)).isoformat(),
                "evidence": {"namespace": "owned-test"}}]}
        (run / "result.json").write_text(json.dumps(result))
        (run / "readiness").mkdir()
        (run / "readiness/sit-readiness.json").write_text(json.dumps(readiness))
        return run / "result.json", result, readiness

    def test_mocked_existing_framework_order_fixed_argv_and_canonical_binding(self):
        handoff, blobs = self.preflight()
        output = self.root / "executed"
        output.mkdir()
        commands = []
        def fake_launch(argv, work, name, deadline):
            commands.append((argv, name))
            if name == "validate": return 0, "validation_status: passed\nplan_digest: " + "a" * 64
            if name == "run":
                path, result, _ = self.canonical(output)
                return 0, "result_json: " + str(path)
            if name == "validate-evidence": return 0, "evidence_validation_status: passed"
            return 0, json.dumps({"suite_id": "SIT-TEST", "profile": "sit", "run_id": "RUN", "batch_id": "BATCH", "status": "passed", "release_evidence_eligible": False})
        with patch.object(gate, "launch", side_effect=fake_launch):
            result = gate.execute(handoff, blobs, output, Path("/approved/jdk/bin/java"))
        self.assertEqual([name for _, name in commands], ["validate", "run", "validate-evidence", "report"])
        self.assertTrue(all("org.springframework.boot.loader.launch.PropertiesLauncher" in argv for argv, _ in commands))
        self.assertTrue(all(argv[0] == "/approved/jdk/bin/java" for argv, _ in commands))
        self.assertEqual(result["status"], "PASSED")
        self.assertFalse(result["release_evidence_eligible"])
        self.assertEqual((output / "docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml").read_bytes(), blobs["suite/contracts/rest_client_v0_3.yaml"])

    def test_plan_drift_stops_before_run(self):
        handoff, blobs = self.preflight()
        output = self.root / "plan-drift"
        output.mkdir()
        with patch.object(gate, "launch", return_value=(0, "validation_status: passed\nplan_digest: " + "b" * 64)) as launch:
            with self.assertRaises(gate.Blocked): gate.execute(handoff, blobs, output, Path("/approved/java"))
            self.assertEqual(launch.call_count, 1)

    def test_runtime_readiness_block_retains_verdict_and_checks_zero_operations(self):
        handoff, blobs = self.preflight()
        output = self.root / "readiness-blocked"
        output.mkdir()
        summary = {}
        def fake_launch(argv, work, name, deadline):
            if name == "validate": return 0, "validation_status: passed\nplan_digest: " + "a" * 64
            if name == "run":
                path, result, readiness = self.canonical(output)
                result.update(status="blocked", provider_results=[], step_results=[], cleanup_failures=[])
                readiness["status"] = "UNKNOWN"
                path.write_text(json.dumps(result))
                (path.parent / "readiness/sit-readiness.json").write_text(json.dumps(readiness))
                return 1, "result_json: " + str(path)
            if name == "validate-evidence": return 0, "evidence_validation_status: passed"
            return 0, json.dumps({"suite_id": "SIT-TEST", "profile": "sit", "run_id": "RUN", "batch_id": "BATCH", "status": "blocked", "release_evidence_eligible": False})
        with patch.object(gate, "launch", side_effect=fake_launch):
            with self.assertRaises(gate.Blocked): gate.execute(handoff, blobs, output, Path("/approved/java"), summary)
        self.assertEqual(summary["framework_verdict"], "blocked")
        self.assertEqual(summary["provider_operations_observed"], 0)

    def test_launcher_uses_clean_environment_and_bounded_output_without_shell(self):
        output = self.root / "launcher"
        output.mkdir()
        child = Mock(returncode=0)
        child.poll.return_value = 0
        child.wait.return_value = 0
        with patch.object(gate.subprocess, "Popen", return_value=child) as spawn:
            status, _ = gate.launch(["/approved/java", "validate"], output, "small", gate.time.monotonic() + 1)
        self.assertEqual(status, 0)
        self.assertEqual(spawn.call_args.kwargs["env"], {"LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"})
        self.assertTrue(spawn.call_args.kwargs["start_new_session"])
        self.assertNotIn("shell", spawn.call_args.kwargs)
        def overflow(*args, **kwargs):
            kwargs["stdout"].write(b"x" * (gate.MAX_LOG + 1))
            return child
        with patch.object(gate.subprocess, "Popen", side_effect=overflow):
            with self.assertRaises(gate.Blocked): gate.launch(["/approved/java"], output, "large", gate.time.monotonic() + 1)

    def test_readiness_simulated_version_adapter_time_identity_and_plan_rejected(self):
        output = self.root / "canonical"
        path, result, readiness = self.canonical(output)
        gate.verify_readiness(self.handoff, result, readiness, self.now)
        for key, value in (("simulated", True), ("observed_version", "wrong"), ("adapter_version", "2"),
                           ("observed_at", (self.now + timedelta(seconds=1)).isoformat()), ("evidence", {}), ("status", "UNKNOWN")):
            altered = deepcopy(readiness)
            altered["checks"][0][key] = value
            with self.assertRaises(gate.Blocked): gate.verify_readiness(self.handoff, result, altered, self.now)
        for key in ("run_id", "batch_id", "plan_digest"):
            altered = deepcopy(readiness)
            altered[key] = "other"
            with self.assertRaises(gate.Blocked): gate.verify_readiness(self.handoff, result, altered, self.now)

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(gate.Blocked): gate.document('{"a":1,"a":2}')


if __name__ == "__main__":
    unittest.main()
