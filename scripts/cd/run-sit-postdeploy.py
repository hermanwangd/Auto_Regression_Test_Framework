#!/usr/bin/env python3
"""Invoke the existing framework after a pinned, protected CD deployment handoff.

This is pipeline composition, not a provider runner. No bundled adapter proves SIT.
Input documents use JSON, which is also accepted by the framework's YAML reader.
"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import time
from urllib.parse import urlsplit

MAX_TOTAL = 96 * 1024 * 1024
MAX_TEXT = 4 * 1024 * 1024
MAX_LOG = 1024 * 1024
SHA = re.compile(r"[a-f0-9]{64}")
ID = re.compile(r"[A-Za-z0-9_.-]{1,128}")


class Blocked(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise Blocked(reason)


def utc(value):
    require(isinstance(value, str), "A UTC timestamp is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.utcoffset() is not None and parsed.utcoffset().total_seconds() == 0,
                "Timestamps must use UTC")
        return parsed
    except ValueError:
        raise Blocked("Invalid UTC timestamp") from None


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_./-]+", value),
            "Artifact paths must be simple relative paths")
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(p not in (".", "..") for p in value.split("/")),
            "Artifact path escapes its bundle")
    return path


def read_file(root, name, limit=MAX_TEXT):
    rel = relative(name)
    path = root.joinpath(*rel.parts)
    for parent in (path, *path.parents):
        require(not parent.is_symlink(), "Symlink artifacts are prohibited")
        if parent == root:
            break
    require(path.is_file() and path.stat().st_size <= limit,
            "Required artifact missing, non-regular, or over size limit: " + name)
    data = path.read_bytes()
    require(len(data) <= limit, "Artifact grew beyond its limit")
    return data


def document(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON keys are prohibited")
            result[key] = value
        return result
    try:
        result = json.loads(data, object_pairs_hook=unique,
                            parse_constant=lambda _: require(False, "Non-finite JSON values are prohibited"))
    except (ValueError, UnicodeError):
        raise Blocked("Expected strict JSON (also valid YAML)") from None
    require(isinstance(result, dict), "Expected a JSON object")
    return result


def checked_blob(root, spec, limit):
    require(isinstance(spec, dict) and set(spec) == {"path", "sha256"}, "Invalid artifact pin")
    require(isinstance(spec["sha256"], str) and SHA.fullmatch(spec["sha256"]), "Invalid SHA-256 pin")
    data = read_file(root, spec["path"], limit)
    require(digest(data) == spec["sha256"], "Artifact digest mismatch: " + spec["path"])
    return data


def preflight(bundle, handoff_name, expected_pin, now):
    require(isinstance(expected_pin, str) and SHA.fullmatch(expected_pin), "Independent handoff pin required")
    raw = read_file(bundle, handoff_name)
    require(digest(raw) == expected_pin, "Protected handoff digest mismatch")
    handoff = document(raw)
    require(set(handoff) == {"contract_version", "environment_stage", "deployment", "suite", "checks",
                             "framework", "readiness_plugins", "files", "max_age_seconds", "expected_plan_digest"},
            "Incomplete or unknown CD handoff configuration")
    require(handoff["contract_version"] == "cd-sit-postdeploy.v1" and handoff["environment_stage"] == "sit",
            "Only the explicit CD SIT handoff contract is supported")
    require(isinstance(handoff["expected_plan_digest"], str) and SHA.fullmatch(handoff["expected_plan_digest"]), "Approved compiled plan digest required")
    age = handoff["max_age_seconds"]
    require(type(age) is int and 1 <= age <= 3600, "max_age_seconds must be 1..3600")
    deployment = handoff["deployment"]
    require(isinstance(deployment, dict) and set(deployment) == {"ref", "expected_revision", "observed_revision", "completed_at"},
            "Deployment identity, versions and completion time are required")
    require(all(isinstance(deployment[k], str) and 0 < len(deployment[k]) <= 256
                for k in ("ref", "expected_revision", "observed_revision")), "Deployment identities must be nonempty")
    require(deployment["expected_revision"] == deployment["observed_revision"], "Deployed revision differs from approved revision")
    completed = utc(deployment["completed_at"])
    require(0 <= (now - completed).total_seconds() <= age, "Deployment evidence is stale or future-dated")
    suite = handoff["suite"]
    require(isinstance(suite, dict) and set(suite) == {"manifest", "profile", "profile_ref", "suite_id", "deployment_check_id", "provider_contract_ref"},
            "Exact leaf suite/profile/deployment check required")
    require(all(isinstance(suite[k], str) and ID.fullmatch(suite[k]) for k in ("profile", "suite_id", "deployment_check_id")),
            "Suite/profile/check IDs must be simple identifiers")
    require(suite["manifest"] == "suite/suite_manifest.yaml"
            and suite["profile_ref"] == "suite/env_profiles/" + suite["profile"] + ".yaml"
            and suite["provider_contract_ref"] == "suite/contracts/rest_client_v0_3.yaml",
            "CD requires conventional suite_manifest.yaml, env_profiles/<profile>.yaml and pinned REST contract paths")
    files = handoff["files"]
    require(isinstance(files, list) and 2 <= len(files) <= 512, "Exact suite input catalog required")
    blobs = {}
    for spec in files:
        data = checked_blob(bundle, spec, min(MAX_TEXT, MAX_TOTAL - sum(map(len, blobs.values()))))
        name = spec["path"]
        require(name.startswith("suite/") and name not in blobs, "Suite catalog paths must be unique under suite/")
        blobs[name] = data
    suite_paths = list((bundle / "suite").rglob("*"))
    require(not any(p.is_symlink() for p in suite_paths), "Symlink suite entries are prohibited")
    actual = {str(p.relative_to(bundle)).replace(os.sep, "/") for p in suite_paths if not p.is_dir()}
    require(actual == set(blobs), "Suite catalog must exactly cover all suite files")
    require(suite["manifest"] in blobs and suite["profile_ref"] in blobs, "Pinned suite/profile is missing")
    manifest = document(blobs[suite["manifest"]])
    profile = document(blobs[suite["profile_ref"]])
    require(manifest.get("manifest_version") == "v0.3" and manifest.get("suite_id") == suite["suite_id"]
            and "child_suites" not in manifest and manifest.get("tests"), "One executable v0.3 leaf suite required")
    require(set(manifest) == {"manifest_version", "suite_id", "default_profile", "targets", "env_profiles", "tests"}
            and manifest["default_profile"] == suite["profile"], "CD supports a finite manifest with inline oracles and no artifact roots")
    manifest_dir = PurePosixPath(suite["manifest"]).parent
    profile_ref = str(PurePosixPath(suite["profile_ref"]).relative_to(manifest_dir))
    require(manifest.get("env_profiles") == {suite["profile"]: {"ref": profile_ref}}, "Manifest must select exactly the pinned profile")
    consumed = {suite["manifest"], suite["profile_ref"], suite["provider_contract_ref"]}
    require(isinstance(manifest["tests"], list) and 1 <= len(manifest["tests"]) <= 32, "CD supports 1..32 tests")
    for test in manifest["tests"]:
        require(isinstance(test, dict) and isinstance(test.get("ref"), str), "Pinned test reference required")
        require(set(test) == {"ref"}, "Only static test references are supported")
        require(re.fullmatch(r"test_cases/[A-Za-z0-9_.-]+\.yaml", test["ref"]), "CD test references must be test_cases/*.yaml")
        name = str(manifest_dir / relative(test["ref"]))
        require(name in blobs and name not in consumed, "Test reference missing or duplicate")
        consumed.add(name)
        validate_test(document(blobs[name]), manifest["targets"])
    require(consumed == set(blobs), "Catalog contains unconsumed inputs; CD supports no external oracle/fixture references")
    contract = document(blobs[suite["provider_contract_ref"]])
    require(contract.get("contract_version") == "v0.3" and contract.get("provider_contract") == "rest_client.v0.3"
            and contract.get("provider_type") == "rest_client" and "native" in contract.get("runtime_modes", []), "Pinned native REST contract required")
    require(manifest["targets"] and all(t == {"provider_contract": "rest_client.v0.3"} for t in manifest["targets"].values()), "CD hook currently supports native REST only")
    require(profile.get("profile_id") == suite["profile"] and profile.get("execution_mode") == "sit_deployed",
            "Explicit sit_deployed profile required; local fixtures are prohibited")
    require("product_test" not in profile, "Swarm product-test SIT remains unsupported")
    require(set(profile) <= {"profile_id", "execution_mode", "isolation", "evidence_classification", "downstream_release_evidence", "targets", "sit_readiness"}, "Unknown profile field in finite CD subset")
    targets = profile.get("targets")
    require(isinstance(targets, dict) and targets and all(isinstance(t, dict) and t.get("runtime_mode") == "native" for t in targets.values()),
            "Every SIT target must explicitly select native runtime")
    require(set(targets) == set(manifest.get("targets", {})), "Profile must bind every manifest target")
    for target in targets.values():
        require(set(target) == {"runtime_mode", "bindings"} and set(target["bindings"]) == {"base_url"}
                and isinstance(target["bindings"]["base_url"], str), "Only literal REST base_url binding is supported")
        origin = urlsplit(target["bindings"]["base_url"])
        require(origin.scheme in ("http", "https") and origin.hostname and not origin.username and not origin.password
                and origin.path in ("", "/") and not origin.query and not origin.fragment, "Literal HTTP origin without credentials required")
    readiness = profile.get("sit_readiness")
    require(isinstance(readiness, dict) and set(readiness) == {"timeout_ms", "checks"}, "SIT readiness configuration required before launch")
    require(type(readiness["timeout_ms"]) is int and 1 <= readiness["timeout_ms"] <= 60000, "Invalid readiness timeout")
    checks = handoff["checks"]
    require(isinstance(checks, list) and 1 <= len(checks) <= 32, "Protected readiness check expectations required")
    by_id = {}
    for check in checks:
        require(isinstance(check, dict) and set(check) == {"id", "kind", "adapter", "adapter_version", "expected_version"}, "Invalid protected check")
        require(all(isinstance(v, str) and 0 < len(v) <= 256 for v in check.values()), "Empty readiness identity/version")
        require(ID.fullmatch(check["id"]) and check["id"] not in by_id and check["adapter"] != "fixture"
                and check["kind"] in ("dependency", "external_system", "test_data"), "Invalid, duplicate or fixture readiness check")
        by_id[check["id"]] = check
    configured = readiness["checks"]
    require(isinstance(configured, list) and len(configured) == len(checks), "Readiness checks differ from protected expectations")
    seen = set()
    for check in configured:
        require(isinstance(check, dict) and set(check) == {"id", "kind", "adapter", "expected_version", "config"}, "Malformed readiness configuration")
        expected = by_id.get(check["id"])
        require(expected is not None and check["id"] not in seen and isinstance(check["config"], dict)
                and all(check[k] == expected[k] for k in ("id", "kind", "adapter", "expected_version")), "Readiness configuration differs from approval")
        require(all(isinstance(k, str) and isinstance(v, str) and "://" not in v
                    and not k.startswith(("secret", "approved_local", "local_ref")) for k, v in check["config"].items()), "Readiness options must be literal non-secret identifiers")
        seen.add(check["id"])
    deployed_check = by_id.get(suite["deployment_check_id"], {})
    require(deployed_check.get("kind") == "dependency" and deployed_check.get("expected_version") == deployment["expected_revision"],
            "A dependency readiness check must verify the deployed revision")
    framework_data = checked_blob(bundle, handoff["framework"], MAX_TOTAL - sum(map(len, blobs.values())))
    require(handoff["framework"]["path"] not in blobs, "Runtime artifact overlaps suite input")
    blobs[handoff["framework"]["path"]] = framework_data
    plugins = handoff["readiness_plugins"]
    require(isinstance(plugins, list) and 1 <= len(plugins) <= 8, "Approved real readiness plugin JAR required")
    artifact_names = {handoff["framework"]["path"]}
    for plugin in plugins:
        data = checked_blob(bundle, plugin, MAX_TOTAL - sum(map(len, blobs.values())))
        require(plugin["path"] not in artifact_names and plugin["path"] not in blobs, "Duplicate artifact path")
        blobs[plugin["path"]] = data
        artifact_names.add(plugin["path"])
    require(all(name.startswith("artifacts/") and name.endswith(".jar") for name in artifact_names), "Runtime JARs must be under artifacts/")
    require(sum(map(len, blobs.values())) <= MAX_TOTAL, "CD bundle exceeds 96 MiB")
    return handoff, blobs


def validate_test(test, targets):
    require(set(test) == {"dsl_version", "test_case_id", "title", "execute", "verify"} and test["dsl_version"] == "v0.3",
            "Finite CD tests have no setup, cleanup, fixtures or source references")
    require(isinstance(test["execute"], list) and len(test["execute"]) == 1, "One REST read per CD test required")
    op = test["execute"][0]
    require(isinstance(op, dict) and set(op) == {"id", "target", "op", "with"} and op["target"] in targets
            and op["op"] == "http_request" and isinstance(op["id"], str) and ID.fullmatch(op["id"]), "Literal REST operation required")
    inputs = op["with"]
    require(isinstance(inputs, dict) and {"request.method", "request.path"} <= set(inputs)
            and set(inputs) <= {"request.method", "request.path", "request.timeout"}
            and inputs["request.method"] in ("GET", "HEAD") and isinstance(inputs["request.path"], str)
            and inputs["request.path"].startswith("/") and not inputs["request.path"].startswith("//")
            and not any(c in inputs["request.path"] for c in ("#", "\\", "\r", "\n")), "Only literal GET/HEAD paths without headers/body are supported")
    timeout = inputs.get("request.timeout", "PT10S")
    require(isinstance(timeout, str) and re.fullmatch(r"PT\d{1,2}(?:\.\d{1,3})?S", timeout)
            and Decimal("0.001") <= Decimal(timeout[2:-1]) <= Decimal("10"),
            "REST timeout must use bounded ISO duration PT0.001S..PT10S")
    require(isinstance(test["verify"], list) and test["verify"], "Inline assertions required")
    for verify in test["verify"]:
        require(isinstance(verify, dict) and set(verify) == {"id", "type", "assert"} and verify["type"] == "assertion", "Only framework assertions are supported")
        assertion = verify["assert"]
        require(isinstance(assertion, dict) and set(assertion) == {"actual", "operator", "expected"}
                and assertion["actual"] in ("step://" + op["id"] + "/response.status", "step://" + op["id"] + "/response.body")
                and assertion["operator"] == "equals", "Only inline equals response assertions are supported")
        require(not any(token in json.dumps(assertion["expected"]) for token in
                        ("artifact://", "env://", "generated://", "local://", "step://", "fixed://", "mock://", "approved_local_")),
                "Expected values must be inline literals without reference substitutes")


def launch(argv, work, name, deadline):
    """Bound owned JVM process group and files, without a shell or inherited credentials."""
    out_path, err_path = work / (name + ".stdout"), work / (name + ".stderr")
    with out_path.open("wb") as out, err_path.open("wb") as err:
        child = subprocess.Popen(argv, cwd=work, env={"LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"},
                                 stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
        try:
            while child.poll() is None:
                require(time.monotonic() < deadline, "Framework invocation deadline exceeded")
                require(sum(p.stat().st_size for p in work.glob("*.std*")) <= MAX_LOG, "Framework output limit exceeded")
                time.sleep(0.05)
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGKILL)
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                raise Blocked("Framework termination could not be confirmed") from None
    require(sum(p.stat().st_size for p in work.glob("*.std*")) <= MAX_LOG, "Framework output limit exceeded")
    return child.returncode, out_path.read_text(encoding="utf-8", errors="strict")


def verify_readiness(handoff, result, readiness, now):
    require(readiness.get("contract_version") == "sit-readiness.v1" and readiness.get("status") == "READY", "Framework readiness did not report READY")
    for key in ("suite_id", "profile", "run_id", "batch_id", "plan_digest"):
        require(isinstance(result.get(key), str) and result[key] and readiness.get(key) == result[key], "Readiness/result identity mismatch")
    require(result["suite_id"] == handoff["suite"]["suite_id"] and result["profile"] == handoff["suite"]["profile"], "Unexpected executed suite/profile")
    require(result["plan_digest"] == handoff["expected_plan_digest"], "Executed plan differs from approved compiled plan")
    start, finish = utc(readiness.get("started_at")), utc(readiness.get("finished_at"))
    require(utc(handoff["deployment"]["completed_at"]) <= start <= finish <= now
            and (now - start).total_seconds() <= handoff["max_age_seconds"], "Readiness evidence is stale, future or predates deployment")
    actual = readiness.get("checks")
    require(isinstance(actual, list) and len(actual) == len(handoff["checks"]), "Incomplete readiness observations")
    expected = {c["id"]: c for c in handoff["checks"]}
    seen = set()
    for check in actual:
        require(isinstance(check, dict) and check.get("id") in expected and check["id"] not in seen, "Unknown or duplicate observed check")
        seen.add(check["id"])
        require(all(check.get(k) == v for k, v in expected[check["id"]].items())
                and check.get("simulated") is False and check.get("status") == "READY"
                and check.get("observed_version") == check["expected_version"]
                and isinstance(check.get("evidence"), dict) and check["evidence"], "Readiness observation/version/adapter differs from approval")
        require(start <= utc(check.get("observed_at")) <= finish, "Observation must be fresh within readiness evaluation")


def execute(handoff, blobs, output, java, summary=None):
    summary = {} if summary is None else summary
    snapshot = output / "snapshot"
    snapshot.mkdir(mode=0o700)
    for name, data in blobs.items():
        dest = snapshot.joinpath(*relative(name).parts)
        dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        dest.write_bytes(data)
    runtime = output / "runtime"
    runtime.mkdir(mode=0o700)
    plugins = ",".join(str(snapshot / p["path"]) for p in handoff["readiness_plugins"])
    contracts = output / "docs/02-architecture/contracts/provider-contracts"
    contracts.mkdir(parents=True)
    (contracts / "rest_client_v0_3.yaml").write_bytes(blobs[handoff["suite"]["provider_contract_ref"]])
    base = [str(java), "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-Djava.io.tmpdir=" + str(runtime),
            "-Dloader.path=" + plugins, "-cp", str(snapshot / handoff["framework"]["path"]),
            "org.springframework.boot.loader.launch.PropertiesLauncher"]
    deadline = time.monotonic() + 300
    summary["framework_launch_attempted"] = True
    validate_exit, validate_stdout = launch(base + ["validate", "--suite", str(snapshot / handoff["suite"]["manifest"]),
                                      "--profile", handoff["suite"]["profile"], "--root", str(runtime)], output, "validate", deadline)
    plans = [line.removeprefix("plan_digest: ") for line in validate_stdout.splitlines() if line.startswith("plan_digest: ")]
    require(validate_exit == 0 and "validation_status: passed" in validate_stdout
            and plans == [handoff["expected_plan_digest"]], "Framework compiled plan differs from protected approval")
    run_exit, stdout = launch(base + ["run", "--suite", str(snapshot / handoff["suite"]["manifest"]),
                              "--profile", handoff["suite"]["profile"], "--root", str(runtime)], output, "run", deadline)
    paths = [line.removeprefix("result_json: ") for line in stdout.splitlines() if line.startswith("result_json: ")]
    require(len(paths) == 1, "Framework did not return exactly one canonical result")
    path = Path(paths[0])
    require(path.is_absolute() and path.name == "result.json", "Non-canonical framework result path")
    rel = str(path.relative_to(runtime))
    require(rel.startswith("target/regression/"), "Framework result is outside canonical runtime root")
    result = document(read_file(runtime, rel))
    evidence_exit, evidence_stdout = launch(base + ["validate-evidence", "--result", str(path), "--root", str(runtime)], output, "validate-evidence", deadline)
    report_exit, report_stdout = launch(base + ["report", "--result", str(path), "--format", "json", "--root", str(runtime)], output, "report", deadline)
    require(evidence_exit == 0 and "evidence_validation_status: passed" in evidence_stdout and report_exit == 0,
            "Existing framework evidence validation/report rejected the result")
    report = document(report_stdout)
    require(all(report.get(k) == result.get(k) for k in ("suite_id", "profile", "run_id", "batch_id", "status")), "Framework report/result mismatch")
    summary.update({"framework_verdict": result.get("status"), "result_json": str(path),
                    "result_sha256": digest(path.read_bytes()), "release_evidence_eligible": report.get("release_evidence_eligible", False)})
    refs = result.get("evidence_refs")
    require(isinstance(refs, list) and "readiness/sit-readiness.json" in refs, "Canonical readiness evidence missing")
    readiness = document(read_file(path.parent, "readiness/sit-readiness.json"))
    summary["readiness_status"] = readiness.get("status")
    if readiness.get("status") != "READY":
        require(result.get("status") == "blocked" and result.get("provider_results") == []
                and result.get("step_results") == [] and result.get("cleanup_failures") == [],
                "Non-ready framework run contains provider or cleanup operations")
        summary["provider_operations_observed"] = 0
    verify_readiness(handoff, result, readiness, datetime.now(timezone.utc))
    require(run_exit == 0 and result.get("status") == "passed", "Framework verdict is " + str(result.get("status")))
    return {"status": "PASSED", "framework_verdict": result["status"], "result_json": str(path),
            "result_sha256": digest(path.read_bytes()), "release_evidence_eligible": report.get("release_evidence_eligible", False)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--handoff", default="deployment.json")
    parser.add_argument("--expected-handoff-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True, help="Fresh retained evidence directory")
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")))
    args = parser.parse_args()
    output = args.output.absolute()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = {"contract_version": "cd-sit-gate-result.v1", "status": "BLOCKED", "framework_launch_attempted": False,
              "module_sit_supported": False, "claim_boundary": "PROTECTED_DIRECT_FRAMEWORK_CD_HOOK_NO_LIVE_SIT_VERIFICATION"}
    try:
        bundle = args.bundle.resolve(strict=True)
        require(bundle.is_dir(), "CD input bundle directory required")
        handoff, blobs = preflight(bundle, args.handoff, args.expected_handoff_sha256, datetime.now(timezone.utc))
        java = (args.java_home / "bin/java").resolve(strict=True)
        require(java.is_file() and os.access(java, os.X_OK), "JDK java executable required")
        report.update(execute(handoff, blobs, output, java, report))
    except (Blocked, OSError, ValueError, KeyError, TypeError, UnicodeError) as error:
        report["reason"] = str(error)
    (output / "gate-result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
