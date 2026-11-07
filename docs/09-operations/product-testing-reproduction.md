# Reproduce the Swarm product-testing module

The implementation is published on `codex/sit-readiness` in [Auto_Regression_Test_Framework](https://github.com/hermanwangd/Auto_Regression_Test_Framework/tree/codex/sit-readiness). Pin the fetched commit before testing. The implementation commit is `3255c0e2`; later documentation commits do not change its Java sources. Do not use public `main` as a substitute for this candidate.

## Fresh environment

Use a JDK 17 containing both `java` and `javac`, Python 3, Git, and Maven 3.9 or the checked-in Maven wrapper. A runtime-only Java installation is insufficient. Initial builds require access to the official Maven repositories; use `-o` only after dependencies are cached. Keep jobs serial and bounded in memory.

```sh
git clone --branch codex/sit-readiness --single-branch \
  https://github.com/hermanwangd/Auto_Regression_Test_Framework.git
cd Auto_Regression_Test_Framework
git rev-parse HEAD
java -version
javac -version
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -B -ntp verify
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -B -ntp \
  -f integrations/swarm-product-test/pom.xml package dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt
```

The root and module are separate Maven builds. The local verified candidate passed 1,371 Surefire and 15 Failsafe framework tests plus 49 module tests, with no failures/errors/skips. These are prior local results, not a remote tester's results. Record the remote environment, commands, exits and fresh reports separately. Rebuilt JAR hashes may differ; calculate and retain the hashes of the actual artifacts used by that test run.

## Packaged bootstrap through the real module

This first check exercises the actual module and Framework with the committed **synthetic fake-provider** sample. It establishes bootstrap readiness, not public-product or native Swarm-agent acceptance. Run from the repository root after both builds above:

```sh
PRODUCT_TEST_REPO=$(pwd -P)
PRODUCT_TEST_FRAMEWORK_JAR="$PRODUCT_TEST_REPO/target/spec-driven-auto-regression-0.3.2.jar"
PRODUCT_TEST_FRAMEWORK_SHA256=$(python3 -c \
  'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' \
  "$PRODUCT_TEST_FRAMEWORK_JAR")
PRODUCT_TEST_HANDOFF_SHA256=$(cat samples/41-product-testing/reviewed_local/handoff/reviewed-tests.json.sha256)
python3 integrations/swarm-product-test/scripts/smoke.py \
  --framework-jar "$PRODUCT_TEST_FRAMEWORK_JAR" \
  --framework-sha256 "$PRODUCT_TEST_FRAMEWORK_SHA256" \
  --suite-dir "$PRODUCT_TEST_REPO/samples/41-product-testing/reviewed_local" \
  --handoff-sha256 "$PRODUCT_TEST_HANDOFF_SHA256" \
  --expect-verdict passed --name independent-bootstrap --java-home "$JAVA_HOME"
```

Set `JAVA_HOME` to that JDK's real location. The sample handoff pin is committed separately for the explicitly synthetic review. Real product approvals must be supplied independently by the reviewer; an untrusted request may not approve itself by rehashing its inputs. Retain `target/smoke/independent-bootstrap/receipt.json`, `smoke-evidence.json` and their linked canonical files.

## Actual Swarm host integration

The public module includes its typed API, default provider, executable module CLI, [host overlay](../../integrations/swarm-product-test/host-overlay/FdiApplication.patch) and [host compatibility harness](../../integrations/swarm-product-test/scripts/verify-host-compatibility.py). Obtain the actual Swarm checkout through an authorized source; do not replace it with another project called Swarm. The inspected host baseline is `fe80374f740ed3c604836431daca5c32e92cf844`, with `FdiApplication` as the entry point. This publication does not change that repository's access permissions.

```sh
python3 integrations/swarm-product-test/scripts/verify-host-compatibility.py \
  --swarm-root /absolute/authorized-swarm-checkout --java-home "$JAVA_HOME"
```

Replace the placeholder path with the authorized checkout, containing `engcim-swarm/`. The harness builds an isolated source overlay and leaves original source bytes intact. Then repeat the packaged smoke with `--via-host`. Missing Swarm access blocks the **actual Swarm host** claim, while the public module's own build/bootstrap can still run. Neither route alone proves a native Swarm agent autonomously found a repository, understood requirements or obtained reviewed approval; retain the actual agent's operations and review evidence for those claims.

## Independent public-project validation

Select a small, maintained, licensed public project and pin its commit. Use different business behavior from the prior local Petclinic pet-type flow. Review the plan and independent expected values before fixture writes. Capture source identity, readiness, owned data, exact cleanup, command exits, verdicts and canonical evidence. Keep artifact digests separate from assertions about semantic correctness.

The delivered native capability is `integration / regression / local`, `knowledge_mode: not_used`, and exactly one literal GET or HEAD to one `http://127.0.0.1:<port>` origin per invocation. Application writes and lifecycle belong to a separately isolated project harness. Broader methods, remote origins, dynamic headers/body, shared-state native policy and multiple HTTP operations are rejected. Use a project and oracle compatible with these actual capabilities, or report the unsupported capability explicitly. Framework assertions determine verdicts; expected failure and unavailable-dependency cases must remain non-PASS.

For native suites, use `env_profiles/` and `test_cases/`, and a literal alias-free reviewed copy of the existing REST contract. Use the existing `prepare-product-test-handoff` command and keep its approved pin independent of the request. See [module contracts and invocation](../../integrations/swarm-product-test/README.md) and [ISTQB gTAA / CT-GenAI mapping and gaps](product-testing-module.md).

Cognee retrieval candidates, governed selection/approval, native Swarm-agent behavior and product coverage are separate claims. Mark each NOT RUN when unavailable. Reference/model tests do not replace execution of this implementation. No company/shared SIT, deployment or release acceptance is implied by this reproduction procedure.
