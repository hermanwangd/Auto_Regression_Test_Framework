# Env Profile Simplification Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task with review after each task. Steps use checkbox (`- [ ]`) syntax for tracking. This is a public configuration simplification and sample migration, not a provider runtime expansion.

**Goal:** Simplify environment configuration so new v0.2.6 samples use one Env_Profile artifact instead of separate `env_profiles/`, `execution_profiles/`, and `environment_bindings/` artifacts.

**Status:** Implemented and verified for v0.2.6.

**Architecture:** Env_Profile becomes the only public runtime environment configuration artifact. Suite manifests select a profile, test case DSL targets reference `provider_id` only, and provider bindings live under `env_profiles/<profile>.yaml`. Existing split configuration remains a compatibility input in the loader and validator, but committed samples and usage-kit samples must demonstrate the Env_Profile-only model.

**Tech Stack:** Spring Boot 3.x, Java 17+, Maven, SnakeYAML, JUnit 5, AssertJ, YAML schemas and checked-in framework samples.

---

## 1. Public Interface Decisions

- Keep DSL version at `v0.2`; do not introduce DSL `v0.3`.
- New DSL test cases must not contain `targets.*.profile`.
- Runtime profile selection is:
  1. `regress run` requires CLI `--profile`.
  2. `regress validate` and `regress run --dry-run` may use `suite_manifest.profile` when no CLI profile is supplied.
  3. If a suite declares `profile`, `profiles`, or `selection.profile`, a CLI profile must match that allowed set.
  4. If no selected profile can be derived, fail with `missing_selected_profile`.
- If deprecated `targets.*.profile` is present in an old test case:
  - accept it as compatibility input when it matches the selected profile
  - fail `conflicting_profile_selection` when it conflicts with CLI or suite profile
- New samples must not contain `execution_profiles/` or `environment_bindings/`.
- New samples must not declare `artifact_roots.execution_profiles` or `artifact_roots.environment_bindings`.
- New samples must use `providers.<provider_id>.bindings` in Env_Profile.
- `providers.<provider_id>.binding_keys` remains compatibility input only and must not appear in new samples.
- Provider Contract still defines allowed `binding_keys`; Env_Profile `bindings` values are validated against those contract keys.
- If new Env_Profile and old split config both exist for the same suite, Env_Profile wins and old split config is ignored as compatibility fallback input.
- Compatibility behavior belongs to framework runtime and validator, not sample authoring style.

## 2. Final Public Shape

Minimal suite manifest:

```yaml
contract_version: v0.2
suite_id: JDBC-CAPABILITY-v0.2
profile: local_jdbc
tests:
  - test_case.yaml
artifact_roots:
  provider_instances: provider_instances/
  env_profiles: env_profiles/
  expected_results: expected_results/
  fixtures: fixtures/
  queries: queries/
```

Minimal Env_Profile:

```yaml
env_profile_id: local_jdbc
execution_mode: local
providers:
  oracle-like-db:
    runtime_mode: ephemeral
    bindings:
      connection:
        secret_ref: generated://provider-capability/oracle-like/connection
      dialect: oracle
      schema: PUBLIC
```

Minimal DSL target:

```yaml
targets:
  database:
    provider_id: oracle-like-db
```

Framework defaults when omitted:

```yaml
isolation_scope: per_run
max_duration: PT5M
dependency_policy:
  require_readiness_evidence: false
data_policy:
  approved_expected_results_required: true
  production_data_allowed: false
  generated_data_allowed: true
  secrets_must_use_refs: true
dependency_substitution_policy:
  mock_evidence_release_claim: prohibited
evidence_policy:
  evidence_classification: framework_provider_capability_only
  downstream_release_evidence: false
```

## 3. Binding Normalization Contract

Env_Profile public authoring uses `bindings`. Runtime keeps using the existing internal `binding_values` map so provider runtimes do not each learn a new shape.

Canonical normalization rules:

| Env_Profile authoring input | Internal runtime `binding_values` |
|---|---|
| `bindings.dialect: oracle` | `dialect: oracle` |
| `bindings.connection.secret_ref: env://JDBC_CONNECTION` | `connection.secret_ref: env://JDBC_CONNECTION` |
| `bindings.query_timeout: PT10S` | `query_timeout: PT10S` |
| `bindings.masking_policy.redact: [connection, password]` | `masking_policy.redact: [connection, password]` |

New samples must use nested maps for nested values. Dotted keys such as `connection.secret_ref` remain compatibility input only when reading deprecated `binding_keys`.

Deprecated compatibility normalization rules:

| Deprecated Env_Profile input | Internal runtime `binding_values` |
|---|---|
| `binding_keys.dialect.value: oracle` | `dialect: oracle` |
| `binding_keys.connection.secret_ref: env://JDBC_CONNECTION` | `connection.secret_ref: env://JDBC_CONNECTION` |
| `binding_keys.connection.local_ref: generated://jdbc` | `connection.local_ref: generated://jdbc` |

Validation rules:

- Validate normalized `binding_values` against Provider Contract `binding_keys`.
- Raw secret detection scans both `bindings` and deprecated `binding_keys`.
- If `bindings` and `binding_keys` both exist for the same provider, use `bindings`; `binding_keys` remains compatibility input only.
- Field paths in new-format validation errors should use `providers.<provider_id>.bindings.<key>`.
- Field paths for deprecated compatibility inputs may still use `providers.<provider_id>.binding_keys.<key>`.

## 4. File Impact Map

Documentation:

- Modify `docs/01-specs/03_feature_specs.md`.
- Modify `docs/09-operations/test_framework_user_guide.md`.
- Modify `docs/02-architecture/05_architecture_and_sequence.md`.
- Modify `docs/02-architecture/06_artifact_contracts.md`.
- Modify `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`.
- Modify `docs/02-architecture/contracts/env_profile.v0.2.schema.yaml`.
- Modify `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`.
- Modify `docs/03-acceptance/04_acceptance_criteria.md` if AC still names split environment artifacts.
- Modify `docs/07-validation-evidence/07_regression_test_plan.md` if test plan still names split environment artifacts.

Schemas:

- Modify `schemas/env_profile.v0.2.schema.yaml`.
- Modify `schemas/suite_manifest.v0.2.schema.yaml`.
- Keep `schemas/execution_profile.v0.2.schema.yaml` and `schemas/environment_binding.v0.2.schema.yaml` only for compatibility validation.

Runtime and validation:

- Modify `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`.
- Modify `src/main/java/com/specdriven/regression/contract/RuntimeBindingResolver.java`.
- Modify provider runtime profile readers only if they bypass `RuntimeBindingResolver` or `ContractBaselineService.providerBinding`.
- Modify CLI validation output only if it reports old artifact names as primary output.

Samples:

- Modify all directories under `samples/00-getting-started/`.
- Modify all directories under `samples/10-contract-baseline/`.
- Modify all directories under `samples/20-provider-capability-p0/`.
- Modify all directories under `samples/30-cross-provider-groups/`.
- Modify all directories under `samples/40-evidence-reporting/` only if result fixtures mention split config.
- Modify `samples/README.md`.

Release and CI:

- Modify `scripts/release/build-usage-kit.sh`.
- Modify `scripts/release/verify-usage-kit.sh`.
- Modify `scripts/release/verify-supported-provider-samples.sh`.
- Modify `.github/workflows/release.yml` only if it references removed sample paths.

Tests:

- Modify `src/test/java/com/specdriven/regression/cli/JdbcProviderCapabilityCommandTest.java`.
- Modify provider capability command tests for WireMock, REST client, Kafka, IBM MQ, NATS, common verify, polling, SOAP mock, and gRPC mock.
- Modify `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java`.
- Add or modify a validation test that proves new samples contain no split environment config.

## 5. Implementation Tasks

### Task 1: Lock The Contract In Docs And Schemas

**Files:**

- Modify `docs/01-specs/03_feature_specs.md`.
- Modify `docs/02-architecture/05_architecture_and_sequence.md`.
- Modify `schemas/env_profile.v0.2.schema.yaml`.
- Modify `schemas/suite_manifest.v0.2.schema.yaml`.
- Modify `docs/02-architecture/contracts/env_profile.v0.2.schema.yaml`.
- Modify `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`.
- Modify `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`.

- [x] Step 1: Update Env_Profile schema to accept `providers.<provider_id>.bindings`.
- [x] Step 2: Keep `providers.<provider_id>.binding_keys` accepted as deprecated compatibility input.
- [x] Step 3: Update suite manifest schema so new samples do not require `artifact_roots.execution_profiles` or `artifact_roots.environment_bindings`.
- [x] Step 4: Document profile selection: `run` requires CLI `--profile`; validate/dry-run may use `suite_manifest.profile`; CLI profile must match any suite-declared allowed profile set; missing profile fails with `missing_selected_profile`.
- [x] Step 5: Update feature spec and architecture sequence docs so the public environment model is Suite Manifest + Env_Profile, not split Execution Profile + Environment Binding.
- [x] Step 6: Run schema and doc consistency checks:

```bash
rg -n "execution_profiles|environment_bindings|binding_keys|targets\\..*profile" \
  docs/02-architecture/contracts schemas
```

Expected: remaining matches either describe deprecated compatibility or Provider Contract `binding_keys`.

### Task 2: Add Boundary Validation Tests First

**Files:**

- Modify or create focused CLI/runtime tests, such as `GoldenE2eCommandTest`, `RuntimeBindingResolverTest`, `ReleaseUsageKitVerificationTest`, or a dedicated `EnvProfileSimplificationValidationTest` if the cases outgrow existing fixtures.

- [x] Step 1: Add a passing test for an Env_Profile-only suite that has `bindings` and no split config directories.
- [x] Step 2: Add a committed-sample validation test that rejects new samples containing `artifact_roots.execution_profiles`.
- [x] Step 3: Add a committed-sample validation test that rejects new samples containing `artifact_roots.environment_bindings`.
- [x] Step 4: Add a validation test for deprecated `targets.*.profile`.
- [x] Step 5: Add a validation test for `conflicting_profile_selection`.
- [x] Step 6: Add the split-config validation tests using isolated temporary suite fixtures, not the committed `samples/` tree.
- [x] Step 7: Add a normalization test that proves scalar and object `bindings` both reach internal runtime `binding_values`:

```yaml
providers:
  oracle-like-db:
    runtime_mode: ephemeral
    bindings:
      dialect: oracle
      connection:
        secret_ref: env://JDBC_CONNECTION
```

Expected internal binding values:

```yaml
dialect: oracle
connection:
  secret_ref: env://JDBC_CONNECTION
```

- [x] Step 8: Run focused tests after runtime changes:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' \
  ./mvnw -q test -Dtest=GoldenE2eCommandTest,RuntimeBindingResolverTest,ReleaseUsageKitVerificationTest
```

Expected after implementation: Env_Profile-only validation passes; committed-sample blockers reject split config; deprecated compatibility cases remain covered.

### Task 3: Implement Env_Profile Reader Compatibility

**Files:**

- Modify `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`.
- Modify `src/main/java/com/specdriven/regression/contract/RuntimeBindingResolver.java`.
- Modify provider runtime profile resolution code only if it bypasses the two files above.

- [x] Step 1: Normalize Env_Profile provider config so `bindings` is the canonical authoring map and `binding_values` remains the internal runtime map.
- [x] Step 2: If `binding_keys` appears in Env_Profile, normalize it into internal `binding_values` for compatibility.
- [x] Step 3: If both `bindings` and `binding_keys` appear for the same provider, use `bindings`; do not let deprecated `binding_keys` override canonical values.
- [x] Step 4: Validate `bindings.*` against Provider Contract `binding_keys`.
- [x] Step 5: Preserve raw secret detection on all binding values.
- [x] Step 6: Run focused tests:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' \
  ./mvnw -q test -Dtest=GoldenE2eCommandTest,RuntimeBindingResolverTest,ReleaseUsageKitVerificationTest
```

Expected: Env_Profile-only validation passes; deprecated compatibility cases remain readable or fail owner-actionably when they conflict with canonical profile selection.

### Task 4: Implement Env_Profile Defaults

**Files:**

- Modify `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`.
- Modify `src/main/java/com/specdriven/regression/contract/RuntimeBindingResolver.java`.
- Modify provider capability services only where evidence classification or timeout defaults are applied outside the shared binding path.
- Modify or create `src/test/java/com/specdriven/regression/cli/EnvProfileSimplificationValidationTest.java`.

- [x] Step 1: Apply omitted Env_Profile defaults during validation and runtime binding resolution.
- [x] Step 2: Ensure omitted `evidence_policy.evidence_classification` defaults to `framework_provider_capability_only`.
- [x] Step 3: Ensure omitted `evidence_policy.downstream_release_evidence` defaults to `false`.
- [x] Step 4: Ensure omitted `data_policy.secrets_must_use_refs` defaults to `true`.
- [x] Step 5: Ensure omitted `data_policy.production_data_allowed` defaults to `false`.
- [x] Step 6: Ensure omitted `isolation_scope` defaults to `per_run`.
- [x] Step 7: Ensure omitted `max_duration` defaults to `PT5M`.
- [x] Step 8: Add a test that a minimal Env_Profile without optional policy fields validates and runs without claiming release evidence eligibility.
- [x] Step 9: Run focused tests:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' \
  ./mvnw -q test -Dtest=GoldenE2eCommandTest,RuntimeBindingResolverTest,ReleaseUsageKitVerificationTest
```

Expected: minimal Env_Profile samples receive defaults consistently, and no provider result claims release evidence unless explicitly configured.

### Task 5: Migrate JDBC Sample First

**Files:**

- Modify `samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml`.
- Modify `samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml`.
- Modify `samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml`.
- Modify `samples/20-provider-capability-p0/data/jdbc/env_profiles/*.yaml`.
- Delete `samples/20-provider-capability-p0/data/jdbc/execution_profiles/`.
- Delete `samples/20-provider-capability-p0/data/jdbc/environment_bindings/`.
- Modify JDBC test cases to remove `targets.*.profile` if present.

- [x] Step 1: Remove `artifact_roots.execution_profiles` and `artifact_roots.environment_bindings` from JDBC manifests.
- [x] Step 2: Convert JDBC Env_Profile provider values from `binding_keys` to `bindings`.
- [x] Step 3: Move any still-required execution settings from old `execution_profiles/` into `env_profiles/*.yaml`.
- [x] Step 4: Move any still-required provider binding values from old `environment_bindings/` into `env_profiles/*.yaml`.
- [x] Step 5: Delete the old JDBC split config directories after content is represented in Env_Profile.
- [x] Step 6: Rebuild the CLI jar and run JDBC public CLI verification:

```bash
VERSION="$(JAVA_TOOL_OPTIONS='-Xmx512m' MAVEN_OPTS='-Xmx2g' ./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -q package -DskipTests
JAVA_TOOL_OPTIONS='-Xmx512m' java -Xmx512m -jar "target/spec-driven-auto-regression-${VERSION}.jar" \
  validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml
JAVA_TOOL_OPTIONS='-Xmx512m' java -Xmx512m -jar "target/spec-driven-auto-regression-${VERSION}.jar" \
  validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml --profile external_jdbc_oracle_env_secret_ref
JAVA_TOOL_OPTIONS='-Xmx512m' java -Xmx512m -jar "target/spec-driven-auto-regression-${VERSION}.jar" \
  validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml --profile external_jdbc_db2_env_secret_ref
```

Expected: all three validations pass and list only provider instances referenced by each suite.

### Task 6: Migrate Remaining Provider Capability Samples

**Files:**

- Modify `samples/20-provider-capability-p0/http/**`.
- Modify `samples/20-provider-capability-p0/rpc/**`.
- Modify `samples/20-provider-capability-p0/messaging/**`.
- Modify `samples/20-provider-capability-p0/verification/**`.
- Modify `samples/30-cross-provider-groups/**`.
- Modify `samples/00-getting-started/**`.
- Modify `samples/10-contract-baseline/**`.

- [x] Step 1: For each leaf suite, remove split artifact roots from `suite_manifest.yaml`.
- [x] Step 2: For each Env_Profile, rename provider value map from `binding_keys` to `bindings`.
- [x] Step 3: Move old `execution_profiles` values that affect runtime behavior into Env_Profile.
- [x] Step 4: Move old `environment_bindings` provider values into Env_Profile.
- [x] Step 5: Delete old split config directories from each migrated sample.
- [x] Step 6: Remove `targets.*.profile` from all test cases.
- [x] Step 7: Add or update `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java` so committed samples reject these paths:

```text
samples/**/execution_profiles/**
samples/**/environment_bindings/**
```

- [x] Step 8: Run committed sample shape scan:

```bash
find samples -path '*/execution_profiles/*' -o -path '*/environment_bindings/*'
rg -n "artifact_roots:|execution_profiles:|environment_bindings:|binding_keys:|profile:" samples
```

Expected: no split config directories; `profile:` remains only in suite manifests, env profile identifiers, result fixtures, or compatibility documentation.

### Task 7: Update Usage-Kit And Release Verification

**Files:**

- Modify `scripts/release/build-usage-kit.sh`.
- Modify `scripts/release/verify-usage-kit.sh`.
- Modify `scripts/release/verify-supported-provider-samples.sh`.
- Modify `.github/workflows/release.yml` only if needed.

- [x] Step 1: Ensure usage-kit canonical sample paths contain only Env_Profile-only samples.
- [x] Step 2: Ensure generated legacy alias paths copy new-format samples, not old split config.
- [x] Step 3: Update `verify-usage-kit.sh` required paths to remove `execution_profiles/` and `environment_bindings/`.
- [x] Step 4: Add a usage-kit verification scan that fails if generated release assets contain split config sample directories.
- [x] Step 5: Run release scripts:

```bash
VERSION="$(JAVA_TOOL_OPTIONS='-Xmx512m' MAVEN_OPTS='-Xmx2g' ./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -q package -DskipTests
bash scripts/release/build-usage-kit.sh "${VERSION}"
bash scripts/release/verify-usage-kit.sh "target/spec-driven-auto-regression-${VERSION}-usage-kit.zip" "${VERSION}"
REQUIRE_EXTERNAL_JDBC=false REQUIRE_EXTERNAL_MESSAGING=false \
  JAVA_TOOL_OPTIONS='-Xmx512m' bash scripts/release/verify-supported-provider-samples.sh "${VERSION}"
```

Expected: usage-kit verification passes and supported-provider sample verification ends with `supported_provider_sample_verification_status: passed_ci_verifiable_external_messaging_not_configured`.

### Task 8: Update User Guide, AC, And Test Plan

**Files:**

- Modify `docs/01-specs/03_feature_specs.md`.
- Modify `docs/02-architecture/05_architecture_and_sequence.md`.
- Modify `docs/02-architecture/06_artifact_contracts.md`.
- Modify `docs/09-operations/test_framework_user_guide.md`.
- Modify `docs/03-acceptance/04_acceptance_criteria.md`.
- Modify `docs/07-validation-evidence/07_regression_test_plan.md`.
- Modify `samples/README.md`.

- [x] Step 1: Update feature spec so Env_Profile-only authoring is the v0.2.6 sample contract.
- [x] Step 2: Update architecture sequence docs so runtime resolution reads selected profile from CLI or suite manifest, then resolves `env_profiles/<profile>.yaml`.
- [x] Step 3: Update artifact contracts to show `bindings` as preferred Env_Profile authoring syntax.
- [x] Step 4: Update user guide to say sample authors configure one Env_Profile per runtime profile.
- [x] Step 5: Add a short migration section from split config to Env_Profile-only config.
- [x] Step 6: Update AC to require new samples not to contain split config directories.
- [x] Step 7: Update test plan to include validation for `bindings`, deprecated `binding_keys`, deprecated target profile, and conflicting profile selection.
- [x] Step 8: Update sample README with the new authoring model:

```text
Suite Manifest + Test Cases + Provider Instances + Env_Profiles
```

### Task 9: Final Verification And Review

**Files:**

- No new source files expected.
- Update tests only if verification reveals a real gap in the public contract.

- [x] Step 1: Run full unit tests:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -q test
```

Expected: build exits `0`.

- [x] Step 2: Run public terminology scan:

```bash
rg -n "execution_profiles|environment_bindings|binding_keys|targets\\..*profile" \
  docs samples scripts src/test/java
```

Expected: matches are limited to compatibility docs, compatibility tests, Provider Contract `binding_keys`, and explicit deprecation assertions.

- [x] Step 3: Run sample split-config blocker scan:

```bash
find samples -path '*/execution_profiles/*' -o -path '*/environment_bindings/*'
```

Expected: no output.

- [x] Step 4: Run whitespace check:

```bash
git diff --check
```

Expected: no output.

- [x] Step 5: Commit after review:

```bash
git add docs schemas samples scripts src .github
git commit -m "Simplify environment profile configuration"
```

## 5. Acceptance Criteria

- All committed samples use Env_Profile-only runtime environment configuration.
- No committed sample contains `execution_profiles/` or `environment_bindings/`.
- No committed sample declares `artifact_roots.execution_profiles` or `artifact_roots.environment_bindings`.
- New sample test cases do not contain `targets.*.profile`.
- New sample Env_Profiles use `bindings`, not `binding_keys`.
- Provider Contract `binding_keys` remains the source of allowed binding names.
- Scalar and object `bindings` normalize to the existing internal `binding_values` runtime map.
- Omitted Env_Profile policy fields receive framework defaults consistently.
- `regress run` requires CLI `--profile`; the selected CLI profile must be allowed by suite-declared profile constraints when they exist.
- Missing selected profile fails before provider runtime.
- Deprecated split config remains readable by compatibility tests.
- Deprecated target profile conflict fails owner-actionably.
- Usage-kit canonical and generated legacy alias samples use the new format.
- Release verification passes without requiring external JDBC, Kafka, or IBM MQ services.

## 6. Out Of Scope

- No DSL version bump.
- No new provider runtime.
- No Testcontainers implementation.
- No RP/RU topology interpretation.
- No release governance or waiver workflow.
- No Allure, ReportPortal, or dashboard work.
