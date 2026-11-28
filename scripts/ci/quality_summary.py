"""Build a commit-scoped quality summary from downloaded GitHub Actions artifacts."""
import argparse
from collections import Counter
from datetime import date, datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from defusedxml import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ("ci", "local-product-tests", "pr-sast")
STATUSES = ("passed", "failed", "blocked", "skipped", "NOT_RUN", "unselected")
# Names identify individual workflow steps. Their conclusion supplies the observation.
SCENARIOS = {
    "Linux / MariaDB": "Verify packaged CLI against native MariaDB",
    "Linux / MongoDB": "Verify native MongoDB",
    "Linux / Redis": "Verify native Redis",
    "Linux / S3 (MinIO)": "Verify native S3",
    "Linux / WebSocket": "Verify WebSocket subscription and correlated events",
    "Linux / SMTP (Mailpit)": "Verify SMTP recovery mail in Mailpit",
    "Linux / SFTP (OpenSSH)": "Verify SFTP batch and reconciliation with OpenSSH",
    "Linux / Chromium": "Verify order submission in Chromium with Playwright",
    "Linux / SQLite": "SQLite through the existing packaged module and framework",
    "Linux / NATS": "Public NATS native protocol through the existing module and framework",
    "Windows / native CLI": "Verify Windows executable pinning and the native provider",
}


def document(path):
    return json.loads(path.read_bytes())


def serialize(data):
    return json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def junit(files):
    counts = Counter(passed=0, failed=0, skipped=0, errors=0)
    for path in files:
        for test in ET.parse(path, forbid_dtd=True).iter("testcase"):
            if test.find("error") is not None:
                counts["errors"] += 1
            elif test.find("failure") is not None:
                counts["failed"] += 1
            elif test.find("skipped") is not None:
                counts["skipped"] += 1
            else:
                counts["passed"] += 1
    return dict(counts) if sum(counts.values()) else None


def suppressions(policy, today):
    rules = []
    for entry in ET.parse(policy, forbid_dtd=True).getroot().findall("{*}suppress"):
        until = entry.get("until")
        expiry = date.fromisoformat(until[:10]) if until else None
        rules.append({"sha1": entry.findtext("{*}sha1"), "cves": [c.text for c in entry.findall("{*}cve")],
                      "expires": until, "expired": expiry <= today if expiry else None})
    return rules


def product_cases(root, gaps):
    results = {}
    providers = set()
    for file in root.rglob("result.json"):
        if not {"target", "regression"}.issubset(file.parts):
            continue
        result = document(file)
        run_id = result["run_id"]
        if run_id in results:
            raise ValueError("Duplicate canonical run " + run_id)
        results[run_id] = (result, hashlib.sha256(file.read_bytes()).hexdigest())
    counts = Counter(dict.fromkeys(STATUSES, 0))
    seen = set()
    receipt_digests = set()
    receipts = sorted(root.rglob("receipt.json"))
    for file in receipts:
        receipt = document(file)
        receipt_digests.add(hashlib.sha256(file.read_bytes()).hexdigest())
        run_id = receipt.get("run_id")
        if not run_id:
            counts["NOT_RUN"] += 1
            continue
        if run_id in seen:
            raise ValueError("Duplicate product receipt " + run_id)
        seen.add(run_id)
        pair = results.get(run_id)
        pin = receipt.get("artifacts", {}).get("result", {}).get("sha256")
        if (not pair or pair[1] != pin or receipt.get("evidence_validation") != "validated"
                or receipt.get("framework_verdict") != pair[0]["status"]):
            gaps.append("Missing or inconsistent canonical product evidence for " + run_id)
            continue
        for case in pair[0]["test_results"]:
            status = case["status"]
            if status not in STATUSES:
                raise ValueError("Unknown product case status " + status)
            counts[status] += 1
        providers.update((r["provider_type"], r["runtime_mode"]) for r in pair[0]["provider_results"])
    if not seen or results.keys() - seen:
        gaps.append("Missing product receipt or missing product runs")
    expected_digests = []
    for product, relative in (("SQLite", "public-sqlite-evidence/verification.json"),
                              ("NATS", "public-nats-evidence/execution/summary.json")):
        summaries = list(root.glob("**/" + relative))
        if len(summaries) != 1:
            gaps.append("Missing or ambiguous product inventory for " + product)
            continue
        cases = document(summaries[0])["cases"]
        rows = list(cases.values()) if isinstance(cases, dict) else cases
        if not rows:
            gaps.append("Empty product inventory for " + product)
        expected_digests.extend(row["receipt_sha256"] for row in rows)
    if len(expected_digests) != len(receipts) or set(expected_digests) != receipt_digests:
        gaps.append("Product receipts differ from the SQLite and NATS scenario inventories")
    return {"outcomes": dict(counts) if receipts else None, "provider_modes": sorted(providers)}


def conformance(root, commit, source, gaps):
    expected = {row["contract"]: row for row in document(ROOT / "src/test/resources/provider-conformance.json")}
    found = {}
    for path in sorted(root.rglob("*.json")):
        row = document(path)
        contract = row["contract"]
        if contract in found or contract not in expected:
            raise ValueError("Unexpected or duplicate conformance receipt " + contract)
        if (row["revision"] != commit or str(row["job_run"]) != str(source.get("databaseId"))
                or str(row["job_attempt"]) != str(source.get("attempt"))
                or row["proof"] != expected[contract]["proof"]
                or set(row["operations"]) != set(expected[contract]["operations"]) or not row["scenarios"]):
            raise ValueError("Mismatched conformance receipt for " + contract)
        found[contract] = row
    missing = sorted(expected.keys() - found.keys())
    if missing:
        gaps.append("Missing adapter conformance receipts — " + ", ".join(missing))
    return {"observed": list(found.values()), "missing": missing}


def traceability(root, gaps):
    file = root / "index.json"
    if not file.is_file():
        gaps.append("Missing suite traceability index")
        return []
    index = document(file)
    if index["schema_version"] != "ci-run-traceability.v1" or not index["runs"]:
        raise ValueError("Missing indexed traceability runs")
    summaries = []
    def pinned(entry):
        path = (root / entry["path"]).resolve(strict=True)
        path.relative_to(root.resolve(strict=True))
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            raise ValueError("Mismatched traceability artifact digest")
        return json.loads(raw)

    selections, run_ids = set(), set()
    for run in index["runs"]:
        trace = pinned(run["coverage"])["run_traceability"]
        canonical = pinned(run["canonical_result"])
        if (canonical["run_id"] in run_ids or canonical["run_id"] != run["run_id"]
                or canonical["status"] != run["canonical_status"]
                or any(trace[key] != run[key] for key in ("run_id", "suite_id", "evidence_state", "canonical_status", "review_ready"))):
            raise ValueError("Mismatched traceability report")
        run_ids.add(canonical["run_id"])
        selections.add("selected" if canonical.get("selected_test_case") else "full")
        counts = Counter(case["outcome"] for case in trace["cases"])
        unselected = sum(case["selection"] == "UNSELECTED" for case in trace["cases"])
        summaries.append({**run, "case_outcomes": dict(counts), "unselected": unselected})
    if len(run_ids) < 2 or selections != {"full", "selected"}:
        gaps.append("Missing full-suite or selected-case traceability evidence")
    return summaries


def summarize(commit, root, today):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full Git commit")
    gaps, sources, steps = [], {}, {}
    for workflow in WORKFLOWS:
        path = root / (workflow + ".json")
        if not path.is_file():
            gaps.append("Missing workflow " + workflow)
            continue
        run = document(path)
        if (run["headSha"], run["event"], run["headBranch"], run["status"]) != (commit, "push", "main", "completed"):
            raise ValueError("Mismatched workflow provenance for " + workflow)
        sources[workflow] = {k: run[k] for k in ("databaseId", "attempt", "url", "conclusion")}
        for job in run["jobs"]:
            for step in job["steps"]:
                steps[step["name"]] = step.get("conclusion") or "unknown"
    scenarios = {name: steps.get(step, "no step evidence") for name, step in SCENARIOS.items()}
    tests = {
        "Linux framework": junit((root / "framework").rglob("TEST-*.xml")),
        "Windows CLI": junit((root / "windows").glob("TEST-*.xml")),
        "Linux Swarm module": junit((root / "products").glob("**/swarm-product-test/target/surefire-reports/TEST-*.xml")),
    }
    gaps.extend("Missing JUnit results for " + group for group, counts in tests.items() if counts is None)
    product = product_cases(root / "products", gaps)
    sast_path = root / "sast/summary.json"
    sast = document(sast_path) if sast_path.is_file() else None
    if sast is None:
        gaps.append("Missing full SAST report")
    elif sast["commit"] != commit:
        raise ValueError("Mismatched SAST commit")
    dependency = None
    path = root / "dependency/dependency-check-report.json"
    if path.is_file():
        deps = document(path)["dependencies"]
        dependency = {"dependencies": len(deps), "findings": sum(len(dep.get("vulnerabilities", [])) for dep in deps),
                      "suppressed_findings": sum(len(dep.get("suppressedVulnerabilities", [])) for dep in deps)}
    else:
        gaps.append("Missing dependency scan report")
    metadata = root / "artifacts.jsonl"
    provenance = {r["group"]: r for r in (json.loads(line) for line in metadata.read_text().splitlines())} if metadata.exists() else {}
    for group, workflow in {"framework": "ci", "windows": "ci", "dependency": "ci", "products": "local-product-tests", "sast": "pr-sast"}.items():
        source = sources.get(workflow, {})
        record = provenance.get(group)
        if not record or record["run_id"] != source.get("databaseId") or not 1 <= record["attempt"] <= source.get("attempt", 0):
            gaps.append("Missing producing job attempt for " + group)
    producer = dict(sources.get("ci", {}))
    producer["attempt"] = provenance.get("framework", {}).get("attempt")
    receipts = conformance(root / "framework/provider-conformance", commit, producer, gaps)
    trace = traceability(root / "framework/traceability", gaps)
    verified = (not gaps and all(source["conclusion"] == "success" for source in sources.values())
                and all(status == "success" for status in scenarios.values()) and sast["passed"]
                and all(run["review_ready"] and run["canonical_status"] == "passed" and run["evidence_state"] == "VALID" for run in trace)
                and all(not counts["errors"] and not counts["failed"] for counts in tests.values()))
    return {"schema_version": "ci-quality.v1", "commit": commit, "sources": sources, "artifact_provenance": provenance,
            "evidence_complete": not gaps, "verification_passed": verified,
            "evidence_gaps": gaps, "junit": tests, "scenarios": scenarios, "product": product, "sast": sast,
            "dependency_scan": dependency, "suppression_rules": suppressions(ROOT / "config/dependency-check-suppressions.xml", today),
            "adapter_conformance": receipts, "trace_runs": trace,
            "unverified": ["macOS runner", "Oracle native", "Db2 native", "Company SIT"]}


def markdown(data):
    lines = ["# CI quality for " + data["commit"][:12], "",
             "Evidence " + ("complete" if data["evidence_complete"] else "incomplete")
             + " · Verification " + ("passed" if data["verification_passed"] else "did not pass"), ""]
    for name, run in data["sources"].items():
        lines.append(f"- [{name} · attempt {run['attempt']}]({run['url']}) — {run['conclusion']}")
    lines += ["", "## JUnit tests", "", "| Runner | Passed | Failed | Errors | Skipped |", "| --- | ---: | ---: | ---: | ---: |"]
    for name, counts in data["junit"].items():
        values = [str(counts[key]) if counts is not None else "unknown" for key in ("passed", "failed", "errors", "skipped")]
        lines.append("| " + " | ".join([name, *values]) + " |")
    lines += ["", "## Product case outcomes", "",
              "The counts include deliberate failure scenarios in SQLite and NATS. Each counted verdict has a matching receipt.", "",
              " · ".join(f"{key} {value}" for key, value in data["product"]["outcomes"].items())
              if data["product"]["outcomes"] is not None else "Product verdicts unavailable.",
              "", "## Provider and platform evidence", "", "| Scenario | CI step |", "| --- | --- |"]
    lines += [f"| {name} | {state} |" for name, state in data["scenarios"].items()]
    lines += ["", "| Provider contract | Evidence | Platform | Observed operations |", "| --- | --- | --- | --- |"]
    for r in data["adapter_conformance"]["observed"]:
        lines.append(f"| {r['contract']} | {r['proof']} | {r['platform']} | " + ", ".join(r["operations"]) + " |")
    for missing in data["adapter_conformance"]["missing"]:
        lines.append(f"| {missing} | missing | — | — |")
    lines += ["", "Product run provider modes — " + ", ".join(f"{name}/{mode}" for name, mode in data["product"]["provider_modes"]),
              "", "Outside these CI jobs — " + ", ".join(data["unverified"]), "", "## Security", ""]
    sast = data["sast"]
    if sast:
        lines += [f"{sast['submitted_regular_files']} submitted files. {len(sast['unanalyzed_files'])} unanalyzed files.", "",
                  "| SAST rule set and input | High | Critical | Diagnostics |", "| --- | ---: | ---: | ---: |"]
        for name, scan in sast["scans"].items():
            lines.append(f"| {name} | {scan['high']} | {scan['critical']} | {len(scan['diagnostics'])} |")
    if data["dependency_scan"]:
        dep = data["dependency_scan"]
        lines += ["", f"Dependency-Check scanned {dep['dependencies']} dependencies, reported {dep['findings']} findings and recorded {dep['suppressed_findings']} suppressed findings."]
    lines.append("")
    for rule in data["suppression_rules"]:
        lines.append(f"- {', '.join(rule['cves'])} — expires {rule['expires'] or 'unspecified'}" + (" (expired)" if rule["expired"] else ""))
    if data["trace_runs"]:
        lines += ["", "## Suite traceability", "", "| Canonical run | Case outcomes | Unselected (within NOT_RUN) | Evidence |",
                  "| --- | --- | ---: | --- |"]
        for run in data["trace_runs"]:
            counts = ", ".join(f"{k} {v}" for k, v in sorted(run["case_outcomes"].items()))
            lines.append(f"| {run['run_id']} | {counts} | {run['unselected']} | {run['evidence_state']} |")
    if data["evidence_gaps"]:
        lines += ["", "## Missing evidence", ""] + ["- " + gap for gap in data["evidence_gaps"]]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("commit")
    parser.add_argument("inputs", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        summary = summarize(args.commit, args.inputs, datetime.now(timezone.utc).date())
        human_summary = markdown(summary)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        summary = {"schema_version": "ci-quality.v1", "commit": args.commit, "evidence_complete": False,
                   "verification_passed": False, "evidence_gaps": [str(error)]}
        human_summary = f"# CI quality for {args.commit[:12]}\n\nEvidence failed validation. See summary.json for the input error.\n"
    (args.output / "summary.json").write_text(serialize(summary), encoding="utf-8")
    (args.output / "summary.md").write_text(human_summary, encoding="utf-8")
