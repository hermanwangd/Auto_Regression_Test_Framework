#!/usr/bin/env python3
"""Package a finite, independently owned local gRPC fixture through the real module CLI.

Run under the workspace's bounded subprocess-group supervisor. This script does not
start a provider or derive its expected response from the provider's actual output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROFILES = {
    "echo.v1": ("swarm.producttest.EchoService", "Echo", "echo.desc.b64"),
    "greeter.v1": ("helloworld.Greeter", "SayHello", "greeter.desc.b64"),
    "routeguide.getfeature.v1": ("routeguide.RouteGuide", "GetFeature", "routeguide.desc.b64"),
}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def valid_oracle(profile, value):
    """Validate only the selected oracle's closed shape; never derive its values."""
    if not isinstance(value, dict):
        return False
    if profile in ("echo.v1", "greeter.v1"):
        return set(value) == {"message"} and isinstance(value["message"], str)
    if profile != "routeguide.getfeature.v1" or set(value) != {"name", "location"} or not isinstance(value["name"], str):
        return False
    point = value["location"]
    return (isinstance(point, dict) and set(point) == {"latitude", "longitude"}
            and type(point["latitude"]) is int and type(point["longitude"]) is int
            and -900000000 <= point["latitude"] <= 900000000
            and -1800000000 <= point["longitude"] <= 1800000000)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-dir", type=Path, required=True)
    parser.add_argument("--profile", choices=PROFILES, required=True)
    parser.add_argument("--fixture-lease-sha256", required=True)
    parser.add_argument("--oracle-file", type=Path, required=True, help='Frozen independent JSON object, e.g. {"message":"swarm-probe"}')
    parser.add_argument("--framework-jar", type=Path, required=True)
    parser.add_argument("--framework-sha256", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expect-verdict", choices=["passed", "failed"], required=True)
    parser.add_argument("--via-host", action="store_true")
    parser.add_argument("--review-metadata-file", type=Path, help="Independent reviewed source and review facts; does not approve preparation")
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")))
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[3]
    fixture = args.fixture_dir.resolve(strict=True)
    oracle = args.oracle_file.resolve(strict=True)
    oracle_bytes = oracle.read_bytes()
    expected = json.loads(oracle_bytes)
    assert valid_oracle(args.profile, expected), ("Independent RouteGuide oracle must have a string name and closed integer Point location"
            if args.profile == "routeguide.getfeature.v1" else "Independent oracle must have one string message")
    assert len(oracle_bytes) <= 65536
    lease_file = fixture / "fixtures/lease.json"
    assert sha(lease_file) == args.fixture_lease_sha256, "Fixture lease differs from independent pin"
    lease = json.loads(lease_file.read_bytes())
    assert lease["profile"] == args.profile
    jar = args.framework_jar.resolve(strict=True)
    assert sha(jar) == args.framework_sha256
    work = args.output_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    suite = work / "suite"
    service, method, descriptor = PROFILES[args.profile]
    for name in (descriptor, "request.json", "lease.json"):
        target = suite / "fixtures" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(fixture / "fixtures" / name, target)
    (suite / "expected").mkdir()
    (suite / "expected/response.json").write_bytes(oracle_bytes)
    (suite / "contracts").mkdir()
    shutil.copyfile(source / "docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml", suite / "contracts/grpc.yaml")
    suite_id = "REVIEWED-GRPC-LOCAL"
    profile = "local_native"
    manifest = {"manifest_version": "v0.3", "suite_id": suite_id, "default_profile": profile,
                "artifact_roots": {"fixtures": "fixtures/", "expected": "expected/"},
                "targets": {"grpc_api": {"provider_contract": "grpc_client.v0.3"}},
                "env_profiles": {profile: {"ref": "env_profiles/local_native.yaml"}}, "tests": [{"ref": "test_cases/unary.yaml"}]}
    write(suite / "suite_manifest.yaml", manifest)
    write(suite / "env_profiles/local_native.yaml", {"profile_id": profile, "execution_mode": "local", "isolation": "per_run",
          "evidence_classification": "framework_verification_only", "downstream_release_evidence": False,
          "targets": {"grpc_api": {"runtime_mode": "native", "bindings": {"target": lease["endpoint"], "owner_run_id": lease["owner_run_id"]}}},
          "product_test": {"contract_version": "product-test.v1", "test_level": "integration", "test_type": "regression", "environment_stage": "local",
              "knowledge_mode": "not_used", "provider_modes": {"grpc_api": "real"}, "dependency_modes": {}, "reviewed_handoff_ref": "handoff/reviewed-tests.json"}})
    write(suite / "test_cases/unary.yaml", {"dsl_version": "v0.3", "test_case_id": "GRPC-UNARY-001", "title": "Reviewed local gRPC unary",
          "execute": [{"id": "unary", "target": "grpc_api", "op": "unary_call", "with": {"grpc.service": service, "grpc.method": method,
              "grpc.descriptor_ref": "artifact://fixtures/" + descriptor, "grpc.message": "artifact://fixtures/request.json", "grpc.timeout": "PT5S"}}],
          "verify": [{"id": "response_oracle", "type": "assertion", "assert": {"actual": "step://unary/response.message", "operator": "json_match", "expected_ref": "artifact://expected/response.json"}}]})
    review = json.loads((source / "samples/41-product-testing/reviewed_local/review-input.json").read_text())
    review.pop("suite_id", None); review.pop("profile", None)
    requirement_ref = "synthetic://requirement/grpc-unary"
    metadata_identity = None
    if args.review_metadata_file is not None:
        metadata_path = args.review_metadata_file.resolve(strict=True)
        metadata_bytes = metadata_path.read_bytes()
        assert len(metadata_bytes) <= 65536
        metadata = json.loads(metadata_bytes)
        allowed = {"review_ref", "reviewed_by", "reviewed_at", "valid_until", "workspace_ref", "project_ref", "repository_revisions", "swarm_source", "framework_baseline", "requirement_ref"}
        required = allowed - {"requirement_ref"}
        assert isinstance(metadata, dict) and required <= set(metadata) <= allowed, "Review metadata fields must be exact and independently supplied"
        requirement_ref = metadata.pop("requirement_ref", requirement_ref)
        review.update(metadata)
        metadata_identity = {"path": str(metadata_path), "sha256": sha(metadata_path)}
    review["reviewed_files"] = [{"path": path, "role": role} for path, role in [
        ("suite_manifest.yaml", "manifest"), ("env_profiles/local_native.yaml", "profile"), ("test_cases/unary.yaml", "test"),
        ("fixtures/" + descriptor, "fixture"), ("fixtures/request.json", "fixture"), ("fixtures/lease.json", "fixture"), ("expected/response.json", "oracle")]]
    review["reviewed_contracts"] = [{"provider_contract": "grpc_client.v0.3", "path": "contracts/grpc.yaml"}]
    review["requirement_mapping"] = [{"requirement_ref": requirement_ref, "test_case_ids": ["GRPC-UNARY-001"], "oracle_refs": ["expected/response.json"], "review_ref": review["review_ref"]}]
    write(suite / "review-input.json", review)
    (suite / "handoff").mkdir()
    java = str(args.java_home / "bin/java")
    with (work / "prepare.stdout.log").open("w") as out, (work / "prepare.stderr.log").open("w") as err:
        result = subprocess.run([java, "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-jar", str(jar), "prepare-product-test-handoff",
            "--suite", str(suite / "suite_manifest.yaml"), "--profile", profile, "--review-input", str(suite / "review-input.json"),
            "--out", str(suite / "handoff/reviewed-tests.json")], stdout=out, stderr=err, timeout=30, cwd=work)
    assert result.returncode == 0, (work / "prepare.stderr.log").read_text() + (work / "prepare.stdout.log").read_text()
    command = [sys.executable, "-B", str(Path(__file__).with_name("smoke.py")), "--framework-jar", str(jar),
        "--framework-sha256", args.framework_sha256, "--suite-dir", str(suite), "--handoff-sha256", sha(suite / "handoff/reviewed-tests.json"),
        "--fixture-lease-sha256", args.fixture_lease_sha256, "--expect-verdict", args.expect_verdict,
        "--test-level", "integration", "--name", "grpc", "--output-dir", str(work / "module"), "--java-home", str(args.java_home)]
    if args.via_host:
        command.append("--via-host")
    with (work / "module.stdout.log").open("w") as out, (work / "module.stderr.log").open("w") as err:
        result = subprocess.run(command, stdout=out, stderr=err, timeout=90, cwd=work)
    assert result.returncode == 0, (work / "module.stderr.log").read_text()
    write(work / "independent-inputs.json", {"profile": args.profile, "fixture_lease_sha256": args.fixture_lease_sha256,
          "oracle_path": str(oracle), "oracle_sha256": sha(oracle), "expected_verdict": args.expect_verdict,
          "source_descriptor_sha256": sha(fixture / "fixtures" / descriptor), "review_metadata": metadata_identity})
    print(json.dumps({"status": "passed", "work": str(work), "receipt": str(work / "module/receipt.json")}, indent=2))

if __name__ == "__main__":
    main()
