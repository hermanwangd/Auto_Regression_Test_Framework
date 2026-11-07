#!/usr/bin/env python3
"""Pinned disposable NATS proof through the existing packaged product-testing module.

prepare never executes the broker. run requires an independently supplied exact-plan
approval. verify uses only the committed, constrained local-ci-policy authority.
All receipts remain local framework verification, never SIT or release evidence.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import tarfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
POLICY = HERE / "nats-ci-policy.json"
CONTRACT = ROOT / "docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml"
CASES = {"positive": "passed", "wrong-oracle": "failed", "downtime": "failed", "recovery": "passed"}
MAX_BYTES = 250 * 1024 * 1024
MIN_FREE = 5 * 1024**3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def check_budget(*roots):
    files = {path.resolve() for root in roots for path in Path(root).rglob("*") if path.is_file()}
    require(sum(path.stat().st_size for path in files) <= MAX_BYTES, "Assets and evidence exceed the 250 MiB budget")


def require_free_space(path):
    require(shutil.disk_usage(path).free >= MIN_FREE, "At least 5 GiB free space is required before every execution")


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON field: " + key)
            result[key] = value
        return result
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 2 * 1024 * 1024,
            "Expected bounded regular JSON: " + str(path))
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def validate_policy(expected_sha=None):
    require(isinstance(expected_sha, str) and re.fullmatch(r"[a-f0-9]{64}", expected_sha),
            "An independently supplied CI policy SHA-256 is required")
    require(sha(POLICY) == expected_sha, "Independent CI policy digest mismatch")
    policy = read_json(POLICY)
    require(policy["schema"] == "local-public-ci-policy.v1" and policy["actor"] == "local-ci-policy"
            and policy["evidence_classification"] == "framework_verification_only"
            and policy["downstream_release_evidence"] is False
            and policy["sit_authority"] is False and policy["case_verdicts"] == CASES,
            "Unsupported local CI authority")
    for relative, digest in policy["source_files"].items():
        path = ROOT / relative
        require(not Path(relative).is_absolute() and ".." not in Path(relative).parts
                and not path.is_symlink() and sha(path) == digest, "CI policy source digest mismatch: " + relative)
    require(set(policy["source_files"]) == set(policy["required_source_files"])
            and {"scripts/public-projects/nats.py", "scripts/public-projects/nats-lock.json",
                 "scripts/public-projects/nats-cases.json", "scripts/public-projects/nats-payload.json",
                 str(CONTRACT.relative_to(ROOT))}.issubset(policy["source_files"]), "Incomplete policy source scope")
    return policy


def download_asset(directory):
    lock = read_json(HERE / "nats-lock.json")
    key = platform.system() + "-" + platform.machine().lower()
    if key == "Darwin-aarch64":
        key = "Darwin-arm64"
    require(key in lock["assets"], "Pinned NATS platform supports Darwin arm64 or Linux x86_64")
    asset = lock["assets"][key]
    directory.mkdir(parents=True, exist_ok=True)
    base = "https://github.com/nats-io/nats-server/releases/download/" + lock["version"] + "/"
    for pin in [lock["checksums"], asset]:
        path = directory / pin["name"]
        if not path.exists():
            request = urllib.request.Request(base + pin["name"], headers={"User-Agent": "framework-sit-public-proof"})
            with urllib.request.urlopen(request, timeout=30) as source, path.open("xb") as output:
                total = 0
                while True:
                    block = source.read(65536)
                    if not block:
                        break
                    total += len(block)
                    require(total <= pin["size"], "Official download exceeds pinned size")
                    output.write(block)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == pin["size"]
                and sha(path) == pin["sha256"], "Official asset checksum mismatch")
    sums = dict((parts[1].lstrip("*"), parts[0]) for line in (directory / "SHA256SUMS").read_text().splitlines()
                if len(parts := line.split()) == 2)
    require(sums.get(asset["name"]) == asset["sha256"], "Official SHA256SUMS disagrees with asset pin")
    binary = directory / asset["name"].removesuffix(".tar.gz") / "nats-server"
    if not binary.exists():
        with tarfile.open(directory / asset["name"], "r:gz") as archive:
            members = archive.getmembers()
            require(len(members) <= 16 and sum(member.size for member in members) <= 64 * 1024**2
                    and all(not (member.issym() or member.islnk() or member.isdev()) for member in members),
                    "Unsupported release archive members")
            name = asset["name"].removesuffix(".tar.gz") + "/nats-server"
            matches = [member for member in members if member.name == name]
            require(len(matches) == 1 and matches[0].isfile() and matches[0].size == asset["binary_size"],
                    "Missing exact regular broker executable")
            binary.parent.mkdir()
            with archive.extractfile(matches[0]) as source, binary.open("xb") as output:
                shutil.copyfileobj(source, output, 65536)
            binary.chmod(0o700)
    require(not binary.is_symlink() and binary.is_file() and binary.stat().st_size == asset["binary_size"]
            and sha(binary) == asset["binary_sha256"] and os.access(binary, os.X_OK), "Extracted broker pin mismatch")
    return lock, asset, binary.resolve()


def substitute(value, variables):
    if isinstance(value, dict):
        return {key: substitute(item, variables) for key, item in value.items()}
    if isinstance(value, list):
        return [substitute(item, variables) for item in value]
    return variables.get(value, value) if isinstance(value, str) else value


def expected_suite(case, port, subject):
    require(case in CASES and isinstance(port, int) and 1 <= port <= 65535
            and re.fullmatch(r"sit\.[a-f0-9]{32}\.echo", subject), "Unsupported generated local case")
    template = read_json(HERE / "nats-cases.json")
    variables = {"__SUITE_ID__": "PUBLIC-NATS-" + case.upper() + "-LOCAL",
                 "__URI__": "nats://127.0.0.1:" + str(port), "__SUBJECT__": subject,
                 "__PUBLISHED__": case != "wrong-oracle"}
    return {"suite_manifest.yaml": substitute(template["manifest"], variables),
            "env_profiles/local_native.yaml": substitute(template["profile"], variables),
            "test_cases/publish.yaml": substitute(template["test"], variables)}


def validate_suites(plan, prepared):
    require(set(plan["cases"]) == set(CASES), "Unexpected generated case coverage")
    for case, entry in plan["cases"].items():
        suite = prepared / case
        expected = expected_suite(case, plan["port"], entry["subject"])
        for relative, document in expected.items():
            require(read_json(suite / relative) == document, "Generated suite differs from committed expectation: " + relative)
        require((suite / "fixtures/payload.json").read_bytes() == (HERE / "nats-payload.json").read_bytes(), "Payload differs from reviewed fixture")
        require((suite / "contracts/nats.yaml").read_bytes() == CONTRACT.read_bytes(), "Provider contract differs from committed expectation")
        handoff = read_json(suite / "handoff/reviewed-tests.json")
        require(handoff == expected_handoff(case, plan, suite), "Generated handoff facts differ from committed expectations")
        require(sha(suite / "handoff/reviewed-tests.json") == entry["handoff_sha256"]
                and handoff["reviewed_by"] == "local-ci-policy" and handoff["review_ref"] == "local-ci-policy://public-nats/v1"
                and handoff["repository_revisions"] == {"nats-server": plan["source_commit"]}, "Generated handoff differs from constrained authority")
        for field in ["reviewed_files", "reviewed_contracts"]:
            for item in handoff[field]:
                require(sha(suite / item["path"]) == item["sha256"], "Reviewed input changed after preparation")
        require({item["path"] for item in handoff["reviewed_files"]} == set(expected) | {"fixtures/payload.json"}
                and len(handoff["reviewed_contracts"]) == 1
                and handoff["reviewed_contracts"][0]["provider_contract"] == "nats.v0.3", "Unexpected reviewed graph")


def expected_handoff(case, plan, suite, include_hashes=True):
    template = read_json(HERE / "nats-cases.json")
    review = dict(template["review_base"])
    review.update({"suite_id": "PUBLIC-NATS-" + case.upper() + "-LOCAL", "profile": "local_native",
                   "reviewed_by": "local-ci-policy", "review_ref": "local-ci-policy://public-nats/v1",
                   "reviewed_at": plan["reviewed_at"], "valid_until": plan["valid_until"],
                   "workspace_ref": "local-public-nats", "project_ref": "public-nats-server",
                   "repository_revisions": {"nats-server": plan["source_commit"]},
                   "reviewed_files": [{"path": relative, "role": role} for relative, role in
                       [("suite_manifest.yaml", "manifest"), ("env_profiles/local_native.yaml", "profile"),
                        ("test_cases/publish.yaml", "test"), ("fixtures/payload.json", "fixture")]],
                   "reviewed_contracts": [{"path": "contracts/nats.yaml", "provider_contract": "nats.v0.3"}],
                   "requirement_mapping": [{"requirement_ref": "local-ci-policy://requirement/native-publish-echo",
                       "test_case_ids": ["NATS-PUBLIC-TC-001"], "oracle_refs": ["test_cases/publish.yaml#published", "test_cases/publish.yaml#subject"],
                       "review_ref": "local-ci-policy://public-nats/v1"}]})
    if include_hashes:
        for field in ["reviewed_files", "reviewed_contracts"]:
            for item in review[field]:
                item["sha256"] = sha(suite / item["path"])
    return review


def validate_plan(plan):
    lock = read_json(HERE / "nats-lock.json")
    require(plan["source_commit"] == lock["source_commit"]
            and any(plan["asset_sha256"] == asset["sha256"] and plan["binary_sha256"] == asset["binary_sha256"]
                    for asset in lock["assets"].values()), "Prepared product identities differ from official locks")
    require(plan["schema"] == "public-nats-plan.v1" and plan["status"] == "prepared_not_approved"
            and plan["authority"] == "local-ci-policy" and plan["evidence_classification"] == "framework_verification_only"
            and plan["release_evidence_eligible"] is False and plan["native_agent_executed"] is False and plan["sit_executed"] is False,
            "Prepared scope cannot become SIT, native agent or release evidence")
    require(re.fullmatch(r"sit-[a-f0-9]{32}", plan["broker_name"]) is not None, "Invalid owned broker identity")
    require(plan["java"] == str(java_path()), "Prepared Java executable differs from configured Java")
    reviewed = dt.datetime.fromisoformat(plan["reviewed_at"].replace("Z", "+00:00"))
    expiry = dt.datetime.fromisoformat(plan["valid_until"].replace("Z", "+00:00"))
    now = dt.datetime.now(dt.timezone.utc)
    require(reviewed <= now < expiry and expiry - reviewed <= dt.timedelta(hours=2), "Prepared local authority has expired")


def java_path():
    home = os.environ.get("JAVA_HOME")
    executable = Path(home) / "bin/java" if home else Path(shutil.which("java") or "/missing/java")
    require(executable.is_file(), "Java 17+ executable is required")
    return executable.resolve()


def prepare(args, output):
    policy = validate_policy(args.ci_policy_sha256)
    output.mkdir(parents=True, exist_ok=False)
    require_free_space(output)
    framework, module = args.framework_jar.resolve(strict=True), args.module_cli.resolve(strict=True)
    require(sha(framework) == args.framework_sha256, "Independently supplied framework pin mismatch")
    require(sha(module) == args.module_sha256, "Independently supplied module pin mismatch")
    lock, asset, binary = download_asset((args.asset_dir or output / "assets").resolve())
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    prepared = output / "prepared"
    prepared.mkdir()
    plan = {"schema": "public-nats-plan.v1", "status": "prepared_not_approved", "authority": "local-ci-policy",
            "policy_sha256": sha(POLICY), "framework_jar": str(framework), "framework_sha256": sha(framework),
            "module_cli": str(module), "module_sha256": sha(module), "java": str(java_path()),
            "binary": str(binary), "binary_sha256": asset["binary_sha256"], "asset_sha256": asset["sha256"],
            "source_commit": lock["source_commit"], "port": port, "broker_name": "sit-" + uuid.uuid4().hex,
            "cases": {}, "evidence_classification": "framework_verification_only", "release_evidence_eligible": False,
            "native_agent_executed": False, "sit_executed": False}
    now = dt.datetime.now(dt.timezone.utc)
    plan["reviewed_at"] = (now - dt.timedelta(seconds=1)).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    plan["valid_until"] = (now + dt.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    for case in CASES:
        suite = prepared / case
        subject = "sit." + uuid.uuid4().hex + ".echo"
        for relative, document in expected_suite(case, port, subject).items():
            write_json(suite / relative, document)
        (suite / "fixtures").mkdir()
        shutil.copyfile(HERE / "nats-payload.json", suite / "fixtures/payload.json")
        (suite / "contracts").mkdir()
        shutil.copyfile(CONTRACT, suite / "contracts/nats.yaml")
        (suite / "handoff").mkdir()
        review = expected_handoff(case, plan, suite, include_hashes=False)
        write_json(suite / "review-input.json", review)
        command = [plan["java"], "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-jar", str(framework),
                   "prepare-product-test-handoff", "--suite", str(suite / "suite_manifest.yaml"), "--profile", "local_native",
                   "--review-input", str(suite / "review-input.json"), "--out", str(suite / "handoff/reviewed-tests.json")]
        with (suite / "prepare.log").open("w") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        require(completed.returncode == 0, "Framework handoff preparation rejected " + case + "; inspect prepare.log")
        plan["cases"][case] = {"subject": subject, "expected_verdict": CASES[case],
                               "handoff_sha256": sha(suite / "handoff/reviewed-tests.json")}
    validate_suites(plan, prepared)
    check_budget(output, binary.parent.parent)
    write_json(output / "prepared-plan.json", plan)
    write_json(output / "preparation-evidence.json", {"status": "prepared_not_approved", "actor": policy["actor"],
               "plan_sha256": sha(output / "prepared-plan.json"), "policy_sha256": sha(POLICY), "source_files": policy["source_files"]})
    return plan


def port_closed(port):
    with socket.socket() as connection:
        connection.settimeout(0.2)
        return connection.connect_ex(("127.0.0.1", port)) != 0


class Broker:
    def __init__(self, plan, directory):
        self.plan, self.directory, self.process, self.log = plan, directory, None, None
    def start(self):
        require_free_space(self.directory)
        require(port_closed(self.plan["port"]), "Selected port is already occupied; no existing process was changed")
        require(sha(self.plan["binary"]) == self.plan["binary_sha256"], "Broker binary changed")
        self.log = (self.directory / ("broker-" + uuid.uuid4().hex + ".log")).open("wb")
        command = [self.plan["binary"], "-a", "127.0.0.1", "-p", str(self.plan["port"]), "-n", self.plan["broker_name"]]
        self.process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=self.log, stderr=subprocess.STDOUT,
                                        cwd=self.directory, env={})
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            require(self.process.poll() is None, "Owned broker exited before readiness; inspect broker log")
            try:
                with socket.create_connection(("127.0.0.1", self.plan["port"]), timeout=0.2) as connection:
                    line = connection.makefile("rb").readline(8193)
                    require(len(line) <= 8192 and line.startswith(b"INFO "), "Unexpected loopback peer")
                    info = json.loads(line[5:])
                    require(info.get("server_name") == self.plan["broker_name"] and info.get("host") == "127.0.0.1",
                            "Loopback peer is not the owned broker")
                    require(info.get("version") == read_json(HERE / "nats-lock.json")["version"].removeprefix("v"),
                            "Owned broker reported an unexpected release version")
                    return {"pid": self.process.pid, "argv": command, "server_name": info["server_name"], "version": info.get("version")}
            except (ConnectionError, OSError):
                time.sleep(0.03)
        raise ValueError("Owned broker did not become ready within five seconds")
    def stop(self):
        if self.process is None:
            if self.log is not None:
                self.log.close()
            return {"terminated": True, "port_closed": port_closed(self.plan["port"])}
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.log.close()
        evidence = {"pid": self.process.pid, "exit_code": self.process.returncode, "terminated": self.process.poll() is not None,
                    "port_closed": port_closed(self.plan["port"])}
        self.process = None
        require(evidence["terminated"] and evidence["port_closed"], "Owned broker cleanup is unconfirmed")
        return evidence


def run_case(plan, prepared, case, output):
    require_free_space(output)
    validate_suites(plan, prepared)
    require(sha(plan["framework_jar"]) == plan["framework_sha256"] and sha(plan["module_cli"]) == plan["module_sha256"],
            "Built artifact changed after preparation")
    suite = prepared / case
    work = output / case
    work.mkdir()
    (work / "outputs").mkdir(mode=0o700)
    handoff = read_json(suite / "handoff/reviewed-tests.json")
    pin = plan["cases"][case]["handoff_sha256"]
    request = {"contract_version": "product-test-request.v1", "module_id": "product-test", "module_version": "1",
               "mission_ref": "local-ci-policy:public-nats", "request_ref": "local-ci-policy:" + uuid.uuid4().hex,
               "execution_revision": "public-nats-" + plan["source_commit"], "workspace_ref": handoff["workspace_ref"],
               "project_ref": handoff["project_ref"], "repository_revisions": handoff["repository_revisions"],
               "requirement_test_mapping": {item["requirement_ref"]: item["test_case_ids"] for item in handoff["requirement_mapping"]},
               "test_level": "integration", "test_type": "regression", "environment_stage": "local", "knowledge_mode": "not_used",
               "artifact_root": str(suite), "suite_manifest": str(suite / "suite_manifest.yaml"), "suite_id": handoff["suite_id"],
               "profile": "local_native", "reviewed_handoff": str(suite / "handoff/reviewed-tests.json"), "expected_handoff_sha256": pin}
    write_json(work / "request.json", request)
    environment = {"SWARM_PRODUCT_TEST_FRAMEWORK_JAR": plan["framework_jar"], "SWARM_PRODUCT_TEST_FRAMEWORK_SHA256": plan["framework_sha256"],
                   "SWARM_PRODUCT_TEST_HANDOFF_SHA256": pin, "SWARM_PRODUCT_TEST_ARTIFACT_ROOT": str(suite),
                   "SWARM_PRODUCT_TEST_OUTPUT_ROOT": str(work / "outputs"), "SWARM_PRODUCT_TEST_TIMEOUT_MS": "60000",
                   "SWARM_PRODUCT_TEST_MAX_OUTPUT_BYTES": "262144"}
    with (work / "receipt.json").open("w") as stdout, (work / "stderr.log").open("w") as stderr:
        completed = subprocess.run([plan["java"], "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-jar", plan["module_cli"],
                                    "product-test", "run", "--request", str(work / "request.json")],
                                   env=environment, cwd=work, stdout=stdout, stderr=stderr, timeout=75)
    receipt = read_json(work / "receipt.json")
    require(receipt["outcome"] == "COMPLETED" and receipt["framework_verdict"] == CASES[case]
            and receipt["evidence_validation"] == "validated" and receipt["release_evidence_eligible"] is False
            and receipt["evidence_classification"] == "framework_verification_only"
            and receipt["request"] == request and (completed.returncode == 0) == (CASES[case] == "passed"),
            "Module case failed validation: " + case + "; inspect receipt.json")
    for artifact in receipt["artifacts"].values():
        path = Path(artifact["path"])
        require(path.resolve().is_relative_to(work) and sha(path) == artifact["sha256"], "Imported artifact digest/path mismatch")
    result = read_json(receipt["artifacts"]["result"]["path"])
    require(result["provider_results"][0]["provider_type"] == "nats"
            and result["provider_results"][0]["runtime_mode"] == "native", "Actual native provider metadata missing")
    expected_failure = {"wrong-oracle": "ASSERTION_FAILED", "downtime": "NATS_CONNECTION_FAILED"}.get(case)
    if expected_failure:
        require(result["failure"]["code"] == expected_failure, "Incorrect negative-path failure: " + case)
    return {"expected_verdict": CASES[case], "actual_verdict": receipt["framework_verdict"], "outcome": receipt["outcome"],
            "failure_code": result.get("failure", {}).get("code"), "run_id": receipt["run_id"],
            "invocation_id": receipt["invocation_id"], "receipt_path": str(work / "receipt.json"), "receipt_sha256": sha(work / "receipt.json")}


def execute(plan_dir, output, approval=None, policy_mode=False):
    require_free_space(plan_dir)
    plan = read_json(plan_dir / "prepared-plan.json")
    validate_plan(plan)
    validate_policy(plan["policy_sha256"])
    validate_suites(plan, plan_dir / "prepared")
    if not policy_mode:
        require(approval is not None, "run requires an independently supplied exact-plan approval")
        decision = read_json(approval)
        require(decision.get("decision") == "APPROVE" and decision.get("approved_by") not in [None, "", "local-ci-policy"]
                and decision.get("plan_sha256") == sha(plan_dir / "prepared-plan.json")
                and decision.get("handoff_sha256") == {case: value["handoff_sha256"] for case, value in plan["cases"].items()},
                "Independent approval does not match exact prepared plan and handoff pins")
    output.mkdir(parents=True, exist_ok=False)
    broker = Broker(plan, output)
    lifecycle, results = [], {}
    try:
        lifecycle.append({"start": broker.start()})
        results["positive"] = run_case(plan, plan_dir / "prepared", "positive", output)
        results["wrong-oracle"] = run_case(plan, plan_dir / "prepared", "wrong-oracle", output)
        lifecycle.append({"stop": broker.stop()})
        results["downtime"] = run_case(plan, plan_dir / "prepared", "downtime", output)
        lifecycle.append({"recovery_start": broker.start()})
        results["recovery"] = run_case(plan, plan_dir / "prepared", "recovery", output)
    finally:
        lifecycle.append({"final_cleanup": broker.stop()})
        write_json(output / "lifecycle.json", lifecycle)
    require(len({value["run_id"] for value in results.values()}) == len(CASES)
            and len({value["invocation_id"] for value in results.values()}) == len(CASES), "Fresh invocation identities were reused")
    check_budget(output, plan_dir, Path(plan["binary"]).parent.parent)
    evidence = {"schema": "public-nats-evidence.v1", "status": "passed", "actor": "local-ci-policy",
                "execution_authority": "committed-local-ci-policy" if policy_mode else "independent-exact-plan-review",
                "policy_sha256": plan["policy_sha256"], "prepared_plan_sha256": sha(plan_dir / "prepared-plan.json"),
                "product": "nats-io/nats-server", "source_commit": plan["source_commit"], "binary_sha256": plan["binary_sha256"],
                "framework_sha256": plan["framework_sha256"], "module_sha256": plan["module_sha256"], "cases": results,
                "native_agent_executed": False, "sit_executed": False, "release_evidence_eligible": False,
                "evidence_classification": "framework_verification_only"}
    write_json(output / "summary.json", evidence)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "run", "verify"])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--framework-jar", type=Path)
    parser.add_argument("--framework-sha256")
    parser.add_argument("--module-cli", type=Path)
    parser.add_argument("--module-sha256")
    parser.add_argument("--asset-dir", type=Path)
    parser.add_argument("--prepared", type=Path)
    parser.add_argument("--approval", type=Path)
    parser.add_argument("--ci-policy-sha256")
    args = parser.parse_args()
    output = args.output.resolve()
    if args.command in ["prepare", "verify"]:
        require(args.framework_jar and args.framework_sha256 and args.module_cli and args.module_sha256,
                "Preparation requires independent framework and module artifact pins")
        prepare(args, output)
        result = execute(output, output / "execution", policy_mode=True) if args.command == "verify" else read_json(output / "preparation-evidence.json")
    else:
        require(args.prepared, "run requires --prepared")
        result = execute(args.prepared.resolve(strict=True), output, args.approval)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
