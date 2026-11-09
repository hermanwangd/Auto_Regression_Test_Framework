#!/usr/bin/env python3
"""Strict testcase-node JUnit validation with frozen identity and run bindings."""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET


def node_identity(node: ET.Element) -> str:
    return node.attrib.get("classname", "") + "::" + node.attrib.get("name", "")


def direct_counts(suite: ET.Element) -> dict:
    nodes = list(suite.findall("testcase"))
    return {
        "tests": len(nodes),
        "failures": sum(node.find("failure") is not None for node in nodes),
        "errors": sum(node.find("error") is not None for node in nodes),
        "skipped": sum(node.find("skipped") is not None for node in nodes),
    }


def validate_junit(
    path: Path,
    binding: dict,
    run_token: str,
    manifest_hash: str,
    source_digest: str,
) -> dict:
    errors = []
    try:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("current-run JUnit missing or empty")
        root = ET.parse(path).getroot()
    except Exception as exc:
        return {"pass": False, "errors": [f"JUnit parse error: {type(exc).__name__}: {exc}"], "path": str(path)}

    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    if not suites:
        errors.append("JUnit has no testsuite")
    nodes = list(root.iter("testcase"))
    identities = [node_identity(node) for node in nodes]
    expected = {
        case_id: f"{value['classname']}::{value['name']}"
        for case_id, value in binding["cases"].items()
    }
    expected_identities = set(expected.values())
    counts_by_identity = Counter(identities)
    for identity in sorted(set(identities) - expected_identities):
        errors.append(f"unknown testcase node: {identity}")
    for identity, count in sorted(counts_by_identity.items()):
        if count > 1:
            errors.append(f"duplicate testcase node: {identity}: {count}")
    for identity in sorted(expected_identities - set(identities)):
        errors.append(f"missing testcase node: {identity}")

    properties = {}
    for suite in suites:
        for prop in suite.findall("./properties/property"):
            name = prop.attrib.get("name")
            value = prop.attrib.get("value")
            if name in {"run_token", "manifest_sha256", "source_digest"}:
                if name in properties and properties[name] != value:
                    errors.append(f"conflicting JUnit property: {name}")
                properties[name] = value
    required_properties = {
        "run_token": run_token,
        "manifest_sha256": manifest_hash,
        "source_digest": source_digest,
    }
    for name, expected_value in required_properties.items():
        if properties.get(name) != expected_value:
            errors.append(f"JUnit property mismatch: {name}: {properties.get(name)} != {expected_value}")

    for suite in suites:
        observed = direct_counts(suite)
        for key, actual in observed.items():
            declared = int(suite.attrib.get(key, 0))
            if declared != actual:
                errors.append(f"suite counter mismatch: {suite.attrib.get('name')}:{key}: {declared} != nodes {actual}")
    node_counts = {"tests": len(nodes)}
    for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        node_counts[key] = sum(node.find(tag) is not None for node in nodes)
    if root.tag == "testsuites":
        for key, actual in node_counts.items():
            if key in root.attrib and int(root.attrib[key]) != actual:
                errors.append(f"root counter mismatch: {key}: {root.attrib[key]} != nodes {actual}")

    outcomes = {}
    for case_id, identity in expected.items():
        matching = [node for node in nodes if node_identity(node) == identity]
        if len(matching) == 1:
            node = matching[0]
            outcomes[case_id] = (
                "failed" if node.find("failure") is not None else
                "error" if node.find("error") is not None else
                "skipped" if node.find("skipped") is not None else "passed"
            )
        else:
            outcomes[case_id] = "NOT RUN"
    if any(outcome != "passed" for outcome in outcomes.values()):
        errors.append(f"non-passing testcase outcomes: {outcomes}")
    return {
        "pass": not errors,
        "path": str(path),
        "errors": errors,
        "testcase_node_count": len(nodes),
        "testcase_identities": identities,
        "expected_identities": sorted(expected_identities),
        "counts": node_counts,
        "case_outcomes": outcomes,
        "properties": properties,
    }
