# 04. Acceptance Criteria

These criteria validate observable outcomes for the framework product baseline in M1. Formal release acceptance criteria for downstream product features remain RP-level artifacts inside each Release Package.

Each framework AC covers the happy path, failure path, and boundary path so generated implementation tasks and regression tests can be traced back to explicit behavior.

The DSL and 7 AP are validated through these AC rather than as separate, abstract concepts:

| AC | Primary AP Focus | DSL Relationship |
|---|---|---|
| AC-001 | Definition and Validation / Discovery and Context | Product Repo structure must be ready before DSL artifacts are created or executed. |
| AC-002 | Definition and Validation / Discovery and Context | RP records must expose the artifact locations where DSL tests, expected results, and evidence live. |
| AC-003 | Definition and Validation | Owner-authored RP AC remain the source of truth that DSL tests trace to. |
| AC-004 | Discovery and Context / Planning and Binding | RP/RU mapping defines execution boundary and provider contracts that DSL tests reference logically. |
| AC-005 | Definition and Validation / Planning and Binding | Agent-created execution-focused DSL drafts are allowed only when AC and execution context readiness are explicit. |
| AC-006 | Definition and Validation / Oracle and Assertion Engine | DSL `expected_results` and `verify` rules must point to explicit truth sources or deterministic decision rules. |
| AC-007 | Planning and Binding / Fixture and State Manager / Execution Engine / Oracle and Assertion Engine / Evidence and Reporting | Checked-in DSL tests must execute through the full 7 AP flow and produce durable evidence. |
| AC-008 | Discovery and Context / Planning and Binding / Fixture and State Manager / Execution Engine | Unsafe or incomplete DSL execution is blocked with owner-actionable reasons. |
| AC-009 | Evidence and Reporting | DSL traceability and run evidence must support RP release review and coverage calculation. |
| AC-010 | Definition and Validation / Evidence and Reporting | Framework Verification and RP Regression Execution must remain separate execution lines with separate commands and evidence meaning. |

Minimum verification rule for DSL/AP clarity:

- Every readiness, generation, dry-run, execution, or evidence report that references DSL behavior shall include `ap`, `field_path` or `contract_path`, `test_case_id` when applicable, `ac_id` when applicable, `reason`, and `owner_action`.
- DSL validation failures shall identify whether the problem belongs to DSL syntax, execution lifecycle state, traceability, targets, setup fixtures, execute outputs, expected results, verify rules, RP/RU mapping, provider contract, environment readiness, runtime policy, or evidence completeness.
- New execution-focused DSL artifacts shall use `dsl_version`, `test_case_id`, `status`, `revision`, `traceability`, `targets`, `scenario`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime` as the core contract before F007 provider runtime execution.
- New execution-focused DSL artifacts shall not contain legacy-only fields such as `rp_id`, `ac_id`, `execution_target`, `target_ru_id`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, or `policy`.
- New execution-focused DSL artifacts shall not contain governance-heavy fields such as `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or governance workflow state.
- A missing provider contract, unsupported DSL capability, missing expected result, missing cleanup reference, missing execute output, invalid verify rule, or missing environment readiness shall block before adapter execution starts.
- Baseline, spec, architecture, and AC documents shall use the same seven AP names and shall not introduce hidden AP-level components.
- Every required or conditional DSL field shall map to a primary AP consumer and have a clear reason for being required.
- Provider implementation settings shall be validated through RP/RU mapping or provider contracts, not embedded directly inside DSL test cases.
- Provider contracts shall be validated against a capability registry by explicit `provider_family`, `provider_type`, required fields, supported runtime status, execution mode, and evidence output requirements.
- External runner use shall require explicit approval metadata and shall be reported as an escape hatch, not as the standard provider extension path.
- A feature is not implementation-ready when its DSL fields, provider contract paths, expected-result source, verify rule, setup lifecycle, execute outputs, runtime policy, or evidence outputs cannot be explained through the 7 AP flow.
- Maven framework verification evidence shall not be treated as downstream Product/RP release evidence.
- CLI RP regression evidence shall identify the RP, batch, environment, test cases, and AC covered by the selected RP execution.
- Execution-focused DSL v1 support is not accepted until one `tests/approved/` artifact with `status: active` can pass `run` and then produce a review-ready `report --batch-id` using v1 traceability.

Acceptance is split by evidence source:

| Acceptance Scope | Evidence Source | Can Be Accepted Now When |
|---|---|---|
| Framework behavior | `./mvnw test`, `./mvnw verify`, sample Product Repo fixtures, and local/mock provider-family cases | The AC behavior is about framework parsing, readiness, blocking, execution, evidence writing, reporting, or verification boundary. |
| Downstream RP regression capability | `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` against owner-provided RP artifacts | The run uses real RP AC, approved tests, approved truth sources, selected provider contracts, and Product Repo evidence. |
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

## AC-004 RP/RU Mapping Blocks Unsafe Execution

### Happy Path

Given an owner-authored RP/RU mapping
When the mapping is checked
Then each RU entry shall declare required execution metadata, dependency semantics, environment reference, and provider contract references.

And provider contract references shall identify the adapter, binding, fixture, oracle, assertion, or observation capability that DSL tests may use.

And each provider contract required for execution shall resolve to one provider capability registry entry with explicit provider family, provider type, required fields, runtime support status, allowed execution mode, and evidence outputs.

### Failure Path

Given missing, ambiguous, unsupported, or unsafe mapping data
When regression execution is requested
Then execution shall be blocked before adapter/provider execution and the report shall include the blocking field and owner action.

And the report shall state whether the block was caused by RP membership, RU version, execution mode, environment reference, dependency semantics, provider capability registry status, or provider contract configuration.

### Boundary Path

Given an RP with multiple RUs
When dependency semantics are checked
Then execution order shall be derived from declared dependencies, not inferred from file order or naming.

Given a DSL test target or provider contract could match more than one RU
When dry-run resolves provider contracts
Then execution shall be blocked until the target RU or provider reference is unambiguous.

## AC-005 Agent Drafting Is Gated by Readiness

### Happy Path

Given ready RP AC and ready execution context artifacts
When the agent skill performs readiness and test drafting
Then the AC may produce a `draft_executable_test_case` artifact.

And the draft shall use execution-focused DSL v1 sections needed by the 7 AP: identity/status/revision, traceability, targets, scenario, setup fixtures, execute operations and outputs, expected_results, verify rules, evidence requirements, and runtime policy.

And the draft shall use readable operations such as `run_batch`, `execute_command`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `request_reply_message`, or `run_application`, not `call_ru` or `target_ru_id`.

### Failure Path

Given ambiguous AC
When the agent skill performs readiness and test drafting
Then the AC shall be marked `not_ready_for_generation` and no executable test shall be produced.

Given a generated executable draft contains legacy-only fields or governance-heavy fields
When the draft is checked for execution readiness
Then it shall be rejected before it can be promoted to an execution-eligible test case.

### Boundary Path

Given ready AC with incomplete execution context
When the agent skill performs readiness and test drafting
Then only a `draft_test_skeleton` may be produced.

Existing checked-in approved tests for the same RP AC shall not be silently overwritten.

## AC-006 Expected Results and Verify Rules Are Explicit

### Happy Path

Given an expected-result artifact, schema, contract, DB query ref, event expectation, or file expectation used for regression evaluation
When it is checked for regression eligibility
Then the DSL shall reference it through `expected_results` and a `verify` item with explicit `actual` and `expected`, or with explicit `target` plus `query` or `event` semantics for state and event checks.

### Failure Path

Given a missing expected result, missing `actual`, missing `expected`, unresolved selector, unsupported verify type, or missing query/event reference
When normal regression execution is requested
Then the run shall be blocked before adapter execution or assertion evaluation and report the missing field, verify ID, and owner action.

### Boundary Path

Given a deterministic verification that does not require an expected-result artifact
When the DSL test is checked
Then the `verify` item shall still declare its type, actual source, expected value or state/event expectation, and remain traceable to RP AC.

## AC-007 Regression Runs Produce Durable Evidence

### Happy Path

Given execution-eligible DSL test cases for the pilot RP
When regression is run in an allowed execution mode
Then the RP execution shall produce one batch evidence record with RP ID, batch ID, execution mode, environment reference, included run IDs, test case IDs, AC IDs, and final batch status.

And each executed test case shall produce durable run evidence with RP ID, batch ID, AC ID, test case ID, run ID, execution mode, resolved targets, setup fixture results, execute outputs, expected result refs, verify results, provider contracts used, provider family, provider type, registry status, provider contract path, adapter/provider result, cleanup result, and final pass/fail status.

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

## AC-008 Unsafe or Incomplete Regression Execution Is Blocked

### Happy Path

Given all required test artifacts, mappings, provider contracts, provider capability registry entries, expected results, setup fixtures, execute output mappings, verify rules, runtime policy, and environment readiness are available
When regression is requested
Then execution may proceed according to the declared execution mode.

And dry-run shall show which AP gates passed before real adapter execution is allowed.

And dry-run shall show that the DSL v1 validation gate passed before provider contract binding and adapter/provider dispatch.

### Failure Path

Given required test artifacts, mappings, provider contracts, provider capability registry entries, expected results, setup fixtures, execute output mappings, verify rules, runtime policy, deployment evidence, or environment readiness are missing or unsupported
When regression is requested
Then execution shall stop before unsafe adapter/provider execution and report the blocking reason with owner action.

And the blocking report shall include the AP name and DSL field or provider contract path that caused the stop.

And when a selected pilot provider family or provider type is missing, unsupported, ambiguous, or only available as an unapproved escape hatch, the dry-run report shall name the provider family, provider type, capability, affected RU, provider contract path, registry status, and required owner action.

Given a new DSL test case uses `call_ru`, `target_ru_id`, `package_inputs`, `oracles`, missing `execute[].outputs`, missing `runtime.timeout`, missing `runtime.retry.max_attempts`, unsupported `verify[].type`, or governance-heavy approval/release fields
When dry-run or execution is requested
Then the framework shall block during Definition and Validation or Planning and Binding before provider dispatch.

### Boundary Path

Given `sit_deployed` execution mode
When deployment or environment readiness evidence is missing
Then regression shall not start.

Given the selected heterogeneous pilot includes K8s and VM deployment readiness
When one deployment readiness provider is missing or not configured
Then only the affected RU path shall be blocked, and independent RU paths may proceed when their dependencies are satisfied.

For multi-RU RPs, execution shall respect declared dependency semantics and stop downstream execution when a required upstream validation fails.

Given an external runner provider contract without approval metadata, bounded timeout, declared inputs/outputs, or evidence map
When dry-run is requested
Then execution shall be blocked before runner invocation and the report shall state that the standard built-in provider path must be used or an escape-hatch approval must be supplied.

## AC-009 Coverage and Evidence Are Release Review Ready

### Happy Path

Given RP AC inventory, approved tests, batch execution evidence, run evidence, truth source artifacts, and approved exclusions
When the evidence package is produced for a selected batch
Then coverage shall be calculated against automatable RP-level AC, and every approved test and evidence item shall trace to RP ID, batch ID, run ID, test case ID, and AC ID.

And execution-focused DSL v1 `traceability.package_id` and `traceability.acceptance_criteria_id` shall be sufficient for coverage reporting when normalized run evidence exists.

Given two automatable AC and two approved tests in one batch
When both runs pass and each run traces to a different AC
Then coverage shall be 100%.

### Failure Path

Given missing evidence, missing traceability, unapproved exclusions, or unresolved failures
When the evidence package is produced
Then the package shall report the gap and shall not claim review-ready coverage.

Given a v1 test execution passes but the selected batch report cannot resolve the test case to RP ID and AC ID from v1 traceability
When the evidence package is produced
Then the package shall not be review-ready and shall report the traceability normalization gap.

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
When `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` is run
Then the framework shall produce RP batch/run evidence under that Product Repo.

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
When no deployed RU versions or environment readiness evidence exist
Then framework Maven tests may still pass, but RP Regression Execution in `sit_deployed` mode shall not start.
