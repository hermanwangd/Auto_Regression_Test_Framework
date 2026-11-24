"""Evaluate full-repository and rendered Helm reports for the checked-out commit."""
import json
import os
import sys
from collections import Counter
from pathlib import Path

from policy import severity


JOB_TEMPLATE = "packaging/portable-runner/deployment/helm/framework-runner/templates/job.yaml"
RENDERED = {"produce.yaml", "collect.yaml"}


def evaluate(report, rendered):
    findings, errors, scanned = report["results"], report["errors"], report["paths"]["scanned"]
    if not all(isinstance(field, list) for field in (findings, errors, scanned)) or not scanned:
        raise ValueError("Malformed report or no files analyzed")
    if rendered and set(scanned) != RENDERED:
        raise ValueError("Both rendered Helm Jobs must be analyzed")

    counts = Counter()
    blocking = []
    for finding in findings:
        level = severity(finding)
        counts[level] += 1
        if level in {"HIGH", "CRITICAL"}:
            blocking.append({"severity": level, "rule": finding["check_id"],
                             "file": finding["path"], "line": finding["start"]["line"]})
    diagnostics = []
    for error in errors:
        kind, path = error["type"], error.get("path")
        # The companion render scan must pass independently for this family.
        covered_by_render = (not rendered and kind == "Other syntax error" and path == JOB_TEMPLATE)
        diagnostics.append({"type": kind, "file": path, "covered_by_render": covered_by_render})
    passed = not blocking and all(item["covered_by_render"] for item in diagnostics)
    return {"passed": passed, "analyzed": len(set(scanned)), "high": counts["HIGH"],
            "critical": counts["CRITICAL"], "by_severity": dict(counts),
            "blocking": blocking, "diagnostics": diagnostics}


def full_gate(reports, output, commit):
    targets = set(os.fsdecode((reports / "targets").read_bytes()).split("\0")) - {""}
    summary = {"commit": commit, "submitted_regular_files": len(targets), "scans": {}}
    analyzed = set()
    for family in ("gitlab", "community"):
        for scope in ("repository", "rendered"):
            label = f"{family}-{scope}"
            try:
                report = json.loads((reports / f"{label}.json").read_bytes())
                result = evaluate(report, scope == "rendered")
                if scope == "repository":
                    analyzed.update(report["paths"]["scanned"])
                print(f"{label}: analyzed={result['analyzed']} High={result['high']} "
                      f"Critical={result['critical']} passed={result['passed']}", flush=True)
            except (OSError, ValueError, KeyError, TypeError) as exc:
                result = {"passed": False, "error": str(exc)}
                print(f"{label}: {exc}", file=sys.stderr)
            summary["scans"][label] = result

    missing = sorted(targets - analyzed)
    source_extensions = {".java", ".py", ".sh", ".js", ".yaml", ".yml", ".properties"}
    missing_source = [p for p in missing if Path(p).suffix in source_extensions or Path(p).name == "Dockerfile"]
    summary["unanalyzed_files"] = missing
    summary["passed"] = bool(targets) and all(item["passed"] for item in summary["scans"].values()) and not missing_source
    if missing_source:
        print(f"Source files missing from both reports: {missing_source}", file=sys.stderr)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(full_gate(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]))
