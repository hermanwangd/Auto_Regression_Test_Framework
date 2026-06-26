# 03. Product Baseline and Feature Specs

## 3.1 Product Baseline

This product is a product spec-driven Release Package Regression Framework. It helps product developers define package-release scope, derive regression tests from RP-level acceptance criteria, execute repeatable package regression checks, and produce evidence for release review.

The product is not tied to one implementation repo. It uses the Product Repo as the initialized source-of-truth structure for specs, release packages, and regression evidence, and treats Release Packages as the release and regression evidence unit.

```text
Virtual Product
-> Product Specs and E2E Scenarios
-> Release Packages
-> RP/RU Mapping
-> Regression Artifacts
-> Evidence Package
```

## 3.2 Product Boundary

### In Scope

- Product Repo lifecycle structure for specs, acceptance, planning, validation, release, operations, and change control.
- Release Package creation guide and package-release source of truth.
- RP-level feature specs and formal RP acceptance criteria.
- RP/RU repo mapping for implementation ownership and evidence contribution.
- Agent-assisted generation of regression tests and expected results from RP AC.
- Generic test execution process for release packages.
- Coverage, traceability, evidence, and release review inputs.

### Out of Scope for M1

- Product-level formal AC as a release denominator.
- RU repo-owned primary specs or primary acceptance criteria.
- Full dashboard experience.
- Support for every release package type.
- Fully automated release approval.
- Unreviewed agent-generated expected results.

## 3.3 M1 Pilot Boundary

M1 validates the product baseline through one data pipeline release package pilot.

M1 includes:

- One RP with a bounded set of release unit repos.
- Local or CI execution without requiring a persistent service.
- Small reviewable fixtures or documented fixture generation.
- RP-level AC readiness classification.
- Agent-generated draft regression tests and expected results for ready RP AC.
- Reviewable coverage and evidence package for the pilot RP.

M1 excludes:

- Cross-package orchestration.
- Production data unless masked and approved.
- Broad package-type plugin support.
- Dashboard-driven release governance.
- Fully automated waiver, manual-only, or release approval.

## 3.4 Feature List

| Feature ID | Feature | Purpose | M1 |
|---|---|---|---:|
| F001 | Product Repo Bootstrap CLI and Readiness Agent Skill | Initialize the Product Repo structure, produce deterministic readiness reports, and use an agent skill to explain owner-actionable next steps | Yes |
| F002 | Release Package Creation Guide and Completeness Check | Tell owners how to create an RP and check whether required RP artifacts are complete | Yes |
| F003 | RP Feature Spec and AC Intake | Consume Product Owner / PM / SA-authored RP feature specs and formal acceptance criteria | Yes |
| F004 | RP/RU Mapping Intake and Completeness Check | Consume human-authored RP/RU mapping and check whether it is complete for regression execution | Yes |
| F005 | Agent Skill: AC and Execution Context Readiness with Test Drafting | Use agent reasoning to classify AC/execution readiness and draft skeleton or executable regression tests | Yes |
| F006 | Agent Skill: Expected Result Drafting | Draft reviewable expected-result artifacts from explicit RP AC and source context | Yes |
| F007 | Release Package Test Execution | Execute package-level regression using generic input catalog, binding, fixture lifecycle, adapters, assertions, and evidence collection | Yes |
| F008 | Coverage and Evidence Package | Report RP AC coverage, traceability, execution evidence, failures, waivers, and release review inputs | Yes |
| F009 | Advanced Spec Readiness | Detect deeper cross-artifact spec gaps and drift across product, RP, architecture, data, and change history | No |
| F010 | Release Governance Integration | Apply release gate policy, waiver workflow, and Go/No-Go records | No |
| F011 | Package Type Plugin SDK | Support additional release package types through adapter/plugin contracts | No |

## 3.5 Next-Phase Readiness

The baseline is ready for architecture design when these conditions are true:

- Product, RP, and RU responsibilities remain separated as defined in the baseline.
- Release Package remains the release unit and primary regression evidence unit.
- Formal release AC remain RP-level; product E2E scenarios provide context only.
- F001 through F008 have purpose, expected behavior, required mechanism, and acceptance criteria.
- Minimum RP artifacts and RP/RU mapping fields are defined.
- M1 pilot scope, non-goals, success metrics, and human approval boundaries are explicit.

Implementation may start only for slices whose inputs are ready:

- F001/F002 may start with the agreed Product Repo folder structure and RP artifact checklist.
- F003/F004 require at least one pilot RP with owner-provided `rp_feature_spec.md`, `acceptance_criteria.md`, and `rp_ru_mapping.yaml`.
- F005/F006 require RP AC with observable input, behavior, output, and pass/fail expectations.
- F007 requires checked-in executable or approved test artifacts, package inputs, fixture strategy, adapter mode, and expected-result policy.
- F008 requires RP AC inventory, execution evidence format, traceability rules, and approval records for exclusions.

Before full M1 implementation, the responsible owner shall supply the pilot RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, and adapter mode.

## 3.6 CI/CD and Environment Execution Policy

RP regression can run in local, CI, or SIT depending on the RP validation boundary.

- `local_fixture`: run against local fixtures, files, mocks, generated data, or deterministic sample inputs.
- `ci_ephemeral`: run in CI against temporary services, containers, schemas, queues, or jobs provisioned by the pipeline.
- `sit_deployed`: run against already deployed RU versions in SIT when real integration behavior cannot be validated locally or ephemerally.
- `evidence_only`: collect mapped RU evidence when direct framework execution is not possible.

For RPs that include multiple RUs, the framework shall use `rp_ru_mapping.yaml` to determine dependency order, execution mode, environment reference, deployment requirement, validation boundary, and evidence responsibility per RU.

The framework may verify deployment readiness before SIT execution, but M1 does not own CD deployment orchestration. CD or the environment owner must deploy required RU versions before SIT regression starts.

## 3.7 Test Case Lifecycle

Regression test cases are managed as durable RP artifacts. The framework shall not regenerate test cases on every execution run.

The lifecycle is:

```text
RP AC / execution context ready
-> draft test skeleton or draft executable test case
-> product developer review
-> checked-in RP test artifact
-> repeated execution
-> update or retire when RP AC, mapping, input, fixture, adapter, or expected result changes
```

Approved or execution-eligible test cases should be stored in the RP `tests/` folder. In the current Product Repo model this means `docs/08-release/release-packages/<rp_id>/tests/`. If a dedicated RP repository is introduced later, the same `tests/` lifecycle applies inside that repo.

The agent may propose new or updated test cases when source artifacts change, but it shall not silently overwrite checked-in approved tests.

## F001 — Product Repo Bootstrap CLI and Readiness Agent Skill

### Purpose

Initialize the Product Repo folder structure, run deterministic readiness checks, and use an agent skill to explain whether the repo is ready for RP-level regression work.

### Expected Behavior

The framework shall provide a standard Product Repo bootstrap command and a machine-readable readiness check. The bootstrap command shall create the agreed docs lifecycle folders and starter artifact locations. The deterministic readiness check shall report missing folders, missing RP artifacts, missing stable IDs, missing RP/RU mapping, and missing RP AC prerequisites.

The readiness agent skill shall read the readiness report, explain the current repo state, classify whether the repo is ready for RP work, and produce owner-actionable next steps. The agent skill may explain and prioritize gaps, but it shall not invent RP scope, RP AC, or RP/RU membership.

### Required Mechanism

- Initialize the agreed docs lifecycle folders when they do not exist.
- Create starter placeholders or templates for product baseline, RP specs, RP AC, traceability, evidence, release, operations, and change-control records.
- Run a deterministic readiness check that required folders exist.
- Check that at least one RP folder or record can be discovered, or report that RP creation is the next owner action.
- Check that required RP artifacts exist or are reported missing.
- Check that RP artifacts reference stable product, RP, and AC identifiers when those artifacts exist.
- Check that RP/RU mapping exists before regression generation.
- Emit a machine-readable readiness report with pass/fail status, missing items, owner action, and next required step.
- Provide an agent skill that reads the readiness report and returns a human-readable readiness explanation, missing-item summary, owner actions, and next command or document to update.

## F002 — Release Package Creation Guide and Completeness Check

### Purpose

Provide clear instructions for Product Owner, PM, SA, or the responsible owner to create a Release Package in the Product Repo, then check whether the RP is complete enough for downstream regression work.

### Expected Behavior

The Product Repo shall document how to create an RP. The responsible owner follows the instructions to create the RP folder or record and required artifacts.

The readiness check shall verify that each RP has identity, owner, target release, lifecycle status, package type, scope, linked product spec context, and evidence package location.

Each RP shall define the minimum artifact set:

- `package.yaml`
- `rp_feature_spec.md`
- `rp_ru_mapping.yaml`
- `acceptance_criteria.md`
- `tests/`
- `expected-results/`
- `traceability.md`
- `evidence_index.md`

### Required Mechanism

- Provide a documented RP creation checklist.
- Instruct the owner to create one release package folder or record per RP.
- Instruct the owner to define RP metadata in `package.yaml`.
- Instruct the owner to write RP feature behavior in `rp_feature_spec.md`.
- Instruct the owner to list release unit repos in `rp_ru_mapping.yaml`.
- Instruct the owner to place formal RP AC in `acceptance_criteria.md`.
- Instruct the owner to store reviewed test cases under the RP `tests/` folder.
- Instruct the owner to store reviewed expected-result artifacts under `expected-results/`.
- Instruct the owner to create initial `traceability.md` and `evidence_index.md` placeholders.
- Check required files and fields for completeness.
- Report missing RP metadata, missing artifacts, or incomplete references as readiness gaps.

## F003 — RP Feature Spec and AC Intake

### Purpose

Consume and govern RP feature specs and formal acceptance criteria authored by Product Owner, PM, or SA.

### Expected Behavior

Formal AC shall be authored and owned by Product Owner, PM, or SA at RP level. Product-level docs may define intent and E2E scenarios, but RP AC shall be the source for generation, coverage, and release readiness.

The framework shall not invent or author primary RP feature behavior or formal RP AC. It shall consume, validate, classify, and trace those specs for downstream generation and evidence.

### Required Mechanism

- Read RP feature behavior and AC from Product Owner / PM / SA-maintained spec artifacts.
- Require stable AC IDs inside each RP.
- Validate that AC include observable inputs, actions, outputs, and allowed side effects when they are marked ready for generation.
- Classify each AC as automatable, manual-only, partial, waived, or not ready.
- Link product E2E scenarios to one or more RP AC when cross-RP context matters.
- Use RP AC IDs as the denominator for coverage and release evidence.

## F004 — RP/RU Mapping Intake and Completeness Check

### Purpose

Consume and validate the human-authored mapping between an RP and its release unit repos.

### Expected Behavior

The framework shall not decide which RU repos are included in an RP. SA, tech lead, product developer, or the responsible owner defines RP membership in `rp_ru_mapping.yaml`.

The framework shall check that each mapping identifies RU repo, unit type, owner, version reference, validation boundary, execution mode, deployment requirement, environment reference, adapter, evidence responsibility, and dependency order.

### Required Mechanism

- Read `rp_ru_mapping.yaml` created by the responsible owner.
- Check that each mapping entry has `ru_id`, repo, owner, unit type, and version reference.
- Check that each mapping entry declares a validation boundary.
- Check that each mapping entry declares `execution_mode`.
- Check that each mapping entry declares `deployment_required`.
- Check that each mapping entry declares `environment_ref` when `execution_mode` is `ci_ephemeral`, `sit_deployed`, or `evidence_only`.
- Check that each mapping entry assigns an adapter or adapter mode.
- Check that each mapping entry declares evidence responsibility.
- Check dependency order when one RU output becomes another RU input.
- Report missing or incomplete mapping fields as readiness gaps.
- Block regression execution when required RU mapping fields are missing.

## F005 — Agent Skill: AC and Execution Context Readiness with Test Drafting

### Purpose

Use an agent skill to reason over RP specs, RP AC, RP/RU mapping, and execution context before drafting regression tests.

### Expected Behavior

The agent skill shall classify both AC readiness and execution context readiness. It shall not draft tests from ambiguous AC. It may generate a draft test skeleton when AC is ready but execution context is incomplete. It may generate a draft executable regression test case only when both AC and execution context are ready.

Product developers own AC clarification and manual expected-result input. QA or release owners approve manual-only and waiver classifications when those classifications affect release evidence or coverage.

### Required Mechanism

- Read `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, package input references, fixture references, adapter references, and validation boundaries.
- Check AC readiness: AC ID, linked RP feature, observable input, behavior, expected output, and pass/fail condition.
- Check execution context readiness: package input reference, fixture or data source, target RU, execution mode, deployment requirement, environment reference, adapter or adapter mode, validation boundary, assertion type, and expected-result reference status.
- Mark ambiguous AC as `not_ready_for_generation`.
- Generate `draft_test_skeleton` only when AC is ready but execution context is incomplete.
- Generate `draft_executable_test_case` only when AC and execution context are both ready.
- Store generated test drafts as reviewable artifacts instead of transient execution state.
- Detect existing checked-in test cases for the same RP AC before generating replacements.
- Emit update proposals when RP AC, RP/RU mapping, package input, fixture, adapter, or expected-result references change.
- Preserve approved test history by creating revisions or replacement links instead of silently overwriting checked-in tests.
- Reference expected results as `pending`, `missing`, or linked to F006 output; do not generate expected-result truth in F005.
- Emit a generation readiness report listing readiness status, generated artifact type, gaps, owner action, and required approval when classification affects coverage.

## F006 — Agent Skill: Expected Result Drafting

### Purpose

Use an agent skill to draft expected-result artifacts from explicit RP AC, RP feature specs, package inputs, and source context.

### Expected Behavior

The agent skill may draft expected results only when observable inputs, behavior, outputs, and relevant business rules are defined. It shall not invent missing business rules or silently convert assumptions into truth.

Generated expected results shall remain draft until reviewed. Product developers review generated expected results before they are used as regression truth. Release owners may require additional approval when expected results affect release readiness.

### Required Mechanism

- Read `rp_feature_spec.md`, `acceptance_criteria.md`, package input references, fixture or sample data, and linked product spec context.
- Draft expected results as separate reviewable artifacts, not hidden runtime state.
- Attach source references from expected results back to RP AC IDs and input references.
- Mark expected-result artifacts as `draft`, `blocked`, or `approved_for_regression`.
- Record assumptions and unresolved gaps inside the expected-result artifact.
- Mark generation as `blocked` when required business rules, input definitions, or output rules are missing.
- Require product developer approval before using generated expected results as regression truth.
- Preserve diffs when expected results change.

## F007 — Release Package Test Execution

### Purpose

Execute checked-in approved or execution-eligible regression artifacts against a release package using a generic test execution process. M1 validates this process with one data pipeline release package type.

### Expected Behavior

The framework shall resolve package inputs, bind runtime values, confirm environment readiness, set up fixtures, execute or validate through package adapters, collect actual results, run assertions, clean up fixtures, and emit raw execution evidence.

The execution process shall remain package-type-neutral. The M1 adapter implementation may be data-pipeline-specific.

F007 shall not author AC, classify AC readiness, generate tests, regenerate checked-in tests, generate expected results, approve expected results, approve waivers, or decide release readiness.

### Required Mechanism

- Read `package.yaml`, `rp_ru_mapping.yaml`, checked-in test cases from the RP `tests/` folder, package input catalog, fixture references, adapter config, and expected-result artifacts.
- Check that test cases include RP ID, AC ID, test case ID, execution target, and assertion references.
- Check that test cases are `approved_for_regression` or explicitly allowed by the execution policy.
- Check that required expected-result artifacts are `approved_for_regression` or otherwise explicitly allowed by the execution policy.
- Resolve the RP execution mode as `local_fixture`, `ci_ephemeral`, `sit_deployed`, or `evidence_only`.
- For multi-RU RPs, follow declared dependency order and stop when a required upstream RU validation fails.
- Check deployment and environment readiness before running `sit_deployed` tests.
- Reject or mark invalid runs when RP/RU mapping, adapter config, package inputs, fixtures, expected results, required deployment evidence, or environment readiness are missing.
- Resolve logical package inputs to concrete fixture or generated data.
- Bind runtime values from package inputs, context, and previous execution steps.
- Run setup actions needed by the package fixture lifecycle.
- Execute or validate through the configured release package adapter.
- Collect actual outputs, logs, and execution metadata.
- Run assertions against expected results.
- Run cleanup actions and record cleanup evidence.
- Emit raw execution artifacts such as execution report, execution log, actual results, assertion results, cleanup evidence, and failure details.

## F008 — Coverage and Evidence Package

### Purpose

Package RP-level coverage, traceability, raw execution evidence, failures, and approved exclusions into review-ready evidence artifacts.

### Expected Behavior

The framework shall report coverage against automatable RP-level AC, trace generated tests to RP AC, retain raw execution evidence from F007, identify failures with expected and actual results, and include manual-only or waived AC records where approved.

F008 shall not execute tests, generate tests, generate expected results, approve waivers, change the coverage denominator, or decide release Go/No-Go. Product developers own evidence review for implementation correctness. QA or release owners approve waivers, manual-only exclusions, and release decisions through F010 or a human release process.

### Required Mechanism

- Consume RP AC inventory, AC classification, generated test cases, F007 raw execution evidence, expected-result artifacts, waiver records, manual-only approvals, and traceability links.
- Calculate coverage as covered automatable RP-level AC divided by total automatable RP-level AC.
- Exclude manual-only or waived AC only when approval records exist.
- Keep partial AC in the denominator unless split and reclassified.
- Keep blocked AC visible in the evidence package instead of silently dropping them.
- Link each evidence item to RP ID, AC ID, test case ID, and execution run ID.
- Include expected result, actual result, failure reason, and source artifact links for failed checks.
- Produce review-ready artifacts such as coverage report, traceability report, evidence index, failure summary, and release review summary.

## F009 — Advanced Spec Readiness

### Purpose

Detect deeper cross-artifact spec gaps and drift after the M1 vertical slice proves the basic readiness workflow.

### Expected Behavior

F009 is not part of M1. It extends readiness beyond basic folder, artifact, AC, and mapping completeness checks.

The advanced checker shall inspect product specs, RP feature specs, RP AC, architecture notes, RP/RU mapping, data assumptions, change records, existing tests, expected-result artifacts, and prior evidence to identify inconsistent or stale information before generation, execution, or release review.

F009 shall not author product specs, author RP feature specs, author RP AC, decide which RU repos belong to an RP, generate regression tests, generate expected results, execute tests, approve waivers, or decide release readiness.

### Required Mechanism

- Compare product intent, RP feature specs, RP AC, RP/RU mapping, architecture notes, change records, expected-result artifacts, generated tests, and prior evidence.
- Detect conflicting specs, duplicated or contradictory AC, missing inputs, missing outputs, missing owners, missing evidence duties, missing version references, and missing validation boundaries.
- Flag stale RP/RU mappings when RP scope, RU version references, or validation boundaries change without matching evidence updates.
- Flag product E2E scenarios that are not mapped to RP AC when they affect package-release confidence.
- Flag tests or expected-result artifacts that are not traceable to RP AC.
- Flag expected results or evidence that are older than the RP version, change record, or mapped RU version reference.
- Flag RP scope or product behavior changes without matching AC readiness, generated-test readiness, or evidence impact records.
- Produce `advanced_spec_readiness_report.md` and/or JSON with issue type, severity, affected product/RP/AC/RU artifact, reason, owner action, and required next step.

## F010 — Release Governance Integration

### Purpose

Connect regression evidence to release governance without fully automating release approval.

### Expected Behavior

The platform shall support release gate policy, waiver workflow, approval records, and Go/No-Go evidence summaries while keeping final release approval human-owned.

### Required Mechanism

- Consume coverage reports, evidence index, waiver records, and manual-only approvals.
- Apply configured release gate thresholds and blocking rules.
- Generate a Go/No-Go summary for release review.
- Require human approval for waiver acceptance and final release decision.
- Preserve approval records with the RP release evidence.

## F011 — Package Type Plugin SDK

### Purpose

Allow additional release package types to plug into the generic test execution process.

### Expected Behavior

The SDK shall define package adapter contracts, evidence contribution expectations, validation boundaries, and onboarding conventions for package types beyond the M1 data pipeline pilot.

### Required Mechanism

- Define the minimum adapter contract for execute, validate, collect evidence, and report errors.
- Define required metadata for package type registration.
- Provide onboarding rules for mapping package type behavior into the generic execution process.
- Require package adapters to emit standard evidence and failure records.
- Validate new package type plugins before they can be used in release evidence.
