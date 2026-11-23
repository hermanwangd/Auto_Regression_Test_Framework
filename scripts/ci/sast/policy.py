"""Prepare the pinned rules and evaluate Semgrep differential JSON reports."""
import argparse
import json
import math
import re
import shutil
import sys
from collections import Counter
from pathlib import Path

GITLAB_LANGUAGES = {"java", "javascript", "python", "generic", "properties", "yaml"}
COMMUNITY_LANGUAGES = {"java", "python", "bash", "yaml", "dockerfile", "generic"}


def copy_rules(source, destination, family):
    destination.mkdir(parents=True)
    count = 0
    for path in sorted(source.rglob("*")):
        if path.suffix not in {".yml", ".yaml"} or not path.is_file() or path.is_symlink():
            continue
        parts = path.relative_to(source).parts
        if family == "gitlab":
            language = parts[2] if parts[0] == "rules" and len(parts) > 2 else parts[0]
            if language not in GITLAB_LANGUAGES or not path.match("rule-*.yml"):
                continue
        else:
            if parts[0] not in COMMUNITY_LANGUAGES or ".test." in path.name:
                continue
            if "security" not in parts and parts[:2] not in {
                ("generic", "secrets"), ("generic", "ci"), ("generic", "unicode"),
            }:
                continue
            if not re.search(r"^rules:", path.read_text(encoding="utf-8"), re.MULTILINE):
                continue
        shutil.copyfile(path, destination / "__".join(parts))
        count += 1
    # An empty or partially downloaded ruleset must never produce a green check.
    expected = {"gitlab": 279, "community": 668}[family]
    if count != expected:
        raise ValueError(f"{family}: expected {expected} pinned config files, selected {count}")
    print(f"{family}: {count} pinned config files")


def severity(finding):
    extra = finding["extra"]
    rating = extra["metadata"].get("security-severity")
    if rating is not None:
        value = str(rating).strip().upper()
        if value in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNKNOWN"}:
            return value
        numeric = float(value)
        if not math.isfinite(numeric) or not 0 <= numeric <= 10:
            raise ValueError("Invalid security-severity")
        if numeric >= 9:
            return "CRITICAL"
        if numeric >= 7:
            return "HIGH"
        return "MEDIUM" if numeric >= 4 else "LOW"
    value = extra["severity"].upper()
    legacy = {"ERROR": "HIGH", "WARNING": "MEDIUM", "INFO": "LOW"}
    if value in legacy:
        return legacy[value]
    if value not in {"CRITICAL", "HIGH", "MEDIUM", "LOW"}:
        raise ValueError("Unsupported Semgrep severity")
    return value


def gate(paths):
    failed = False
    for path in paths:
        report = json.loads(path.read_bytes())
        findings, errors = report["results"], report["errors"]
        if not isinstance(findings, list) or not isinstance(errors, list):
            raise ValueError("Malformed scanner report")
        counts = Counter(severity(finding) for finding in findings)
        diagnostics = Counter(str(error["type"][0] if isinstance(error["type"], list) else error["type"])
                              for error in errors)
        print(f"{path.name}: new Critical={counts['CRITICAL']}, High={counts['HIGH']}, "
              f"other={len(findings)-counts['CRITICAL']-counts['HIGH']}, diagnostics={len(errors)}")
        if diagnostics:
            print("Incomplete scan:", dict(diagnostics), file=sys.stderr)
        failed |= bool(diagnostics or counts["CRITICAL"] or counts["HIGH"])
    return int(failed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("gitlab", type=Path)
    prepare.add_argument("community", type=Path)
    prepare.add_argument("out", type=Path)
    evaluate = commands.add_parser("gate")
    evaluate.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        for family in ("gitlab", "community"):
            copy_rules(getattr(args, family), args.out / family, family)
    else:
        sys.exit(gate(args.reports))
