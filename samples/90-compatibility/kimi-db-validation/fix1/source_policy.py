#!/usr/bin/env python3
"""Frozen control-source digest and per-run content snapshots."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil

MANIFEST_NAME = "control-source-manifest.json"
DIGEST_PATH = Path("provenance/control-source-digest.sha256")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_manifest(root: Path) -> tuple[Path, dict, str]:
    path = root / MANIFEST_NAME
    raw = path.read_bytes()
    return path, json.loads(raw), hashlib.sha256(raw).hexdigest()


def verify_source(root: Path) -> dict:
    failures = []
    try:
        manifest_path, manifest, digest = load_manifest(root)
        expected_digest = (root / DIGEST_PATH).read_text().strip()
        if digest != expected_digest:
            failures.append(f"control source digest mismatch: {digest} != {expected_digest}")
        files = manifest.get("files", {})
        if not files:
            failures.append("control source manifest has no files")
        actual = {}
        for name, expected in sorted(files.items()):
            path = root / name
            if not path.is_file():
                failures.append(f"controlled source missing: {name}")
                continue
            observed = sha256(path)
            actual[name] = observed
            if observed != expected:
                failures.append(f"controlled source changed: {name}: {observed} != {expected}")
        return {
            "pass": not failures, "source_digest": digest,
            "expected_source_digest": expected_digest,
            "manifest": str(manifest_path), "files": actual, "failures": failures,
        }
    except Exception as exc:
        return {
            "pass": False, "source_digest": None, "files": {},
            "failures": failures + [f"source verification error: {type(exc).__name__}: {exc}"],
        }


def create_snapshot(root: Path, run_dir: Path) -> dict:
    verification = verify_source(root)
    if not verification["pass"]:
        return verification
    snapshot_dir = run_dir / "source-snapshot"
    snapshot_dir.mkdir(exist_ok=False)
    for name in verification["files"]:
        destination = snapshot_dir / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, destination)
        if sha256(destination) != verification["files"][name]:
            return {**verification, "pass": False, "failures": [f"snapshot copy mismatch: {name}"]}
    manifest_path, manifest, digest = load_manifest(root)
    manifest_snapshot = run_dir / MANIFEST_NAME
    shutil.copyfile(manifest_path, manifest_snapshot)
    if sha256(manifest_snapshot) != digest:
        return {**verification, "pass": False, "failures": ["control-source manifest snapshot mismatch"]}
    snapshot = {
        "source_digest": digest,
        "files": verification["files"],
        "snapshot_directory": str(snapshot_dir),
        "control_source_manifest_snapshot": str(manifest_snapshot),
        "control_source_manifest_sha256": digest,
        "content_snapshot_complete": True,
    }
    (run_dir / "source-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
    return {**verification, "snapshot": snapshot}


def verify_against_snapshot(root: Path, run_dir: Path, expected_digest: str) -> dict:
    current = verify_source(root)
    failures = list(current["failures"])
    if current.get("source_digest") != expected_digest:
        failures.append(f"run source digest changed: {current.get('source_digest')} != {expected_digest}")
    snapshot_json = run_dir / "source-snapshot.json"
    snapshot_dir = run_dir / "source-snapshot"
    if not snapshot_json.is_file() or not snapshot_dir.is_dir():
        failures.append("per-run source snapshot missing")
        snapshot = {"files": {}}
    else:
        snapshot = json.loads(snapshot_json.read_text())
        if snapshot.get("source_digest") != expected_digest:
            failures.append("snapshot digest does not match run binding")
        manifest_snapshot = run_dir / MANIFEST_NAME
        if not manifest_snapshot.is_file() or sha256(manifest_snapshot) != expected_digest:
            failures.append("control-source manifest snapshot missing or changed")
        for name, expected in snapshot.get("files", {}).items():
            copied = snapshot_dir / name
            live = root / name
            if not copied.is_file() or sha256(copied) != expected:
                failures.append(f"snapshot content missing or changed: {name}")
            if not live.is_file() or sha256(live) != expected:
                failures.append(f"live source differs from run snapshot: {name}")
    return {
        "pass": not failures, "source_digest": current.get("source_digest"),
        "expected_source_digest": expected_digest, "files": current.get("files", {}),
        "failures": failures, "snapshot_directory": str(snapshot_dir),
    }
