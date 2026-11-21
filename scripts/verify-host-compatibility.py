#!/usr/bin/env python3
"""Compile only the real Swarm entry/Dev204/mission-test dependency closure, without editing Swarm."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--swarm-root", type=Path, required=True, help="Read-only Git checkout, containing engcim-swarm/")
parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "/usr")))
args = parser.parse_args()
module = Path(__file__).resolve().parents[1]
source = args.swarm_root.resolve() / "engcim-swarm"
build = module / "target/host-compatibility"
build.mkdir(parents=True, exist_ok=True)
cp = (module / "target/test-classpath.txt").read_text().strip()
java = str(args.java_home / "bin/java")
javac = str(args.java_home / "bin/javac")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

pins = {
    "src/main/java/com/featuredeliveryintelligence/fdi/application/FdiApplication.java": "1e14310d39b622d7403e827403d2d2b6f1ac320ddceae51ec0be29854edac3c4",
    "src/main/java/com/featuredeliveryintelligence/fdi/application/Dev204Cli.java": "ff0669763d5070e834ffba893a2ebe681a20e868c85fc0b9b595256e468069b8",
    "src/main/java/com/featuredeliveryintelligence/fdi/orchestration/CogneeSearchClient.java": "506f163a5294f68881996d38f06de47ae6aa7ee2321954f77535db5c0cfc4878",
    "src/main/java/com/featuredeliveryintelligence/fdi/orchestration/SwarmKnowledgeGateway.java": "d2fb8408676d4de60d0f38528756558c0f669c61168b107ed1ea46aee23018d0",
    "src/test/java/com/featuredeliveryintelligence/fdi/orchestration/MissionFlowTests.java": "d24dd6c7b6151e18bc8516ee84ac6e58630b7b0b8b0b6b42336f00569fe07d97",
}
for name, digest in pins.items():
    assert sha(source / name) == digest, f"Inspected Swarm source pin changed: {name}"
base = subprocess.run(["git", "-C", str(args.swarm_root), "rev-parse", "HEAD"], text=True, capture_output=True, check=True).stdout.strip()
assert base == "fe80374f740ed3c604836431daca5c32e92cf844", "Swarm Git base changed"

entry = source / "src/main/java/com/featuredeliveryintelligence/fdi/application/FdiApplication.java"
overlay = build / "src/com/featuredeliveryintelligence/fdi/application/FdiApplication.java"
overlay.parent.mkdir(parents=True, exist_ok=True)
original = entry.read_text()
needle = "        if (Dev204Cli.handles(args)) return;"
assert original.count(needle) == 1
overlay.write_text(original.replace(needle,
    '        if (args.length > 0 && "product-test".equals(args[0]))\n'
    '            if (com.featuredeliveryintelligence.fdi.producttest.ProductTestCli.handles(args)) return;\n' + needle))
harness = build / "src/HostContractHarness.java"
harness.write_text('''
public final class HostContractHarness {
  public static void main(String[] args) throws Exception {
    Class<?> type = Class.forName("com.featuredeliveryintelligence.fdi.orchestration.MissionFlowTests");
    var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
    Object test = constructor.newInstance();
    var method = type.getDeclaredMethod("T12_resultTypesRemainDistinct"); method.setAccessible(true);
    method.invoke(test);
    System.out.println("Original MissionFlowTests.T12_resultTypesRemainDistinct: passed");
  }
}
''')
classes = build / "classes"
classes.mkdir(exist_ok=True)
compile_result = subprocess.run([javac, "-J-Xmx512m", "--release", "17", "-verbose", "-classpath", cp + os.pathsep + str(module / "target/classes"),
    "-sourcepath", str(source / "src/main/java"), "-d", str(classes), str(overlay), str(harness),
    str(source / "src/test/java/com/featuredeliveryintelligence/fdi/orchestration/MissionFlowTests.java")], text=True, capture_output=True, timeout=60)
(build / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
assert compile_result.returncode == 0, f"Host compilation failed; see {build / 'compile.log'}"
compiled_sources = set(re.findall(r"SimpleFileObject\[([^\]]+\.java)\]", compile_result.stderr))
for directory, relative in re.findall(r"DirectoryFileObject\[([^\]:]+):([^\]]+\.java)\]", compile_result.stderr):
    compiled_sources.add(str(Path(directory) / relative))
compiled_sources = sorted(compiled_sources)
source_digests = {str(Path(name).relative_to(source)): sha(Path(name)) for name in compiled_sources if Path(name).is_relative_to(source)}

def run(name, main, arguments, include_module, extra_jars=()):
    classpath = str(classes) + os.pathsep + cp
    if include_module:
        classpath += os.pathsep + str(module / "target/swarm-product-test-1.0.0-host.jar")
    for jar in extra_jars:
        classpath += os.pathsep + str(jar)
    result = subprocess.run([java, "-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-cp", classpath, main, *arguments],
                            text=True, capture_output=True, timeout=30, cwd=build)
    (build / f"{name}.stdout.log").write_text(result.stdout)
    (build / f"{name}.stderr.log").write_text(result.stderr)
    assert result.returncode == 0, f"Host check {name} failed; see {build}"
    return result.stdout

mission_output = run("mission-distinction", "HostContractHarness", [], False)
scenario = build / "scenario.json"
scenario.write_text(json.dumps({"release": "fdi-mvp-v0.4.7.1", "scenarios": [{"scenario_id": "LOCAL-COMPAT", "target": "SYSTEM_BEHAVIOR",
    "target_kind": "SYSTEM_BEHAVIOR", "agent_prompt": "Synthetic local compatibility packet; no agent or company service execution.",
    "rubric": {"pass_conditions": ["packet produced"], "forbidden_conditions": ["claim execution"]}}]}))
entry_name = "com.featuredeliveryintelligence.fdi.application.FdiApplication"
without = run("dev204-module-absent", entry_name, ["dev204-prepare", "--scenario-pack", str(scenario), "--output-dir", str(build / "packets-absent")], False)
provider_jar = module / "target/swarm-product-test-1.0.0-provider.jar"
with_module = run("dev204-module-present", entry_name, ["dev204-prepare", "--scenario-pack", str(scenario), "--output-dir", str(build / "packets-present")], True, [provider_jar])
assert json.loads(without) == json.loads(with_module)
assert json.loads(without)["claim_boundary"] == "PACKETS_PREPARED_NOT_EXECUTED"
assert json.loads(without)["packet_count"] == 2
run("spring-module-absent", entry_name, ["--spring.main.web-application-type=none", "--spring.main.banner-mode=off"], False)
module_harness = build / "src/HostModuleHarness.java"
module_harness.write_text('''
import com.featuredeliveryintelligence.fdi.producttest.*;
public final class HostModuleHarness {
  public static void main(String[] args) {
    try {
      var module = ProductTestingModules.resolve("product-test", "1", request -> null, Thread.currentThread().getContextClassLoader());
      String expected = args[0].equals("replacement") ? "ReplacementProvider" : "DefaultProductTestingModule";
      if (args[0].equals("absent") || args[0].equals("duplicate") || !expected.equals(module.getClass().getSimpleName()))
        throw new AssertionError("Unexpected module: " + module.getClass().getName());
    } catch (IllegalArgumentException error) {
      if (!(args[0].equals("absent") && error.getMessage().contains("not installed"))
          && !(args[0].equals("duplicate") && error.getMessage().contains("Duplicate"))) throw error;
    }
    System.out.println("Ordinary classpath provider " + args[0] + ": passed");
  }
}
''')
replacement = build / "replacement/ReplacementProvider.java"
replacement.parent.mkdir(exist_ok=True)
replacement.write_text('''
package replacement;
import com.featuredeliveryintelligence.fdi.producttest.*;
public final class ReplacementProvider implements ProductTestingModuleProvider, ProductTestingModule {
  private FrameworkExecutionPort framework;
  public String id() { return "product-test"; }
  public String version() { return "1"; }
  public ProductTestingModule create(FrameworkExecutionPort port) { framework = port; return this; }
  public ProductTestReceipt execute(ProductTestRequest request) { return new DefaultProductTestingModule(framework).execute(request); }
}
''')
replacement_classes = build / "replacement/classes"
replacement_classes.mkdir(exist_ok=True)
host_jar = module / "target/swarm-product-test-1.0.0-host.jar"
for sources, destination in [([module_harness], classes), ([replacement], replacement_classes)]:
    subprocess.run([javac, "-J-Xmx256m", "--release", "17", "-classpath", str(host_jar), "-d", str(destination), *map(str, sources)], check=True, capture_output=True, timeout=30)
replacement_jar = build / "replacement-provider.jar"
with zipfile.ZipFile(replacement_jar, "w") as jar:
    jar.write(replacement_classes / "replacement/ReplacementProvider.class", "replacement/ReplacementProvider.class")
    jar.writestr("META-INF/services/com.featuredeliveryintelligence.fdi.producttest.ProductTestingModuleProvider", "replacement.ReplacementProvider\n")
run("provider-absent", "HostModuleHarness", ["absent"], True)
run("provider-default", "HostModuleHarness", ["default"], True, [provider_jar])
run("provider-replacement", "HostModuleHarness", ["replacement"], True, [replacement_jar])
run("provider-duplicate", "HostModuleHarness", ["duplicate"], True, [provider_jar, replacement_jar])
for name, digest in {**pins, **source_digests}.items():
    assert sha(source / name) == digest, f"Original Swarm source changed during compatibility verification: {name}"
evidence = {"status": "passed", "claim_boundary": "LOCAL_SOURCE_OVERLAY_NOT_INSTALLED_SWARM_ACCEPTANCE",
            "swarm_base": base, "source_digests": source_digests, "entry_source_sha256": pins[next(iter(pins))],
            "overlay_sha256": sha(overlay), "compiled_source_count": len(source_digests),
            "checks": ["original MissionFlowTests.T12_resultTypesRemainDistinct", "Dev204 prepare with module absent",
                       "Dev204 prepare with module present", "Spring startup with module absent", "ordinary classpath provider absent",
                       "ordinary classpath default provider", "ordinary classpath independently compiled replacement", "ordinary classpath duplicate rejected"],
            "host_classes": str(classes), "dependency_classpath": str(module / "target/test-classpath.txt")}
(build / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
print(json.dumps(evidence, indent=2))
