# Swarm product testing module

This Java 17 module invokes the existing Test Framework and imports its validated canonical result and evidence. It does not run test steps, implement assertions, query Cognee, create `VerificationResult`, fabricate a Multica reference, or close a mission. Swarm analysis/review roles remain responsible for requirements, cross-repository impact, proposals, review and interpretation. Existing framework assertions decide test verdicts.

The module supports **component / regression / local** using the framework's fake provider, and **integration / regression / local** for one native loopback HTTP read. System and acceptance levels, broader integration workflows, performance and security types, and SIT/preproduction/live stages are unsupported and rejected before launch. An optional local readiness/shared-state fixture exercises framework policy mechanics with the same local tuple. It does not prove real SIT or distributed ownership.

`knowledge_mode: not_used` explicitly uses reviewed local artifacts without retrieval. `required` needs the existing governed Swarm selection receipt in the pinned handoff. Required mode cannot fall back to no knowledge. The native integration slice requires `not_used`; component tests retain both knowledge modes. The framework validates the existing receipt envelope and selected-source coverage; this module does not repeat governance or claim a deployed Cognee version. The local required-mode fixture is synthetic.

## Build and packaging

For a fresh clone, online builds, pinned smoke inputs and independent public-project validation, start with the [reproduction guide](../../docs/09-operations/product-testing-reproduction.md). A JDK with `javac` is required; a Java runtime alone cannot build the module.

Build offline using cached dependencies, with bounded memory:

```sh
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' mvn -o package \
  dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt
```

The build produces:

- `target/swarm-product-test-1.0.0-host.jar`: typed API, CLI composition, loader, default coordination implementation and framework transport; **no provider registration**.
- `target/swarm-product-test-1.0.0-provider.jar`: default ServiceLoader factory and its registration. Add/remove/replace this small artifact beside the host JAR. Resolve exactly `product-test` version `1`; absent, duplicate and incompatible providers fail closed.
- `target/swarm-product-test-1.0.0-cli.jar`: standalone executable convenience package containing the default provider and dependencies.
- `target/swarm-product-test-1.0.0.jar`: combined library convenience artifact. Do not put this combined artifact beside the modular host/provider pair or a replacement provider.

A replacement JAR implements `ProductTestingModuleProvider`, publishes its class name in `META-INF/services/com.featuredeliveryintelligence.fdi.producttest.ProductTestingModuleProvider`, and creates a `ProductTestingModule` using the supplied `FrameworkExecutionPort`. The factory is the only discovery hook; there is no general plugin platform. Deployment controls installed JARs.

## Invocation and trust boundary

`ProductTestRequest` contains reviewed artifact locators, exact repository revisions, requirement-to-test mapping, pinned workspace/project scope and invocation correlation. Mission, request and execution-revision values are correlation facts; they do not confer approval. Execution revision is an exact string, compatible with Swarm revisions such as `rev-17`. The request never contains an executable or command string. See the strict module-local [request](contracts/product-test-request.v1.schema.json) and [receipt](contracts/product-test-receipt.v1.schema.json) contracts.

Provision these values independently of request content in the protected host invocation configuration:

| Variable | Meaning |
| --- | --- |
| `SWARM_PRODUCT_TEST_FRAMEWORK_JAR` | Absolute path to the approved existing framework JAR |
| `SWARM_PRODUCT_TEST_FRAMEWORK_SHA256` | Independently pinned framework bytes |
| `SWARM_PRODUCT_TEST_HANDOFF_SHA256` | Independently approved reviewed-handoff bytes; never derive this from an untrusted request |
| `SWARM_PRODUCT_TEST_ARTIFACT_ROOT` | Canonical absolute directory containing approved suite inputs |
| `SWARM_PRODUCT_TEST_OUTPUT_ROOT` | Existing canonical absolute directory for fresh private run directories |
| `SWARM_PRODUCT_TEST_TIMEOUT_MS` | Optional total deadline across run, validation and report; default 30000, maximum 120000 |
| `SWARM_PRODUCT_TEST_MAX_OUTPUT_BYTES` | Optional combined stdout/stderr limit across all child commands; default 262144, maximum 1048576 |

The request's handoff pin must equal the independent binding pin. Editing the handoff and recomputing the request pin cannot replace the approved pin. Child `REGRESS_REVIEWED_HANDOFF_SHA256` comes only from the binding. The module checks pinned scope, repository revisions, requirement mapping and the exact tuple in digest-verified reviewed profile bytes before dispatch; the framework owns comprehensive reviewed-file coverage, governance, snapshot and provider policy validation.

The one transport uses argument arrays with configured Java, `-Xmx512m -XX:MaxMetaspaceSize=256m`, `-Djava.io.tmpdir=<fresh private directory>`, the approved JAR, and fixed framework commands:

```text
run --suite <absolute manifest> --profile <id> --root <fresh private directory>
validate-evidence --result <canonical result> --root <same private directory>
report --result <canonical result> --format json --root <same private directory>
```

Each child gets a cleared environment plus the independent handoff pin. No arbitrary environment, shell, credentials or request-provided argv is forwarded. The fresh directory has owner-only permissions. Results are accepted only from its canonical `target/regression/<suite>/<batch>/<random run>/result.json` path, with matching CLI/result/summary/index/handoff identities, fresh timestamps, contained regular files, no symlinks, matching scope/revisions, stable content digests and successful framework validation/reporting. Files are bounded to 4 MiB each and 64 MiB per run, with at most 4096 entries. Handoff JSON is read as a bounded regular file before hashing; the same bytes are parsed.

Local evidence must remain `framework_verification_only`, with explicit false release eligibility and matching declared/observed modes: simulated for component fake tests, real only for the finite native REST integration capability. PASS requires complete observed mode coverage; non-PASS may contain a matching observed subset when execution stopped before observation. Both mode maps must be present. A fake PASS relabeled as release evidence is rejected. `COMPLETED` with framework `failed`, `blocked` or `skipped` remains that verbatim verdict. Zero exit, malformed evidence and stale output cannot create PASS. Timeout and output-limit receipts are terminal for that invocation: no retry, late-file scan or receipt promotion occurs. Cancellation has one additional 500 ms shutdown grace: the owned child is signaled through its process handle, termination is checked, and pipe closure/reader joins are bounded. Diagnostics explicitly distinguish confirmed and unconfirmed termination or stream cleanup. Descendant termination is best effort on hosts that permit process enumeration. Daemon cleanup never collects or promotes late evidence.

## Native local integration

The native capability invokes the existing `rest_client.v0.3` provider for exactly one HTTP operation per invocation, using GET or HEAD and an exact reviewed `http://127.0.0.1:<port>` origin. Static paths and bounded timeouts are allowed; write methods, bodies, custom headers, DNS/off-loopback origins, redirects, readiness and shared-state policies are rejected. The reviewed response body is capped at 1 MiB before String accumulation, and one deadline covers headers plus complete body consumption with cancellation on timeout/interruption. Application fixture creation/update and exact-ID cleanup remain in the separately authorized disposable-project harness. The existing fake ownership store does not govern the public application. Canonical evidence binds the actual test tuple and native provider metadata and remains release-ineligible.

Use the conventional v0.3 `env_profiles/` and `test_cases/` directories for native fixtures. The source REST contract contains YAML aliases; materialize an equivalent literal JSON copy (still valid YAML) into the reviewed suite and hash that copy. Strict reviewed parsing continues to reject aliases. The canonical source contract remains unchanged.

## CLI and real Swarm composition

The standalone command is:

```sh
java -jar target/swarm-product-test-1.0.0-cli.jar product-test run --request /absolute/request.json
```

Apply the two-line [FdiApplication overlay](host-overlay/FdiApplication.patch) beside the existing Dev204 handler in an approved Swarm build. The original dirty Swarm checkout used for this work is never edited. Non-product commands retain Dev204/Spring behavior when the module JAR is absent. Product commands require the host API and one compatible provider. CLI exit is zero only for validated `passed`; receipts preserve other outcomes separately.

The read-only compatibility harness checks pinned actual source bytes, compiles only the real entry/Dev204/mission-test dependency closure into this module's target directory, executes the original `MissionFlowTests.T12_resultTypesRemainDistinct`, exercises Dev204 and Spring without the module, and verifies ordinary-classpath provider removal/replacement/duplicate rejection:

```sh
python3 scripts/verify-host-compatibility.py --swarm-root /absolute/engcim-swarm-checkout --java-home "$JAVA_HOME"
```

It records every compiled original source digest and checks that the originals remain unchanged. This is local source-overlay compatibility evidence, not installation into Swarm or company acceptance.

## Packaged smoke

Build the framework separately, then supply the approved artifact and handoff pins explicitly:

```sh
python3 scripts/smoke.py \
  --framework-jar /absolute/framework.jar --framework-sha256 <approved sha256> \
  --suite-dir /absolute/reviewed_local --handoff-sha256 <approved sha256> \
  --expect-verdict passed --name local-pass --java-home "$JAVA_HOME" --via-host
```

Repeat against the reviewed assertion-failure fixture with `--expect-verdict failed`; a readiness-blocked fixture uses `blocked`. `--via-host` enters the real compiled `FdiApplication` overlay. Omit it for the executable module CLI. `--provider-jar` can point to the independently compiled replacement from the compatibility harness. Receipts, process logs and linked digests are saved under `target/smoke/<name>/`. All demonstrations use the existing packaged framework and fake provider; they do not contact company systems or Cognee.

The frozen v2 local verification passed 42 module tests, eight source-overlay compatibility checks, seven packaged executions and two actual-entry provider rejection checks. The packaged cases cover local and required-knowledge PASS, a real assertion FAIL, readiness BLOCKED, replacement-provider PASS, and simultaneous same-suite PASS/FAIL with separate pinned handoffs and output directories. Missing and duplicate providers fail at the real `FdiApplication` entry before any framework output directory is created. All seven requests and receipts validate against the local JSON contracts. Compact receipts, commands and hashes are retained in the delivery's `evidence-v2/packaged/swarm-manifest.json`; the framework JAR SHA-256 for this verification is `fa97ffc806cb68c72b96086adb82d2c730911f5b10bece93839da282ff1bb7fd`.
