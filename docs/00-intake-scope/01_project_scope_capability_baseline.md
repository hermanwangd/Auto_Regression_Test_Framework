# 01. Project Scope & Capability Baseline

## 1.1 Background

Product developers need reliable auto regression coverage for release packages, but current regression assets are often fragmented across project-specific scripts, ad hoc fixtures, manual expected-result checks, and release notes. This creates three recurring problems:

- Regression coverage is hard to measure and usually incomplete.
- Release package behavior can change without a clear trace from RP acceptance criteria to test evidence.
- Developers spend too much effort rebuilding test runners, package input loaders, assertions, and reports per project.

This project defines a product spec-driven Release Package Regression Framework. Product is a virtual product boundary, while release is performed by package release. The Product Repo must therefore provide the initialized source-of-truth structure for product specs, release package membership, RP acceptance criteria, regression artifacts, evidence, monitoring, and change workflow.

```text
Product Spec and RP Acceptance Criteria
-> Agent-generated Test Artifacts and Expected Results
-> Release Package Regression Framework
-> Coverage and Evidence Package
-> Release Review Input
```

## 1.2 Target User

The first real user is the product developer responsible for implementing and maintaining release package changes.

The platform should help this user:

- Understand which RP acceptance criteria are covered by automated regression tests.
- Generate draft test cases and expected results from approved RP acceptance criteria.
- Run repeatable regression checks against the release package's declared inputs, behavior, outputs, and evidence requirements.
- Produce evidence that can be reviewed by QA, release owners, or project stakeholders.

## 1.3 Objective

- Improve auto regression test coverage for the first release package pilot to greater than 80%.
- Build a reusable Release Package Regression Framework that can validate package-level behavior and evidence across project types.
- Allow the agent to generate expected results from RP acceptance criteria when the AC is explicit, reviewable, and traceable.
- Standardize test case DSL, package input catalog, package binding, assertions, expected outputs, evidence, and coverage reporting.
- Make every generated test artifact traceable back to RP acceptance criteria and source specs.
- Keep release approval human-owned even when test creation is agent-assisted.

## 1.4 Product, RP, and RU Role Model

| Level | Role | Owns | Does Not Own |
|---|---|---|---|
| Product | Virtual product boundary and development workflow grouping | Product intent, product specs, E2E scenarios, RP inventory, monitoring intent, change workflow | Release artifact or primary release AC |
| Release Package (RP) | Release artifact and development execution unit | RP scope, RP feature specs, RP acceptance criteria, RP/RU mapping, regression artifacts, evidence package, release readiness | Runtime implementation internals |
| Release Unit (RU) repo | Implementation workspace | Code, local tests, build artifacts, unit/component evidence | Primary product/RP specs or primary acceptance criteria |

Formal acceptance criteria are owned at the RP level. Product-level docs may define product intent and E2E scenarios, but they should not define the primary release acceptance denominator. RU repos provide implementation and evidence mapped back to RP-level AC.

## 1.5 Release Package Definition

A release package is a software package that includes one or more release units required to deliver product features.

For this framework, a release package must declare:

- Product feature scope and linked product spec context.
- RP-level acceptance criteria.
- Release units included in the package, such as services, jobs, data pipelines, schemas, configuration, scripts, or UI modules.
- Execution or validation boundary for each release unit.
- Inputs, expected behavior, outputs, and allowed side effects.
- Regression evidence required for release review.
- Environment assumptions, including test data, dependencies, and runtime constraints.

The M1 pilot uses a data pipeline release package as the first package type. The release package adapter may be package-type-specific, but the test execution process remains framework-core.

## 1.6 Product Repo Definition

The Product Repo owns development workflow specs for the virtual product boundary and its package releases. It is not required to contain runtime implementation code. Runtime implementation remains in RU repos.

The Product Repo should contain the product spec lifecycle structure:

- `00-intake-scope/`: product scope, baseline, and release package inventory.
- `01-specs/`: product specs and RP feature specs.
- `02-architecture/`: product and RP architecture, boundaries, and sequence flows.
- `03-acceptance/`: RP-level acceptance criteria and product E2E scenarios.
- `04-planning/`: implementation and release planning.
- `05-decisions-adr/`: architecture and product decision records.
- `06-reviews/`: spec, PR, release, and evidence review checklists.
- `07-validation-evidence/`: traceability, regression evidence, coverage, and validation reports.
- `08-release/`: release package readiness, go/no-go, and release records.
- `09-operations/`: monitoring, runtime checks, runbooks, and operational feedback.
- `10-change-control/`: change requests, impact analysis, waivers, and workflow records.
- `99-archive/`: retired specs, superseded release packages, and historical evidence.

The Product Repo maps the virtual product boundary to package-release execution:

```text
Virtual Product
-> Product Spec and E2E Scenarios
-> Release Packages as release artifacts
-> RP/RU repo mapping
-> Regression Test Cases, Data, Expected Results
-> Evidence Package
```

Product specs should go down to the Release Package level. Release Packages are the release and regression evidence units. RU repos are referenced through each release package's RP/RU mapping; they are not a separate product spec level.

## 1.7 Release Package Artifact Contract

Each RP should define these minimum artifacts in the Product Repo:

- `package.yaml`: RP identity, scope, owner, lifecycle status, target release, and package type.
- `rp_feature_spec.md`: RP feature behavior, source context, and package-level behavior boundaries.
- `rp_ru_mapping.yaml`: release units, repo references, owners, versions, dependency order, adapters, and evidence responsibilities.
- `acceptance_criteria.md`: RP-level AC used for generation, regression coverage, and release readiness.
- `tests/`: versioned RP regression test cases, including draft, approved, and retired test artifacts.
- `expected-results/`: reviewable expected-result artifacts used by approved tests.
- `traceability.md`: mapping from product spec context to RP AC, generated tests, evidence, and review state.
- `evidence_index.md`: evidence package manifest for coverage reports, execution reports, logs, diffs, waivers, and approvals.

## 1.8 RP/RU Mapping Contract

Each RP/RU mapping entry should include:

| Field | Purpose |
|---|---|
| `rp_id` | Release package identifier |
| `ru_id` | Release unit identifier inside the RP |
| `repo` | Source repository or implementation workspace |
| `unit_type` | Service, job, data pipeline, schema, config, script, UI module, or other package unit type |
| `owner` | Developer or team accountable for implementation evidence |
| `version_ref` | Branch, tag, commit, build artifact, or version used by the RP |
| `validation_boundary` | What the framework should execute or validate for this RU |
| `execution_mode` | Local fixture, CI ephemeral, SIT deployed, or evidence-only validation mode |
| `environment_ref` | Target environment, CI job, SIT namespace, endpoint set, or local fixture reference |
| `deployment_required` | Whether this RU must be deployed before RP regression execution |
| `adapter` | Release package adapter or adapter mode used for execution |
| `evidence_responsibility` | Evidence this RU must produce or contribute |
| `dependency_order` | Ordering constraints relative to other RUs |

## 1.8.1 Execution Environment Policy

RP regression execution may run in different modes. The framework shall not assume every RP requires SIT deployment, and it shall not assume local-only execution is enough for every RP.

| Execution Mode | Use When | Deployment Ownership |
|---|---|---|
| `local_fixture` | RP behavior can be validated with local fixtures, files, mocks, or deterministic package inputs | No SIT deployment required |
| `ci_ephemeral` | Multiple RUs can be built or composed in CI using temporary services, containers, schemas, or jobs | CI pipeline provisions and tears down the environment |
| `sit_deployed` | RP behavior depends on deployed RU services, jobs, schemas, configuration, or endpoints in SIT | CD or environment owner deploys RUs before regression |
| `evidence_only` | The framework cannot execute the RU directly, but approved build, unit, component, or manual evidence can support RP AC | RU owner provides mapped evidence |

For RPs that include multiple RUs, the RP/RU mapping must declare dependency order, execution mode, deployment requirement, environment reference, validation boundary, and evidence responsibility for each RU.

CI/CD boundary:

- CI may run readiness checks, artifact validation, agent generation, local fixture tests, and CI-ephemeral integration tests.
- CD may deploy RU versions to SIT or another target environment.
- SIT regression may run only after required RU versions and environment readiness are confirmed.
- The framework orchestrates regression execution and evidence collection, but it does not own CD deployment in M1.
- Evidence must record which RU versions, deployment references, environment references, and test case revisions were used.

## 1.9 AC Level and Coverage Policy

Formal AC are defined at RP level. Product-level E2E scenarios provide context and cross-RP confidence checks, but they are not the primary RP coverage denominator.

M1 auto regression coverage is calculated as:

```text
covered automatable RP-level AC / total automatable RP-level AC in release package scope
```

Rules:

- Manual-only AC require explicit approval before exclusion from the denominator.
- Waived AC require waiver records and approval before exclusion from the denominator.
- Partially covered AC remain in the denominator with status `partial`, unless split into smaller AC and reclassified.
- Product E2E scenarios may map to one or more RP AC, but they do not replace RP AC.
- Release unit tests count as evidence only when mapped back to RP AC.

## 1.10 Success Metrics

| Metric | M1 Target | Evidence |
|---|---:|---|
| Auto regression coverage | >80% of automatable RP-level AC in pilot RP scope | Coverage report mapped to RP AC IDs |
| Traceability | 100% of generated tests link to RP AC/source spec | Traceability matrix |
| Repeatability | Same input produces same result across repeated runs | Regression execution logs |
| Reviewability | Generated expected results can be inspected before merge | Artifact diff and review checklist |
| Failure visibility | Failed checks include reason, input, expected result, and actual result | Failure evidence report |

## 1.11 M1 Non-Functional Constraints

- Test execution should be deterministic for the same package inputs and expected results.
- M1 should support local or CI execution for one pilot RP without requiring a persistent service.
- SIT execution is required only when the RP validation boundary depends on deployed RU behavior that cannot be reproduced with local fixtures or CI-ephemeral composition.
- Fixture and sample data should be small enough to review and commit safely, or generated by a documented command.
- Production data must not be used unless masked and approved.
- Evidence artifacts should be retained with the RP release record.
- Test runs should avoid destructive operations unless explicitly approved.
- Local commands should remain bounded and avoid memory-heavy execution.

## 1.12 Non-goals

- Not replacing developer ownership of release package correctness.
- Not fully automating release approval.
- Not allowing agent-generated tests or expected results to merge without review.
- Not using production data without masking and approval.
- Not supporting every release package type in M1; M1 uses a data pipeline release package as the first pilot.
- Not building a full dashboard before the pilot proves value.
- Not owning CD deployment orchestration in M1; the framework consumes deployment and environment readiness evidence when SIT is required.
- Not executing destructive operations without explicit approval.

## 1.13 Milestone Definition

| Milestone | Meaning | Exit Criteria |
|---|---|---|
| M1 Release Package Vertical Slice | Product developer can generate, review, and run regression tests for one data pipeline release package pilot | Basic AC readiness gate, AC-driven test generation, expected-result generation, package input catalog, package adapter, assertions, coverage >80%, evidence package |
| M2 Framework Hardening | Framework supports repeatable adoption across additional release package types | Advanced spec readiness checker, schema validation, fixture lifecycle, reusable assertions, stable CLI, documented onboarding |
| M3 Governance Integration | Regression output supports release review and waiver decisions | P1/P2/P3 policy, approval flow, waiver record, Go/No-Go report |
| M4 Scale-out | Multiple projects can adopt the same framework beyond the first pilot | Plugin SDK, project templates, adapter examples, adoption guide |

## 1.14 Capability Baseline

| Capability ID | Capability | MVP | Owner | Status | Evidence |
|---|---|---:|---|---|---|
| CAP-001 | Product Repo Bootstrap CLI and Readiness Agent Skill | M1 | Platform / Agent Skill | Planned | Initialized docs lifecycle, machine-readable readiness report, and owner-actionable readiness explanation |
| CAP-002 | RP Creation Guide and Completeness Check | M1 | Platform | Planned | RP creation checklist and completeness report |
| CAP-003 | RP Feature Spec and AC Intake | M1 | Platform / Agent Skill | Planned | Parsed RP feature spec and RP AC inventory |
| CAP-004 | RP/RU Mapping Intake and Completeness Check | M1 | Platform | Planned | Validated RP/RU mapping report |
| CAP-005 | AC and Execution Context Readiness Agent Skill | M1 | Agent Skill | Planned | Generation readiness report |
| CAP-006 | Expected Result Drafting Agent Skill | M1 | Agent Skill | Planned | Reviewable expected-result artifact |
| CAP-007 | Test Case YAML DSL and Lifecycle | M1 | Platform | Planned | Schema validation and checked-in RP test folder policy |
| CAP-008 | Execution Environment Resolver | M1 | Platform | Planned | Execution mode and environment readiness report |
| CAP-009 | Package Input Catalog | M1 | Platform | Planned | Catalog schema and sample package inputs |
| CAP-010 | Package Binding Resolver | M1 | Platform | Planned | Binding unit tests |
| CAP-011 | Release Package Adapter | M1 | Platform | Planned | Package execution evidence |
| CAP-012 | Package Output Assertion Library | M1 | Platform | Planned | Assertion test report |
| CAP-013 | Package Fixture Lifecycle | M1 | Platform | Planned | Setup and cleanup evidence |
| CAP-014 | Release Package Test Execution | M1 | Platform | Planned | Execution report and raw evidence |
| CAP-015 | Coverage Reporter | M1 | Platform | Planned | AC coverage report showing >80% |
| CAP-016 | Evidence Reporter | M1 | Platform | Planned | Reviewable evidence package |
| CAP-017 | Advanced Spec Readiness Checker | M2 | Agent Skill | Planned | Advanced readiness report |
| CAP-018 | Failure Triage Agent | M3 | Agent Skill | Planned | Failure triage report |
| CAP-019 | Release Gate Engine | M3 | QA / Release | Planned | Go / No-Go evidence |
| CAP-020 | Waiver Process | M3 | Release | Planned | Waiver records |
| CAP-021 | Plugin SDK | M4 | Platform | Planned | Custom package type plugin example |

CAP-001 through CAP-016 are framework-core capabilities for M1. CAP-009 Package Input Catalog, CAP-010 Package Binding Resolver, and CAP-013 Package Fixture Lifecycle are part of the generic test execution process for all release package types. CAP-011 Release Package Adapter may have package-type-specific implementations, with the first M1 adapter focused on the data pipeline release package pilot.

## 1.15 AC Readiness and Fallback Policy

The agent may generate expected results only when the related RP acceptance criteria define observable inputs, behavior, and outputs.

When an AC is ambiguous or incomplete:

- The framework should mark it as `not_ready_for_generation`.
- The agent may generate a test skeleton, but not an expected result.
- A product developer must either clarify the AC, provide a manual expected result, or classify the AC as manual-only or waived.
- Manual-only and waived ACs require explicit approval before exclusion from coverage calculation.

## 1.16 Test Case Lifecycle and Storage Policy

Generated test cases are durable RP artifacts. The framework shall not regenerate test cases on every execution run.

Rules:

- Test generation is an explicit action triggered by new or changed RP AC, changed execution context, missing coverage, or owner request.
- Execution runs read checked-in test cases from the RP `tests/` folder.
- Draft test cases may be stored under `tests/draft/` until reviewed.
- Approved executable test cases should be stored under `tests/approved/` and reused across repeated runs.
- Superseded test cases should move to `tests/retired/` or be marked `retired` with replacement links.
- Each checked-in test case must reference RP ID, AC ID, test case ID, source artifact version, expected-result reference, and artifact status.
- Regeneration must preserve history by creating a new revision or replacement test case instead of silently overwriting approved tests.
- If an RP has a dedicated RP repository, the same `tests/` lifecycle applies there. In the current Product Repo model, the RP folder under `docs/08-release/release-packages/<rp_id>/` is the RP artifact location.

## 1.17 Key Assumptions to Validate

- RP acceptance criteria are explicit enough for the agent to derive expected results without inventing business rules.
- Product developers will trust generated artifacts if diffs, traceability, and review controls are clear.
- The first data pipeline release package can reach greater than 80% meaningful regression coverage with a small package adapter and fixture set.
- The first pilot has stable sample data or masked data that can be committed or regenerated safely.
- Coverage measured by RP AC mapping is a useful proxy for regression confidence.

## 1.18 Not Doing Yet

- UI regression support: excluded from M1 to keep the first release package pilot focused.
- Multi-project dashboard: deferred until the first pilot proves repeatable evidence value.
- Fully automated release decisions: excluded because release truth remains human-owned.
- Broad adapter marketplace: deferred until the release package adapter pattern stabilizes.
- Agent-only golden baseline approval: excluded because expected results must remain reviewable and auditable.
