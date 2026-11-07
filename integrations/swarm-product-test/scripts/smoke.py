#!/usr/bin/env python3
"""Run one reviewed local fixture through the packaged module and existing packaged framework."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--framework-jar", type=Path, required=True)
parser.add_argument("--framework-sha256", required=True)
parser.add_argument("--suite-dir", type=Path, required=True)
parser.add_argument("--handoff-sha256", required=True, help="Independently supplied reviewed pin; never derived by this script")
parser.add_argument("--expect-verdict", choices=["passed", "failed", "blocked", "skipped"], required=True)
parser.add_argument("--name", required=True)
parser.add_argument("--via-host", action="store_true")
parser.add_argument("--provider-jar", type=Path)
parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")))
args = parser.parse_args()
module = Path(__file__).resolve().parents[1]
suite = args.suite_dir.resolve(strict=True)
jar = args.framework_jar.resolve(strict=True)
assert args.name and all(c.isalnum() or c in "_-" for c in args.name), "Use a simple output name"
work = module / "target/smoke" / args.name
work.mkdir(parents=True, exist_ok=True)
outputs = work / "outputs"
outputs.mkdir(mode=0o700, exist_ok=True)
handoff_path = suite / "handoff/reviewed-tests.json"
handoff = json.loads(handoff_path.read_text())
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(jar) == args.framework_sha256, "Framework artifact differs from supplied pin"
assert sha(handoff_path) == args.handoff_sha256, "Handoff differs from independently supplied pin"
request = {
    "contract_version": "product-test-request.v1", "module_id": "product-test", "module_version": "1",
    "mission_ref": "synthetic-mission:product-module-smoke", "request_ref": "synthetic-request:" + str(uuid.uuid4()),
    "execution_revision": "synthetic-revision-1", "workspace_ref": handoff["workspace_ref"], "project_ref": handoff["project_ref"],
    "repository_revisions": handoff["repository_revisions"],
    "requirement_test_mapping": {item["requirement_ref"]: item["test_case_ids"] for item in handoff["requirement_mapping"]},
    "test_level": "component", "test_type": "regression", "environment_stage": "local", "knowledge_mode": handoff["knowledge_mode"],
    "artifact_root": str(suite), "suite_manifest": str(suite / "suite_manifest.yaml"), "suite_id": handoff["suite_id"], "profile": handoff["profile"],
    "reviewed_handoff": str(handoff_path), "expected_handoff_sha256": args.handoff_sha256,
}
request_file = work / "request.json"
request_file.write_text(json.dumps(request, indent=2) + "\n")
environment = {
    "SWARM_PRODUCT_TEST_FRAMEWORK_JAR": str(jar), "SWARM_PRODUCT_TEST_FRAMEWORK_SHA256": args.framework_sha256,
    "SWARM_PRODUCT_TEST_HANDOFF_SHA256": args.handoff_sha256,
    "SWARM_PRODUCT_TEST_ARTIFACT_ROOT": str(suite), "SWARM_PRODUCT_TEST_OUTPUT_ROOT": str(outputs),
    "SWARM_PRODUCT_TEST_TIMEOUT_MS": "60000", "SWARM_PRODUCT_TEST_MAX_OUTPUT_BYTES": "262144",
}
java = str(args.java_home / "bin/java")
provider = (args.provider_jar or module / "target/swarm-product-test-1.0.0-provider.jar").resolve(strict=True)
host = module / "target/swarm-product-test-1.0.0-host.jar"
if args.via_host:
    classpath = os.pathsep.join([str(module / "target/host-compatibility/classes"), str(host), str(provider),
                                (module / "target/test-classpath.txt").read_text().strip()])
    invocation = ["-cp", classpath, "com.featuredeliveryintelligence.fdi.application.FdiApplication"]
else:
    invocation = ["-jar", str(module / "target/swarm-product-test-1.0.0-cli.jar")]
stdout_file = work / "receipt.json"
with stdout_file.open("w") as out, (work / "stderr.log").open("w") as err:
    completed = subprocess.run([java, "-Xmx512m", "-XX:MaxMetaspaceSize=256m", *invocation, "product-test", "run", "--request", str(request_file)],
                               env=environment, stdout=out, stderr=err, timeout=75, cwd=work)
assert stdout_file.stat().st_size <= 4 * 1024 * 1024, "Receipt output exceeds bound"
receipt = json.loads(stdout_file.read_text())
assert receipt["outcome"] == "COMPLETED", receipt
assert receipt["framework_verdict"] == args.expect_verdict, receipt
assert receipt["evidence_validation"] == "validated", receipt
assert receipt["release_evidence_eligible"] is False
assert receipt["evidence_classification"] == "framework_verification_only"
assert receipt["request"] == request
assert (completed.returncode == 0) == (args.expect_verdict == "passed")
for artifact in receipt["artifacts"].values():
    assert sha(Path(artifact["path"])) == artifact["sha256"], artifact
evidence = {"status": "passed", "claim_boundary": "LOCAL_PACKAGED_FAKE_PROVIDER_NOT_COMPANY_ACCEPTANCE",
    "route": "real_FdiApplication_overlay" if args.via_host else "packaged_module_cli",
    "framework_sha256": args.framework_sha256, "approved_handoff_sha256": args.handoff_sha256,
    "host_sha256": sha(host), "provider_sha256": sha(provider), "module_outcome": receipt["outcome"],
    "framework_verdict": receipt["framework_verdict"], "run_id": receipt["run_id"],
    "invocation_id": receipt["invocation_id"], "receipt_path": str(stdout_file), "receipt_sha256": sha(stdout_file),
    "result": receipt["artifacts"]["result"], "reviewed_handoff_evidence": receipt["artifacts"]["reviewed_handoff"]}
(work / "smoke-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
print(json.dumps(evidence, indent=2))
