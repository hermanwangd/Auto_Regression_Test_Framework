#!/usr/bin/env python3
"""Explicit accepted-producer consumer proof. Does not infer source roots or invoke a SUT."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess

p=argparse.ArgumentParser()
for name in ["java-home","producer-jar","expected-jar-sha256","expected-schema-sha256","dependencies-lock","expected-lock-sha256","roots-binding","expected-roots-sha256","out","maven-lock"]:
    p.add_argument("--"+name,required=True)
a=p.parse_args();module=Path(__file__).resolve().parents[1];out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join([str(module/"target/classes"),str(module/"target/test-classes"),(module/"target/test-classpath.txt").read_text().strip()])
command=[str(Path(a.java_home)/"bin/java"),"-Xmx512m","-XX:MaxMetaspaceSize=256m","-cp",cp,"com.featuredeliveryintelligence.fdi.producttest.preparation.AcceptedD2ProducerClientProbe",str(Path(a.java_home)/"bin/java"),a.producer_jar,a.expected_jar_sha256,a.expected_schema_sha256,a.dependencies_lock,a.expected_lock_sha256,a.roots_binding,a.expected_roots_sha256,str(out)]
with Path(a.maven_lock).open("a") as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    result=subprocess.run(command,cwd=module,capture_output=True,text=True,timeout=240)
    if any(secret in result.stdout+result.stderr for secret in ["PRIVATE-CANARY","SYNTHETIC-TOKEN-CANARY"]):raise SystemExit("PRIVATE_TEST_TEXT_IN_PROBE_OUTPUT")
    (out/"probe.stdout.log").write_text(result.stdout);(out/"probe.stderr.log").write_text(result.stderr)
    (out/"probe-invocation.json").write_text(json.dumps({"classification":"PUBLIC_SYNTHETIC_SOURCE_CONTRACT_ONLY","exit_code":result.returncode,"independently_expected_producer_jar_sha256":a.expected_jar_sha256,"independently_expected_runtime_schema_sha256":a.expected_schema_sha256,"independently_expected_lock_sha256":a.expected_lock_sha256,"independently_expected_roots_sha256":a.expected_roots_sha256,"root_paths_exported":False,"live_cognee":"NOT_RUN","native_swarm":"NOT_RUN","SUT_execution":"NOT_RUN"},indent=2)+"\n")
    if result.returncode:raise SystemExit("ACCEPTED_PRODUCER_CONSUMER_FAILED: "+str(out/"probe.stderr.log"))
    print(result.stdout.strip())
