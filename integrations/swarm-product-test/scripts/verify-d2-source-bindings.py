#!/usr/bin/env python3
"""Explicit accepted-producer consumer proof. Does not infer source roots or invoke a SUT."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess

from harness_tools import jdk_environment, jdk_tool


def digest(value):
    if not re.fullmatch("[a-f0-9]{64}", value):
        raise argparse.ArgumentTypeError("Expected a lowercase SHA-256 digest")
    return value


def main():
    parser = argparse.ArgumentParser()
    for option in ("java-home", "producer-jar", "dependencies-lock", "roots-binding", "out", "maven-lock"):
        parser.add_argument("--" + option, required=True, type=Path)
    for option in ("expected-jar-sha256", "expected-schema-sha256", "expected-lock-sha256", "expected-roots-sha256"):
        parser.add_argument("--" + option, required=True, type=digest)
    args = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join([str(module / "target/classes"), str(module / "target/test-classes"),
                          (module / "target/test-classpath.txt").read_text().strip()])
    java = jdk_tool(args.java_home, "java")
    with args.maven_lock.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # Keep caller paths literal so the D2 client checks its original no-symlink boundary.
        result = subprocess.run(["java", "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-cp", cp,
                                 "com.featuredeliveryintelligence.fdi.producttest.preparation.AcceptedD2ProducerClientProbe",
                                 str(java), str(args.producer_jar), args.expected_jar_sha256, args.expected_schema_sha256,
                                 str(args.dependencies_lock), args.expected_lock_sha256, str(args.roots_binding),
                                 args.expected_roots_sha256, str(out)], executable=java, env=jdk_environment(),
                                cwd=module, capture_output=True, text=True, timeout=240)
        if any(secret in result.stdout + result.stderr for secret in ("PRIVATE-CANARY", "SYNTHETIC-TOKEN-CANARY")):
            raise SystemExit("PRIVATE_TEST_TEXT_IN_PROBE_OUTPUT")
        (out / "probe.stdout.log").write_text(result.stdout)
        (out / "probe.stderr.log").write_text(result.stderr)
        record = {"classification": "PUBLIC_SYNTHETIC_SOURCE_CONTRACT_ONLY", "exit_code": result.returncode,
                  "independently_expected_producer_jar_sha256": args.expected_jar_sha256,
                  "independently_expected_runtime_schema_sha256": args.expected_schema_sha256,
                  "independently_expected_lock_sha256": args.expected_lock_sha256,
                  "independently_expected_roots_sha256": args.expected_roots_sha256,
                  "root_paths_exported": False, "live_cognee": "NOT_RUN", "native_swarm": "NOT_RUN", "SUT_execution": "NOT_RUN"}
        (out / "probe-invocation.json").write_text(json.dumps(record, indent=2) + "\n")
        if result.returncode:
            raise SystemExit("ACCEPTED_PRODUCER_CONSUMER_FAILED: " + str(out / "probe.stderr.log"))
        print(result.stdout.strip())


if __name__ == "__main__":
    main()
