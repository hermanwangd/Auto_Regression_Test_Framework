"""Scan tracked regular files and the two rendered Helm Jobs with the pinned rules."""
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

from policy import severity


JOB_TEMPLATE = "packaging/portable-runner/deployment/helm/framework-runner/templates/job.yaml"
RENDERED = {"produce.yaml", "collect.yaml"}


def scan(cwd, targets, rules, report):
    subprocess.run([
        "semgrep", "scan", "--config", str(rules), "--jobs", "1", "--max-memory", "900",
        "--timeout", "60", "--metrics", "off", "--disable-version-check",
        "--no-rewrite-rule-ids", "--disable-nosem", "--quiet", "--json", "--output", str(report),
        "--", *targets,
    ], cwd=cwd, check=True)
    return json.loads(report.read_bytes())


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


def full_scan(rules, output, commit):
    root = Path.cwd()
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != commit:
        raise ValueError(f"Expected {commit}, checked out {head}")
    entries = os.fsdecode(subprocess.check_output(["git", "ls-files", "-z"])).split("\0")
    targets = [path for path in entries if path and not Path(path).is_symlink() and Path(path).is_file()]
    if not targets:
        raise ValueError("No tracked regular files to scan")
    rendered_dir = output / "rendered"
    for name in RENDERED:
        if "kind: Job" not in (rendered_dir / name).read_text().splitlines():
            raise ValueError(f"Missing rendered Job in {name}")

    summary = {"commit": head, "submitted_regular_files": len(targets), "scans": {}}
    analyzed = set()
    for family in ("gitlab", "community"):
        for scope, cwd, files in [("repository", root, targets), ("rendered", rendered_dir, sorted(RENDERED))]:
            label = f"{family}-{scope}"
            try:
                report = scan(cwd, files, rules / family, output / f"{label}.json")
                result = evaluate(report, scope == "rendered")
                if scope == "repository":
                    analyzed.update(report["paths"]["scanned"])
                print(f"{label}: analyzed={result['analyzed']} High={result['high']} "
                      f"Critical={result['critical']} passed={result['passed']}", flush=True)
            except (subprocess.CalledProcessError, OSError, ValueError, KeyError, TypeError) as exc:
                # CalledProcessError contains the complete target list. Report its exit code only.
                reason = f"Semgrep exited {exc.returncode}" if isinstance(exc, subprocess.CalledProcessError) else str(exc)
                result = {"passed": False, "error": reason}
                print(f"{label}: {reason}", file=sys.stderr)
            summary["scans"][label] = result

    missing = sorted(set(targets) - analyzed)
    source_extensions = {".java", ".py", ".sh", ".js", ".yaml", ".yml", ".properties"}
    missing_source = [p for p in missing if Path(p).suffix in source_extensions or Path(p).name == "Dockerfile"]
    summary["unanalyzed_files"] = missing
    summary["passed"] = all(item["passed"] for item in summary["scans"].values()) and not missing_source
    if missing_source:
        print(f"Source files missing from both reports: {missing_source}", file=sys.stderr)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(full_scan(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]))
