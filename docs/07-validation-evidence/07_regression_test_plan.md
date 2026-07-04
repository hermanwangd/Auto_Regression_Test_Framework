# 07. Framework Verification Test Plan

This plan defines how to verify Auto Regression Test Framework v0.2 itself. It covers unit/component verification and sample generated-artifact integration verification for the feature-complete pre-release execution contract.

It does not define downstream product-feature release AC and does not replace a real RP release regression plan. Real Product/RP regression evidence is produced later after owner-provided RP artifacts are translated into framework-readable suite, run, Env_Profile, Provider Instance, and traceability artifacts. Provider Contracts are framework-owned by default and resolved from the built-in catalog.

Passing the current framework verification suite is not v0.2 delivery acceptance by itself. It is evidence that the verification harness can detect regressions while the v0.2 implementation slices are completed. v0.2 delivery acceptance requires slice-level implementation evidence and review against AC-001 through AC-018.

## 7.1 Verification Objective

Framework verification shall prove that v0.2 can:

- Complete Track A contract verification before claiming provider runtime maturity.
- Verify the documented v0.2 public interface with controlled breaking changes allowed before v1.0 before expanding runtime/provider tests.
- Run fast unit/component tests for parsers, validators, resolvers, CLI behavior, evidence writers, and reporting logic.
- Run an end-to-end framework integration flow against sample generated framework artifacts.
- Use sample DSL v0.2 tests, suite manifest, Env_Profile, Provider Instance, expected results, traceability map, and framework Provider Contract catalog lookup to validate generic discovery, binding, provider execution, verification, result JSON, batch evidence, run evidence, and coverage report generation.
- Verify provider capability registry, Provider Contract schema validation, Provider Instance validation, Env_Profile validation, verify plugin contracts, and provider type behavior required by the selected heterogeneous pilot without treating mock or sample provider evidence as downstream RP release evidence.
- Keep sample fixture evidence clearly separate from downstream Product/RP release evidence.

## 7.2 Verification Levels

| Level | Subject Under Test | Command | Fixture Source | Evidence |
|---|---|---|---|---|
| Unit/component framework verification | Framework modules and CLI behavior | `./mvnw test` | Temp directories and small local test data | Maven Surefire reports. |
| Sample generated-artifact integration verification | Framework end-to-end behavior | `./mvnw verify` | `src/test/resources/framework-verification/sample-product-repo/` generated framework artifacts | Maven Failsafe reports and generated temporary sample evidence. |
| Provider public-interface contract verification | Provider capability registry validation, Provider Contract schema validation, Provider Instance validation, Env_Profile validation, runtime mode validation, local/CI dependency provisioning policy validation, DSL target resolution, dry-run dispatch, unsupported capability blocking, provider-safety-unapproved escape-hatch blocking, and normalized evidence shape | `./mvnw verify` | Local/CI mock, WireMock HTTP mock, stub, ephemeral, Testcontainers-backed, fake-topic, embedded-broker, disposable-schema, and native provider fixtures plus injectable native messaging transport coverage for request/response, Kafka/NATS publish, NATS request/reply provider mode, consume/observe, cleanup, DB fixture, deployment readiness, file/batch, and escape-hatch contract validation when declared | Maven Failsafe reports and generated temporary provider evidence. |
| Track A contract artifact verification | Public interface docs, contract schemas, P0 provider/verify catalog, validation taxonomy, secret guardrails, evidence folder structure, and sample artifacts | Docs review plus YAML/JSON parse checks; future automation through contract tests | `samples/` and `docs/02-architecture/contracts/` | Contract review evidence and parse output. |
| Packaged CLI smoke verification | Spring Boot jar CLI entrypoint | `java -jar target/spec-driven-auto-regression-0.2.2.jar validate --root . --rp-id RP-FWK-SAMPLE --env ci` | Sample Product Repo fixture or current repository fixture | CLI output and exit code. |

`./mvnw test` must stay fast and deterministic. `./mvnw verify` may execute a local shell provider through the sample fixture, but must not require SIT/UAT deployment.
Provider public-interface contract verification must prove that local and CI Env_Profiles can replace external service, DB, messaging, K8s, and VM dependencies with explicit mock, WireMock HTTP mock, stub, ephemeral, Testcontainers-backed, fake-topic, embedded-broker, disposable-schema, or generated-data bindings. It must validate dependency provisioning policy, provider runtime mode, provider `binding_keys`, binding key value kinds, and generated refs to Provider Contract `bindable_outputs` or declared dependency provisioner outputs. Track A limits this to contract validation, sample parsing, and dry-run planning; provider behavior is verified in later runtime tracks. It must not require real SIT/UAT endpoints, real K8s clusters, real VMs, production data, or committed secrets. It must also prove that SIT/preprod release evidence cannot silently use mock substitution. External runner verification is limited to contract validation and provider safety approved escape-hatch behavior unless an explicit runner implementation slice is selected later. External runner approval is provider safety approval, not release approval.

## 7.3 Unit and Component Verification

Unit/component tests live in `src/test/java` and use the `*Test` suffix so Maven Surefire runs them during `./mvnw test`.

They shall cover framework runtime behavior first. Phase 2 Agent Skill support checks are next-stage support verification and are not mapped to framework AC unless the case also verifies reusable runtime behavior.

- Product Repo bootstrap and readiness checks.
- RP skeleton and product artifact completeness checks for product-side readiness.
- Generated suite manifest, run plan, Env_Profile, Provider Instance, target dependency, framework Provider Contract catalog lookup, and missing-field handling.
- DSL v0.2 schema validation for identity, status, revision, tags, `source_refs`, optional `labels`, compatible profiles, targets with `provider_id`, selected profile from CLI or suite manifest, optional `data`, operation `inputs`, setup, execute, cleanup, expected refs, verify, evidence, and runtime, plus execution lifecycle status checks and unsupported capability blocking.
- Expected-result approval gating.
- Environment resolver behavior for `local`, `ci`, `sit`, and `preprod`.
- Binding, provider, fixture, execution, assertion, evidence, and coverage-report behavior.
- Provider type metadata for request/response, messaging, DB fixture, deployment readiness, file/batch, and approved escape-hatch contracts.
- Unsupported provider types, missing explicit custom Provider Contracts, missing Provider Instances, missing Env_Profile provider bindings, invalid input keys, missing binding keys, invalid generated refs, missing cleanup policy, and missing readiness evidence fail before provider execution.
- Packaged CLI delegation behavior through `RegressionApplication.runCli(...)`.

## 7.4 Sample Generated-Artifact Integration Verification

The sample integration fixture is a miniature Product Repo plus generated framework artifacts used only to verify the framework. The framework integration path must consume generated artifacts, not infer Product/RP/RU topology. It must include:

- A sample Product Repo root marker that says fixture evidence is not downstream Product/RP release evidence.
- One sample RP label, currently `RP-FWK-SAMPLE`, carried only through optional labels or generated traceability-map metadata.
- Generated `suite_manifest.yaml`, `run_plan.yaml`, Env_Profiles, Provider Instances, and `traceability_map.yaml`.
- One checked-in approved DSL test case.
- One approved expected-result artifact.
- One Provider Instance using a bounded local shell provider type.
- Sample input data and expected output data.

Provider public-interface verification cases use a separate local/CI mock-stub-ephemeral provider fixture set when one shell provider fixture is not enough to prove registry behavior. That fixture set must include valid, missing-field, unsupported runtime mode, prohibited SIT/preprod mock substitution, ambiguous, invalid binding key, invalid binding value kind, invalid generated_ref, and unapproved escape-hatch Provider Instances and Env_Profiles for the selected provider types, plus framework catalog and explicit custom-contract failure cases.

The integration flow shall:

```text
copy sample Product Repo fixture to temp directory
-> check-rp --root <temp-product-repo> --rp-id RP-FWK-SAMPLE --strict-schema
-> validate --root <temp-product-repo> --rp-id RP-FWK-SAMPLE --env ci
-> run --root <temp-product-repo> --rp-id RP-FWK-SAMPLE --env ci --dry-run
-> run --root <temp-product-repo> --rp-id RP-FWK-SAMPLE --env ci
-> report --batch-id BATCH-001
-> assert batch evidence, run evidence, and coverage evidence
```

The integration suite shall expose framework AC traceability through `FrameworkVerificationIT` display names and this mapping:

| Integration Case | AC Coverage | Scenario | Required Assertions |
|---|---|---|---|
| FWK-IT-001 | AC-001, AC-002, AC-004, AC-005, AC-009, AC-012, AC-013, AC-015, AC-017 | Happy path generated artifact validation, `run`, and `report` complete without SIT/UAT deployment | Valid DSL targets resolve through provider_id plus selected Env_Profile, Provider Instance, framework Provider Contract catalog, Env_Profile provider binding keys, fixture setup/cleanup, shell provider execution, durable batch/run evidence, result JSON, suite selection, 100% sample coverage, and sample evidence boundary. |
| FWK-IT-003 | AC-001, AC-002, AC-005, AC-016 | Provider resolution failure | Missing Provider Instance, unknown provider_type in the framework catalog, missing explicit custom Provider Contract, missing Env_Profile, missing provider binding, missing binding key, invalid input key, missing output ref, unsupported runtime_mode, prohibited mock substitution, or ambiguous provider selection blocks before provider execution and writes blocked run evidence. |
| FWK-IT-005 | AC-015 | Test inventory boundary | Missing execution-eligible checked-in DSL test case blocks before provider execution. |
| FWK-IT-006 | AC-005, AC-009, AC-012, AC-013 | Execution/assertion failure | Provider execution starts, assertion fails, run evidence is failed, and report is not review-ready. |
| FWK-IT-010 | AC-002, AC-004, AC-015, AC-016 | Provider public-interface dry-run | Local/CI mock, stub, ephemeral, Testcontainers-backed, fake-topic, embedded-broker, disposable-schema, and native Provider Instances for selected provider types resolve through the capability registry and framework Provider Contract catalog with provider_id, provider_type, env_profile_id, runtime_mode, dependency provisioner, registry status, supported execution modes, evidence outputs, contract ref, instance path, Env_Profile path, binding key status, generated_ref status, and resolved execution plan. Missing, ambiguous, unsupported, invalid input key, missing output ref, missing binding key, invalid generated_ref, missing declared dependency provisioner output, unsupported runtime_mode, missing Testcontainer image/catalog ref, missing readiness check, missing bindable output, prohibited SIT/preprod mock substitution, or unapproved escape-hatch provider blocks with affected logical target and owner action. Runtime execution for AC-005 through AC-008 is covered by provider execution cases, not this dry-run display case. |
| FWK-IT-011 | AC-012, AC-013, AC-016 | Provider evidence normalization | Mock, stub, ephemeral, and native provider results are normalized into run evidence with provider_id, provider_type, env_profile_id, runtime_mode, registry status, framework Provider Contract ref, Provider Instance path, resolved operation result, assertion result, cleanup result, release-evidence eligibility, and final status. |
| FWK-IT-014 | AC-004, AC-005, AC-006, AC-007, AC-008, AC-012, AC-013, AC-016 | Heterogeneous public-run provider runtime | `HeterogeneousProviderRuntimeIT` runs one generated suite through public `regress run` with DB setup/cleanup, REST, gRPC, Kafka publish/observe/cleanup, NATS request/reply/observe/cleanup, K8s readiness, VM readiness, external runner bridge, assertions, and run evidence. Evidence remains local/mock framework verification proof, not downstream RP release evidence. |

Phase 2 Agent Skill support cases are next-stage verification. They are verified separately against `SUP-AC` and do not claim framework AC coverage:

| Support Case | Support AC | Feature | Scenario | Evidence |
|---|---|---|---|---|
| SUP-IT-001 | SUP-AC-001 | F001 | Product Repo bootstrap creates lifecycle folders, optional RU repo scan creates only draft/proposed docs/spec artifacts with source refs, readiness changes from fail to pass, rerun is idempotent, and no formal RP scope or AC is invented. | EVD-001 |
| SUP-IT-002 | SUP-AC-002 | F002 | RP creation guidance/scaffold creates required artifact locations in draft/proposed state, completeness check reports gaps, and owner review remains required. | EVD-002 |
| SUP-IT-003 | SUP-AC-003 | F003 | Owner-authored or owner-reviewed RP AC remains unchanged while draft/proposed specs preserve scan evidence, stable AC ID, classification, source refs, review state, and owner-authored truth flag. | EVD-003 |
| SUP-IT-004 | SUP-AC-004 | F004 | Current framework verification smoke validates that owner-authored RP/RU mapping is explicit, Provider Instances are declared, and the framework does not infer generated artifacts. Full Phase 2 Agent Skill validation must additionally emit suite manifest, run plan, Env_Profiles, Provider Instances, traceability map, and mapping explanation; suite-local Provider Contracts are emitted only for custom provider or snapshot pinning mode. | EVD-004 |
| SUP-IT-005 | SUP-AC-005 | F005 | Ready AC produces executable draft, existing approved tests produce update proposals, ambiguous AC blocks, and incomplete context produces skeleton only. | EVD-005 |
| SUP-IT-006 | SUP-AC-006 | F006 | Explicit RP AC and output rules produce draft expected-result artifacts with source refs and review status; missing or conflicting rules block and do not create approved regression truth. | EVD-006 |

Minimum SUP-IT-005 cases:

- Generator output for `draft_executable_test_case` is created only from ready AC and execution context, and does not overwrite checked-in approved tests.
- Generator output for `draft_test_skeleton` and `update_proposal` preserves lifecycle status, source/review context, and cannot be treated as execution-eligible until reviewed and approved.
- Existing approved tests produce update proposals instead of destructive rewrites.

Generated sample evidence must stay in the test temp directory. It shall not be committed and shall not count as real Product/RP release evidence.

## 7.5 Provider Public Interface Verification

Provider public-interface verification proves that the framework can validate the runtime model from the user guide:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Framework built-in Provider Contract catalog
  -> selected Env_Profile
    -> Env_Profile.providers.<provider_id>.binding_keys
```

It proves that the framework can validate provider capability registry entries, framework Provider Contract schemas, Provider Instance shape, Env_Profile values, DSL target resolution, dry-run planning, dispatch, blocking, and normalized evidence for heterogeneous logical execution boundaries. It does not prove a real Product/RP release.

Required interface validation tests:

| Test Area | Required Positive Tests | Required Failure Tests | AC |
|---|---|---|---|
| Provider Contract catalog validation | Valid framework-owned contract files referenced by the provider capability registry define provider_type, allowed operations, allowed input keys, required inputs, binding key schema, allowed binding value kinds, bindable outputs, output refs, evidence outputs, failure codes, defaults, `safety.rules`, and valid instance shape | Unknown provider_type, missing built-in catalog entry, missing provider_type, duplicate operation, missing allowed input keys, missing required inputs, missing binding key schema, missing bindable output metadata for generated refs, missing output refs, missing required `safety.rules` for command-capable operations, unsafe defaults, unsupported failure code, registry entry pointing to a nonexistent path, or missing explicit custom contract in custom mode blocks before dispatch | AC-016 |
| Provider Instance validation | Valid instances declare provider_id/provider_type and use the same top-level shape as the Provider Contract | Missing instance, unknown field, unknown operation, unsupported input key, output ref not in contract, or failure code not in contract blocks before dispatch | AC-002 / AC-016 |
| Env_Profile validation | Valid Env_Profile supplies all required `providers.<provider_id>.binding_keys` using allowed value kinds from the Provider Contract | Missing Env_Profile, missing provider binding, missing binding key, unknown binding key, invalid value kind, invalid enum, invalid generated_ref, raw secret, or incompatible execution mode blocks before dispatch | AC-002 / AC-014 / AC-018 |
| Data catalog validation | Valid DSL `data` entries are lifecycle-neutral and use exactly one of `ref` or safe literal `value`; `${data.<name>}` refs resolve to reviewed artifact or literal values | Legacy `data_binding`, lifecycle categories, missing source, both `ref` and `value`, unresolved data ref, inline endpoint/JDBC/broker URL, raw secret, or using `data` as an Env_Profile binding substitute blocks before execution | AC-001 / AC-004 / AC-014 |
| Dependency provisioning validation | Local/CI Env_Profiles declare allowed Testcontainers or equivalent ephemeral dependencies, readiness checks, cleanup scope, and generated output binding refs | Unsupported provisioner, missing image/catalog ref, missing readiness check, missing cleanup scope, or missing bindable output ref blocks before dispatch | AC-002 / AC-016 |
| DSL target resolution | DSL target provider_id plus selected Env_Profile resolves to Provider Instance, framework Provider Contract, and Env_Profile provider binding | Unknown provider_id, missing selected Env_Profile, unknown provider_type, invalid operation, invalid input key, or missing output ref blocks before execution | AC-001 / AC-002 |
| Dry-run integration | `regress run --dry-run` emits resolved execution plan and provider gaps without executing operations | Any real provider operation starting during dry-run fails the test | AC-015 / AC-016 |
| Evidence contract | Provider execution writes provider_id, provider_type, env_profile_id, operation, resolved operation result, Provider Contract ref, Provider Instance ref, and evidence refs | Missing provider identity, missing resolved operation result, or unmasked secret makes evidence invalid | AC-012 / AC-013 / AC-014 |

The contract verification suite shall cover:

| Provider Type Area | Positive Verification | Failure Verification | Evidence Requirement |
|---|---|---|---|
| Request/response | Resolve `rest_client`, `grpc_client`, `wiremock_http_mock`, `soap_mock`, and `grpc_mock` contracts, instances, Env_Profiles, payload input keys, response mapping, timeout, explicit mock/stub endpoint binding for local/CI, WireMock mappings ref, generated `base_url` / `endpoint_url` / `target_uri` injection through `generated_ref`, URI joining, non-2xx status, native gRPC timeout/non-timeout failure, assertion reference, and checked-in WireMock + HTTP/SOAP/gRPC happy/failure/boundary samples. PR-008A adds SOAP mock samples; PR-008B adds gRPC unary mock samples. | Missing base URL/service binding key, missing generated WireMock `base_url`, missing SOAP `endpoint_url`, missing gRPC `target_uri`, missing WireMock `bindable_outputs`, invalid generated_ref, missing WireMock mappings ref, missing SOAP response ref, invalid XPath, missing gRPC descriptor ref, unknown service/method, unsupported streaming operation, unsupported runtime_mode, prohibited SIT/preprod mock endpoint, unsupported operation, invalid request input key, missing request body ref, missing output ref, malformed operation map, provider exception, timeout, assertion mismatch, or non-success response blocks or fails with normalized evidence before downstream release evidence is claimed. | provider_id, provider_type, env_profile_id, runtime_mode, action, request binding ref, `http_request_response` or `grpc_request_response` evidence ref, WireMock/SOAP/gRPC request journal and server log when selected, SOAP assertion evidence, gRPC protobuf JSON diff, HTTP status or gRPC status/error, timeout flag, assertion result |
| Messaging | Resolve `nats`, canonical `kafka`, and `ibm_mq` Provider Contracts/Instances plus Env_Profile binding keys. NATS P0 covers `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`, subject binding, `connection.secret_ref` or approved local ref, payload input keys, `consume_from: test_start_time`, timeout, poll interval, and event evidence. Kafka P1 client-provider coverage includes `kafka_publish`, `kafka_observe`, `kafka_payload_match`, `bootstrap_servers`, `topic`, `consumer_group`, optional security refs, non-mutating observation defaults, event evidence, and `executable_runtime_modes: [mock]`. IBM MQ P1 client-provider coverage includes `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match`, queue-manager/channel/connection/queue/credential refs, browse-first shared-queue behavior, MQ evidence, and `executable_runtime_modes: [mock]`. | Missing topic/subject/queue binding key, missing Kafka consumer group, missing IBM MQ credential secret_ref, operation-level Kafka topic/consumer_group override, operation-level IBM MQ queue override, unsupported runtime_mode, runtime_mode not listed by Provider Contract executable runtime modes, prohibited SIT/preprod fake broker, missing publish/put payload binding, invalid input key, ref outside suite root, invalid duration/instant, unsupported mode, unsupported serialization, destructive MQ get without future explicit opt-in, malformed action shape, transport failure, or unsupported provider_type blocks or fails before downstream release evidence is claimed. | provider_id, provider_type, env_profile_id, runtime_mode, executable runtime mode status, topic/subject/queue ref, key/correlation id, action mode, payload binding, observed message/event ref, message count, observation window, attempts, timeout/failure classification |
| DB fixture | Resolve `jdbc` Provider Contract/Instance and Env_Profile binding keys for seed/query/cleanup, `secret_ref` or runtime-generated connection binding key, Oracle / DB2 dialect, SQL input binding, mock DB or disposable schema binding for local/CI, isolation key, cleanup marker, cleanup strategy, verification query rows, and malformed query-row skipping. | Mutating setup without cleanup, missing connection binding key, inline credential, unsupported runtime_mode, prohibited SIT/preprod mock DB, unsupported dialect, unsafe query ref, missing SQL ref, missing SQL file, invalid input key, invalid SQL input binding, or SQL failure blocks before setup or fails with framework evidence. | provider_id, provider_type, env_profile_id, runtime_mode, seed ref, query ref, dialect, masked params, row count, query result ref, cleanup result, postcondition evidence |
| Deployment readiness | Resolve `kubernetes_runtime` and `vm_runtime` contracts, instances, version/deployment refs, readiness probes, bounded K8s direct API readiness, bounded K8s pod log capture, bounded VM SSH/WinRM command probes, blank timeout fallback, native probe IO fallback, and failure-evidence write failure. | Missing namespace/host binding key, API server ref, pod selector/log tail bound, VM command ref, readiness probe, Env_Profile provider binding, provider capability, native IO failure, or failed evidence write blocks affected logical target before execution or reports an actionable technical failure. | provider_id, provider_type, env_profile_id, readiness status, deployment/version ref, affected target, captured pod log ref or VM command output ref when configured |
| File/batch | Resolve `shell_command` contract/instance with command binding key, required `safety.access_policy`, input refs, output refs, logs, success codes, and timeout | Missing command binding key, missing required safety policy, unsafe command allowlist, invalid input key, missing output ref, timeout, or non-success exit code produces blocked or failed run evidence | provider_id, provider_type, env_profile_id, safety policy status, stdout/stderr refs, actual output ref, exit code, timeout flag |
| External runner escape hatch | Resolve only when explicit provider safety approval metadata, reason, owner, command/container ref, inputs, outputs, timeout, and evidence map are present | Missing provider safety approval, unbounded timeout, unsafe output path, unsafe evidence-map path, missing evidence map, or available built-in provider alternative blocks before runner invocation; missing mapped evidence artifact fails the run after invocation | Escape-hatch status, provider safety approval ref, runner command/container ref, exit/timeout result, mapped evidence artifacts, and mapped-artifact existence status |

WireMock + HTTP request sample coverage must include these CLI paths:

- Happy path: `regress validate --suite samples/provider_capability/wiremock_http_request/suite_manifest.yaml`, `regress run --suite samples/provider_capability/wiremock_http_request/suite_manifest.yaml --profile local_wiremock_http`, and `regress report --result <generated_result_json>` return success with WireMock and `rest_client` provider evidence.
- Failure path: `regress run --suite samples/provider_capability/wiremock_http_request/suite_manifest_failure.yaml --profile local_wiremock_http` returns a failed run with assertion evidence; `regress report` consumes the result as review-ready with failures.
- Boundary path: `regress run --suite samples/provider_capability/wiremock_http_request/suite_manifest_boundary.yaml --profile local_wiremock_http` returns success for an empty request ref and HTTP 204 no-content response.

Every provider public-interface case must also verify dry-run output. Dry-run must name provider_id, provider_type, env_profile_id, registry status, capability, affected logical target, framework Provider Contract ref, Provider Instance path, Env_Profile path, binding key status, generated_ref status, AP gate, and owner action for missing, ambiguous, unsupported, invalid input key, missing output ref, missing binding key, invalid generated_ref, or unapproved escape-hatch capabilities.

## 7.5.1 Execution-Focused DSL v0.2 Verification

Framework verification must prove the v0.2 DSL contract before implementation claims runtime completion:

This is a pre-provider-runtime gate. FWK-008 must be green before sample fixture migration, provider dispatch expansion, or native runtime work can claim support for the new execution-focused DSL shape. FWK-008 includes parser validation, Env_Profile binding validation, secret guardrails, plugin catalog validation, result schema validation, and one CLI run/report proof that the same v0.2 artifact is usable by execution and reporting.

| DSL Area | Positive Verification | Failure Verification |
|---|---|---|
| Identity and source refs | Reads `dsl_version: v0.2`, `test_case_id`, `status`, `revision`, tags, optional `source_refs`, optional `labels`, and optional `compatible_profiles` | Unsupported `status`, incompatible selected profile, source_refs containing execution artifact keys, or label/source-ref-driven runtime branching blocks before execution. |
| Targets | Resolves multiple named `targets` with `provider_id`, then combines the selected Env_Profile with Provider Instance, framework Provider Contract, and Env_Profile provider binding | Missing target, unknown provider_id, missing selected Env_Profile, missing Provider Instance, unknown provider_type, missing explicit custom Provider Contract, or missing Env_Profile provider binding blocks before provider dispatch. |
| Inputs | Resolves operation-level `inputs` into reviewed refs or safe literals, validates input keys against the selected Provider Contract `allowed_inputs`, and creates separate run IDs, result JSON, and run evidence with `parameter_case_id` where parameter sets contain multiple cases | Missing or unreadable input ref, invalid input key, duplicate case ID in the referenced set, missing values, raw secret literal, or unresolved data reference blocks before provider dispatch. |
| Setup | Resolves `setup.operations`, inline cleanup, `cleanup.operations`, and `${data.<name>}` references for state-mutating setup | State-changing setup without cleanup operation, legacy `data_binding`, missing data artifact `ref`, unresolved data ref, invalid input key, or raw runtime value in `data` or `inputs` reports a fixture/schema-policy gap. |
| Execute | Resolves v0.2 `execute.operations[].operation`, `target`, `inputs`, and `outputs` for one or more uniquely named operations | Duplicate operation IDs, ambiguous ordering, `call_ru`, `target_ru_id`, missing outputs, missing output refs in the Provider Contract, invalid input key, or unsupported operation blocks before execution. |
| Data and expected refs | Resolves `data` refs used by setup, execute, verify, and cleanup | Duplicated legacy oracle/expected references, legacy lifecycle data categories, or missing expected/data ref blocks before assertion evaluation. |
| Verify | Supports captured-output actual/expected checks, provider-metadata `response_status_equals`, canonical `selector`, `db_record_exists`, and `event_published` semantics. `json_path_equals` and `json_path_absent` must keep `actual` as the captured output ref and use `selector` for the JSON/YAML path. | Missing required `actual`, missing `expected`, missing provider metadata, missing selector for JSON path verification, query ref, event ref, or unsupported verify type blocks with verify ID. |
| Evidence, result, and runtime | Resolves `evidence.required`, `runtime.timeout`, `runtime.retry.max_attempts`, evidence types, technical failure classification, and standard result JSON | Evidence refs that do not point to execute/verify outputs, unbounded runtime policy, missing result fields, or unclassified failures block completion. |
| Run/report consumption | `run` executes one active v0.2 approved test and `report --batch-id` produces review-ready source refs, optional report labels, result refs, evidence refs, and coverage | A run that passes but cannot be included in a review-ready batch report fails the gate. |
| Prohibited fields | Accepts execution-focused fields only in new artifacts | `rp_id`, `ac_id`, `execution_target`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, `policy`, approval fields, waiver fields, release gate fields, or risk approval fields block before execution. |
| Legacy compatibility | Legacy sample artifacts remain readable through an explicit compatibility path until migrated | Legacy-only input must not be silently promoted as a new execution-focused artifact. |

Minimum FWK-008 test cases:

- Valid execution-focused DSL with multiple targets, optional data refs, setup operations, operation inputs, one or more execute operations, captured outputs, expected refs, verify rules, evidence refs, timeout, and retry passes validation.
- Unsupported status, source_refs containing execution artifact keys, missing target, unknown execute target, missing execute outputs, missing expected result ref, invalid verify rule, invalid evidence ref, or unbounded runtime policy blocks before provider dispatch.
- Duplicate or ambiguous executable `execute.operations[]` entries block before provider dispatch so the runtime cannot silently execute only the primary operation.
- `json_path_equals` without `selector` and `json_path_absent` without `selector` block during DSL validation before provider dispatch or assertion evaluation. Migrated artifacts may use compatibility aliases `path` or `json_path`, but new generated DSL must use `selector`.
- `response_status_equals` with provider HTTP status metadata passes without `actual`; the same verify type blocks when neither provider metadata nor a captured `actual`/`selector` source is available.
- Legacy operation/field names such as `call_ru`, `target_ru_id`, `package_inputs`, and `oracles` are rejected in new execution-focused artifacts.
- Governance-heavy fields such as approval, waiver, release gate, or risk approval state are rejected in DSL test cases.
- Operation-level `inputs` with a reviewed two-case parameter set creates two run IDs, two run evidence directories, recorded `parameter_case_id`, resolved input values, and batch/report coverage that counts the AC once.
- Malformed v0.2 input sets or input keys not allowed by the selected Provider Contract block before provider dispatch.
- CLI `run` selection honors `--test-case`, `--tag`, and `--suite` by limiting preflight, execution, batch evidence, and run evidence to selected approved tests. Empty selector matches block with `run.selection.*` gaps and owner action instead of reporting the approved-test folder as missing.
- CLI dry-run blocks selected tests whose `compatible_profiles` do not include the requested `--env` Env_Profile; Env_Profile blocks when selected provider bindings omit required binding keys, use invalid value kinds, or reference missing bindable outputs; unsupported `execution_mode` blocks before dispatch; `sit` and `preprod` execution modes block when target readiness refs are missing.
- CLI dry-run reports DSL validation gaps with AP, field path, test case ID, acceptance-criteria source ref when available, reason, and owner action.
- CLI `run` accepts one `tests/approved/` v0.2 test with `status: active`, writes standard result JSON plus run and batch evidence, and CLI `report --batch-id` returns review-ready coverage with source ref, labels or traceability-map labels when provided, test case ID, and run ID.

This verification may initially run against parser tests, CLI dry-run tests, compatibility translation tests, plugin catalog tests, secret guardrail tests, result schema tests, and one CLI run/report consumption test. It must be green before migrating the sample fixture or changing provider runtime dispatch.

## 7.5.2 Pre-Implementation Documentation Review

Before a DSL, AP, provider runtime, evidence, or report implementation slice starts, the framework documentation shall be reviewed as part of verification planning. This review is required because the DSL is a public contract consumed by agents, developers, QA, and the runtime.

The review shall confirm:

- Feature/spec behavior, non-goals, and user ownership are current.
- Architecture design and artifact contracts define the same DSL v0.2 required, conditional, optional, legacy-compatible, and prohibited fields.
- AC covers happy path, failure path, and boundary path for the change.
- The implementation plan has an ordered slice and explicit gate.
- This test plan names the framework verification cases and downstream RP evidence needed to prove the change.

Suggested lightweight checks:

```bash
rg -n "call_ru|target_ru_id|package_inputs|oracles|approval_status|release_gate|risk_approval" docs/01-specs docs/02-architecture docs/03-acceptance docs/04-planning docs/07-validation-evidence
rg -n "dsl_version|targets|data|inputs|setup|execute|verify|evidence|required|conditional|prohibited" docs/01-specs docs/02-architecture docs/03-acceptance docs/07-validation-evidence
```

Legacy terms may appear only in migration, compatibility, or prohibited-field contexts. A new implementation slice is blocked when the docs disagree on field ownership, AP responsibility, provider contract boundary, acceptance behavior, or verification evidence.

## 7.6 Track A Contract Verification Gate

Track A is a pre-runtime gate. It verifies that the framework interface is stable enough for implementation, not that all provider runtime behavior exists.

Required Track A verification:

| Verification | Required Coverage |
|---|---|
| Provider Contract schema validation | Valid contracts define provider_type, allowed operations, allowed input keys, required inputs, binding key schema, allowed binding value kinds, bindable outputs, output refs, evidence outputs, failure codes, defaults, safety rules, and valid Provider Instance shape; invalid contracts fail deterministically. |
| Provider Instance validation | Valid instances use Provider Contract shape; unknown fields, unsupported operations, invalid input keys, unsupported output refs, or unsupported failure codes block before dispatch. |
| Env_Profile validation | Selected Env_Profile supplies all required provider binding keys using allowed value kinds; missing provider binding, missing key, unknown key, raw secret, invalid generated_ref, or incompatible runtime mode blocks. |
| DSL target resolution | DSL `provider_id` plus the selected Env_Profile resolves to Provider Instance, framework Provider Contract, and Env_Profile provider binding; missing or ambiguous resolution blocks. |
| Invalid input key | Operation-level `inputs` keys must be allowed by the referenced Provider Contract operation. |
| Missing binding key | Required binding keys in Provider Contract must be present in Env_Profile `providers.<provider_id>.binding_keys`. |
| Missing Provider Contract | Provider Instance `provider_type` without a matching framework Provider Contract, or without an explicit custom Provider Contract when custom mode is selected, blocks validation and dry-run. |
| Missing Provider Instance | DSL target `provider_id` without a matching Provider Instance blocks validation and dry-run. |
| Missing Env_Profile | Selected env_profile_id without provider binding blocks validation and dry-run. |
| Sample RP dry-run integration | `regress run --dry-run` contract produces a resolved execution plan and does not execute real operations. |
| Sample RP execution evidence contract | Sample result/evidence artifacts contain provider_id, provider_type, profile, runtime_mode, resolved operation result, failure classification, and masked evidence refs. |

Track A verification may be performed by docs review and syntax checks before implementation exists. Later automation must map these checks to FWK-013 and provider contract tests.

### 7.6.1 Track B Golden E2E Verification Gate

Track B proves one complete framework lifecycle through checked-in `samples/golden_e2e/` artifacts and a deterministic framework-owned fake provider. It is not provider-expansion work and must not exercise WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, SIT, Oracle, DB2, or downstream product deployment.

Required Track B verification:

| Verification | Required Coverage |
|---|---|
| Public CLI validate | `regress validate --suite samples/golden_e2e/suite_manifest.yaml` loads the suite and validates DSL, Provider Contract, Provider Instance, Env_Profile or compatibility profile/binding artifacts, target resolution, expected result refs, and secret guardrails. |
| Public CLI run | `regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden` generates batch ID, run ID, result JSON, and evidence folder. |
| Framework fake provider | `sample_fake_provider` executes setup, deterministic sample operation, and cleanup without external services or product topology. |
| Verify/assertion | At least one `value_equals` and one `json_match` or generic artifact compare pass in the happy path. |
| Evidence | Evidence includes execution log, fixture setup, fixture cleanup, actual output, expected result reference, assertion result, diff artifact, evidence index, and batch summary. |
| Report | `regress report --result <generated_result_json>` consumes the generated result and reports review-ready framework verification evidence. |
| Failure paths | Invalid DSL, missing Provider Instance, unsupported operation, missing expected result, verification mismatch, missing evidence ref, raw secret, and invalid result JSON are covered without Track C provider behavior. |

### 7.6.2 Track C Provider Capability Verification Gate

Track C implements selected v0.2 P0 provider capability runtime through the same lifecycle proven by Track B. It is not all providers and must not include release governance, Phase 2 Agent Skill, RP/RU topology interpretation, or non-P0 provider work.

Required Track C verification:

| Verification | Required Coverage |
|---|---|
| Public CLI validate | `regress validate --suite samples/provider_capability/suite_manifest.yaml` validates P0 provider contracts, instances, bindings, targets, expected refs, and secret guardrails. |
| Public CLI run | `regress run --suite samples/provider_capability/suite_manifest.yaml --profile local_provider` generates batch ID, run ID, standard result JSON, and indexed provider evidence. |
| WireMock | Stub injection, base URL output, request journal, server log, `http_mock_called`, and `http_mock_request_body_match`. |
| JDBC | SQL params binding, Oracle/DB2 dialect contract validation, `db_seed`, `db_cleanup`, `db_record_exists`, query evidence, and masked DB evidence. |
| NATS | Subject handling, `event_published`, `event_payload_match`, `consume_from: test_start_time`, timeout, poll interval, and event evidence. |
| Compare | `artifact_compare` target resolution, `json_match`, `schema_match`, `file_diff`, `ignore_paths`, `normalize`, `ignore_order`, and assertion diff evidence. |
| Polling | `polling_observer` target resolution, polling for DB/event observation with timeout, poll interval, and last observed evidence; execute actions are not retried. |
| Evidence/report | Every provider result references provider evidence; report consumes all provider capability results. |
| Failure paths | Invalid contract/instance/binding, unsupported operation, unsupported input key, missing output ref, missing expected artifact, raw secret, provider-specific mismatch/timeout, and required evidence missing. |

## 7.7 Required Framework Verification Cases

| Test ID | AC Coverage | Scenario | Command Level | Priority | Automation |
|---|---|---|---|---|---|
| FWK-001 | AC-001 through AC-018 | Unit/component suite validates parsers, CLI behavior, resolvers, execution services, evidence writers, result writers, secret guardrails, plugin contracts, and reporters | `./mvnw test` | P1 | Auto |
| FWK-002 | AC-001, AC-002, AC-004, AC-005, AC-009, AC-012, AC-013, AC-015, AC-017 | Sample generated artifacts run the core local fixture integration path through CLI commands without SIT/UAT deployment; Provider public-interface AC are covered by FWK-006, FWK-010, and FWK-011 | `./mvnw verify` | P1 | Auto |
| FWK-003 | AC-012, AC-013, AC-017 | Sample fixture evidence is marked as framework verification evidence and is not counted as downstream RP release evidence | `./mvnw verify` | P1 | Auto |
| FWK-004 | AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-016 | DSL, Env_Profile, Provider Instance, framework Provider Contract catalog, fixture, parameter, expected-result, secret, or verify-rule gaps block or fail with actionable evidence | `./mvnw verify` | P1 | Auto |
| FWK-005 | AC-013, AC-015 | Packaged jar delegates CLI arguments to the framework command layer and returns meaningful exit codes | `./mvnw test` plus packaged CLI smoke | P1 | Auto / CLI |
| FWK-006 | AC-004, AC-005, AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-016 | Provider public-interface verification covers request/response, messaging, DB fixture, deployment readiness, file/batch provider behavior, runtime mode validation, and escape-hatch contract gating with local/CI mock-stub-ephemeral fixtures | `./mvnw verify` | P1 | Auto |
| FWK-007 | AC-002, AC-004, AC-014, AC-016 | Provider negative cases block before unsafe execution and report provider_id, provider_type, env_profile_id, registry status, escape-hatch approval status when applicable, capability, affected logical target, framework Provider Contract ref, Provider Instance path, Env_Profile path, AP gate, and owner action | `./mvnw verify` | P1 | Auto |
| FWK-008 | AC-001, AC-002, AC-003, AC-012, AC-013, AC-014, AC-015, AC-017, AC-018 | Execution-focused DSL v0.2 parser/resolver verifies source refs, labels, compatible Env_Profiles, optional data refs, operation inputs, suite/test/tag selection, Env_Profile fields, execution-mode enum, Provider Instance refs, Env_Profile provider bindings, SIT/preprod readiness refs, evidence, runtime, result schema, secret blocking, and report consumption. | `./mvnw test` | P1 | Auto |
| FWK-009 | AC-001 through AC-018 | Pre-implementation documentation review confirms feature/spec, architecture, artifact contract, AC, implementation plan, and test plan agree before a DSL/runtime slice starts | Docs review plus lightweight `rg` checks | P1 | Manual / Agent-assisted |
| FWK-010 | AC-005, AC-006, AC-007, AC-008, AC-016 | Provider type catalog verification covers shell command, REST, gRPC client, WireMock HTTP mock, SOAP mock, gRPC mock, JDBC, NATS, canonical Kafka, IBM MQ, legacy `kafka_messaging` alias behavior, K8s runtime, VM runtime, external runner, supported provider modes, registry metadata, documented `runtime_status` behavior, Provider Contract/Instance compatibility such as numeric-string cleanup bounds, Kafka client operations `kafka_publish`, `kafka_observe`, `kafka_payload_match`, IBM MQ client operations `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match`, NATS P0 operations `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`, SOAP/gRPC mock PR-008 operations, and unsupported provider blocking | `./mvnw verify` | P1 | Auto |
| FWK-011 | AC-009, AC-010, AC-011, AC-016 | Verify catalog verification covers JSON/schema, file diff, DB polling, event polling, basic, structure, collection, numeric/time, WireMock HTTP mock verify types, and plugin verify behavior | `./mvnw verify` | P1 | Auto |
| FWK-012 | AC-004, AC-012, AC-013, AC-014 | Fixture/evidence/result verification covers `data` catalog refs, operation inputs, prohibited raw runtime values, `db_seed`, `db_cleanup`, `http_stub`, setup, cleanup precedence across DSL policy, Provider Contract strategy, Provider Instance selection, Env_Profile restrictions, masking, evidence indexing, result schema, coverage denominator rules for automatable and partial AC, exclusion metadata gaps, and failure classification | `./mvnw verify` | P1 | Auto |
| FWK-013 | AC-001, AC-002, AC-012, AC-013, AC-015, AC-016 | Documented v0.2 public interface verification covers the user guide, CLI invocation, DSL/test definition fields, Env_Profile fields, Provider Instance fields, Provider Contract fields, result/evidence schemas, stable output keys, artifact locations, and next-stage support command boundary with controlled breaking changes allowed before v1.0 | Docs review plus contract tests | P1 | Auto / Agent-assisted |
| FWK-014 | AC-004, AC-005, AC-006, AC-007, AC-008, AC-012, AC-013, AC-016 | Future broader heterogeneous runtime track beyond Track C P0: public `regress run` executes a generated heterogeneous runtime suite covering REST, gRPC, Kafka, K8s/VM readiness, external runner bridge, and cross-provider assertions/evidence. | `./mvnw verify -Dit.test=HeterogeneousProviderRuntimeIT` | P1 | Auto |
| FWK-015 | AC-001, AC-002, AC-004, AC-005, AC-009, AC-012, AC-013, AC-014, AC-015, AC-016, AC-017 | Track B Golden E2E runs the checked-in sample suite through `validate`, `run`, and `report --result` using only `sample_fake_provider`, and covers the required happy/failure paths without external services or product topology interpretation | `./mvnw verify -Dit.test=GoldenE2EIT` | P1 | Auto |
| FWK-016 | AC-004, AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-015, AC-016 | Track C P0 provider capability suite validates, runs, and reports WireMock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence through the public CLI without non-P0 providers or product topology interpretation | `./mvnw verify -Dit.test=ProviderCapabilityIT` | P1 | Auto |
| FWK-017 | AC-006, AC-012, AC-013, AC-014, AC-015, AC-016 | PR-008 protocol mock suite validates, runs, and reports SOAP mock happy/failure/boundary cases in PR-008A and gRPC unary happy/failure/boundary cases in PR-008B. The suite proves framework provider capability evidence only and must not require custom SOAP/gRPC servers, RU topology interpretation, SIT/preprod endpoints, or streaming gRPC. | `./mvnw test -Dtest=SoapMockCapabilityCommandTest,GrpcMockCapabilityCommandTest` | P1 | Auto |
| FWK-018 | AC-015, AC-016 | Provider capability suite group validates and dry-runs child suites without provider execution, then runs REST, SOAP, and gRPC mock-server cross-verification suites. Each canonical child suite uses `tests[]` to run happy plus boundary cases under one shared Env_Profile, while expected-failure child suites remain separate with `expected_status: failed`; the run writes suite summary plus raw Allure results under `target/suite-groups/`. Failure tests cover child refs escaping the suite root and duplicate child ids. | `./mvnw test -Dtest=MockServerCrossVerifySampleCommandTest` | P1 | Auto |

### 7.7.1 FWK-013 Public Interface Contract Test Scope

FWK-013 must verify both `docs/09-operations/test_framework_user_guide.md` and `docs/02-architecture/contracts/framework_usage_interface.v0.2.md` before provider/runtime expansion is accepted. Automated mapping: `FrameworkPublicInterfaceContractTest` verifies the public interface contract and referenced contract artifacts.

- `regress validate` supports Product Repo mode with `--rp-id` and `--env`, and framework sample mode with `--suite <suite_manifest_path>` for standard multi-test suites plus compatibility `child_suites[]` aggregation manifests. It supports optional `--root` default `.`, `--test-case`, `--tag`, `--format yaml|json`, and `--strict`, returns exit code `0`, `1`, or `2`, and prints stable keys `status`, `valid`, `errors`, `warnings`, `selected_tests`, `provider_instances_used`, `provider_contracts_used`, `env_profiles_used`, `child_suites`, and `owner_action`.
- `regress run` supports Product Repo mode with `--rp-id` and `--env`, and framework sample mode with `--suite <suite_manifest_path>` and `--profile <profile>`. It supports optional `--root` default `.`, `--dry-run`, `--test-case`, and `--tag`, creates one batch/run context when execution starts, records per-test outcomes in `test_results[]`, and prints stable dry-run and run keys. Suite group dry-run must not execute provider runtime; suite group run must write `suite_summary_json`, `suite_summary_yaml`, and `allure_results_dir`.
- Single-suite `tests[]` execution uses one selected suite-level profile for every test case; test cases reference Provider Instances by `provider_id` and do not select their own runtime profile. Provider capability samples must include coverage that a multi-test suite shares one Env_Profile and reports one suite-level `batch_id`, one `run_id`, `test_count`, `test_results[]`, `provider_summary[]`, pass count, fail count, evidence, and result JSON. Messaging coverage must include a mixed Kafka + IBM MQ suite using one Env_Profile with bindings for both provider IDs and provider evidence summary entries for both provider types. Mixed-provider result JSON must not expose first-provider top-level `provider_id`, `provider_type`, `topic`, or `queue` as if they describe the whole suite.
- `regress report` supports Product Repo mode with `--rp-id` and `--batch-id`, and framework sample mode with `--result <generated_result_json>`. It supports optional `--root` default `.`, supports `--format text|yaml|json`, and prints stable review keys.
- `regress validate-evidence --result` and `regress report --result` must reject malformed standard result JSON, including execution-started results where `suite_id`, `batch_id`, `run_id`, `test_count`, `test_results`, `start_time`, `end_time`, or `duration_ms` is missing, `test_count` is not a positive JSON integer value, `test_count` does not equal `test_results.length`, a `test_results[]` entry is not an object, a `test_results[]` entry is missing `test_case_id`, `status`, or `profile`, a per-test status is outside `passed`, `failed`, and `blocked`, or a multi-provider standard result inferred from `test_results[]` or `provider_results[]` is missing `provider_summary[]`.
- DSL and suite contracts exist and define stable runtime-consumed fields for identity/status/source refs, labels, compatible profiles, optional data catalog, operation inputs, targets, setup, execute, cleanup, expected refs, verify, evidence, runtime policy, suite/test/tag selection, and suite group child aggregation fields with duplicate-id and path-escape validation.
- Env_Profile contract exists and defines stable runtime-consumed fields for environment selection, execution mode, target provider bindings, secret refs, readiness refs, dependency model, constraints, runtime_mode, and binding key values.
- Provider Contract and Provider Instance contracts exist and define stable fields for provider_id, provider_type, binding key schema, bindable outputs, allowed operations, allowed input keys, required inputs, runtime-mode vocabulary, executable runtime modes, runtime status, cleanup strategy, output refs, evidence outputs, safety constraints, and failure codes.
- Messaging provider contract tests document accepted Provider Contract and Provider Instance representations such as numeric-string cleanup bounds and `request-reply` mode aliases before provider runtime expansion.
- Result and evidence schemas exist and define stable output fields, masking rules, failure classification, batch/run evidence locations, assertion evidence, cleanup evidence, and review evidence.
- Evidence/report contract tests include `CoverageReportServiceTest#batchReportWithNoAutomatableAcceptanceCriteriaIsNotReviewReady`, `#batchReportFlagsManualOrWaivedExclusionsMissingApprovalFields`, and `#batchReportCountsPartialAcceptanceCriteriaWhenApprovedRunPasses` to document review-ready coverage behavior.
- Run evidence contract tests include `EvidenceWriterTest#simpleExecutionRunOverloadWritesEmptyResolutionSections` and `#executionRunWritesV02OutputMapFallbackWhenOutputRefIsAbsent` to document cleanup evidence filtering and v0.2 output fallback formatting.
- Missing required options, missing option values, and unsupported option values return usage error `2`; validation, execution, evidence, or review failures return `1`; successful readiness/execution/reporting returns `0`.
- `init-product-repo`, `check-readiness`, `init-rp`, `generate-tests`, and `draft-expected-results` remain next-stage support commands and cannot be counted as current-stage runtime maturity evidence.

### 7.7.2 Framework Verification Mapping Gate

This gate validates that the framework verification plan has complete AC path automation mapping. It does not mean v0.2 delivery is implemented, accepted, or ready for pilot release execution. Delivery acceptance still requires implemented slices, code coverage evidence, review, and pilot evidence where real external systems are selected.

- `./mvnw test` passes.
- `./mvnw verify` passes and publishes the JaCoCo report without enforcing delivery coverage thresholds.
- `./mvnw -Pv02-delivery-coverage verify` passes before v0.2 delivery acceptance; this profile applies class-level LINE 0.97 and BRANCH 0.88 to critical framework public-interface and runtime classes.
- `FrameworkMaturityCoverageGateTest` proves framework AC-001 through AC-018 have automated happy, failure, and boundary path coverage mapped in this plan.
- `FWK-001` through `FWK-013` plus `FWK-015`, `FWK-016`, and PR-008 `FWK-017` are either automated or explicitly mapped to an automated test class/method before the related runtime is accepted. `FWK-014` remains a later broader heterogeneous runtime scenario beyond Track C P0.
- AC-001 through AC-018 each have happy, failure, and boundary path coverage.
- The contract artifacts under `docs/02-architecture/contracts/` exist and are referenced by the relevant verification cases.
- Any sample Product Repo or mock provider evidence is marked as framework verification evidence, not downstream RP release evidence.
- Delivery-gate exclusions are limited to DTO/record-style carrier classes, Spring launcher code, sample fixtures, and documented unreachable defensive branches; exclusions cannot be used to bypass missing CLI, DSL, Provider Contract, Provider Instance, Env_Profile, generated runtime artifact, evidence/report, or heterogeneous e2e behavior.

### 7.7.3 Contract Artifact Verification Matrix

| Contract Artifact | Primary Verification Cases |
|---|---|
| `docs/09-operations/test_framework_user_guide.md` | FWK-009 / FWK-013 |
| `framework_usage_interface.v0.2.md` | FWK-005 / FWK-009 / FWK-013 |
| `test_case_dsl.v0.2.schema.yaml` | FWK-001 / FWK-004 / FWK-008 / FWK-009 / FWK-013 |
| `env_profile.v0.2.schema.yaml` | FWK-002 / FWK-004 / FWK-007 / FWK-008 / FWK-013 |
| `execution_profile.v0.2.schema.yaml` | Compatibility coverage until Env_Profile runtime migration |
| `environment_binding.v0.2.schema.yaml` | Compatibility coverage until Env_Profile runtime migration |
| `suite_manifest.v0.2.schema.yaml` | FWK-002 / FWK-005 / FWK-008 / FWK-013 |
| `provider_contract.v0.2.schema.yaml` | FWK-003 / FWK-006 / FWK-007 / FWK-010 / FWK-013 |
| `provider_capability_registry.v0.2.yaml` | FWK-006 / FWK-007 / FWK-010 / FWK-011 / FWK-013 |
| `provider_plugin_contract.md` | FWK-006 / FWK-007 / FWK-010 / FWK-013 |
| `verify_plugin_contract.md` | FWK-006 / FWK-011 / FWK-013 |
| `result.v0.2.schema.yaml` | FWK-002 / FWK-008 / FWK-012 / FWK-013 |
| `evidence.v0.2.schema.yaml` | FWK-002 / FWK-003 / FWK-012 / FWK-013 |
| `validation_error_taxonomy.v0.2.yaml` | Track A / FWK-004 / FWK-013 |
| `secret_guardrails.v0.2.yaml` | Track A / FWK-004 / FWK-012 / FWK-013 |
| `evidence_folder_structure.v0.2.md` | Track A / FWK-012 / FWK-013 |
| `p0_provider_verify_catalog.v0.2.md` | Track A / FWK-010 / FWK-011 / FWK-013 |
| `samples/golden_e2e/` | Track B / FWK-015 |
| `samples/provider_capability/` | Track C / FWK-016 |
| `samples/provider_capability/soap_mock/` | PR-008A / FWK-017 |
| `samples/provider_capability/grpc_mock/` | PR-008B / FWK-017 |

### 7.6.4 AC Path Coverage Matrix

| AC | Happy Path Case | Failure Path Case | Boundary Path Case |
|---|---|---|---|
| AC-001 | FWK-001 / FWK-008 / FWK-013 / FWK-015 | FWK-004 / FWK-008 / FWK-015 | FWK-008 / FWK-009 / FWK-013 |
| AC-002 | FWK-002 / FWK-008 / FWK-013 / FWK-015 | FWK-004 / FWK-007 / FWK-015 | FWK-008 / FWK-012 / FWK-013 |
| AC-003 | FWK-008 | FWK-004 / FWK-008 | FWK-008 / FWK-012 |
| AC-004 | FWK-002 / FWK-012 / FWK-015 / FWK-016 | FWK-004 / FWK-007 / FWK-012 / FWK-016 | FWK-006 / FWK-012 |
| AC-005 | FWK-002 / FWK-010 / FWK-015 | FWK-003 / FWK-006 | FWK-010 |
| AC-006 | FWK-006 / FWK-010 / FWK-016 / FWK-017 | FWK-007 / FWK-010 / FWK-016 / FWK-017 | FWK-010 / FWK-017 |
| AC-007 | FWK-006 / FWK-010 / FWK-016 | FWK-007 / FWK-012 / FWK-016 | FWK-011 / FWK-012 / FWK-016 |
| AC-008 | FWK-006 / FWK-010 / FWK-016 | FWK-007 / FWK-010 / FWK-016 | FWK-010 / FWK-011 / FWK-016 |
| AC-009 | FWK-002 / FWK-011 / FWK-015 / FWK-016 | FWK-006 / FWK-011 / FWK-015 / FWK-016 | FWK-011 / FWK-016 |
| AC-010 | FWK-006 / FWK-011 / FWK-016 | FWK-004 / FWK-011 / FWK-016 | FWK-011 / FWK-016 |
| AC-011 | FWK-006 / FWK-011 / FWK-016 | FWK-004 / FWK-011 / FWK-016 | FWK-011 / FWK-016 |
| AC-012 | FWK-002 / FWK-012 / FWK-013 / FWK-015 / FWK-016 / FWK-017 | FWK-004 / FWK-012 / FWK-015 / FWK-016 / FWK-017 | FWK-003 / FWK-012 / FWK-013 |
| AC-013 | FWK-002 / FWK-012 / FWK-013 / FWK-015 / FWK-016 / FWK-017 | FWK-004 / FWK-012 / FWK-015 / FWK-016 / FWK-017 | FWK-003 / FWK-008 / FWK-013 |
| AC-014 | FWK-001 / FWK-012 / FWK-015 / FWK-016 / FWK-017 | FWK-004 / FWK-008 / FWK-015 / FWK-016 / FWK-017 | FWK-012 |
| AC-015 | FWK-002 / FWK-008 / FWK-013 / FWK-015 / FWK-016 / FWK-017 | FWK-005 / FWK-008 / FWK-013 / FWK-015 / FWK-016 / FWK-017 | FWK-008 / FWK-013 |
| AC-016 | FWK-006 / FWK-010 / FWK-011 / FWK-013 / FWK-015 / FWK-016 / FWK-017 | FWK-003 / FWK-007 / FWK-010 / FWK-015 / FWK-016 / FWK-017 | FWK-009 / FWK-010 / FWK-013 |
| AC-017 | FWK-003 / FWK-008 / FWK-015 | FWK-008 / FWK-009 | FWK-008 / FWK-009 / FWK-015 |
| AC-018 | FWK-002 / FWK-008 | FWK-004 / FWK-008 | FWK-008 |

### 7.7.5 Current Coverage Snapshot

This snapshot records what the current framework verification suite is intended to prove. It must be updated whenever provider runtime support changes. `Supported` means supported only inside the current framework verification boundary unless the row explicitly says accepted for v0.2 delivery or downstream RP execution.

| Area | Current Java Evidence | Status | Remaining Pilot Gap |
|---|---|---|---|
| Framework CLI, readiness, generation, run, report, batch/run evidence | `RegressionCommandTest`, `ProductRepoServiceTest`, `ReleasePackageServiceTest`, `AcIntakeServiceTest`, `TestCaseLifecycleServiceTest`, `CoverageReportService` tests through CLI flows | Partial framework verification coverage. It must prove DSL v0.2 parser/resolver/run/report consumption, but this is not yet v0.2 delivery acceptance. AC-017 requires proof that conflicting Product/RP/RU labels are reporting metadata and do not select provider_id, provider_type, Env_Profile, or Provider Contract. | Real RP evidence still requires owner artifacts. |
| Sample Product Repo integration | `FrameworkVerificationIT`, `PackagedCliSmokeIT` | Covered by `./mvnw verify` as sample framework evidence only | Does not count as downstream RP release evidence. |
| File/batch provider | `RegressionCommandTest`, `FrameworkVerificationIT`, `ExecutionEngineTest` | Partial framework verification support for bounded shell/file execution, logs, output refs, timeout, success code, and evidence | Package-specific tools are out of scope unless exposed through reusable Provider Contracts or approved external runner. |
| REST/gRPC request/response provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `RequestResponseProviderTest`, `DefaultGrpcClientInvokerTest`, and `WireMockHttpRequestSampleCommandTest` request/response cases | Framework verification support for contract/dry-run request-response behavior plus executable local/CI WireMock + `rest_client` HTTP request sample coverage with happy, failure, and boundary paths | Pilot endpoint validation remains target provider work. |
| Response assertion provider | `ExecutionEngineTest` response assertion cases, `RequestResponseProviderTest` HTTP evidence cases, `AssertionEngineTest` schema/contract assertion cases, DSL validator selector cases, and CLI metadata-status cases | Partial framework verification support for HTTP/status, JSON path equality/absence, numeric tolerance, schema/contract, multi-assertion aggregation, and durable assertion evidence | Add invariant or custom comparator assertions only if selected by the pilot. |
| Messaging provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `MessagingProviderTest`, `DefaultMessagingTransportTest`, `ExecutionEngineTest` messaging cases, future `KafkaClientProviderRuntimeTest`, future `IbmMqClientProviderRuntimeTest`, and profile-gated native broker integration evidence | Partial framework verification support for local/mock messaging, NATS publish/observe, and P1 Kafka/IBM MQ client-provider contracts, validation, dry-run, mocked client seams, and evidence shape | Real pilot broker validation remains target provider work; Kafka broker provisioning, IBM MQ queue-manager provisioning, Kafka request/reply, destructive MQ get, and persistent broker/queue purge are future work only if the selected RP requires them. |
| DB fixture provider | `RegressionCommandTest`, `ExecutionEngineTest`, and `OracleReadinessServiceTest` DB fixture/assertion cases | Partial framework verification support for JDBC setup/query/cleanup, query-result oracle readiness, DB row count assertions, and evidence | Richer DB-row match modes remain future provider/assertion work only if selected by the pilot. |
| Deployment readiness provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `DeploymentReadinessProviderTest`, and `ExecutionEngineTest` readiness cases | Partial framework verification support for local/mock readiness and native K8s/VM bounded probes | Real pilot K8s/VM validation remains target provider work. |
| External runner escape hatch | `RegressionCommandTest`, `FrameworkVerificationIT` escape-hatch cases plus FWK-010/FWK-013 contract checks | Framework verification must cover approved `external_runner` Provider Contracts, required `safety.access_policy`, provider safety approval refs, safe outputs/evidence map, built-in alternative blocking, mapped-artifact checks, and missing/unsafe policy blocking | Pilot-specific runner command validation remains target provider work. |
| Heterogeneous pilot validation | Not covered by framework Maven tests | Pending owner-provided RP plus generated artifacts | Requires real `package.yaml`, `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, approved tests, approved truth sources, generated suite/run/Env_Profile/Provider Instance artifacts, and framework Provider Contract catalog compatibility. |

## 7.8 Selected Heterogeneous Pilot Validation Gate

After owner-provided pilot RP artifacts exist, the downstream RP release pipeline shall run Agent Skill translation, then validate the real heterogeneous RP using generated framework artifacts, framework Provider Contract catalog compatibility, selected Provider Instances, and selected Env_Profiles.

The selected pilot validation must prove:

- At least one request/response provider path for REST or gRPC.
- At least one messaging provider path for Kafka or NATS.
- DB fixture setup, query/assertion, and cleanup with an isolation key.
- K8s and VM deployment readiness checks before deployed-environment execution.
- External runner escape hatch only when the pilot RP has an approved legacy or specialized boundary that cannot use a reusable built-in provider.
- Batch/run evidence includes provider_id, provider_type, env_profile_id, registry status, framework Provider Contract ref, Provider Instance path, Env_Profile path, resolved bindings, resolved operation result, assertion result, cleanup result, and final pass/fail status.
- Dry-run blocks missing, ambiguous, unsupported, invalid input key, missing output ref, missing binding key, or unapproved escape-hatch selected provider types before unsafe execution.

This gate is not satisfied by the sample framework fixture. It requires owner-authored `package.yaml`, `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, approved DSL tests, approved truth sources, generated suite/run/Env_Profile/Provider Instance artifacts, framework Provider Contract catalog compatibility, and environment readiness records for the pilot RP.

## 7.9 Downstream RP Regression Boundary

Downstream Product/RP regression is a framework capability, but it is not the primary subject of this framework verification test plan.

When owner-provided Product/RP artifacts exist, release package regression shall be verified by a separate RP validation flow:

```bash
agent product-mapping-translate --root <product-repo> --rp-id <rp-id> --out generated-framework/
regress run --root <product-repo> --rp-id <rp-id> --env <mode>
regress report --batch-id <batch-id>
```

That flow produces real RP batch/run evidence under the Product Repo. It may use `local`, `ci`, `sit`, or `preprod` depending on the selected Env_Profile `execution_mode`. SIT/preprod runs require deployed target versions and environment readiness evidence.

## 7.10 CI/CD Execution Policy

| Pipeline Stage | Required Command | Purpose |
|---|---|---|
| Pull request | `./mvnw test` | Fast framework unit/component verification. |
| Main or release branch | `./mvnw verify` | Framework integration verification with sample generated framework artifacts. |
| Provider public-interface contract verification | `./mvnw verify` | Local/CI mock-stub-ephemeral Provider Contract, Provider Instance, and Env_Profile proof for heterogeneous execution boundaries without real downstream RP release evidence. |
| Packaged CLI smoke | `java -jar target/spec-driven-auto-regression-0.2.2.jar validate --root . --rp-id RP-FWK-SAMPLE --env ci` | Verify packaged command delegation through the public runtime command surface. |
| RP release pipeline | Agent translation plus `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` | Downstream RP regression execution, outside this framework verification plan. |

All commands should be bounded and avoid memory-heavy execution. Local and CI runs should stay under the repository guidance of 8 GB RAM.

## 7.11 Out of Scope

- Formal downstream product-feature AC definition.
- Treating sample fixture evidence as real Product/RP release evidence.
- Framework-owned SIT/UAT deployment orchestration.
- Real pilot RP validation before owner-provided RP artifacts exist.
- Broad package-type plugin certification beyond the sample fixture.
