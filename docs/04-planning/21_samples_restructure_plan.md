# Samples Restructure Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task with review after each task. Use checkbox (`- [x]`) syntax for tracking. This is a sample layout and documentation migration, not a provider runtime change.

## 1. Goal

Restructure checked-in samples so users can distinguish getting-started samples, executable provider capability suites, suite groups, evidence/reporting samples, and compatibility-only fixtures without reading every manifest.

## 2. Architecture

Samples become a navigable usage-kit surface. A leaf suite is any directory whose `suite_manifest.yaml` owns `tests[]`; a suite group is any directory whose `suite_manifest.yaml` owns `child_suites[]`. Do not add `suite_type`; keep the current manifest contract and make the directory layout express intent.

Suite groups must own their children under the same directory tree because child refs are intentionally blocked from escaping the suite group directory.

## 3. Target Structure

```text
samples/
  00-getting-started/
    golden_e2e/
  10-contract-baseline/
    mixed_wiremock_jdbc_nats/
  20-provider-capability-p0/
    suite_manifest.yaml
    http/
      wiremock_http_mock/
      rest_client_with_wiremock/
    rpc/
      soap_mock/
      grpc_mock/
    data/
      jdbc/
    messaging/
      nats/
      kafka/
      ibm_mq/
      kafka_ibm_mq_mixed/
    verification/
      common_verify/
      artifact_compare/
      polling_observer/
  30-cross-provider-groups/
    mock_server_cross_verify/
      suite_manifest.yaml
      rest_wiremock_http/
      soap_mock_http_client/
      grpc_mock_grpc_client/
  40-evidence-reporting/
    evidence_hardening/
  90-compatibility/
    dummy_rest/
```

Runtime-mode coverage provider instances stay beside executable provider instances but use explicit names and labels:

```text
provider_instances/
  payment_api_client.yaml
  runtime_mode_sample__payment_api_client_mock.yaml
  runtime_mode_sample__payment_api_client_stub.yaml
```

These files must include `sample_scope: usage_kit_runtime_mode_sample` and must not be referenced by executable test case targets unless a future task intentionally makes them executable.

## 4. Migration Mapping

| Current Path | Target Path |
|---|---|
| `samples/golden_e2e/` | `samples/00-getting-started/golden_e2e/` |
| `samples/contract_baseline/` | `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` |
| `samples/provider_capability/suite_manifest.yaml` | `samples/20-provider-capability-p0/suite_manifest.yaml` |
| `samples/provider_capability/wiremock/` | `samples/20-provider-capability-p0/http/wiremock_http_mock/` |
| `samples/provider_capability/wiremock_http_request/` | `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` |
| `samples/provider_capability/soap_mock/` | `samples/20-provider-capability-p0/rpc/soap_mock/` |
| `samples/provider_capability/grpc_mock/` | `samples/20-provider-capability-p0/rpc/grpc_mock/` |
| `samples/provider_capability/jdbc/` | `samples/20-provider-capability-p0/data/jdbc/` |
| `samples/provider_capability/nats/` | `samples/20-provider-capability-p0/messaging/nats/` |
| `samples/provider_capability/kafka/` | `samples/20-provider-capability-p0/messaging/kafka/` |
| `samples/provider_capability/ibm_mq/` | `samples/20-provider-capability-p0/messaging/ibm_mq/` |
| `samples/provider_capability/messaging_mixed/` | `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/` |
| `samples/provider_capability/common_verify/` | `samples/20-provider-capability-p0/verification/common_verify/` |
| `samples/provider_capability/compare/` | `samples/20-provider-capability-p0/verification/artifact_compare/` |
| `samples/provider_capability/polling/` | `samples/20-provider-capability-p0/verification/polling_observer/` |
| `samples/provider_capability/mock_server_cross_verify/` | `samples/30-cross-provider-groups/mock_server_cross_verify/` |
| `samples/evidence_hardening/` | `samples/40-evidence-reporting/evidence_hardening/` |
| `samples/provider_capability/dummy_rest/` | `samples/90-compatibility/dummy_rest/` |

Within moved manifests, update `child_suites[].ref` only when the child moved relative to the group root. Leaf suite internals should keep local refs such as `fixtures/...`, `expected_results/...`, and `provider_instances/...`.

## 5. Compatibility Strategy

Source tree should contain only canonical paths after the migration. To avoid breaking consumers immediately, `scripts/release/build-usage-kit.sh` should generate legacy path copies inside the usage-kit zip for one compatibility release.

Rules:

- Public docs point to canonical paths only.
- Release notes include a legacy-path deprecation table.
- Generated legacy copies are not checked into git.
- `scripts/release/verify-usage-kit.sh` validates both canonical paths and generated legacy paths while compatibility is enabled.
- `dummy_rest` is verified only as a compatibility sample, not as part of the supported provider sample gate.
- Remove generated legacy copies in the next breaking sample-layout release.

Suggested generated legacy mapping. Treat the left column as the generated compatibility path inside the release zip and the right column as the canonical source path to copy from. Do not implement this as a single shallow copy from `20-provider-capability-p0` to `provider_capability`; synthesize the old root from the canonical provider capability, cross-provider group, and compatibility roots so every old release-asset path still resolves.

| Generated Legacy Path | Canonical Source Path |
|---|---|
| `usage-kit/samples/golden_e2e/` | `usage-kit/samples/00-getting-started/golden_e2e/` |
| `usage-kit/samples/contract_baseline/` | `usage-kit/samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` |
| `usage-kit/samples/provider_capability/` | `usage-kit/samples/20-provider-capability-p0/` |
| `usage-kit/samples/provider_capability/mock_server_cross_verify/` | `usage-kit/samples/30-cross-provider-groups/mock_server_cross_verify/` |
| `usage-kit/samples/provider_capability/dummy_rest/` | `usage-kit/samples/90-compatibility/dummy_rest/` |
| `usage-kit/samples/evidence_hardening/` | `usage-kit/samples/40-evidence-reporting/evidence_hardening/` |

## 6. Files To Update

Sample files:

- Move all directories listed in the migration mapping using `git mv`.
- Rename runtime-mode sample provider instance files under the new HTTP, gRPC, and polling directories.
- Add `samples/README.md` with a short map of sample categories and the leaf/group distinction.

Scripts:

- `scripts/ci/verify-contracts.sh`
- `scripts/release/build-usage-kit.sh`
- `scripts/release/verify-supported-provider-samples.sh`
- `scripts/release/verify-usage-kit.sh`
- `scripts/ci/secret-scan.sh` only if path allow/deny patterns assume old sample roots.
- `/Users/herman_mbp2023/Documents/test_framework_pirun/pirun/inspect_usage_kit.py` only if its sample path classification depends on old directory names instead of recursive `suite_manifest.yaml` discovery.

Docs and user-facing contracts:

- `docs/09-operations/test_framework_user_guide.md`
- `docs/09-operations/provider_support_matrix.md`
- `docs/08-release/framework/framework_release_readiness.md`
- `docs/07-validation-evidence/07_regression_test_plan.md`
- `docs/07-validation-evidence/10_evidence_matrix.md`
- `docs/03-acceptance/04_acceptance_criteria.md`
- `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`
- `docs/02-architecture/contracts/p0_provider_verify_catalog.v0.2.md`

Tests with hard-coded sample paths:

- `src/test/java/com/specdriven/regression/cli/GoldenE2eCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/ContractBaselineCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/WireMockProviderCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/WireMockHttpRequestSampleCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/SoapMockCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/GrpcMockCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/JdbcProviderCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/NatsProviderCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/MessagingClientProviderCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/CommonVerifyCapabilityCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/MockServerCrossVerifySampleCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/EvidenceHardeningCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/V022SuiteModeAcceptanceCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/V023SuiteModeFrameworkFixCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/RegressionCommandV024BoundaryTest.java`
- `src/test/java/com/specdriven/regression/integration/PackagedCliSmokeIT.java`
- `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java`
- `src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java`
- `src/test/java/com/specdriven/regression/report/ReportAndEvidenceCommandTest.java`

## 7. Implementation Tasks

### Task 1: Add Sample Layout Contract Tests

- [x] Add a test class `src/test/java/com/specdriven/regression/release/SampleLayoutContractTest.java`.
- [x] Assert these canonical directories exist:
  - `samples/00-getting-started/golden_e2e`
  - `samples/10-contract-baseline/mixed_wiremock_jdbc_nats`
  - `samples/20-provider-capability-p0`
  - `samples/30-cross-provider-groups/mock_server_cross_verify`
  - `samples/40-evidence-reporting/evidence_hardening`
  - `samples/90-compatibility/dummy_rest`
- [x] Assert every suite group manifest has `child_suites[]` and no `tests[]`.
- [x] Assert every leaf suite manifest has `tests[]` and no `child_suites[]`.
- [x] Assert no group child `ref` starts with `../` or escapes its group directory.
- [x] Assert files labeled `sample_scope: usage_kit_runtime_mode_sample` are not referenced by any `targets.*.provider_id` in checked-in test cases.

Run:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -B -ntp -Dtest=SampleLayoutContractTest test
```

Expected first run: fail because canonical directories do not exist yet.

### Task 2: Move Samples To Canonical Paths

- [x] Use `git mv` for every path in the migration mapping.
- [x] Rename runtime-mode sample provider instances:
  - `payment_api_client_mock.yaml` -> `runtime_mode_sample__payment_api_client_mock.yaml`
  - `payment_api_client_stub.yaml` -> `runtime_mode_sample__payment_api_client_stub.yaml`
  - `customer_grpc_client_mock.yaml` -> `runtime_mode_sample__customer_grpc_client_mock.yaml`
  - `customer_grpc_client_stub.yaml` -> `runtime_mode_sample__customer_grpc_client_stub.yaml`
  - `local_polling_observer_ephemeral.yaml` -> `runtime_mode_sample__local_polling_observer_ephemeral.yaml`
- [x] Update `samples/20-provider-capability-p0/suite_manifest.yaml` child refs to point at the new child locations under the same group root.
- [x] Update `samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml` child refs to point at its local children.

Run:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -B -ntp -Dtest=SampleLayoutContractTest test
```

Expected: sample layout contract tests pass.

### Task 3: Update Release and CI Scripts

- [x] Replace old sample paths in `scripts/ci/verify-contracts.sh`.
- [x] Replace old sample paths in `scripts/release/verify-supported-provider-samples.sh`.
- [x] Replace old sample paths in `scripts/release/verify-usage-kit.sh`.
- [x] Update `scripts/release/build-usage-kit.sh` to copy canonical sample roots.
- [x] Add generated legacy path copy logic to `scripts/release/build-usage-kit.sh`.
- [x] Add `samples/README.md` to the usage-kit zip and to `verify-usage-kit.sh` required paths.
- [x] Move `dummy_rest` out of the supported provider sample gate. If it remains verified, run it under an explicit compatibility sample verification section with compatibility wording in stdout.
- [x] Add usage-kit manifest fields:
  - `sample_layout_version: v2`
  - `canonical_sample_roots`
  - `generated_legacy_sample_roots`
  Write these fields to `usage-kit/usage-kit-manifest.yaml` in the generated zip.

Run:

```bash
bash scripts/ci/verify-contracts.sh
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -B -ntp package
bash scripts/release/build-usage-kit.sh 0.2.5
bash scripts/release/verify-usage-kit.sh target/spec-driven-auto-regression-0.2.5-usage-kit.zip
```

Expected: all commands pass, and the generated usage kit contains canonical paths plus legacy copies, including `samples/README.md`, `samples/provider_capability/mock_server_cross_verify/`, and `samples/provider_capability/dummy_rest/`. Inspect the manifest with:

```bash
unzip -p target/spec-driven-auto-regression-0.2.5-usage-kit.zip usage-kit/usage-kit-manifest.yaml
```

Expected manifest fields:

```yaml
sample_layout_version: v2
canonical_sample_roots:
  - samples/00-getting-started
  - samples/10-contract-baseline
  - samples/20-provider-capability-p0
  - samples/30-cross-provider-groups
  - samples/40-evidence-reporting
  - samples/90-compatibility
generated_legacy_sample_roots:
  - samples/golden_e2e
  - samples/contract_baseline
  - samples/provider_capability
  - samples/evidence_hardening
```

### Task 4: Update Java Tests

- [x] Replace hard-coded paths in the test files listed in Section 6.
- [x] Prefer constants grouped by sample family, for example `PROVIDER_CAPABILITY_ROOT = Path.of("samples/20-provider-capability-p0")`.
- [x] Keep tests that assert checked-in artifact paths, but point them at canonical paths.
- [x] Keep `ReleaseUsageKitVerificationTest.usageKitSamplesCoverSupportedRuntimeModeRows` scanning the canonical provider capability root.

Run:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -B -ntp -Dtest=GoldenE2eCommandTest,ContractBaselineCommandTest,WireMockProviderCapabilityCommandTest,WireMockHttpRequestSampleCommandTest,SoapMockCapabilityCommandTest,GrpcMockCapabilityCommandTest,JdbcProviderCapabilityCommandTest,NatsProviderCapabilityCommandTest,MessagingClientProviderCapabilityCommandTest,CommonVerifyCapabilityCommandTest,MockServerCrossVerifySampleCommandTest,EvidenceHardeningCommandTest,ReleaseUsageKitVerificationTest test
```

Expected: all listed tests pass.

### Task 5: Update User Guide and Support Docs

- [x] Update all commands in `docs/09-operations/test_framework_user_guide.md` to canonical paths.
- [x] Update `docs/09-operations/provider_support_matrix.md` release-verifiable sample paths.
- [x] Update `docs/02-architecture/contracts/framework_usage_interface.v0.2.md` stable artifact locations.
- [x] Update `docs/02-architecture/contracts/p0_provider_verify_catalog.v0.2.md` sample references.
- [x] Update AC and test plan references in `docs/03-acceptance/04_acceptance_criteria.md` and `docs/07-validation-evidence/07_regression_test_plan.md`.
- [x] Add a short "Sample Layout" subsection explaining:
  - leaf suite = `tests[]`
  - suite group = `child_suites[]`
  - suite group child refs must stay inside the group directory
  - runtime-mode contract samples are coverage artifacts, not executable targets

Run:

```bash
rg -n "samples/(golden_e2e|contract_baseline|provider_capability|evidence_hardening)" \
  docs/09-operations \
  docs/08-release/framework \
  docs/07-validation-evidence \
  docs/03-acceptance \
  docs/02-architecture/contracts \
  scripts \
  src/test/java
```

Expected: no active user guide, support docs, or Java tests point users at old canonical paths. The only remaining old-path matches are release-note/readiness deprecation tables or explicit compatibility-generation and compatibility-verification blocks in release scripts that prove generated legacy usage-kit paths still work for one release.

### Task 6: Verify End To End

- [x] Run contract and sample verification:

```bash
bash scripts/ci/check-public-support-contract.sh
bash scripts/ci/verify-contracts.sh
bash scripts/ci/secret-scan.sh
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' bash scripts/release/verify-supported-provider-samples.sh 0.2.5
```

- [x] Run usage-kit matrix verification with the external pi-run inspector against the generated usage kit.

Refresh the external pi-run usage kit from the generated zip, then run the matrix:

```bash
rm -rf /Users/herman_mbp2023/Documents/test_framework_pirun/usage-kit-v0.2.5
mkdir -p /Users/herman_mbp2023/Documents/test_framework_pirun/usage-kit-v0.2.5
mkdir -p /Users/herman_mbp2023/Documents/test_framework_pirun/release-assets-v0.2.5
unzip -q target/spec-driven-auto-regression-0.2.5-usage-kit.zip \
  -d /Users/herman_mbp2023/Documents/test_framework_pirun/usage-kit-v0.2.5
cp target/spec-driven-auto-regression-0.2.5.jar \
  /Users/herman_mbp2023/Documents/test_framework_pirun/release-assets-v0.2.5/spec-driven-auto-regression-0.2.5.jar
cd /Users/herman_mbp2023/Documents/test_framework_pirun
python3 -m pirun.inspect_usage_kit --framework-version 0.2.5
python3 -m pirun.run_usage_kit_matrix --framework-version 0.2.5 --command-set validate
python3 - <<'PY'
import json
from pathlib import Path

matrix = json.loads(Path("reports/pi-run-v0.2.5-function-coverage-matrix.json").read_text())
counts = {}
for row in matrix["matrix_rows"]:
    counts[row["expected_status"]] = counts.get(row["expected_status"], 0) + 1
print("expected_status_counts:", counts)
assert counts.get("BLOCKED_USAGE_KIT_SAMPLE_GAP", 0) == 0, counts
PY
```

Expected matrix result:

```text
BLOCKED_USAGE_KIT_SAMPLE_GAP: 0
```

- [x] Run full verification:

```bash
JAVA_TOOL_OPTIONS='-Xmx1024m' MAVEN_OPTS='-Xmx2g' ./mvnw -B -ntp verify
```

Expected: Surefire and Failsafe pass.

## 8. Acceptance Criteria

- Canonical sample layout exists and is documented in `samples/README.md`.
- Suite group and leaf suite semantics are enforced by tests.
- No checked-in suite group has escaping child refs.
- Canonical docs and scripts use the new sample paths.
- Generated usage kit keeps one-release legacy path compatibility, including legacy `mock_server_cross_verify` and `dummy_rest` paths.
- `dummy_rest` is compatibility-only and is not counted as a supported provider sample.
- Runtime-mode coverage samples are clearly labeled and not executed accidentally.
- `BLOCKED_USAGE_KIT_SAMPLE_GAP` remains `0`.
- Full Maven verify passes under the 8 GB memory safety rule.

## 9. Risks and Decisions

- Keeping legacy paths in source tree would preserve compatibility but keep the repo messy. This plan avoids that by generating legacy copies only in the usage-kit zip.
- Moving sample paths is a public usage-kit change. Release notes must call this out even if legacy copies are generated.
- `dummy_rest` is compatibility-only and should not be promoted as a public provider capability sample.
- Do not add `suite_type`; the current `tests[]` versus `child_suites[]` contract is enough.
