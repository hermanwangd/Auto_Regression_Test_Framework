#!/usr/bin/env python3
"""Pytest entry point for -B -I -S with approved source-only target imports."""
from __future__ import annotations

import argparse
import importlib
import importlib.metadata as metadata
import json
import os
from pathlib import Path
import sys
import sysconfig
import traceback

TESTWARE_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTWARE_ROOT))

from approved_runtime import configure_source_only_imports, verify_importable_inventory


def write_json(path: Path, value) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, default=str) + "\n")
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", required=True, type=Path)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--probe-output", required=True, type=Path)
    parser.add_argument("pytest_args", nargs="*")
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    source_root = args.source_root.resolve()
    for name in list(os.environ):
        if name.startswith("PYTHON") or name.startswith("PYTEST_") or name == "VIRTUAL_ENV":
            os.environ.pop(name, None)
    os.environ["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
    os.environ["PYTHONNOUSERSITE"] = "1"
    initial_sys_path = list(sys.path)
    sys.path.insert(0, str(runtime))
    stdlib_roots = {
        Path(sysconfig.get_paths()["stdlib"]).resolve(),
        Path(sysconfig.get_paths()["platstdlib"]).resolve(),
    }
    stdlib_archive = Path(sysconfig.get_paths()["stdlib"]).with_name(
        f"python{sys.version_info.major}{sys.version_info.minor}.zip"
    ).resolve()

    def path_allowed(value: str) -> bool:
        if not value:
            value = os.getcwd()
        path = Path(value).resolve()
        if path == stdlib_archive:
            return True
        if any(part in {"site-packages", "dist-packages"} for part in path.parts):
            try:
                path.relative_to(runtime)
            except ValueError:
                return False
        for root in (runtime, source_root, TESTWARE_ROOT, *stdlib_roots):
            try:
                path.relative_to(root)
                return True
            except ValueError:
                pass
        return False

    result = {
        "python": sys.version,
        "python_flags": {
            "isolated": sys.flags.isolated, "no_site": sys.flags.no_site,
            "no_user_site": sys.flags.no_user_site, "ignore_environment": sys.flags.ignore_environment,
            "dont_write_bytecode": sys.flags.dont_write_bytecode,
        },
        "initial_sys_path": initial_sys_path,
        "runtime": str(runtime), "source_root": str(source_root),
        "external_plugin_allowlist": [],
        "pytest_plugin_autoload": "disabled",
        "failures": [],
    }
    if not (sys.flags.isolated and sys.flags.no_site and sys.flags.no_user_site and sys.flags.ignore_environment):
        result["failures"].append("required -I/-S interpreter isolation flags are not active")
    result["sys_path_after_runtime_insert"] = list(sys.path)
    result["module_origins"] = {}
    try:
        result["source_only_imports"] = configure_source_only_imports()
        result["approved_importable_inventory"] = verify_importable_inventory(runtime)
        result["failures"].extend(result["approved_importable_inventory"]["failures"])
        if result["failures"]:
            raise RuntimeError("approved runtime source-only preflight rejected")
        for name in ["pytest", "sqlite_utils", "click", "dateutil", "sqlite_fts4", "tabulate", "tzdata"]:
            module = importlib.import_module(name)
            filename = getattr(module, "__file__", None)
            result["module_origins"][name] = filename
            try:
                origin = Path(filename).resolve()
                origin.relative_to(runtime)
                if origin.suffix != ".py":
                    result["failures"].append(f"target module did not load from source .py: {name}: {filename}")
            except (ValueError, TypeError):
                result["failures"].append(f"module outside trusted runtime: {name}: {filename}")
        distributions = []
        for dist in metadata.distributions():
            dist_path = Path(dist._path).resolve()
            item = {"name": dist.metadata.get("Name"), "version": dist.version, "path": str(dist_path)}
            distributions.append(item)
            try:
                dist_path.relative_to(runtime)
            except ValueError:
                result["failures"].append(f"distribution outside trusted runtime: {item}")
        result["discoverable_distributions"] = distributions
    except Exception as exc:
        result["failures"].append(f"early isolated import failure: {type(exc).__name__}: {exc}")

    exit_code = 90
    result.update({
        "pytest_exit_code": exit_code,
        "plugins": [],
        "plugin_distinfo": None,
        "pass": False,
        "probe_write_complete": False,
    })
    try:
        import pytest

        class RunProbe:
            def pytest_sessionfinish(self, session, exitstatus):
                try:
                    manager = session.config.pluginmanager
                    plugins = []
                    for name, plugin in manager.list_name_plugin():
                        if plugin is None:
                            plugins.append({"name": name, "blocked": True, "plugin": None, "module": None, "origin": None})
                            continue
                        module_name = (
                            getattr(plugin, "__module__", None)
                            or getattr(plugin, "__name__", None)
                            or type(plugin).__module__
                        )
                        if module_name == "__main__":
                            module = plugin if hasattr(plugin, "__file__") else None
                        else:
                            module = importlib.import_module(module_name)
                        origin = getattr(module, "__file__", None)
                        plugins.append({"name": name, "blocked": False, "plugin": repr(plugin), "module": module_name, "origin": origin})
                        if origin and not path_allowed(origin):
                            result["failures"].append(f"plugin module outside allowed roots: {name}: {origin}")
                        if origin:
                            try:
                                plugin_origin = Path(origin).resolve()
                                plugin_origin.relative_to(runtime)
                                if plugin_origin.suffix != ".py":
                                    result["failures"].append(f"target plugin did not load from source .py: {name}: {origin}")
                            except ValueError:
                                pass
                    distinfo = manager.list_plugin_distinfo()
                    result["plugins"] = plugins
                    result["plugin_distinfo"] = [str(item) for item in distinfo]
                    if distinfo:
                        result["failures"].append(f"external pytest distributions loaded despite empty allowlist: {distinfo}")
                except Exception as exc:
                    result["failures"].append(f"pytest sessionfinish probe error: {type(exc).__name__}: {exc}")
                    result["sessionfinish_traceback"] = traceback.format_exc()
                finally:
                    result["final_sys_path"] = list(sys.path)
                    outside = [value for value in sys.path if not path_allowed(value)]
                    result["outside_sys_path_entries"] = outside
                    if outside:
                        result["failures"].append(f"outside sys.path entries: {outside}")

        if not result["failures"]:
            exit_code = pytest.main(args.pytest_args, plugins=[RunProbe()])
    except Exception as exc:
        exit_code = 90
        result["failures"].append(f"pytest/probe execution error: {type(exc).__name__}: {exc}")
        result["pytest_traceback"] = traceback.format_exc()
    finally:
        result["pytest_exit_code"] = exit_code
        result["pass"] = not result["failures"]
        result["probe_write_complete"] = True
        write_json(args.probe_output, result)
    return exit_code if not result["failures"] else 90


if __name__ == "__main__":
    raise SystemExit(main())
