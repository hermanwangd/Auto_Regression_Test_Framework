# Track C — Provider Capability Implementation Plan

> For agentic workers: implement this plan task-by-task with review after each task. Track C reuses the Track B lifecycle and adds only selected v0.2 P0 provider capability runtime.

## 1. Summary

Track C implements the selected v0.2 P0 provider and verification capabilities needed for practical framework execution: WireMock-backed mock providers for HTTP/SOAP/gRPC surfaces, `rest_client` HTTP request samples, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and provider evidence integration. PR-008 extends Track C with WireMock-backed SOAP and unary gRPC mock capability after the current P0 lifecycle is stable.

Track C is not all providers. It must not add product topology interpretation, Phase 2 Agent Skill behavior, release governance, or non-P0 providers.

## 2. Scope and Non-goals

In scope:

- P0 Provider Contracts, Provider Instances, Environment Bindings, runtime dispatch, evidence, result JSON, and report consumption.
- WireMock stub injection and mock verification.
- JDBC SQL params binding, Oracle/DB2 dialect contract validation, seed/query/cleanup, and query evidence.
- NATS publish/observe/payload-match verification with `consume_from: test_start_time`.
- Common `json_match`, `schema_match`, `file_diff`, `ignore_paths`, `normalize`, and `ignore_order`.
- Polling for observation-style verify checks.
- Success and failure evidence for every P0 capability.

Out of scope:

- MockServer, custom SOAP/gRPC mock server implementation outside WireMock-backed runtime, Oracle/DB2 Testcontainers, container runner, K8s job runner, ReportPortal, Pact, OpenAPI/AsyncAPI generation, Flyway/Liquibase, release governance, waiver workflow, release gate, Phase 2 Agent Skill, and RP/RU topology interpretation.

## 3. Track C Prerequisites from Track A and Track B

Track A must provide schemas, CLI contract, validation taxonomy, secret guardrails, evidence folder spec, and P0 provider/verify catalog.

Track B must provide Golden E2E lifecycle, fake provider, deterministic sample artifacts, standard result JSON, evidence folder, report consumption, and happy/failure coverage.

Track C may start only when `samples/provider_capability/` exists and parses.

## 4. P0 Provider Capability Inventory

| Capability | Provider / Verify Surface | Track C Runtime Target |
|---|---|---|
| WireMock + HTTP request | `wiremock_http_mock`, `rest_client`, `http_request`, `http_stub`, `http_mock_called`, `http_mock_request_body_match` | Framework starts local WireMock, injects checked-in stubs, passes generated `base_url` to `rest_client`, executes HTTP requests, and records request journal, server log, and `http_request_response` evidence. |
| WireMock-backed SOAP mock | `soap_mock`, `load_soap_stub`, `soap_request_received`, XPath/XML match, SOAPAction match | PR-008A: Framework uses WireMock HTTP/XML behavior to expose generated `endpoint_url`, load SOAP stubs, verify SOAP requests, and record request journal, server log, HTTP response, and assertion evidence. |
| WireMock-backed gRPC unary mock | `grpc_mock`, `load_grpc_stub`, `grpc_request_received`, descriptor/proto refs, request JSON match | PR-008B: Framework uses the WireMock gRPC extension to expose generated `target_uri`, load unary stubs, verify requests, and record request journal, server log, protobuf JSON diff, and assertion evidence. |
| JDBC Oracle/DB2 | `jdbc`, `db_seed`, `db_cleanup`, `db_record_exists`, SQL params, dialect | Executes against configured JDBC binding or controlled fixture; validates Oracle/DB2 dialect contracts. |
| NATS | `nats`, `nats_publish`, `nats_observe`, `event_published`, `event_payload_match` | Uses framework local capability binding or controlled CI binding without SIT release evidence claims. |
| JSON/Schema/File Diff | `artifact_compare`, `json_match`, `schema_match`, `file_diff` | Framework-owned compare target with ignore paths, normalization, ignore order, and diff evidence. |
| Polling | `polling_observer`, `poll_until_condition`, timeout, poll interval, last observed evidence | Polls observation-style verify checks only; does not retry product execute actions. |
| Evidence | result JSON, evidence index, provider evidence, assertion diff | Every provider result is indexed and report-consumable with secret masking. |

## 5. WireMock Capability

Implement WireMock as the default HTTP mock provider for local/CI isolated dependency testing.

Required runtime:

- Validate `wiremock_http_mock` Provider Contract and Provider Instance.
- Resolve Environment Binding and generated `base_url` output.
- Inject stubs from checked-in mapping artifacts.
- Execute checked-in `rest_client` `http_request` operations against the generated WireMock `base_url`.
- Capture HTTP status, headers, body, duration, and `http_request_response` evidence.
- Record request journal evidence and server log evidence.
- Verify `http_mock_called` and `http_mock_request_body_match`.

Failure tests:

- Missing stub mapping.
- Missing HTTP request body ref.
- Expected mock call missing.
- Request body mismatch.
- HTTP response assertion mismatch.
- WireMock unavailable.
- Raw secret in mock config is blocked.

Boundary tests:

- Empty request body represented by a checked-in ref.
- HTTP 204 no-content response with an explicitly expected empty body.
- Repeated sample runs isolate output/evidence directories.

Non-goals:

- Do not use WireMock to claim SIT release readiness.
- Do not replace internal RP/RU with WireMock in SIT evidence.
- Do not treat the sample `rest_client` runtime as broad downstream endpoint certification.

## 6. JDBC / Oracle / DB2 Capability

Implement JDBC provider capability for Oracle/DB2-style verification.

Required runtime:

- Validate JDBC Provider Contract and Provider Instance.
- Resolve `secret_ref` connection data and block raw secrets.
- Bind SQL params.
- Validate dialect `oracle` and `db2`.
- Execute `db_seed`, `query`, `db_record_exists`, and `db_cleanup`.
- Emit masked query evidence: query ref, dialect, masked params, row count, duration, and masked sample result.

If real Oracle/DB2 is unavailable in framework CI, dialect-specific tests may use deterministic SQL fixture validation and controlled JDBC result fixtures. Real Oracle/DB2 validation may be supplied later by pilot/SIT evidence.

Failure tests:

- Missing connection binding.
- Raw secret blocked.
- Missing SQL params.
- Query ref missing.
- Dialect mismatch.
- Record not found.
- Cleanup failure captured as evidence.

Non-goal:

- Do not implement Oracle/DB2 Testcontainers unless moved into P0 by decision log.

## 6A. PR-008A SOAP Mock Capability

Implement SOAP mock as a WireMock-backed provider capability, not as a custom SOAP server.

Required runtime:

- Validate `soap_mock` Provider Contract and Provider Instance.
- Resolve Env_Profile `endpoint_url` generated binding or static mock endpoint.
- Load checked-in SOAP response fixtures.
- Match SOAPAction/header and XPath/XML request conditions.
- Return XML response or SOAP fault.
- Record request journal, server log, HTTP response, and assertion evidence.
- Reset mock state between tests without restarting the whole suite when suite-scoped lifecycle is selected.

Failure tests:

- Missing SOAP response ref.
- Invalid XPath.
- Expected SOAP request missing.
- XML request mismatch.
- SOAP fault expected/unexpected mismatch.
- Raw secret in SOAP headers is blocked or masked.

Boundary tests:

- SOAP namespaces are matched with namespace-aware or local-name XPath.
- Empty SOAP body or SOAP fault is represented by checked-in refs.
- Mock server starts once per suite/batch when framework-owned; DSL setup only loads stubs.

## 6B. PR-008B gRPC Unary Mock Capability

Implement gRPC mock through the WireMock gRPC extension, not a custom gRPC server.

Required runtime:

- Validate `grpc_mock` Provider Contract and Provider Instance.
- Resolve Env_Profile descriptor/service binding keys and expose generated `target_uri` for the paired `grpc_client`.
- Load descriptor/proto refs from checked-in artifacts.
- Load unary request/response JSON fixtures.
- Match service, method, request JSON, optional metadata, and gRPC status.
- Record request journal, server log, protobuf JSON diff, and assertion evidence.
- Reset mock state between tests without restarting the whole suite when suite-scoped lifecycle is selected.

Failure tests:

- Missing descriptor ref.
- Unknown service or method.
- Request JSON mismatch.
- Response fixture invalid for descriptor.
- Non-OK gRPC status handling.
- Raw authorization metadata is blocked or masked.

Boundary tests:

- Unary request/response only.
- Client/server streaming fails before execution as unsupported.
- Mock server starts once per suite/batch when framework-owned; DSL setup only loads stubs.

## 7. NATS Capability

Implement NATS event verification capability.

Required runtime:

- Validate NATS Provider Contract and Provider Instance.
- Resolve Environment Binding.
- Handle subjects.
- Support `event_published`, `event_payload_match`, `consume_from: test_start_time`, timeout, and poll interval.
- Emit event evidence: subject, correlation value when available, observed payload, matched fields, observation window, attempts, and timeout status.

Failure tests:

- Event not published.
- Payload mismatch.
- Timeout.
- Connection error.
- Old event is not matched when `consume_from: test_start_time` is selected.

Non-goal:

- Stream handling and consumer management are P1 unless moved into P0 by decision log.

## 8. JSON / Schema / File Diff Capability

Implement generic comparison verification.

Required runtime:

- `json_match`
- `schema_match`
- `file_diff`
- `ignore_paths`
- `normalize`
- `ignore_order`
- Assertion diff evidence.

Failure tests:

- JSON mismatch.
- Schema mismatch.
- File diff mismatch.
- Expected artifact missing.
- Ignored paths not applied correctly.
- Normalize not applied correctly.
- Ignore order not applied correctly.

Non-goal:

- `csv_diff`, `yaml_diff`, and advanced standalone `json_diff` are P1 unless trivial through the same common diff engine.

## 9. Polling Capability

Implement polling for eventually consistent observation checks.

Required runtime:

- Poll until condition.
- Timeout.
- `poll_interval`.
- Last observed evidence.
- Applies to `db_record_exists`, `event_published`, and file existence checks if supported by the same verify engine.

Rules:

- Execute actions are not retried as product actions.
- Observation-style verify checks may poll.
- Timeout failures include last observed evidence.
- Product assertion failures are not silently retried.
- Fail-fast on connection error is P1 unless moved into P0.

## 10. Evidence Capability

Implement provider evidence integration.

Required runtime:

- Standard result JSON.
- Evidence folder and evidence index.
- WireMock request journal and server log evidence.
- JDBC query evidence.
- NATS event evidence.
- Assertion diff evidence.
- Fixture setup evidence.
- Cleanup evidence.
- Secret masking in result and evidence.

Rules:

- Every P0 provider capability emits provider-specific evidence.
- Evidence is indexed by standard evidence index.
- Evidence refs appear in standard result JSON.
- Required evidence missing is a framework failure.
- Secrets are masked or blocked.

## 11. CLI Behavior

Track C uses the Track B suite-path lifecycle:

```bash
regress validate --suite samples/provider_capability/suite_manifest.yaml
regress run --suite samples/provider_capability/suite_manifest.yaml --profile local_provider
regress report --result <generated_result_json>
```

The root `samples/provider_capability/suite_manifest.yaml` may be an aggregate index only. Executable provider capability acceptance may run per-capability suite manifests until an aggregate executable suite has canonical provider instances, Env_Profile, and environment bindings.

CLI must:

- Return deterministic exit codes.
- Produce batch ID and run ID.
- Produce standard result JSON.
- Produce evidence folder.
- Fail with owner-actionable errors for invalid Provider Contract, invalid Provider Instance, missing Environment Binding, and unresolved provider runtime.

## 12. Sample Artifact Set

Required sample artifacts live under `samples/provider_capability/`:

- `suite_manifest.yaml`
- `wiremock/test_case.yaml`
- `wiremock/provider_contract.yaml`
- `wiremock/provider_instance.yaml`
- `wiremock/environment_binding.yaml`
- `wiremock/fixtures/payment_success_stub.json`
- `wiremock/fixtures/payment_failure_stub.json`
- `wiremock/expected_results/expected_request.json`
- `soap_mock/test_case.yaml`
- `soap_mock/provider_instances/payment_soap_mock.yaml`
- `soap_mock/env_profiles/local_soap_mock.yaml`
- `soap_mock/fixtures/payment_submit_request.xml`
- `soap_mock/fixtures/payment_submit_response.xml`
- `soap_mock/fixtures/payment_boundary_request.xml`
- `soap_mock/fixtures/payment_boundary_response.xml`
- `grpc_mock/test_case.yaml`
- `grpc_mock/provider_instances/customer_grpc_mock.yaml`
- `grpc_mock/env_profiles/local_grpc_mock.yaml`
- `grpc_mock/proto/customer.proto`
- `grpc_mock/proto/customer.desc.b64`
- `grpc_mock/fixtures/get_customer_request.json`
- `grpc_mock/fixtures/get_customer_response.json`
- `jdbc/test_case.yaml`
- `jdbc/provider_contract.yaml`
- `jdbc/provider_instances/oracle_like.yaml`
- `jdbc/provider_instances/db2_like.yaml`
- `jdbc/environment_binding.yaml`
- `jdbc/fixtures/db_seed.sql`
- `jdbc/fixtures/db_cleanup.sql`
- `jdbc/queries/order_exists_oracle.sql`
- `jdbc/queries/order_exists_db2.sql`
- `jdbc/expected_results/db_expected.json`
- `nats/test_case.yaml`
- `nats/provider_contract.yaml`
- `nats/provider_instance.yaml`
- `nats/environment_binding.yaml`
- `nats/fixtures/event_input.json`
- `nats/expected_results/event_expected.json`
- `compare/test_case.yaml`
- `compare/provider_contract.yaml`
- `compare/provider_instance.yaml`
- `compare/environment_binding.yaml`
- `compare/expected_results/expected_payload.json`
- `compare/expected_results/expected_schema.json`
- `compare/expected_results/expected_file.json`
- `compare/actual_samples/actual_payload.json`
- `compare/actual_samples/actual_file.json`
- `polling/test_case.yaml`
- `polling/provider_contract.yaml`
- `polling/provider_instance.yaml`
- `polling/environment_binding.yaml`
- `polling/expected_results/expected_timeout_result.json`
- `result/expected_result_shape.json`
- `result/expected_evidence_index.yaml`

## 13. Happy Path Tests

| Test | Command / Scope | Expected |
|---|---|---|
| Provider suite validate | `regress validate --suite samples/provider_capability/suite_manifest.yaml` | Exit `0`; all P0 contracts, instances, bindings, targets, and expected refs validate. |
| Provider suite run | `regress run --suite samples/provider_capability/suite_manifest.yaml --profile local_provider` | Exit `0`; batch/run IDs, result JSON, and evidence folder are written. |
| Provider report | `regress report --result <generated_result_json>` | Exit `0`; report consumes all provider results. |
| WireMock happy path | WireMock sample | Stub injected, base URL output produced, request journal and server log evidence emitted. |
| SOAP mock happy path | SOAP mock sample | SOAP stub loaded, endpoint URL output produced, SOAPAction/XPath match passes, XML response returned, request journal/server log/HTTP response/assertion evidence emitted. |
| gRPC mock happy path | gRPC mock sample | Descriptor-backed unary stub loaded, target URI output produced, request JSON match passes, response returned, request journal/server log/protobuf JSON diff evidence emitted. |
| JDBC happy path | Oracle-like and DB2-like samples | Seed/query/cleanup run with masked query evidence. |
| NATS happy path | NATS sample | Event observed from test start time and payload matches. |
| Compare happy path | Compare sample | JSON/schema/file checks pass with ignore/normalize rules. |
| Polling happy path | DB/event observation tests | Poll interval honored and success stops polling. |

## 14. Failure Path Tests

Required failure tests:

- Invalid Provider Contract.
- Invalid Provider Instance.
- Missing Environment Binding.
- Unsupported operation.
- Unsupported input key.
- Missing output ref.
- Missing expected artifact.
- Raw secret in config.
- WireMock call missing.
- WireMock request body mismatch.
- SOAP request missing.
- SOAP XPath/XML mismatch.
- gRPC descriptor missing.
- gRPC request mismatch.
- gRPC streaming operation unsupported.
- DB record not found.
- SQL param missing.
- NATS event not found.
- NATS payload mismatch.
- Polling timeout.
- File diff mismatch.
- Schema mismatch.
- Required evidence missing.

Every failure must produce owner-actionable error output and evidence when execution started.

## 15. Definition of Ready

- Track A schemas exist.
- Track B Golden E2E passes.
- Provider Contract schema supports P0 providers.
- Provider Instance schema supports P0 providers.
- Environment Binding schema supports `runtime_mode` and `secret_ref`.
- Result schema supports provider evidence.
- Evidence folder structure supports query/event/mock/diff evidence.
- P0 provider/verify catalog is defined.
- Secret guardrail exists.
- CLI validate/run/report exists.

## 16. Definition of Done

1. WireMock P0 capability works with framework-driven `http_stub` injection.
2. JDBC P0 capability works with SQL params binding, `db_seed`, `db_cleanup`, `db_record_exists`, and query evidence.
3. Oracle/DB2 dialect contract tests pass.
4. NATS P0 capability works with `event_published`, `event_payload_match`, `consume_from: test_start_time`, and event evidence.
5. Polling works for DB and event observation.
6. JSON/schema/file diff capabilities work with `ignore_paths`, `normalize`, and `ignore_order`.
7. Every provider capability emits standard result JSON.
8. Every provider capability emits indexed evidence.
9. Required failure paths produce owner-actionable error and evidence.
10. Raw secrets are blocked or masked.
11. Report consumes all provider capability results.
12. Future tracks and Phase 2 Agent Skill can reuse provider contracts without changing the public contract.
13. PR-008A SOAP mock works through WireMock-backed XML/SOAP behavior with request journal, server log, HTTP response, assertion evidence, and report-consumable evidence.
14. PR-008B gRPC mock works through WireMock gRPC extension unary behavior with descriptor validation, request journal, protobuf JSON diff, and report-consumable evidence.
15. SOAP/gRPC mock lifecycle is suite/batch scoped; DSL setup loads stubs but does not start RU or mock-server processes directly.

## 17. First PR Plan

First PR should implement the lowest-risk reusable core:

1. Provider capability suite loader for `samples/provider_capability/`.
2. Common compare engine for `json_match`, `schema_match`, and `file_diff`.
3. Evidence index/result references for compare failures.
4. Failure tests for missing expected artifact, file diff mismatch, schema mismatch, and required evidence missing.

WireMock, JDBC, and NATS should follow only after the compare/evidence foundation is stable.

## 18. Work Breakdown

| Task | Files | Acceptance |
|---|---|---|
| C1 Provider capability suite loader | `runtime/ProviderCapabilitySuiteLoader.java`, tests | Loads `samples/provider_capability/suite_manifest.yaml` and resolves P0 artifacts. |
| C2 Contract validation hardening | `provider/ProviderContractResolver.java`, tests | Invalid contract, instance, binding, operation, input key, required input, and output refs block before runtime. |
| C3 Compare engine | `assertion/CompareEngine.java`, `AssertionEngine.java`, tests | `json_match`, `schema_match`, `file_diff`, `ignore_paths`, `normalize`, `ignore_order`, and diff evidence work. |
| C4 Evidence foundation | `evidence/EvidenceWriter.java`, result/report tests | Provider evidence refs are indexed and report-consumable. |
| C5 WireMock provider | `provider/WireMockProvider.java`, tests | Stub injection, base URL output, request journal, server log, and mock assertions work. |
| C6 JDBC provider | `provider/JdbcProvider.java`, tests | SQL params, dialect validation, seed/query/cleanup, DB polling, and masked evidence work. |
| C7 NATS provider | `provider/NatsProvider.java`, tests | Subject handling, event observation, payload match, timeout, and event evidence work. |
| C8 Polling engine | `verification/PollingEngine.java`, tests | Timeout, interval, last observed evidence, DB/event polling semantics work. |
| C9 Provider capability integration | `ProviderCapabilityIT.java` | Public CLI validates, runs, and reports provider capability suite. |

## 19. Risks and Mitigation

- Risk: Track C grows into all-provider scope. Mitigation: only P0 rows in Section 4 are implementable; P1/P2 require decision log.
- Risk: Oracle/DB2 verification blocks CI. Mitigation: use controlled JDBC fixtures for framework verification and accept real Oracle/DB2 evidence from pilot/SIT later.
- Risk: NATS test instability. Mitigation: allow local test broker, embedded/test broker, or controlled CI NATS binding; avoid SIT dependency.
- Risk: evidence leaks secrets. Mitigation: use secret guardrail before execution and masking checks before publication.
- Risk: product actions are retried. Mitigation: polling applies only to observation-style verify checks.

## 20. Open Questions

- Which local NATS strategy should be standard for CI: local test server, embedded/test broker, or controlled CI binding?
- Should JDBC controlled fixtures be H2-backed, in-memory row fixtures, or a thin fake `ResultSet` harness for dialect tests?
- Should WireMock lifecycle use one server per test case or one server per suite with reset between tests?

## Task Breakdown Table

| Order | Task | Depends On | Verification |
|---:|---|---|---|
| 1 | Provider capability suite loader | Track B loader pattern | Loader tests and YAML/JSON parse checks. |
| 2 | Contract validation hardening | Loader | Negative contract/instance/binding tests. |
| 3 | Compare engine | Contract validation | Assertion unit tests and diff evidence tests. |
| 4 | Evidence foundation | Compare engine | Evidence index/result/report tests. |
| 5 | WireMock provider | Evidence foundation | WireMock provider tests and failure tests. |
| 6 | JDBC provider | Evidence foundation | JDBC fixture/dialect/query tests and failure tests. |
| 7 | NATS provider | Evidence foundation | NATS event/payload/polling tests and failure tests. |
| 8 | Polling engine | JDBC/NATS verification | Timeout and last-observed evidence tests. |
| 9 | Public CLI provider suite | All prior tasks | `ProviderCapabilityIT`. |

## File Path Proposal

- `src/main/java/com/specdriven/regression/runtime/ProviderCapabilitySuiteLoader.java`
- `src/main/java/com/specdriven/regression/assertion/CompareEngine.java`
- `src/main/java/com/specdriven/regression/verification/PollingEngine.java`
- `src/main/java/com/specdriven/regression/provider/WireMockProvider.java`
- `src/main/java/com/specdriven/regression/provider/JdbcProvider.java`
- `src/main/java/com/specdriven/regression/provider/NatsProvider.java`
- `src/main/java/com/specdriven/regression/result/ResultWriter.java`
- `src/test/java/com/specdriven/regression/integration/ProviderCapabilityIT.java`
- `src/test/java/com/specdriven/regression/provider/WireMockProviderTest.java`
- `src/test/java/com/specdriven/regression/provider/JdbcProviderTest.java`
- `src/test/java/com/specdriven/regression/provider/NatsProviderTest.java`
- `src/test/java/com/specdriven/regression/assertion/CompareEngineTest.java`
- `src/test/java/com/specdriven/regression/verification/PollingEngineTest.java`

## Acceptance Criteria for Track C

- AC-C001 validates the P0 provider suite through public CLI.
- AC-C002 runs the P0 provider suite through public CLI with `local_provider`.
- AC-C003 WireMock injects stubs and records request journal/server log evidence.
- AC-C004 JDBC validates Oracle/DB2 dialect contracts, binds params, seeds, queries, cleans up, and masks evidence.
- AC-C005 NATS observes events from test start time and captures payload match evidence.
- AC-C006 JSON/schema/file diff checks pass and produce diff evidence on mismatch.
- AC-C007 Polling honors timeout/poll interval and records last observed evidence.
- AC-C008 Standard result JSON references provider evidence.
- AC-C009 Report consumes provider capability results.
- AC-C010 Raw secrets are blocked or masked.
- AC-C011 Required failure paths are covered with owner-actionable errors.

## Immediate First PR Checklist

- [ ] Add provider capability suite loader tests.
- [ ] Add compare engine tests for `json_match`, `schema_match`, `file_diff`, `ignore_paths`, `normalize`, and `ignore_order`.
- [ ] Add evidence index/result reference tests for compare evidence.
- [ ] Add failure tests for missing expected artifact, schema mismatch, file diff mismatch, and required evidence missing.
- [ ] Run `MAVEN_OPTS='-Xmx1024m' ./mvnw -Dtest='ProviderCapabilitySuiteLoaderTest,CompareEngineTest,EvidenceWriterTest' test`.
- [ ] Confirm no Track C code starts WireMock/JDBC/NATS in the first PR.

## P1/P2 Backlog Split

P1 backlog:

- Kafka client provider contract/runtime seam with `kafka_publish`, `kafka_observe`, `kafka_payload_match`, mocked client tests, and optional profile-gated native broker execution.
- IBM MQ client provider contract/runtime seam with `mq_put`, browse-first `mq_browse`, `mq_message_exists`, `mq_payload_match`, mocked client tests, and optional profile-gated native queue-manager execution.
- NATS stream handling and consumer management.
- Connection-error fail-fast policy.
- CSV diff and YAML diff if not covered by the common diff engine.
- Real Oracle/DB2 pilot/SIT validation harness.
- Persistent broker purge with strict safety policy.

P2 backlog:

- MockServer.
- Oracle/DB2 Testcontainers.
- Container runner and K8s job runner.
- ReportPortal integration.
- Pact.
- OpenAPI / AsyncAPI auto generation.
- Flyway / Liquibase integration.
- Release governance, waiver workflow, and release gate integration.
