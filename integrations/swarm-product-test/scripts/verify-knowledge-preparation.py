#!/usr/bin/env python3
"""Actual fe803 host preparation contract harness. No source edits, publication or live Cognee claims."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

PINNED_HOST = {
    "src/main/java/com/featuredeliveryintelligence/fdi/application/FdiApplication.java": "1e14310d39b622d7403e827403d2d2b6f1ac320ddceae51ec0be29854edac3c4",
    "src/main/java/com/featuredeliveryintelligence/fdi/application/Dev204Cli.java": "ff0669763d5070e834ffba893a2ebe681a20e868c85fc0b9b595256e468069b8",
    "src/main/java/com/featuredeliveryintelligence/fdi/orchestration/CogneeSearchClient.java": "506f163a5294f68881996d38f06de47ae6aa7ee2321954f77535db5c0cfc4878",
    "src/main/java/com/featuredeliveryintelligence/fdi/orchestration/SwarmKnowledgeGateway.java": "d2fb8408676d4de60d0f38528756558c0f669c61168b107ed1ea46aee23018d0",
    "src/test/java/com/featuredeliveryintelligence/fdi/orchestration/MissionFlowTests.java": "d24dd6c7b6151e18bc8516ee84ac6e58630b7b0b8b0b6b42336f00569fe07d97",
}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def fresh_output_dir(module, requested=None):
    if requested is not None:
        build = requested.resolve()
        build.mkdir(parents=True, mode=0o700, exist_ok=False)
        build.chmod(0o700)
        return build
    parent = module / "target/knowledge-preparation"
    parent.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="invocation-", dir=parent))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--swarm-root", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, help="Fresh invocation output directory; reuse is rejected")
    parser.add_argument("--compile-only", action="store_true", help="Freeze source/class/classpath pins without host, HTTP or retrieval execution")
    parser.add_argument("--maven-lock", type=Path, required=True, help="Same lock used by Maven launcher; do not invoke Maven while held")
    args = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    host = args.swarm_root.resolve() / "engcim-swarm"
    with args.maven_lock.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for name, expected in PINNED_HOST.items():
            if sha(host / name) != expected:
                raise SystemExit("HOST_SOURCE_PIN_MISMATCH")
        build = fresh_output_dir(module, args.output_dir)
        cp = os.pathsep.join([str(module / "target/classes"), str(module / "target/test-classes"), (module / "target/test-classpath.txt").read_text().strip()])
        classes = build / "classes"
        classes.mkdir(exist_ok=True)
        sources = sorted((module / "src/host/java").rglob("*.java")) + sorted((module / "src/host-test/java").rglob("*.java"))
        javac = [str(args.java_home / "bin/javac"), "-J-Xmx512m", "--release", "17", "-verbose", "-classpath", cp, "-sourcepath", str(host / "src/main/java"), "-d", str(classes), *map(str,sources)]
        compiled = subprocess.run(javac, capture_output=True, text=True, timeout=120, cwd=module)
        (build / "compile.log").write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise SystemExit("HOST_CONTRACT_COMPILE_FAILED: " + str(build / "compile.log"))
        compiled_sources = set(re.findall(r"SimpleFileObject\[([^\]]+\.java)\]", compiled.stderr))
        for directory, relative in re.findall(r"DirectoryFileObject\[([^\]:]+):([^\]]+\.java)\]", compiled.stderr):
            compiled_sources.add(str(Path(directory) / relative))
        closure = {str(Path(name).relative_to(host)): sha(Path(name)) for name in compiled_sources if Path(name).is_relative_to(host)}
        if args.compile_only:
            cp_entries = [Path(item) for item in cp.split(os.pathsep)]
            classpath = []
            for entry in cp_entries:
                if entry.is_file():
                    classpath.append({"path": str(entry), "sha256": sha(entry), "kind": "jar"})
                elif entry.is_dir():
                    classpath.append({"path": str(entry), "kind": "class_directory", "classes": {str(p.relative_to(entry)): sha(p) for p in sorted(entry.rglob("*.class"))}})
                else:
                    raise SystemExit("CLASSPATH_ENTRY_MISSING")
            manifest = {"schema_version":"host-preparation-compile-only.v1", "status":"COMPILED_NOT_EXECUTED", "host_revision":"fe80374f740ed3c604836431daca5c32e92cf844", "host_five_pins":PINNED_HOST, "compiled_host_source_hashes":closure, "owned_source_hashes":{str(p.relative_to(module)):sha(p) for p in sources}, "compiled_classes":{str(p.relative_to(classes)):sha(p) for p in sorted(classes.rglob("*.class"))}, "classpath":classpath, "classes_directory":str(classes), "host_execution":"NOT_RUN", "HTTP_TEST_operations":0, "live_cognee":"NOT_RUN", "native_swarm_actor":"NOT_RUN"}
            for name, expected in {**PINNED_HOST, **closure}.items():
                if sha(host / name) != expected:
                    raise SystemExit("HOST_SOURCE_CHANGED_DURING_COMPILE")
            (build / "compile-only-manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
            print("COMPILED_NOT_EXECUTED")
            print("manifest_json: " + str(build / "compile-only-manifest.json"))
            return
        command = [str(args.java_home / "bin/java"), "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-cp", str(classes) + os.pathsep + cp, "com.featuredeliveryintelligence.fdi.producttest.preparation.HostKnowledgeContextPortTest", str(build / "artifacts")]
        checked = subprocess.run(command, capture_output=True, text=True, timeout=180, cwd=module)
        if any(secret in checked.stdout + checked.stderr for secret in ("PRIVATE-CANARY", "SYNTHETIC-TOKEN-CANARY")):
            raise SystemExit("PRIVATE_PREPARATION_CONTENT_IN_HARNESS_OUTPUT")
        (build / "contract.stdout.log").write_text(checked.stdout)
        (build / "contract.stderr.log").write_text(checked.stderr)
        for name, expected in {**PINNED_HOST, **closure}.items():
            if sha(host / name) != expected:
                raise SystemExit("HOST_SOURCE_CHANGED_DURING_CHECK")
        manifest = {"schema_version":"host-preparation-contract-evidence.v1", "classification":"synthetic_public_local_host_contract", "host_revision":"fe80374f740ed3c604836431daca5c32e92cf844", "host_five_pins":PINNED_HOST, "compiled_host_source_hashes":closure, "owned_source_hashes":{str(p.relative_to(module)):sha(p) for p in sources}, "exit_code":checked.returncode, "live_cognee":"NOT_RUN", "native_swarm_actor":"NOT_RUN", "d2_integration":"D2_BINDING_API_UNBOUND"}
        (build / "manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
        if checked.returncode:
            raise SystemExit("HOST_CONTRACT_FAILED: " + str(build / "contract.stderr.log"))
        print(checked.stdout.strip())
        print("manifest_json: " + str(build / "manifest.json"))

if __name__ == "__main__":
    main()
