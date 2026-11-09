#!/usr/bin/env python3
"""Focused r2-fix1 policy for approved target-runtime imports only."""
from __future__ import annotations

import csv
import hashlib
import importlib.metadata as metadata
from importlib.machinery import (
    EXTENSION_SUFFIXES, SOURCE_SUFFIXES, ExtensionFileLoader, FileFinder, SourceFileLoader,
)
import io
from pathlib import Path
import sys

IMPORTABLE_SUFFIXES = {".py", ".pyc", ".pyo", ".so", ".pyd"}


def configure_source_only_imports() -> dict:
    """Disable future sourceless imports and bytecode writes in this process."""
    def is_file_finder(hook) -> bool:
        return (
            getattr(hook, "__module__", None) == "_frozen_importlib_external"
            and getattr(hook, "__name__", None) in {"path_hook", "path_hook_for_FileFinder"}
        )

    source_only_hook = FileFinder.path_hook(
        (ExtensionFileLoader, EXTENSION_SUFFIXES),
        (SourceFileLoader, SOURCE_SUFFIXES),
    )
    replaced = 0
    hooks = []
    for hook in sys.path_hooks:
        if is_file_finder(hook):
            hooks.append(source_only_hook)
            replaced += 1
        else:
            hooks.append(hook)
    if not replaced:
        raise RuntimeError("no FileFinder path hook was available for source-only policy")
    sys.path_hooks[:] = hooks
    sys.dont_write_bytecode = True
    return {
        "file_finder_hooks_replaced": replaced,
        "loaders": ["ExtensionFileLoader", "SourceFileLoader"],
        "sourceless_file_loader_removed": True,
        "dont_write_bytecode": sys.dont_write_bytecode,
        "interpreter_dont_write_bytecode_flag": sys.flags.dont_write_bytecode,
    }


def _record_path(runtime: Path, dist_info: Path, name: str) -> Path | None:
    relative = Path(name)
    if relative.is_absolute():
        return None
    candidates = [runtime / relative, dist_info / relative]
    if len(relative.parts) >= 3 and relative.parts[:2] == ("..", ".."):
        candidates.append(runtime / Path(*relative.parts[2:]))
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
            resolved.relative_to(runtime)
        except (ValueError, OSError):
            continue
        return resolved
    return None


def verify_importable_inventory(runtime: Path) -> dict:
    """Allow only RECORD-covered importables; reject all target bytecode."""
    runtime = runtime.resolve()
    allowed = set()
    failures = []
    record_count = 0
    try:
        for dist in metadata.distributions(path=[str(runtime)]):
            dist_info = Path(dist._path).resolve()
            raw = dist.read_text("RECORD")
            if not raw:
                failures.append(f"approved inventory RECORD missing or empty: {dist_info}")
                continue
            record_count += 1
            for row in csv.reader(io.StringIO(raw)):
                if not row or not row[0]:
                    failures.append(f"approved inventory contains an empty RECORD row: {dist_info}")
                    continue
                resolved = _record_path(runtime, dist_info, row[0])
                if resolved is not None:
                    allowed.add(resolved)
    except Exception as exc:
        failures.append(f"approved inventory RECORD error: {type(exc).__name__}: {exc}")
    observed = []
    unexpected = []
    bytecode = []
    for path in sorted(runtime.rglob("*")):
        if not path.is_file() or path.suffix not in IMPORTABLE_SUFFIXES:
            continue
        relative = path.relative_to(runtime).as_posix()
        observed.append(relative)
        resolved = path.resolve()
        if path.suffix in {".pyc", ".pyo"}:
            bytecode.append(relative)
        if resolved not in allowed:
            unexpected.append(relative)
    if bytecode:
        failures.append(f"target runtime bytecode is forbidden: {bytecode}")
    if unexpected:
        failures.append(f"importables not allowlisted by installed RECORD: {unexpected}")
    return {
        "scope": "target runtime .py/.pyc/.pyo/.so/.pyd files only",
        "runtime": str(runtime),
        "record_count": record_count,
        "allowed_importable_count": len(allowed & {runtime / name for name in observed}),
        "observed_importable_count": len(observed),
        "observed_importables": observed,
        "unexpected_importables": unexpected,
        "bytecode_files": bytecode,
        "failures": failures,
        "pass": not failures,
    }


def remove_runtime_bytecode(runtime: Path) -> dict:
    """Remove installer-generated target bytecode before approved verification."""
    runtime = runtime.resolve()
    removed = []
    for path in sorted(runtime.rglob("*")):
        if path.suffix not in {".pyc", ".pyo"} or not path.is_file():
            continue
        removed.append({
            "path": path.relative_to(runtime).as_posix(),
            "sha256_before_removal": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size,
        })
        path.unlink()
    for path in sorted(runtime.rglob("__pycache__"), reverse=True):
        if path.is_dir() and not any(path.iterdir()):
            path.rmdir()
    return {"runtime": str(runtime), "removed_count": len(removed), "removed": removed}
