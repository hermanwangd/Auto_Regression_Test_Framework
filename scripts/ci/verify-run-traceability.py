"""Retain portable canonical runs and their validated case traceability indexes from the packaged CLI."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "target/traceability"
SAMPLE = Path("samples/20-provider-capability-p0/verification/multi_test_shared_env")
SELECTED = "MULTI-TEST-V03-TC-001"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(jar, bundle, name, selection):
    def cli(*args):
        command = ["java", "-Xmx384m", "-jar", str(jar), *args]
        return subprocess.run(command, cwd=bundle, check=True, text=True, stdout=subprocess.PIPE, timeout=60).stdout

    log = cli("run", "--suite", "suite/suite_manifest.yaml", "--profile", "local_v03", *selection)
    match = re.search(r"^result_json: (.+)$", log, re.MULTILINE)
    require(match, log)
    result = (bundle / match.group(1)).resolve()
    result.relative_to(bundle)
    cli("validate-evidence", "--result", str(result))
    coverage = bundle / "reports" / name / "coverage.json"
    cli("report", "--result", str(result), "--format", "json", "--coverage-dir", str(coverage.parent))
    index = json.loads(coverage.read_bytes())["run_traceability"]
    source = index["canonical_result"]
    require((coverage.parent / source["path"]).resolve() == result and sha(result) == source["sha256"],
            "Canonical result identity mismatch")
    require(index["review_ready"] and index["evidence_state"] == "VALID" and index["canonical_status"] == "passed",
            "Expected a reviewable framework run")
    require(len(index["cases"]) == 2, "Expected both manifest cases in the index")
    for case in index["cases"]:
        require(sha(bundle / "suite" / case["source_path"]) == case["source_sha256"], "Source identity mismatch")
        require(case["lifecycle"] == "active" and case["requirement_refs"] and case["acceptance_criteria_refs"],
                "Expected declared framework requirement and acceptance references")
        if not selection or case["test_case_id"] == SELECTED:
            require(case["selection"] == "SELECTED" and case["outcome"] == "PASS" and case["review_ready"]
                    and case["evidence"], "Missing selected case evidence")
        else:
            require(case["selection"] == "UNSELECTED" and case["outcome"] == "NOT_RUN" and not case["review_ready"]
                    and not case["evidence"], "Unselected case received execution credit")
        for entry in case["evidence"]:
            require(sha(result.parent / entry["path"]) == entry["sha256"], "Case evidence digest mismatch")
    return {**{field: index[field] for field in ("suite_id", "run_id", "canonical_status", "evidence_state", "review_ready")},
            "canonical_result": {"path": result.relative_to(OUTPUT).as_posix(), "sha256": sha(result)},
            "coverage": {"path": coverage.relative_to(OUTPUT).as_posix(), "sha256": sha(coverage)},
            "suite_manifest": (bundle / "suite/suite_manifest.yaml").relative_to(OUTPUT).as_posix(), "source_directory": SAMPLE.as_posix()}


def main():
    version = subprocess.check_output([ROOT / "scripts/release/verify-release-version.sh"], text=True).strip()
    jar = ROOT / f"target/spec-driven-auto-regression-{version}.jar"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    bundle = Path(tempfile.mkdtemp(prefix="bundle-", dir=OUTPUT))
    try:
        shutil.copytree(ROOT / SAMPLE, bundle / "suite")
        runs = [run(jar, bundle, "full", []), run(jar, bundle, "selected", ["--test-case", SELECTED])]
        pending = bundle / "index.pending"
        pending.write_text(json.dumps({"schema_version": "ci-run-traceability.v1", "runs": runs}, indent=2) + "\n")
        os.replace(pending, OUTPUT / "index.json")
    except Exception:
        shutil.rmtree(bundle)
        raise
    print(f"Verified two packaged invocations in {OUTPUT / 'index.json'}")


if __name__ == "__main__":
    main()
