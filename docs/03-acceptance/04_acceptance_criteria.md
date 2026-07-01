# 04. Acceptance Criteria

These criteria validate Auto Regression Test Framework v0.2 as a feature-complete pre-release execution framework and define separate Phase 2 Agent Skill support acceptance for Product Repo/RP preparation. They do not define downstream product-feature release AC, and they do not make the framework responsible for Product/RP/RU topology interpretation.

Current-stage maturity is measured by framework AC-001 through AC-018 only. Phase 2 Agent Skill support acceptance is intentionally next-stage and must not lower or block the framework maturity score unless a missing framework contract prevents generic validation, execution, evidence, or reporting.

v0.2 acceptance follows this boundary:

```text
Framework owns how to execute.
Agent Skill owns product interpretation.
Product docs own the truth.
```

Minimum cross-cutting rules:

- Framework runtime decisions must use DSL, suite manifest, Env_Profile, Provider Instance, Provider Contract, and resolved targets.
- `labels` are report metadata only; the framework must not branch on Product/RP/RU labels.
- Raw secrets must be blocked in DSL, Env_Profile, result, and evidence.
- All blocked or failed outcomes must include technical failure classification, affected field or contract path, and owner action.
- Maven framework verification evidence must not be treated as downstream Product/RP release evidence.
- The documented v0.2 public interface with controlled breaking changes allowed before v1.0 includes CLI invocation, DSL/test definition fields, Env_Profile fields, Provider Instance fields, Provider Contract fields, result schema, and evidence schema. AC-015 covers validate, dry-run, run selection, and report invocation; AC-001 covers DSL; AC-002 covers Env_Profile/provider resolution; AC-016 covers Provider Contract and Provider Instance validation; AC-012 and AC-013 cover output interface.

| AC | Acceptance Focus |
|---|---|
| AC-001 | Validate v0.2 test case DSL |
| AC-002 | Resolve selected Env_Profile, Provider Instance, and Provider Contract |
| AC-003 | Expand parameterized test cases |
| AC-004 | Set up and clean up fixtures |
| AC-005 | Execute CLI target and capture outputs |
| AC-006 | Execute HTTP target and capture response |
| AC-007 | Execute JDBC query and return result |
| AC-008 | Observe NATS/Kafka events and NATS request/reply provider mode |
| AC-009 | Support JSON, schema, file exists, and file diff |
| AC-010 | Support `db_record_exists` with polling |
| AC-011 | Support `event_published` with polling |
| AC-012 | Collect required evidence |
| AC-013 | Output standard result JSON |
| AC-014 | Block raw secrets in DSL/config/result/evidence |
| AC-015 | Validate and run by test ID, suite, tag, and profile |
| AC-016 | Validate Provider Contract, Provider Instance, and provider capability contracts |
| AC-017 | Treat labels as metadata and avoid RP/RU logic |
| AC-018 | Run the same generic test against different profiles when bindings exist |

Phase 2 Agent Skill support acceptance is intentionally separate from framework runtime AC:

| Support AC | Acceptance Focus |
|---|---|
| SUP-AC-001 | Initialize Product Repo and support RU repo scan draft docs/specs |
| SUP-AC-002 | Create/scaffold RP artifacts and check completeness |
| SUP-AC-003 | Intake RP feature specs and AC while preserving owner-review boundary |
| SUP-AC-004 | Translate Product/RP/RU mapping into framework artifacts |
| SUP-AC-005 | Classify AC readiness and draft DSL tests without overwriting approved tests |
| SUP-AC-006 | Draft expected results while preserving approval boundary |

## AC-001 Validate v0.2 Test Case DSL

Happy path: Given a valid `dsl_version: v0.2` test with required metadata, source refs, targets using `provider_id`, optional `data`, setup/execute/cleanup operations, operation `inputs`, verify checks, evidence, runtime, and a selected profile from CLI or suite manifest, validation passes and reports the consuming AP for each section.

Failure path: Given missing required fields, duplicate execute/verify IDs, unsupported enum values, governance fields, legacy-only fields in a new artifact, a target without `provider_id`, a missing selected profile, an unresolved `${data.<name>}` reference, prohibited raw runtime values in `data`, an operation not allowed by the resolved Provider Contract, an input key not allowed by the Provider Contract, or an output ref not declared by the Provider Contract, validation blocks before execution with `schema_error` or `target_resolution_error`.

Boundary path: Given a legacy v1 artifact, the framework may read it only through an explicit compatibility path and must not promote it as a new v0.2 artifact.

## AC-002 Resolve Selected Env_Profile, Provider Instance, and Provider Contract

Happy path: Given `generated-framework/run_plan.yaml` with an Env_Profile ref, matching `generated-framework/env_profiles/<env_profile_id>.yaml`, generated Provider Instances, and the framework Provider Contract catalog, the framework resolves env_profile_id, supported execution mode (`local`, `ci`, `sit`, or `preprod`), isolation scope, dependency model, dependency substitution policy, dependency provisioning policy, data policy, max duration, logical targets, target dependencies, `provider_id`, Provider Instance, `provider_type`, built-in Provider Contract, selected `runtime_mode`, required binding keys, Env_Profile provider binding values, and valid `generated_ref` targets.

Failure path: Given a missing Env_Profile ref, missing referenced Env_Profile file, missing required Env_Profile field, unsupported execution mode, unsupported provisioner, missing dependency definition, missing readiness check, missing cleanup scope, unsupported or prohibited `runtime_mode`, incompatible Env_Profile, unresolved target, missing Provider Instance, unknown `provider_type` in the framework Provider Contract catalog, missing explicit custom Provider Contract, Provider Instance shape mismatch, invalid operation input key, missing output ref, missing Env_Profile provider entry, missing required binding key, unknown binding key, invalid binding value kind, invalid enum value, invalid `generated_ref`, missing bindable output, undeclared mock/stub/ephemeral replacement, or prohibited destructive operation, execution blocks before provider dispatch with `target_resolution_error` or `environment_error`.

Boundary path: Given `local` or `ci` execution, external service, DB, messaging, K8s, or VM dependencies may be replaced only by explicitly declared mock, stub, ephemeral, fake-topic, embedded-broker, disposable-schema, generated-data, or Testcontainers-backed Env_Profile provider binding values allowed by the selected Env_Profile, Provider Contract, and Provider Instance. Generated dependency values must be referenced by Env_Profile binding keys using `generated_ref` and must target Provider Contract `bindable_outputs`. Given `sit` or `preprod` execution, the selected Env_Profile must reference deployed runtime readiness, must default to `runtime_mode: native`, and must not use mock substitution for release evidence.

## AC-003 Expand Parameterized Test Cases

Happy path: Given operation `inputs` with `ref` or safe literal `value`, the framework resolves reviewed sources, validates each input key against the referenced Provider Contract `allowed_inputs`, and creates per-parameter run IDs, result records, and evidence folders where parameter sets contain multiple cases.

Failure path: Given a missing parameter file, duplicate case ID, empty values, unresolved parameter reference, raw secret literal, or input key not allowed by the Provider Contract, execution blocks before dispatch with owner action.

Boundary path: Multiple parameter cases for one test count the traced AC once in coverage while preserving each parameter result.

## AC-004 Set Up and Clean Up Fixtures

Happy path: Given setup operations with scope and cleanup policy, the framework sets up required state, records setup evidence, executes the test, and runs cleanup according to policy. Supported v0.2 injection includes optional reviewed `data` catalog entries plus Provider Contract operations such as `db_seed`, `db_cleanup`, and `http_stub`.

Failure path: Given mutating setup without cleanup where cleanup is required, unsafe cleanup scope, missing input ref, missing reviewed data artifact, legacy `data_binding`, lifecycle-categorized data, `data` entry without exactly one of `ref` or `value`, unresolved `${data.<name>}` reference, raw endpoint/JDBC/broker/secret value in `data` or `inputs`, invalid provider target, or failed setup, execution blocks or fails with `schema_error`, `fixture_setup_error`, or `cleanup_error`.

Boundary path: Default fixture behavior is `scope: test_case` and `cleanup_policy: always` unless the DSL explicitly declares another supported policy. Fixture injection uses Provider Contracts, Provider Instances, and Env_Profiles; the DSL does not contain raw endpoint, topic, DB credential, or provider implementation values.

Contract rule: DSL owns `scope` and `cleanup_policy`; the selected Provider Contract owns allowed `cleanup_strategy`; the Provider Instance may select only a compatible strategy; and the Env_Profile may restrict cleanup scope or destructive behavior. Execution may start only when the combined policy and strategy are compatible and cleanup evidence can be written.

## AC-005 Execute CLI Target and Capture Outputs

Happy path: Given a shell command Provider Instance and `execute_command` or `run_batch` operation allowed by its Provider Contract, the framework invokes the configured provider, captures exit code, stdout/stderr or log refs, output files, duration, and status.

Failure path: Given a non-zero disallowed exit code, timeout, missing command ref, missing required `safety.access_policy`, unsafe command policy, or missing output ref, the run fails or blocks with `execution_error`, `target_resolution_error`, or `timeout` and preserves logs.

Boundary path: CLI commands must be bounded by runtime policy and must not contain inline secrets.

## AC-006 Execute HTTP Target and Capture Response

Happy path: Given a `rest_client` Provider Instance, selected profile, resolved `base_url`, and allowed `http_request` operation, the framework sends the configured request, captures `response.status`, `response.headers`, `response.body`, `response.duration_ms`, and `http_request_response` evidence. Given a local/CI `wiremock_http_mock` Provider Instance, the framework loads checked-in stub mappings, exposes generated `base_url` for the HTTP client target, records request journal/server log evidence, and verifies `http_mock_called`, `http_mock_request_body_match`, or response assertions. Given a `grpc_client` Provider Instance and allowed request operation, the framework captures gRPC status, metadata, body refs, timing, and request/response evidence where the selected runtime is implemented.

Failure path: Given missing endpoint binding, missing generated WireMock `base_url`, missing WireMock mappings ref, unsupported method, timeout, invalid payload binding, invalid operation input key, missing response output ref, missing request journal output ref, assertion mismatch, non-success provider response where success is required, or prohibited SIT/preprod mock substitution, execution blocks or fails with actionable details and preserves HTTP/provider failure evidence.

Boundary path: Empty request bodies and no-content responses such as HTTP 204 are valid when explicitly represented by checked-in refs and expected outputs. Raw URLs and credentials belong in Env_Profile binding keys or secret refs, not in the DSL body. WireMock plus `rest_client` evidence is valid for local/CI framework provider capability verification and isolated dependency replacement; it must not replace internal RP/RU dependencies or claim SIT/preprod release evidence.

### WireMock HTTP Request Sample

The checked-in sample under `samples/provider_capability/wiremock_http_request/` must validate, dry-run, execute, and report:

- Happy path: WireMock stub load, generated `base_url`, `rest_client` `http_request`, response status/body verification, WireMock request journal, server log, `http_request_response` evidence, and report consumption.
- Failure path: deterministic assertion failure with failed assertion evidence and report status that preserves reviewable provider evidence.
- Boundary path: empty request input and HTTP 204 no-content response without treating an empty body as missing evidence.

## AC-007 Execute JDBC Query and Return Result

Happy path: Given a `jdbc` Provider Instance and allowed SQL operation or DB verify rule, the framework resolves the `secret_ref` or runtime-generated connection ref from Env_Profile binding keys, runs the referenced Oracle / DB2 / JDBC query with parameter binding, captures row count/result refs, and records DB query evidence with query ref, dialect, masked params, duration, and masked sample result.

Failure path: Given missing connection ref, inline credential, unsafe SQL body, missing query ref, unsupported dialect, invalid SQL params binding, missing cleanup marker for mutating setup, or query timeout, execution blocks or fails with the correct technical classification.

Boundary path: Mutating DB setup must use fixture lifecycle and cleanup policy, not ad hoc execute SQL hidden inside verification. DB cleanup should use `run_id` / `case_id` marker where possible.

## AC-008 Observe NATS/Kafka Events

Happy path: Given `nats` or `kafka_messaging` Provider Instances with Env_Profile binding values, the framework can publish, consume, or observe declared events and retain event payload evidence. Given NATS `nats_publish`, `nats_observe`, `event_published`, or `event_payload_match`, the framework observes from `consume_from: test_start_time` by default when possible, handles subjects, captures matched fields, attempts, observation window, and timeout status.

Failure path: Given missing topic/subject ref, missing payload binding, invalid ref path, invalid timeout, unsupported serialization, unsupported provider mode, or broker timeout, the run blocks or fails with owner action.

Boundary path: Test-owned cleanup drain is allowed when bounded; broker administrator purge is not assumed by default. Kafka request/reply and advanced NATS JetStream stream/consumer handling are not default P0 capabilities and must fail as unsupported unless a selected future RP adds an approved reusable provider contract.

## AC-009 Support JSON, Schema, File Exists, and File Diff

Happy path: Given structured captured outputs or file targets and expected artifacts, the framework verifies `json_match`, `schema_match`, `file_exists`, `file_not_empty`, `file_diff`, `csv_row_count_equals`, or `csv_diff` and records assertion or diff evidence.

Failure path: Given missing actual file, missing expected file, unsupported format, missing schema ref, invalid selector, or unresolved ignore path, verification fails with `verification_failed`.

Boundary path: Normalization, ignore order, and ignore paths must be explicit in verify options. `selector` remains the canonical field selector inside structured captured outputs.

## AC-010 Support `db_record_exists` With Polling

Happy path: Given `db_record_exists` with query ref, expected fields, timeout, and poll interval, the framework polls until the record is observed or timeout expires.

Failure path: Given timeout without matching state, the verify result fails and preserves the final observed DB result.

Boundary path: Product action execution is not retried automatically; only verification polling is allowed.

## AC-011 Support `event_published` With Polling

Happy path: Given `event_published` with topic or subject, key/filter, expected payload match, timeout, and poll interval, the framework observes until the event is found or timeout expires.

Failure path: Given timeout without matching event, the verify result fails and preserves final observation evidence.

Boundary path: Default event observation starts from `test_start_time` unless the test explicitly declares another supported position.

## AC-012 Collect Required Evidence

Happy path: Given `evidence.required`, the framework collects or references required execution logs, actual artifacts, expected artifact references, assertion diffs, DB query results, event payloads, HTTP request/response, mock request journal, WireMock server log, provider reports, fixture logs, cleanup logs, provider_id, provider_type, profile, selected `runtime_mode`, dependency substitution evidence, and resolved operation result.

Failure path: Given a required evidence ref that cannot be resolved or copied, the run is not evidence-complete and must report the missing evidence path.

Boundary path: Evidence collection must mask secrets and preserve enough context for failed, blocked, and timed-out runs, including last observed polling evidence. Sample, mock, stub, fake-topic, embedded-broker, or ephemeral dependency evidence must be labeled as framework/local/CI verification evidence and must not be reported as downstream Product/RP release evidence. Standard result JSON is canonical; Allure or HTML output is an optional export.

## AC-013 Output Standard Result JSON

Happy path: Each test or parameter case produces result JSON with framework version, DSL version, test case ID, parameter case ID when applicable, status, profile, environment, timestamps, labels, steps, provider_id, provider_type, resolved operation result, verify results, evidence refs, and failure object.

Failure path: If result JSON cannot be written or does not satisfy `result.v0.2.schema.yaml`, the run is not complete.

Boundary path: Technical failure classification is framework-owned; product defect or spec ambiguity classification belongs to Agent Skill or later triage.

## AC-014 Block Raw Secrets in DSL/Config/Result/Evidence

Happy path: `secret_ref`, runtime-generated secrets, CI secret references, and vault references are accepted.

Failure path: Raw passwords, tokens, credentials, or connection strings in DSL, Env_Profile, result, or evidence block validation or evidence publication with `secret_resolution_error`.

Boundary path: Secret-like values inside masked evidence must remain masked and must not be printed by `regress report` or any diagnostic output.

## AC-015 Validate and Run by Test ID, Suite, Tag, and Profile

Happy path: CLI supports the invocation portion of the documented v0.2 public interface with controlled breaking changes allowed before v1.0: `regress validate --rp-id <rp-id> --env <env_profile_id>`, `regress run --rp-id <rp-id> --env <env_profile_id>` with optional `--dry-run`, `--test-case <test-case-id>`, `--suite <suite-id>`, or `--tag <tag>`, and `regress report --rp-id <rp-id> --batch-id <batch-id>`. `--root <product-repo>` is optional and defaults to the current directory. `validate` produces deterministic schema, DSL, Provider Contract, Provider Instance, Env_Profile, result/evidence, and secret guardrail findings without execution. `run --dry-run` produces a resolved execution plan containing provider_id, Provider Instance, provider_type, Provider Contract, env_profile_id, Env_Profile provider binding, required binding key status, bindable output ref status, output refs, and blocked owner actions without executing real provider operations.

Failure path: Missing suite, unknown tag, missing profile, missing option value, unsupported report format, incompatible profile, or empty selection blocks with actionable output and usage errors returning exit code `2` where appropriate.

Boundary path: Test, suite, and tag selection choose framework-readable checked-in approved tests only; they do not infer RP/RU membership. Debug or next-stage support commands do not satisfy AC-015 unless they delegate to the documented v0.2 runtime interface.

## AC-016 Validate Provider Contract, Provider Instance, and Provider Capability Contracts

Happy path: Framework-owned Provider Contracts are materialized files referenced by the provider capability registry and define provider_type, allowed runtime modes, allowed operations, allowed input keys, required inputs, binding key schema, bindable outputs, output refs, evidence outputs, failure codes, defaults, and valid Provider Instance shape. Provider Instances declare provider_id, provider_type, allowed runtime modes, and conform to the resolved built-in or explicit custom Provider Contract without redefining binding key schema. Env_Profiles supply `runtime_mode` and all required `providers.<provider_id>.binding_keys` for the selected env_profile_id. Provider and verify plugins expose metadata for ID, version, provider_type, supported operations or verify types, required binding keys, supported execution modes, runtime status, evidence outputs, and safety constraints; framework validation checks the catalog before execution. The provider catalog includes local/CI HTTP mock support through `wiremock_http_mock`, framework-owned HTTP request sample execution through `rest_client`, JDBC support through `jdbc`, NATS support through `nats`, artifact comparison support through `artifact_compare`, and polling support through `polling_observer` only when the framework can validate and execute the capability or block it with a precise unsupported-capability finding.

Failure path: Missing plugin metadata, unsupported version, ambiguous plugin ID, unsupported provider_type, unsupported `runtime_mode`, unknown `provider_type` in the framework catalog, missing explicit custom Provider Contract when custom mode is declared, missing Provider Instance, missing Env_Profile, missing Env_Profile provider entry, Provider Instance field not allowed by its Provider Contract, Provider Instance redefining binding key schema, invalid operation input key, missing required input, missing output ref, missing required binding key, invalid binding key value kind, invalid binding key value type, invalid enum value, invalid generated_ref, generated_ref targeting a missing bindable output, missing required `safety.access_policy`, unsafe command-capable provider policy, undeclared mock substitution, unsupported capability, prohibited mock use in `sit` or `preprod`, or unapproved escape-hatch provider blocks before execution.

Boundary path: Plugin contracts define generic capabilities; product-specific strategy selection remains Agent Skill work. Provider Contract validation may report selected provider_id, provider_type, env_profile_id, Provider Contract, Provider Instance, Env_Profile provider binding, and capability, but it must not infer Product/RP/RU topology or choose product strategy.

## Track A Contract Baseline Acceptance Overlay

Track A is accepted only when the framework contract baseline is documented and sample-valid. It does not require full WireMock, JDBC, NATS, K8s, VM, or external-runner runtime execution.

Track A acceptance requires:

- All public interface docs define DSL Test Case, Provider Contract, Provider Instance, Env_Profile, CLI, and Evidence Contract consistently.
- `regress validate`, `regress run --dry-run`, and `regress report` syntax, output keys, exit codes, and error behavior are specified.
- DSL targets resolve through `provider_id` plus the selected Env_Profile; Provider Instance, framework Provider Contract catalog, and Env_Profile provider binding resolution rules are specified.
- Provider Contract validation covers allowed operations, allowed input keys, required inputs, output refs, evidence outputs, failure codes, required binding keys, defaults, and valid Provider Instance shape.
- Invalid operation input key, missing required input, missing Provider Instance, unknown provider type or missing explicit custom Provider Contract, missing Env_Profile, missing binding key, invalid binding key value kind, invalid generated_ref, and missing output ref fail before provider dispatch with taxonomy-backed errors.
- Dry-run produces a resolved execution plan without real provider operations.
- Result/evidence contracts include provider_id, provider_type, profile, runtime_mode, resolved operation result, failure classification, and masked evidence refs.
- Sample artifacts exist for suite manifest, DSL test case, Provider Instances, Env_Profiles or compatibility profile/binding artifacts, expected result, fixtures, result JSON, and evidence index. Built-in Provider Contracts exist in the framework catalog rather than every sample suite.

## Track B Golden E2E Acceptance Overlay

Track B is accepted only when one framework-owned Golden E2E sample runs through the public CLI using checked-in `samples/golden_e2e/` artifacts and the deterministic `sample_fake_provider`. Track B evidence is framework verification evidence only and must not be represented as downstream Product/RP release evidence.

Track B acceptance requires:

- `regress validate --suite samples/golden_e2e/suite_manifest.yaml` validates the sample suite without provider execution.
- `regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden` executes setup, one fake-provider operation, two verify checks, cleanup, result writing, and evidence writing.
- `regress report --result <generated_result_json>` consumes generated result JSON and reports review-ready framework verification evidence.
- DSL, framework Provider Contract catalog, Provider Instance, Env_Profile, target resolution, and secret guardrails are validated before execution.
- At least one simple value verify and one generic artifact verify pass in the happy path.
- Failure paths cover invalid DSL, missing Provider Instance, unsupported operation, missing expected result, verification mismatch, missing evidence reference, raw secret, and invalid result JSON.
- The fake provider does not call external services, inspect Product/RP/RU topology, execute real provider behavior, or require SIT/deployment.

## Track C Provider Capability Acceptance Overlay

Track C is accepted only when the selected v0.2 P0 provider capabilities run through the same public framework lifecycle proven by Track B. Track C evidence is framework provider capability evidence only and must not be represented as downstream Product/RP release evidence.

Track C acceptance requires:

- `regress validate --suite samples/provider_capability/suite_manifest.yaml` validates all P0 provider capability artifacts.
- `regress run --suite samples/provider_capability/suite_manifest.yaml --profile local_provider` executes selected P0 capability scenarios and writes standard result JSON plus indexed evidence.
- `regress report --result <generated_result_json>` consumes provider capability results.
- WireMock supports framework-driven stub injection, base URL output, request journal evidence, server log evidence, `http_mock_called`, and `http_mock_request_body_match`.
- JDBC supports secret-ref connection, Oracle/DB2 dialect validation, SQL params, `db_seed`, `db_cleanup`, `db_record_exists`, query evidence, and masked DB evidence.
- NATS supports subject handling, `event_published`, `event_payload_match`, `consume_from: test_start_time`, timeout, poll interval, and event evidence.
- JSON/schema/file diff uses `artifact_compare` and supports `json_match`, `schema_match`, `file_diff`, `ignore_paths`, `normalize`, `ignore_order`, and diff evidence.
- Polling uses `polling_observer` and supports timeout, poll interval, and last observed evidence for DB and event observation.
- Raw secrets are blocked or masked in provider config, result, evidence, and report output.
- Required failure paths are covered with owner-actionable output and evidence when execution has started.

Contract rule: `provider_contract.v0.2.schema.yaml`, `provider_instance.v0.2.schema.yaml`, `env_profile.v0.2.schema.yaml`, `provider_capability_registry.v0.2.yaml`, `provider_plugin_contract.md`, `verify_plugin_contract.md`, result schema, and evidence schema must exist as framework-owned artifacts before AC-016 can be marked implementation-ready. Compatibility validation may also cover legacy `environment_binding.v0.2.schema.yaml` and `execution_profile.v0.2.schema.yaml` until runtime migration completes. They must validate allowed runtime modes and local/CI mock substitution before provider dispatch. This does not mean v0.2 implementation is complete; it only defines the acceptance target.

## AC-017 Treat Labels as Metadata and Avoid RP/RU Logic

Happy path: Labels such as product, package, runtime unit, team, or domain are copied into result and evidence for reporting.

Failure path: If runtime behavior depends on label values to choose provider_id, provider_type, topology, target order, or environment, the design is invalid and must be corrected before implementation.

Boundary path: Generated `traceability_map.yaml` may enrich reports, but execution uses target IDs, Env_Profile, Provider Instance, Provider Contract, and provider/plugin contracts.

## AC-018 Run Same Generic Test Against Different Profiles

Happy path: Given one generic DSL test compatible with `local`, `ci`, `sit`, and `preprod`, the framework can run it against each Env_Profile when matching Provider Instances and Env_Profile provider bindings exist.

Failure path: Given a selected Env_Profile without a provider binding, without a Provider Instance, with incompatible constraints, missing readiness evidence, missing required binding key, or prohibited fixture behavior, execution blocks before provider dispatch.

Boundary path: A test may declare `compatible_profiles` to intentionally restrict where it can run.

## SUP-AC-001 Initialize Product Repo and Support RU Repo Scan Draft Docs/Specs

Happy path: Given an empty Product Repo root, `regress init-product-repo` creates the agreed lifecycle folders and starter artifact locations. Given explicit RU implementation repo inputs and an explicit write-draft action, the Agent Skill scans code, build files, API definitions, config, deployment manifests, tests, and README files, then writes draft/proposed docs or spec artifacts with source file refs, commit refs, assumptions, confidence notes, and review status.

Failure path: Given a missing RU repo path, unreadable repo, missing scan scope, unsupported write target, or absent explicit write-draft action, the skill reports a readiness gap and must not write draft artifacts.

Boundary path: Reverse-engineered artifacts are draft/proposed only and must not become formal Product/RP truth, formal RP AC, or generation truth until owner-reviewed.

## SUP-AC-002 Create/Scaffold RP Artifacts and Check Completeness

Happy path: Given owner-provided RP ID, package type, owner, target release, and scope, the CLI or Agent Skill creates/scaffolds the RP artifact locations and reports completeness for `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md`.

Failure path: Given missing RP ID, duplicate RP ID, missing required metadata, invalid artifact path, or incomplete references, the check reports owner action and does not mark the RP ready.

Boundary path: Artifacts scaffolded from owner input or RU scan findings remain draft/proposed until owner review; the scaffold must not invent formal RP scope, RP AC, or RP/RU membership.

## SUP-AC-003 Intake RP Feature Specs and AC While Preserving Owner Review Boundary

Happy path: Given owner-authored or owner-reviewed RP feature specs and AC, the Agent Skill preserves source refs, review status, stable AC IDs, classification, and owner-authored truth flags. Given draft/proposed specs, it can summarize gaps and prepare review input without promoting them to formal generation truth.

Failure path: Given missing stable AC IDs, unreviewed draft specs used as formal truth, ambiguous source refs, or AC without observable input/action/output/pass-fail criteria, the intake marks the item not ready and reports owner action.

Boundary path: Draft/proposed specs may guide owner review, but only owner-authored or owner-reviewed specs and AC can be used as formal generation, coverage, or release evidence truth.

## SUP-AC-004 Translate Product/RP/RU Mapping Into Framework Artifacts

Happy path: Given owner-authored `rp_ru_mapping.yaml`, release/deployment context, selected validation boundary, and required provider types, the Agent Skill emits `suite_manifest.yaml`, `run_plan.yaml`, Env_Profiles, Provider Instances, `traceability_map.yaml`, and a mapping explanation with source facts and unresolved assumptions. It emits suite-local Provider Contracts only for explicit custom provider or snapshot pinning mode.

Failure path: Given missing RP/RU membership, ambiguous target mapping, missing Env_Profile provider binding, missing Provider Instance, unknown framework provider type, missing explicit custom Provider Contract, or unresolved dependency graph, translation blocks with owner action and does not emit execution-ready artifacts.

Boundary path: Generated mapping artifacts may carry Product/RP/RU labels for reporting, but framework runtime must not infer topology from those labels.

## SUP-AC-005 Classify AC Readiness and Draft DSL Tests Without Overwriting Approved Tests

Happy path: Given reviewed RP AC with observable inputs, actions, outputs, side effects, and pass/fail expectations, the Agent Skill classifies readiness and writes draft skeletons or draft executable v0.2 DSL tests under draft locations with source refs and review status.

Failure path: Given ambiguous AC, missing execution context, missing expected result, existing approved test for the same source AC, or unsupported provider need, the skill writes a blocked readiness report or update proposal instead of overwriting approved tests.

Boundary path: Draft skeletons and executable drafts are not execution-eligible until reviewed, checked in to the approved location, and allowed by DSL lifecycle status.

## SUP-AC-006 Draft Expected Results While Preserving Approval Boundary

Happy path: Given explicit RP AC, source context, input examples, and output rules, the Agent Skill writes draft expected-result artifacts with source refs, assumptions, unresolved gaps, and review status.

Failure path: Given missing business rules, incomplete output definition, conflicting source refs, or ambiguous normalization rules, the skill marks expected-result drafting blocked and reports owner action.

Boundary path: Draft expected results must not be used as regression truth until the responsible product developer or owner marks them approved for regression.
