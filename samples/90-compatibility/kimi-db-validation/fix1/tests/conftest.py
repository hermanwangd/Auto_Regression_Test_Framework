from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from source_policy import verify_source

MANIFEST_PATH = ROOT / "case_manifest.json"
MANIFEST = json.loads(MANIFEST_PATH.read_text())
BINDING = json.loads((ROOT / "case-binding.json").read_text())["suites"][MANIFEST["suite"]]["cases"]
CASES = {case["case_id"]: case for case in MANIFEST["cases"]}


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    setattr(item, f"rep_{report.when}", report)


@pytest.fixture(autouse=True)
def bind_junit(record_testsuite_property):
    record_testsuite_property("run_token", os.environ["SWARM_DB02_RUN_TOKEN"])
    record_testsuite_property("manifest_sha256", os.environ["SWARM_DB02_MANIFEST_SHA256"])
    record_testsuite_property("source_digest", os.environ["SWARM_DB02_SOURCE_DIGEST"])


@pytest.fixture
def case(request) -> dict[str, Any]:
    marker = request.node.get_closest_marker("case_id")
    if marker is None:
        raise RuntimeError("every DB-02 test needs @pytest.mark.case_id")
    case_id = marker.args[0]
    spec = CASES[case_id]
    recorder: dict[str, Any] = {
        "case_id": case_id,
        "fixed_input": spec["fixed_input"],
        "expected": spec["expected"],
        "observed": None,
        "diagnostics": None,
    }
    started_at = utcnow()
    yield recorder
    reports = [getattr(request.node, f"rep_{when}", None) for when in ("setup", "call", "teardown")]
    failed = next((report for report in reports if report is not None and report.failed), None)
    skipped = next((report for report in reports if report is not None and report.skipped), None)
    outcome = "failed" if failed is not None else "skipped" if skipped is not None else "passed"
    run_id = os.environ["SWARM_DB02_RUN_ID"]
    run_token = os.environ["SWARM_DB02_RUN_TOKEN"]
    case_dir = Path(os.environ["SWARM_DB02_CASE_OUTPUT_DIR"])
    manifest_hash = hashlib.sha256(MANIFEST_PATH.read_bytes()).hexdigest()
    source = verify_source(ROOT)
    if manifest_hash != os.environ["SWARM_DB02_MANIFEST_SHA256"] or not source["pass"] or source["source_digest"] != os.environ["SWARM_DB02_SOURCE_DIGEST"]:
        raise RuntimeError("manifest or control-source binding mismatch")
    if case_dir.name != "case-records" or case_dir.parent.name != run_id or not case_dir.is_dir():
        raise RuntimeError("case output is not the exclusive current-run directory")
    identity = BINDING[case_id]
    record = {
        "queue": "DB-02-runtime-hardening-r2-fix1", "suite": MANIFEST["suite"],
        "case_id": case_id, "run_id": run_id, "run_token": run_token,
        "junit_classname": identity["classname"], "junit_name": identity["name"],
        "fixed_input": spec["fixed_input"], "expected": spec["expected"],
        "observed": recorder["observed"],
        "expected_matches_observed": spec["expected"] == recorder["observed"],
        "pytest_outcome": outcome, "failure": str(failed.longrepr) if failed is not None else None,
        "diagnostics": recorder.get("diagnostics"), "started_at": started_at, "ended_at": utcnow(),
        "manifest_sha256": manifest_hash, "source_digest": source["source_digest"],
        "derived_result": True,
    }
    with (case_dir / f"{case_id}.json").open("x", encoding="utf-8") as handle:
        handle.write(json.dumps(record, indent=2, ensure_ascii=False) + "\n")
    if failed is None and spec["expected"] != recorder["observed"]:
        pytest.fail(f"expected/observed mismatch: {spec['expected']!r} != {recorder['observed']!r}")
