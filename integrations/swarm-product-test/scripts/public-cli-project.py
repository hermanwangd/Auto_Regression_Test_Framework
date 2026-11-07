#!/usr/bin/env python3
"""Prepare approved public CLI inputs; execute only through the existing smoke.py.

SQLite compilation follows https://www.sqlite.org/howtocompile.html#compiling_the_command_line_interface.
Literal argv/stdin follow https://www.sqlite.org/cli.html. No shell or SUT-derived oracle.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile

MODULE = Path(__file__).resolve().parents[1]
REPO = MODULE.parents[1]
FIXTURES = MODULE / "public-projects/sqlite"
BUDGET = 200 * 1024**2
RESERVE = 5 * 1024**3
CLAIM = "LOCAL_CLI_INTEGRATION_NOT_SHARED_SIT_OR_RELEASE_ACCEPTANCE"


def sha(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def budget(*roots):
    total = 0
    for root in roots:
        root = Path(root)
        require(not root.is_symlink(), "Owned root must not be a symlink")
        anchor = root if root.exists() else root.parent
        require(shutil.disk_usage(anchor).free >= RESERVE, "Keep at least 5 GiB free")
        if root.exists():
            for item in root.rglob("*"):
                require(not item.is_symlink(), "Owned tree must not contain symlinks")
                if item.is_file():
                    total += item.stat().st_size
    require(total <= BUDGET, "Public CLI cache plus evidence exceed 200 MiB")
    return total


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        raise ValueError("Pinned asset URL must not redirect")


def download(spec, destination, algorithm):
    # URLs come only from the reviewed, committed lock; no URL CLI option exists.
    require(spec["url"].startswith(("https://www.sqlite.org/", "https://raw.githubusercontent.com/sqlite/sqlite/")),
            "Only the reviewed official SQLite hosts are allowed")
    digest = hashlib.new(algorithm)
    size = 0
    with urllib.request.build_opener(NoRedirect).open(spec["url"], timeout=30) as response:
        require(response.status == 200, "Asset download failed")
        with destination.open("xb") as output:
            while True:
                block = response.read(65536)
                if not block:
                    break
                size += len(block)
                require(size <= spec["max_bytes"], "Asset exceeds its reviewed size bound")
                digest.update(block)
                output.write(block)
    require(digest.hexdigest() == spec[algorithm], "Official asset digest differs from reviewed lock")


def archive_members(archive, lock):
    expected = {lock["root"] + "/" + name for name in lock["members"]}
    files = []
    total = 0
    seen = set()
    for item in archive.infolist():
        path = PurePosixPath(item.filename)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in item.filename,
                "Unsafe archive path")
        require(item.filename not in seen, "Duplicate archive member")
        seen.add(item.filename)
        mode = item.external_attr >> 16
        require(not stat.S_ISLNK(mode), "Archive symlink is forbidden")
        if item.is_dir():
            require(item.filename == lock["root"] + "/", "Unexpected archive directory")
            continue
        require(item.filename in expected, "Unexpected archive member")
        require(not item.flag_bits & 1, "Encrypted archive is forbidden")
        total += item.file_size
        require(total <= 20 * 1024**2, "Expanded source archive exceeds 20 MiB")
        files.append(item)
    require({item.filename for item in files} == expected, "Missing source archive member")
    return files


def fetch(args):
    lock = read(FIXTURES / "asset-lock.json")
    cache = args.cache_dir.resolve()
    require(not cache.exists(), "Cache directory must be fresh; reuse via prepare after verifying build.json")
    cache.parent.mkdir(parents=True, exist_ok=True)
    budget(cache)
    cache.mkdir(mode=0o700)
    download(lock["archive"], cache / "source.zip", "sha3_256")
    require(sha(cache / "source.zip") == lock["archive"]["sha256"], "Source archive SHA-256 mismatch")
    download(lock["license"], cache / "LICENSE.md", "sha256")
    source = cache / "source"
    source.mkdir()
    with zipfile.ZipFile(cache / "source.zip") as archive:
        for item in archive_members(archive, lock["archive"]):
            with archive.open(item) as stream, (source / PurePosixPath(item.filename).name).open("xb") as output:
                shutil.copyfileobj(stream, output)
    require(sha(source / "sqlite3.c", "sha3_256") == lock["archive"]["sqlite3_c_sha3_256"],
            "sqlite3.c differs from the official release source digest")
    require(lock["source_id"] in (source / "sqlite3.c").read_text(), "SQLite source identity mismatch")
    require({name: sha(source / name) for name in lock["archive"]["members"]} == lock["source_sha256"],
            "Source files differ from reviewed identities")
    compiler = Path(shutil.which(args.cc) or args.cc).resolve(strict=True)
    executable = cache / "sqlite3"
    command = [str(compiler), *lock["compile_flags"], "-o", str(executable)]
    with (cache / "compile.log").open("w") as log:
        completed = subprocess.run(command, cwd=source, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    require(completed.returncode == 0, "SQLite compilation failed; inspect compile.log")
    require(executable.is_file() and not executable.is_symlink(), "Compiled executable must be a regular file")
    metadata = {"project": "sqlite", "version": lock["version"], "repository": lock["repository"],
                "revision": lock["revision"], "source_id": lock["source_id"],
                "asset_lock_sha256": sha(FIXTURES / "asset-lock.json"),
                "archive_sha256": sha(cache / "source.zip"), "archive_sha3_256": sha(cache / "source.zip", "sha3_256"),
                "license_sha256": sha(cache / "LICENSE.md"),
                "source_sha256": {name: sha(source / name) for name in lock["archive"]["members"]},
                "compiler": str(compiler), "compiler_sha256": sha(compiler), "compile_argv": command,
                "platform": platform.platform(), "architecture": platform.machine(),
                "binary": str(executable), "binary_sha256": sha(executable),
                "product_executed": False}
    write(cache / "build.json", metadata)
    budget(cache)
    print(json.dumps(metadata, indent=2))


def checked_build(cache):
    lock = read(FIXTURES / "asset-lock.json")
    build = read(cache / "build.json")
    require(build["asset_lock_sha256"] == sha(FIXTURES / "asset-lock.json"), "Build uses a different source lock")
    require(build["revision"] == lock["revision"] and build["source_id"] == lock["source_id"], "Build provenance mismatch")
    require(sha(cache / "source.zip", "sha3_256") == lock["archive"]["sha3_256"], "Source archive changed")
    require(sha(cache / "source.zip") == build["archive_sha256"], "Source archive SHA-256 changed")
    require(build["archive_sha256"] == lock["archive"]["sha256"] and build["source_sha256"] == lock["source_sha256"],
            "Build differs from pinned source/archive identities")
    require(sha(cache / "LICENSE.md") == lock["license"]["sha256"] == build["license_sha256"], "License changed")
    for name, digest in build["source_sha256"].items():
        require(name in lock["archive"]["members"] and sha(cache / "source" / name) == digest, "Source file changed")
    require(set(build["source_sha256"]) == set(lock["archive"]["members"]), "Incomplete source identities")
    with zipfile.ZipFile(cache / "source.zip") as archive:
        for item in archive_members(archive, lock["archive"]):
            require(hashlib.sha256(archive.read(item)).hexdigest() == sha(cache / "source" / PurePosixPath(item.filename).name),
                    "Source file differs from the pinned archive")
    compiler = Path(build["compiler"])
    require(compiler.is_file() and sha(compiler) == build["compiler_sha256"], "Compiler identity changed")
    binary = cache / "sqlite3"
    require(str(binary) == build["binary"] and not binary.is_symlink() and sha(binary) == build["binary_sha256"],
            "Built executable changed")
    require(build["compile_argv"] == [str(compiler), *lock["compile_flags"], "-o", str(binary)], "Compile flags changed")
    return build


def suite_objects(case, binary, binary_hash):
    manifest = {"manifest_version": "v0.3", "suite_id": "PUBLIC-SQLITE-" + case["name"],
                "default_profile": "local_cli", "artifact_roots": {"expected": "expected/"},
                "targets": {"cli": {"provider_contract": "cli_process.v0.3"}},
                "env_profiles": {"local_cli": {"ref": "env_profiles/local_cli.yaml"}},
                "tests": [{"ref": "test_cases/cli.yaml"}]}
    profile = {"profile_id": "local_cli", "execution_mode": "local", "isolation": "per_run",
               "evidence_classification": "framework_verification_only", "downstream_release_evidence": False,
               "targets": {"cli": {"runtime_mode": "native", "bindings": {
                   "executable": binary, "executable_sha256": "0" * 64 if case.get("bad_pin") else binary_hash}}},
               "product_test": {"contract_version": "product-test.v1", "test_level": "integration", "test_type": "regression",
                                "environment_stage": "local", "knowledge_mode": "not_used", "provider_modes": {"cli": "real"},
                                "dependency_modes": {}, "reviewed_handoff_ref": "handoff/reviewed-tests.json"}}
    inputs = {"process.argv": case["argv"], "process.stdin": case.get("stdin", ""), "process.cwd": "work",
              "process.timeout_ms": case.get("timeout_ms", 5000), "process.max_output_bytes": case.get("max_output_bytes", 16384)}
    assertions = [{"id": name, "type": "assertion", "assert": {"actual": "step://invoke/" + output,
                   "operator": "equals", "expected": value}} for name, output, value in [
                       ("exit_ok", "exit_code", case["exit"]), ("stdout_ok", "stdout", case["stdout"]),
                       ("stderr_ok", "stderr", case.get("stderr", ""))]]
    test = {"dsl_version": "v0.3", "test_case_id": "TC-SQLITE", "title": "Reviewed public SQLite CLI behavior",
            "execute": [{"id": "invoke", "target": "cli", "op": "run_command", "with": inputs}], "verify": assertions}
    return {"suite_manifest.yaml": manifest, "env_profiles/local_cli.yaml": profile, "test_cases/cli.yaml": test}


def check_suite(suite, case, binary, binary_hash):
    for relative, expected in suite_objects(case, binary, binary_hash).items():
        require(read(suite / relative) == expected, "Generated suite differs from the committed oracle: " + relative)


def policy(args):
    path = getattr(args, "local_ci_policy", None)
    if not path:
        return None
    require(getattr(args, "policy_sha256", None) and sha(path) == args.policy_sha256, "CI policy differs from external reviewed pin")
    value = read(path)
    require(value["actor"] == "local-ci-policy" and value["claim"] == CLAIM, "Wrong local CI policy boundary")
    required = {str(Path(__file__).resolve().relative_to(REPO)), str((FIXTURES / "asset-lock.json").relative_to(REPO)),
                str((FIXTURES / "cases.json").relative_to(REPO)),
                "docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml",
                "samples/41-product-testing/reviewed_local/review-input.json"}
    require(set(value["reviewed_files"]) == required, "CI policy must pin the complete harness, lock, oracle and template set")
    for relative, digest in value["reviewed_files"].items():
        require(sha(REPO / relative) == digest, "Committed reviewed input changed: " + relative)
    return value


def review_object(case, objects, build, actor, reference, reviewed_at, valid_until):
    review = read(REPO / "samples/41-product-testing/reviewed_local/review-input.json")
    review.pop("suite_id", None)
    review.pop("profile", None)
    review.update(reviewed_by=actor, review_ref=reference, project_ref="public-sqlite-cli-validation",
                  workspace_ref="local-public-cli-workspace", repository_revisions={build["repository"]: build["revision"]},
                  reviewed_at=reviewed_at, valid_until=valid_until)
    review["reviewed_files"] = [{"path": name, "role": role} for name, role in [
        ("suite_manifest.yaml", "manifest"), ("env_profiles/local_cli.yaml", "profile"), ("test_cases/cli.yaml", "test")]]
    review["reviewed_contracts"] = [{"path": "contracts/cli_process_v0_3.yaml", "provider_contract": "cli_process.v0.3"}]
    review["requirement_mapping"] = [{"requirement_ref": build["repository"] + "/tree/" + build["revision"] + "#" + case["name"],
        "test_case_ids": ["TC-SQLITE"], "oracle_refs": ["test_cases/cli.yaml#" + item["id"] for item in objects["test_cases/cli.yaml"]["verify"]],
        "review_ref": reference}]
    return review


def expected_handoff(suite, review):
    handoff = json.loads(json.dumps(review))
    handoff.update(suite_id=read(suite / "suite_manifest.yaml")["suite_id"], profile="local_cli")
    for field in ("reviewed_files", "reviewed_contracts"):
        for item in handoff[field]:
            item["sha256"] = sha(suite / item["path"])
    return handoff


def java(args):
    return str(args.java_home.resolve(strict=True) / "bin/java")


def prepare(args):
    authority = policy(args)
    require(authority or (args.reviewed_by and args.review_ref), "Supply review identity or the approved local CI policy")
    cache = args.cache_dir.resolve(strict=True)
    evidence = args.evidence_dir.resolve()
    require(not evidence.exists(), "Evidence directory must be fresh")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    budget(cache, evidence)
    build = checked_build(cache)
    jar = args.framework_jar.resolve(strict=True)
    require(sha(jar) == args.framework_sha256, "Framework artifact differs from external pin")
    cli = MODULE / "target/swarm-product-test-1.0.0-cli.jar"
    require(sha(cli) == args.module_sha256, "Module artifact differs from external pin")
    now = datetime.now(timezone.utc)
    prepared = []
    evidence.mkdir(mode=0o700)
    for case in read(FIXTURES / "cases.json")["cases"]:
        suite = evidence / "suites" / case["name"]
        objects = suite_objects(case, build["binary"], build["binary_sha256"])
        for relative, value in objects.items():
            write(suite / relative, value)
        contract = suite / "contracts/cli_process_v0_3.yaml"
        contract.parent.mkdir()
        shutil.copyfile(REPO / "docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml", contract)
        review = review_object(case, objects, build, "local-ci-policy" if authority else args.reviewed_by,
                               "local-ci-policy://sqlite-v1" if authority else args.review_ref,
                               (now - timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
                               (now + timedelta(days=1)).isoformat().replace("+00:00", "Z"))
        write(suite / "review-input.json", review)
        handoff = suite / "handoff/reviewed-tests.json"
        command = [java(args), "-Xmx512m", "-jar", str(jar), "prepare-product-test-handoff", "--suite", str(suite / "suite_manifest.yaml"),
                   "--profile", "local_cli", "--review-input", str(suite / "review-input.json"), "--out", str(handoff)]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
        (suite / "prepare.log").write_text(completed.stdout + completed.stderr)
        require(completed.returncode == 0, "Framework handoff preparation failed: " + case["name"])
        prepared.append({"name": case["name"], "suite": str(suite), "handoff_sha256": sha(handoff)})
    plan = {"status": "prepared_not_runtime_approved" if not authority else "prepared_under_reviewed_local_ci_policy",
            "actor": "local-ci-policy" if authority else args.reviewed_by, "claim": CLAIM,
            "cache_dir": str(cache), "build": build, "framework_jar": str(jar), "framework_sha256": args.framework_sha256,
            "module_cli_sha256": args.module_sha256, "harness_sha256": sha(Path(__file__)),
            "asset_lock_sha256": sha(FIXTURES / "asset-lock.json"), "fixtures_sha256": sha(FIXTURES / "cases.json"),
            "contract_sha256": sha(REPO / "docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml"),
            "policy_sha256": args.policy_sha256 if authority else None, "cases": prepared}
    write(evidence / "prepared-plan.json", plan)
    budget(cache, evidence)
    print("prepared_plan_sha256", sha(evidence / "prepared-plan.json"), flush=True)


def verify(args):
    evidence = args.evidence_dir.resolve(strict=True)
    plan_path = evidence / "prepared-plan.json"
    plan = read(plan_path)
    authority = policy(args)
    require(bool(authority) != bool(args.approval_file), "Select exactly one independent approval file or reviewed local CI policy")
    if authority:
        require(plan["actor"] == "local-ci-policy" and plan["policy_sha256"] == args.policy_sha256,
                "Prepared plan must use the same reviewed local CI policy")
    else:
        approval = read(args.approval_file)
        expected = {"prepared_plan_sha256": sha(plan_path), "harness_sha256": sha(Path(__file__)),
                    "framework_sha256": plan["framework_sha256"], "module_cli_sha256": plan["module_cli_sha256"],
                    "binary_sha256": plan["build"]["binary_sha256"],
                    "cases": {case["name"]: case["handoff_sha256"] for case in plan["cases"]}}
        require(approval.get("status") == "approved" and approval.get("reviewer") and approval.get("review_ref"), "Runtime review is required")
        for key, value in expected.items():
            require(approval.get(key) == value, "Runtime approval pin differs: " + key)
    require(plan["claim"] == CLAIM and plan["harness_sha256"] == sha(Path(__file__)), "Harness or claim changed")
    require(plan["asset_lock_sha256"] == sha(FIXTURES / "asset-lock.json") and
            plan["fixtures_sha256"] == sha(FIXTURES / "cases.json"), "Reviewed fixtures changed")
    require(plan["contract_sha256"] == sha(REPO / "docs/02-architecture/contracts/provider-contracts/cli_process_v0_3.yaml"),
            "Source CLI contract differs from reviewed plan")
    cache = Path(plan["cache_dir"])
    require(checked_build(cache) == plan["build"], "Build provenance changed")
    require(sha(plan["framework_jar"]) == plan["framework_sha256"], "Framework artifact changed")
    require(sha(MODULE / "target/swarm-product-test-1.0.0-cli.jar") == plan["module_cli_sha256"], "Module artifact changed")
    cases = read(FIXTURES / "cases.json")["cases"]
    require([case["name"] for case in cases] == [case["name"] for case in plan["cases"]], "Case coverage changed")
    # Validate every static suite before launching any public executable.
    for case, record in zip(cases, plan["cases"]):
        suite = evidence / "suites" / case["name"]
        require(str(suite) == record["suite"], "Suite path escaped the prepared evidence root")
        check_suite(suite, case, plan["build"]["binary"], plan["build"]["binary_sha256"])
        require(sha(suite / "contracts/cli_process_v0_3.yaml") == plan["contract_sha256"], "Reviewed CLI contract changed")
        require(sha(suite / "handoff/reviewed-tests.json") == record["handoff_sha256"], "Reviewed handoff changed")
        review = read(suite / "review-input.json")
        require(read(suite / "handoff/reviewed-tests.json") == expected_handoff(suite, review),
                "Prepared handoff differs from the complete expected review envelope")
        require(review["repository_revisions"] == {plan["build"]["repository"]: plan["build"]["revision"]}, "Product revision changed")
        if authority:
            require(review["reviewed_by"] == "local-ci-policy" and review["review_ref"] == "local-ci-policy://sqlite-v1", "Wrong CI actor")
            expected_review = review_object(case, suite_objects(case, plan["build"]["binary"], plan["build"]["binary_sha256"]),
                                           plan["build"], "local-ci-policy", "local-ci-policy://sqlite-v1",
                                           review["reviewed_at"], review["valid_until"])
            require(review == expected_review, "Generated review envelope differs from local CI policy")
            reviewed_at = datetime.fromisoformat(review["reviewed_at"].replace("Z", "+00:00"))
            valid_until = datetime.fromisoformat(review["valid_until"].replace("Z", "+00:00"))
            require(reviewed_at <= datetime.now(timezone.utc) <= valid_until and
                    valid_until - reviewed_at <= timedelta(days=1, seconds=2), "Local CI review time is outside its finite window")
    require(not (evidence / "verification.json").exists(), "Preserve completed verification evidence")
    budget(cache, evidence)
    results = []
    for case, record in zip(cases, plan["cases"]):
        output = evidence / ("run-" + case["name"])
        require(not output.exists(), "Runtime directory must be fresh")
        command = [sys.executable, str(MODULE / "scripts/smoke.py"), "--framework-jar", plan["framework_jar"],
                   "--framework-sha256", plan["framework_sha256"], "--suite-dir", record["suite"],
                   "--handoff-sha256", record["handoff_sha256"], "--expect-verdict", case.get("verdict", "passed"),
                   "--name", "sqlite-" + case["name"], "--test-level", "integration", "--output-dir", str(output),
                   "--java-home", str(args.java_home.resolve(strict=True))]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=90)
        (evidence / (case["name"] + ".log")).write_text(completed.stdout + completed.stderr)
        require(completed.returncode == 0, "Packaged module check failed: " + case["name"])
        receipt = read(output / "receipt.json")
        result = read(receipt["artifacts"]["result"]["path"])
        require(result.get("failure", {}).get("code") == case.get("failure"), "Unexpected framework failure code")
        require(len(result["step_results"]) == len(result["provider_results"]) == 1, "Expected one native operation")
        provider = result["provider_results"][0]
        require(provider["provider_type"] == "cli_process" and provider["provider_contract"] == "cli_process.v0.3"
                and provider["runtime_mode"] == "native", "Wrong native provider")
        refs = [item for item in receipt["artifacts"].values() if "/provider-evidence/cli_process/" in item["path"]]
        require(len(refs) == 1, "Expected one canonical CLI evidence artifact")
        observed = read(refs[0]["path"])
        require(observed["cleanup_status"] == "complete" and observed["launched"] is (not case.get("bad_pin", False)), "Launch or cleanup mismatch")
        require(observed["observed_executable_sha256"] == plan["build"]["binary_sha256"], "Observed binary pin mismatch")
        require(observed["executable_sha256"] == ("0" * 64 if case.get("bad_pin") else plan["build"]["binary_sha256"]), "Configured binary pin mismatch")
        require(observed["stdin_sha256"] == hashlib.sha256(case.get("stdin", "").encode()).hexdigest(), "Observed stdin digest mismatch")
        require(observed["argv_sha256"] == hashlib.sha256(json.dumps(case["argv"], separators=(",", ":"), ensure_ascii=False).encode()).hexdigest(), "Observed argv digest mismatch")
        failed_operation = case["name"] in {"timeout", "output-limit", "pin-mismatch"}
        require(observed["operation_status"] == ("failed" if failed_operation else "passed"), "Unexpected provider outcome")
        outputs = result["step_results"][0].get("outputs", {})
        if failed_operation:
            require(not outputs, "Failed provider operation must withhold assertion outputs")
            if case.get("provider_error"):
                require(observed["error"] == case["provider_error"], "Wrong bounded-output failure reason")
        else:
            require(outputs["exit_code"] == case["exit"] and outputs["stdout"] == case.get("observed_stdout", case["stdout"])
                    and outputs["stderr"] == case.get("stderr", ""), "Canonical observation differs from independent expected value")
        pids = ([observed["pid"]] if "pid" in observed else []) + observed["descendant_pids"]
        for pid in pids:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                pass
            else:
                raise ValueError("Owned process still exists: " + str(pid))
        results.append({"case": case["name"], "module_outcome": receipt["outcome"], "framework_verdict": receipt["framework_verdict"],
                        "failure_code": case.get("failure"), "evidence_validation": receipt["evidence_validation"],
                        "receipt_sha256": sha(output / "receipt.json"), "cli_evidence_sha256": sha(refs[0]["path"]),
                        "cleanup_status": observed["cleanup_status"], "owned_pids_stopped": pids,
                        "release_evidence_eligible": receipt["release_evidence_eligible"]})
        budget(cache, evidence)
        print(case["name"], receipt["framework_verdict"], case.get("failure"), flush=True)
    write(evidence / "verification.json", {"status": "passed", "claim": CLAIM, "actor": plan["actor"],
          "prepared_plan_sha256": sha(plan_path), "policy_sha256": args.policy_sha256 if authority else None,
          "approval_sha256": sha(args.approval_file) if args.approval_file else None, "cases": results,
          "owned_bytes": budget(cache, evidence), "shared_sit": "NOT_RUN", "native_swarm_agent": "NOT_RUN", "live_cognee": "NOT_RUN"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    fetch_parser = sub.add_parser("fetch", help="Fetch exact official assets and compile; never execute SQLite")
    fetch_parser.add_argument("--project", choices=["sqlite"], required=True)
    fetch_parser.add_argument("--cache-dir", type=Path, required=True)
    fetch_parser.add_argument("--cc", default="cc")
    prepare_parser = sub.add_parser("prepare", help="Freeze suites and handoffs without product execution")
    prepare_parser.add_argument("--project", choices=["sqlite"], required=True)
    prepare_parser.add_argument("--cache-dir", type=Path, required=True)
    prepare_parser.add_argument("--framework-jar", type=Path, required=True)
    prepare_parser.add_argument("--framework-sha256", required=True)
    prepare_parser.add_argument("--module-sha256", required=True)
    prepare_parser.add_argument("--reviewed-by")
    prepare_parser.add_argument("--review-ref")
    verify_parser = sub.add_parser("verify", help="Use existing smoke.py after separate runtime approval")
    verify_parser.add_argument("--approval-file", type=Path)
    for current in (prepare_parser, verify_parser):
        current.add_argument("--evidence-dir", type=Path, required=True)
        current.add_argument("--java-home", type=Path, required=True)
        current.add_argument("--local-ci-policy", type=Path)
        current.add_argument("--policy-sha256")
    args = parser.parse_args()
    {"fetch": fetch, "prepare": prepare, "verify": verify}[args.command](args)


if __name__ == "__main__":
    main()
