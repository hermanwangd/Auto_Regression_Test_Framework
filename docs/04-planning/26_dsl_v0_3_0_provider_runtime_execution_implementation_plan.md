# DSL v0.3.0 Provider Runtime Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Keep Maven memory bounded with `MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m'`.

**Goal:** Promote DSL v0.3.0 from validation/dry-run plus golden fake-provider execution to real provider runtime execution across the existing framework-supported provider capabilities.

**Architecture:** v0.3 runtime execution must use a normalized execution plan as the only runtime input: `DSL target -> Suite Manifest target -> Provider Contract -> Env_Profile target -> Execution Plan -> Provider Runtime`. Existing v0.2 Provider Instance execution remains supported and isolated. v0.3 must not read Provider Instance files, infer Product/RP/RU topology, or dispatch based on sample folder names.

**Tech Stack:** Spring Boot 3.x, Java 17+, Maven, JUnit 5, AssertJ, SnakeYAML, Jackson, existing provider runtime classes under `src/main/java/com/specdriven/regression/provider/**`, and existing CLI command path in `RegressionCommand`.

---

## 1. Current Baseline

Implemented before this plan:

- v0.3 schemas exist.
- v0.3 suite/test/env validation exists.
- v0.3 dry-run target resolution exists.
- `sample_fake_provider.v0.3` golden runtime execution exists.
- `http_mock.v0.3` and `rest_client.v0.3` contract/dry-run sample exists.
- v0.2 executable provider capability samples remain the real provider runtime surface.

Missing before this plan:

- v0.3 real provider runtime execution for `http_mock`, `rest_client`, `jdbc`, `nats`, `kafka`, `ibm_mq`, `soap_mock`, `grpc_mock`, `grpc_client`, `artifact_compare`, `common_verify`, and `polling_observer`.
- v0.3 result/evidence/report hardening for real provider runtime outputs.
- v0.3 executable sample corpus beyond the fake-provider golden sample.

## 2. Non-Goals

- Do not remove v0.2 Provider Instance support.
- Do not rename or delete the v0.2 `wiremock_http_mock` compatibility runtime.
- Do not add new providers.
- Do not start Kafka, IBM MQ, Oracle, DB2, NATS, or external services from the framework unless an existing provider runtime already supports that mode.
- Do not add Product/RP/RU topology interpretation.
- Do not add Phase 2 Agent Skill behavior.
- Do not add release governance, waiver workflow, dashboard, Allure, or ReportPortal.
- Do not make pi-run-specific logic part of the framework.

## 3. Release Scope

### P0 For v0.3.0 Runtime Execution

These must run through v0.3 DSL and produce reportable result JSON:

- `sample_fake_provider.v0.3`: already implemented; keep as smoke baseline.
- `http_mock.v0.3 + rest_client.v0.3`: framework-owned WireMock-backed HTTP mock plus REST client.
- `jdbc.v0.3`: local H2 Oracle/DB2-compatible modes plus external `env://JDBC_CONNECTION` validation path.
- `nats.v0.3`: existing NATS client provider runtime with `env://NATS_CONNECTION`.
- `artifact_compare.v0.3`, `common_verify.v0.3`, `polling_observer.v0.3`: JSON/schema/file diff and shared polling.
- `kafka.v0.3`, `ibm_mq.v0.3`: existing client providers in mock/native modes, no server provisioning.
- `soap_mock.v0.3 + rest_client.v0.3`: WireMock-backed SOAP/XML mock with HTTP client stimulus.
- `grpc_mock.v0.3 + grpc_client.v0.3`: WireMock gRPC extension mock with unary gRPC client stimulus.

This plan is a release train, not a single PR. Do not release v0.3.0 until every P0 provider slice has an executable sample, result JSON, evidence validation, report verification, and usage-kit command coverage. If a later decision cuts scope, create a separate version plan and do not call the reduced scope v0.3.0 complete.

### Framework Verification Boundary

v0.3 verification has two execution paths:

- `verify[].type: assertion`: framework-owned verifier engine. It handles `equals`, `json_match`, `schema_match`, and `file_diff` without a provider target.
- `verify[].type: provider_check`: provider runtime path. It uses `target`, `op`, and `with`, and every operation/input/output must be validated by the target Provider Contract.

Do not route plain assertion checks through provider runtime adapters. Use provider adapters only for provider-owned observations such as request journal checks, DB record polling, message observation, or mock server verification.

### Compatibility Gate

These v0.2 samples must remain green after each implementation slice:

- `samples/00-getting-started/golden_e2e/`
- `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/`
- `samples/20-provider-capability-p0/`
- `samples/30-cross-provider-groups/mock_server_cross_verify/`
- `samples/90-compatibility/dummy_rest/`

## 4. Target v0.3 Sample Layout

Create or update this checked-in sample tree:

```text
samples/v0_3_dsl/
  README.md
  golden/
  http_mock_rest_client/
  data/
    jdbc/
  messaging/
    nats/
    kafka/
    ibm_mq/
    kafka_ibm_mq_mixed/
  rpc/
    soap_mock_rest_client/
    grpc_mock_grpc_client/
  verification/
    artifact_compare/
    common_verify/
    polling_observer/
  multi_test/
  negative/
    target_resolution/
    bindings/
    operations/
    refs/
    legacy_fields/
    secrets/
    cleanup/
```

Rules:

- v0.3 test cases must use `dsl_version: v0.3`.
- v0.3 suites must use `manifest_version: v0.3`.
- v0.3 suites must not contain Provider Instance refs.
- v0.3 test cases must not contain `provider_id`, `provider_instance`, `parameters`, `bind_as`, `data_binding`, `datasets`, `fixtures`, or `expected_results`.
- v0.3 executable samples must use provider contract IDs such as `http_mock.v0.3`, `rest_client.v0.3`, and `jdbc.v0.3`.

Provider Contract resolution rules:

- v0.3 suite targets must resolve Provider Contracts by exact `provider_contract` id, not by provider type fallback.
- Built-in Provider Contracts must be resolved through the existing framework contract catalog, not by assuming the process `cwd`.
- `provider_contract_resolution.mode` may use `framework_builtin`, `suite_override`, or `snapshot`, but every mode must block before execution on missing, duplicate, or non-v0.3 contract ids.
- The suite manifest must not contain provider contract file paths. Suite targets name stable contract ids only.
- Each checked-in v0.3 contract file must be included in the bundled contract catalog/index used by jar execution and usage-kit verification.

## 5. Implementation Tasks

### Task 1: Add v0.3 Runtime Execution Red Tests

**Files:**

- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`
- Create: `src/test/java/com/specdriven/regression/cli/DslV03ProviderRuntimeExecutionCommandTest.java`
- Create: `src/test/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilderTest.java`

- [x] **Step 1: Add failing CLI runtime tests**

Add tests that prove the current gap. The first test must fail with `unsupported_suite_runtime` before implementation:

```java
@Test
void runV03HttpMockRestClientExecutesProviderRuntime() {
    CommandResult run = execute(
            "run",
            "--suite", "samples/v0_3_dsl/http_mock_rest_client/suite_manifest.yaml",
            "--profile", "local_v03");

    assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
    assertThat(run.stdout())
            .contains("run_status: passed")
            .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
            .contains("provider_runtime_executed: true")
            .contains("provider_type: http_mock")
            .contains("provider_type: rest_client");

    Path resultJson = extractPath(run.stdout(), "result_json");
    assertThat(Files.readString(resultJson))
            .contains("\"dsl_version\": \"v0.3\"")
            .contains("\"provider_contract\": \"http_mock.v0.3\"")
            .contains("\"provider_contract\": \"rest_client.v0.3\"")
            .doesNotContain("provider_instance");
}
```

- [x] **Step 2: Add failing plan-builder tests**

Add a test asserting that v0.3 execution plan records contain target, provider contract, provider type, runtime mode, operation, and safe input refs:

```java
@Test
void buildsExecutionPlanForV03ProtocolTargets() {
    V03ExecutionPlan plan = builder.build(
            Path.of("samples/v0_3_dsl/http_mock_rest_client/suite_manifest.yaml"),
            "local_v03");

    assertThat(plan.suiteId()).isEqualTo("HTTP-MOCK-REST-CLIENT-v0.3");
    assertThat(plan.steps()).extracting(V03ExecutionStep::providerContract)
            .contains("http_mock.v0.3", "rest_client.v0.3");
    assertThat(plan.steps()).allSatisfy(step -> assertThat(step.providerInstanceRef()).isBlank());
}
```

- [x] **Step 3: Run red tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03ProviderRuntimeExecutionCommandTest,V03ExecutionPlanBuilderTest test
```

Expected before implementation: tests fail because `V03ExecutionPlanBuilder` does not exist or v0.3 provider runtime dispatch is blocked.

- [x] **Step 4: Keep red tests local until the first green runtime slice**

Do not push or commit a permanently failing release branch state. Commit these tests with Task 5 after the v0.3 execution plan, contract registry, and runtime dispatch pass together.

### Task 2: Harden v0.3 Provider Contract Catalog Resolution

**Files:**

- Modify: `src/main/java/com/specdriven/regression/contract/FrameworkProviderContractCatalog.java`
- Modify: `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`
- Create or modify: `docs/02-architecture/contracts/provider-contracts/provider-contracts.index`
- Modify: `src/test/java/com/specdriven/regression/contract/FrameworkProviderContractCatalogTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`

- [x] **Step 1: Add contract catalog tests**

Add tests proving:

- bundled contract lookup does not depend on process `cwd`,
- exact id `http_mock.v0.3` resolves to `provider_type: http_mock`,
- exact id `rest_client.v0.3` resolves to `provider_type: rest_client`,
- a v0.3 suite target cannot resolve by provider type fallback,
- duplicate `provider_contract` ids fail before runtime execution,
- missing `provider_contract` ids fail before runtime execution.

Example assertions:

```java
CommandResult result = execute(
        "validate",
        "--suite", "samples/v0_3_dsl/http_mock_rest_client/suite_manifest.yaml",
        "--profile", "local_v03");

assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
assertThat(result.stdout())
        .contains("provider_contracts_used:")
        .contains("http_mock.v0.3")
        .contains("rest_client.v0.3")
        .doesNotContain("provider_instance");
```

- [x] **Step 2: Update bundled contract index**

Ensure `provider-contracts.index` includes every built-in v0.3 contract file as it is added. The index must include at least:

```text
http_mock_v0_3.yaml
rest_client_v0_3.yaml
sample_fake_provider_v0_3.yaml
```

Later provider tasks must append their own v0.3 contract files to the same index in the same commit as the contract file.

- [x] **Step 3: Enforce exact v0.3 contract id resolution**

In `ContractBaselineService`, v0.3 target validation must call exact-id resolution:

```java
Map<String, Object> contract = providerContract(graph, requestedProviderContractId);
if (contract.isEmpty() || !isExplicitV03ProviderContract(requestedProviderContractId, contract)) {
    findings.add(finding(
            suitePath,
            "targets." + targetName + ".provider_contract",
            "missing_provider_contract",
            "Use a built-in v0.3 Provider Contract id or configure provider_contract_resolution."));
}
```

Do not fall back from `jdbc.v0.3` to provider type `jdbc` for v0.3 runtime execution.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=FrameworkProviderContractCatalogTest,DslV03CommandTest test
```

Expected: v0.3 built-in contract ids resolve consistently from repo and packaged catalog paths.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/FrameworkProviderContractCatalog.java \
        src/main/java/com/specdriven/regression/contract/ContractBaselineService.java \
        docs/02-architecture/contracts/provider-contracts/provider-contracts.index \
        src/test/java/com/specdriven/regression/contract/FrameworkProviderContractCatalogTest.java \
        src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java
git commit -m "feat: harden v0.3 provider contract catalog resolution"
```

### Task 3: Introduce v0.3 Execution Plan Model

**Files:**

- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionPlan.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionStep.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ResolvedTarget.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilder.java`
- Modify: `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`

- [x] **Step 1: Add immutable plan records**

```java
public record V03ResolvedTarget(
        String target,
        String providerContract,
        String providerType,
        String profile,
        String runtimeMode,
        Map<String, Object> bindings) {
}

public record V03ExecutionStep(
        String testCaseId,
        String phase,
        String id,
        String target,
        String providerContract,
        String providerType,
        String profile,
        String runtimeMode,
        String operation,
        Map<String, Object> inputs,
        String providerInstanceRef) {
}

public record V03ExecutionPlan(
        String suiteId,
        String profile,
        List<V03ResolvedTarget> targets,
        List<V03ExecutionStep> steps) {
}
```

- [x] **Step 2: Extract plan building from existing validation graph**

Add a public method to `ContractBaselineService`:

```java
public V03ExecutionPlan buildV03ExecutionPlan(Path suiteManifest, String profile) {
    ValidationResult validation = validateSuite(suiteManifest);
    if (!validation.valid()) {
        throw new IllegalArgumentException("v0.3 suite is not valid: " + validation.findings());
    }
    return new V03ExecutionPlanBuilder(this).build(suiteManifest, profile);
}
```

If direct reuse of private `ContractGraph` is too large for this slice, keep `V03ExecutionPlanBuilder` package-private and move only the minimal graph-loading helpers needed for v0.3.

- [x] **Step 3: Ensure plan has no Provider Instance input**

The builder must set `providerInstanceRef` to `""` for every v0.3 step. Add a test that scans the plan and fails on nonblank provider instance refs.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ExecutionPlanBuilderTest,DslV03CommandTest test
```

Expected after implementation: plan builder tests pass and existing v0.3 validate/dry-run tests remain green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/v03 \
        src/main/java/com/specdriven/regression/contract/ContractBaselineService.java \
        src/test/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilderTest.java
git commit -m "feat: add v0.3 execution plan model"
```

### Task 4: Add v0.3 Provider Runtime Adapter Boundary

**Files:**

- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ProviderRuntimeAdapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ProviderRuntimeRegistry.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionContext.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03StepResult.java`
- Create: `src/test/java/com/specdriven/regression/contract/v03/V03ProviderRuntimeRegistryTest.java`

- [x] **Step 1: Define adapter interface**

```java
public interface V03ProviderRuntimeAdapter {
    String providerType();

    boolean supports(String providerContract, String operation);

    V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context);
}
```

- [x] **Step 2: Define registry**

```java
public final class V03ProviderRuntimeRegistry {
    private final Map<String, V03ProviderRuntimeAdapter> adapters;

    public V03ProviderRuntimeAdapter resolve(String providerType) {
        V03ProviderRuntimeAdapter adapter = adapters.get(providerType);
        if (adapter == null) {
            throw new IllegalArgumentException("No v0.3 runtime adapter for provider_type `" + providerType + "`.");
        }
        return adapter;
    }
}
```

- [x] **Step 3: Add registry tests**

Test resolving known adapters and failing on unknown provider type with owner-actionable error text.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ProviderRuntimeRegistryTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/v03 \
        src/test/java/com/specdriven/regression/contract/v03/V03ProviderRuntimeRegistryTest.java
git commit -m "feat: add v0.3 provider runtime adapter registry"
```

### Task 5: Add v0.3 Runtime Execution Service and CLI Dispatch

**Files:**

- Create: `src/main/java/com/specdriven/regression/contract/v03/V03RuntimeExecutionService.java`
- Modify: `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`
- Modify: `src/main/java/com/specdriven/regression/cli/RegressionCommand.java` result wrapper if it cannot represent v0.3 target/provider contract fields.
- Test: `src/test/java/com/specdriven/regression/cli/DslV03ProviderRuntimeExecutionCommandTest.java`

- [x] **Step 1: Add runtime execution service**

The service must:

- validate the suite,
- build the v0.3 execution plan,
- execute setup, execute, verify, cleanup in order,
- always attempt cleanup after setup starts,
- write result JSON and evidence,
- return a CLI summary compatible with existing `regress run`.

- [x] **Step 2: Route v0.3 suites from CLI**

In `RegressionCommand.dispatchSuiteRuntimeInternal`, detect `manifest_version: v0.3` or any selected test case with `dsl_version: v0.3`. Route to `V03RuntimeExecutionService` instead of folder-name based provider capability services.

- [x] **Step 3: Preserve dry-run behavior**

`run --dry-run` must keep using `ContractBaselineService.dryRun` and must not instantiate or invoke provider runtime adapters.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03CommandTest,DslV03ProviderRuntimeExecutionCommandTest test
```

Expected after this task: unsupported v0.3 provider types fail through the v0.3 service with `unsupported_suite_runtime`, not through v0.2 folder-based dispatch.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/v03 \
        src/main/java/com/specdriven/regression/cli/RegressionCommand.java \
        src/test/java/com/specdriven/regression/cli/DslV03ProviderRuntimeExecutionCommandTest.java
git commit -m "feat: route v0.3 suites through runtime execution service"
```

### Task 6: Implement `http_mock.v0.3 + rest_client.v0.3` Runtime Slice

**Files:**

- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/HttpMockV03Adapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/RestClientV03Adapter.java`
- Modify: `src/main/java/com/specdriven/regression/provider/wiremock/WireMockHttpMockProviderRuntime.java` only if it needs execution-plan input support.
- Modify: `src/main/java/com/specdriven/regression/provider/http/RestClientProviderRuntime.java` only if it needs execution-plan input support.
- Modify: `samples/v0_3_dsl/http_mock_rest_client/`
- Test: `src/test/java/com/specdriven/regression/cli/DslV03ProviderRuntimeExecutionCommandTest.java`

- [x] **Step 1: Map v0.3 inputs to existing HTTP mock runtime**

Map:

| v0.3 contract | Existing runtime operation |
| --- | --- |
| `http_mock.v0.3` `load_stubs` | WireMock load stubs / start mock |
| `http_mock.v0.3` `verify_requests` | WireMock request journal verification |
| `http_mock.v0.3` `reset_mock` | WireMock cleanup/reset |
| `rest_client.v0.3` `http_request` | REST client request runtime |

- [x] **Step 2: Support generated binding flow**

`payment_api.bindings.base_url: generated://payment_mock/base_url` must resolve from the preceding `payment_mock` setup output. The execution context must store generated outputs by target name.

- [x] **Step 3: Run sample**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03ProviderRuntimeExecutionCommandTest#runV03HttpMockRestClientExecutesProviderRuntime test
```

Expected: pass. Result JSON includes both `http_mock.v0.3` and `rest_client.v0.3`.

- [x] **Step 4: Verify report**

Add test:

```java
CommandResult report = execute("report", "--result", resultJson.toString());
assertThat(report.exit()).isZero();
assertThat(report.stdout())
        .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
        .contains("status: passed")
        .contains("provider_results_count: 2");
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/v03 \
        src/main/java/com/specdriven/regression/provider/wiremock/WireMockHttpMockProviderRuntime.java \
        src/main/java/com/specdriven/regression/provider/http/RestClientProviderRuntime.java \
        samples/v0_3_dsl/http_mock_rest_client \
        src/test/java/com/specdriven/regression/cli/DslV03ProviderRuntimeExecutionCommandTest.java
git commit -m "feat: execute v0.3 http mock and rest client sample"
```

### Task 7: Implement v0.3 Result, Evidence, and Cleanup Model

**Files:**

- Modify: `src/main/java/com/specdriven/regression/contract/ProviderCapabilityResultWriter.java`
- Modify: `src/main/java/com/specdriven/regression/contract/ResultContractValidator.java`
- Modify: `src/main/java/com/specdriven/regression/evidence/EvidenceHardeningService.java`
- Create: `src/test/java/com/specdriven/regression/contract/v03/V03ResultEvidenceContractTest.java`

- [x] **Step 1: Add v0.3 result fields**

Every v0.3 provider result must include:

- `target`
- `provider_contract`
- `provider_type`
- `profile`
- `runtime_mode`
- `operation`
- `status`
- `evidence_refs`

It must not require Provider Instance refs.

- [x] **Step 2: Preserve original and cleanup failures**

If execute fails and cleanup fails, result JSON must include primary failure plus cleanup failure evidence. Cleanup failure must not hide original failure.

- [x] **Step 3: Add evidence validation tests**

Test valid evidence refs, missing evidence file, unknown evidence ref, raw secret leak, and cleanup failure evidence.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ResultEvidenceContractTest,EvidenceHardeningCommandTest,ReportAndEvidenceCommandTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/specdriven/regression/contract/ProviderCapabilityResultWriter.java \
        src/main/java/com/specdriven/regression/contract/ResultContractValidator.java \
        src/main/java/com/specdriven/regression/evidence/EvidenceHardeningService.java \
        src/test/java/com/specdriven/regression/contract/v03/V03ResultEvidenceContractTest.java
git commit -m "feat: harden v0.3 result and evidence contract"
```

### Task 8: Implement v0.3 JDBC Runtime Slice

**Files:**

- Create: `docs/02-architecture/contracts/provider-contracts/jdbc_v0_3.yaml`
- Create: `samples/v0_3_dsl/data/jdbc/`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/JdbcV03Adapter.java`
- Modify: `src/main/java/com/specdriven/regression/provider/jdbc/JdbcProviderRuntime.java` only if a small input mapping hook is required.
- Test: `src/test/java/com/specdriven/regression/cli/DslV03JdbcRuntimeCommandTest.java`

- [x] **Step 1: Add `jdbc.v0.3` contract**

Declare operations:

- `db_seed`
- `db_query`
- `db_record_exists`
- `db_cleanup`

Declare binding keys:

- `dialect`
- optional `connection.secret_ref`

- [x] **Step 2: Add v0.3 JDBC sample**

Sample must include local H2-compatible mode and external profile validation for `env://JDBC_CONNECTION`.

- [x] **Step 3: Implement adapter**

Map v0.3 operations to existing `JdbcProviderRuntime`.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03JdbcRuntimeCommandTest,JdbcProviderCapabilityCommandTest,JdbcExternalEnvSecretRefTest test
```

- [ ] **Step 5: Commit**

```bash
git add docs/02-architecture/contracts/provider-contracts/jdbc_v0_3.yaml \
        samples/v0_3_dsl/data/jdbc \
        src/main/java/com/specdriven/regression/contract/v03/adapter/JdbcV03Adapter.java \
        src/test/java/com/specdriven/regression/cli/DslV03JdbcRuntimeCommandTest.java
git commit -m "feat: execute v0.3 jdbc runtime sample"
```

### Task 9: Implement v0.3 Framework Verification and Polling Runtime Slice

**Files:**

- Create: `docs/02-architecture/contracts/provider-contracts/artifact_compare_v0_3.yaml`
- Create: `docs/02-architecture/contracts/provider-contracts/polling_observer_v0_3.yaml`
- Create: `samples/v0_3_dsl/verification/artifact_compare/`
- Create: `samples/v0_3_dsl/verification/common_verify/`
- Create: `samples/v0_3_dsl/verification/polling_observer/`
- Modify: `src/main/java/com/specdriven/regression/assertion/AssertionEngine.java`
- Modify: `src/main/java/com/specdriven/regression/contract/v03/V03RuntimeExecutionService.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/PollingObserverV03Adapter.java`
- Test: `src/test/java/com/specdriven/regression/cli/DslV03VerificationRuntimeCommandTest.java`

- [x] **Step 1: Add framework verifier samples**

`artifact_compare_v0_3.yaml` and common assertion definitions are framework verifier contracts, not provider runtime dispatch contracts. They may remain in the contract catalog for validation compatibility, but they must not require a Provider Instance and must not create a generic provider adapter for assertion-only checks.

Assertion samples must cover:

- `json_match`
- `schema_match`
- `file_diff`

These checks use `verify[].type: assertion` and must not specify a provider target. They must validate `artifact://...` refs, `step://...` refs, `ignore_paths`, `normalize`, and `ignore_order` through the framework assertion/verifier engine.

- [x] **Step 2: Add provider-check observation contract**

`polling_observer_v0_3.yaml` covers provider-owned observation operations only:

- `observe_condition`
- `event_published`
- `db_record_exists`

These operations require `verify[].type: provider_check`, `target`, `op`, and `with`. Operation names, allowed inputs, output refs, and failure codes must be validated by the target Provider Contract before execution.

- [x] **Step 3: Route assertion and provider_check separately**

`V03RuntimeExecutionService` must use two paths:

```java
if ("assertion".equals(verifyType)) {
    return assertionEngine.evaluate(assertionRequest);
}
if ("provider_check".equals(verifyType)) {
    return providerRuntimeRegistry.resolve(providerType).execute(step, context);
}
throw validationError("unsupported_verify_type", fieldPath);
```

Do not create a generic provider adapter for plain `json_match`, `schema_match`, or `file_diff` assertions.

- [x] **Step 4: Preserve polling rules**

Polling must retry only observation-style checks. It must not retry mutating setup/execute/provider actions.

- [x] **Step 5: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03VerificationRuntimeCommandTest,CommonVerifyCapabilityCommandTest test
```

- [ ] **Step 6: Commit**

```bash
git add docs/02-architecture/contracts/provider-contracts/*_v0_3.yaml \
        samples/v0_3_dsl/verification \
        src/main/java/com/specdriven/regression/assertion/AssertionEngine.java \
        src/main/java/com/specdriven/regression/contract/v03/V03RuntimeExecutionService.java \
        src/main/java/com/specdriven/regression/contract/v03/adapter/PollingObserverV03Adapter.java \
        src/test/java/com/specdriven/regression/cli/DslV03VerificationRuntimeCommandTest.java
git commit -m "feat: execute v0.3 framework verification samples"
```

### Task 10: Implement v0.3 Messaging Runtime Slice

**Files:**

- Create: `docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml`
- Create: `docs/02-architecture/contracts/provider-contracts/kafka_v0_3.yaml`
- Create: `docs/02-architecture/contracts/provider-contracts/ibm_mq_v0_3.yaml`
- Create: `samples/v0_3_dsl/messaging/nats/`
- Create: `samples/v0_3_dsl/messaging/kafka/`
- Create: `samples/v0_3_dsl/messaging/ibm_mq/`
- Create: `samples/v0_3_dsl/messaging/kafka_ibm_mq_mixed/`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/NatsV03Adapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/KafkaV03Adapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/IbmMqV03Adapter.java`
- Test: `src/test/java/com/specdriven/regression/cli/DslV03MessagingRuntimeCommandTest.java`

- [x] **Step 1: Add contracts**

Use existing provider operation names:

- NATS: `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`
- Kafka: `kafka_publish`, `kafka_observe`, `kafka_payload_match`
- IBM MQ: `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match`

- [x] **Step 2: Add mock/native sample profiles**

Samples must validate with checked-in mock/local profile and external profile refs. External native profiles must not block CI when env vars are absent unless the suite is explicitly selected.

- [x] **Step 3: Implement adapters**

Adapters must call existing `NatsProviderRuntime`, `KafkaProviderRuntime`, and `IbmMqProviderRuntime`. They must not start broker servers.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03MessagingRuntimeCommandTest,NatsProviderCapabilityCommandTest,MessagingClientProviderCapabilityCommandTest test
```

- [ ] **Step 5: Commit**

```bash
git add docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml \
        docs/02-architecture/contracts/provider-contracts/kafka_v0_3.yaml \
        docs/02-architecture/contracts/provider-contracts/ibm_mq_v0_3.yaml \
        samples/v0_3_dsl/messaging \
        src/main/java/com/specdriven/regression/contract/v03/adapter/*V03Adapter.java \
        src/test/java/com/specdriven/regression/cli/DslV03MessagingRuntimeCommandTest.java
git commit -m "feat: execute v0.3 messaging provider samples"
```

### Task 11: Implement v0.3 SOAP and gRPC Mock Runtime Slice

**Files:**

- Create: `docs/02-architecture/contracts/provider-contracts/soap_mock_v0_3.yaml`
- Create: `docs/02-architecture/contracts/provider-contracts/grpc_mock_v0_3.yaml`
- Create: `docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml`
- Create: `samples/v0_3_dsl/rpc/soap_mock_rest_client/`
- Create: `samples/v0_3_dsl/rpc/grpc_mock_grpc_client/`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/SoapMockV03Adapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/GrpcMockV03Adapter.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/adapter/GrpcClientV03Adapter.java`
- Test: `src/test/java/com/specdriven/regression/cli/DslV03RpcRuntimeCommandTest.java`

- [x] **Step 1: Add contracts**

SOAP:

- `start_soap_mock`
- `load_soap_stub`
- `soap_request_received`
- `reset_mock`

gRPC:

- `start_grpc_mock`
- `load_grpc_stub`
- `grpc_request_received`
- `reset_mock`
- `unary_call`

- [x] **Step 2: Add samples**

SOAP sample uses `soap_mock.v0.3` plus `rest_client.v0.3`. gRPC sample uses `grpc_mock.v0.3` plus `grpc_client.v0.3`.

- [x] **Step 3: Implement adapters**

Reuse `SoapMockProviderRuntime`, `GrpcMockProviderRuntime`, and `GrpcClientProviderRuntime`. Keep gRPC streaming unsupported.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03RpcRuntimeCommandTest,SoapMockCapabilityCommandTest,GrpcMockCapabilityCommandTest test
```

- [ ] **Step 5: Commit**

```bash
git add docs/02-architecture/contracts/provider-contracts/soap_mock_v0_3.yaml \
        docs/02-architecture/contracts/provider-contracts/grpc_mock_v0_3.yaml \
        docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml \
        samples/v0_3_dsl/rpc \
        src/main/java/com/specdriven/regression/contract/v03/adapter/*V03Adapter.java \
        src/test/java/com/specdriven/regression/cli/DslV03RpcRuntimeCommandTest.java
git commit -m "feat: execute v0.3 soap and grpc mock samples"
```

### Task 12: Add v0.3 Multi-Test and Negative Sample Corpus

**Files:**

- Create: `samples/v0_3_dsl/multi_test/`
- Create: `samples/v0_3_dsl/negative/**`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`
- Create: `src/test/java/com/specdriven/regression/cli/DslV03NegativeSampleCommandTest.java`

- [x] **Step 1: Add multi-test sample**

One suite must contain at least two test cases sharing the same Env_Profile and target map.

- [x] **Step 2: Add negative samples**

Create at least one checked-in fixture for each category:

- unknown target
- missing Env_Profile target
- missing Provider Contract
- missing required binding
- unknown binding key
- invalid runtime mode
- unsupported operation
- unsupported input
- invalid artifact ref
- symlink escape
- forward `step://` ref
- each prohibited legacy field
- raw secret in DSL
- raw secret in Env_Profile
- cleanup failure preservation

Progress note: checked-in negative samples now cover unknown target, missing Env_Profile
target, missing Provider Contract, missing required binding, unknown binding key, invalid
runtime mode, unsupported operation, unsupported input, invalid artifact ref, symlink escape,
forward step ref, prohibited legacy `data_binding`, raw secret in DSL, raw secret in
Env_Profile, and cleanup failure preservation.

- [x] **Step 3: Add tests**

Each negative sample must assert the exact reason code and owner-actionable field path.

- [x] **Step 4: Run tests**

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03NegativeSampleCommandTest,DslV03CommandTest test
```

- [ ] **Step 5: Commit**

```bash
git add samples/v0_3_dsl/multi_test samples/v0_3_dsl/negative \
        src/test/java/com/specdriven/regression/cli/DslV03NegativeSampleCommandTest.java \
        src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java
git commit -m "test: add v0.3 multi-test and negative sample corpus"
```

### Task 13: Update Usage Kit, Docs, and Release Gates

**Files:**

- Modify: `samples/v0_3_dsl/README.md`
- Modify: `docs/09-operations/test_framework_user_guide.md`
- Modify: `docs/09-operations/provider_support_matrix.md`
- Modify: `docs/07-validation-evidence/11_dsl_v0_3_test_plan.md`
- Modify: `scripts/release/build-usage-kit.sh`
- Modify: `scripts/release/verify-usage-kit.sh`
- Create: `scripts/release/verify-v0-3-runtime-samples.sh`

- [x] **Step 1: Update user guide**

Document:

- v0.3 runtime execution support by provider type,
- which samples are executable,
- which external/native profiles require env vars,
- that v0.2 samples remain supported.

- [x] **Step 2: Update support matrix**

Move provider types from `contract_only` to `supported` only after their v0.3 executable sample and test pass.

- [x] **Step 3: Add release script**

`verify-v0-3-runtime-samples.sh` must use an explicit P0 matrix. It must not verify only the golden and HTTP samples.

```bash
V03_P0_SUITES=(
  "samples/v0_3_dsl/golden/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/http_mock_rest_client/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/data/jdbc/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/verification/artifact_compare/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/verification/common_verify/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/verification/polling_observer/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/messaging/nats/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/messaging/kafka/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/messaging/ibm_mq/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/rpc/soap_mock_rest_client/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/rpc/grpc_mock_grpc_client/suite_manifest.yaml:local_v03"
  "samples/v0_3_dsl/multi_test/suite_manifest.yaml:local_v03"
)
```

For each matrix entry, the script must run:

```bash
java -Xmx512m -jar "$JAR" validate --suite "$SUITE" --profile "$PROFILE"
java -Xmx512m -jar "$JAR" run --suite "$SUITE" --profile "$PROFILE" --dry-run
RUN_OUTPUT="$(java -Xmx512m -jar "$JAR" run --suite "$SUITE" --profile "$PROFILE")"
RESULT_JSON="$(printf '%s\n' "$RUN_OUTPUT" | awk -F': ' '/result_json:/ {print $2; exit}')"
java -Xmx512m -jar "$JAR" report --result "$RESULT_JSON"
java -Xmx512m -jar "$JAR" validate-evidence --result "$RESULT_JSON"
```

External/native profiles requiring `env://...` values must be documented and may be skipped in default CI only when the selected local P0 profile still proves the provider runtime path without external infrastructure. Missing env vars in unselected external profiles must not block local/CI P0 verification.

- [x] **Step 4: Update usage-kit verification**

`verify-usage-kit.sh` must validate, dry-run, run, report, and validate evidence for all v0.3 executable samples.

- [x] **Step 5: Run release checks**

```bash
scripts/release/build-usage-kit.sh 0.3.0
scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.3.0-usage-kit.zip 0.3.0
scripts/release/verify-v0-3-runtime-samples.sh 0.3.0
```

- [ ] **Step 6: Commit**

```bash
git add samples/v0_3_dsl/README.md \
        docs/09-operations/test_framework_user_guide.md \
        docs/09-operations/provider_support_matrix.md \
        docs/07-validation-evidence/11_dsl_v0_3_test_plan.md \
        scripts/release/build-usage-kit.sh \
        scripts/release/verify-usage-kit.sh \
        scripts/release/verify-v0-3-runtime-samples.sh
git commit -m "docs: add v0.3 runtime execution release gates"
```

## 6. Final Definition of Done

v0.3.0 provider runtime execution is complete only when all are true:

- v0.3 samples validate, dry-run, run, report, and validate evidence.
- v0.3 result JSON does not require Provider Instance refs.
- v0.3 executable samples exist for all provider types listed in P0 release scope.
- v0.2 executable samples still pass.
- `run --dry-run` never invokes provider runtime.
- Runtime failures include target, provider contract, provider type, profile, operation, failure code, category, and owner-actionable message.
- Cleanup always runs after setup starts.
- Cleanup failure does not hide original failure.
- Raw secrets are blocked or masked in DSL, Env_Profile, result JSON, evidence, logs, and report.
- Usage-kit contains v0.3 docs, contracts, samples, commands, and release verification evidence.

## 7. Full Verification Command Set

Run these before release:

```bash
scripts/ci/check-schema-drift.sh
scripts/ci/check-public-support-contract.sh
scripts/ci/secret-scan.sh
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03CommandTest,DslV03ProviderRuntimeExecutionCommandTest,DslV03JdbcRuntimeCommandTest,DslV03MessagingRuntimeCommandTest,DslV03VerificationRuntimeCommandTest,DslV03RpcRuntimeCommandTest,DslV03NegativeSampleCommandTest test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=GoldenE2eCommandTest,WireMockProviderCapabilityCommandTest,WireMockHttpRequestSampleCommandTest,JdbcProviderCapabilityCommandTest,NatsProviderCapabilityCommandTest,MessagingClientProviderCapabilityCommandTest,SoapMockCapabilityCommandTest,GrpcMockCapabilityCommandTest,CommonVerifyCapabilityCommandTest test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q verify
scripts/release/build-usage-kit.sh 0.3.0
scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.3.0-usage-kit.zip 0.3.0
scripts/release/verify-v0-3-runtime-samples.sh 0.3.0
```

## 8. Plan Self-Review

Spec coverage:

- Suite target model: Tasks 1-5.
- Provider Contract catalog and exact-id resolution: Task 2.
- Provider Contract validation: Tasks 1-5 and provider-specific Tasks 6, 8, 9, 10, 11.
- Env_Profile binding model: Tasks 3, 6, 8, 9, 10, 11.
- Runtime execution: Tasks 5-11.
- Framework assertion versus provider_check boundary: Task 9.
- Result/evidence/report: Task 7.
- Samples and release artifact: Tasks 12-13.
- v0.2 compatibility: Tasks 6-13 and final verification commands.

Type consistency:

- All v0.3 runtime inputs flow through `V03ExecutionPlan`.
- Provider-specific runtime code is isolated behind `V03ProviderRuntimeAdapter`.
- Assertion-only verification is isolated in the framework verifier engine and does not require provider runtime dispatch.
- v0.3 Provider Contracts are resolved by exact contract id through the framework catalog.
- Existing v0.2 Provider Instance model remains outside v0.3 runtime input.

No placeholder policy:

- Every task names exact files, expected tests, and verification commands.
- Provider slices name explicit operation mappings.
- Release gates name exact scripts and command lines.
