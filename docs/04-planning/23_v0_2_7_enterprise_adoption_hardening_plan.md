# Framework v0.2.7 Enterprise Adoption Hardening Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task with review after each task. Steps use checkbox (`- [ ]`) syntax for tracking. This is an enterprise adoption hardening release, not a provider architecture rewrite.

**Goal:** Ship v0.2.7 as a cleaner adoption release that closes v0.2.6 release findings, makes external JDBC driver setup usable, aligns public schemas, and improves usage-kit onboarding.

**Architecture:** Keep the v0.2 suite-mode public runtime model: Suite Manifest, DSL Test Case, Provider Contract, Provider Instance, Env_Profile, Evidence, Result JSON, and Report. Do not split provider modules or introduce runtime SPI in v0.2.7. Add narrowly scoped runtime diagnostics and driver loading only where they unblock Oracle/DB2 external JDBC adoption.

**Tech Stack:** Spring Boot 3.x, Java 17+, Maven, Picocli-style command parsing inside `RegressionCommand`, SnakeYAML, Jackson, JUnit 5, AssertJ, shell release scripts, GitHub Actions, usage-kit packaging.

---

## 1. Release Scope

Release theme: **Enterprise Adoption Hardening + External Runtime Usability**.

P0 scope:

- Carry forward `35b712d` compatibility evidence fix: `dummy_rest` must not become downstream release evidence.
- Close or explicitly document the two v0.2.6 pi-run P2 findings:
  - `FW-V026-001`: `report --format json` unsupported.
  - `FW-V026-002`: project-provisioned WireMock external `base_url` consumption not proven.
- Add JDBC Oracle/DB2 driver loading mechanism:
  - `--driver-path`
  - `--driver-dir`
  - `REGRESS_DRIVER_PATH`
  - `usage-kit/drivers/`
  - `regress doctor drivers`
- Add external runtime setup docs for JDBC, Kafka, IBM MQ, NATS, REST, and gRPC.
- Align public schema contracts between `docs/02-architecture/contracts/`, `schemas/`, and usage-kit schemas.
- Add root onboarding files and update `AGENTS.md`.
- Improve usage-kit quick start, troubleshooting, driver setup, and legacy sample path warnings.

P1 stretch:

- Provider dependency policy and dependency inventory.
- `regress list-providers`.
- `regress list-samples`.
- `regress explain --failure-code <code>`.

Out of scope:

- Provider module split.
- Provider runtime SPI/plugin architecture.
- Centralized evidence model refactor.
- QA Agent skill suite.
- New provider families.
- Product/RP/RU topology interpretation inside framework runtime.
- Release governance, waiver workflow, Allure, ReportPortal, or dashboard.

## 2. Preconditions

- Current `main` contains or is based on commit `35b712d`.
- Work should start from a clean branch, recommended branch name: `release/0.2.7`.
- Keep command memory bounded. Use Maven heap caps such as `MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m"` unless a heavier test explicitly requires more.

Validation before implementation:

```bash
git status --short --branch
git log --oneline -3
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=V023SuiteModeFrameworkFixCommandTest,SampleLayoutContractTest test
```

Expected:

```text
working tree clean or only planned changes
V023SuiteModeFrameworkFixCommandTest passes
SampleLayoutContractTest passes
```

## 3. Public Interface Decisions

### 3.1 Report JSON

`regress report --result <result_json> --format json` becomes a v0.2.7 public report format. It must validate result JSON and evidence before returning success, just like text and YAML.

Required JSON shape:

```json
{
  "report_status": "review_ready",
  "suite_id": "SUITE-ID",
  "batch_id": "BATCH-ID",
  "run_id": "RUN-ID",
  "test_count": 1,
  "passed_count": 1,
  "failed_count": 0,
  "evidence_dir": "target/...",
  "missing_evidence_count": 0,
  "failed_evidence_count": 0,
  "provider_results_count": 1,
  "release_evidence_eligible": false,
  "masking_status": "passed",
  "failure_codes": []
}
```

Failure JSON shape:

```json
{
  "report_status": "failed",
  "failure_code": "EVIDENCE_VALIDATION_FAILED",
  "category": "EVIDENCE_ERROR",
  "message": "Owner-actionable message",
  "findings": []
}
```

### 3.2 WireMock External Base URL

Project-provisioned WireMock is not a framework-managed server. The framework must consume an Env_Profile binding:

```yaml
providers:
  wiremock-payment-api:
    runtime_mode: external
    bindings:
      base_url:
        value: http://127.0.0.1:18080
```

Rules:

- `base_url` may use `value` or approved `generated_ref`.
- `base_url` must use `http` or `https`.
- `base_url` must not contain userinfo, token query params, password query params, or `secret_ref`.
- Runtime must not start a framework-managed WireMock server when external `base_url` is supplied.
- Evidence must include provider ID, provider type, consumed base URL, request URL, and HTTP status.

### 3.3 JDBC Driver Loading

Do not bundle Oracle or DB2 drivers. v0.2.7 supports external driver discovery for JDBC Oracle/DB2 only.

Resolution precedence:

```text
--driver-path > --driver-dir > REGRESS_DRIVER_PATH > usage-kit/drivers/
```

Rules:

- `--driver-path` may be repeated.
- `--driver-dir` scans direct child `.jar` files only.
- `REGRESS_DRIVER_PATH` uses the platform path separator.
- `usage-kit/drivers/` is a fallback convention when running from a usage-kit directory.
- `regress doctor drivers` only checks jar existence and class loadability. It must not connect to a database.
- Driver loading must not print raw connection strings or secrets.

## 4. File Impact Map

Runtime and CLI:

- Modify `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`.
- Modify `src/main/java/com/specdriven/regression/provider/jdbc/JdbcProviderRuntime.java`.
- Create `src/main/java/com/specdriven/regression/provider/jdbc/JdbcDriverLoader.java`.
- Create `src/main/java/com/specdriven/regression/provider/jdbc/JdbcDriverDiscovery.java`.
- Modify WireMock/REST services only where external `base_url` consumption is handled:
  - `src/main/java/com/specdriven/regression/contract/WireMockHttpRequestCapabilityService.java`
  - `src/main/java/com/specdriven/regression/provider/wiremock/WireMockHttpMockProviderRuntime.java`
  - `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`

Report and evidence:

- Modify `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`.
- Modify `src/main/java/com/specdriven/regression/evidence/EvidenceHardeningService.java` only if report JSON needs an existing validation summary.
- Add or modify report tests under `src/test/java/com/specdriven/regression/cli/`.

Schemas and contracts:

- Modify `docs/02-architecture/contracts/*.schema.yaml`.
- Modify `schemas/*.schema.yaml`.
- Add `docs/02-architecture/contracts/evidence_index.v0.2.schema.yaml` or explicitly rename/align `evidence.v0.2.schema.yaml`.
- Add `scripts/ci/check-schema-drift.sh`.
- Modify `.github/workflows/ci.yml` and `.github/workflows/release.yml` if the drift check is not already covered by existing contract verification.

Docs and release packaging:

- Create root `README.md`.
- Create root `SECURITY.md`.
- Create root `CONTRIBUTING.md`.
- Create root `RELEASE.md`.
- Create root `SUPPORT.md`.
- Decide and create root `LICENSE` and `NOTICE`, or document why they are deferred.
- Modify `AGENTS.md`.
- Modify `docs/09-operations/test_framework_user_guide.md`.
- Create or update usage-kit docs through `scripts/release/build-usage-kit.sh`:
  - `QUICKSTART.md`
  - `TROUBLESHOOTING.md`
  - `DRIVER_SETUP.md`
  - `EXTERNAL_RUNTIME_SETUP.md`
  - `drivers/README.md`
- Add legacy path warning files in generated usage-kit samples.

Tests:

- Modify `src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java`.
- Modify `src/test/java/com/specdriven/regression/cli/V022SuiteModeAcceptanceCommandTest.java`.
- Add `src/test/java/com/specdriven/regression/cli/ReportJsonFormatCommandTest.java`.
- Add `src/test/java/com/specdriven/regression/provider/jdbc/JdbcDriverDiscoveryTest.java`.
- Add or extend `src/test/java/com/specdriven/regression/provider/jdbc/JdbcProviderCapabilityCommandTest.java`.
- Extend `src/test/java/com/specdriven/regression/provider/wiremock/WireMockExternalBaseUrlSupportTest.java`.
- Add `src/test/java/com/specdriven/regression/schema/SchemaDriftContractTest.java` if the shell drift check needs a Java guard.
- Update release/usage-kit verification tests if they assert exact sample doc lists.

## 5. Implementation Tasks

### Task 1: Create Release Branch and Carry Forward v0.2.6 Fix

**Files:**

- No file changes expected if `35b712d` is already present.

- [x] Step 1: Create or switch to release branch.

```bash
git status --short --branch
git switch -c release/0.2.7
```

Expected: branch is `release/0.2.7`; if branch already exists, use `git switch release/0.2.7`.

- [x] Step 2: Confirm compatibility evidence fix is present.

```bash
git log --oneline --all --grep "keep compatibility sample out of release evidence"
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=V023SuiteModeFrameworkFixCommandTest test
```

Expected:

```text
commit 35b712d or equivalent is reachable
V023SuiteModeFrameworkFixCommandTest passes
```

- [x] Step 3: Commit only if branch setup required a merge or cherry-pick.

```bash
git status --short
```

Expected: clean if no merge/cherry-pick was needed.

### Task 2: Add `report --format json`

**Files:**

- Modify `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`.
- Modify `src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java`.
- Modify `src/test/java/com/specdriven/regression/cli/V022SuiteModeAcceptanceCommandTest.java`.
- Add `src/test/java/com/specdriven/regression/cli/ReportJsonFormatCommandTest.java`.
- Modify `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`.
- Modify `docs/07-validation-evidence/07_regression_test_plan.md`.
- Modify `docs/09-operations/test_framework_user_guide.md`.

- [x] Step 1: Add failing test for valid JSON report.

Test behavior:

```java
CommandResult report = execute("report",
        "--result", "samples/40-evidence-reporting/evidence_hardening/valid_result.json",
        "--format", "json");
assertThat(report.exit()).isZero();
assertThat(report.stdout()).contains("\"report_status\"");
assertThat(report.stdout()).contains("\"suite_id\"");
assertThat(report.stdout()).contains("\"masking_status\"");
assertThat(report.stderr()).isBlank();
```

Run:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=ReportJsonFormatCommandTest test
```

Expected before implementation: fails because JSON format is unsupported.

- [x] Step 2: Implement JSON output in `RegressionCommand.reportResult`.

Required behavior:

```text
--format text => existing text summary
--format yaml => existing YAML summary
--format json => deterministic JSON summary
unknown format => exit 2
```

Implementation notes:

- Reuse existing report/evidence validation result objects.
- Use Jackson for JSON serialization.
- Do not build JSON by string concatenation.
- Preserve deterministic key order with `LinkedHashMap`.

- [x] Step 3: Update public interface docs and tests.

Change public docs from:

```text
report --result <result_json> [--format text|yaml]
```

to:

```text
report --result <result_json> [--format text|yaml|json]
```

Update the public interface contract test so it no longer asserts JSON is unsupported.

- [x] Step 4: Add negative JSON report tests.

Cases:

```text
valid result => exit 0, JSON report_status review_ready
missing evidence result => exit 1, JSON report_status failed
secret leak result => exit 1, JSON report_status failed
unknown format xml => exit 2, unsupported format
```

- [x] Step 5: Run focused verification.

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=ReportJsonFormatCommandTest,V022SuiteModeAcceptanceCommandTest,FrameworkPublicInterfaceContractTest test
```

Expected: all pass.

- [x] Step 6: Commit.

```bash
git add src/main/java/com/specdriven/regression/cli/RegressionCommand.java \
  src/test/java/com/specdriven/regression/cli/ReportJsonFormatCommandTest.java \
  src/test/java/com/specdriven/regression/cli/V022SuiteModeAcceptanceCommandTest.java \
  src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java \
  docs/02-architecture/contracts/framework_usage_interface.v0.2.md \
  docs/07-validation-evidence/07_regression_test_plan.md \
  docs/09-operations/test_framework_user_guide.md
git commit -m "feat: support json report output"
```

### Task 3: Prove Project-Provisioned WireMock External `base_url`

**Files:**

- Modify `docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml`.
- Modify `docs/09-operations/test_framework_user_guide.md`.
- Modify `samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/**`.
- Modify `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`.
- Modify WireMock/REST runtime files only if external `base_url` is not already consumed.
- Extend `src/test/java/com/specdriven/regression/provider/wiremock/WireMockExternalBaseUrlSupportTest.java`.
- Modify `scripts/release/verify-supported-provider-samples.sh` if release verification misses the external base URL case.

- [x] Step 1: Add failing test that starts an external local WireMock and passes its `base_url` through Env_Profile.

Expected assertions:

```text
framework run exits 0
provider_runtime_invoked: true
result JSON contains provider_id wiremock-payment-api
evidence contains consumed_base_url http://127.0.0.1:<port>
framework did not start an additional WireMock server for that provider
```

Run:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=WireMockExternalBaseUrlSupportTest test
```

Expected before implementation: fails if external base URL is not consumed.

- [x] Step 2: Implement or fix external `base_url` resolution.

Rules:

```text
missing base_url => CONFIGURATION_ERROR
malformed base_url => CONFIGURATION_ERROR
base_url with userinfo => SECRET_GUARDRAIL_ERROR
base_url with token/password query => SECRET_GUARDRAIL_ERROR
valid http/https base_url => runtime consumes external URL
```

- [x] Step 3: Add release sample or release verification path.

The release verifier must be able to prove:

```text
framework_consumed_project_dependencies: true
framework_consumption_status: consumed
```

for the WireMock project-provisioned dependency.

- [x] Step 4: Run focused verification.

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=WireMockExternalBaseUrlSupportTest,WireMockHttpRequestSampleCommandTest test
scripts/release/verify-supported-provider-samples.sh
```

Expected: external base URL sample is verified or explicitly listed as supported release proof.

- [x] Step 5: Commit.

```bash
git add docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml \
  docs/09-operations/test_framework_user_guide.md \
  samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http \
  src/main/java/com/specdriven/regression \
  src/test/java/com/specdriven/regression/provider/wiremock/WireMockExternalBaseUrlSupportTest.java \
  scripts/release/verify-supported-provider-samples.sh
git commit -m "fix: prove external wiremock base url consumption"
```

### Task 4: Add JDBC Driver Loading and `doctor drivers`

**Files:**

- Modify `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`.
- Modify `src/main/java/com/specdriven/regression/provider/jdbc/JdbcProviderRuntime.java`.
- Create `src/main/java/com/specdriven/regression/provider/jdbc/JdbcDriverDiscovery.java`.
- Create `src/main/java/com/specdriven/regression/provider/jdbc/JdbcDriverLoader.java`.
- Add `src/test/java/com/specdriven/regression/provider/jdbc/JdbcDriverDiscoveryTest.java`.
- Extend `src/test/java/com/specdriven/regression/cli/JdbcProviderCapabilityCommandTest.java`.
- Modify `scripts/release/build-usage-kit.sh`.
- Add usage-kit driver placeholders through source-controlled release inputs.

- [x] Step 1: Add driver discovery unit tests.

Cases:

```text
--driver-path one jar => returns that jar
--driver-path repeated => returns both jars in order
--driver-dir => returns direct child jars only
REGRESS_DRIVER_PATH => returns path-separator entries
usage-kit/drivers fallback => returns jars under drivers/
missing path => owner-actionable finding
non-jar path => owner-actionable finding
```

Run:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=JdbcDriverDiscoveryTest test
```

Expected before implementation: fails because discovery classes do not exist.

- [x] Step 2: Implement `JdbcDriverDiscovery`.

Required public behavior:

```text
driver_source: cli_driver_path | cli_driver_dir | env_regress_driver_path | usage_kit_drivers | none
driver_status: found | missing | invalid
driver_paths: [absolute-or-normalized-paths]
owner_action: provided when missing or invalid
```

- [x] Step 3: Implement driver class loading.

Rules:

```text
oracle dialect requires loadable oracle.jdbc.OracleDriver when native/external connection is selected
db2 dialect requires loadable com.ibm.db2.jcc.DB2Driver when native/external connection is selected
local H2 compatibility mode does not require external driver
doctor drivers does not connect to database
run uses driver loader before opening JDBC connection
```

- [x] Step 4: Add CLI parsing.

Supported forms:

```bash
regress run --suite <suite> --profile <profile> --driver-path ./drivers/oracle/ojdbc11.jar
regress run --suite <suite> --profile <profile> --driver-dir ./drivers
REGRESS_DRIVER_PATH=./drivers/oracle/ojdbc11.jar regress run --suite <suite> --profile <profile>
regress doctor drivers --driver-dir ./drivers
```

Unknown `doctor` subcommand exits `2`.

- [x] Step 5: Add driver placeholders to usage kit.

Generated usage-kit paths:

```text
drivers/README.md
drivers/oracle/put-ojdbc-here.txt
drivers/db2/put-db2-jcc-here.txt
```

Placeholder files must not include vendor driver binaries.

- [x] Step 6: Run focused verification.

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -Dtest=JdbcDriverDiscoveryTest,JdbcProviderCapabilityCommandTest test
scripts/release/build-usage-kit.sh 0.2.7
```

Expected: tests pass and usage kit contains driver placeholders.

Note: Task 4 verification used the current `pom.xml` version (`0.2.6`) for `build-usage-kit.sh` because the `0.2.7` version bump is intentionally deferred to Task 8. Task 8 must rerun the release guard with `0.2.7`.

- [x] Step 7: Commit.

```bash
git add src/main/java/com/specdriven/regression/cli/RegressionCommand.java \
  src/main/java/com/specdriven/regression/provider/jdbc \
  src/test/java/com/specdriven/regression/provider/jdbc/JdbcDriverDiscoveryTest.java \
  src/test/java/com/specdriven/regression/cli/JdbcProviderCapabilityCommandTest.java \
  scripts/release/build-usage-kit.sh
git commit -m "feat: add jdbc driver discovery diagnostics"
```

### Task 5: Align Schema Contracts and Add Drift Gate

**Files:**

- Modify `docs/02-architecture/contracts/*.schema.yaml`.
- Modify `schemas/*.schema.yaml`.
- Create `scripts/ci/check-schema-drift.sh`.
- Modify `scripts/ci/verify-contracts.sh` if it is the correct aggregate gate.
- Modify `.github/workflows/ci.yml`.
- Modify `.github/workflows/release.yml`.
- Add or modify schema drift tests if shell-only verification is insufficient.

- [x] Step 1: Decide canonical source.

Use this policy:

```text
docs/02-architecture/contracts/*.schema.yaml is canonical public contract source.
schemas/*.schema.yaml is the runtime/packaged copy.
usage-kit schemas are copied from the same canonical content.
```

- [x] Step 2: Align schema filenames.

Required set:

```text
env_profile.v0.2.schema.yaml
environment_binding.v0.2.schema.yaml
evidence_index.v0.2.schema.yaml
execution_profile.v0.2.schema.yaml
provider_contract.v0.2.schema.yaml
provider_instance.v0.2.schema.yaml
result.v0.2.schema.yaml
suite_manifest.v0.2.schema.yaml
test_case_dsl.v0.2.schema.yaml
```

If `evidence.v0.2.schema.yaml` and `run_profile.v0.2.schema.yaml` remain, mark them as legacy/derived or remove them from the drift gate with an explicit allowlist.

- [x] Step 3: Align schema content.

Start with known drift files:

```text
env_profile.v0.2.schema.yaml
result.v0.2.schema.yaml
test_case_dsl.v0.2.schema.yaml
evidence_index.v0.2.schema.yaml
```

- [x] Step 4: Add `scripts/ci/check-schema-drift.sh`.

Behavior:

```text
exit 0 when schema filenames and content match canonical policy
exit 1 with owner-actionable file list when drift exists
does not require rg to be installed; fallback to grep/find/diff
```

- [x] Step 5: Add CI wiring.

Commands:

```bash
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
```

Expected: both pass locally and in CI.

- [x] Step 6: Commit.

```bash
git add docs/02-architecture/contracts schemas scripts/ci/check-schema-drift.sh scripts/ci/verify-contracts.sh .github/workflows
git commit -m "fix: enforce schema contract drift gate"
```

### Task 6: Add Root Onboarding and Update Agent Guide

**Files:**

- Create `README.md`.
- Create `SECURITY.md`.
- Create `CONTRIBUTING.md`.
- Create `RELEASE.md`.
- Create `SUPPORT.md`.
- Create `LICENSE` and `NOTICE` if license decision is available.
- Modify `AGENTS.md`.

- [x] Step 1: Add root README.

Required sections:

```text
What this framework solves
5-minute quick start
Framework public interface
Provider support matrix link
Usage kit vs framework source
Evidence boundary and release evidence rule
External runtime setup links
How to extend providers
Release verification summary
```

- [x] Step 2: Add governance docs.

Minimum:

```text
SECURITY.md: vulnerability reporting, supported versions, secret handling
CONTRIBUTING.md: branch, test, commit, PR evidence rules
RELEASE.md: release steps and release artifact acceptance
SUPPORT.md: supported provider/status policy
```

- [x] Step 3: Update `AGENTS.md`.

Remove:

```text
starter workspace
specs/
target/spec-driven-auto-regression-0.2.0.jar check-readiness
```

Replace with current commands:

```bash
./mvnw test
./mvnw verify
java -jar target/spec-driven-auto-regression-0.2.7.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar target/spec-driven-auto-regression-0.2.7.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden
java -jar target/spec-driven-auto-regression-0.2.7.jar report --result <generated_result_json>
```

- [x] Step 4: Run docs hygiene.

```bash
rg -n "starter workspace|0\\.2\\.0|specs/" README.md AGENTS.md SECURITY.md CONTRIBUTING.md RELEASE.md SUPPORT.md
```

Expected: no stale matches.

- [x] Step 5: Commit.

```bash
git add README.md SECURITY.md CONTRIBUTING.md RELEASE.md SUPPORT.md AGENTS.md LICENSE NOTICE
git commit -m "docs: add framework onboarding and contribution guide"
```

### Task 7: Improve Usage Kit Adoption Docs and Legacy Warnings

**Files:**

- Modify `scripts/release/build-usage-kit.sh`.
- Modify `scripts/release/verify-usage-kit.sh`.
- Create source docs that the usage kit packaging script copies:
  - `docs/09-operations/quickstart.md` or equivalent source path
  - `docs/09-operations/troubleshooting.md`
  - `docs/09-operations/driver_setup.md`
  - `docs/09-operations/external_runtime_setup.md`
- Modify `samples/README.md` if legacy warning policy is documented there.

- [x] Step 1: Add usage-kit `QUICKSTART.md`.

Must include:

```bash
java -jar ../spec-driven-auto-regression-0.2.7.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar ../spec-driven-auto-regression-0.2.7.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden
java -jar ../spec-driven-auto-regression-0.2.7.jar report --result <generated_result_json> --format text
java -jar ../spec-driven-auto-regression-0.2.7.jar report --result <generated_result_json> --format json
```

- [x] Step 2: Add `DRIVER_SETUP.md`.

Must include Oracle and DB2 examples:

```bash
JDBC_CONNECTION='<oracle-jdbc-url>' java -jar ../spec-driven-auto-regression-0.2.7.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml \
  --profile external_jdbc_oracle_env_secret_ref \
  --driver-path ./drivers/oracle/ojdbc11.jar

JDBC_CONNECTION='<db2-jdbc-url>' java -jar ../spec-driven-auto-regression-0.2.7.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml \
  --profile external_jdbc_db2_env_secret_ref \
  --driver-path ./drivers/db2/jcc.jar
```

- [x] Step 3: Add `EXTERNAL_RUNTIME_SETUP.md`.

Provider sections:

```text
JDBC Oracle
JDBC DB2
Kafka
IBM MQ
NATS
REST endpoint
gRPC endpoint
```

Each section must include required env vars, external dependency, validation command, run command, evidence boundary, and common failure codes.

- [x] Step 4: Add legacy sample warnings.

Generated usage-kit warning files:

```text
samples/LEGACY_COMPATIBILITY_README.md
samples/golden_e2e/DEPRECATED_PATH.md
samples/provider_capability/DEPRECATED_PATH.md
samples/contract_baseline/DEPRECATED_PATH.md
samples/evidence_hardening/DEPRECATED_PATH.md
```

Warning text:

```text
This path is retained for v0.2.x compatibility. New suites should use canonical samples under samples/00-getting-started, samples/10-contract-baseline, samples/20-provider-capability-p0, samples/30-cross-provider-groups, or samples/40-evidence-reporting.
```

- [x] Step 5: Verify usage kit.

```bash
scripts/release/build-usage-kit.sh 0.2.7
scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.2.7-usage-kit.zip
```

Expected: quickstart docs, driver placeholders, and warning files exist in the usage kit.

- [x] Step 6: Commit.

```bash
git add scripts/release/build-usage-kit.sh scripts/release/verify-usage-kit.sh docs samples/README.md
git commit -m "docs: improve usage kit adoption path"
```

### Task 8: Add Provider Dependency Policy

**Files:**

- Create `docs/09-operations/provider_dependency_policy.md`.
- Modify `README.md` to link to it.
- Modify release notes source if dependency policy is summarized there.

- [x] Step 1: Add provider dependency policy.

Required policy points:

```text
Core jar may include CI-verifiable open-source clients needed by supported providers.
Oracle/DB2 vendor JDBC drivers are not bundled in public release assets.
Vendor drivers must be supplied through --driver-path, --driver-dir, REGRESS_DRIVER_PATH, or approved internal provider packs.
Dependency-Check suppressions require owner, reason, affected provider surface, expiry, and upgrade plan.
Future v0.3 may split provider packs, but v0.2.7 remains a single jar.
```

- [x] Step 2: Add dependency inventory table.

Minimum columns:

```text
dependency
provider surface
bundled in public jar
license review needed
security review owner
upgrade cadence
```

- [x] Step 3: Commit.

```bash
git add docs/09-operations/provider_dependency_policy.md README.md
git commit -m "docs: define provider dependency policy"
```

### Task 9: Release Verification

**Files:**

- Modify `pom.xml` and release metadata when version bump starts.
- Modify release notes source.
- Modify scripts only if verification finds gaps.

- [x] Step 1: Bump version and hardcoded runtime metadata.

Commands:

```bash
rg -n "0\\.2\\.6|v0\\.2\\.6" pom.xml src docs scripts samples .github AGENTS.md README.md
```

Update intentional release metadata to `0.2.7` / `v0.2.7`. Do not change historical docs unless they describe current public behavior incorrectly.

- [x] Step 2: Run local Maven verification.

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw verify
```

Expected: exit 0.

- [x] Step 3: Run release guard scripts.

```bash
scripts/release/verify-release-version.sh 0.2.7
scripts/ci/check-public-support-contract.sh
scripts/ci/check-schema-drift.sh
scripts/ci/secret-scan.sh
scripts/ci/generate-sbom.sh
scripts/release/build-usage-kit.sh 0.2.7
scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.2.7-usage-kit.zip
scripts/release/verify-supported-provider-samples.sh
```

Expected: exit 0 for all required commands.

- [ ] Step 4: Run release asset acceptance from pi-run workspace after GitHub release assets exist.

Commands must use release assets, not source archives:

```bash
cd /Users/herman_mbp2023/Documents/test_framework_pirun
python3 -m pirun.verify_release_assets --framework-version 0.2.7 --output-dir reports
python3 -m pirun.inspect_usage_kit --framework-version 0.2.7 --output-dir reports
python3 -m pirun.run_usage_kit_matrix --framework-version 0.2.7 --output-dir reports --execute --command-set 'validate,run --dry-run'
python3 pirun/run_evidence_matrix.py --framework-version 0.2.7 --output-dir reports
python3 pirun/scan_raw_secrets.py --framework-version 0.2.7 --output-dir reports reports .pirun/runs
```

Expected:

```text
release asset checksum verification PASS
usage-kit matrix 0 unexpected failures
evidence matrix includes report-valid-json PASS
raw secret scan PASS
```

- [ ] Step 5: Record release decision.

Create pi-run report:

```text
reports/pi-run-v0.2.7-release-check.md
```

Required decision language:

```text
RELEASE_FOUND_PI_RUN_EXECUTED
```

or:

```text
RELEASE_FOUND_PI_RUN_EXECUTED_WITH_FINDINGS
```

If findings remain, list severity, owner, evidence path, and whether they block v0.2.7.

## 6. Definition of Done

v0.2.7 is ready only when all P0 items meet these criteria:

- `dummy_rest` compatibility sample remains framework-only and cannot claim downstream release evidence.
- `regress report --format json` works for valid result JSON and returns owner-actionable JSON failures for invalid evidence/secret leakage.
- Project-provisioned WireMock external `base_url` consumption is proven by framework evidence and pi-run project report.
- Oracle/DB2 JDBC driver loading works through `--driver-path`, `--driver-dir`, `REGRESS_DRIVER_PATH`, and usage-kit `drivers/` fallback.
- `regress doctor drivers` reports driver presence/loadability without connecting to DB.
- Schema drift gate blocks mismatched public/runtime schemas.
- Root onboarding docs exist and no longer describe the repo as a starter workspace.
- Usage-kit contains QUICKSTART, TROUBLESHOOTING, DRIVER_SETUP, EXTERNAL_RUNTIME_SETUP, driver placeholders, and legacy path warnings.
- Release guard scripts pass.
- Maven verification passes under bounded memory.
- GitHub release assets are pi-run from `/Users/herman_mbp2023/Documents/test_framework_pirun` with no unexpected failures.

## 7. Review Checklist

- [x] Does every v0.2.6 release finding have a v0.2.7 task or explicit deferral?
- [x] Does every new CLI option appear in help, user guide, and tests?
- [x] Does every usage-kit path use canonical `env_profiles/`, `provider_instances/`, and `custom_provider_contracts/` naming?
- [x] Are Oracle/DB2 drivers excluded from release assets?
- [x] Does driver diagnostics avoid DB connections and raw secret output?
- [x] Does report JSON preserve evidence validation and secret guardrails?
- [x] Does schema drift CI work without requiring `rg`?
- [x] Are legacy sample paths visibly deprecated?
- [x] Are all release commands bounded to avoid excessive memory use?
