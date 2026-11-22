# Framework v0.2.4 Release Readiness Implementation Plan

> For agentic workers: implement this plan task-by-task with review after each task. v0.2.4 is a framework release truthfulness and runtime completion release, not a new architecture planning round.

## 1. Goal

Ship v0.2.4 with a truthful public provider support model, complete CI-verifiable Kafka and IBM MQ client runtime coverage, release-verifiable samples, report/evidence coverage, project-provisioned JDBC secret-ref consumption, and clear release integrity metadata.

## 2. Scope

In scope:

- Canonical suite-mode CLI only: `validate`, `run`, `run --dry-run`, `report`, and `validate-evidence`.
- Public provider support represented only by provider-level `support_status`.
- Kafka and IBM MQ provider types may be `supported` only when the provider-level supported definition is met; runtime/profile coverage such as native, external, or ephemeral is documented separately and must not become a public support status.
- Project-provisioned JDBC external connection refs such as `env://JDBC_CONNECTION`.
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
| `supported` | Public contract exists, runtime exists, at least one CI-verifiable executable usage-kit sample exists, and release verification passes. |
| `contract_only` | Public contract exists, but runtime execution is unavailable; validation blocks before dispatch. |
| `deprecated` | Compatibility-only provider or alias; new artifacts and samples must not use it. |
| `unsupported` | Not part of the public provider capability surface. |

`native`, `ephemeral`, `framework_managed`, and `external` are server/dependency or runtime/profile coverage descriptions, not public provider support modes. A runtime/profile coverage row is release-verified only when the matching suite-mode sample passes `validate`, `run`, `report`, and `validate-evidence`; otherwise it must be documented as unverified, downgraded, or blocked before dispatch.

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
- Modify `src/main/java/com/specdriven/regression/provider/jdbc/JdbcProviderRuntime.java`.
- Modify or introduce the shared provider runtime secret-ref resolver used for contract-declared `secret_ref` binding keys.

Samples and release verification:

- Modify `samples/20-provider-capability-p0/messaging/kafka/**`.
- Modify `samples/20-provider-capability-p0/messaging/ibm_mq/**`.
- Modify `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/**`.
- Modify `samples/20-provider-capability-p0/data/jdbc/**`.
- Modify external endpoint verification to prove `rest_client` consumes project-provided `base_url`; keep WireMock samples framework-managed.
- Create or update `samples/40-evidence-reporting/**` for shared release command fixtures, expected report outputs, and evidence-validation fixtures.
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
- `src/test/java/com/specdriven/regression/provider/jdbc/JdbcExternalEnvSecretRefTest.java`.
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
- `supported` providers require CI-verifiable executable usage-kit samples and passing release verification.
- `kafka_messaging` is `deprecated`; new samples use `kafka`.

Migration rules:

| Existing public value | v0.2.4 `support_status` |
|---|---|
| `supported` with executable sample and passing release verification | `supported` |
| `partial` without executable release verification | `contract_only` |
| `partial` for Kafka / IBM MQ after provider-level runtime and CI-verifiable release samples pass | `supported` |
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

### Task 3: Support Project-provisioned External HTTP `base_url`

Correction for the current public interface: project-provisioned generic HTTP endpoints are consumed by `rest_client` through Env_Profile `base_url`. If the project happens to use WireMock only as the implementation behind a generic REST endpoint, the framework still treats it as `rest_client`. `wiremock_http_mock.base_url` is reserved for an owner-provisioned WireMock-compatible mock server where the framework must call WireMock Admin API to load/reset stubs or inspect request journal evidence.

Required behavior:

- Framework-managed WireMock samples continue to pass.
- `docs/02-architecture/contracts/provider-contracts/rest_client.yaml` defines `base_url` for external HTTP endpoint consumption:
  - `value_type: uri`
  - `allowed_value_kinds: [value, secret_ref, generated_ref]`
  - `allowed_schemes: [http, https]` for external endpoints; `local://framework-demo-server` is reserved for checked-in framework demo samples.
  - raw userinfo, password, token, authorization query params, and other secret-like query values are rejected for `base_url`.
- Env_Profile or Environment Binding can supply `rest_client.base_url` as a static value or predefined generated value.
- `wiremock_http_mock` must not consume generic external `base_url`; generic external endpoint tests should target `rest_client`.
- `wiremock_http_mock.base_url` may consume an external WireMock-compatible Admin API endpoint only when the framework owns stub/journal operations but does not own the server process.
- Evidence must prove the consumed `base_url`, request URL, response status, and provider IDs.
- Missing, malformed, or secret-bearing `base_url` fails validation before provider dispatch with an owner-actionable error.
- Release verification may use a project-provisioned local WireMock process as the external HTTP server, but the framework consumes it through `rest_client`.

Failure codes:

| Failure | Code |
|---|---|
| external REST endpoint selected but no `base_url` supplied | `CONFIGURATION_MISSING_REQUIRED_BINDING_KEY` |
| URI is malformed or scheme is not `http` / `https` | `CONFIGURATION_INVALID_BINDING_KEY_URI` |
| URI contains userinfo or secret-like query params | `SECRET_GUARDRAIL_RAW_SECRET` |
| request cannot reach supplied endpoint | `PROVIDER_UNAVAILABLE` |

Required evidence:

```yaml
provider_id: payment-api-client
provider_type: rest_client
support_status: supported
consumed_binding_keys: [base_url]
request_url: http://127.0.0.1:<port>/payments
http_status: 200
```

Validation command:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=WireMockExternalBaseUrlSupportTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml
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

Complete Kafka as a client provider runtime. The framework consumes project-provided bindings and does not start a Kafka server. Provider-level `support_status` is separate from runtime/profile coverage rows such as native, external, or ephemeral.

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
- Add or update `samples/20-provider-capability-p0/messaging/kafka/env_profiles/ci_kafka_external.yaml` with:
  - `runtime_mode: native`
  - `bootstrap_servers.secret_ref` or `bootstrap_servers.generated_ref`
  - `topic`
  - `consumer_group`
  - timeout and poll interval.
- Keep mock or in-memory samples only as framework capability fixtures unless they are the explicit CI-verifiable runtime sample used for provider-level support. External native broker coverage remains a separate release coverage row.

Release verification environment:

- The release workflow or release verification harness may provision Kafka outside the framework process.
- The framework jar must only consume the resolved broker binding and must not start Kafka itself.
- If `KAFKA_BOOTSTRAP_SERVERS` or equivalent binding is missing, external native Kafka coverage is not release-verified in that environment; this must not be misreported as a framework runtime failure.
- Unit tests may use a fake transport, and CI-verifiable samples may use a framework-safe local capability fixture. External native broker evidence is required only when the release environment intentionally enables it.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=KafkaProviderRuntimeTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml --profile ci_kafka_external
```

Release gate:

```text
kafka.support_status = supported
kafka.release_usage_kit_sample = pass
kafka.validate = pass
kafka.run = pass
kafka.report = pass
kafka.validate_evidence = pass
kafka.external_native_coverage = pass when external broker bindings are configured, otherwise documented as not verified
```

### Task 6: Implement IBM MQ Client Runtime

Complete IBM MQ as a client provider runtime. The framework consumes project-provided bindings and does not start an IBM MQ server. Provider-level `support_status` is separate from runtime/profile coverage rows such as native, external, or ephemeral.

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
- Add or update `samples/20-provider-capability-p0/messaging/ibm_mq/env_profiles/ci_ibm_mq_external.yaml` with:
  - `runtime_mode: native`
  - `queue_manager`
  - `channel`
  - `conn_name`
  - `queue`
  - `credential.secret_ref`
  - timeout and poll interval.
- Keep mock or in-memory samples only as framework capability fixtures unless they are the explicit CI-verifiable runtime sample used for provider-level support. External native queue-manager coverage remains a separate release coverage row.

Release verification environment:

- The release workflow or release verification harness may provision IBM MQ outside the framework process.
- The framework jar must only consume the resolved queue-manager binding and must not start IBM MQ itself.
- If IBM MQ connection and credential refs are missing, external native IBM MQ coverage is not release-verified in that environment; this must not be misreported as a framework runtime failure.
- Unit tests may use a fake transport, and CI-verifiable samples may use a framework-safe local capability fixture. External native queue-manager evidence is required only when the release environment intentionally enables it.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=IbmMqProviderRuntimeTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external
```

Release gate:

```text
ibm_mq.support_status = supported
ibm_mq.release_usage_kit_sample = pass
ibm_mq.validate = pass
ibm_mq.run = pass
ibm_mq.report = pass
ibm_mq.validate_evidence = pass
ibm_mq.external_native_coverage = pass when external queue-manager bindings are configured, otherwise documented as not verified
```

Kafka and IBM MQ external Env_Profile contracts must validate in every release promotion run. Native external execution is optional because the public GitHub CI release environment does not own broker or queue-manager endpoints. When `KAFKA_BOOTSTRAP_SERVERS`, `IBM_MQ_CONN_NAME`, and `IBM_MQ_CREDENTIAL` are all configured, the release gate runs native external messaging samples; when any one is configured, all are required. Set `REQUIRE_EXTERNAL_MESSAGING=true` only in a release environment that intentionally requires native external messaging evidence. Missing external messaging variables with `REQUIRE_EXTERNAL_MESSAGING=false` must produce an explicit not-verified external coverage row, not a failed provider support claim.

### Task 7: Support Project-provisioned JDBC `env://` Connection Secret Refs

Allow the JDBC runtime to consume externally provisioned database connection material through contract-declared secret refs. The framework must not provision Docker, Testcontainers, Oracle, DB2, or database drivers for this path.

Required behavior:

- `connection.secret_ref: env://JDBC_CONNECTION` is valid only when the JDBC Provider Contract declares `connection.secret_ref`.
- `env://<ENV_NAME>` names must match environment variable syntax: uppercase letters, digits, and `_`, starting with a letter or `_`.
- Missing or blank `JDBC_CONNECTION` fails before provider dispatch with `SECRET_RESOLUTION_ERROR`.
- Unsupported secret-ref schemes fail with `UNSUPPORTED_SECRET_REF_SCHEME`.
- The resolved JDBC connection value is passed to `JdbcProviderRuntime`.
- Resolved connection strings, usernames, passwords, and credential fragments must never appear in stdout, stderr, result JSON, report output, or evidence.
- Split JDBC URL/user/password refs are out of scope unless the Provider Contract explicitly declares those fields and release verification covers them.

Required sample:

- Add or update split external JDBC Env_Profiles for Oracle and DB2 single-provider native samples.
- Each external sample uses `connection.secret_ref: env://JDBC_CONNECTION`.
- The external native sample is executed only when `REQUIRE_EXTERNAL_JDBC=true`; `JDBC_EXTERNAL_DIALECT=oracle|db2` selects which suite runs. Otherwise external JDBC coverage is documented as not verified, not as framework failure.

Validation commands:

```bash
MAVEN_OPTS="-Xmx2g" ./mvnw -q test -Dtest=JdbcExternalEnvSecretRefTest
java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml
JDBC_EXTERNAL_DIALECT=oracle JDBC_CONNECTION='<masked externally supplied value>' java -Xmx512m -jar target/spec-driven-auto-regression-0.2.4.jar run --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml --profile external_jdbc_oracle_env_secret_ref
```

Release gate:

```text
jdbc.external_env_secret_ref.validate = pass
jdbc.external_env_secret_ref.run = pass when JDBC_CONNECTION is configured, otherwise documented as not verified
jdbc.external_env_secret_ref.report = pass when result is generated
jdbc.external_env_secret_ref.validate_evidence = pass when result is generated
jdbc.raw_connection_secret_in_evidence = false
jdbc.missing_env_secret_ref = owner_actionable_failure
```

### Task 8: Add Executable Usage-kit Verification

Update release samples and release workflow so every `supported` provider claim has a release-verifiable sample.

Required suites:

- `samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml`.
- `samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml`.
- `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml`.
- `samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml` with an external `env://JDBC_CONNECTION` profile.
- `samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml` with an external `base_url` profile.
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

- The release workflow may start external Kafka, IBM MQ, WireMock, or database dependencies before invoking the framework jar.
- Those provisioners are release verification infrastructure, not framework runtime behavior.
- The framework jar must receive only Env_Profile / Environment Binding values such as `base_url`, `bootstrap_servers`, `conn_name`, queue names, topics, JDBC connection secret refs, and other contract-declared secret refs.
- Generated result JSON must show the provider consumed those values and did not provision those servers itself.

### Task 9: Document Release Integrity

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

### Task 10: Final Regression and Release Gate

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
project-provisioned external HTTP base_url: PASS, consumed by rest_client runtime
provider support matrix consistency: PASS
report positive cases: PASS
report negative cases: EXPECTED_FAIL
report --format json: exit 2 unsupported format
validate-evidence positive cases: PASS
validate-evidence negative cases: EXPECTED_FAIL
Kafka provider support_status: provider-level supported after CI-verifiable suite-mode verification
Kafka external native coverage: PASS when configured, otherwise explicit not-verified coverage row
IBM MQ provider support_status: provider-level supported after CI-verifiable suite-mode verification
IBM MQ external native coverage: PASS when configured, otherwise explicit not-verified coverage row
project-provisioned JDBC env:// connection ref: PASS when JDBC_CONNECTION is configured, otherwise explicit not-verified coverage row
usage-kit sample gaps: none for supported provider claims
release asset checksum metadata: present
release asset raw signature metadata: present
certificate chain/provenance limitation: explicitly documented
```

## 6. Completion Rule

v0.2.4 is releasable only when all P0 tasks pass and no provider is published as `supported` by documentation claim alone. Kafka and IBM MQ provider-level support requires CI-verifiable executable suite-mode evidence. External native Kafka, IBM MQ, and JDBC coverage may remain not verified in public CI when external dependencies or secret refs are absent, but any runtime/profile coverage row published as release-verified must pass `validate`, `run`, `report`, and `validate-evidence`.
