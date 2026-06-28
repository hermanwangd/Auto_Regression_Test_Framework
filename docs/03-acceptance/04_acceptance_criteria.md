# 04. Acceptance Criteria

These criteria validate Auto Regression Test Framework v0.2 as a feature-complete pre-release execution framework. They do not define downstream product-feature release AC, and they do not make the framework responsible for Product/RP/RU topology interpretation.

v0.2 acceptance follows this boundary:

```text
Framework owns how to execute.
Agent Skill owns product interpretation.
Product docs own the truth.
```

Minimum cross-cutting rules:

- Framework runtime decisions must use DSL, suite manifest, run profile, environment binding, provider/plugin contracts, and resolved targets.
- `labels` are report metadata only; the framework must not branch on Product/RP/RU labels.
- Raw secrets must be blocked in DSL, run profile, environment binding, result, and evidence.
- All blocked or failed outcomes must include technical failure classification, affected field or contract path, and owner action.
- Maven framework verification evidence must not be treated as downstream Product/RP release evidence.

| AC | Acceptance Focus |
|---|---|
| AC-001 | Validate v0.2 test case DSL |
| AC-002 | Resolve selected run profile and environment binding |
| AC-003 | Expand parameterized test cases |
| AC-004 | Set up and clean up fixtures |
| AC-005 | Execute CLI target and capture outputs |
| AC-006 | Execute HTTP target and capture response |
| AC-007 | Execute JDBC query and return result |
| AC-008 | Observe NATS/Kafka event publication |
| AC-009 | Support file exists and file diff |
| AC-010 | Support `db_record_exists` with polling |
| AC-011 | Support `event_published` with polling |
| AC-012 | Collect required evidence |
| AC-013 | Output standard result JSON |
| AC-014 | Block raw secrets in DSL/config/result/evidence |
| AC-015 | Run by test ID, suite, tag, and profile |
| AC-016 | Expose runner and verify plugin contracts |
| AC-017 | Treat labels as metadata and avoid RP/RU logic |
| AC-018 | Run the same generic test against different profiles when bindings exist |

## AC-001 Validate v0.2 Test Case DSL

Happy path: Given a valid `dsl_version: v0.2` test with required metadata, source refs, targets, execute, verify, evidence, and runtime, validation passes and reports the consuming AP for each section.

Failure path: Given missing required fields, duplicate execute/verify IDs, unsupported enum values, governance fields, or legacy-only fields in a new artifact, validation blocks before execution with `schema_error`.

Boundary path: Given a legacy v1 artifact, the framework may read it only through an explicit compatibility path and must not promote it as a new v0.2 artifact.

## AC-002 Resolve Selected Run Profile and Environment Binding

Happy path: Given a selected profile and matching environment binding, the framework resolves runner location, trigger, isolation scope, dependency model, constraints, logical targets, and binding refs.

Failure path: Given a missing profile, incompatible profile, missing environment binding, unresolved target, or prohibited destructive operation, execution blocks before provider dispatch with `target_resolution_error` or `environment_error`.

Boundary path: Given SIT execution, the binding may reference deployed runtime and secret refs, but the framework must not deploy product code.

## AC-003 Expand Parameterized Test Cases

Happy path: Given `parameters.ref` and `parameters.bind_as`, the framework expands reviewed parameter cases, resolves `${param.<bind_as>.<field>}`, and creates per-parameter run IDs, result records, and evidence folders.

Failure path: Given a missing parameter file, duplicate case ID, empty values, or unresolved parameter reference, execution blocks before dispatch with owner action.

Boundary path: Multiple parameter cases for one test count the traced AC once in coverage while preserving each parameter result.

## AC-004 Set Up and Clean Up Fixtures

Happy path: Given fixtures with scope and cleanup policy, the framework sets up required state, records fixture evidence, executes the test, and runs cleanup according to policy.

Failure path: Given mutating fixtures without cleanup where cleanup is required, unsafe cleanup scope, missing fixture ref, or failed setup, execution blocks or fails with `fixture_setup_error` or `cleanup_error`.

Boundary path: Default fixture behavior is `scope: test_case` and `cleanup_policy: always` unless the DSL explicitly declares another supported policy.

## AC-005 Execute CLI Target and Capture Outputs

Happy path: Given a CLI target and `execute_command` or `run_batch` operation, the framework invokes the configured runner, captures exit code, stdout/stderr or log refs, output files, duration, and status.

Failure path: Given a non-zero disallowed exit code, timeout, missing command ref, or missing output ref, the run fails with `execution_error` or `timeout` and preserves logs.

Boundary path: CLI commands must be bounded by runtime policy and must not contain inline secrets.

## AC-006 Execute HTTP Target and Capture Response

Happy path: Given an HTTP target and `call_api` operation, the framework sends the configured request, captures status, headers/body refs when configured, timing, and request/response evidence.

Failure path: Given missing endpoint binding, unsupported method, timeout, or invalid payload binding, execution blocks or fails with actionable details.

Boundary path: Raw URLs and credentials belong in environment binding or secret refs, not in the DSL body.

## AC-007 Execute JDBC Query and Return Result

Happy path: Given a JDBC target and `execute_sql` operation or DB verify rule, the framework resolves the connection ref, runs the referenced query, captures row count/result refs, and records DB evidence.

Failure path: Given missing connection ref, inline credential, unsafe SQL body, missing query ref, or query timeout, execution blocks or fails with the correct technical classification.

Boundary path: Mutating DB setup must use fixture lifecycle and cleanup policy, not ad hoc execute SQL hidden inside verification.

## AC-008 Observe NATS/Kafka Event Publication

Happy path: Given NATS or Kafka target bindings, the framework can publish, consume, or observe declared events and retain event payload evidence.

Failure path: Given missing topic/subject ref, missing payload binding, unsupported serialization, missing correlation when required, or broker timeout, the run blocks or fails with owner action.

Boundary path: Test-owned cleanup drain is allowed when bounded; broker administrator purge is not assumed by default.

## AC-009 Support File Exists and File Diff

Happy path: Given file targets and expected file artifacts, the framework verifies `file_exists`, `file_not_empty`, `file_diff`, `csv_row_count_equals`, or `csv_diff` and records diff evidence.

Failure path: Given missing actual file, missing expected file, unsupported format, or unresolved ignore path, verification fails with `verification_failed`.

Boundary path: Normalization, ignore order, and ignore paths must be explicit in verify options.

## AC-010 Support `db_record_exists` With Polling

Happy path: Given `db_record_exists` with query ref, expected fields, timeout, and poll interval, the framework polls until the record is observed or timeout expires.

Failure path: Given timeout without matching state, the verify result fails and preserves the final observed DB result.

Boundary path: Product action execution is not retried automatically; only verification polling is allowed.

## AC-011 Support `event_published` With Polling

Happy path: Given `event_published` with topic or subject, key/filter, expected payload match, timeout, and poll interval, the framework observes until the event is found or timeout expires.

Failure path: Given timeout without matching event, the verify result fails and preserves final observation evidence.

Boundary path: Default event observation starts from `test_start_time` unless the test explicitly declares another supported position.

## AC-012 Collect Required Evidence

Happy path: Given `evidence.required`, the framework collects or references required execution logs, actual artifacts, expected artifacts, assertion diffs, DB query results, event payloads, HTTP request/response, runner reports, fixture logs, and cleanup logs.

Failure path: Given a required evidence ref that cannot be resolved or copied, the run is not evidence-complete and must report the missing evidence path.

Boundary path: Evidence collection must mask secrets and preserve enough context for failed, blocked, and timed-out runs.

## AC-013 Output Standard Result JSON

Happy path: Each test or parameter case produces result JSON with framework version, DSL version, test case ID, parameter case ID when applicable, status, profile, environment, timestamps, labels, steps, verify results, evidence refs, and failure object.

Failure path: If result JSON cannot be written or does not satisfy `result.v0.2.schema.yaml`, the run is not complete.

Boundary path: Technical failure classification is framework-owned; product defect or spec ambiguity classification belongs to Agent Skill or later triage.

## AC-014 Block Raw Secrets in DSL/Config/Result/Evidence

Happy path: `secret_ref`, runtime-generated secrets, CI secret references, and vault references are accepted.

Failure path: Raw passwords, tokens, credentials, or connection strings in DSL, run profile, environment binding, result, or evidence block validation or evidence publication with `secret_resolution_error`.

Boundary path: Secret-like values inside masked evidence must remain masked and must not be printed by `report` or `explain`.

## AC-015 Run by Test ID, Suite, Tag, and Profile

Happy path: CLI supports `regress validate`, `regress run --test`, `regress run --suite`, `regress run --tag`, `regress list`, `regress report`, and `regress explain` with selected profile.

Failure path: Missing suite, unknown tag, missing profile, incompatible profile, or empty selection blocks with actionable output.

Boundary path: Suite and tag selection choose framework-readable tests only; they do not infer RP/RU membership.

## AC-016 Expose Runner and Verify Plugin Contracts

Happy path: Runner and verify plugins expose metadata for ID, version, supported target types, operations or verify types, and required binding fields; framework validation checks the catalog before execution.

Failure path: Missing plugin metadata, unsupported version, ambiguous plugin ID, or missing required binding blocks before execution.

Boundary path: Plugin contracts define generic capabilities; product-specific strategy selection remains Agent Skill work.

## AC-017 Treat Labels as Metadata and Avoid RP/RU Logic

Happy path: Labels such as product, package, runtime unit, team, or domain are copied into result and evidence for reporting.

Failure path: If runtime behavior depends on label values to choose runner, topology, target order, or environment, the design is invalid and must be corrected before implementation.

Boundary path: Generated `traceability_map.yaml` may enrich reports, but execution uses target IDs, run profile, environment binding, and provider/plugin contracts.

## AC-018 Run Same Generic Test Against Different Profiles

Happy path: Given one generic DSL test compatible with `local_debug`, `ci_pr`, `ci_nightly`, and `sit_regression`, the framework can run it against each profile when matching environment bindings exist.

Failure path: Given a selected profile without a binding, with incompatible constraints, or with prohibited fixture behavior, execution blocks before provider dispatch.

Boundary path: A test may declare `compatible_profiles` to intentionally restrict where it can run.
