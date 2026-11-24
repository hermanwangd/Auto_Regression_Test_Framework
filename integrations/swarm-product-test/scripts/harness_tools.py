"""Resolve operator-selected toolchains for the module's fixed Java and C build actions."""
import os
from pathlib import Path
import re
import shutil


def jdk_tool(home, name):
    if name not in {"java", "javac"}:
        raise ValueError("Expected a Java runtime or compiler")
    filename = name + (".exe" if os.name == "nt" else "")
    tool = (Path(home) / "bin" / filename).resolve(strict=True)
    runtime = tool.parent.name == "bin" and (tool.parent.parent / "lib/modules").is_file()
    # macOS also supplies system JDK dispatchers under /usr/bin.
    system = Path("/usr/bin") / filename
    if not runtime and not (os.name == "posix" and system.is_file() and tool.samefile(system)):
        raise ValueError("Selected Java tool must belong to a runtime installation")
    if tool.name.lower() != filename or not tool.is_file() or not os.access(tool, os.X_OK):
        raise ValueError("Selected JDK launcher does not match " + name)
    return tool


def compiler_tool(configured):
    candidate = shutil.which(configured)
    if not candidate:
        raise ValueError("Configured C compiler is unavailable")
    compiler = Path(candidate).resolve(strict=True)
    # Accept versioned and target-prefixed drivers, including distribution alternatives.
    driver = r"(?:[A-Za-z0-9_+.]+-)*(?:cc|gcc|clang|icc|icx|tcc|c89|c99)(?:-[0-9]+(?:\.[0-9]+)*)?(?:\.exe)?"
    if not re.fullmatch(driver, compiler.name) or not compiler.is_file() or not os.access(compiler, os.X_OK):
        raise ValueError("Expected an executable C compiler driver")
    return compiler


def jdk_environment():
    # These variables would add JVM/compiler arguments outside the harness's fixed command.
    injected = {"JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"}
    return {key: value for key, value in os.environ.items() if key.upper() not in injected}


def require(condition, message):
    if not condition:
        raise ValueError(message)
