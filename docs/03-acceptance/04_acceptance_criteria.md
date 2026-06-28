# 04. Acceptance Criteria

These criteria validate observable outcomes for the framework product baseline in M1. Formal release acceptance criteria for downstream product features remain RP-level artifacts inside each Release Package.

Each framework AC covers the happy path, failure path, and boundary path so generated implementation tasks and regression tests can be traced back to explicit behavior.

The DSL and 7 AP are validated through these AC rather than as separate, abstract concepts:

| AC | Primary AP Focus | DSL Relationship |
|---|---|---|
| AC-001 | Definition and Validation / Discovery and Context | Product Repo structure must be ready before DSL artifacts are created or executed. |
| AC-002 | Definition and Validation / Discovery and Context | RP records must expose the artifact locations where DSL tests, expected results, and evidence live. |
| AC-003 | Definition and Validation | Owner-authored RP AC remain the source of truth that DSL tests trace to. |
| AC-004 | Discovery and Context / Planning and Binding | Agent-generated suite, run, environment, provider, and traceability artifacts define the execution boundary that DSL tests reference logically. |
| AC-005 | Definition and Validation / Planning and Binding | Agent-created execution-focused DSL drafts are allowed only when AC and execution context readiness are explicit. |
| AC-006 | Definition and Validation / Oracle and Assertion Engine | DSL `expected_results` and `verify` rules must point to explicit truth sources or deterministic decision rules. |
| AC-007 | Planning and Binding / Fixture and State Manager / Execution Engine / Oracle and Assertion Engine / Evidence and Reporting | Checked-in DSL tests must execute through the full 7 AP flow and produce durable evidence. |
| AC-008 | Discovery and Context / Planning and Binding / Fixture and State Manager / Execution Engine | Unsafe or incomplete DSL execution is blocked with owner-actionable reasons. |
| AC-009 | Evidence and Reporting | DSL source refs, optional report labels, traceability map, and run evidence must support RP release review and coverage calculation. |
| AC-010 | Definition and Validation / Evidence and Reporting | Framework Verification and RP Regression Execution must remain separate execution lines with separate commands and evidence meaning. |

Minimum verification rule for DSL/AP clarity:

- Every readiness, generation, dry-run, execution, or evidence report that references DSL behavior shall include `ap`, `field_path` or `contract_path`, `test_case_id` when applicable, acceptance-criteria source ref when applicable, `reason`, and `owner_action`.
- DSL validation failures shall identify whether the problem belongs to DSL syntax, execution lifecycle state, source refs or report-label normalization, targets, setup fixtures, execute outputs, expected results, verify rules, generated suite/run/environment artifacts, provider contract, environment readiness, runtime policy, or evidence completeness.
- New execution-focused DSL artifacts shall use `dsl_version`, `test_case_id`, `status`, `revision`, `source_refs.acceptance_criteria`, `targets`, `scenario`, `execute`, `verify`, `evidence`, and `runtime` as the always-required core contract before F007 provider runtime execution. `labels`, `compatible_profiles`, `setup`, and `expected_results` content are required only when the scenario, report, selected profile, or verify rules need them.
- M1 execution-focused DSL artifacts shall contain exactly one executable `execute[]` item. Multiple operations shall be represented as multiple approved test cases in the same batch until multi-step orchestration is designed and verified.
- New execution-focused DSL artifacts may use `parameters.ref` and `parameters.bind_as` when the same reviewed test case must run with multiple named input variants from a checked-in parameter set. Inline `parameters.strategy`, `parameters.cases`, and `${parameters.<name>}` references are legacy-only and unsupported in new DSL artifacts.
- New execution-focused DSL artifacts shall not contain legacy-only fields such as `rp_id`, `ac_id`, `execution_target`, `target_ru_id`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, or `policy`.
- New execution-focused DSL artifacts shall not contain governance-heavy fields such as `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or governance workflow state.
- A missing provider contract, unsupported DSL capability, missing expected result, missing cleanup reference, missing execute output, invalid verify rule, or missing environment readiness shall block before adapter execution starts.
- A structured verify rule shall keep `actual` as the captured output reference and declare `selector` as the canonical field path. Missing `selector` for `json_path_equals` or `json_path_absent` shall block before provider dispatch or assertion evaluation.
- Baseline, spec, architecture, and AC documents shall use the same seven AP names and shall not introduce hidden AP-level components.
- Every required or conditional DSL field shall map to a primary AP consumer and have a clear reason for being required.
- A verify rule shall declare the truth source required by its type: captured-output `actual` and `expected`, provider metadata plus `expected`, or target/query/event semantics.
- Before implementation starts for any DSL, AP, provider runtime, evidence, or report change, the feature/spec, architecture design, artifact contract, AC, implementation plan, and test plan shall describe the same behavior, non-goals, required/conditional/prohibited fields, happy/failure/boundary paths, and verification evidence.
- Provider implementation settings shall be validated through generated provider contracts and environment bindings, not embedded directly inside DSL test cases.
- Provider contracts shall be validated against a capability registry by explicit `provider_family`, `provider_type`, required fields, supported runtime status, execution mode, and evidence output requirements.
- External runner use shall require explicit approval metadata and shall be reported as an escape hatch, not as the standard provider extension path.
- A feature is not implementation-ready when its DSL fields, provider contract paths, expected-result source, verify rule, setup lifecycle, execute outputs, runtime policy, or evidence outputs cannot be explained through the 7 AP flow.
- Maven framework verification evidence shall not be treated as downstream Product/RP release evidence.
- CLI RP regression evidence shall identify the batch, environment, test cases, acceptance-criteria source refs, and optional Product/RP labels supplied by generated artifacts for the selected execution.
- Execution-focused DSL v1 support is not accepted until one `tests/approved/` artifact with `status: active` can pass `run` and then produce a review-ready `report --batch-id` using v1 source refs and normalized report labels.

Acceptance is split by evidence source:

| Acceptance Scope | Evidence Source | Can Be Accepted Now When |
|---|---|---|
| Framework behavior | `./mvnw test`, `./mvnw verify`, sample Product Repo fixtures, and local/mock provider-family cases | The AC behavior is about framework parsing, readiness, blocking, execution, evidence writing, reporting, or verification boundary. |
| Downstream RP regression capability | Agent translation plus generic `regress run --suite-manifest ... --run-plan ... --environment-binding ...` against owner-provided RP artifacts | The run uses real RP AC, approved tests, approved truth sources, generated framework artifacts, selected provider contracts, and Product Repo evidence. |
| Native heterogeneous pilot support | The selected pilot RP plus native provider implementations where required | Native provider runtime, contract validation, evidence mapping, and provider-specific verification exist for the selected REST/gRPC, Kafka/NATS, DB, K8s/VM, or escape-hatch boundary. |

Passing framework verification is necessary but not sufficient for accepting native Kafka, NATS, gRPC, K8s, VM, or the full selected heterogeneous pilot. Those are accepted only through the downstream RP or native-provider evidence above.

## AC-001 Product Repo Bootstrap and Readiness Are Deterministic

### Happy Path

Given an empty Product Repo
When the framework bootstrap and readiness check are run
Then the required lifecycle folders and starter artifact locations shall exist, and the readiness report shall list pass/fail status, checked items, owner action, and next required step.

### Failure Path

Given an incomplete Product Repo
When the readiness check is run
Then missing folders or starter artifacts shall be reported as readiness gaps without inventing Product scope, RP scope, RP AC, or RP/RU membership.

### Boundary Path

Given an already initialized Product Repo
When bootstrap is run again
Then existing files shall not be overwritten, and the result shall remain idempotent.

## AC-002 Release Package Records Are Complete or Actionable

### Happy Path

Given a responsible owner follows the RP creation guide
When the RP record is checked
Then `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md` shall be present.

### Failure Path

Given an RP record with missing required artifacts
When the RP record is checked
Then each missing artifact shall be reported as a completeness gap with owner action.

### Boundary Path

Given optional or future RP artifacts are absent
When the RP record is checked
Then the check shall not fail unless the artifact is required for the requested operation.

## AC-003 AC Intake Preserves Owner-Authored Truth

### Happy Path

Given an RP feature spec and RP-level AC authored by Product Owner, PM, or SA
When the framework performs intake
Then the intake result shall preserve owner-authored AC text, stable AC IDs, readiness classification, and linked product E2E context when present.

### Failure Path

Given ambiguous, incomplete, or conflicting AC
When intake is performed
Then the AC shall be marked as not ready for executable generation with owner action.

### Boundary Path

Given missing behavior, input, output, or pass/fail rules
When intake is performed
Then the framework shall not author, rewrite, or invent primary RP behavior or formal RP AC.

## AC-004 Generated Execution Artifacts Block Unsafe Execution

### Happy Path

Given owner-authored Product/RP/RU mapping has been translated by the Agent Skill
When the generated framework artifacts are checked
Then `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, and `traceability_map.yaml` shall declare the selected tests, logical targets, dependency semantics, execution mode, environment references, provider contract references, and opaque product trace labels.

And provider contract references shall identify the runner, binding, fixture, expected-result reader, verify provider, observation provider, or evidence capability that DSL tests may use.

And each provider contract required for execution shall resolve to one provider capability registry entry with explicit provider family, provider type, required fields, runtime support status, allowed execution mode, and evidence outputs.

And framework checks shall treat RP/RU IDs, implementation language, release manifest, and SIT topology as opaque traceability or Agent Skill input, not as runtime decision rules.

And the Agent Skill shall produce a mapping explanation report that records selected runner, selected run profile, strategy selection reason, source facts used, unresolved assumptions, and validation warnings.

### Failure Path

Given missing, ambiguous, unsupported, or unsafe generated suite/run/environment/provider data
When regression execution is requested
Then execution shall be blocked before adapter/provider execution and the report shall include the blocking field and owner action.

And the report shall state whether the block was caused by suite manifest, run profile, logical target dependency, execution mode, environment binding, provider capability registry status, or provider contract configuration.

Given Product/RP/RU mapping is incomplete or inconsistent
When the Agent Skill translation is requested
Then the Agent Skill shall report product-side readiness gaps and shall not ask the framework core to infer missing topology.

### Boundary Path

Given a generated run plan with multiple logical targets
When dependency semantics are checked
Then execution order shall be derived from declared target dependencies, not inferred from Product/RP/RU file order, naming, language, or topology.

Given a DSL test target or provider contract could match more than one logical target or provider contract
When dry-run resolves provider contracts
Then execution shall be blocked until the target or provider reference is unambiguous.

## AC-005 Agent Drafting Is Gated by Readiness

### Happy Path

Given ready RP AC and ready execution context artifacts
When the agent skill performs readiness and test drafting
Then the AC may produce a `draft_executable_test_case` artifact.

And the draft shall use execution-focused DSL v1 sections needed by the 7 AP: identity/status/revision, source refs, optional labels, targets, scenario, setup fixtures, execute operations and outputs, expected_results, verify rules, evidence requirements, and runtime policy.

And the draft shall use readable operations such as `run_batch`, `execute_command`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `request_reply_message`, or `run_application`, not `call_ru` or `target_ru_id`.

### Failure Path

Given ambiguous AC
When the agent skill performs readiness and test drafting
Then the AC shall be marked `not_ready_for_generation` and no executable test shall be produced.

Given a generated executable draft, skeleton, or update proposal contains legacy-only fields or governance-heavy fields
When the draft is checked for execution readiness
Then it shall be rejected before it can be promoted or used as an execution-eligible test case.

### Boundary Path

Given ready AC with incomplete execution context
When the agent skill performs readiness and test drafting
Then only a `draft_test_skeleton` may be produced.

The skeleton shall use v1 identity/status/revision, `source_refs`, optional `labels`, include readiness gaps, and omit executable sections that require missing target, setup, execute, expected-result, verify, evidence, or runtime context.

Existing checked-in approved tests for the same RP AC shall not be silently overwritten.

When an approved test already exists for the same RP AC, generation shall create an `update_proposal` with v1 identity/status/revision, `source_refs`, optional `labels`, `replaces`, source fingerprint, and readiness gaps instead of modifying the approved test in place.

## AC-006 Expected Results and Verify Rules Are Explicit

### Happy Path

Given an expected-result artifact, schema, contract, DB query ref, event expectation, file expectation, deterministic inline expectation, or provider metadata expectation used for regression evaluation
When it is checked for regression eligibility
Then the DSL shall reference reusable truth through `expected_results` when needed and a `verify` item with explicit captured-output `actual` and `expected`, provider metadata plus `expected`, or explicit `target` plus `query` or `event` semantics for state and event checks.

### Failure Path

Given a missing expected result, missing required captured-output `actual`, missing `expected`, missing provider metadata required by the verify type, missing or unresolved `selector` for structured output verification, unsupported verify type, or missing query/event reference
When normal regression execution is requested
Then the run shall be blocked before adapter execution or assertion evaluation and report the missing field, verify ID, and owner action.

### Boundary Path

Given a deterministic verification that does not require an expected-result artifact
When the DSL test is checked
Then the `verify` item shall still declare its type, actual source, expected value or state/event expectation, and remain traceable to RP AC.

Given a `json_path_equals` or `json_path_absent` verify rule
When the DSL test is checked
Then `actual` shall reference a captured output and `selector` shall declare the JSON/YAML path being evaluated. Compatibility aliases `path` or `json_path` may be read from migrated artifacts, but new generated DSL shall use `selector`.

Given a `response_status_equals` verify rule
When the DSL test is checked
Then it shall declare `expected`, and it may omit `actual` only when the selected request/response provider supplies HTTP status metadata in execution evidence.

## AC-007 Regression Runs Produce Durable Evidence

### Happy Path

Given execution-eligible DSL test cases for the pilot RP
When regression is run in an allowed execution mode
Then the suite execution shall produce one batch evidence record with batch ID, execution mode, environment reference, included run IDs, test case IDs, acceptance-criteria source refs, optional report labels, and final batch status.

And each executed test case shall produce durable run evidence with batch ID, acceptance-criteria source ref, optional report labels, test case ID, run ID, execution mode, resolved targets, setup fixture results, execute outputs, expected result refs, verify results, provider contracts used, provider family, provider type, registry status, provider contract path, adapter/provider result, cleanup result, and final pass/fail status.

And when a DSL test declares `parameters.ref` and `parameters.bind_as`, each resolved parameter-set case shall produce a separate run ID and durable run evidence containing `parameter_case_id` and resolved parameter values or safe references.

And an execution-focused DSL v1 test case stored under `tests/approved/` with `status: active` shall run without requiring legacy-only fields in the test case body.

And the selected heterogeneous pilot shall produce evidence for each registered provider family selected for the RP: request/response, messaging, DB fixture, deployment readiness, and file/batch when the RP uses it. External runner evidence is required only when an approved escape-hatch contract is selected.

For framework verification, local/mock provider-family evidence may satisfy the framework behavior portion of this AC. It does not satisfy native Kafka/NATS/gRPC/K8s/VM pilot acceptance unless those native runtimes are implemented and selected by the pilot RP.

### Failure Path

Given a regression run that fails during adapter execution, assertion evaluation, observation collection, or cleanup
When the run ends
Then run evidence shall preserve the failure status, actual outputs or references, logs, cleanup result, and owner-actionable failure detail.

And the batch evidence shall include the failed run status without overwriting evidence from other runs in the same batch.

### Boundary Path

Given checked-in tests already exist
When regression execution runs
Then checked-in tests shall not be regenerated by default.

Given two execution-eligible tests exist in the same RP
When regression execution runs
Then the tests shall produce two different run IDs and two different run evidence directories.

Given one execution-eligible test references a reviewed parameter set with two cases through `parameters.ref` and `parameters.bind_as`
When regression execution runs
Then the two parameter cases shall produce two different run IDs and two different run evidence directories while preserving the same test case ID and acceptance-criteria source ref.

## AC-008 Unsafe or Incomplete Regression Execution Is Blocked

### Happy Path

Given all required test artifacts, generated suite/run/environment artifacts, provider contracts, provider capability registry entries, expected results, setup fixtures, execute output mappings, verify rules, runtime policy, and environment readiness are available
When regression is requested
Then execution may proceed according to the declared execution mode.

And dry-run shall show which AP gates passed before real adapter execution is allowed.

And dry-run shall show that the DSL v1 validation gate passed before provider contract binding and adapter/provider dispatch.

### Failure Path

Given required test artifacts, generated suite/run/environment artifacts, provider contracts, provider capability registry entries, expected results, setup fixtures, execute output mappings, verify rules, runtime policy, deployment evidence, or environment readiness are missing or unsupported
When regression is requested
Then execution shall stop before unsafe adapter/provider execution and report the blocking reason with owner action.

And the blocking report shall include the AP name and DSL field or provider contract path that caused the stop.

And blocked run evidence shall preserve enough normalized DSL runtime context to explain which targets, setup fixtures, execute operations, expected-result refs, verify rules, evidence refs, and runtime policy were resolved before blocking.

And when a selected pilot provider family or provider type is missing, unsupported, ambiguous, or only available as an unapproved escape hatch, the dry-run report shall name the provider family, provider type, capability, affected logical target, provider contract path, registry status, and required owner action.

Given a new DSL test case uses multiple executable `execute[]` steps, `call_ru`, `target_ru_id`, `package_inputs`, `oracles`, missing `execute[].outputs`, missing `runtime.timeout`, missing `runtime.retry.max_attempts`, unsupported `verify[].type`, missing structured verify selector, or governance-heavy approval/release fields
When dry-run or execution is requested
Then the framework shall block during Definition and Validation or Planning and Binding before provider dispatch.

Given a DSL test case uses missing `parameters.ref`, missing `parameters.bind_as`, an unreadable parameter set, missing or duplicate parameter case IDs, missing case values, or `${param.<bind_as>.<field>}` references that cannot be resolved
When dry-run or execution is requested
Then the framework shall block during Definition and Validation or Planning and Binding before provider dispatch and report the parameter field path and owner action.

### Boundary Path

Given `sit_deployed` execution mode
When deployment or environment readiness evidence is missing
Then regression shall not start.

Given the selected heterogeneous pilot includes K8s and VM deployment readiness
When one deployment readiness provider is missing or not configured
Then only the affected logical target path shall be blocked, and independent target paths may proceed when their dependencies are satisfied.

For multi-target run plans, execution shall respect declared dependency semantics and stop downstream execution when a required upstream target validation fails.

Given an external runner provider contract without approval metadata, bounded timeout, declared inputs/outputs, or evidence map
When dry-run is requested
Then execution shall be blocked before runner invocation and the report shall state that the standard built-in provider path must be used or an escape-hatch approval must be supplied.

## AC-009 Coverage and Evidence Are Release Review Ready

### Happy Path

Given RP AC inventory, approved tests, batch execution evidence, run evidence, truth source artifacts, and approved exclusions
When the evidence package is produced for a selected batch
Then coverage shall be calculated against automatable RP-level AC, and every approved test and evidence item shall trace to `source_refs.acceptance_criteria`, batch ID, run ID, test case ID, and optional Product/RP labels supplied by generated artifacts.

And execution-focused DSL v1 `source_refs.acceptance_criteria`, optional `labels`, and generated `traceability_map.yaml` shall be sufficient for coverage reporting when normalized run evidence exists.

Given two automatable AC and two approved tests in one batch
When both runs pass and each run traces to a different AC
Then coverage shall be 100%.

### Failure Path

Given missing evidence, missing source refs or required reporting labels, unapproved exclusions, or unresolved failures
When the evidence package is produced
Then the package shall report the gap and shall not claim review-ready coverage.

Given a v1 test execution passes but the selected batch report cannot resolve the test case to an acceptance-criteria source ref and required reporting labels from v1 `source_refs`, optional `labels`, or `traceability_map.yaml`
When the evidence package is produced
Then the package shall not be review-ready and shall report the source-reference or report-label normalization gap.

Given two automatable AC and two approved tests in one batch
When one run passes and one run fails
Then coverage shall count only the AC covered by the passed traceable run and shall not be review-ready.

### Boundary Path

Given manual-only or waived AC
When coverage is calculated
Then those AC may be excluded from the automatable denominator only when approval records exist.

Given multiple passed tests cover the same AC in one batch
When coverage is calculated
Then that AC shall be counted once.

Given multiple parameter-set cases for one test cover the same AC in one batch
When coverage is calculated
Then that AC shall be counted once, while each parameter case remains visible in run evidence.

Given a single run report exists
When RP release coverage is calculated
Then the framework shall use batch-level evidence rather than single-run evidence.

## AC-010 Framework Verification and RP Regression Execution Are Separated

### Happy Path

Given a framework code change
When `./mvnw test` is run
Then the result shall verify framework unit/component behavior only, using Maven Surefire reports and local test fixtures.

Given a framework integration verification run
When `./mvnw verify` is run
Then the result shall verify framework behavior against a sample Product Repo fixture without requiring SIT/UAT deployment.

Given a downstream Product Release Package needs regression validation
When product-aware tooling generates framework-readable artifacts and invokes the generic runner
Then the framework shall produce batch/run evidence that can be packaged under that Product Repo with RP trace labels.

### Failure Path

Given a missing or unsupported sample fixture
When framework integration verification is run
Then `./mvnw verify` shall fail as framework verification failure and shall not create or claim downstream release evidence.

Given a missing RP environment reference, provider contract, approved test, or readiness evidence
When RP Regression Execution is requested
Then the CLI shall block or fail the RP run with owner-actionable evidence according to AC-008.

### Boundary Path

Given sample Product Repo fixture evidence produced during framework verification
When release coverage is calculated for a real Product/RP
Then fixture evidence shall not count toward the downstream Product/RP release denominator or numerator.

Given SIT/UAT validation is required for an RP
When no deployed target versions or environment readiness evidence exist
Then framework Maven tests may still pass, but RP Regression Execution in `sit_deployed` mode shall not start.
