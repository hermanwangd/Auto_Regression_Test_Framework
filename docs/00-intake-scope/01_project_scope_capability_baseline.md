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

The M1 pilot uses one heterogeneous release package as the adoption proof. The selected RP should include request/response interaction, asynchronous messaging, DB fixture setup/cleanup, K8s and VM readiness, and provider capability registry validation. External runner is optional and allowed only as an approved escape hatch when a reusable built-in provider cannot safely represent a legacy or specialized boundary. Package-specific behavior remains outside the framework core.

M1 is delivered through two gates:

| Gate | Proves | Does Not Prove |
|---|---|---|
| Framework Verification | The framework can validate artifacts, block unsafe execution, run local/mock provider paths, write evidence, and calculate sample coverage through `./mvnw test` and `./mvnw verify`. | Real downstream RP release readiness or native technology certification. |
| Heterogeneous Pilot Validation | An owner-provided Product Repo and RP can achieve greater than 80% automatable RP AC coverage using the selected provider contracts. | Broad support for every package type or every native provider. |

Framework verification may use local mocks, stub endpoints, fake brokers, local JDBC fixtures, and command/API stubs to prove reusable provider behavior. Native Kafka, NATS, gRPC, K8s, and VM provider runtimes are framework-verified where listed in the capability matrix, but downstream pilot acceptance still requires owner-provided RP artifacts, selected provider contracts, and real environment evidence.

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
- `rp_ru_mapping.yaml`: product-owned release unit mapping used by Agent Skills, not by the framework core directly.
- `acceptance_criteria.md`: RP-level AC used for generation, regression coverage, and release readiness.
- `tests/`: versioned RP regression test cases, including draft, approved, and retired test artifacts.
- `expected-results/`: reviewable expected-result artifacts used by approved tests.
- `generated-framework/`: Agent-generated framework-readable artifacts such as `suite_manifest.yaml`, `run_plan.yaml`, `environment_bindings/*.yaml`, provider contracts, and `traceability_map.yaml`.
- `traceability.md`: mapping from product spec context to RP AC, generated tests, evidence, and review state.
- `evidence_index.md`: evidence package manifest for coverage reports, execution reports, logs, diffs, waivers, and approvals.

## 1.8 Product Mapping and Framework Artifact Boundary

`rp_ru_mapping.yaml` is product knowledge. Product owners, SA, tech leads, or product developers use it to declare which RUs belong to an RP, which versions matter, and which validation boundary is intended. The generic framework core must not interpret product topology or decide which RU should use which runner.

Each RP/RU mapping entry should include enough information for the Agent Skill to generate framework-readable artifacts:

| Field | Purpose |
|---|---|
| `rp_id` | Release package identifier |
| `ru_id` | Release unit identifier inside the RP |
| `repo` | Source repository or implementation workspace |
| `unit_type` | Service, job, data pipeline, schema, config, script, UI module, or other package unit type |
| `owner` | Developer or team accountable for implementation evidence |
| `version_ref` | Branch, tag, commit, build artifact, or version used by the RP |
| `validation_boundary` | What the Agent Skill should translate into logical framework targets and tests |
| `execution_mode` | Local fixture, CI ephemeral, SIT deployed, or evidence-only validation mode |
| `environment_ref` | Target environment, CI job, SIT namespace, endpoint set, or local fixture reference |
| `deployment_required` | Whether this RU must be deployed before RP regression execution |
| `adapter` | Candidate runner or provider mode for Agent Skill translation |
| `evidence_responsibility` | Evidence this RU must produce or contribute |
| `dependency_order` | Product topology hint; generated `run_plan.yaml` must express executable target dependencies |

The Agent Skill translates product mapping into:

- `suite_manifest.yaml`: selected tests, trace labels, and coverage source refs.
- `run_plan.yaml`: logical target dependency graph, run profile, and selected execution mode.
- `environment_binding.yaml`: logical targets bound to runner/provider contracts for one environment.
- `provider_contracts/*.yaml`: reusable runner, fixture, verify, and evidence provider contracts.
- `traceability_map.yaml`: opaque product/RP/RU/source labels used for reporting, not runtime branching.
- `mapping_explanation.md` or `.yaml`: strategy selection reason, selected runner/profile, source facts, unresolved assumptions, and validation warnings for human review.

## 1.8.1 Execution Environment Policy

RP regression execution may run in different modes. Product topology determines which mode is appropriate, but the Agent Skill records the selected mode in framework-readable run profiles and environment bindings. The framework shall not infer deployment need from RP/RU names or product topology.

| Execution Mode | Use When | Deployment Ownership |
|---|---|---|
| `local_fixture` | RP behavior can be validated with local fixtures, files, mocks, or deterministic package inputs | No SIT deployment required |
| `ci_ephemeral` | Multiple RUs can be built or composed in CI using temporary services, containers, schemas, or jobs | CI pipeline provisions and tears down the environment |
| `sit_deployed` | RP behavior depends on deployed RU services, jobs, schemas, configuration, or endpoints in SIT | CD or environment owner deploys RUs before regression |
| `evidence_only` | The framework cannot execute the RU directly, but approved build, unit, component, or manual evidence can support RP AC | RU owner provides mapped evidence |

For RPs that include multiple RUs, the RP/RU mapping must declare enough topology for the Agent Skill to generate explicit target dependencies, execution mode, deployment readiness refs, environment binding, and evidence responsibilities.

CI/CD boundary:

- CI may run readiness checks, artifact validation, agent generation, local fixture tests, and CI-ephemeral integration tests.
- CD may deploy RU versions to SIT or another target environment.
- SIT regression may run only after the generated environment binding references readiness evidence for the selected deployed targets.
- The framework orchestrates regression execution and evidence collection, but it does not own CD deployment in M1.
- Evidence may retain RP/RU/version labels as opaque traceability metadata, but framework decisions must use generated target IDs, provider contracts, run profiles, and environment bindings.

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

## 1.9.1 DSL and 7 AP Baseline Model

The regression DSL is the checked-in RP-level test contract. In one sentence: it is the durable description of what RP behavior is validated and how the framework shall execute and judge that validation repeatably. It states which RP behavior is validated, which AC it traces to, what input or state is needed, what logical action is performed, what oracle/assertion decides pass/fail, and what evidence must be retained.

The DSL is not a BDD-only story, package-specific script, provider configuration file, secret store, generated run log, or replacement for owner-authored RP AC. Provider-specific details such as endpoints, commands, DB loaders, queue clients, credentials, and adapter implementation settings belong in generated provider contracts or environment bindings.

The 7 AP are framework processing areas. In one sentence: they are the lifecycle responsibility boundaries that convert reviewed RP artifacts into executable and reviewable regression evidence. An AP is not necessarily a separate Java package, deployable service, or RP-specific plugin.

The baseline ownership model is:

| Item | Owner | Purpose |
|---|---|---|
| RP feature spec and RP AC | Product owner, PM, SA, or RP owner | Define business behavior and acceptance truth. |
| RP/RU mapping | RP/RU owner or platform owner | Declare product topology and release boundary for Agent Skill translation. |
| Generated framework artifacts | Agent Skill with reviewer approval | Translate product topology into suite manifest, run plan, environment bindings, provider contracts, and traceability map. |
| DSL test case | Product developer or agent-assisted reviewer | Preserve reviewed regression validation intent as a repeatable artifact. |
| Expected result or oracle truth | RP owner or delegated reviewer | Approve the truth source used by assertions. |
| 7 AP execution flow | Framework | Validate, plan, bind, execute, assert, and report without inventing business truth. |

| AP | Consumes | Produces | Clear Boundary |
|---|---|---|---|
| Definition and Validation | DSL files, suite manifest, run plan, environment bindings, expected-result artifacts | Schema, lifecycle, approval, and compatibility findings | Validates framework-readable artifacts; does not infer business truth. |
| Discovery and Context | Suite manifest, run plan, environment binding, requested profile | Resolved execution context, target list, artifact paths, traceability labels | Finds declared context; does not understand product topology. |
| Planning and Binding | DSL tests, parameters, package inputs, provider contracts | Execution plan with resolved bindings and step placeholders | Resolves logical references; does not execute package behavior. |
| Fixture and State Manager | Preconditions, fixture setup/cleanup, lifecycle policy | Prepared state, cleanup plan, cleanup evidence | Owns state safety; does not hide destructive actions. |
| Execution Engine | Execution plan, adapter contracts, environment policy | Step results, adapter outputs, logs, runtime metadata | Executes configured actions; does not embed RP-specific logic. |
| Oracle and Assertion Engine | Actual outputs, approved truth sources, assertion rules | Pass/fail decisions with expected, actual, and reason | Evaluates evidence; does not approve truth. |
| Evidence and Reporting | Outputs from all APs, traceability, waiver records | Durable evidence, coverage, failure, and release-review reports | Reports readiness; does not decide release approval. |

Ownership split:

- RP owners define feature behavior, RP AC, RP/RU membership, and release decisions.
- Agent skills translate product topology into framework-readable artifacts and may draft DSL tests or expected results only from ready owner-authored context.
- The DSL owns validation intent and logical references.
- Generated run plans, environment bindings, and provider contracts own executable configuration.
- The 7 AP own deterministic validation, planning, execution orchestration, assertion evaluation, and evidence production over generated framework-readable artifacts only.

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
- Not supporting every release package type or technology provider natively in M1; the first pilot proves the provider model on one selected heterogeneous RP.
- Not building a full dashboard before the pilot proves value.
- Not owning CD deployment orchestration in M1; the framework consumes deployment and environment readiness evidence when SIT is required.
- Not executing destructive operations without explicit approval.

## 1.13 Milestone Definition

| Milestone | Meaning | Exit Criteria |
|---|---|---|
| M1 Release Package Vertical Slice | Product developer can generate, review, and run regression tests for one heterogeneous release package pilot | Basic AC readiness gate, AC-driven test generation, expected-result generation, package input catalog, provider-dispatched execution, assertions, coverage >80%, evidence package |
| M2 Framework Hardening | Framework supports repeatable adoption across additional release package types | Advanced spec readiness checker, schema validation, fixture lifecycle, reusable assertions, stable CLI, documented onboarding |
| M3 Governance Integration | Regression output supports release review and waiver decisions | P1/P2/P3 policy, approval flow, waiver record, Go/No-Go report |
| M4 Scale-out | Multiple projects can adopt the same framework beyond the first pilot | Plugin SDK, project templates, adapter examples, adoption guide |

## 1.13.1 Current Acceptance Boundary

Current framework verification can be accepted only for the framework capabilities covered by Maven tests and sample Product Repo fixtures. It is not enough to declare the M1 heterogeneous pilot accepted.

| Area | Current Framework Status | Pilot Acceptance Requirement |
|---|---|---|
| File/batch execution | Supported through bounded shell/file provider contracts. | Use when selected RP has file, CLI, batch, or pipeline-style validation. |
| REST/gRPC request/response | Supported through configurable REST contracts and native descriptor-driven gRPC unary contracts. | Pilot must prove selected endpoint behavior against owner-provided RP artifacts and environment refs. |
| Messaging | Supported through local/mock messaging plus native Kafka/NATS publish, consume/observe, and bounded cleanup drain contracts. | Pilot must prove selected broker behavior against owner-provided Kafka/NATS environment refs. |
| DB fixture | Supported through JDBC setup, verification query, cleanup SQL refs, cleanup strategy, isolation key evidence, and DB row count assertions. | Pilot must prove state isolation and cleanup on real selected DB fixture boundary. |
| Deployment readiness | Supported through local/mock readiness plus native K8s and VM bounded readiness probes, K8s direct API availability, pod log capture, and VM SSH/WinRM command probes. | Pilot must prove selected K8s/VM readiness against owner-provided environments. |
| External runner | Supported as an approved escape hatch with approval metadata, timeout, inputs, outputs, evidence map, and mapped-artifact checks. | Use only when built-in providers cannot safely represent the selected legacy or specialized boundary. |

## 1.14 Capability Baseline

| Capability ID | Capability | MVP | Owner | Status | Evidence |
|---|---|---:|---|---|---|
| CAP-001 | Product Repo Bootstrap CLI and Readiness Agent Skill | M1 | Platform / Agent Skill | Framework verified | Initialized docs lifecycle, machine-readable readiness report, and owner-actionable readiness explanation |
| CAP-002 | RP Creation Guide and Completeness Check | M1 | Platform | Framework verified | RP creation checklist and completeness report |
| CAP-003 | RP Feature Spec and AC Intake | M1 | Platform | Framework verified | Parsed RP feature spec and RP AC inventory |
| CAP-004 | Agent Skill Product Mapping Translation | M1 | Agent Skill / Platform | Framework contract verified | Product mapping translated into suite manifest, run plan, environment binding, provider contracts, and traceability map |
| CAP-005 | AC and Execution Context Readiness Service / Future Agent Skill | M1 | Platform / Agent Skill | Partial | Java readiness and DSL draft service is framework-tested; packaged agent skill artifact remains pending |
| CAP-006 | Expected Result Drafting Service / Future Agent Skill | M1 | Platform / Agent Skill | Partial | Java expected-result draft and approval-gate service is framework-tested; packaged agent skill artifact remains pending |
| CAP-007 | Test Case YAML DSL and Lifecycle | M1 | Platform | Framework verified | Schema validation and checked-in RP test folder policy |
| CAP-008 | Execution Environment Resolver | M1 | Platform | Framework verified | Execution mode and environment readiness report |
| CAP-009 | Package Input Catalog | M1 | Platform | Partial | Supported binding types are `input_file`, `dataset`, `db_seed`, `api_payload`, and `message_event`; `config_file`, `env_var`, and `existing_state` remain pending |
| CAP-010 | Package Binding Resolver | M1 | Platform | Partial | Binding unit tests and dry-run gaps for supported types; additional pilot bindings pending |
| CAP-011 | Release Package Adapter | M1 | Platform | Framework verified | File/batch, REST/gRPC unary, local/mock plus Kafka/NATS messaging, JDBC fixture, local/mock plus K8s/VM readiness, and approved external runner evidence |
| CAP-012 | Package Output Assertion Library | M1 | Platform | Framework verified | File diff, expected-result artifact checks, HTTP/status field checks, JSON path equality and absence checks, numeric tolerance checks, schema/contract checks, and DB row count assertions are framework-tested |
| CAP-013 | Package Fixture Lifecycle | M1 | Platform | Framework verified | DB setup/query/cleanup evidence and cleanup policy checks |
| CAP-014 | Release Package Test Execution | M1 | Platform | Framework verified | Batch/run execution report and raw evidence for current provider set |
| CAP-015 | Coverage Reporter | M1 | Platform | Framework verified | Batch-level AC coverage report; real pilot >80% remains pending owner artifacts |
| CAP-016 | Evidence Reporter | M1 | Platform | Framework verified | Reviewable evidence package for sample/local framework verification flows |
| CAP-017 | Advanced Spec Readiness Checker | M2 | Agent Skill | Planned | Advanced readiness report |
| CAP-018 | Failure Triage Agent | M3 | Agent Skill | Planned | Failure triage report |
| CAP-019 | Release Gate Engine | M3 | QA / Release | Planned | Go / No-Go evidence |
| CAP-020 | Waiver Process | M3 | Release | Planned | Waiver records |
| CAP-021 | Plugin SDK | M4 | Platform | Planned | Custom package type plugin example |

Status meanings:

- `Framework verified`: implemented and covered by framework Maven tests or sample fixture verification.
- `Partial`: implemented for the current local/mock or supported provider subset, with explicitly listed gaps.
- `Pilot pending`: requires owner-provided pilot RP artifacts or native provider implementation before acceptance.
- `Planned`: not part of the current implemented M1 framework verification slice.

CAP-001 through CAP-016 are framework-core capabilities for M1. CAP-009 Package Input Catalog, CAP-010 Package Binding Resolver, and CAP-013 Package Fixture Lifecycle are part of the generic test execution process for all release package types. CAP-011 Release Package Adapter is implemented through provider families and adapters; the first M1 adoption proof focuses on the selected heterogeneous RP rather than a single package type.

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
- The first heterogeneous release package can reach greater than 80% meaningful regression coverage with a reusable provider contract model and a bounded provider set.
- The first pilot has stable sample data or masked data that can be committed or regenerated safely.
- Coverage measured by RP AC mapping is a useful proxy for regression confidence.

## 1.18 Not Doing Yet

- UI regression support: excluded from M1 to keep the first release package pilot focused.
- Multi-project dashboard: deferred until the first pilot proves repeatable evidence value.
- Fully automated release decisions: excluded because release truth remains human-owned.
- Broad adapter marketplace: deferred until the release package adapter pattern stabilizes.
- Agent-only golden baseline approval: excluded because expected results must remain reviewable and auditable.
