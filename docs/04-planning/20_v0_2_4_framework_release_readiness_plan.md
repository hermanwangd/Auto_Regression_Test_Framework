# Framework v0.2.4 Release Readiness Implementation Plan

> For agentic workers: implement this plan task-by-task with review after each task. v0.2.4 is a framework release truthfulness and runtime completion release, not a new architecture planning round.

## 1. Goal

Ship v0.2.4 with a truthful public provider support model, complete Kafka and IBM MQ client runtimes, release-verifiable samples, report/evidence coverage, and clear release integrity metadata.

## 2. Scope

In scope:

- Canonical suite-mode CLI only: `validate`, `run`, `run --dry-run`, `report`, and `validate-evidence`.
- Public provider support represented only by `support_status`.
- Kafka and IBM MQ marked `supported` only after executable suite-mode release verification passes.
- `report --format text`, `report --format yaml`, and `validate-evidence` release verification.
- `report --format json` returns usage error exit `2`; JSON report is not a v0.2.4 public contract.
- Evidence/report secret guardrails before publication.
- Release asset checksum, raw detached signature, and explicit certificate/provenance limitation metadata.

Out of scope:

- Framework-owned Docker/Testcontainers provisioning.
- Project-side release-validation orchestration wrappers.
- Product/RP/RU topology interpretation.
- Release governance, waiver workflow, Allure, ReportPortal, dashboard, Phase 2 Agent Skill.

## 3. Public Interface Decision

Use this provider support model everywhere:

| `support_status` | Meaning |
|---|---|
| `supported` | Public contract exists, runtime exists, executable usage-kit sample exists, and release verification passes. |
| `contract_only` | Public contract exists, but runtime execution is unavailable; validation blocks before dispatch. |
| `deprecated` | Compatibility-only provider or alias; new artifacts and samples must not use it. |
| `unsupported` | Not part of the public provider capability surface. |

`native`, `ephemeral`, `framework_managed`, and `external` are server/dependency lifecycle descriptions, not public provider support modes.

## 4. File Impact Map

Documentation and contracts:

- Modify `docs/01-specs/03_feature_specs.md`.
- Modify `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`.
- Modify `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`.
- Modify provider contracts under `docs/02-architecture/contracts/provider-contracts/`.
- Modify `docs/09-operations/provider_support_matrix.md`.
- Modify `docs/09-operations/test_framework_user_guide.md`.
- Modify `docs/03-acceptance/04_acceptance_criteria.md`.

Runtime and CLI:

- Create or modify `scripts/ci/check-public-support-contract.sh`.
- Modify `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`.
- Modify `src/main/java/com/specdriven/regression/contract/FrameworkProviderContractCatalog.java`.
- Modify `src/main/java/com/specdriven/regression/provider/ProviderCapabilityRegistry.java`.
- Modify `src/main/java/com/specdriven/regression/report/CoverageReportService.java`.
- Modify `src/main/java/com/specdriven/regression/evidence/EvidenceHardeningService.java`.
- Modify `src/main/java/com/specdriven/regression/provider/kafka/KafkaProviderRuntime.java`.
- Modify `src/main/java/com/specdriven/regression/provider/ibmmq/IbmMqProviderRuntime.java`.

Samples and release verification:

- Modify `samples/provider_capability/kafka/**`.
- Modify `samples/provider_capability/ibm_mq/**`.
- Modify `samples/provider_capability/messaging_mixed/**`.
- Modify `samples/provider_capability/mock_server_cross_verify/**` for WireMock external `base_url` support evidence.
- Create `samples/provider_capability/release_verification/` for shared release command fixtures, expected report outputs, and evidence-validation fixtures.
- Modify `.github/workflows/release.yml` if release verification commands are missing.
- Modify release-note generation inputs or a checked-in release-note template. Do not edit generated `target/release-notes.md` directly.

Tests to add or update:

- `src/test/java/com/specdriven/regression/cli/RegressionCommandV024BoundaryTest.java`.
- `src/test/java/com/specdriven/regression/provider/ProviderSupportStatusTest.java`.
- `src/test/java/com/specdriven/regression/contract/ProviderSupportMatrixConsistencyTest.java`.
- `src/test/java/com/specdriven/regression/provider/wiremock/WireMockExternalBaseUrlSupportTest.java`.
- `src/test/java/com/specdriven/regression/report/ReportAndEvidenceCommandTest.java`.
- `src/test/java/com/specdriven/regression/provider/kafka/KafkaProviderRuntimeTest.java`.
- `src/test/java/com/specdriven/regression/provider/ibmmq/IbmMqProviderRuntimeTest.java`.
- `src/test/java/com/specdriven/regression/provider/messaging/MessagingReleaseEnvironmentTest.java`.
- `src/test/java/com/specdriven/regression/release/ReleaseUsageKitVerificationTest.java`.

## 5. Implementation Tasks

### Task 1: Lock CLI Boundary

Remove public RP-mode from help, docs, and release verification. The framework CLI public release surface must expose only the canonical command set. Any remaining implementation compatibility command is internal, is not a release gate, must not appear in help or usage-kit verification, and must not be used as v0.2.4 runtime evidence.

Required behavior:

- `--help` lists only `validate`, `run`, `report`, and `validate-evidence`; `run --help` documents `--dry-run`.
- Direct Product/RP runtime orchestration is not a framework public command. Product/RP tooling must produce suite-mode artifacts before invoking `validate`, `run`, `report`, or `validate-evidence`.
- Unknown non-canonical command names exit `2` through the generic unknown-command path. Legacy compatibility commands, if still present in implementation code for older tests, remain outside the v0.2.4 public runtime interface and release gate.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=RegressionCommandV024BoundaryTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar --help
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --help
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar not-a-command
```

### Task 2: Enforce `support_status`

Add one source of truth for provider status and reject drift across registry, contracts, support matrix, and samples.

Required behavior:

- `docs/01-specs/03_feature_specs.md` defines the same public `support_status` vocabulary used by the registry, support matrix, user guide, AC, and tests.
- `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml` exposes public `support_status`; public docs and release gates must not expose `runtime_status` as the support contract.
- Runtime lifecycle details may remain only as internal execution metadata, for example `runtime_capabilities` or `execution_modes`, and must not be used as public support status.
- `docs/09-operations/provider_support_matrix.md` is converted from Native/Mock/Ephemeral columns to provider-level rows keyed by `provider_type` and `support_status`.
- Registry provider has a matching Provider Contract.
- Provider Contract has a registry entry unless marked internal or deprecated.
- `contract_only` providers are blocked before runtime dispatch.
- `deprecated` providers are rejected in new samples.
- `supported` providers require executable usage-kit samples and passing release verification.
- `kafka_messaging` is `deprecated`; new samples use `kafka`.

Migration rules:

| Existing public value | v0.2.4 `support_status` |
|---|---|
| `supported` with executable sample and passing release verification | `supported` |
| `partial` without executable release verification | `contract_only` |
| `partial` for Kafka / IBM MQ after runtime and release samples pass | `supported` |
| `deprecated_alias` | `deprecated` |
| `approved_escape_hatch_only` without release-verifiable sample and safety approval | `contract_only` |
| Missing contract or hidden/internal-only capability | `unsupported` or internal-only, not published as supported |

Acceptance fields:

```text
registry_without_contract = []
support_matrix_conflicts = []
contract_only_runtime_dispatch = []
deprecated_alias_used_by_new_samples = []
supported_provider_claims_without_usage_kit_sample = []
supported_provider_claims_with_failed_release_verification = []
```

Validation command:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=ProviderSupportStatusTest,ProviderSupportMatrixConsistencyTest
scripts/ci/check-public-support-contract.sh
```

### Task 3: Support Project-provisioned WireMock External `base_url`

Keep framework-managed `wiremock_http_mock` supported and add explicit support for project-provisioned external WireMock `base_url`. The framework does not provision the external WireMock server, but it must consume the project-provided binding and prove that requests were sent to that URL.

Required behavior:

- Framework-managed WireMock samples continue to pass.
- `docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml` defines an optional `base_url` binding key for external connection:
  - `value_type: uri`
  - `allowed_value_kinds: [value, generated_ref]`
  - `allowed_schemes: [http, https]`
  - raw userinfo, password, token, authorization query params, and `secret_ref` values are rejected for `base_url`.
- `connect_mock` consumes `mock.base_url` and must not call `start_mock`.
- Env_Profile or Environment Binding can supply `base_url` as a static value or predefined generated value.
- When external `base_url` is supplied, the runtime must not start a framework-managed WireMock instance for that provider.
- The paired HTTP client provider must resolve its target URL from the external `base_url`.
- Evidence must prove the consumed `base_url`, request URL, response status, and provider IDs.
- Missing, malformed, or secret-bearing `base_url` fails validation before provider dispatch with an owner-actionable error.
- Release verification uses a project-provisioned local WireMock process started by the verification harness before `regress run`; the framework consumes only the resulting `base_url`.

Failure codes:

| Failure | Code |
|---|---|
| external WireMock selected but no `base_url` supplied | `WIREMOCK_EXTERNAL_BASE_URL_MISSING` |
| URI is malformed or scheme is not `http` / `https` | `WIREMOCK_EXTERNAL_BASE_URL_INVALID` |
| URI contains userinfo or secret-like query params | `WIREMOCK_EXTERNAL_BASE_URL_SECRET_LEAK` |
| request cannot reach supplied endpoint | `WIREMOCK_EXTERNAL_CONNECTION_FAILED` |

Required evidence:

```yaml
provider_id: wiremock_payment_api
provider_type: wiremock_http_mock
support_status: supported
framework_started_wiremock: false
external_base_url_consumed: true
consumed_binding_keys: [base_url]
request_url: http://127.0.0.1:<port>/payments
http_status: 200
```

Validation command:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=WireMockExternalBaseUrlSupportTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml
```

### Task 4: Complete Report and Evidence Release Coverage

Add release verification for `report` and `validate-evidence`.

Required behavior:

- `validate-evidence --result <valid_result_json>` exits `0`.
- Invalid evidence index, missing evidence file, raw secret, or bad result ref exits `1`.
- `report --format text` exits `0` for valid result and `1` for invalid result.
- `report --format yaml` exits `0` for valid result and `1` for invalid result.
- `report --format json` exits `2` with an unsupported format message.

Required fixtures:

- Valid result and evidence index.
- Missing evidence file.
- Malformed result JSON.
- Schema-invalid result JSON.
- Inconsistent `test_count` vs `test_results`.
- Raw secret in provider evidence.
- Blocked provider result.
- Failed assertion result.

Validation command:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=ReportAndEvidenceCommandTest
```

### Task 5: Implement Kafka Client Runtime

Complete Kafka as a client provider runtime. The framework consumes project-provided bindings and does not start a Kafka server.

Required operations:

- Publish message.
- Observe or consume message.
- Match payload.
- Support timeout and polling.
- Emit masked evidence.
- Return owner-actionable connection, timeout, and assertion failures.

Required binding inputs:

- Bootstrap servers or connection URL from Env_Profile / Environment Binding, typically `env://KAFKA_BOOTSTRAP_SERVERS` for release verification.
- Topic.
- Consumer group when observation requires it.
- Security protocol, SASL, schema registry, and header/partition controls are future contract extensions; v0.2.4 supports only the client baseline fields in the Provider Contract.

Contract and sample changes:

- Update `docs/02-architecture/contracts/provider-contracts/kafka.yaml` so the v0.2.4 executable runtime includes client execution against externally supplied broker bindings.
- Remove `native` from `contract_only_runtime_modes` when the native client runtime passes release verification.
- Keep server lifecycle labels such as `native` or `ephemeral` out of the public support matrix; they remain execution-profile details only.
- Add or update `samples/provider_capability/kafka/env_profiles/ci_kafka_external.yaml` with:
  - `runtime_mode: native`
  - `bootstrap_servers.secret_ref` or `bootstrap_servers.generated_ref`
  - `topic`
  - `consumer_group`
  - timeout and poll interval.
- Keep mock or in-memory samples only as framework capability fixtures; they do not prove `support_status: supported`.

Release verification environment:

- The release workflow or release verification harness may provision Kafka outside the framework process.
- The framework jar must only consume the resolved broker binding and must not start Kafka itself.
- If `KAFKA_BOOTSTRAP_SERVERS` or equivalent binding is missing, Kafka cannot be published as `supported` in v0.2.4.
- Unit tests may use a fake transport, but the release gate requires a real broker or a release-approved external broker endpoint.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=KafkaProviderRuntimeTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/provider_capability/kafka/suite_manifest.yaml
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite samples/provider_capability/kafka/suite_manifest.yaml --profile ci_kafka_external
```

Release gate:

```text
kafka.support_status = supported
kafka.release_usage_kit_sample = pass
kafka.validate = pass
kafka.run = pass
kafka.report = pass
kafka.validate_evidence = pass
```

### Task 6: Implement IBM MQ Client Runtime

Complete IBM MQ as a client provider runtime. The framework consumes project-provided bindings and does not start an IBM MQ server.

Required operations:

- Put message.
- Browse or observe message without destructive queue get.
- Match payload.
- Support timeout and polling.
- Emit masked evidence.
- Return owner-actionable connection, timeout, and assertion failures.

Required binding inputs:

- Connection name or host/port from Env_Profile / Environment Binding, typically `env://IBM_MQ_CONN_NAME` for release verification.
- Queue manager, channel, and queue.
- Credentials through secret refs only.
- CCDT/TLS client configuration, message selectors, headers, and arbitrary properties remain future work.

Contract and sample changes:

- Update `docs/02-architecture/contracts/provider-contracts/ibm_mq.yaml` so the v0.2.4 executable runtime includes client execution against externally supplied queue-manager bindings.
- Remove `native` from `contract_only_runtime_modes` when the native client runtime passes release verification.
- Keep server lifecycle labels such as `native` or `ephemeral` out of the public support matrix; they remain execution-profile details only.
- Add or update `samples/provider_capability/ibm_mq/env_profiles/ci_ibm_mq_external.yaml` with:
  - `runtime_mode: native`
  - `queue_manager`
  - `channel`
  - `conn_name`
  - `queue`
  - `credential.secret_ref`
  - timeout and poll interval.
- Keep mock or in-memory samples only as framework capability fixtures; they do not prove `support_status: supported`.

Release verification environment:

- The release workflow or release verification harness may provision IBM MQ outside the framework process.
- The framework jar must only consume the resolved queue-manager binding and must not start IBM MQ itself.
- If IBM MQ connection and credential refs are missing, IBM MQ cannot be published as `supported` in v0.2.4.
- Unit tests may use a fake transport, but the release gate requires a real IBM MQ queue manager or a release-approved external queue-manager endpoint.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=IbmMqProviderRuntimeTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/provider_capability/ibm_mq/suite_manifest.yaml
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite samples/provider_capability/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external
```

Release gate:

```text
ibm_mq.support_status = supported
ibm_mq.release_usage_kit_sample = pass
ibm_mq.validate = pass
ibm_mq.run = pass
ibm_mq.report = pass
ibm_mq.validate_evidence = pass
```

Skipping external Kafka or IBM MQ verification is allowed only for local smoke diagnosis. A release promotion run must set `REQUIRE_EXTERNAL_MESSAGING=true`; `ALLOW_EXTERNAL_MESSAGING_SKIP=true` produces `blocked_external_messaging_skipped` and is not a promotable gate result.

### Task 7: Add Executable Usage-kit Verification

Update release samples and release workflow so every `supported` provider claim has a release-verifiable sample.

Required suites:

- `samples/provider_capability/kafka/suite_manifest.yaml`.
- `samples/provider_capability/ibm_mq/suite_manifest.yaml`.
- `samples/provider_capability/messaging_mixed/suite_manifest.yaml`.
- `samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml` with an external `base_url` profile.
- Existing Golden E2E, WireMock, JDBC, NATS, common verify, polling, SOAP mock, and gRPC mock suites.

Required command pattern:

```bash
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite <suite>
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite <suite> --profile <profile>
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar report --result <generated_result_json> --format text
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate-evidence --result <generated_result_json>
```

Release verification must fail if any `supported` provider lacks this command set.

Release workflow dependency setup:

- The release workflow may start external Kafka, IBM MQ, or WireMock dependencies before invoking the framework jar.
- Those provisioners are release verification infrastructure, not framework runtime behavior.
- The framework jar must receive only Env_Profile / Environment Binding values such as `base_url`, `bootstrap_servers`, `conn_name`, queue names, topics, and secret refs.
- Generated result JSON must show the provider consumed those values and did not provision those servers itself.

### Task 8: Document Release Integrity

Document release asset verification beyond checksums.

Required release metadata:

```yaml
checksum_verification: passed
raw_signature_verification: passed
certificate_chain_trust: not_proven
build_provenance: not_proven
release_decision: accepted_with_integrity_limitation
```

Required release-note content:

- Jar checksum.
- Usage-kit checksum.
- Detached signature verification command.
- Public key or certificate source.
- Explicit statement that certificate chain trust and build provenance are not proven unless implemented later.

### Task 9: Final Regression and Release Gate

Run bounded-memory verification before tagging.

Commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test
MAVEN_OPTS="-Xmx2g" ./mvnw -q verify
scripts/ci/check-public-support-contract.sh
rg -n "report --format json" docs samples src
```

`scripts/ci/check-public-support-contract.sh` must fail on stale public support vocabulary, including public `runtime_status`, Native/Mock/Ephemeral support-matrix columns, `contract_available`, and `runtime_supported`, except in explicitly allowlisted migration notes or internal implementation comments.

Expected final gate:

```text
public non-canonical commands: absent from help and usage-kit release verification
public RP-mode: removed from release interface
provider support matrix uses support_status only
framework-managed WireMock capability: PASS
project-provisioned WireMock external base_url: PASS, consumed by runtime
provider support matrix consistency: PASS
report positive cases: PASS
report negative cases: EXPECTED_FAIL
report --format json: exit 2 unsupported format
validate-evidence positive cases: PASS
validate-evidence negative cases: EXPECTED_FAIL
Kafka support_status: supported after executable suite-mode verification
IBM MQ support_status: supported after executable suite-mode verification
usage-kit sample gaps: none for supported provider claims
release asset checksum metadata: present
release asset raw signature metadata: present
certificate chain/provenance limitation: explicitly documented
```

## 6. Completion Rule

v0.2.4 is releasable only when all P0 tasks pass and no provider is published as `supported` by documentation claim alone. If Kafka or IBM MQ cannot pass executable suite-mode verification, the release must either stay open or downgrade that provider from `supported`; it must not ship a false support claim.
