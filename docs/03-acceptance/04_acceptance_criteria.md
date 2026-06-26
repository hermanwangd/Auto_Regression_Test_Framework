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
| AC-005 | Definition and Validation / Planning and Binding | Agent-created DSL drafts are allowed only when AC and execution context readiness are explicit. |
| AC-006 | Definition and Validation / Oracle and Assertion Engine | DSL oracles/assertions must point to approved truth sources or explicit decision rules. |
| AC-007 | Planning and Binding / Fixture and State Manager / Execution Engine / Oracle and Assertion Engine / Evidence and Reporting | Checked-in DSL tests must execute through the full 7 AP flow and produce durable evidence. |
| AC-008 | Discovery and Context / Planning and Binding / Fixture and State Manager / Execution Engine | Unsafe or incomplete DSL execution is blocked with owner-actionable reasons. |
| AC-009 | Evidence and Reporting | DSL traceability and run evidence must support RP release review and coverage calculation. |

Minimum verification rule for DSL/AP clarity:

- Every readiness, generation, dry-run, execution, or evidence report that references DSL behavior shall include `ap`, `field_path` or `contract_path`, `test_case_id` when applicable, `ac_id` when applicable, `reason`, and `owner_action`.
- DSL validation failures shall identify whether the problem belongs to DSL syntax, lifecycle/approval state, RP/RU mapping, provider contract, fixture policy, oracle/assertion truth, environment readiness, or evidence completeness.
- A missing provider contract, unsupported DSL capability, unapproved expected result, missing cleanup policy, or missing environment readiness shall block before adapter execution starts.
- Baseline, spec, architecture, and AC documents shall use the same seven AP names and shall not introduce hidden AP-level components.
- Every required or conditional DSL field shall map to a primary AP consumer and have a clear reason for being required.
- Provider implementation settings shall be validated through RP/RU mapping or provider contracts, not embedded directly inside DSL test cases.
- A feature is not implementation-ready when its DSL fields, provider contract paths, assertion/oracle source, fixture lifecycle, or evidence outputs cannot be explained through the 7 AP flow.

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

### Failure Path

Given missing, ambiguous, or unsafe mapping data
When regression execution is requested
Then execution shall be blocked before adapter/provider execution and the report shall include the blocking field and owner action.

And the report shall state whether the block was caused by RP membership, RU version, execution mode, environment reference, dependency semantics, or provider contract configuration.

### Boundary Path

Given an RP with multiple RUs
When dependency semantics are checked
Then execution order shall be derived from declared dependencies, not inferred from file order or naming.

## AC-005 Agent Drafting Is Gated by Readiness

### Happy Path

Given ready RP AC and ready execution context artifacts
When the agent skill performs readiness and test drafting
Then the AC may produce a `draft_executable_test_case` artifact.

And the draft shall include the DSL sections needed by the 7 AP: identity/traceability, scenario, execution target, logical inputs, steps, oracle or assertion rule, evidence requirements, and cleanup policy when state is mutated.

### Failure Path

Given ambiguous AC
When the agent skill performs readiness and test drafting
Then the AC shall be marked `not_ready_for_generation` and no executable test shall be produced.

### Boundary Path

Given ready AC with incomplete execution context
When the agent skill performs readiness and test drafting
Then only a `draft_test_skeleton` may be produced.

Existing checked-in approved tests for the same RP AC shall not be silently overwritten.

## AC-006 Truth Sources Require Explicit Source and Approval

### Happy Path

Given an expected-result artifact or oracle truth source used for regression evaluation
When it is checked for regression eligibility
Then it shall include source references, approval status, and assumptions or unresolved gaps when applicable.

### Failure Path

Given a missing, draft, blocked, or unapproved truth source
When normal regression execution is requested
Then the run shall be blocked before assertion evaluation and report the missing approval or source gap.

### Boundary Path

Given an inline decision rule that does not require an expected-result artifact
When the DSL test is checked
Then the assertion shall still declare an oracle or inline decision rule and remain traceable to RP AC.

## AC-007 Regression Runs Produce Durable Evidence

### Happy Path

Given an approved or execution-eligible DSL test case for the pilot RP
When regression is run in an allowed execution mode
Then the run shall produce durable evidence with RP ID, AC ID, test case ID, run ID, execution mode, resolved bindings, provider contracts used, adapter result, assertion result, cleanup result, and final pass/fail status.

### Failure Path

Given a regression run that fails during adapter execution, assertion evaluation, observation collection, or cleanup
When the run ends
Then evidence shall preserve the failure status, actual outputs or references, logs, cleanup result, and owner-actionable failure detail.

### Boundary Path

Given checked-in tests already exist
When regression execution runs
Then checked-in tests shall not be regenerated by default.

## AC-008 Unsafe or Incomplete Regression Execution Is Blocked

### Happy Path

Given all required test artifacts, mappings, provider contracts, truth sources, fixture policy, and environment readiness are available
When regression is requested
Then execution may proceed according to the declared execution mode.

And dry-run shall show which AP gates passed before real adapter execution is allowed.

### Failure Path

Given required test artifacts, mappings, provider contracts, truth sources, fixture policy, deployment evidence, or environment readiness are missing or unsupported
When regression is requested
Then execution shall stop before unsafe adapter/provider execution and report the blocking reason with owner action.

And the blocking report shall include the AP name and DSL field or provider contract path that caused the stop.

### Boundary Path

Given `sit_deployed` execution mode
When deployment or environment readiness evidence is missing
Then regression shall not start.

For multi-RU RPs, execution shall respect declared dependency semantics and stop downstream execution when a required upstream validation fails.

## AC-009 Coverage and Evidence Are Release Review Ready

### Happy Path

Given RP AC inventory, generated tests, execution evidence, truth source artifacts, and approved exclusions
When the evidence package is produced
Then coverage shall be calculated against automatable RP-level AC, and every generated test and evidence item shall trace to RP ID and AC ID.

### Failure Path

Given missing evidence, missing traceability, unapproved exclusions, or unresolved failures
When the evidence package is produced
Then the package shall report the gap and shall not claim review-ready coverage.

### Boundary Path

Given manual-only or waived AC
When coverage is calculated
Then those AC may be excluded from the automatable denominator only when approval records exist.
