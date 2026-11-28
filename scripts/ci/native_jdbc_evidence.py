"""Validate a native JDBC receipt against both archived CLI results."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def validate(directory, dialect, revision, run, attempt):
    receipt = json.loads((directory / "receipt.json").read_bytes())
    expected = {"schema_version": "native-jdbc.v1", "dialect": dialect, "revision": revision,
                "job_run": str(run), "job_attempt": str(attempt)}
    if any(receipt.get(key) != value for key, value in expected.items()):
        raise ValueError("Mismatched native JDBC receipt provenance for " + dialect)
    if (not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", receipt["image"])
            or not re.fullmatch(r"[0-9a-f]{64}", receipt["driver_sha256"])
            or not receipt["engine_version"].strip() or not receipt["driver"].endswith(".jar")):
        raise ValueError("Missing database or driver identity for " + dialect)
    profile = "external_" + dialect
    run_ids, order_ids = set(), set()
    for status in ("passed", "failed"):
        observation = receipt["cases"][status]
        raw = (directory / (status + ".json")).read_bytes()
        if hashlib.sha256(raw).hexdigest() != observation["result_sha256"]:
            raise ValueError("Mismatched native JDBC result digest")
        case = json.loads(raw)
        order_id = observation["order_id"]
        if (not re.fullmatch(r"ORD-[0-9a-f]{24}", order_id) or not case["run_id"]
                or case["run_id"] in run_ids or order_id in order_ids):
            raise ValueError("Missing or reused native JDBC run or order identity")
        run_ids.add(case["run_id"])
        order_ids.add(order_id)
        providers = [(p["provider_contract"], p["target"], p["profile"], p["runtime_mode"])
                     for p in case["provider_results"]]
        steps = case["step_results"]
        if (case["test_case_id"] != "JDBC-NATIVE-ORDER" or case["profile"] != profile
                or providers != [("jdbc.v0.3", "order_db", profile, "native")]
                or case["status"] != status or case["cleanup_failures"]
                or case["failure"]["code"] != (None if status == "passed" else "ASSERTION_FAILED")
                or [s["operation"] for s in steps] != ["db_seed", "db_query", "db_record_exists", "db_cleanup"]
                or any(s["status"] != "passed" for s in steps)
                or steps[0]["outputs"]["affected_rows"] != 1 or steps[3]["outputs"]["affected_rows"] != 1
                or steps[1]["outputs"]["row_count"] != 1
                or steps[1]["outputs"]["sample_rows"] != [{"ORDER_ID": order_id, "STATUS": "CREATED"}]
                or steps[2]["outputs"]["matched"] is not True
                or [v["status"] for v in case["verify_results"] if v["id"] == "one_order_found"] != [status]
                or observation["remaining_rows"] != ["other-run KEEP"]
                or observation["evidence_validation"] != "validated"):
            raise ValueError("Inconsistent native JDBC " + dialect + " " + status + " evidence")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("dialect", choices=["oracle", "db2"])
    for key in ("revision", "run", "attempt"):
        parser.add_argument(key)
    validate(**vars(parser.parse_args()))
