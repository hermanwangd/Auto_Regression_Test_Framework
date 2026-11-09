#!/usr/bin/env python3
"""Fail-closed trusted-wheel, RECORD and isolated-runtime verification."""
from __future__ import annotations

import argparse
import base64
import configparser
import csv
import datetime as dt
import hashlib
import importlib.metadata as metadata
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

from approved_runtime import verify_importable_inventory
from process_policy import run_bounded_subprocess


def canonical(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_lock(path: Path) -> dict[str, dict[str, str]]:
    logical = path.read_text().replace("\\\n", " ")
    locked = {}
    for line in logical.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([^\s]+)\s+--hash=sha256:([0-9a-f]{64})", line)
        if not match:
            raise ValueError(f"unsupported locked requirement: {line!r}")
        name, version, wheel_hash = match.groups()
        locked[canonical(name)] = {"name": name, "version": version, "wheel_sha256": wheel_hash}
    return locked


def decode_record_hash(encoded: str) -> str:
    algorithm, value = encoded.split("=", 1)
    if algorithm != "sha256" or not value:
        raise ValueError(f"unsupported or empty RECORD hash: {encoded}")
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding).hex()


def encoded_sha256(raw: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(raw).digest()).decode().rstrip("=")


def parse_rows(raw: bytes, label: str) -> tuple[list[list[str]], dict[str, list[str]], list[str]]:
    errors = []
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8"))))
    except Exception as exc:
        return [], {}, [f"{label} is not valid UTF-8 CSV: {exc}"]
    if not rows:
        errors.append(f"{label} is empty")
    mapping = {}
    hashed = 0
    for row in rows:
        if not row or not row[0]:
            errors.append(f"{label} contains an empty row")
            continue
        name = row[0]
        if name in mapping:
            errors.append(f"{label} duplicate row: {name}")
        mapping[name] = row
        encoded = row[1] if len(row) > 1 else ""
        size = row[2] if len(row) > 2 else ""
        if encoded or size:
            if not encoded or not size:
                errors.append(f"{label} partial hash/size: {name}")
            else:
                try:
                    decode_record_hash(encoded)
                    int(size)
                    hashed += 1
                except (ValueError, TypeError) as exc:
                    errors.append(f"{label} invalid hash/size: {name}: {exc}")
    if hashed == 0:
        errors.append(f"{label} has no hashed content entries")
    return rows, mapping, errors


def resolve_installed(runtime: Path, dist_info: Path, name: str) -> Path | None:
    relative = Path(name)
    if relative.is_absolute():
        return None
    candidates = [runtime / relative, dist_info / relative]
    if len(relative.parts) >= 3 and relative.parts[:2] == ("..", ".."):
        candidates.append(runtime / Path(*relative.parts[2:]))
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
            resolved.relative_to(runtime.resolve())
        except (ValueError, OSError):
            continue
        if resolved.is_file():
            return resolved
    return None


def verify_distribution(
    runtime: Path, dist_info: Path, locked: dict, wheel_path: Path,
    *, allow_missing_generated_pyc: bool = False,
) -> dict:
    result = {
        "distribution": locked["name"], "version": locked["version"],
        "dist_info": str(dist_info), "wheel": str(wheel_path),
        "wheel_sha256_expected": locked["wheel_sha256"],
        "wheel_sha256_observed": sha256(wheel_path) if wheel_path.is_file() else None,
        "wheel_files_verified": 0, "installed_wheel_files_verified": 0,
        "generated_pyc_verified": [], "generated_console_scripts_verified": [],
        "record_self_verified": False, "exceptions": [], "failures": [], "pass": False,
    }
    failures = result["failures"]
    if not wheel_path.is_file() or result["wheel_sha256_observed"] != locked["wheel_sha256"]:
        failures.append("trusted wheel missing or SHA-256 does not match lock")
        return result
    if not dist_info.is_dir():
        failures.append("dist-info missing")
        return result
    metadata_path = dist_info / "METADATA"
    record_path = dist_info / "RECORD"
    if not metadata_path.is_file():
        failures.append("installed METADATA missing")
    if not record_path.is_file():
        failures.append("installed RECORD missing")
        return result
    try:
        dist = metadata.PathDistribution(dist_info)
        if canonical(dist.metadata.get("Name", "")) != canonical(locked["name"]):
            failures.append("installed METADATA Name mismatch")
        if dist.version != locked["version"]:
            failures.append(f"installed version mismatch: {dist.version} != {locked['version']}")
    except Exception as exc:
        failures.append(f"installed METADATA invalid: {exc}")
    try:
        archive = zipfile.ZipFile(wheel_path)
        bad_member = archive.testzip()
        if bad_member:
            failures.append(f"wheel CRC failure: {bad_member}")
        wheel_record_names = [name for name in archive.namelist() if name.endswith(".dist-info/RECORD")]
        if len(wheel_record_names) != 1:
            failures.append(f"wheel must have exactly one dist-info RECORD: {wheel_record_names}")
            archive.close()
            return result
        wheel_record_name = wheel_record_names[0]
        wheel_metadata_name = wheel_record_name[:-len("RECORD")] + "METADATA"
        from email.parser import Parser
        wheel_meta = Parser().parsestr(archive.read(wheel_metadata_name).decode("utf-8"))
        if canonical(wheel_meta.get("Name", "")) != canonical(locked["name"]) or wheel_meta.get("Version") != locked["version"]:
            failures.append("wheel METADATA identity/version mismatch")
        wheel_rows, wheel_map, wheel_errors = parse_rows(archive.read(wheel_record_name), "wheel RECORD")
        failures.extend(wheel_errors)
        wheel_members = {name for name in archive.namelist() if not name.endswith("/")}
        if set(wheel_map) != wheel_members:
            failures.append(f"wheel RECORD/member coverage mismatch: missing={sorted(wheel_members-set(wheel_map))}, extra={sorted(set(wheel_map)-wheel_members)}")
        for name, row in wheel_map.items():
            if name == wheel_record_name:
                if len(row) > 1 and row[1]:
                    failures.append("wheel RECORD self must use the specified unhashed exception")
                continue
            if name not in wheel_members:
                continue
            if len(row) < 3 or not row[1] or not row[2]:
                failures.append(f"wheel content row is not hashed: {name}")
                continue
            raw = archive.read(name)
            if row[1] != "sha256=" + encoded_sha256(raw) or int(row[2]) != len(raw):
                failures.append(f"wheel RECORD content mismatch: {name}")
            else:
                result["wheel_files_verified"] += 1
        entry_points = configparser.ConfigParser()
        if wheel_record_name[:-len("RECORD")] + "entry_points.txt" in wheel_members:
            entry_points.read_string(archive.read(wheel_record_name[:-len("RECORD")] + "entry_points.txt").decode("utf-8"))
        installed_raw = record_path.read_bytes()
        installed_rows, installed_map, installed_errors = parse_rows(installed_raw, "installed RECORD")
        failures.extend(installed_errors)
        wheel_record_relative = wheel_record_name
        for name, wheel_row in wheel_map.items():
            installed_row = installed_map.get(name)
            if installed_row is None:
                failures.append(f"installed RECORD missing wheel-covered row: {name}")
                continue
            if name == wheel_record_relative:
                installed_file = resolve_installed(runtime, dist_info, name)
                if installed_file is None:
                    failures.append("installed RECORD self missing")
                else:
                    result["record_self_verified"] = True
                    result["exceptions"].append({"type": "record_self", "path": name, "required_exists": True})
                continue
            if len(installed_row) < 3 or not installed_row[1] or not installed_row[2]:
                failures.append(f"required installed file was dehashed: {name}")
                continue
            if installed_row[1] != wheel_row[1] or installed_row[2] != wheel_row[2]:
                failures.append(f"installed RECORD does not match trusted wheel row: {name}")
                continue
            installed_file = resolve_installed(runtime, dist_info, name)
            if installed_file is None:
                failures.append(f"installed wheel-covered file missing or outside runtime: {name}")
                continue
            raw = installed_file.read_bytes()
            if "sha256=" + encoded_sha256(raw) != wheel_row[1] or len(raw) != int(wheel_row[2]):
                failures.append(f"installed bytes differ from trusted wheel: {name}")
            else:
                result["installed_wheel_files_verified"] += 1
        for name, row in installed_map.items():
            if name in wheel_map:
                continue
            installed_file = resolve_installed(runtime, dist_info, name)
            relative = Path(name)
            if len(relative.parts) >= 3 and relative.parts[:2] == ("..", "..") and relative.parts[2] == "bin":
                if len(row) < 3 or not row[1] or not row[2] or installed_file is None:
                    failures.append(f"generated console script must exist and be hashed: {name}")
                    continue
                script_name = relative.parts[-1]
                entries = entry_points.items("console_scripts") if entry_points.has_section("console_scripts") else []
                matching = [value for key, value in entries if key == script_name]
                raw = installed_file.read_bytes()
                if not matching:
                    # Versioned aliases such as pip3.12 are trusted only when
                    # byte-identical to an installed script explicitly named by
                    # the trusted wheel's console_scripts metadata.
                    alias_sources = [key for key, _ in entries if (runtime / "bin" / key).is_file() and (runtime / "bin" / key).read_bytes() == raw]
                    if not alias_sources:
                        failures.append(f"untrusted generated console script or alias: {name}")
                        continue
                    matching = [dict(entries)[alias_sources[0]]]
                target = matching[0].split(":", 1)
                text = installed_file.read_text(errors="replace")
                if len(target) != 2 or target[0] not in text or target[1].split()[0] not in text:
                    failures.append(f"console script does not correspond to wheel entry point: {name}")
                    continue
                if "sha256=" + encoded_sha256(raw) != row[1] or len(raw) != int(row[2]):
                    failures.append(f"generated console script hash mismatch: {name}")
                    continue
                result["generated_console_scripts_verified"].append(name)
                result["exceptions"].append({"type": "installer_generated_console_script", "path": name, "hash_verified": True})
                continue
            installer_metadata = {
                f"{dist_info.name}/INSTALLER": b"pip\n",
                f"{dist_info.name}/REQUESTED": b"",
            }
            if name in installer_metadata:
                if installed_file is None or installed_file.read_bytes() != installer_metadata[name]:
                    failures.append(f"installer metadata missing or unexpected content: {name}")
                    continue
                raw = installed_file.read_bytes()
                if len(row) > 1 and row[1] and ("sha256=" + encoded_sha256(raw) != row[1] or len(raw) != int(row[2])):
                    failures.append(f"installer metadata hash mismatch: {name}")
                    continue
                result["exceptions"].append({"type": "installer_metadata", "path": name, "required_exists": True, "exact_content_verified": True, "hashed": bool(len(row) > 1 and row[1])})
                continue
            is_pyc = relative.suffix == ".pyc" and relative.parent.name == "__pycache__" and ".." not in relative.parts
            if is_pyc:
                if len(row) > 1 and (row[1] or (len(row) > 2 and row[2])):
                    failures.append(f"generated pyc exception must remain explicitly unhashed: {name}")
                    continue
                if installed_file is None:
                    if allow_missing_generated_pyc:
                        result["exceptions"].append({
                            "type": "generated_pyc_disabled", "path": name,
                            "required_exists": False, "approved_source_only": True,
                        })
                    else:
                        failures.append(f"generated pyc missing: {name}")
                    continue
                source = relative.parent.parent / (relative.name.split(".", 1)[0] + ".py")
                if source.as_posix() not in wheel_map:
                    failures.append(f"pyc has no trusted wheel source: {name}")
                    continue
                if installed_file.read_bytes()[:4] != importlib.util.MAGIC_NUMBER:
                    failures.append(f"generated pyc magic mismatch: {name}")
                    continue
                result["generated_pyc_verified"].append(name)
                result["exceptions"].append({"type": "generated_pyc", "path": name, "required_exists": True, "source": source.as_posix()})
                continue
            failures.append(f"unclassified extra installed RECORD row: {name}")
        archive.close()
    except Exception as exc:
        failures.append(f"trusted wheel/RECORD verification error: {type(exc).__name__}: {exc}")
    result["pass"] = not failures
    return result


def isolated_import_check(runtime: Path) -> dict:
    env = os.environ.copy()
    for name in list(env):
        if name.startswith("PYTHON") or name.startswith("PYTEST_") or name == "VIRTUAL_ENV":
            env.pop(name, None)
    probe = Path(__file__).resolve().parent / "approved_import_probe.py"
    command = [sys.executable, "-B", "-I", "-S", str(probe), "--runtime", str(runtime)]
    with tempfile.TemporaryDirectory(prefix="db02-r2-fix1-import-probe-") as temporary:
        stdout = Path(temporary) / "stdout.log"
        stderr = Path(temporary) / "stderr.log"
        completed = run_bounded_subprocess(
            command, cwd=runtime, env=env, stdout_path=stdout, stderr_path=stderr,
            timeout_seconds=60,
        )
        stdout_text = stdout.read_text() if stdout.exists() else ""
        stderr_text = stderr.read_text() if stderr.exists() else ""
    try:
        result = json.loads(stdout_text)
    except json.JSONDecodeError:
        result = {"modules": {}, "versions": {}, "failures": ["approved import probe did not return JSON"]}
    result.update({
        "command": command, "subprocess": completed, "exit_code": completed["exit_code"],
        "stdout": stdout_text, "stderr": stderr_text,
    })
    if completed["status"] != "completed" or completed["exit_code"] != 0 or not completed["cleanup_pass"]:
        result["failures"].append(f"approved import probe rejected: {completed['status']}:{completed['exit_code']}")
    return result


def verify(runtime: Path, lock: Path, wheelhouse: Path) -> dict:
    locked = parse_lock(lock)
    wheels = {}
    wheel_failures = []
    for wheel in sorted(wheelhouse.glob("*.whl")):
        try:
            with zipfile.ZipFile(wheel) as archive:
                record = next(name for name in archive.namelist() if name.endswith(".dist-info/METADATA"))
                from email.parser import Parser
                meta = Parser().parsestr(archive.read(record).decode("utf-8"))
                key = canonical(meta.get("Name", ""))
                if key in wheels:
                    wheel_failures.append(f"duplicate wheel for {key}")
                wheels[key] = wheel
        except Exception as exc:
            wheel_failures.append(f"invalid wheel {wheel.name}: {exc}")
    missing_wheels = sorted(set(locked) - set(wheels))
    unexpected_wheels = sorted(set(wheels) - set(locked))
    distributions = {}
    for dist in metadata.distributions(path=[str(runtime)]):
        name = dist.metadata.get("Name")
        if name:
            distributions[canonical(name)] = dist
    missing = sorted(set(locked) - set(distributions))
    unexpected = sorted(set(distributions) - set(locked))
    version_mismatches = {}
    records = {}
    for key, dist in sorted(distributions.items()):
        if key in locked and dist.version != locked[key]["version"]:
            version_mismatches[key] = {"expected": locked[key]["version"], "observed": dist.version}
        if key in locked and key in wheels:
            records[key] = verify_distribution(
                runtime, Path(dist._path), locked[key], wheels[key],
                allow_missing_generated_pyc=True,
            )
    record_failures = {key: value["failures"] for key, value in records.items() if value["failures"]}
    inventory = verify_importable_inventory(runtime)
    imports = isolated_import_check(runtime) if not any([missing, unexpected, version_mismatches, missing_wheels, unexpected_wheels]) else {
        "failures": ["skipped until locked runtime and wheel coverage are complete"], "exit_code": 1,
    }
    result = {
        "verified_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "runtime": str(runtime), "requirements_lock": str(lock), "wheelhouse": str(wheelhouse),
        "requirements_lock_sha256": sha256(lock), "locked_count": len(locked),
        "installed_count": len(distributions), "wheel_count": len(wheels),
        "locked": locked, "installed_versions": {key: dist.version for key, dist in sorted(distributions.items())},
        "missing_distributions": missing, "unexpected_distributions": unexpected,
        "missing_wheels": missing_wheels, "unexpected_wheels": unexpected_wheels,
        "wheel_failures": wheel_failures, "version_mismatches": version_mismatches,
        "records": records, "record_failures": record_failures,
        "approved_importable_inventory": inventory,
        "isolated_import_check": imports, "tests_rerun": False,
    }
    result["pass"] = not any([
        missing, unexpected, version_mismatches, missing_wheels, unexpected_wheels,
        wheel_failures, record_failures, len(records) != len(locked),
        inventory["failures"], imports.get("failures"), imports.get("exit_code", 1) != 0,
    ])
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--requirements", type=Path, required=True)
    parser.add_argument("--wheelhouse", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.runtime.resolve(), args.requirements.resolve(), args.wheelhouse.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({
        "pass": result["pass"], "locked_count": result["locked_count"],
        "installed_count": result["installed_count"], "wheel_count": result["wheel_count"],
        "record_failure_distributions": sorted(result["record_failures"]),
        "approved_inventory_failures": result["approved_importable_inventory"]["failures"],
        "isolated_import_failures": result["isolated_import_check"].get("failures"),
    }, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
