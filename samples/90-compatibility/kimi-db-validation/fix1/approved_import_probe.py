#!/usr/bin/env python3
"""Bounded verifier probe using the same approved-import policy as pytest."""
from __future__ import annotations

import argparse
import importlib
import importlib.metadata as metadata
import json
from pathlib import Path
import sys
import traceback

TESTWARE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTWARE_ROOT))

from approved_runtime import configure_source_only_imports, verify_importable_inventory


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", required=True, type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    result = {"modules": {}, "versions": {}, "failures": []}
    try:
        result["source_only_imports"] = configure_source_only_imports()
        if not sys.flags.dont_write_bytecode:
            result["failures"].append("approved import probe requires -B")
        result["approved_importable_inventory"] = verify_importable_inventory(runtime)
        result["failures"].extend(result["approved_importable_inventory"]["failures"])
        sys.path.insert(0, str(runtime))
        result["sys_path"] = list(sys.path)
        if not result["failures"]:
            for name in ["sqlite_utils", "click", "dateutil", "sqlite_fts4", "tabulate", "tzdata", "pytest"]:
                module = importlib.import_module(name)
                filename = getattr(module, "__file__", None)
                result["modules"][name] = filename
                try:
                    origin = Path(filename).resolve()
                    origin.relative_to(runtime)
                    if origin.suffix != ".py":
                        result["failures"].append(f"target import is not source .py: {name}: {filename}")
                except (ValueError, TypeError):
                    result["failures"].append(f"non-isolated import: {name}: {filename}")
            for dist in metadata.distributions():
                path = Path(dist._path).resolve()
                try:
                    path.relative_to(runtime)
                except ValueError:
                    result["failures"].append(f"outside distribution: {dist.metadata.get('Name')}: {path}")
            for value in sys.path:
                if "site-packages" in value or "dist-packages" in value:
                    try:
                        Path(value).resolve().relative_to(runtime)
                    except ValueError:
                        result["failures"].append(f"outside site path: {value}")
    except Exception as exc:
        result["failures"].append(f"approved import probe error: {type(exc).__name__}: {exc}")
        result["traceback"] = traceback.format_exc()
    result["pass"] = not result["failures"]
    print(json.dumps(result))
    return 0 if result["pass"] else 90


if __name__ == "__main__":
    raise SystemExit(main())
