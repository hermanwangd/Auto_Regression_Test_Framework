# 04. Acceptance Criteria

These criteria validate the framework product baseline for M1. Formal release acceptance criteria for downstream product features remain RP-level artifacts inside each Release Package.

## AC-001 Product Repo Bootstrap Is Ready

Given an empty or incomplete Product Repo
When the bootstrap/readiness command or workflow is run
Then the agreed docs lifecycle folders shall exist, required starter artifact locations shall be present or reported missing, and the readiness report shall list pass/fail status, missing items, owner action, and next required step.

## AC-002 Release Package Can Be Created and Checked

Given a responsible owner follows the RP creation guide
When an RP folder or record is checked
Then `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md` shall be present or reported as completeness gaps with required owner action.

## AC-003 RP Feature Spec and AC Intake Does Not Invent Truth

Given an RP feature spec and RP-level AC authored by Product Owner, PM, or SA
When the framework performs intake
Then it shall preserve stable AC IDs, classify AC readiness, link product E2E context when present, and shall not author or invent primary RP behavior or formal RP AC.

## AC-004 RP/RU Mapping Completeness Blocks Unsafe Execution

Given an RP/RU mapping created by the responsible owner
When the framework checks the mapping
Then each RU entry shall declare repo, owner, unit type, version reference, validation boundary, execution mode, deployment requirement, environment reference, adapter or adapter mode, evidence responsibility, and dependency order; missing required fields shall block regression execution.

## AC-005 Agent Drafts Tests Only From Ready Inputs

Given RP AC and execution context artifacts
When the agent skill performs readiness and test drafting
Then ambiguous AC shall be marked `not_ready_for_generation`, ready AC with incomplete execution context shall produce only `draft_test_skeleton`, and fully ready AC plus execution context may produce `draft_executable_test_case`.

Existing checked-in test cases for the same RP AC shall be detected before generating replacements. Approved tests shall not be silently overwritten.

## AC-006 Expected Results Require Explicit Source and Approval

Given an agent-drafted expected result
When it is created from RP AC, RP feature spec, package inputs, and source context
Then the artifact shall include source references, assumptions, unresolved gaps, and status `draft`, `blocked`, or `approved_for_regression`; only approved expected results may be used as regression truth.

## AC-007 Release Package Regression Executes With Evidence

Given checked-in approved or execution-eligible test cases, expected results, package inputs, fixtures, adapter config, and RP/RU mapping
When the regression workflow runs for the pilot RP
Then it shall read test cases from the RP `tests/` folder, resolve inputs, set up fixtures, execute or validate through the adapter, collect actual results, run assertions, clean up fixtures, and emit raw execution evidence without regenerating test cases by default.

For multi-RU RPs, execution shall follow declared dependency order. For `sit_deployed` execution, regression shall start only after required deployment and environment readiness evidence is present.

## AC-008 Coverage and Evidence Are Release Review Ready

Given RP AC inventory, generated tests, execution evidence, expected-result artifacts, and approved exclusions
When the evidence package is produced
Then coverage shall be calculated against automatable RP-level AC, every generated test and evidence item shall trace to RP ID and AC ID, failures shall include expected and actual results, and manual-only or waived AC shall be excluded only with approval records.
