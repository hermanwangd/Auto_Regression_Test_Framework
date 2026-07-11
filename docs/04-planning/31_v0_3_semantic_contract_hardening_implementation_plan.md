# DSL v0.3 Semantic Contract Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` task-by-task. Keep Maven bounded with `MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m'`.

**Goal:** Make v0.3 validation, dry-run, and execution use one typed multi-test compiled suite with fail-closed references and assertions.

**Architecture:** Evolve the existing `V03ExecutionPlanBuilder` into the sole compiler; do not introduce a parallel `V03SuiteCompiler`. It loads the suite, profile, all test cases, and framework Provider Contracts once, produces a `V03CompiledSuite`, then passes that object to dry-run rendering and `V03RuntimeExecutionService`.

**Tech Stack:** Spring Boot 3.x, Java 17+, JUnit 5, AssertJ, SnakeYAML, Jackson, Maven.

---

## Scope and Preconditions

- No new provider or provider runtime is in scope.
- Do not alter the v0.2 execution path.
- Treat current v0.3 result/evidence behavior as compatibility-sensitive.
- Every task ends with focused tests before its commit; run the bounded full suite before release.

## File Map

| Area | Files |
| --- | --- |
| Compiler model | `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilder.java`, `V03ExecutionPlan.java`, `V03ExecutionStep.java`, new `V03CompiledSuite.java`, new `V03CompiledTestCase.java` |
| References | new `contract/v03/ref/V03Reference.java`, `V03ReferenceParser.java`, `V03ReferenceResolver.java`; existing runtime adapters stop parsing raw strings |
| Assertions | new `contract/v03/assertion/V03AssertionKind.java`, `V03AssertionValidator.java`; `V03RuntimeExecutionService.java` |
| Validation | `ContractBaselineService.java`, v0.3 schemas and Provider Contract YAML files |
| CLI/runtime | `RegressionCommand.java`, `ContractBaselineService.java`, new `V03DryRunRenderer.java`, `V03RuntimeExecutionService.java` |
| Tests | `src/test/java/com/specdriven/regression/contract/v03/**`, `src/test/java/com/specdriven/regression/cli/DslV03*Test.java` |
| Samples/docs | `samples/00-getting-started/golden_e2e/**`, `samples/20-provider-capability-p0/**`, v0.3 spec, architecture, AC, test plan, user guide |

## Task 1: Freeze Current Observable Behavior

**Files:**
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/GoldenE2eCommandTest.java`
- Modify: `src/test/java/com/specdriven/regression/contract/v03/V03ResultEvidenceContractTest.java`

- [ ] Add a two-test v0.3 fixture using one shared Env_Profile and assert per-test status plus suite counts.
- [ ] Add characterization assertions for golden result JSON keys, evidence index entries, report summary, cleanup outcome, and exit code.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03CommandTest,GoldenE2eCommandTest,V03ResultEvidenceContractTest test
```

- [ ] Commit: `test: characterize v0.3 multi-test and golden output`

## Task 2: Introduce the Multi-Test Compiled Model

**Files:**
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03CompiledSuite.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03CompiledTestCase.java`
- Modify: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionPlan.java`
- Modify: `src/main/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilder.java`
- Modify: `src/test/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilderTest.java`

- [ ] Add records equivalent to:

```java
record V03CompiledSuite(String suiteId, String profileId,
        Map<String, V03ResolvedTarget> targets,
        Map<String, Path> artifactRoots,
        List<V03CompiledTestCase> tests) { }

record V03CompiledTestCase(String testCaseId,
        List<V03ExecutionStep> setup, List<V03ExecutionStep> execute,
        List<V03ExecutionStep> verify, List<V03ExecutionStep> cleanup) { }
```

- [ ] Change `V03ExecutionPlanBuilder.build(Path, String)` to compile every `suite.tests` reference, reject duplicate test IDs, and retain current ordered step behavior.
- [ ] Keep `V03ExecutionPlan` only as a compatibility view if required; it must derive from `V03CompiledSuite`, never re-read YAML.
- [ ] Test a two-test suite has one resolved target set and two distinct test-case step scopes.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ExecutionPlanBuilderTest,DslV03CommandTest test
```

- [ ] Commit: `feat: compile v0.3 suites as multi-test plans`

## Task 3: Add One Typed Reference Parser and Resolver

**Files:**
- Create: `src/main/java/com/specdriven/regression/contract/v03/ref/V03Reference.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/ref/V03ReferenceParser.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/ref/V03ReferenceResolver.java`
- Modify: `V03ExecutionPlanBuilder.java`, `V03RuntimeExecutionService.java`
- Modify: `contract/v03/adapter/AbstractProviderRuntimeV03Adapter.java`
- Create: `src/test/java/com/specdriven/regression/contract/v03/ref/V03ReferenceResolverTest.java`

- [ ] Parse only `artifact://<root>/<path>[#<pointer>]`, `step://<id>/<output>`, `generated://<target>/<output>`, and Env_Profile-only `env://<name>`.
- [ ] Reject dot-form generated refs with `invalid_generated_ref`; reject an `env://` ref in a DSL step with `invalid_reference_scope`.
- [ ] Resolve artifact roots through the compiled suite; enforce containment and apply JSON Pointer after materialization.
- [ ] Resolve step refs only from earlier steps of the same test case and only for contract-declared outputs.
- [ ] Replace raw prefix stripping in the runtime and adapters with resolved values from the compiler context.
- [ ] Test valid slash generated refs, dot-form rejection, artifact pointer extraction, traversal rejection, undeclared output rejection, and cross-test step-ref rejection.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ReferenceResolverTest,V03ExecutionPlanBuilderTest,DslV03NegativeSampleCommandTest test
```

- [ ] Commit: `feat: unify v0.3 reference resolution`

## Task 4: Make Assertion Semantics Fail Closed

**Files:**
- Create: `src/main/java/com/specdriven/regression/contract/v03/assertion/V03AssertionKind.java`
- Create: `src/main/java/com/specdriven/regression/contract/v03/assertion/V03AssertionValidator.java`
- Modify: `V03RuntimeExecutionService.java`, `V03ExecutionPlanBuilder.java`
- Modify: `docs/02-architecture/contracts/test_case_dsl.v0.3.schema.yaml`
- Create: `src/test/java/com/specdriven/regression/contract/v03/assertion/V03AssertionValidatorTest.java`

- [ ] Register `equals`, `not_equals`, `gt`, `gte`, `lt`, `lte`, `matches`, `exists`, `not_exists`, `json_match`, `schema_match`, and `file_diff`.
- [ ] Validate operand shape and value compatibility during compilation; reject all unknown kinds with `unsupported_assertion_kind`.
- [ ] Remove the runtime fallback that compares strings for any otherwise unrecognized assertion operator.
- [ ] Preserve existing JSON/schema/file-diff evidence generation and test each catalog entry at least once.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03AssertionValidatorTest,DslV03VerificationRuntimeCommandTest test
```

- [ ] Commit: `fix: fail closed for v0.3 assertions`

## Task 5: Validate Provider Checks and Binding Dependencies at Compile Time

**Files:**
- Modify: `ContractBaselineService.java`, `V03ExecutionPlanBuilder.java`
- Modify: `docs/02-architecture/contracts/provider_contract.v0.3.schema.yaml`, `schemas/provider_contract.v0.3.schema.yaml`
- Modify: `docs/02-architecture/contracts/env_profile.v0.3.schema.yaml`, `schemas/env_profile.v0.3.schema.yaml`
- Modify: `docs/02-architecture/contracts/provider-contracts/*_v0_3.yaml`
- Modify: `src/test/java/com/specdriven/regression/contract/v03/V03ExecutionPlanBuilderTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03NegativeSampleCommandTest.java`

- [ ] Require every provider operation to declare allowed phases, `with` fields, required fields, output paths, bindable outputs, evidence types, and failure codes.
- [ ] Reject `provider_check.expect`; provider-specific expected input stays inside contract-declared `with` fields. Require follow-up `type: assertion` steps for generic comparison.
- [ ] Construct the generated-output dependency graph from Env_Profile bindings; reject missing producer targets, undeclared outputs, self references, and cycles with owner-actionable paths.
- [ ] Test unsupported phase/operation/input, missing binding, missing producer, undeclared output, cycle, and prohibited provider-check expectation.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=V03ExecutionPlanBuilderTest,DslV03NegativeSampleCommandTest,V03ProviderRuntimeRegistryTest test
```

- [ ] Commit: `feat: validate v0.3 provider checks and binding graph`

## Task 6: Route Dry-Run Through the Compiled Suite

**Files:**
- Create: `src/main/java/com/specdriven/regression/contract/v03/V03DryRunRenderer.java`
- Modify: `ContractBaselineService.java`, `RegressionCommand.java`
- Modify: `V03RuntimeExecutionService.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03NegativeSampleCommandTest.java`

- [ ] Keep `ContractBaselineService.dryRun()` as the v0.2 path. For a v0.3 leaf suite, compile once through `V03ExecutionPlanBuilder`, pass the `V03CompiledSuite` to `V03DryRunRenderer`, and do not call target-only dry-run resolution.
- [ ] Route `validate --suite <v0.3-leaf>` through the same compiler after baseline schema discovery. Map compiler findings to `validation_status: failed`; on success, report compiled test/step counts without invoking a provider. Validation must not construct a second v0.3 semantic plan.
- [ ] Render deterministic `resolved_execution_plan` entries for every compiled step: `test_case_id`, phase, step ID, target, Provider Contract, provider type, runtime mode, operation, safe typed inputs, declared outputs, and evidence requirements. Never render secret values.
- [ ] Make `run --dry-run` return `run_status: dry_run_ready` and `provider_runtime_invoked: false` only after compilation succeeds. Compilation errors return `run_status: blocked` and the same owner-actionable finding codes as `validate`.
- [ ] Pass the same compiled object to `V03RuntimeExecutionService`; remove v0.3 raw YAML loading from runtime execution.
- [ ] Test validation/dry-run/runtime plan equivalence, blocked invalid refs/operations, secret masking, and zero provider invocations for validation and dry-run.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03CommandTest,DslV03NegativeSampleCommandTest,V03ExecutionPlanBuilderTest,V03RuntimeStatusTest test
```

- [ ] Commit: `feat: render v0.3 dry-run from compiled suite`

## Task 7: Enforce Leaf-Suite Version Gating

**Files:**
- Modify: `ContractBaselineService.java`, `RegressionCommand.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03CommandTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/DslV03NegativeSampleCommandTest.java`
- Modify: `src/test/java/com/specdriven/regression/cli/SuiteSummaryAggregationCommandTest.java`

- [ ] Require a v0.3 leaf Suite Manifest and all its referenced test cases to declare v0.3 before v0.3 schema/contract resolution.
- [ ] Reject mixed v0.2/v0.3 test cases within one leaf suite with `mixed_dsl_versions`; do not select either leaf runtime.
- [ ] Preserve suite-group behavior: each `child_suites[]` entry validates and runs using its own declared version, and the parent only aggregates standardized child summaries.
- [ ] Test a v0.3 leaf mixed-version failure and a v0.2/v0.3 suite group whose children each pass independently and aggregate without re-compilation.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=DslV03CommandTest,DslV03NegativeSampleCommandTest,SuiteSummaryAggregationCommandTest test
```

- [ ] Commit: `fix: scope v0.3 version gates to leaf suites`

## Task 8: Move Golden Execution Through the Generic Runtime

**Files:**
- Modify: `RegressionCommand.java`, `GoldenE2eService.java`
- Modify: `V03ProviderRuntimeRegistry.java`, `V03RuntimeExecutionService.java`
- Modify: `samples/00-getting-started/golden_e2e/**`
- Modify: `src/test/java/com/specdriven/regression/cli/GoldenE2eCommandTest.java`

- [ ] Register the fake provider through the same v0.3 registry used by every Provider Contract.
- [ ] Replace command-layer golden dispatch with generic compiled-suite execution.
- [ ] Make characterization tests from Task 1 green without weakening the result/evidence/report contract.
- [ ] Run:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q \
  -Dtest=GoldenE2eCommandTest,DslV03CommandTest,V03ResultEvidenceContractTest test
```

- [ ] Commit: `refactor: execute v0.3 golden suite through provider registry`

## Task 9: Align Samples, Public Documents, and Existing Release Gates

**Files:**
- Modify: `docs/01-specs/05_dsl_v0_3_no_provider_instance_spec.md`
- Modify: `docs/02-architecture/08_dsl_v0_3_no_provider_instance_architecture.md`
- Modify: `docs/03-acceptance/05_dsl_v0_3_acceptance_criteria.md`
- Modify: `docs/07-validation-evidence/11_dsl_v0_3_test_plan.md`
- Modify: `docs/09-operations/test_framework_user_guide.md`
- Modify: canonical v0.3 `samples/**` and their READMEs
- Modify: `scripts/release/verify-v0-3-runtime-samples.sh`
- Modify: `scripts/release/verify-usage-kit.sh`
- Modify: `scripts/release/verify-release-version.sh` when archive inventory needs an explicit v0.3 semantic-contract check
- Modify: `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java`
- Modify: release artifact packaging workflow and its tests when the generated archive inventory is incomplete

- [ ] Replace all v0.3 dot-form generated-ref and generic `provider_check.expect` examples. Preserve them only in clearly labeled v0.2 compatibility sections.
- [ ] State and enforce the v0.3 verify grammar: only `type: assertion` and `type: provider_check`; reject `verify.checks[]` and legacy top-level verifier names in v0.3 leaf tests. Keep their current behavior only on the v0.2 path.
- [ ] Link docs to canonical sample paths; do not create duplicate docs fixture trees.
- [ ] Extend the existing v0.3 runtime-sample and usage-kit verifiers to build with `./mvnw`, run from outside the repository working directory, validate all v0.3 sample manifests, run supported local samples, validate evidence, run report, and verify the bundled contract registry. Do not create a parallel release gate.
- [ ] Verify release archive inventory contains the jar, checksums, usage kit, schemas, Provider Contract catalog/index, canonical samples, and user guide.
- [ ] Record exact sample categories that are validation-only because external services are not provisioned.
- [ ] Run:

```bash
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q test
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw -q verify
scripts/release/verify-v0-3-runtime-samples.sh
scripts/release/verify-usage-kit.sh
```

- [ ] Commit: `docs: align v0.3 semantic contract and release gates`

## Final Definition of Done

- [ ] There is one v0.3 compiler and no runtime YAML re-parse path.
- [ ] Multi-test suites retain shared targets/profile and separate per-test results.
- [ ] Every public ref has one parser/resolver and one documented syntax.
- [ ] Assertions are catalogued and fail closed.
- [ ] `provider_check.expect` is rejected and Provider Contract semantics are validated before dispatch.
- [ ] Mixed versions within a leaf suite and invalid generated dependency graphs block before runtime; suite groups aggregate independent child versions.
- [ ] Golden execution uses the generic runtime path.
- [ ] v0.2 compatibility tests, supported v0.3 samples, evidence validation, reporting, and release gate pass.
